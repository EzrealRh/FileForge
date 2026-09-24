#!/usr/bin/env bash
# 应用内更新的验证台：拿 :core 里那份真代码去打真实 GitHub API。
#
# 用法：bash tools/updateprobe/run.sh [只读token] [本地apk路径]
#   仓库公开时什么也不用给，全程匿名跑；改回私有再传一个 token（GitHub → Settings →
#   Developer settings → Fine-grained token，只勾 Contents: Read-only）。比产物 CRC 时会下整包，其余只取前 4KB。
#
# 依赖：JDK 21、Gradle 缓存里的 kotlin-stdlib（跑过一次 :core:jar 就有）。
set -euo pipefail
cd "$(dirname "$0")/../.."

TOKEN="${1:-}"
# 默认取 release 目录里版本号最新的那个包。写死版本号的坑不是"忘了改就报错"，
# 而是"忘了改就拿着上一版的本地产物去比这一版的线上产物"——那种比出来的 CRC 结论是假的。
DEFAULT_APK=$(ls -1 app/build/outputs/apk/release/fileforge-v*-release.apk 2>/dev/null | sort -V | tail -1)
APK="${2:-$DEFAULT_APK}"
[ -n "$APK" ] || { echo "release 目录里没有 fileforge-v*-release.apk，先跑 ./gradlew :app:assembleRelease"; exit 3; }
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/gradle-home}"
JDK="/c/Program Files/Java/jdk-21/bin"
[ -x "$JDK/javac" ] || JDK="$(dirname "$(command -v javac)")"

POSIX_HOME=$(printf '%s' "$GRADLE_USER_HOME" | sed -E 's#^([A-Za-z]):[\\/]#/\L\1/#')
STDLIB=$(find "$POSIX_HOME/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
    -name "kotlin-stdlib-*.jar" ! -name "*sources*" 2>/dev/null | sort | tail -1)
[ -n "$STDLIB" ] || { echo "找不到 kotlin-stdlib，先跑一次 ./gradlew :core:jar"; exit 3; }

./gradlew --offline -q :core:jar
CORE="core/build/libs/core.jar"
[ -f "$CORE" ] || { echo "没有 $CORE"; exit 3; }

rm -rf build/updateprobe && mkdir -p build/updateprobe
# 调的是 Windows 版 JDK：classpath 分隔符要分号、路径要反斜杠，还得拦住 MSYS 的路径改写
winpath() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
CP="$(winpath build/updateprobe);$(winpath "$CORE");$(winpath "$STDLIB")"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
"$JDK/javac" -cp "$CP" -d build/updateprobe tools/updateprobe/UpdateProbe.java
"$JDK/java" -cp "$CP" -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 "-DlocalApk=$(winpath "$APK")" UpdateProbe "$TOKEN"
