#!/usr/bin/env bash
set -euo pipefail

# 简单鹤音形挂载（豆包）构建脚本
# 依赖：Android SDK build-tools(aapt2/d8/zipalign/apksigner) + platform android.jar
#       + libs/api82.jar（Xposed API，仅编译期）
#
# 词库：先跑 tools/gen_dict.py 生成 assets/jdh_dict_multi.txt
# 签名：需要自己的 keystore，见 README

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-}"
BT="${BUILD_TOOLS:-}"
PLATFORM="${ANDROID_JAR:-}"
API="$HERE/libs/api82.jar"
DICT="$HERE/assets/jdh_dict_multi.txt"
KS="${KEYSTORE:-$HERE/debug.keystore}"
OUT="${OUT:-$HERE/build/简单鹤音形挂载.apk}"

if [ -z "$SDK" ]; then
    echo "请设置 ANDROID_SDK_ROOT" >&2
    exit 1
fi
[ -z "$BT" ] && BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -z "$PLATFORM" ] && PLATFORM="$(find "$SDK/platforms" -maxdepth 2 -name android.jar 2>/dev/null | sort -V | tail -1)"

for need in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM" "$API" "$DICT" "$KS"; do
    if [ ! -e "$need" ]; then
        echo "缺少: $need" >&2
        exit 1
    fi
done

cd "$HERE"
rm -rf build
mkdir -p build/classes build/dex build/apk build/res

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir res -o build/res/compiled.zip

echo "[2/6] 链接清单"
"$BT/aapt2" link -I "$PLATFORM" -o build/apk/base.apk \
    --manifest AndroidManifest.xml --min-sdk-version 27 --target-sdk-version 33 \
    build/res/compiled.zip

echo "[3/6] 编译 Hook"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$API:$PLATFORM" \
    -d build/classes src/com/jdh/simplehe/*.java

echo "[4/6] 生成 dex（Xposed API 不进 dex）"
"$BT/d8" --lib "$PLATFORM" --min-api 27 --output build/dex \
    $(find build/classes -name '*.class')

echo "[5/6] 打包入口与词库"
cd build/apk
unzip -o -q base.apk
cp ../dex/classes.dex .
mkdir -p assets
cp "$HERE/assets/xposed_init" "$DICT" assets/
rm -f base.apk
zip -r -q ../final_unsigned.apk .

echo "[6/6] 对齐并签名"
cd "$HERE"
"$BT/zipalign" -f -p 4 build/final_unsigned.apk build/aligned.apk
mkdir -p "$(dirname "$OUT")"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" build/aligned.apk

echo "--- 校验 ---"
if "$BT/dexdump" build/dex/classes.dex 2>/dev/null | grep -q 'Class descriptor.*de/robv/android/xposed'; then
    echo "错误：Xposed API 类被打进 dex" >&2
    exit 1
fi
echo "OK：dex 无 Xposed 类定义"
ls -lh "$OUT"
"$BT/apksigner" verify --print-certs "$OUT" | grep -i 'SHA-256' || true