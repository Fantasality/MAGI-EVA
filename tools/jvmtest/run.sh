#!/usr/bin/env bash
# ============================================================================
#  JVM 端到端测试 —— 用将打进 APK 的真实 Java 类，打真实的 Jev 接口
# ============================================================================
#
#  与 build.sh 的区别：build.sh 证明「能打包」，这个脚本证明「跑得对」。
#  它编译的是 app/src/main/java 下的 core/ 与 jev/ 原样代码（不经任何改写），
#  只把 Prefs 依赖的 android.content.* 换成 tools/jvmtest/android/content 里的两个内存替身。
#  界面层（ui/、各 Activity）不参与，因为它们依赖真正的 Canvas 与 View。
#
#  用法:
#      bash tools/jvmtest/run.sh "詢問"           # 无代理
#      bash tools/jvmtest/run.sh "詢問" --proxy   # 经 127.0.0.1:7897
# ============================================================================

set -euo pipefail

# 本仓库不写死任何本机绝对路径；需要覆盖时用环境变量，例如
#   JAVA_HOME=/opt/jdk21 bash tools/jvmtest/run.sh "詢問"
winpath() {
  local p="$1"
  p="${p//\\//}"
  if [ -d "$p" ]; then (cd "$p" && { pwd -W 2>/dev/null || pwd; }); else printf '%s' "$p"; fi
}

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="${MAGI_PROJ:-$(winpath "$(cd "$SELF_DIR/../.." && pwd)")}"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(winpath "${JAVA_HOME}")/bin"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(dirname "$(command -v java)")"
else
  echo "✕ 找不到 JDK：请设置 JAVA_HOME，或把 java 放进 PATH" >&2
  exit 1
fi

case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; CPSEP=";" ;;
  *)                    EXE="";     CPSEP=":" ;;
esac

OUT="$PROJ/build/jvmtest"
LIBS="$PROJ/tools/jvmtest/libs"

QUESTION="${1:-我們應該啟動人類補完計劃嗎？}"
PROXY_FLAG="${2:-}"

cd "$PROJ"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

SRC_DIR="$PROJ/tools/jvmtest"

echo "── 编译 core/ + jev/ + 测试替身 ────────────────────────────"
find app/src/main/java/com/nerv/magi/core \
     app/src/main/java/com/nerv/magi/jev \
     tools/jvmtest/android \
     -name '*.java' > "$OUT/sources.txt"
echo "app/src/main/java/com/nerv/magi/../../../tools/jvmtest/MagiHarness.java" >/dev/null
find tools/jvmtest -maxdepth 1 -name '*.java' >> "$OUT/sources.txt"

"$JAVA_BIN/javac$EXE" -encoding UTF-8 -nowarn \
    -cp "$LIBS/json.jar" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "    ✓ $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"

echo ""
echo "── 运行 ────────────────────────────────────────────────"
PROXY_OPTS=()
if [ "$PROXY_FLAG" = "--proxy" ]; then
  PROXY_OPTS=(-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897
              -Dhttp.proxyHost=127.0.0.1  -Dhttp.proxyPort=7897)
  echo "    （经代理 127.0.0.1:7897）"
fi

"$JAVA_BIN/java$EXE" "${PROXY_OPTS[@]}" \
    -cp "$OUT/classes${CPSEP}$LIBS/json.jar" MagiHarness "$QUESTION"
