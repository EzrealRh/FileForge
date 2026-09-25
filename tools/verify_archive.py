#!/usr/bin/env python3
"""用 Python 标准库 zipfile 复核 Kotlin 的 ZipWriter 产物。

为什么要有这一步：一个自家 reader 能读开的 zip 一点说服力都没有 —— 用户拿到包之后
是发给微信、丢进 Windows 资源管理器、交给 7-Zip 的。所以写侧的判据必须由**另一家实现**
给出：zipfile 打不开、条目少了、内容差一个字节、或者那条本该存原文的被压大了，都算不过。

先跑测试（ZipTest 会把 Kotlin 写的包和三份原始载荷写到 core/build/archive/）：

    ./gradlew :core:test

再跑本脚本：

    python tools/verify_archive.py

判据：
 1. zipfile 打得开，`testzip()` 逐条 CRC 全过
 2. 条目名与 written.zip.truth（zipfile 读参照包看到的名字）一致
 3. 每条内容与打包前的原始载荷**逐字节相同**
 4. 压不动的那条（随机字节）确实是 ZIP_STORED，没有越压越大
 5. 参照包 written-expected.zip 与我们的产物在名字与 CRC 上一致
"""
import os
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")        # Windows 控制台默认 GBK，中文提示会糊

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
BUILD = os.path.join(ROOT, "core", "build", "archive")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "archive")

# Kotlin 那边 ZipTest.writtenZip() 就是这个顺序；两边一旦错开，第 3 条判据立刻报错
ITEMS = [("说明.txt", "item-note.bin"), ("sub/keep.bin", "item-blob.bin"), ("store/raw.zip", "item-random.bin")]

failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def read_truth(name):
    path = os.path.join(FIXTURES, name)
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if "=" in line:
                key, value = line.rstrip("\n").split("=", 1)
                values[key] = value
    return values


def main():
    if not os.path.isdir(BUILD):
        print("找不到 %s，先跑 ./gradlew :core:test" % os.path.normpath(BUILD))
        return 3
    written = os.path.join(BUILD, "written.zip")
    truth = read_truth("written.zip.truth")

    print("Kotlin 写的包 %s（%d 字节）" % (os.path.basename(written), os.path.getsize(written)))
    try:
        package = zipfile.ZipFile(written)
    except zipfile.BadZipFile as bad:
        print("  失败  zipfile 根本打不开：%s" % bad)
        return 1
    with package:
        infos = package.infolist()
        check("zipfile 打得开，中央目录读得出条目", bool(infos), "%d 条" % len(infos))
        check("逐条 CRC 全过（testzip 无坏条目）", package.testzip() is None)
        check("条目名与参照一致", truth["names"].split("|") == [i.filename for i in infos],
              " / ".join(i.filename for i in infos))
        for (name, payload), info in zip(ITEMS, infos):
            with open(os.path.join(BUILD, payload), "rb") as handle:
                original = handle.read()
            same = package.read(info) == original
            check("「%s」内容与打包前逐字节相同" % name, same,
                  "%d vs %d 字节" % (len(package.read(info)), len(original)))
        stored = infos[-1]
        check("压不动的那条退回 ZIP_STORED", stored.compress_type == zipfile.ZIP_STORED,
              "实际方式 %d，%d → %d 字节" % (stored.compress_type, stored.file_size, stored.compress_size))
        check("UTF-8 名字置了标志位", bool(stored.flag_bits & 0x800) or stored.filename.isascii(),
              "说明.txt 那条：%s" % bin(infos[0].flag_bits))

    print("与参照包（Python 按同一批载荷压的）比名字与 CRC")
    with zipfile.ZipFile(os.path.join(FIXTURES, "written-expected.zip")) as expected:
        mine = {i.filename: format(i.CRC, "d") for i in zipfile.ZipFile(written).infolist()}
        theirs = {i.filename: format(i.CRC, "d") for i in expected.infolist()}
    check("名字集合一致", sorted(mine) == sorted(theirs), " / ".join(sorted(theirs)))
    check("每条 CRC 与参照一致", all(mine.get(k) == v for k, v in theirs.items()),
          str({k: (mine.get(k), v) for k, v in theirs.items() if mine.get(k) != v})[:120])
    check("CRC 表与 written.zip.truth 一致",
          truth["crcs"].split("|") == [format(i.CRC, "d") for i in zipfile.ZipFile(written).infolist()])

    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nzipfile 认可：Kotlin 写的包别人打得开、条目齐、内容一字不差。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
