#!/usr/bin/env python3
"""生成「单字 + 符号」挂载词库。

本分支定位
----------
豆包输入法的词库、整句、联想已经很好用。模块只补两件事：
1. **简单鹤音形码能确定某个汉字**时，把该单字置顶；
2. **快捷符号**（`oy` 系列的带圈数字 / 罗马数字 / `$ ￥` / `-1`、标点连打等）保留，
   这类在豆包里没有对应通道，属于简单鹤的独有能力。

**剔除快捷词与多字词**：`jd`/`jde`、`欧亚大陆`、`一千万`、`阿sir`、`B站` 等
一律不挂，完全交给豆包，避免干扰它自身的词库与整句。

保留规则
--------
1. 汉字单字（恰好 1 字符，U+4E00–9FFF / U+3400–4DBF）→ 保留
2. 不含汉字的候选 → 视为符号，保留；但**纯英文单词/缩写剔除**
   （`ASMR` / `Ctrl` / `ChatGPT` 这类是词不是符号）
3. 括号包住的短符号 `（一）` `（10）` → 保留（oy 数字符号的一部分）
4. 其余（含汉字的多字词）→ 剔除

用法
----
    python3 tools/gen_dict_single.py <仓库路径> <输出文件>
"""
import os
import re
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else "/workspace/rime-JDhe-CyIME"
OUT = sys.argv[2] if len(sys.argv) > 2 else "assets/jdh_dict_multi.txt"

SOURCES = [
    os.path.join(REPO, "cn_dicts/jde/jde.dict.yaml"),
    os.path.join(REPO, "cn_dicts/jde/jde_ext.dict.yaml"),
]

CODE_RE = re.compile(r"^[a-z]{1,4}$")
HAN_RANGES = ((0x4E00, 0x9FFF), (0x3400, 0x4DBF))
# 括号包住的短符号：（一）（10）(10) 等
PAREN_RE = re.compile(r"^[（(].{1,3}[）)]$")


def is_han(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in HAN_RANGES)


def han_count(word: str) -> int:
    return sum(1 for c in word if is_han(c))


def keep(word: str) -> bool:
    """是否保留该候选。"""
    if not word:
        return False
    # 1. 汉字单字
    if len(word) == 1 and is_han(word):
        return True
    # 2. 不含汉字 → 符号；但纯英文单词/缩写是词，剔除
    if han_count(word) == 0:
        if word.isascii() and word.isalpha():
            return False
        return True
    # 3. 括号数字符号
    if PAREN_RE.match(word):
        return True
    # 4. 其余（含汉字的多字词）剔除
    return False


def rows(path):
    if not os.path.isfile(path):
        print("跳过（不存在）", path)
        return
    text = open(path, encoding="utf-8").read()
    idx = text.find("\n...\n")
    body = text[idx + 5:] if idx >= 0 else text
    for line in body.split("\n"):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        word = parts[0].strip()
        if not keep(word):
            continue
        for single in parts[1].split(","):
            code = single.split(";")[0].strip()
            if CODE_RE.fullmatch(code):
                yield code, word


def main():
    table = {}
    order = []
    total = 0
    for path in SOURCES:
        added = 0
        for code, word in rows(path):
            total += 1
            bucket = table.get(code)
            if bucket is None:
                bucket = []
                table[code] = bucket
                order.append(code)
            if word in bucket:
                continue
            bucket.append(word)
            added += 1
        print(f"{os.path.basename(path)}: 保留 {added}")

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        for code in sorted(order):
            fh.write(code + "\t" + " ".join(table[code]) + "\n")

    lens = {}
    for code in table:
        lens[len(code)] = lens.get(len(code), 0) + 1
    items = sum(len(v) for v in table.values())
    single = sum(1 for v in table.values() for w in v if len(w) == 1 and is_han(w))
    sym = sum(1 for v in table.values() for w in v if han_count(w) == 0)
    print()
    print("读入行", total, "编码数", len(table), "候选项", items)
    print(f"  汉字单字 {single}  符号 {sym}  括号数字 {items - single - sym}")
    print("码长分布", dict(sorted(lens.items())))
    print("写出", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()