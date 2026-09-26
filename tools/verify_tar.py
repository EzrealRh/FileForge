#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 **Python 的 tarfile 与 gzip** 复核 Kotlin 的 tar 读与写。

被复核的产物（按顺序先跑）：
    python tools/make_tar_fixtures.py
    GRADLE_USER_HOME=D:/gradle-home ./gradlew :core:test --tests 'com.fileforge.core.TarFixtureTest'

七条判据各自的分工：
 1) 读得对      —— 每份夹具我们落的成员清单，与 tarfile 自己读同一份文件逐字段相同
 2) 写得对      —— tarfile 读我们写的 .tar：成员一字不多一字不少（长名字与 PAX 记账不能漏成可见文件）
 3) gz 层也对    —— 我们写的 .tar.gz 用 tarfile 一次读通，成员与 2 相同
 4) 头里的名字    —— 我们写的 gzip 头 FNAME 按 RFC 1952 的位置自己解一遍核对（gzip 模块不暴露这个字段）
 5) 单个 gz      —— 我们 ungzip 出来的内容 sha 与夹具声明相同，FNAME 也照位置解出来对得上
 6) 坏包停在一处  —— 头块校验和坏了，我们停在哪、tarfile 停在哪，两边必须是同一处
 7) 类型判据一致  —— 符号链接 / 字符设备这些，两边认出来的语义相同（谁都不把它们当普通文件）
"""
import gzip
import hashlib
import io
import json
import os
import struct
import sys
import tarfile

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RES = os.path.join(ROOT, "core", "src", "test", "resources", "tar")
OUT = os.path.join(ROOT, "core", "build", "tar")

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def manifest():
    return json.loads(io.open(os.path.join(RES, "manifest.json"), encoding="utf-8").read())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tarfile_rows(path):
    """tarfile 自己读一遍：名字（抹掉目录末尾斜杠）、长度、时间、语义类型、内容 sha。"""
    rows = []
    with tarfile.open(path, "r:*") as pack:
        for info in pack:
            data = b""
            if info.isreg() and info.size:
                data = pack.extractfile(info).read()
            char = info.type.decode("ascii") if isinstance(info.type, bytes) else str(info.type)
            kind = {"0": "file", "\x00": "file", "5": "dir", "2": "symlink", "1": "link",
                    "3": "char", "4": "block", "6": "fifo", "L": "longname", "x": "pax",
                    "g": "pax-global", "S": "sparse", "V": "volume"}.get(char, "other:" + char)
            rows.append("%s\t%d\t%d\t%s\t%s" % (info.name.rstrip("/"), info.size, int(info.mtime), kind, sha(data)))
    return rows


def our_rows(archive):
    """我们落的那份清单里，属于这个包的那些行（去掉开头的包名列）。"""
    out = []
    for line in io.open(os.path.join(OUT, "listing.txt"), encoding="utf-8").read().splitlines():
        parts = line.split("\t")
        if parts and parts[0] == archive:
            out.append("\t".join(parts[1:]))
    return out


def gunzip_name(path):
    """按 RFC 1952 的字段位置自己解 FNAME（gzip 模块读得动但不暴露这个字段）。"""
    with open(path, "rb") as handle:
        raw = handle.read(256)
    assert raw[:2] == b"\x1f\x8b", "不是 gzip"
    flags = raw[3]
    at = 10
    if flags & 4:                                   # FEXTRA
        at += struct.unpack("<H", raw[at:at + 2])[0] + 2
    if flags & 8:                                   # FNAME
        return raw[at:raw.index(0, at)].decode("utf-8")
    return None


def main():
    man = manifest()

    # 1) 读得对：每份夹具两边逐字段相同
    mismatch = []
    for archive in man["archives"]:
        name = archive["file"]
        mine, theirs = our_rows(name), tarfile_rows(os.path.join(RES, name))
        if mine != theirs:
            mismatch.append("%s：我们 %d 条 / tarfile %d 条" % (name, len(mine), len(theirs)))
            for index in range(max(len(mine), len(theirs))):
                left = mine[index] if index < len(mine) else "<缺>"
                right = theirs[index] if index < len(theirs) else "<缺>"
                if left != right:
                    mismatch.append("   第 %d 条 我们[%s] 别人[%s]" % (index + 1, left[:70], right[:70]))
                    break
    check("每份包我们读出来的成员清单与 tarfile 逐字段相同", not mismatch, "\n".join(mismatch[:8]))

    # 2) 写得对：tarfile 读我们写的包
    # 我们写的那份是拿哪份夹具当料写的，由落盘的一侧说，判据不重复钉一个常量
    source = io.open(os.path.join(OUT, "written-from.txt"), encoding="utf-8").read().strip()
    want = our_rows(source)
    written = tarfile_rows(os.path.join(OUT, "written.tar"))
    want_files = [line for line in want if line.split("\t")[3] == "file"]
    check(
        "tarfile 读我们写的 tar：成员一字不多一字不少",
        written == want_files,
        "我们写的 %s\n   期望 %s" % (written, want_files),
    )

    # 3) gz 层
    check(
        "tarfile 一次读通我们写的 tar.gz，成员与上面相同",
        tarfile_rows(os.path.join(OUT, "written.tar.gz")) == want_files,
        str(tarfile_rows(os.path.join(OUT, "written.tar.gz"))),
    )

    # 4) 我们写的 gzip 头里的 FNAME
    check(
        "我们写的 gz 头里 FNAME 按 RFC 1952 的位置解出来是 written.tar",
        gunzip_name(os.path.join(OUT, "written.tar.gz")) == "written.tar",
        str(gunzip_name(os.path.join(OUT, "written.tar.gz"))),
    )

    # 5) 单个 gz：内容与名字
    gz = man["gz"]
    body = open(os.path.join(OUT, "gunzip.bin"), "rb").read()
    check(
        "我们 ungzip 的产物与夹具声明的内容一字不差，头里的原名也对",
        sha(body) == gz["sha"] and len(body) == gz["size"] and
        io.open(os.path.join(OUT, "gunzip-name.txt"), encoding="utf-8").read().strip() == gz["name"],
        "sha 对不上或名字不对",
    )
    check("Python 自己也能读通这份 gz（内容相同）", sha(gzip.decompress(open(os.path.join(RES, gz["file"]), "rb").read())) == gz["sha"])

    # 6) 坏包：两边停在同一处
    broken_rows = io.open(os.path.join(OUT, "broken.txt"), encoding="utf-8").read().splitlines()
    broken = [line for line in broken_rows if line.strip()]
    check(
        "头块校验和坏了，我们与 tarfile 停在同一条上",
        len(broken) == man["broken"]["good_before"] and
        [line.split("\t")[1] for line in broken] == man["broken"]["names"],
        "我们 %d 条 %s / tarfile %d 条 %s" % (
            len(broken), [l.split("\t")[1] for l in broken],
            man["broken"]["good_before"], man["broken"]["names"]),
    )
    notes = io.open(os.path.join(OUT, "notes.txt"), encoding="utf-8").read()
    check("坏包这件事写进了说明里", "校验和" in notes, notes.strip())

    # 7) 非普通文件的语义两边一致（逐包比：不同包里的同名条目不能互相盖掉判断）
    total, wrong = 0, []
    for archive in man["archives"]:
        mine = {line.split("\t")[0]: line.split("\t")[3] for line in our_rows(archive["file"])}
        theirs = {line.split("\t")[0]: line.split("\t")[3] for line in tarfile_rows(os.path.join(RES, archive["file"]))}
        for name, kind in theirs.items():
            if kind == "file":
                continue
            total += 1
            if mine.get(name) != kind:
                wrong.append("%s %s：我们 %s / tarfile %s" % (archive["file"], name, mine.get(name), kind))
    check("符号链接、设备与目录两边认成同一种东西", total > 0 and not wrong, "\n".join(wrong[:6]))

    failed = 0
    for ok, name, detail in results:
        print("%s %s%s" % ("PASS" if ok else "FAIL", name, "" if ok or not detail else "\n      " + detail))
        failed += 0 if ok else 1
    print("tar 判据 %d 条 · 红 %d 条" % (len(results), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
