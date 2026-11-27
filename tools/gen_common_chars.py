#!/usr/bin/env python3
"""生成常用字表 assets/common_chars.txt。

判据：GB2312 一级汉字（3755 个，区 0xB0–0xD7），这是经典的「最常用字」界定。
- 在一级字表 → 常用字，简单鹤候选置顶
- 不在（含 GB2312 二级、GBK 扩展、CJK 扩展区的生僻字）→ 生僻字，让位给豆包首选

模块用它在候选合并时决定顺序：
    四码 + 简单鹤首选是生僻单字 → 豆包首选占第 1 位，生僻字排第 2
    其余情况（常用字）→ 简单鹤首选照常置顶

用法：
    python3 tools/gen_common_chars.py assets/common_chars.txt
"""
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "assets/common_chars.txt"


def level1() -> list:
    chars = []
    for hi in range(0xB0, 0xD8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except Exception:
                continue
            if len(ch) == 1:
                chars.append(ch)
    return chars


def main():
    chars = level1()
    # 去重并保持顺序
    seen = set()
    uniq = []
    for c in chars:
        if c in seen:
            continue
        seen.add(c)
        uniq.append(c)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("".join(uniq) + "\n")
    print(f"常用字 {len(uniq)} 个 -> {OUT}")


if __name__ == "__main__":
    main()