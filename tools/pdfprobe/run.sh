#!/usr/bin/env bash
# PDF 语义实验台：桌面版 PDFBox 2.0.27（与 pdfbox-android 同内核）在本机 JVM 上跑，
# 用来在写安卓代码之前把「复制页会不会丢资源」「压缩能降多少」这类问题量清楚。
#
#   依赖（一次性）：
#     mkdir -p /d/toolchain/pb/desktop && cd /d/toolchain/pb/desktop
#     for u in org/apache/pdfbox/pdfbox/2.0.27/pdfbox-2.0.27.jar \
#              org/apache/pdfbox/fontbox/2.0.27/fontbox-2.0.27.jar \
#              commons-logging/commons-logging/1.2/commons-logging-1.2.jar; do
#       curl -sSfL -O "https://maven.aliyun.com/repository/central/$u"; done
#
#   用法：./tools/pdfprobe/run.sh <子命令> [参数...]   子命令见 PdfProbe.main
#   textlayout 还要 :core 的产物：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JARS="${PDFPROBE_JARS:-/d/toolchain/pb/desktop}"
OUT="${PDFPROBE_OUT:-/tmp/pdfprobe}"
# textlayout / pdftable 这两个子命令直接调 :core 里那份排版器（不是在 Java 里重写一套规则），
# 所以要带上它的产物 —— 判据核的才是应用真正会用的那一套算法。
CORE_JAR="${PDFPROBE_CORE_JAR:-$REPO/core/build/libs/core.jar}"
KT_STDLIB="${KOTLIN_STDLIB_JAR:-$(find "${GRADLE_USER_HOME:-/d/gradle-home}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
  -name 'kotlin-stdlib-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)}"
if [ ! -f "$CORE_JAR" ] || [ ! -f "$KT_STDLIB" ]; then
  echo "缺 :core 的 jar 或 kotlin-stdlib。先跑：GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar" >&2
  exit 1
fi

# 本机的 javac/java 是 Windows 启动器：classpath 用 ';' 分，条目还得是 D:\ 那种写法。
# 把 /d/... 与冒号分开的串递过去不会报错，只会"找不到主类"，看着像脚本坏了 —— 所以条目一律先转。
wpath() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
PARTS=("$JARS/pdfbox-2.0.27.jar" "$JARS/fontbox-2.0.27.jar" "$JARS/commons-logging-1.2.jar" "$CORE_JAR" "$KT_STDLIB")
CP=""
for entry in "${PARTS[@]}"; do
  [ -z "$CP" ] && CP="$(wpath "$entry")" || CP="$CP;$(wpath "$entry")"
done

mkdir -p "$OUT/classes"
if [ ! -f "$OUT/classes/PdfProbe.class" ] || [ "$HERE/PdfProbe.java" -nt "$OUT/classes/PdfProbe.class" ] \
  || [ "$CORE_JAR" -nt "$OUT/classes/PdfProbe.class" ]; then
  javac -nowarn -cp "$CP;$(wpath "$OUT/classes")" -d "$OUT/classes" "$HERE/PdfProbe.java"
fi

ARGS=()
for arg in "$@"; do
  case "$arg" in
    /*) ARGS+=("$(wpath "$arg")") ;;      # 只有绝对路径要换写法：相对路径按当前目录走，别动
    *) ARGS+=("$arg") ;;
  esac
done
exec java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog -cp "$CP;$(wpath "$OUT/classes")" PdfProbe "${ARGS[@]}"
