"""把 lobehub icons 的单色 SVG 转成 Android VectorDrawable（零依赖、可 tint）。

来源：https://github.com/lobehub/lobe-icons （MIT）—— 与 rikkahub 用的是同一套图标资产
（其 AIIconMatcher.kt 注释就写了 // https://lobehub.com/zh/icons）。

为什么转 VectorDrawable 而不是像 rikkahub 那样运行时渲染 SVG：
Lab 没有 Coil / SVG 解码器，加依赖不值得；这些图标都是「单色 currentColor + 纯 path」，
转成 VectorDrawable 后体积 1-3KB、矢量清晰、用 Compose 的 Icon(tint=品牌色) 就能着色，
和现有「彩色圆底 + 首字母」头像的视觉语言一致（单色图标 + accent 色）。
"""
import re
import sys
from pathlib import Path

SRC = Path(sys.argv[1])          # 下载好的 svg 目录
DST = Path(sys.argv[2])          # res/drawable 目录

# 我们的品牌 id → lobe 图标文件名
MAPPING = {
    "deepseek": "deepseek.svg",
    "moonshot": "kimi.svg",
    "dashscope": "qwen.svg",
    "zhipu": "zhipu.svg",
    "volces": "doubao.svg",
    "siliconflow": "siliconcloud.svg",
    "openai": "openai.svg",
    "anthropic": "anthropic.svg",
    "gemini": "gemini.svg",
    "xai": "xai.svg",
    "openrouter": "openrouter.svg",
}

HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<!--\n"
    "  品牌图标：来自 lobehub/lobe-icons（MIT License），https://github.com/lobehub/lobe-icons\n"
    "  由 SVG 单色版（fill=currentColor + 纯 path）转换为 VectorDrawable；\n"
    "  统一 fillColor 为黑，运行时由 Compose Icon(tint=品牌/accent 色) 着色。\n"
    "  维护：新增品牌请同时更新 store/ProviderIcons.kt 的映射与 NOTICE。\n"
    "-->\n"
)

path_re = re.compile(r"<path\b([^>]*?)/?>", re.S)
attr_re = re.compile(r'([\w:-]+)\s*=\s*"([^"]*)"')


def convert(svg_text: str) -> str:
    vb = re.search(r'viewBox\s*=\s*"([^"]+)"', svg_text)
    if not vb:
        raise ValueError("no viewBox")
    nums = [float(x) for x in re.split(r"[ ,]+", vb.group(1).strip())]
    vw, vh = nums[2], nums[3]

    paths = []
    for m in path_re.finditer(svg_text):
        attrs = dict(attr_re.findall(m.group(1)))
        d = attrs.get("d", "").strip()
        if not d:
            continue
        fill_type = ""
        if attrs.get("fill-rule") == "evenodd" or attrs.get("clip-rule") == "evenodd":
            fill_type = '\n        android:fillType="evenOdd"'
        paths.append(
            '    <path\n'
            f'        android:fillColor="#FF000000"{fill_type}\n'
            f'        android:pathData="{d}" />'
        )
    if not paths:
        raise ValueError("no usable path")

    return (
        HEADER
        + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        + '    android:width="24dp"\n'
        + '    android:height="24dp"\n'
        + f'    android:viewportWidth="{vw:g}"\n'
        + f'    android:viewportHeight="{vh:g}">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    ok, fail = 0, []
    for brand, svg_name in MAPPING.items():
        src = SRC / svg_name
        try:
            xml = convert(src.read_text(encoding="utf-8"))
            out = DST / f"provider_icon_{brand}.xml"
            out.write_text(xml, encoding="utf-8")
            print(f"OK   {brand:12s} <- {svg_name:20s} {out.stat().st_size:5d} B")
            ok += 1
        except Exception as e:  # noqa: BLE001
            fail.append(f"{brand}: {e}")
            print(f"FAIL {brand:12s} <- {svg_name:20s} {e}")
    print(f"\nconverted={ok} failed={len(fail)}")


if __name__ == "__main__":
    main()
