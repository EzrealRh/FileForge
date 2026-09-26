#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「tar / gzip」这套判据有没有牙：两段，改坏实现与改坏产物。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
 第一段改源码，每轮都重跑落盘测试；单元测试先红的也算拦住（那是第一层）。
 第二段只改坏落盘的产物：外部那 9 条判据得真的在读这些字节，不能因为第一层先红就显不出牙。
有条判据没牙就退出码非 0。

跑法：python tools/reverse_tar.py
"""
import gzip
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
ARCHIVE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "archive")
TAR = os.path.join(ARCHIVE, "Tar.kt")
GZ = os.path.join(ARCHIVE, "Gzip.kt")
OUT_DIR = os.path.join(ROOT, "core", "build", "tar")
STAMP = os.path.join(OUT_DIR, "listing.txt")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.TarFixtureTest"'

# (改哪个文件, 说明, 原样, 改成, 期望红的那条判据)
BREAKS = [
    (TAR, "内容不按 512 补齐（下一条的起点全错位）",
     "at = dataAt + roundUp(size)", "at = dataAt + size", "成员清单与 tarfile 逐字段相同"),
    (TAR, "取内容时少取一个字节（尾巴那字节没人管）",
     "if (entry.size <= 0L) ByteArray(0) else slices.slice(entry.offset, entry.offset + entry.size)",
     "if (entry.size <= 0L) ByteArray(0) else slices.slice(entry.offset, entry.offset + entry.size - 1)",
     "成员清单与 tarfile 逐字段相同"),
    (TAR, "写长名字时不出 PAX 扩展头（别人读出来是截断的名字）",
     "if (path.isNotEmpty()) writePax(path, item, out)", "if (false) writePax(path, item, out)",
     "tarfile 读我们写的 tar"),
    (GZ, "文件名不写的长度上限被改成 0（FNAME 那格空了）",
     "private const val MAX_NAME_BYTES = 200", "private const val MAX_NAME_BYTES = 0",
     "我们写的 gz 头里 FNAME"),
    (GZ, "读 FNAME 时按 Latin-1 解（中文原名变乱码）",
     "name = String(bytes, at, stop - at, Charsets.UTF_8)",
     "name = String(bytes, at, stop - at, Charsets.ISO_8859_1)", "我们 ungzip 的产物与夹具声明的内容一字不差"),
    (TAR, "头块校验和不对也接着往后读（错位的条目当成新的成员）",
     'notes += "第 ${order + 1} 个 512 字节的头块校验和不对，到这里为止读得出来，后面的不能接着信"\n                break',
     'notes += "第 ${order + 1} 个 512 字节的头块校验和不对，到这里为止读得出来，后面的不能接着信"\n                at += BLOCK\n                continue',
     "头块校验和坏了"),
]


def flip(path, old, new):
    text = io.open(path, encoding="utf-8").read()
    assert text.count(old) == 1, "%s 里 %r 找到 %d 处" % (path, old[:30], text.count(old))
    io.open(path, "w", encoding="utf-8", newline="\n").write(text.replace(old, new))
    return text


def reds(text):
    return [line[5:].strip() for line in text.splitlines() if line.startswith("FAIL ")]


def run_verdict(label, expect, red):
    hit = [item for item in red if expect in item]
    return "%s -> 外部红了 %d 条 %s，本该红的「%s」%s" % (
        label, len(red), [item[:26] for item in red], expect, "红了" if hit else "没红（看上面哪条红）")


def run_case(label, expect):
    result = subprocess.run("python tools/verify_tar.py", cwd=ROOT, shell=True, capture_output=True, env=ENV)
    text = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
    red = reds(text)
    if result.returncode != 0 and not red:
        red = ["判据脚本整个崩了"]
    return run_verdict(label, expect, red)


def tamper_gz(expect):
    """改坏 gz 里那份 tar 的一个字节，再用 Python 的 gzip 重新压好（gz 层保持合法）。"""
    path = os.path.join(OUT_DIR, "written.tar.gz")
    original = open(path, "rb").read()
    body = gzip.decompress(original)
    broken = body[:512] + bytes([body[512] ^ 0xFF]) + body[513:]
    with open(path, "wb") as handle:
        handle.write(gzip.compress(broken, mtime=0))
    result = subprocess.run("python tools/verify_tar.py", cwd=ROOT, shell=True, capture_output=True, env=ENV)
    text = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
    red = reds(text)
    if result.returncode != 0 and not red:
        red = ["判据脚本整个崩了"]
    verdict = run_verdict("产物改坏（written.tar.gz 的内容，重新压好）", expect, red)
    with open(path, "wb") as handle:
        handle.write(original)
    return verdict


def cut(text, needle):
    return "\n".join(line for line in text.splitlines() if needle not in line) + "\n"


def tamper(name, break_it, expect):
    """只改坏落盘的产物：外部那几条判据得真的在读这些字节。"""
    path = os.path.join(OUT_DIR, name)
    binary = not name.endswith(".txt")
    raw = open(path, "rb").read()
    original = raw if binary else raw.decode("utf-8")
    broken = break_it(original)
    with open(path, "wb") as handle:
        handle.write(broken if binary else broken.encode("utf-8"))
    result = subprocess.run("python tools/verify_tar.py", cwd=ROOT, shell=True, capture_output=True, env=ENV)
    text = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
    red = reds(text)
    if result.returncode != 0 and not red:
        red = ["判据脚本整个崩了"]
    verdict = run_verdict("产物改坏（%s）" % name, expect, red)
    with open(path, "wb") as handle:
        handle.write(original if binary else original.encode("utf-8"))
    return verdict


def main():
    originals = {path: io.open(path, encoding="utf-8").read() for path in {b[0] for b in BREAKS}}
    verdicts = []
    try:
        for path, label, needle, replacement, expect in BREAKS:
            source = originals[path]
            if source.count(needle) != 1:
                verdicts.append("跳过（要改坏的那行没找到或有重，count=%d）：%s" % (source.count(needle), label))
                continue
            io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(needle, replacement, 1))
            build = subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)
            out = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                log = os.path.join("D:", os.sep, "fflogs", "reverse_tar.log")
                with io.open(log, "w", encoding="utf-8") as handle:
                    handle.write(out)
                why = "编译就红了" if any(line.startswith("e: ") for line in out.splitlines()) else "单元测试先红了"
                verdicts.append("%s -> %s（外部判据没跑到，少一层算多层保险）" % (label, why))
            else:
                verdicts.append(run_case(label, expect))
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
    finally:
        for path, source in originals.items():
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
        subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)

    listing = io.open(STAMP, encoding="utf-8").read().splitlines()
    symlink_line = next(line for line in listing if "\tsymlink\t" in line)
    verdicts.append(tamper("listing.txt", lambda t: t.replace(symlink_line, symlink_line.replace("symlink", "file")),
                           "符号链接、设备与目录两边认成同一种东西"))
    verdicts.append(tamper("written.tar", lambda raw: raw[:512] + bytes([raw[512] ^ 0xFF]) + raw[513:],
                           "tarfile 读我们写的 tar"))
    # 只动 gz 里那份 tar 的一个字节，再按 gzip 重新压好：这样"gz 层没问题、内容不对"
    # 这一类只能由第三条抓到（动原始字节会让 Python 直接报错，测不出判据的指向）
    verdicts.append(tamper_gz("tarfile 一次读通我们写的 tar.gz"))
    verdicts.append(tamper("gunzip.bin", lambda raw: raw + b"x", "我们 ungzip 的产物与夹具声明的内容一字不差"))
    verdicts.append(tamper("notes.txt", lambda t: cut(t, "校验和"), "坏包这件事写进了说明里"))
    verdicts.append(tamper("broken.txt", lambda t: t + "多出来的一条\t0\t0\tfile\t\n", "头块校验和坏了"))
    verdicts.append(tamper("written.tar.gz", lambda raw: raw[:10] + bytes([raw[10] ^ 0x20]) + raw[11:],
                           "我们写的 gz 头里 FNAME"))

    print("\n".join(verdicts))
    toothless = [v for v in verdicts if "没红" in v or "跳过" in v or "没测到" in v]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
