#!/usr/bin/env python3
r"""
判"PDF 元数据：查看 / 清除 / 改写"那一步：改写单由 :core 那份 `PdfMeta.patch` 算，
`/Info` 由桌面 PDFBox 写（与 app 同一个 API 面），来料由 **PyMuPDF** 写，
读回来用 **MuPDF** 与 **pdfplumber** 两家独立解析各核一遍。

跑法（先落夹具与产物）：
    python tools/make_pdf_meta_fixtures.py
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:jar
    for i in 0 1 2 3 4 5 6 7; do PDFPROBE_OUT=D:/fflogs/pdfprobe bash tools/pdfprobe/run.sh pdfmeta \
        core/build/pdfmeta/meta.pdf core/build/pdfmeta/case$i.pdf core/build/pdfmeta/cases.tsv $i; done
    python tools/verify_pdf_meta.py

为什么来料不能让 PDFBox 写：这一条判的是"PDFBox 改完 /Info 别人还读不读得对"，
来料、改写、读回三边不能有两边同源。

十一条判据：
  1  清除把六个身份键全删了（两家都读到空）
  2  文件里自己加的自定义键（Company）也一起没了
  3  选"留着日期"时那两个日期还在，别的照删
  4  改写模式把填了的那几项真写进去了
  5  空着的那格是"别动它"：既不覆盖也不删除
  6  勾了盖时间，修改时间就是给的那个时刻，创建时间不动
  7  清除模式下不另盖修改时间（刚删的别写回去）
  8  超长的值截到上限，并说清截了多少
  9  正文一个字没被动，页数也不变
 10  什么都没填也没勾时间时，/Info 与来料完全一致
 11  说明诚实：notes 说改写几项，纸上就变几项
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

import fitz
import pdfplumber

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_pdf_meta_fixtures import CASES, INFO          # noqa: E402  夹具的来料与意图，判据不另编一份
from verify_odt_read import attempt, check, results     # noqa: E402

BUILD = os.path.join("core", "build", "pdfmeta")
IDENTITY = ["title", "author", "subject", "keywords", "creator", "producer"]
PDF_KEYS = ["Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Company"]
PDF_TO_MU = dict(zip(PDF_KEYS, ["title", "author", "subject", "keywords", "creator", "producer",
                                "creationDate", "modDate", "company"]))


def read_both(name: str) -> tuple:
    """两家独立读路：MuPDF 的 metadata 字典与 pdfplumber 的 /Info 字典。"""
    doc = fitz.open(os.path.join(BUILD, "%s.pdf" % name))
    mupdf = {key: (doc.metadata.get(key) or "").strip() for key in IDENTITY}
    mupdf["creationDate"] = (doc.metadata.get("creationDate") or "").strip()
    mupdf["modDate"] = (doc.metadata.get("modDate") or "").strip()
    info_ref = doc.xref_get_key(-1, "Info")
    raw = doc.xref_object(int(info_ref[1].split()[0])) if info_ref[0] == "xref" else ""
    pages = doc.page_count
    text = "".join(page.get_text() for page in doc)
    doc.close()
    plumber = pdfplumber.open(os.path.join(BUILD, "%s.pdf" % name))
    # pdfplumber 给的是 PDF 里的键名（Title / ModDate），MuPDF 给的是小写名（title / modDate）：
    # 两边归成同一套小写名，判据里就不用记两套
    meta = {PDF_TO_MU[key]: (plumber.metadata.get(key) or "").strip() for key in PDF_KEYS}
    plumber.close()
    return mupdf, meta, raw, pages, text


def main() -> int:
    missing = [name for name in ["meta"] + ["case%d" % k for k in range(len(CASES))]
               if not os.path.isfile(os.path.join(BUILD, "%s.pdf" % name))
               or os.path.getsize(os.path.join(BUILD, "%s.pdf" % name)) == 0]
    missing += ["case%d" % i for i in range(len(CASES))
                if not os.path.isfile(os.path.join(BUILD, "case%d.notes.txt" % i))]
    if missing:
        print("红的是产物不在：%s" % "、".join(missing))
        print("先跑 tools/make_pdf_meta_fixtures.py、./gradlew :core:jar，再用 tools/pdfprobe/run.sh pdfmeta 出产物")
        return 1

    source = attempt(0, "读来料", lambda: read_both("meta"))
    if not isinstance(source, tuple):
        return 1
    cases = {index: attempt(index, "读 case%d" % index, lambda index=index: read_both("case%d" % index))
             for index in range(len(CASES))}
    if any(not isinstance(item, tuple) for item in cases.values()):
        return 1

    cleared_mu, cleared_pdf, cleared_raw, cleared_pages, _ = cases[0]
    # 第 9 号那一份走"只清标准字段"：这一档没有"扫剩下所有键"那一步兜底，
    # 六个点名删的键是不是真按名删了，只有它看得出来（两种清法都判，才算两层各守各的）
    only_mu, only_pdf, only_raw, _, _ = cases[8]
    check(1, "清除把六个身份键与两个日期全删了；「只清标准字段」那一档也照样删干净",
          all(not cleared_mu[key] and not cleared_pdf[key] for key in IDENTITY)
          and all(cleared_mu[key] == "" for key in ("creationDate", "modDate"))
          and all(not only_mu[key] and not only_pdf[key] for key in IDENTITY)
          and not only_mu["creationDate"] and not only_mu["modDate"],
          "全部清：%s；只清标准：%s" % ({k: v for k, v in cleared_mu.items() if v}, {k: v for k, v in only_mu.items() if v}))

    check(2, "「清除全部」把自定义键（Company）也扫掉，「只清标准字段」那一档留着它",
          "/Company" not in cleared_raw and not cleared_pdf["company"]
          and "/Company" in only_raw and bool(only_pdf["company"]),
          "全部清：%s；只清标准：%s" % (cleared_raw.replace("\n", " ")[:100], only_raw.replace("\n", " ")[:100]))

    keep_mu, keep_pdf, keep_raw, _, _ = cases[1]
    check(3, "选「留着日期」时那两个日期还在，别的照删",
          "/CreationDate" in keep_raw and "/ModDate" in keep_raw and keep_mu["creationDate"] == INFO["creationDate"]
          and all(not keep_pdf[key] for key in IDENTITY),
          "原始对象：%s；MuPDF 日期=%s,%s" % (keep_raw.replace("\n", " ")[:140], keep_mu["creationDate"], keep_mu["modDate"]))

    one_mu, one_pdf, _, _, _ = cases[2]
    check(4, "改写模式把填了的那项真写进去了（两家都读到新值）",
          one_mu["title"] == "新标题" and one_pdf["title"] == "新标题",
          "MuPDF=%r pdfplumber=%r" % (one_mu["title"], one_pdf["title"]))

    check(5, "空着的那格是「别动它」：既不覆盖也不删除",
          one_mu["author"] == INFO["author"] and one_pdf["author"] == INFO["author"]
          and cases[3][0]["author"] == INFO["author"] and cases[3][1]["author"] == INFO["author"]
          and cases[3][0]["producer"] == "换掉的",
          "case2 作者=%r；case3 作者=%r 转换工具=%r" % (one_mu["author"], cases[3][0]["author"], cases[3][0]["producer"]))

    stamp_mu, stamp_pdf, _, _, _ = cases[4]
    check(6, "勾了盖时间：修改时间就是给的那个时刻，创建时间不动",
          stamp_mu["modDate"].startswith("D:20231114") or stamp_mu["modDate"].startswith("D:20231115"),
          "ModDate=%s（应为 2023-11-14/15 那天）CreationDate=%s" % (stamp_mu["modDate"], stamp_mu["creationDate"]))

    clear_stamp_mu, _, clear_stamp_raw, _, _ = cases[6]
    check(7, "清除模式下不另盖修改时间（刚删掉的东西别再写回去）",
          "/ModDate" not in clear_stamp_raw and not clear_stamp_mu["modDate"],
          "原始对象：%s；MuPDF=%r" % (clear_stamp_raw.replace("\n", " ")[:120], clear_stamp_mu["modDate"]))

    long_mu, long_pdf, _, _, _ = cases[7]
    long_notes = open(os.path.join(BUILD, "case7.notes.txt"), encoding="utf-8").read()
    written = long_mu["subject"] or long_pdf["Subject"]
    check(8, "超长的值截到 400 个字并把截掉说清楚",
          len(written) == 400 and "460" in long_notes and "没写进去" in long_notes,
          "纸上 %d 个字；notes=%s" % (len(written), long_notes.strip()[:90]))

    origin_text = source[4]
    body_bad = []
    for index in range(len(CASES)):
        _mu, _pdf, _raw, pages, text = cases[index]
        if pages != source[3]:
            body_bad.append("case%d 页数 %d（来料 %d）" % (index, pages, source[3]))
        if "".join(text.split()) != "".join(origin_text.split()):
            body_bad.append("case%d 的正文被动了" % index)
    check(9, "正文一个字没被动，页数也不变", not body_bad, "; ".join(body_bad[:2]))

    untouched_mu, untouched_pdf, untouched_raw, _, _ = cases[5]
    # 一个键都不该动：两家的读数与来料逐键相同，或原始 /Info 对象文本一字不差
    same = all(untouched_pdf[key] == source[1][key] for key in PDF_TO_MU.values()) \
        or untouched_raw.strip(" \n<>") == source[2].strip(" \n<>")
    check(10, "什么都没填也没勾时间：/Info 与来料一个字节都不差", bool(same),
          "改后：%s；来料：%s" % (untouched_raw.replace("\n", " ")[:120], source[2].replace("\n", " ")[:120]))

    honesty = []
    for index, row in enumerate(CASES):
        notes = open(os.path.join(BUILD, "case%d.notes.txt" % index), encoding="utf-8").read()
        claimed = re.findall(r"改写 (\d+) 项", notes)
        changed = 0
        if row[0] == "edit":
            for cell in row[4:]:
                key, _, value = cell.partition("=")
                pdf_key = {"Title": "title", "Author": "author", "Subject": "subject",
                           "Keywords": "keywords", "Creator": "creator", "Producer": "producer"}.get(key)
                if value.strip() and pdf_key and cases[index][0][pdf_key] != (source[0].get(pdf_key) or ""):
                    changed += 1
        if claimed and int(claimed[0]) != changed:
            honesty.append("case%d notes 说改写 %s 项，纸上实际变了 %d 项" % (index, claimed[0], changed))
    check(11, "说明诚实：notes 说改写几项，纸上就变几项", not honesty, "; ".join(honesty[:2]))

    failed = [item for item in results if not item[2]]
    print("\n共 %d 条，%d 条红" % (len(results), len(failed)))
    for number, name, _ in failed:
        print("  红的是第 %d 条：%s" % (number, name))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
