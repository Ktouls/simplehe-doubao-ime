#!/usr/bin/env python3
"""以已签名的旧 APK 为壳重打包：只换 dex 与 assets，不改资源。

用途：在没有可用 aapt2 的机器上迭代（例如 aapt2 是 x86_64 二进制、
跑在 arm64 设备上会 Exec format error）。仅适用于只改代码/词库、
不改 res/ 与 AndroidManifest 结构的场景。

用法：
    python3 tools/repack.py <base.apk> <keystore> <output.apk> [--version 2.4]

versionName 在 AXML 字符串池里是 UTF-16 的定长字符串，只能等长替换。
当前容器基准版本是 1.2，所以支持的替换长度与 1.2 相同（3 字符）。
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
    if data.count(old) != 1:
        raise SystemExit("versionName 字符串定位失败，中止")
    return data.replace(old, new)


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
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            dst.writestr(entry, payload)

    for path in (aligned, out):
        if os.path.exists(path):
            os.remove(path)
    subprocess.check_call(["zipalign", "-f", "-p", "4", unsigned, aligned])
    subprocess.check_call([
        "apksigner", "sign",
        "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--out", out, aligned,
    ])
    subprocess.check_call(["apksigner", "verify", "--print-certs", out])
    print("产物:", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()