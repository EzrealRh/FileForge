#!/usr/bin/env bash
# 「改图片方向与边界」的桌面替身：调 :core 那份 ImageGeometry 算，Java2D 只负责画。
#
#   用法：./tools/imgprobe/run.sh transform <源图> <出图> <度数> <翻法0-3> <裁|-> <比例|->
#   要先出 :core 的产物：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${IMGPROBE_OUT:-/tmp/imgprobe}"
CORE_JAR="${IMGPROBE_CORE_JAR:-$REPO/core/build/libs/core.jar}"
KT_STDLIB="${KOTLIN_STDLIB_JAR:-$(find "${GRADLE_USER_HOME:-/d/gradle-home}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
  -name 'kotlin-stdlib-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)}"
if [ ! -f "$CORE_JAR" ] || [ ! -f "$KT_STDLIB" ]; then
  echo "缺 :core 的 jar 或 kotlin-stdlib。先跑：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar" >&2
  exit 1
fi

# 本机的 javac/java 是 Windows 启动器：classpath 用 ';' 分，条目要 D:\ 那种写法。
wpath() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
CP="$(wpath "$CORE_JAR");$(wpath "$KT_STDLIB")"

mkdir -p "$OUT/classes"
if [ ! -f "$OUT/classes/ImgProbe.class" ] || [ "$HERE/ImgProbe.java" -nt "$OUT/classes/ImgProbe.class" ] \
  || [ "$CORE_JAR" -nt "$OUT/classes/ImgProbe.class" ]; then
  javac -nowarn -cp "$CP;$(wpath "$OUT/classes")" -d "$OUT/classes" "$HERE/ImgProbe.java"
fi

ARGS=()
for arg in "$@"; do
  case "$arg" in
    /*) ARGS+=("$(wpath "$arg")") ;;      # 只有绝对路径要换写法：相对路径按当前目录走，别动
    *) ARGS+=("$arg") ;;
  esac
done
exec java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -Dsun.stderr.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$CP;$(wpath "$OUT/classes")" ImgProbe "${ARGS[@]}"
