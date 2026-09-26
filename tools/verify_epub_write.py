#!/usr/bin/env python3
"""
判"写 EPUB"那一侧的产物：外部三方独立读同一份文件，各说各话才算数。

跑法（先跑 JVM 那边把产物落出来）：
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.EpubWriteFixtureTest' --rerun
    python tools/verify_epub_write.py

八条判据，每条一个编号 —— `tools/reverse_epub_write.py` 逐条喂缺陷，证明它们有牙：
  1 包的结构：mimetype 排第一且不压缩，必需部件齐全
  2 每份 XML/XHTML 良构，且没有一处 DTD 指向别人的地址
  3 OPF 的清单与包里的文件一一对得上，spine 的顺序就是声明的章序
  4 元数据：书名 / 语言 / 书号 / 作者 / 日期，与 manifest 逐项一致，container 指的 OPF 真在
  5 目录两份（NCX 与 nav）条数与章名都对得上，且指的文件都存在
  6 pandoc 读回来的**块序列**与 pandoc 直接读来料的块序列逐个相等
  7 pandoc 读回来的**文字**与来料本身的文字一字不差（不多字、不少字、不改字）
  8 pandoc 读回来的元数据（title / language / identifier）与 manifest 一致

期望值来自 core/src/test/resources/epubwrite/manifest.json，那份文件由
tools/make_epub_write_fixtures.py 用 Python 另一套实现算出。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

SRC = os.path.join("core", "src", "test", "resources", "epubwrite")
BUILD = os.path.join("core", "build", "epubwrite")

OPF_NS = "http://www.idpf.org/2007/opf"
DC_NS = "http://purl.org/dc/elements/1.1/"
CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
XHTML_NS = "http://www.w3.org/1999/xhtml"
EPUB_NS = "http://www.idpf.org/2007/ops"

REQUIRED = ["META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx", "OEBPS/nav.xhtml"]

results = []


def check(number: int, name: str, ok: bool, detail: str = "") -> bool:
    results.append((number, name, ok, detail))
    print("%s %d %s%s" % ("OK  " if ok else "FAIL", number, name, (" :: " + detail) if detail and not ok else ""))
    return ok


def pandoc(fmt: str, path: str, to: str = "json"):
    run = subprocess.run(["pandoc", "-f", fmt, "-t", to, "--wrap=none", path], capture_output=True, text=True, encoding="utf-8")
    if run.returncode != 0:
        raise RuntimeError("pandoc 读 %s（-%s）失败：%s" % (path, fmt, run.stderr.strip()[:300]))
    return run.stdout


def reader_for(source: str) -> str:
    """来料用哪个 pandoc 读者。

    markdown 那里要**关掉 smart**：pandoc 默认会把 `"开篇"` 折成弯引号，
    而我们那一侧是照原文搬字的，两边比的是"同一份内容"，不是排版口味。

    纯文本这份没有 plain/text 这个读者可用（本机的 pandoc 构建里就没编进来），
    退而用 markdown_strict-smart：来料里没有任何记号，剩下的规矩就是"空行分段"，
    与我们那侧 PlainDoc 的读法同一条 —— 来料一改就得回来看这条还成不成立。
    """
    if source.endswith(".md"):
        return "markdown-smart"
    if source.endswith(".html"):
        return "html"
    return "markdown_strict-smart"


def top_blocks(ast: dict) -> list:
    """顶层块的"类型(标题带上级次)"序列；空块不算。

    pandoc 的 epub 读者会在每个 spine 文档前塞一个空 Para（拿 pandoc 自己出的书读回来同样有），
    那是它的读法不是我们的书少了内容，所以两边都剔掉再比。
    """
    out = []
    for block in ast["blocks"]:
        kind = block["t"]
        if not inline_text(block):
            continue
        if kind == "Header":
            classes = block["c"][1][1]
            level = next((item.replace("Level", "") for item in classes if item.startswith("Level")), "")
            out.append("Header%s" % level)
        else:
            out.append(kind)
    return out


def inline_text(node) -> str:
    """把 AST 里散着的文字串起来。

    Str / Space / Code 之外还要收 RawInline：Markdown 里的 `<尖括号>` 这种写法 pandoc 认成
    一段原样 HTML（RawInline），丢掉它就变成"来料少了一段字"，比的是内容不是 pandoc 的口味。
    """
    if isinstance(node, dict):
        kind = node.get("t")
        content = node.get("c")
        if kind == "Str" and isinstance(content, str):
            return content
        if kind == "Space":
            return ""
        if kind in ("RawInline", "RawBlock"):
            # 这两者的 c 都是 [格式, 那段原样文字]：第一个是 "html" 这种标签，不是内容
            if isinstance(content, list) and len(content) > 1 and isinstance(content[1], str):
                return content[1]
            return ""
        if kind == "Code":
            # Code 是 [Attr, [Inline]]：属性里没有正文，取第二个
            if isinstance(content, list) and len(content) > 1:
                return inline_text(content[1])
            return inline_text(content)
        return inline_text(content)
    if isinstance(node, list):
        return "".join(inline_text(item) for item in node)
    return ""


def norm(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def main() -> int:
    if not os.path.isdir(BUILD):
        print("没有 %s，先跑 :core:test --tests '*EpubWriteFixtureTest'" % BUILD)
        return 1
    with open(os.path.join(SRC, "manifest.json"), encoding="utf-8") as handle:
        manifest = json.load(handle)

    for case in manifest["cases"]:
        stem = os.path.splitext(case["source"])[0]
        path = os.path.join(BUILD, "%s.epub" % stem)
        if not os.path.exists(path):
            check(1, "%s 产物存在" % stem, False, "没有 %s" % path)
            continue
        archive = zipfile.ZipFile(path)
        names = archive.namelist()

        # 1 包的结构
        first = names[0]
        info = archive.getinfo("mimetype")
        body = archive.read("mimetype").decode("utf-8")
        check(
            1,
            "%s 的 mimetype 排第一且不压缩" % stem,
            first == "mimetype" and info.compress_type == 0 and body == "application/epub+zip",
            "第一条是 %s，compress_type=%s，内容=%r" % (first, info.compress_type, body),
        )
        missing = [want for want in REQUIRED if want not in names]
        chapters = ["OEBPS/text/ch%d.xhtml" % i for i in range(1, len(case["chapters"]) + 1)]
        missing += [want for want in chapters if want not in names]
        check(1, "%s 必需部件齐全" % stem, not missing, "缺 %s（包里有 %s）" % (missing, names))

        # 2 良构 + 不带外部 DTD
        bad = []
        for name in names:
            if not (name.endswith(".xml") or name.endswith(".opf") or name.endswith(".xhtml") or name.endswith(".ncx")):
                continue
            raw = archive.read(name)
            try:
                ElementTree.fromstring(raw)
            except ElementTree.ParseError as error:
                bad.append("%s：%s" % (name, error))
                continue
            head = raw[:400].decode("utf-8", "replace")
            doctype = re.search(r"<!DOCTYPE[^>]*>", head, re.I)
            if doctype and doctype.group(0).strip() != "<!DOCTYPE html>":
                bad.append("%s 的 DTD 指向外面：%s" % (name, doctype.group(0)))
        check(2, "%s 每份 XML 良构且不指向别人的地址" % stem, not bad, "；".join(bad))

        opf = ElementTree.fromstring(archive.read("OEBPS/content.opf"))
        container = ElementTree.fromstring(archive.read("META-INF/container.xml"))

        # 3 清单 ↔ 文件 ↔ spine
        items = {}
        properties = {}
        order = []
        for node in opf.iter():
            if node.tag == "{%s}item" % OPF_NS:
                items[node.get("id")] = node.get("href")
                properties[node.get("id")] = (node.get("properties") or "").split()
            elif node.tag == "{%s}itemref" % OPF_NS:
                order.append(node.get("idref"))
        unresolved = []
        resolved = {}
        for item_id, href in items.items():
            path_in_zip = "OEBPS/" + href
            resolved[item_id] = path_in_zip
            if path_in_zip not in names:
                unresolved.append("%s → %s" % (item_id, path_in_zip))
        dangling = [ref for ref in order if ref not in items]
        not_in_spine = [
            item_id
            for item_id, href in items.items()
            if href.endswith(".xhtml") and "nav" not in properties[item_id] and item_id not in order
        ]
        check(
            3,
            "%s 清单与文件与 spine 对得上" % stem,
            not unresolved and not dangling and not not_in_spine,
            "清单指向不存在的文件 %s；spine 里没这个项 %s；没排进顺序的正文 %s" % (unresolved, dangling, not_in_spine),
        )
        want_paths = ["OEBPS/text/ch%d.xhtml" % i for i in range(1, len(case["chapters"]) + 1)]
        check(
            3,
            "%s 的 spine 顺序就是章序" % stem,
            [resolved.get(ref) for ref in order] == want_paths,
            "%s 而非 %s" % ([resolved.get(ref) for ref in order], want_paths),
        )

        # 4 元数据
        def dc(tag: str):
            node = opf.find(".//{%s}%s" % (DC_NS, tag))
            return node.text if node is not None else None

        modified = None
        for node in opf.iter():
            if node.tag == "{%s}meta" % OPF_NS and node.get("property") == "dcterms:modified":
                modified = node.text
        rootfile = container.find(".//{%s}rootfile" % CONTAINER_NS)
        meta_ok = (
            dc("title") == case["title"]
            and dc("language") == case["language"]
            and dc("identifier") == case["identifier"]
            and dc("date") == manifest["modified"]
            and modified == manifest["modified"]
            and rootfile is not None
            and rootfile.get("full-path") == "OEBPS/content.opf"
        )
        author_node = opf.find(".//{%s}creator" % DC_NS)
        if case["author"]:
            meta_ok = meta_ok and author_node is not None and author_node.text == case["author"]
        else:
            meta_ok = meta_ok and author_node is None
        check(
            4,
            "%s 的元数据与 manifest 一致" % stem,
            meta_ok,
            "title=%r language=%r identifier=%r date=%r modified=%r creator=%r rootfile=%r"
            % (dc("title"), dc("language"), dc("identifier"), dc("date"), modified,
               author_node.text if author_node is not None else None,
               rootfile.get("full-path") if rootfile is not None else None),
        )

        # 5 目录两份
        ncx = ElementTree.fromstring(archive.read("OEBPS/toc.ncx"))
        nav = ElementTree.fromstring(archive.read("OEBPS/nav.xhtml"))
        ncx_points = [node for node in ncx.iter("{%s}navPoint" % NCX_NS)]
        ncx_labels = [point.find("{%s}navLabel/{%s}text" % (NCX_NS, NCX_NS)).text for point in ncx_points]
        ncx_srcs = [point.find("{%s}content" % NCX_NS).get("src") for point in ncx_points]
        toc = nav.find(".//{%s}nav[@{%s}type='toc']" % (XHTML_NS, EPUB_NS))
        nav_pairs = [
            (a.get("href"), "".join(a.itertext()).strip())
            for a in (toc.iter("{%s}a" % XHTML_NS) if toc is not None else [])
        ]
        want_paths_relative = [path.split("OEBPS/", 1)[1] for path in want_paths]
        clauses = {
            "NCX 名": ncx_labels == case["chapters"],
            "NCX 路径": ["OEBPS/" + src for src in ncx_srcs] == want_paths,
            "nav 路径": [href for href, _ in nav_pairs] == want_paths_relative,
            "nav 名": [label for _, label in nav_pairs] == case["chapters"],
            "nav 指的文件在": all(("OEBPS/" + href) in names for href, _ in nav_pairs),
        }
        nav_ok = all(clauses.values())
        check(
            5,
            "%s 的两份目录都对得上章名与文件" % stem,
            nav_ok,
            "没过的是 %s；NCX 名 %r / 路径 %r；nav 名 %r / 路径 %r；声明 %r；要 %r"
            % ([key for key, value in clauses.items() if not value], ncx_labels, ncx_srcs,
               [label for _, label in nav_pairs], [href for href, _ in nav_pairs], case["chapters"], want_paths_relative),
        )

        # 6 / 7 / 8 拿 pandoc 独立读回来
        # 读不回来本身就是一种红：一份"自家能读、别人读不动"的书不该只在判据 2 露头
        try:
            from_epub = json.loads(pandoc("epub", path))
            from_source = json.loads(pandoc(reader_for(case["source"]), os.path.join(SRC, case["source"])))
        except RuntimeError as error:
            check(6, "%s 能被 pandoc 独立读回来" % stem, False, str(error))
            continue
        check(
            6,
            "%s 的块序列与来料一致" % stem,
            top_blocks(from_epub) == top_blocks(from_source),
            "读回来 %s；来料 %s" % (top_blocks(from_epub), top_blocks(from_source)),
        )
        epub_text = norm(inline_text(from_epub["blocks"]))
        source_text = norm(inline_text(from_source["blocks"]))
        check(7, "%s 的文字一字不差" % stem, epub_text == source_text, diff_where(epub_text, source_text))

        meta = from_epub.get("meta", {})

        def meta_text(key: str) -> str:
            node = meta.get(key)
            return norm(inline_text(node)) if node else ""

        check(
            8,
            "%s 被 pandoc 读出的元数据一致" % stem,
            meta_text("title") == norm(case["title"])
            and meta_text("language") == case["language"]
            and meta_text("identifier") == case["identifier"],
            "pandoc 看到 title=%r language=%r identifier=%r" % (meta.get("title"), meta.get("language"), meta.get("identifier")),
        )

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    return 1 if failed else 0


def diff_where(a: str, b: str) -> str:
    if a == b:
        return ""
    for index in range(min(len(a), len(b))):
        if a[index] != b[index]:
            return "第 %d 字起不同：读回来…%s… / 来料…%s…" % (index, a[max(0, index - 20):index + 20], b[max(0, index - 20):index + 20])
    return "长度不同：%d 对 %d，多出的是 %r" % (len(a), len(b), (a[len(b):] or b[len(a):])[:60])


if __name__ == "__main__":
    sys.exit(main())
