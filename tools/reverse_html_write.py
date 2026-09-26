#!/usr/bin/env python3
"""
给 tools/verify_html_write.py 喂反例：逐个塞缺陷，断言点名到该点的那条。

跑法：
    python tools/reverse_html_write.py            # 塞缺陷 → 跑判据 → 还原
    python tools/reverse_html_write.py --comply   # 照现在的产物再判一遍，断言"照规格做的会被收"
    python tools/reverse_html_write.py --list

只动 core/build/htmlwrite/ 里的产物（build 目录，不是仓库文件）：原字节先整批留在内存里，
每个反例跑完立刻写回，最后按 sha1 核对一处不剩 —— 下一轮正常验证拿到的还是刚打出来的那份。
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

BUILD = os.path.join("core", "build", "htmlwrite")
VERIFY = os.path.join("tools", "verify_html_write.py")


def sub(old: str, new: str):
    def edit(text: str) -> str:
        if old not in text:
            raise AssertionError("反例没打上：页面里找不到 %r" % old)
        return text.replace(old, new, 1)
    return edit


def drop(text: str):
    def edit(source: str) -> str:
        if text not in source:
            raise AssertionError("反例没打上：页面里没有 %r" % text)
        return source.replace(text, "", 1)
    return edit


TAMPERS = [
    ("charset 声明没了", 1, "note.html", sub('<meta charset="utf-8">', "")),
    ("DOCTYPE 那一行没了", 1, "note.html", sub("<!DOCTYPE html>\n", "")),
    ("表格少闭一个标签", 1, "table.table.html", sub("</table>", "")),
    ("正文里多一个没闭合的块", 1, "note.html", sub("</body>", "<div>多了个没关的块</body>")),
    ("标题写成别的东西", 7, "note.html", sub("<title>note</title>", "<title>别的标题</title>")),
    ("少说了一句", 3, "note.html", drop("开场白")),
    ("列表被摊平成段落", 2, "note.html", sub("<ul>", "<p>")),
    ("表头那行不再是表头", 5, "table.table.html",
     sub("<th>数量</th>", "<td>数量</td>")),
    ("链接地址改一个字母", 6, "note.html", sub("example.com", "examplo.com")),
    ("两层列表被并成一层", 8, "note.html", sub("<ul><li>更深的一点</li></ul>", "<li>更深的一点</li>")),
    ("网页与 Word 不再是同一套结构", 4, "head.html", sub("<h1>只有标题开头</h1>", "<p>只有标题开头</p>")),
]


def run_verify() -> tuple:
    run = subprocess.run([sys.executable, VERIFY], capture_output=True, text=True, encoding="utf-8")
    return run.returncode, re.findall(r"^FAIL (\d+) ", run.stdout, re.M), run.stdout + run.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for name, number, _, _ in TAMPERS:
            print("%d  %-18s %s" % (number, name, "产物"))
        return 0
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*HtmlWriteFixtureTest'" % BUILD)
        return 1
    originals = {}
    for name in sorted(os.listdir(BUILD)):
        with open(os.path.join(BUILD, name), "rb") as handle:
            originals[name] = handle.read()
    before = {key: hashlib.sha1(value).hexdigest() for key, value in originals.items()}

    def restore_all():
        for name, data in originals.items():
            with open(os.path.join(BUILD, name), "wb") as handle:
                handle.write(data)

    if "--comply" in argv:
        restore_all()
        code, _, output = run_verify()
        if code == 0:
            print("合规面：判据全绿（照规格做出来的页面被收）")
            return 0
        print("合规面就红了，反例还没测：\n%s" % output[-1500:])
        return 1

    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    misses = []
    tried = 0
    for (name, number, artifact, edit) in TAMPERS:
        if only and only != name:
            continue
        tried += 1
        path = os.path.join(BUILD, artifact)
        try:
            text = originals[artifact].decode("utf-8")
            mutated = edit(text)
            if mutated == text:
                raise AssertionError("改了个空")
        except AssertionError as error:
            print("反例失效 %-24s %s" % (name, error))
            misses.append((name, str(error)))
            continue
        with open(path, "wb") as handle:
            handle.write(mutated.encode("utf-8"))
        code, reds, _ = run_verify()
        restore_all()
        if code == 0:
            print("没牙   %-26s 判据 %d：塞了这个缺陷裁判还是全绿" % (name, number))
            misses.append((name, "裁判全绿"))
        elif str(number) not in reds:
            print("点错名 %-26s 期望红 %d，实际红了 %s" % (name, number, sorted(set(reds))))
            misses.append((name, "没点到 %d，只点了 %s" % (number, sorted(set(reds)))))
        else:
            print("有牙   %-26s 判据 %d 红了（共 %d 条红）" % (name, number, len(reds)))

    after = {}
    for name in originals:
        with open(os.path.join(BUILD, name), "rb") as handle:
            after[name] = hashlib.sha1(handle.read()).hexdigest()
    if after != before:
        print("产物没还原干净：%s" % [k for k in before if before[k] != after[k]])
        return 1
    print("\n试了 %d 个反例，问题 %d 个；产物按 sha1 逐字节还原 ✓" % (tried, len(misses)))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
