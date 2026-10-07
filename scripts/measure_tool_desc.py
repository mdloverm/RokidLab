#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测量工具 schema 的真实体量构成：工具级 desc vs 参数级 desc vs 结构性开销。

背景（2026-10-07）：此前「desc 占 schema 64%、可压 26%」是估算，实测不成立。
本脚本给准数，并区分两类desc：
  - 判据类：回答「何时调用这个工具」，压缩需谨慎
  - 教材类：把语法/用法手册塞进 desc（如save_code_file 里的 AIUI SFC 规范、
    Lab.callTool 回调写法），**每轮都在背**，是纯浪费

用法：python scripts/measure_tool_desc.py [top_n]
"""
import re
import sys
import pathlib

TOOLS_DIR = (pathlib.Path(__file__).resolve().parent.parent
             / "phone-app/src/main/java/com/rokidlab/phone/ai/tools")

DESC_RE = re.compile(r'description\s*=\s*((?:\s*"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', re.S)
STR_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
NAME_RE = re.compile(r'ToolEntry\(\s*name\s*=\s*"([^"]+)"')
# 参数级 description："key" to mapOf(..., "description" to "...")
PARAM_DESC_RE = re.compile(r'"description"\s*to\s*"((?:[^"\\]|\\.)*)"')

# 教材类信号词：命中即高度可疑（这些是「怎么用」的正文，不是「何时用」的判据）
TEXTBOOK_HINTS = (
    "必须", "禁止", "严禁", "不得", "只能", "一律", "切勿", "务必",
    "写法", "结构", "模板", "示例", "如 app.json", "四块", "回调",
)

def est_tokens(s: str) -> float:
    cjk = sum(1 for c in s if '\u4e00' <= c <= '\u9fff')
    return cjk * 0.75 + (len(s) - cjk) * 0.3

def unescape(s: str) -> str:
    return s.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')

def extract(block: str):
    dm = DESC_RE.search(block)
    tool_desc = unescape("".join(STR_RE.findall(dm.group(1)))) if dm else ""
    params = [unescape(m) for m in PARAM_DESC_RE.findall(block)]
    return tool_desc.strip(), params

def main() -> int:
    top_n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    rows = []
    for kt in sorted(TOOLS_DIR.glob("*.kt")):
        src = kt.read_text(encoding="utf-8")
        for m in NAME_RE.finditer(src):
            name = m.group(1)
            block = src[m.end(): m.end() + 3000]
            # 只取到下一个 ToolEntry 为止
            nxt = block.find("ToolEntry(")
            if nxt > 0:
                block = block[:nxt]
            tool_desc, params = extract(block)
            if not tool_desc:
                continue
            flags = [h for h in TEXTBOOK_HINTS if h in tool_desc]
            rows.append({
                "name": name, "file": kt.name, "desc": tool_desc,
                "d_tok": est_tokens(tool_desc),
                "p_tok": sum(est_tokens(p) for p in params),
                "n_params": len(params),
                "hints": len(flags),
            })

    if not rows:
        print("!! 没解析出 description", file=sys.stderr)
        return 1

    d_total = sum(r["d_tok"] for r in rows)
    p_total = sum(r["p_tok"] for r in rows)
    grand = d_total + p_total
    print(f"带内联 desc 的工具: {len(rows)}")
    print(f"  工具级 desc: {d_total:>8,.0f} token  ({d_total/grand*100:.0f}%)")
    print(f"  参数级 desc: {p_total:>8,.0f} token  ({p_total/grand*100:.0f}%)，共 {sum(r['n_params'] for r in rows)} 个参数")
    print(f"  合计: {grand:,.0f} token")
    print()

    print(f"=== 按「工具级 desc + 参数 desc」排序 top {top_n} ===")
    print(f"{'工具名':<32}{'字符':>6}{'desc tok':>10}{'参数 tok':>10}{'教材词':>7}")
    for r in sorted(rows, key=lambda r: -(r["d_tok"] + r["p_tok"]))[:top_n]:
        mark = f"{r['hints']}" if r["hints"] else "-"
        print(f"{r['name']:<32}{len(r['desc']):>6}{r['d_tok']:>10.0f}"
              f"{r['p_tok']:>10.0f}{mark:>7}")

    # 教材类嫌疑（命中信号词密度高的）
    sus = [r for r in rows if r["hints"] >= 4]
    sus.sort(key=lambda r: -r["d_tok"])
    print()
    print(f"=== 疑似「教材塞进desc」工具（信号词≥4，共 {len(sus)} 个，"
          f"{sum(r['d_tok'] for r in sus):,.0f} token）===")
    for r in sus:
        print(f"  {r['name']:<32}{r['d_tok']:>6.0f} tok  信号词×{r['hints']}")
    return 0

if __name__ == "__main__":
    sys.exit(main())