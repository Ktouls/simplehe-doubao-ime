#!/usr/bin/env python3
"""从 rime-JDhe-CyIME 仓库直接生成挂载词库（编码 -> 候选词，空格分隔）。

规则：
1. 严格按仓库 yaml 的**文件行序**累积，保序即保「简单鹤内部排序」；
2. jde.dict.yaml（主码表） -> jde_ext.dict.yaml（扩展字集） -> my_user（个人词）；
3. 同 (编码, 词) 去重，同编码内保持首次出现顺序；
4. 丢弃含非小写字母的编码（反引号反查、分隔符等不属于音形主码）；
5. 输出 UTF-8，制表符分隔，无 BOM。
"""
import os
import re
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else "/workspace/rime-JDhe-CyIME"
OUT = sys.argv[2] if len(sys.argv) > 2 else "assets/jdh_dict_multi.txt"

SOURCES = [
    os.path.join(REPO, "cn_dicts/jde/jde.dict.yaml"),
    os.path.join(REPO, "cn_dicts/jde/jde_ext.dict.yaml"),
    os.path.join(REPO, "cn_dicts/my_user.dict.yaml"),
]

CODE_RE = re.compile(r"^[a-z]{1,4}$")

# ---------------------------------------------------------------------------
# 补回被「清理 ① 占位」误伤的正规数字符号
#
# Ktouls/rime-JDhe-CyIME 为修复「四码首选是 ①」清了 14,111 条含 ① 的词条，
# 但 oy 系列是「oy + 数字键盘键位」的快速数字符号，属于正规条目：
#   oyy(y=1) → ① Ⅰ ㊀ （一） -1
#   oye(e=2) → ② Ⅱ ㊁
#   ...
# 其中 oyy 的 ① 被一并删掉（其余 oy 组不受影响，因为只有 ① 出现在四码冗余位）。
# 上游 V9.1.0 日志「① 在一些四码符号处有冗余，删掉」只针对四码，不含 oyy。
#
# 这里只补 oyy 的 ①，四码的冗余（oyyy/oyye/...上的 ①）保持删除。
RESTORE = {
    "oyy": "①",
}



def rows(path):
    if not os.path.isfile(path):
        print("跳过（不存在）", path)
        return
    text = open(path, encoding="utf-8").read()
    # yaml 头部以 ... 结束，之后才是词条
    idx = text.find("\n...\n")
    body = text[idx + 5:] if idx >= 0 else text
    for line in body.split("\n"):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        word, code = parts[0].strip(), parts[1].strip()
        if not word or not code:
            continue
        # 一个词可能有多个编码，用逗号分隔
        for single in code.split(","):
            single = single.strip()
            # 去掉 ";" 之后可能出现的注记，只保留纯音形码
            single = single.split(";")[0].strip()
            if CODE_RE.fullmatch(single):
                yield single, word


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
        print(f"{os.path.basename(path)}: 新增 {added}")

    # 补回被误删的正规符号（插到该码候选项最前，与上游顺序一致）
    for code, word in RESTORE.items():
        bucket = table.get(code)
        if bucket is None:
            table[code] = [word]
            order.append(code)
        elif word not in bucket:
            bucket.insert(0, word)

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        for code in sorted(order):
            fh.write(code + "\t" + " ".join(table[code]) + "\n")

    lens = {}
    for code in table:
        lens[len(code)] = lens.get(len(code), 0) + 1
    print("读入行", total, "编码数", len(table), "候选项", sum(len(v) for v in table.values()))
    print("码长分布", dict(sorted(lens.items())))
    print("写出", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()