#!/usr/bin/env python3
"""
给 tools/verify_odt_write.py 喂反例：把 .odt 的写法改坏，断言判据点名到该点的那条。

跑法：
    python tools/reverse_odt_write.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_odt_write.py --only 编号列表写成圆点样式
    python tools/reverse_odt_write.py --list

改坏的是 core/office/OdtWrite.kt（一处一处改，锚点必须唯一）。原字节先留下，跑完原样写回，
最后按 sha1 核对并用 git status 证明还原干净 —— 不用 git checkout 撤销。
判据没跑起来（产物没落盘 / 判据崩了）单独报，不混进"改不坏"。

两条没配反例的：连续空格写成裸空白、制表符丢掉 —— Markdown 那条本来就把连续空白折成一个空格，
两边的文字判据（第 8 条）分不出这两种改法，那种丢法由 OdtWriteTest 的
「连续空格与制表按 ODF 的元素写」判（它比的是 .odt 自己）。
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

WRITER = "core/src/main/kotlin/com/fileforge/core/office/OdtWrite.kt"
BUILD = os.path.join("core", "build", "odtwrite")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.OdtWriteTest" --rerun'

CASES = [
    {
        "名字": "mimetype 被压缩",
        "判据": 1,
        "原样": "com.fileforge.core.archive.ZipItem(\"mimetype\", MIME.length.toLong(), modifiedAt, stored = true) {",
        "改成": "com.fileforge.core.archive.ZipItem(\"mimetype\", MIME.length.toLong(), modifiedAt) {",
    },
    {
        "名字": "清单点了包里没写的文件",
        "判据": 1,
        "原样": "        if (withMeta) out.append(entry(\"meta.xml\", \"text/xml\"))",
        "改成": "        out.append(entry(\"nothing.xml\", \"text/xml\"))\n"
                "        if (withMeta) out.append(entry(\"meta.xml\", \"text/xml\"))",
    },
    {
        "名字": "automatic-styles 放到正文之后",
        "判据": 1,
        "原样": "        xml.append(\"<office:automatic-styles>\")\n        xml.append(runs.render())\n"
                "        xml.append(COLUMN_STYLE)\n        xml.append(\"</office:automatic-styles>\")\n"
                "        xml.append(\"<office:body><office:text text:use-soft-page-breaks=\\\"true\\\">\")\n"
                "        xml.append(body)",
        "改成": "        xml.append(\"<office:body><office:text text:use-soft-page-breaks=\\\"true\\\">\")\n"
                "        xml.append(body)\n        xml.append(\"<office:automatic-styles>\")\n"
                "        xml.append(runs.render())\n        xml.append(COLUMN_STYLE)\n"
                "        xml.append(\"</office:automatic-styles>\")",
    },
    {
        "名字": "层级不写在标题元素上",
        "判据": 4,
        "原样": '                    out.append("<text:h text:style-name=\\"").append(style)\n'
                '                        .append("\\" text:outline-level=\\"").append(level).append("\\">")',
        "改成": '                    out.append("<text:h text:style-name=\\"").append(style).append("\\">")',
    },
    {
        "名字": "编号列表写成圆点样式",
        "判据": 5,
        "原样": "private fun styleOf(bullet: Boolean): String = if (bullet) BULLET_STYLE else NUMBER_STYLE",
        "改成": "private fun styleOf(bullet: Boolean): String = BULLET_STYLE",
    },
    {
        "名字": "引用段用正文样式",
        "判据": 9,
        "原样": "        style == \"Quote\" -> \"Quotations\"",
        "改成": "        style == \"Quote\" -> \"Text_20_Body\"",
    },
    {
        "名字": "代码段用正文样式",
        "判据": 3,
        "原样": "        style == \"SourceCode\" -> \"Source_20_Code\"",
        "改成": "        style == \"SourceCode\" -> \"Text_20_Body\"",
    },
    {
        # 这一条红在第 2 条而不是第 12 条，是实测出来的：没有 header-rows 的表，
        # pandoc 的 ODT 读者直接在 Prelude.maximum 上崩掉 —— 那份包别人读不动，比"表头没认出来"更严重
        "名字": "表头那行不裹进 header-rows",
        "判据": 2,
        "原样": "        if (headerRows > 0) {\n            out.append(\"<table:table-header-rows>\")",
        "改成": "        if (false) {\n            out.append(\"<table:table-header-rows>\")",
    },
    {
        "名字": "把最后一行当表头",
        "判据": 12,
        "原样": "            row(part.rows.first(), width, out)",
        "改成": "            row(part.rows.last(), width, out)",
    },
    {
        "名字": "表里少写最后一行",
        "判据": 6,
        "原样": "        part.rows.drop(headerRows).forEach { row(it, width, out) }",
        "改成": "        part.rows.drop(headerRows).dropLast(1).forEach { row(it, width, out) }",
    },
    {
        "名字": "链接地址不写出去",
        "判据": 7,
        "原样": "        val target = run.link?.takeIf { it.isNotBlank() }",
        "改成": "        val target: String? = null",
    },
    {
        "名字": "少写一段的字",
        "判据": 8,
        "原样": "                para.runs.forEach { run(it, out, runs, tally) }",
        "改成": "                para.runs.dropLast(1).forEach { run(it, out, runs, tally) }",
    },
    {
        "名字": "分隔线写成普通空段",
        "判据": 10,
        "原样": "                out.append(\"<text:p text:style-name=\\\"Horizontal_20_Line\\\"/>\")",
        "改成": "                out.append(\"<text:p text:style-name=\\\"Text_20_Body\\\"/>\")",
    },
    {
        "名字": "书名不写进 meta.xml",
        "判据": 11,
        "原样": "val meta = if (title.isBlank()) null else metaXml(title)",
        "改成": "        val meta: String? = null",
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def run(cmd: str) -> int:
    code, _ = run_and_log(cmd)
    return code


def run_and_log(cmd: str) -> tuple:
    done = subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=ENV)
    return done.returncode, (done.stdout or "") + (done.stderr or "")


def wipe_artifacts() -> None:
    shutil.rmtree(BUILD, ignore_errors=True)


def run_referee() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_odt_write.py")],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return done.returncode, re.findall(r"^FAIL\s+(\d+) ", done.stdout, re.M), done.stdout + done.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for case in CASES:
            print("%2d  %s" % (case["判据"], case["名字"]))
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    files = [WRITER]
    originals = {path: read_bytes(path) for path in files}
    texts = {path: originals[path].decode("utf-8") for path in files}

    def restore():
        for path, blob in originals.items():
            with open(path, "wb") as handle:
                handle.write(blob)
            # 当场核对：留到最后一轮才报"没还原干净"，中间那些轮的判词就都不可信了
            if read_bytes(path) != blob:
                print("还原没到位 %s —— 停下来，别再往下判" % path)
                raise SystemExit(1)

    wipe_artifacts()
    if run(DUMP) != 0:
        print("基线就没绿：先修好 :core:test 的 OdtWriteTest 再谈反例")
        return 1
    code, reds, output = run_referee()
    if code != 0:
        print("判据基线就红，反例还没测：\n%s" % output[-1500:])
        return 1
    if "--comply" in argv:
        print("合规面：判据全绿（照 ODF 写出来的包被 pandoc 收），反例还没跑")
        return 0

    misses = []
    tried = 0
    for case in CASES:
        if only and only != case["名字"]:
            continue
        tried += 1
        text = texts[WRITER]
        if text.count(case["原样"]) != 1:
            print("反例失效 %-22s 锚点出现 %d 次（实现改过，这条得跟着改）" % (case["名字"], text.count(case["原样"])))
            misses.append((case["名字"], "锚点不唯一或没了"))
            continue
        with open(WRITER, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text.replace(case["原样"], case["改成"], 1))
        wipe_artifacts()
        tests_rc, dump_out = run_and_log(DUMP)
        tests_green = tests_rc == 0
        dumped = os.path.exists(os.path.join(BUILD, "book.odt"))
        code, reds, output = run_referee()
        restore()
        wipe_artifacts()
        run(DUMP)
        if not dumped:
            clues = [line.strip()[:110] for line in dump_out.splitlines()
                     if line.startswith("e: ") or "FAILED" in line or "What went wrong" in line]
            print("没产物 %-22s 落盘测试没写出 %s；gradle 线索：%s"
                  % (case["名字"], BUILD, clues or dump_out.strip().splitlines()[-3:]))
            misses.append((case["名字"], "判据没跑起来（没有产物）"))
            continue
        if code != 0 and not reds:
            print("判据崩了 %-23s 没有一条 FAIL，输出尾部：%s" % (case["名字"], output[-300:].strip()))
            misses.append((case["名字"], "判据崩了，没点到名"))
            continue
        if code == 0 and tests_green:
            print("没牙   %-24s 单元测试与判据都没红" % case["名字"])
            misses.append((case["名字"], "全绿"))
        elif str(case["判据"]) not in reds:
            note = ("单元测试拦了，判据没红（红的是 %s）" % (sorted(set(reds)) or "无")) if not tests_green \
                else "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-24s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-24s 判据 %2d 红了（单元测试%s；共红 %s）"
                  % (case["名字"], case["判据"], "也拦了一道" if not tests_green else "没拦住", sorted(set(reds))))

    dirty = [path for path in files
             if hashlib.sha1(read_bytes(path)).hexdigest() != hashlib.sha1(originals[path]).hexdigest()]
    if dirty:
        print("源文件没还原干净：%s" % dirty)
        return 1
    status = subprocess.run(["git", "status", "--short", "--"] + files,
                            capture_output=True, text=True, encoding="utf-8").stdout.strip()
    print("\n试了 %d 个反例，问题 %d 个；源文件按 sha1 还原 ✓  git status：%s"
          % (tried, len(misses), status if status else "干净"))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
