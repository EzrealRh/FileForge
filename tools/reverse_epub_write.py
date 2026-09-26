#!/usr/bin/env python3
"""
给 tools/verify_epub_write.py 喂反例，证明它每条判据都有牙。

跑法：
    python tools/reverse_epub_write.py            # 逐个塞缺陷，断言点名到该点的那条
    python tools/reverse_epub_write.py --comply   # 不动产物再判一遍，断言"照规格做的会被收"
    python tools/reverse_epub_write.py --list

只动 core/build/epubwrite/ 里的产物（build 目录，不是仓库文件）：
原字节一开始就整批留在内存里，每个反例跑完立刻按原字节写回，
最后一律核对 sha1 —— 下一轮（正常验证）拿到的必须还是 JVM 刚打出来的那份。

一个反例只破一处：一张夹具混两个条件，红了也不知道是哪一个红的。
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

BUILD = os.path.join("core", "build", "epubwrite")
VERIFY = os.path.join("tools", "verify_epub_write.py")


def rewrite(path: str, changes: dict, drops=()) -> None:
    """按 changes（条目名 → 改写函数）重打包，drops 里的条目删掉。

    重建时照 EPUB 的硬规矩把 mimetype 摆第一且不压缩 —— 不然反例还没跑到就把判据 1 顺带红了，
    等于每个反例都红两条，点名的意义就淡了。
    """
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        blobs = {name: archive.read(name) for name in names}
    ordered = ([n for n in names if n != "mimetype"])
    if "mimetype" in blobs:
        ordered = ["mimetype"] + ordered
    with zipfile.ZipFile(path, "w") as sink:
        for name in ordered:
            if name in drops:
                continue
            data = blobs[name]
            if name in changes:
                data = changes[name](data.decode("utf-8")).encode("utf-8")
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_STORED if name == "mimetype" else zipfile.ZIP_DEFLATED
            sink.writestr(info, data)


def sub(old: str, new: str):
    def edit(text: str) -> str:
        if old not in text:
            raise AssertionError("反例没打上：找不到 %r" % old)
        return text.replace(old, new, 1)
    return edit


def chain(*edits):
    def edit(text: str) -> str:
        for step in edits:
            text = step(text)
        return text
    return edit


def drop_word(word: str):
    def edit(text: str) -> str:
        if word not in text:
            raise AssertionError("反例没打上：正文里没有 %r" % word)
        return text.replace(word, "", 1)
    return edit


def order_only(path: str, stored_first: bool, order: list) -> None:
    """只改包里的条目顺序 / mimetype 的压缩方式，内容一字不动。"""
    with zipfile.ZipFile(path) as archive:
        blobs = {name: archive.read(name) for name in archive.namelist()}
    with zipfile.ZipFile(path, "w") as sink:
        for name in order:
            info = zipfile.ZipInfo(name)
            info.compress_type = (zipfile.ZIP_STORED if name == "mimetype" else zipfile.ZIP_DEFLATED) if stored_first else zipfile.ZIP_DEFLATED
            sink.writestr(info, blobs[name])


XHTML11_DTD = ('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" '
               '"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">')

TAMPERS = [
    ("mimetype 排第二", 1, "note",
     lambda path: order_only(path, True, [n for n in zipfile.ZipFile(path).namelist() if n != "mimetype"] + ["mimetype"])),
    ("mimetype 被压缩了", 1, "note",
     lambda path: order_only(path, False, zipfile.ZipFile(path).namelist())),
    ("少了 nav 那一份目录", 1, "note", lambda path: rewrite(path, {}, drops=["OEBPS/nav.xhtml"])),
    ("章节文件少闭一个标签", 2, "note",
     lambda path: rewrite(path, {"OEBPS/text/ch2.xhtml": sub("</body>", "</p></body>")})),
    ("DTD 指向外面的地址", 2, "note",
     lambda path: rewrite(path, {"OEBPS/text/ch1.xhtml": sub("<!DOCTYPE html>", XHTML11_DTD)})),
    ("spine 指了个不存在的项", 3, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub('idref="c2"', 'idref="c9"')})),
    ("章序被排乱了", 3, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub('<itemref idref="c1"/><itemref idref="c2"/>',
                                                          '<itemref idref="c2"/><itemref idref="c1"/>')})),
    ("清单里的正文没排进顺序", 3, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub('<itemref idref="c3"/>', "")})),
    ("清单指的文件不在包里", 3, "note",
     lambda path: rewrite(path, {}, drops=["OEBPS/text/ch3.xhtml"])),
    ("语言标错了", 4, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub("<dc:language>zh</dc:language>", "<dc:language>en</dc:language>")})),
    ("书号被换了", 4, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub("urn:uuid:", "urn:uuid:0000000")})),
    ("没有作者却写了一个", 4, "head",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub("</dc:date>", "</dc:date><dc:creator>凭空的人</dc:creator>")})),
    ("NCX 的章名与正文不一致", 5, "note",
     lambda path: rewrite(path, {"OEBPS/toc.ncx": sub("<text>第二章 收尾</text>", "<text>别的名字</text>")})),
    ("nav 指了个不存在的文件", 5, "note",
     lambda path: rewrite(path, {"OEBPS/nav.xhtml": sub("text/ch3.xhtml", "text/ch9.xhtml")})),
    ("有序列表被写成无序", 6, "note",
     lambda path: rewrite(path, {"OEBPS/text/ch2.xhtml": chain(sub("<ol>", "<ul>"), sub("</ol>", "</ul>"))})),
    ("少了一句话里的一个词", 7, "note",
     lambda path: rewrite(path, {"OEBPS/text/ch1.xhtml": drop_word("开场白")})),
    ("书名从元数据里没了", 8, "note",
     lambda path: rewrite(path, {"OEBPS/content.opf": sub("<dc:title>读书笔记甲乙</dc:title>", "")})),
]


def run_verify() -> tuple:
    run = subprocess.run([sys.executable, VERIFY], capture_output=True, text=True, encoding="utf-8")
    return run.returncode, re.findall(r"^FAIL (\d+) ", run.stdout, re.M), run.stdout + run.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for name, number, stem, _ in TAMPERS:
            print("%d  %-11s %s" % (number, stem, name))
        return 0
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*EpubWriteFixtureTest'" % BUILD)
        return 1
    originals = {}
    for name in sorted(os.listdir(BUILD)):
        if name.endswith(".epub"):
            with open(os.path.join(BUILD, name), "rb") as handle:
                originals[name] = handle.read()
    digest_before = {key: hashlib.sha1(value).hexdigest() for key, value in originals.items()}

    def restore_all():
        for name, data in originals.items():
            with open(os.path.join(BUILD, name), "wb") as handle:
                handle.write(data)

    if "--comply" in argv:
        restore_all()
        code, reds, output = run_verify()
        if code == 0:
            print("合规面：判据全绿（照规格做出来的产物被收）")
            return 0
        print("合规面就红了，反例还没测：\n%s" % output[-1500:])
        return 1

    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    misses = []
    tried = 0
    for (name, number, stem, mutate) in TAMPERS:
        if only and only != name:
            continue
        tried += 1
        path = os.path.join(BUILD, "%s.epub" % stem)
        try:
            mutate(path)
        except AssertionError as error:
            print("反例失效 %-20s %s" % (name, error))
            misses.append((name, str(error)))
            restore_all()
            continue
        code, reds, _ = run_verify()
        restore_all()
        if code == 0:
            print("没牙   %-22s 判据 %d：塞了这个缺陷裁判还是全绿" % (name, number))
            misses.append((name, "裁判全绿"))
        elif str(number) not in reds:
            print("点错名 %-22s 期望红 %d，实际红了 %s" % (name, number, sorted(set(reds))))
            misses.append((name, "没点到 %d，只点了 %s" % (number, sorted(set(reds)))))
        else:
            print("有牙   %-22s 判据 %d 红了（共 %d 条红）" % (name, number, len(reds)))

    digest_after = {}
    for name in originals:
        with open(os.path.join(BUILD, name), "rb") as handle:
            digest_after[name] = hashlib.sha1(handle.read()).hexdigest()
    if digest_after != digest_before:
        print("产物没还原干净：%s" % [k for k in digest_before if digest_before[k] != digest_after[k]])
        return 1
    print("\n试了 %d 个反例，问题 %d 个；产物按 sha1 逐字节还原 ✓" % (tried, len(misses)))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
