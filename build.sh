#!/usr/bin/env bash
# ============================================================================
#  MAGI-EVA —— 免 Gradle 直构脚本
# ============================================================================
#
#  为什么要这么一个脚本：本机只有 JDK 21，而缓存的 Gradle 8.2 不支持 JDK 21、
#  缓存的 Gradle 8.5 是半个残档。与其为了拉一条 Gradle 工具链去啃代理，
#  不如直接用 Android SDK 自带的 build-tools 走最原始的链路：
#
#      aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner
#
#  好处是整条链路<b>完全离线、完全确定</b>：本 app 不依赖 AndroidX、Material
#  或任何第三方库，只用 Android framework + JDK，所以没有任何 Maven 解析步骤。
#
#  用法:
#      bash build.sh              # 构建 debug 变体（仍会用 debug 密钥签名，可直接装）
#      bash build.sh release      # 构建 release 变体
#      bash build.sh clean        # 清掉 build/
#
#  产物:
#      build/MAGI-EVA-<变体>.apk
# ============================================================================

set -euo pipefail

# ---------------------------------------------------------------- 路径解析
# 本仓库<不写死任何本机绝对路径>，全部按下面顺序解析，便于在任何机器上直接用：
#   PROJ     脚本自身所在目录
#   SDK      $ANDROID_HOME → $ANDROID_SDK_ROOT → 常见默认位置
#   JAVA_BIN $JAVA_HOME/bin → PATH 上的 java
#   PYTHON   $PYTHON → python
# 需要覆盖时用环境变量，例如:
#   ANDROID_HOME=/opt/android-sdk BUILD_TOOLS=35.0.0 bash build.sh

# 把 POSIX/混写路径统一成 Windows 版工具能吃的形式（Git Bash 下 pwd -W 给出 D:/...）
winpath() {
  local p="$1"
  p="${p//\\//}"
  if [ -d "$p" ]; then (cd "$p" && { pwd -W 2>/dev/null || pwd; }); else printf '%s' "$p"; fi
}

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="${MAGI_PROJ:-$(winpath "$SELF_DIR")}"

SDK="$(winpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}")"

# 用 build-tools 36+ 而不是 34：34 自带的 R8 8.2.2 遇到 enum 类会内部崩溃
#   java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
#   ... 于 Xxx$Status.class
# 36.0.0 的 d8 对同一批 class 正常；aapt2 / zipalign 两版行为一致。
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
PLATFORM="$SDK/platforms/${ANDROID_PLATFORM:-android-34}/android.jar"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(winpath "${JAVA_HOME}")/bin"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(dirname "$(command -v java)")"
else
  echo "✕ 找不到 JDK：请设置 JAVA_HOME，或把 java 放进 PATH" >&2
  exit 1
fi

PYTHON="${PYTHON:-python}"

# Windows 版 SDK 工具带 .exe 后缀；Linux/macOS 不带。
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
  *)                    EXE=""     ;;
esac

KEYSTORE="${MAGI_KEYSTORE:-$PROJ/build/magi-debug.jks}"
KEY_ALIAS="${MAGI_KEY_ALIAS:-magi}"
KEY_PASS="${MAGI_KEY_PASS:-magi-eva}"

MIN_SDK=24
TARGET_SDK=34
VERSION_CODE=1
VERSION_NAME="1.0.0"

# ------------------------------------------------------------------ 前置检查
for f in "$BT/aapt2$EXE" "$BT/zipalign$EXE" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$PLATFORM"; do
  if [ ! -e "$f" ]; then
    echo "✕ 缺少必需组件: $f" >&2
    echo "  请安装 Android SDK build-tools ${BUILD_TOOLS:-36.0.0} 与 platform ${ANDROID_PLATFORM:-android-34}，" >&2
    echo "  或用 ANDROID_HOME / BUILD_TOOLS / ANDROID_PLATFORM 指定正确位置。" >&2
    exit 1
  fi
done

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "✕ 找不到 python（第 5 步用它把 classes.dex 写入 APK）。用 PYTHON=/path/to/python 指定。" >&2
  exit 1
fi

VARIANT="${1:-debug}"
if [ "$VARIANT" = "clean" ]; then
  rm -rf "$PROJ/build"
  echo "已清理 $PROJ/build"
  exit 0
fi

cd "$PROJ"

BUILD="$PROJ/build"
RES_ZIP="$BUILD/res.zip"
BASE_APK="$BUILD/base-unsigned.apk"
ALIGNED="$BUILD/base-aligned.apk"
OUT="$BUILD/MAGI-EVA-$VARIANT.apk"

# 只清中间产物，保留已构建的 *.apk 与密钥库——
# 否则「先构建 release 再构建 debug」会把上一个变体的产物删掉。
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
rm -f "$BUILD/res.zip" "$BUILD/base-unsigned.apk" "$BUILD/base-aligned.apk" \
      "$BUILD/sources.txt" "$BUILD/classes.txt" "$BUILD/javac.log"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

step() { echo ""; echo "── $* ────────────────────────────────────────"; }

# 用 wc -c 取字节数。这个环境下 `ls -la | awk '{print $5}'` 在中文 locale 里
# 会把列数数错，报出跟实际无关的数字（踩过一次）。
fsize() { wc -c < "$1" | tr -d ' \r\n'; }

# --------------------------------------------------------------- 1. 资源编译
step "1/6  aapt2 compile"
"$BT/aapt2$EXE" compile --dir app/src/main/res -o "$RES_ZIP"
echo "    $(fsize "$RES_ZIP") bytes → res.zip"

# --------------------------------------------------------------- 2. 注入包名
# 源 AndroidManifest.xml 故意不写 package="…"（AGP 8 起该属性被禁止，
# 必须改用 build.gradle 的 namespace）。直构这条链路需要包名，
# 所以在构建目录里生成一份注入了 package 的副本，源文件保持干净。
step "2/6  生成带 package 的 manifest"
sed 's|android:versionCode="1"|package="com.nerv.magi" android:versionCode="1"|' \
    app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
grep -o 'package="[^"]*"' "$BUILD/AndroidManifest.xml" | head -1

# ----------------------------------------------------------------- 3. 链接
step "3/6  aapt2 link"
"$BT/aapt2$EXE" link \
    -o "$BASE_APK" \
    -I "$PLATFORM" \
    --manifest "$BUILD/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --custom-package com.nerv.magi \
    --min-sdk-version $MIN_SDK \
    --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE \
    --version-name "$VERSION_NAME" \
    -A app/src/main/assets \
    --no-version-vectors \
    "$RES_ZIP"
echo "    → $(fsize "$BASE_APK") bytes（资源 + manifest，尚未含 dex）"
echo "    R.java: $(find "$BUILD/gen" -name R.java | head -1)"

# --------------------------------------------------------------- 4. 编译 Java
step "4/6  javac"
find app/src/main/java -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name 'R.java' >> "$BUILD/sources.txt"
echo "    源文件 $(wc -l < "$BUILD/sources.txt") 个"

"$JAVA_BIN/javac$EXE" \
    -source 8 -target 8 \
    -nowarn \
    -encoding UTF-8 \
    -bootclasspath "$PLATFORM" \
    -classpath "$PLATFORM" \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1 || {
      echo "    ✕ javac 失败："
      grep -a "error" "$BUILD/javac.log" | head -40
      exit 1
    }

CLASS_COUNT=$(find "$BUILD/classes" -name '*.class' | wc -l)
if [ "$CLASS_COUNT" -eq 0 ]; then
  echo "    ✕ 没有产生任何 class，编译失败"
  cat "$BUILD/javac.log"
  exit 1
fi
echo "    ✓ $CLASS_COUNT 个 class"

# ------------------------------------------------------------------ 5. dex
# 注意：d8.bat / apksigner.bat 是批处理，遇到 SDK 路径里的空格会炸
# （'C:\Users\Tang' 不是内部或外部命令），所以这里直接走它们的 jar。
step "5/6  d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA_BIN/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$PLATFORM" \
    --min-api $MIN_SDK \
    --release \
    --output "$BUILD/dex" \
    @"$BUILD/classes.txt" 2>&1 | tail -8
echo "    → $(fsize "$BUILD/dex/classes.dex") bytes classes.dex"

# 把 classes.dex 塞进 aapt2 产出的 APK（用 Python 的 zipfile，比 aapt add 可靠）
"$PYTHON" - "$BASE_APK" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == 'classes.dex':
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, 'classes.dex')
shutil.move(tmp, apk)
print("    classes.dex 已写入 APK")
PY

# ------------------------------------------------------------- 6. 对齐 + 签名
step "6/6  zipalign + apksigner"
"$BT/zipalign$EXE" -f -p 4 "$BASE_APK" "$ALIGNED"

if [ ! -f "$KEYSTORE" ]; then
  echo "    生成调试密钥库 …"
  "$JAVA_BIN/keytool$EXE" -genkeypair -v \
      -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
      -dname "CN=MAGI-EVA, OU=NERV, O=MAGI System, L=Tokyo-3, ST=Hakone, C=JP" \
      2>&1 | tail -2
fi

"$JAVA_BIN/java$EXE" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT" "$ALIGNED"

rm -f "$ALIGNED" "$BASE_APK" "$RES_ZIP"

echo ""
echo "══════════════════════════════════════════════════════════════"
echo " 构建完成"
echo "   产物   : $OUT"
echo "   大小   : $(fsize "$OUT") bytes"
"$JAVA_BIN/java$EXE" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT" 2>&1 | head -4
echo "══════════════════════════════════════════════════════════════"
