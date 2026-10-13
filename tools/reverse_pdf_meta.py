#!/usr/bin/env python3
r"""
给 tools/verify_pdf_meta.py 喂反例：把改写单（`:core` 的 `PdfMeta`）或写 /Info 那一步改坏一处，
断言判据点名到该点的那一条。

跑法：
    python tools/reverse_pdf_meta.py                 # 逐个改坏 → 重编 :core:jar → 重跑探针 → 跑判据 → 还原
    python tools/reverse_pdf_meta.py --only Company  # 名字里带 ASCII 片段的可以单跑
    python tools/reverse_pdf_meta.py --list

每轮先把产物删干净再重跑：判据读的是 PDF，jar 不重编或探针被跳过，判据就拿着上一轮的好产物说"全绿"，
那条反例会被误报成"没牙"（这台机器上真发生过一次）。源文件按原字节写回，收尾 sha1 核对。
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
BUILD = os.path.join(ROOT, "core", "build", "pdfmeta")
META = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf", "PdfMeta.kt")
PROBE = os.path.join(HERE, "pdfprobe", "PdfProbe.java")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
REBUILD = ("GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q && "
           "python tools/make_pdf_meta_fixtures.py && "
           "for i in 0 1 2 3 4 5 6 7 8; do "
           "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
           "bash tools/pdfprobe/run.sh pdfmeta core/build/pdfmeta/meta.pdf "
           "core/build/pdfmeta/case$i.pdf core/build/pdfmeta/cases.tsv $i || exit 1; done")
VERIFY = ["python", "tools/verify_pdf_meta.py"]
PRODUCTS = ["case%d.pdf" % i for i in range(9)] + ["case%d.notes.txt" % i for i in range(9)]

CASES = [
    {
        "判据": 1, "名字": "清除时身份键一个都不删 identity", "文件": META,
        "原样": "            FIELDS.forEach { (key, _) -> removes += key }",
        "改成": "            listOf<String>().forEach { key -> removes += key }",
    },
    {
        "判据": 2, "名字": "自定义键不扫了 Company", "文件": META,
        "原样": '            if (mode == "clear") removes += "*"                 // 文件自己加的自定义键',
        "改成": '            if (false) removes += "*"                          // 忘记扫自定义键',
    },
    {
        "判据": 3, "名字": "点名保留的日期没进单子 keeps", "文件": META,
        "原样": "            return Patch(writes, removes, if (keepDates) DATE_KEYS.map { it.first } else emptyList(), keepDates, false, notes)",
        "改成": "            return Patch(writes, removes, emptyList(), keepDates, false, notes)",
    },
    {
        "判据": 4, "名字": "填了的值没写进去 writes", "文件": META,
        "原样": "                else -> writes += key to given",
        "改成": "                else -> Unit",
    },
    {
        "判据": 5, "名字": "空着那格也被当成要清 blank", "文件": META,
        "原样": "                given.isEmpty() -> Unit",
        "改成": '                given.isEmpty() -> writes += key to ""',
    },
    {
        "判据": 6, "名字": "勾了盖时间却没盖上 stamp", "文件": META,
        "原样": "        return Patch(writes, removes, emptyList(), keepDates, stampNow && nowMillis > 0L, notes)",
        "改成": "        return Patch(writes, removes, emptyList(), keepDates, false, notes)",
    },
    {
        "判据": 7, "名字": "清除模式下也去盖修改时间 clearstamp", "文件": META,
        "原样": "            return Patch(writes, removes, if (keepDates) DATE_KEYS.map { it.first } else emptyList(), keepDates, false, notes)",
        "改成": "            return Patch(writes, removes, if (keepDates) DATE_KEYS.map { it.first } else emptyList(), keepDates, stampNow, notes)",
    },
    {
        "判据": 8, "名字": "超长的值不截断 long", "文件": META,
        "原样": "                given.length > MAX_VALUE -> {",
        "改成": "                given.length > MAX_VALUE * 1000 -> {",
    },
    {
        "判据": 9, "名字": "顺手把一页删了 page", "文件": PROBE,
        "原样": "                java.util.Calendar when = java.util.Calendar.getInstance();",
        "改成": "                java.util.Calendar when = java.util.Calendar.getInstance();\n                doc.removePage(0);",
    },
    {
        "判据": 10, "名字": "没填也动了文件 writeanyway", "文件": PROBE,
        "原样": "            PDDocumentInformation info = doc.getDocumentInformation();",
        "改成": "            PDDocumentInformation info = doc.getDocumentInformation();\n            info.setCreator(\"probe 加的\");",
    },
    {
        "判据": 11, "名字": "说明里的项数不实 count", "文件": META,
        "原样": '            notes += "改写 ${writes.size} 项：',
        "改成": '            notes += "改写 ${writes.size + 2} 项：',
    },
]


def wipe() -> None:
    if not os.path.isdir(BUILD):
        return
    for name in os.listdir(BUILD):
        if name.startswith("case") and (name.endswith(".pdf") or name.endswith(".notes.txt")):
            os.remove(os.path.join(BUILD, name))


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    made = all(os.path.isfile(os.path.join(BUILD, name)) for name in PRODUCTS)
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "error:" in line or "FAILED" in line]
    return run.returncode != 0, made, " / ".join(lines[:3]) or "退出码 %s" % run.returncode


def verifier() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    out = (run.stdout or "") + (run.stderr or "")
    reds = [int(n) for n in re.findall(r"^FAIL\s+(\d+)", out, re.M)]
    return reds, ("共 " not in out or "条红" not in out), out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-40s 判据 %2d  %s" % (case["名字"], case["判据"], os.path.basename(case["文件"])))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    files = sorted({case["文件"] for case in CASES})
    originals = {path: open(path, "rb").read() for path in files}
    digests = {path: hashlib.sha1(data).hexdigest() for path, data in originals.items()}
    print("开工：%s" % "、".join("%s sha1 %s…" % (os.path.basename(p), digests[p][:10]) for p in files))
    bad = []
    passed = 0
    try:
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            path = case["文件"]
            source = originals[path].decode("utf-8")
            hits = source.count(case["原样"])
            if hits != 1:
                bad.append(("锚点不唯一", case, "找到 %d 处" % hits))
                print("跳过 %-38s 锚点找到 %d 处（应当正好 1 处）" % (case["名字"], hits))
                continue
            wipe()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source.replace(case["原样"], case["改成"], 1))
            broken, made, clue = rebuilt()
            reds, crashed, out = verifier()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source)
            if hashlib.sha1(open(path, "rb").read()).hexdigest() != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
                print("停下：%s 还原后 sha1 不一致" % os.path.basename(path))
                return 1
            if broken:
                bad.append(("改不动（编译或探针先红了）", case, clue[:150]))
                print("%-42s 这一处改不动：编译/探针就红了" % case["名字"])
            elif not made:
                # 先问产物在不在：产物没落盘时判据只能报"产物不在"，那既不是红也不是崩
                bad.append(("没产物", case, clue or "这一轮没重写 PDF"))
                print("%-42s 没有产物：%s" % (case["名字"], clue[:110] or "未知"))
            elif crashed:
                bad.append(("判据崩了", case, (out.strip().splitlines() or ["无输出"])[-1][:150]))
                print("%-42s 判据崩了（不是红，是没跑成）" % case["名字"])
            elif case["判据"] in reds:
                passed += 1
                others = [n for n in reds if n != case["判据"]]
                print("%-42s 红的是第 %2d 条 ✔%s" % (
                    case["名字"], case["判据"], "（另外还红了 %s）" % others if others else ""))
            else:
                bad.append(("改不坏", case, "实际红了 %s" % (reds or "无")))
                print("%-42s 没红到第 %2d 条（实际红：%s）" % (case["名字"], case["判据"], reds or "无"))
    finally:
        for path in files:
            if hashlib.sha1(open(path, "rb").read()).hexdigest() != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
        wipe()
        _broken, made, clue = rebuilt()
        print("\n还原核对：%s；干净代码重落产物=%s" % (
            "、".join("%s sha1 %s（%s）" % (os.path.basename(p), hashlib.sha1(open(p, "rb").read()).hexdigest()[:10],
                                  "与开工前一致" if hashlib.sha1(open(p, "rb").read()).hexdigest() == digests[p] else "不一致")
                      for p in files), made))
        if not made:
            print("  警告：还原后没能重落产物，%s" % clue)
    print("\n共 %d 个反例跑到判定，%d 个没红到该点的条" % (passed + len(bad), len(bad)))
    for kind, case, detail in bad:
        print("  %s：%s（判据 %s）%s" % (kind, case["名字"], case["判据"], detail))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
