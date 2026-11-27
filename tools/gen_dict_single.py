#!/usr/bin/env python3
"""生成「单字 + 符号」挂载词库。

本分支定位
----------
**1~3 码（一简 / 二简 / 三简）完全不动**，原样挂载。
简单鹤的简码体系（`jde → 简单鹤`、`jd → 简单 见到 觉得`、`daj → 打 档案夹 档案局`）
是方案的核心，不能筛选。

**4 码做筛选**：只保留汉字单字与快捷符号。4 码在简单鹤里主要是
「音节+音节+形码」的精确位与各类符号位，多字词交给豆包更合适。

**四码的词一律不收**：不管是主表词还是扩展表词，四码只留单个汉字与符号。
该码有扩展生僻字时也一样 —— 生僻字照样保留（它是单字），词照样剔除：

    hvld → 䏨              （主表词 回来/会来 剔除，只剩生僻单字）
    csn  → 悰 䈡           （简单鹤单字置顶）+ 从哪 从南 …（豆包原生候选）
    jde  → 简单鹤          （三简，保留）
    jd   → 简单 见到 觉得   （二简，保留）
    oyy  → ① Ⅰ ㊀ （一） -1（符号，保留）
    jdh  → 豆包自己的候选   （四码多字词，剔除）

四码单字的位次由模块决定（见 HeEntry.isHanChar）：豆包首选占第 1，
简单鹤这个字固定第 2，不分常用与生僻。

保留规则
--------
码长 ≤ 3（一简/二简/三简）：
    **全部保留**，不做任何筛选。

码长 4：
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


def keep(word: str, code_len: int, is_ext: bool) -> bool:
    """是否保留该候选。

    1~3 码（一简/二简/三简）全部保留，简单鹤的简码体系不动。

    4 码一律精简，不看是否有扩展生僻字：
      - 单个汉字 → 保留（主表、扩展表都留，生僻字也是单字）
      - 不含汉字的符号 → 保留，但纯英文单词/缩写剔除
      - 括号包住的短符号 （一）(10) → 保留
      - 其余（含汉字的多字词，主表扩展表一概）→ 剔除
    """
    if not word:
        return False
    if code_len <= 3:
        return True

    if len(word) == 1 and is_han(word):
        return True
    if han_count(word) == 0:
        if word.isascii() and word.isalpha():
            return False
        return True
    if PAREN_RE.match(word):
        return True
    return False


def collect_main_single_codes(path):
    """四码中，主表存在汉字单字的码集合。"""
    codes = set()
    for code, word in rows(path):
        if len(code) == 4 and len(word) == 1 and is_han(word):
            codes.add(code)
    return codes


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
        for single in parts[1].split(","):
            code = single.split(";")[0].strip()
            if not CODE_RE.fullmatch(code):
                continue
            yield code, word


def main():
    table = {}
    order = []
    total = 0
    for path in SOURCES:
        is_ext = os.path.basename(path).startswith("jde_ext")
        added = 0
        for code, word in rows(path):
            if not keep(word, len(code), is_ext):
                continue
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
    multi = items - single - sym
    print()
    print("读入行", total, "编码数", len(table), "候选项", items)
    print(f"  汉字单字 {single}  符号 {sym}  前三码含多字词 {multi}（一简/二简/三简，原样保留）")
    print("码长分布", dict(sorted(lens.items())))
    print("写出", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()