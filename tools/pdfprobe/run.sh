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
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JARS="${PDFPROBE_JARS:-/d/toolchain/pb/desktop}"
OUT="${PDFPROBE_OUT:-/tmp/pdfprobe}"
CP="$JARS/pdfbox-2.0.27.jar:$JARS/fontbox-2.0.27.jar:$JARS/commons-logging-1.2.jar"

mkdir -p "$OUT/classes"
if [ ! -f "$OUT/classes/PdfProbe.class" ] || [ "$HERE/PdfProbe.java" -nt "$OUT/classes/PdfProbe.class" ]; then
  javac -nowarn -cp "$CP" -d "$OUT/classes" "$HERE/PdfProbe.java"
fi
exec java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog -cp "$CP:$OUT/classes" PdfProbe "$@"
