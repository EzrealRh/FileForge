#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「PDF → Word」这套判据有没有牙：挨个把实现改坏，看对应那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
每轮破坏都重跑 `:core:jar` → 探针（桌面版 PDFBox 读 + :core 那份判断层）→ verify_pdf_docx.py。

跑法：python tools/reverse_pdf_docx.py      （跑完自动还原，并按还原后的代码重落一次产物）
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
PDFDOC = os.path.join(ROOT, "core", "build", "pdfdoc")
CORE_DIR = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "pdf")
PROBE = os.path.join(HERE, "pdfprobe", "PdfProbe.java")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
# 探针那一步一律用 /d/... 这种写法：run.sh 拿冒号拼 javac 的 classpath，
# 传 D:/gradle-home 进去会被那个冒号切成两截（表现是"kotlin.jvm.functions 这个包不存在"）
BUILD = ("GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q && "
         "GRADLE_USER_HOME=/d/gradle-home PDFPROBE_OUT=/d/fflogs/pdfprobe "
         "bash tools/pdfprobe/run.sh pdfdoc core/build/pdfdoc/structure.pdf core/build/pdfdoc")

# (说明, 文件, 原样, 改成, 期望红的那条)
BREAKS = [
    ("标题的字号门槛抬到 1.5 倍（14pt 的二级标题不再是标题）", "PdfDoc.kt",
     "private const val HEADING_RATIO = 1.08f",
     "private const val HEADING_RATIO = 1.5f",
     "块序列与声明相同"),
    ("占比闸门放开（正文里 12pt 的拉丁文与单个大字符都被提成标题）", "PdfDoc.kt",
     "private const val MAX_HEADING_SHARE = 0.12f",
     "private const val MAX_HEADING_SHARE = 0.9f",
     "块序列与声明相同"),
    ("页眉页脚不再删（每页那行「第 N 页」混进正文）", "PdfDoc.kt",
     "private const val FURNITURE_RATIO = 0.6f",
     "private const val FURNITURE_RATIO = 5f",
     "字与声明逐序相同"),
    ("折行不再并段（一行一段）", "PdfDoc.kt",
     "private const val LEADING_RATIO = 1.9f",
     "private const val LEADING_RATIO = 0.5f",
     "块序列与声明相同"),
    ("断词的横线不去掉（exam-ples 原样留着）", "PdfDoc.kt",
     "if (last == '-' && first.isLowerCase()) return previous.substring(0, previous.length - 1) + next",
     "if (last == '-' && first.isLowerCase()) return previous + next",
     "拉丁文的词形与声明相同"),
    ("量的时候取行内最小字号（混排的那行量小了）", PROBE,
     "size = Math.max(size, position.getFontSizeInPt());",
     "size = size == 0f ? position.getFontSizeInPt() : Math.min(size, position.getFontSizeInPt());",
     "字号与位置与 pdfminer 独立量的一致"),
]


def path_of(filename):
    return os.path.join(CORE_DIR, filename) if filename.endswith(".kt") else filename


def rebuild():
    """重编 :core 的 jar 并重跑探针（产物必须对应改坏后的代码）。"""
    return subprocess.run(["bash", "-c", BUILD], cwd=ROOT, capture_output=True, env=ENV)


def stamp():
    return os.path.getmtime(os.path.join(PDFDOC, "structure.docx"))


def dump(text, tag):
    folder = os.path.join("D:", os.sep, "fflogs")
    os.makedirs(folder, exist_ok=True)
    with io.open(os.path.join(folder, "revpdf-%s.log" % tag), "w", encoding="utf-8") as handle:
        handle.write(text)


def main():
    targets = {path_of(name): io.open(path_of(name), encoding="utf-8").read()
               for _, name, _, _, _ in BREAKS}
    verdicts = []
    try:
        for index, (label, filename, needle, replacement, expect) in enumerate(BREAKS):
            path = path_of(filename)
            source = targets[path]
            if source.count(needle) != 1:
                verdicts.append("跳过（要改坏的那行没找到或有重，count=%d）：%s" % (source.count(needle), label))
                continue
            io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(needle, replacement, 1))
            before = stamp()
            build = rebuild()
            text = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                dump(text, index)
                verdicts.append("%s -> 编译或探针先红了（外部这套判据没测到，见 revpdf-%d.log）" % (label, index))
            elif stamp() == before:
                verdicts.append("%s -> 产物没被重写，这轮没测到" % label)
            else:
                result = subprocess.run("python tools/verify_pdf_docx.py", cwd=ROOT, shell=True,
                                        capture_output=True, env=ENV)
                out = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
                red = [line.strip()[3:] for line in out.splitlines() if line.strip().startswith("未过：")]
                if result.returncode != 0 and not red:      # 判据脚本整个崩了也算红
                    red = ["判据脚本非 0 退出（见 revpdf-%d.log）" % index]
                    dump(out, index)
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> 外部判据红了 %d 条 %s，本该红的「%s」%s" % (
                    label, len(red), red, expect, "红了" if hit else "没红（看上面哪条红）"))
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
    finally:
        for path, text in targets.items():
            io.open(path, "w", encoding="utf-8", newline="\n").write(text)
        rebuild()                                           # 产物必须对应还原后的代码
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
