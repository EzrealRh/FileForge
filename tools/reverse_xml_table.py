#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「XML 转表」这套判据有没有牙：改坏实现 + 改坏产物两段。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
第一段改坏 XmlTable 的判断（挑哪处、怎么摊平、压平要不要交代），每轮重跑落盘测试；
第二段只改坏落盘的 catalog.csv / notes.txt，证明外部那 7 条真的在读这些字节。

跑法：python tools/reverse_xml_table.py
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
XML_TABLE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "data", "XmlTable.kt")
OUT_DIR = os.path.join(ROOT, "core", "build", "xmldata")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.XmlTableFixtureTest"'

# (说明, 原样, 改成, 期望红的那条判据)
BREAKS = [
    ("打分不看条数（字段多的浅层就赢了）",
     "val score: Int get() = count * 100 + keys * 10 - depth",
     "val score: Int get() = keys * 10 - depth",
     "ElementTree 数出来重复最多的那处"),
    ("打分改成越深越优先",
     "val score: Int get() = count * 100 + keys * 10 - depth",
     "val score: Int get() = count * 100 + keys * 10 + depth",
     "ElementTree 数出来重复最多的那处"),
    ("单值嵌套不再往下钻（整坨压成文本）",
     'only is JsonObject -> flatten(only, "$key.", packed).members.forEach { (nested, child) -> put(out, nested, child) }',
     "only is JsonObject -> put(out, key, JsonString(JsonRender.render(only)))",
     "列名与列数与 ElementTree 独立推的一致"),
    ("真嵌套压成的那格不再是合法 JSON",
     "put(out, key, JsonString(JsonRender.render(value)))",
     'put(out, key, JsonString(value.arrayValue.joinToString(" ") { JsonRender.render(it) }))',
     "真嵌套压成的那一格是合法 JSON 文本"),
    ("元素自己的文字换了个列名（判据里那套规则说改就改）",
     'val key = if (raw == Xml.TEXT) prefix + TEXT_COLUMN else "$prefix$raw"',
     'val key = if (raw == Xml.TEXT) prefix + "内容" else "$prefix$raw"',
     "列名与列数与 ElementTree 独立推的一致"),
]

TAMPERS = [
    ("catalog.csv", lambda t: t.replace("可借的", "可借的（改过）"), "每一格的内容与 ElementTree 独立摊出来的一致"),
    ("catalog.csv", lambda t: t.replace("@lang", "lang"), "列名与列数与 ElementTree 独立推的一致"),
    ("notes.txt", lambda t: "\n".join(l for l in t.splitlines() if "行取自" not in l) + "\n",
     "ElementTree 数出来重复最多的那处"),
]


def reds(text):
    return [line[5:].strip() for line in text.splitlines() if line.startswith("FAIL ")]


def verdict(label, expect, text):
    red = reds(text)
    hit = [item for item in red if expect in item]
    return "%s -> 外部红了 %d 条 %s，本该红的「%s」%s" % (
        label, len(red), [item[:24] for item in red], expect, "红了" if hit else "没红（看上面哪条红）")


def run_verify():
    got = subprocess.run("python tools/verify_xml_table.py", cwd=ROOT, shell=True, capture_output=True, env=ENV)
    return got.stdout.decode("utf-8", "replace") + got.stderr.decode("utf-8", "replace")


def main():
    source = io.open(XML_TABLE, encoding="utf-8").read()
    verdicts = []
    try:
        for label, needle, replacement, expect in BREAKS:
            if source.count(needle) != 1:
                verdicts.append("跳过（要改坏的那行没找到或有重，count=%d）：%s" % (source.count(needle), label))
                continue
            io.open(XML_TABLE, "w", encoding="utf-8", newline="\n").write(source.replace(needle, replacement, 1))
            build = subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)
            out = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                why = "编译就红了" if any(line.startswith("e: ") for line in out.splitlines()) else "单元测试先红了"
                verdicts.append("%s -> %s（外部判据没跑到，少一层算多层保险）" % (label, why))
            else:
                verdicts.append(verdict(label, expect, run_verify()))
            io.open(XML_TABLE, "w", encoding="utf-8", newline="\n").write(source)
    finally:
        io.open(XML_TABLE, "w", encoding="utf-8", newline="\n").write(source)
        subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)

    for name, break_it, expect in TAMPERS:
        path = os.path.join(OUT_DIR, name)
        original = io.open(path, encoding="utf-8").read()
        io.open(path, "w", encoding="utf-8", newline="\n").write(break_it(original))
        verdicts.append(verdict("产物改坏（%s）" % name, expect, run_verify()))
        io.open(path, "w", encoding="utf-8", newline="\n").write(original)

    print("\n".join(verdicts))
    toothless = [v for v in verdicts if "没红" in v or "跳过" in v]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
