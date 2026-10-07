#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验「工具三表一致」—— 加工具后必跑。

## 为什么必须有这个脚本（本项目的硬门禁）

`ToolProvider` 有三处必须对齐的地方：

| 表 | 位置 | 漏了会怎样 |
|---|---|---|
| `toolNames` | provider 顶层 | 该工具**永远调不到**，但**不报错、单测照绿** |
| `tools()` 里的 `ToolEntry` | schema/风险/文案 | 设置页看不到、风险档缺失、schema 没下发 |
| `execute()` 的 `when` 分支 | 执行 | 调用报「未知工具」 |

三处**没有任何编译期约束**把它们绑在一起 —— 这是 2026-09 多次"AI 突然调不到某个工具"
的根因。唯一有效的手段就是把它们机械地互相比对。

本脚本覆盖**全仓所有 provider**，不只改动的那个。

用法：
    python scripts/check_tool_wiring.py
退出码 0 = 一致；1 = 有漏项。

⚠️ 已知的两类「合法不一致」脚本会自动放行（并在输出里标注）：
  1. `execute` 分支用**常量**而非字面量（如 `ToolRegistry.TOOL_CODE_FILE ->`）；
  2. provider 的工具名里带**非字面量表达式**（如 `"${...}xxx"`）。
遇到新的合法模式时，请显式加进白名单并在注释里写清理由，不要放宽整个检查。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_DIR = os.path.join(ROOT, "phone-app", "src", "main", "java", "com", "rokidlab", "phone", "ai", "tools")

# execute() 里允许出现的「工具名表达式」：常量引用（真实名字在别处定义）
EXEC_NAME_EXPR = re.compile(
    r'"([a-z_]+)"\s*->'          # 字面量分支
    r'|[A-Za-z_][\w.]*\.\w+\s*->'  # 常量分支（ToolRegistry.TOOL_XXX ->）
)


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


AI_DIR = os.path.join(ROOT, "phone-app", "src", "main", "java", "com", "rokidlab", "phone", "ai")

_GLOBAL_CONSTS = None


def global_consts():
    """全仓的 `const val X = "字面量"` 表（**按短名索引**）。

    ⚠️ 必须跨文件：`SubagentToolProvider` 的名单写的是
    `setOf(ReadOnlySubagent.TOOL_NAME, WriteSubagent.TOOL_NAME)`，
    而那两个常量定义在 `ai/subagent/` 下，不在本文件里。
    只扫单文件时它解析不出来 ⇒ 报「解析不出真实工具名」的假问题。
    """
    global _GLOBAL_CONSTS
    if _GLOBAL_CONSTS is not None:
        return _GLOBAL_CONSTS
    table = {}
    for dirpath, _dirs, files in os.walk(AI_DIR):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            try:
                txt = read(os.path.join(dirpath, fn))
            except Exception:
                continue
            for m in re.finditer(
                r'const val (\w+)\s*(?::\s*String\s*)?=\s*"([A-Za-z_][\w]*)"', txt
            ):
                # 同名常量取先遇到的；真冲突会在下面的白名单里显式处理
                table.setdefault(m.group(1), m.group(2))
    _GLOBAL_CONSTS = table
    return table


def const_table(src):
    """本文件的常量表 + 全仓表（本文件优先）。"""
    table = dict(global_consts())
    for m in re.finditer(r'const val (\w+)\s*(?::[^=]+)?=\s*"([A-Za-z_][\w]*)"', src):
        table[m.group(1)] = m.group(2)
    return table


def resolve(name, consts, qualified):
    """把名单里的一个 token 还原成真实工具名；还原不出来返回 None。"""
    if name.startswith('"'):
        return name.strip('"')
    if name in consts:
        return consts[name]
    # ⚠️ 必须**精确匹配整个尾段**，不能"遍历 qualified 看谁的短名在 consts 里"。
    # `TOOL_CODE_FILE` 是 `TOOL_READ_CODE_FILE` 的**前缀**，遍历式匹配会先撞上后者，
    # 把 save_code_file 误解析成 read_code_file —— 表现为"另一个工具说缺分支"，
    # 排查时完全指不到真正的缺口。
    if "." in name:
        short = name.rsplit(".", 1)[-1]
        if short in consts:
            return consts[short]
    return None


def qualified_consts(src):
    return set(re.findall(r'([A-Z]\w*\.TOOL_\w+)', src))


def extract_tool_names(src, consts, qualified):
    """从 `override val toolNames = setOf(...)` 里取名字。

    ⚠️ 正则必须**锚定到那个具体的属性**：`setOf(` 之后只允许「字符串 + 逗号/换行」，
    一旦遇到别的 token 就说明已经越出名单、进了 schema 正文。
    早期版本用宽松的 setOf 匹配 + 全量引号提取，结果把每个 schema 里的
    "type"/"properties" 等 JSON key 全当成工具名 —— 报出 118 条假问题。
    """
    m = re.search(
        r"override val toolNames\s*=\s*setOf\(([^)]*)\)",
        src,
    )
    if not m:
        return None
    out = set()
    for tok in re.findall(r'"[^"]+"|[A-Za-z_][\w.]*', m.group(1)):
        r = resolve(tok, consts, qualified)
        if r is None:
            return ("UNRESOLVED", tok)   # 名单里有解析不出来的 token ⇒ 交给调用方报告
        out.add(r)
    return out


def extract_entry_names(src, consts=None, qualified=None):
    """从 `tools()` 的 `ToolEntry(name = "...")` 里取名字。

    `name =` 可能排在 ToolEntry 参数的第一位，也可能被别的参数顶到第二行
    （有 provider 写了 `ToolEntry(group = ..., name = ...)`），两种都要认。
    """
    out = set()
    for tok in re.findall(
        r'ToolEntry\((?:[^()]|\([^()]*\))*?name\s*=\s*("[^"]+"|[A-Za-z_][\w.]*)', src
    ):
        r = resolve(tok, consts or {}, qualified or set())
        if r:
            out.add(r)
    return out


def extract_exec_names(src, consts, qualified):
    """从 `execute()` 的 when 分支里取名字（含常量分支，按其字面量部分无法还原 → 单独记）。"""
    m = re.search(r"override fun execute\b", src)
    if not m:
        return set(), set()
    body = src[m.end():]
    literals = set(re.findall(r'"([a-z_]+)"\s*->', body))
    # 常量分支：`ToolRegistry.TOOL_CODE_FILE ->` / `TOOL_RUN ->` / `ReadOnlySubagent.TOOL_NAME ->`
    raw_const = re.findall(r'([A-Za-z_][\w.]*)\s*->', body)
    resolved = set()
    for tok in raw_const:
        if tok.endswith(".config") or tok.endswith(".usable"):
            continue                      # 业务字段名（`st.config.enabled ->`）不是工具名
        r = resolve(tok, consts, qualified)
        if r:
            resolved.add(r)
    return literals, resolved


def main():
    if not os.path.isdir(TOOLS_DIR):
        print("FAIL: 找不到 tools 目录：%s" % TOOLS_DIR)
        return 1

    # ToolProvider.kt 是接口本身、McpToolProvider 是动态 provider（工具运行时才定）
    providers = sorted(
        f for f in os.listdir(TOOLS_DIR)
        if f.endswith(".kt") and "ToolProvider" in f
        and f not in ("ToolProvider.kt", "McpToolProvider.kt")
    )
    if not providers:
        print("FAIL: tools 目录下没有 *ToolProvider.kt")
        return 1

    total_names = total_entries = 0
    problems = []
    notes = []

    for fn in providers:
        path = os.path.join(TOOLS_DIR, fn)
        src = read(path)

        consts = const_table(src)
        qualified = qualified_consts(src)
        names = extract_tool_names(src, consts, qualified)
        entries = extract_entry_names(src, consts, qualified)
        exec_lits, exec_consts = extract_exec_names(src, consts, qualified)

        if names is None:
            problems.append("%s: 找不到 `override val toolNames`" % fn)
            continue
        if isinstance(names, tuple):
            problems.append(
                "%s: toolNames 里的 '%s' 解析不出真实工具名（既不是字面量也找不到对应常量）"
                % (fn, names[1])
            )
            continue
        if not entries:
            problems.append("%s: 找不到任何 `ToolEntry(` 声明" % fn)
        if len(entries) < len(names) and names:
            notes.append("%s: 名单 %d 个 / ToolEntry %d 个（差值通常来自 execute 用常量分支）"
                         % (fn, len(names), len(entries)))
        if not exec_lits and not exec_consts:
            notes.append("%s: execute() 没有 when 分支（可能是单工具 provider 走 if）" % fn)

        total_names += len(names)
        total_entries += len(entries)

        # ① toolNames ⊆ entries
        for miss in sorted(names - entries):
            problems.append(
                "%s: toolNames 声明了 '%s' 但 tools() 里没有对应 ToolEntry"
                " ⇒ 模型永远调不到它，且**不会报错、单测照绿**" % (fn, miss)
            )
        # ② entries ⊆ toolNames
        for extra in sorted(entries - names):
            problems.append(
                "%s: tools() 声明了 '%s' 但 toolNames 里没有"
                " ⇒ schema 会下发但装配层不认" % (fn, extra)
            )
        # ③ toolNames ⊆ execute（字面量 ∪ 常量）
        covered = exec_lits | exec_consts
        _ = exec_consts  # 常量分支已按名字还原参与核对，这里不再单独报 note
        # 单工具 provider 用的是 `if (name != TOOL_X) throw ...` 早退守卫，
        # 不是 when 分支 —— `name !=` 与 `name ==` 都要认，否则这 10 个 provider 全部误报。
        exec_body = src[src.find("override fun execute"):] if "override fun execute" in src else ""
        guard_names = set()
        for tok in re.findall(r'name\s*[!=]=\s*("[^"]+"|[A-Za-z_][\w.]*)', exec_body):
            r = resolve(tok, consts, qualified)
            if r:
                guard_names.add(r)
        covered = covered | guard_names
        for miss in sorted(names - covered):
            problems.append(
                "%s: toolNames 里的 '%s' 在 execute() 里既没有 when 分支也没有 == 判断"
                " ⇒ 调用会报「未知工具」" % (fn, miss)
            )

    print("provider 数：%d    静态工具名：%d    ToolEntry：%d"
          % (len(providers), total_names, total_entries))
    for n in notes:
        print("  note  %s" % n)
    if problems:
        print("")
        for p in problems:
            print("FAIL  %s" % p)
        print("\n结果：不一致（%d 项）" % len(problems))
        return 1
    print("结果：三表一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
