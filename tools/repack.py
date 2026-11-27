#!/usr/bin/env python3
"""以已签名的旧 APK 为壳重打包：只换 dex 与 assets，不改资源。

用途：在没有可用 aapt2 的机器上迭代（例如 aapt2 是 x86_64 二进制、
跑在 arm64 设备上会 Exec format error）。仅适用于只改代码/词库、
不改 res/ 与 AndroidManifest 结构的场景。

用法：
    python3 tools/repack.py <base.apk> <keystore> <output.apk> [--version 3.6]

versionName 在 AXML 字符串池里是 UTF-16 的定长字符串，只能等长替换。
基准版本是 shell 里的 1.2（3 字符），所以目标版本也必须 3 字符。

Android 11+ 的硬性要求：targetSdk >= 30 时 resources.arsc 必须以
STORED（未压缩）方式存放并 4 字节对齐，否则解析阶段就报
INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED，安装器连界面都不弹。
所以重写 zip 时 resources.arsc 强制 STORED，随后 zipalign 4 字节对齐。
"""
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEX = os.path.join(ROOT, "build", "dex", "classes.dex")
ASSETS = os.path.join(ROOT, "assets")
DICT = os.path.join(ASSETS, "jdh_dict_multi.txt")

BASE_VER = "1.2"


def patch_manifest(data: bytes, new_ver: str) -> bytes:
    old = BASE_VER.encode("utf-16-le")
    new = new_ver.encode("utf-16-le")
    if len(new) != len(old):
        raise SystemExit(f"versionName 必须等长（基准 {BASE_VER!r}）")
    if data.count(old) == 1:
        return data.replace(old, new)
    if data.count(new) == 1:
        return data              # 壳 APK 里已经是目标版本名，不必改
    raise SystemExit("versionName 字符串定位失败，中止")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    new_ver = BASE_VER
    if "--version" in sys.argv:
        new_ver = sys.argv[sys.argv.index("--version") + 1]
        args = [a for a in args if a != new_ver]

    if len(args) < 3:
        raise SystemExit(__doc__)
    base, ks, out = args[0], args[1], args[2]

    for need in (base, ks, DEX, DICT):
        if not os.path.isfile(need):
            raise SystemExit("缺少: " + need)

    unsigned = os.path.join(ROOT, "build", "repacked_unsigned.apk")
    aligned = os.path.join(ROOT, "build", "repacked_aligned.apk")
    os.makedirs(os.path.dirname(unsigned), exist_ok=True)

    payload_assets = {}
    for name in sorted(os.listdir(ASSETS)):
        with open(os.path.join(ASSETS, name), "rb") as fh:
            payload_assets["assets/" + name] = fh.read()
    with open(DEX, "rb") as fh:
        dex = fh.read()

    with zipfile.ZipFile(base) as src, zipfile.ZipFile(unsigned, "w") as dst:
        for info in src.infolist():
            name = info.filename
            if name.startswith("META-INF/"):
                continue          # 旧签名作废，稍后重签
            if name == "classes.dex":
                payload = dex
            elif name in payload_assets:
                payload = payload_assets[name]
            elif name == "AndroidManifest.xml":
                payload = patch_manifest(src.read(name), new_ver)
            else:
                payload = src.read(name)
            entry = zipfile.ZipInfo(name, date_time=info.date_time)
            # Android 11+ 要求 resources.arsc 未压缩，否则整包解析失败
            entry.compress_type = (zipfile.ZIP_STORED if name == "resources.arsc"
                                   else info.compress_type)
            entry.external_attr = info.external_attr
            dst.writestr(entry, payload)

    for path in (aligned, out):
        if os.path.exists(path):
            os.remove(path)
    subprocess.check_call(["zipalign", "-f", "4", unsigned, aligned])
    subprocess.check_call([
        "apksigner", "sign",
        "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--out", out, aligned,
    ])
    check_arsc(out)
    subprocess.check_call(["apksigner", "verify", "--print-certs", out])
    print("产物:", out, os.path.getsize(out), "bytes")


def check_arsc(path: str) -> None:
    """确认 resources.arsc 是 STORED 且 4 字节对齐（Android 11+ 安装要求）。"""
    import struct
    with open(path, "rb") as fh:
        blob = fh.read()
    with zipfile.ZipFile(path) as z:
        info = z.getinfo("resources.arsc")
    off = info.header_offset
    # 必须读本地文件头的 extra 长度：zipalign 的对齐填充写在这里，
    # 中央目录里的 extra 可能为空，按它算偏移会误判。
    fields = struct.unpack("<IHHHHHIIIHH", blob[off:off + 30])
    method, name_len, extra_len = fields[3], fields[9], fields[10]
    data_off = off + 30 + name_len + extra_len
    if method != 0:
        raise SystemExit("resources.arsc 未被 STORED 存放，Android 11+ 会安装失败")
    if data_off % 4 != 0:
        raise SystemExit(f"resources.arsc 偏移 {data_off} 未 4 字节对齐")
    print(f"arsc 校验通过：STORED，偏移 {data_off}（4 字节对齐）")


if __name__ == "__main__":
    main()