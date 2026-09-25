#!/usr/bin/env python3
"""用 Python 标准库 zipfile 造 zip 测试夹具，并记下 zipfile 自己读回来的值当参照。

为什么要另一家实现：zip 的判据是"这份目录别人读不读得对"。EOCD 位置、zip64 哨兵、
名字编码标志位这些，自家写自家读永远一致，坏处要到别人手上才暴露。所以——

  读侧：本脚本压出 plain / names / comment / gbk 四份包，`.truth` 记的是 zipfile **读回来**
        看到的名字表、原始长度、压缩方式与 CRC（不是我以为写进去的）。
  写侧：本脚本按同一批载荷压一份"参照包" written-expected.zip，`.truth` 记它的名字与 CRC；
        Kotlin 的 ZipWriter 产出对得上这些值，再由 tools/verify_archive.py 把 Kotlin 那份
        产物真交给 zipfile 打开核一遍。

输出到 core/src/test/resources/archive/。用法：python tools/make_archive_fixtures.py
"""
import os
import sys
import zipfile

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "archive"
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")   # Windows 控制台默认 GBK，中文提示会糊

GIBBERISH_NOTE = "你好，这是一份带中文名字的文本。重复重复重复重复"


# 与 Kotlin 那边 ZipItem.NO_DEFLATE 同一条规则：已经压过的类型再 Deflate 一遍只会变大。
# 两边各留一份正是有意的——written.zip.truth 记了 zipfile 实际选的方式，
# 两条表一旦漂开，ZipTest 的 methods 断言立刻红。
NO_DEFLATE = {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "bmp",
    "mp4", "mov", "mkv", "webm", "avi",
    "mp3", "aac", "m4a", "flac", "ogg", "opus", "wav",
    "zip", "jar", "apk", "rar", "7z", "gz", "bz2", "xz",
    "pdf",
}


def payloads():
    """写侧要打包的三份内容。Kotlin 那边直接读这三个 .bin，绝不复制第二份定义。"""
    import random
    return [
        ("说明.txt", GIBBERISH_NOTE.encode("utf-8")),
        ("sub/keep.bin", bytes(i % 251 for i in range(2048))),
        # 随机字节：任何 deflate 都压不动它；名字带 .zip 让"存原文"这条规则被测到
        ("store/raw.zip", random.Random(7).randbytes(4096)),
    ]


def build_plain(path):
    with zipfile.ZipFile(path, "w") as package:
        package.writestr("a.txt", b"hello from python\n" * 40, zipfile.ZIP_DEFLATED)
        package.writestr("notes.md", "# 标题\n中文与 english 混着写\n", zipfile.ZIP_DEFLATED)
        package.writestr("raw.bin", bytes(range(256)) * 3, zipfile.ZIP_STORED)


def build_names(path):
    with zipfile.ZipFile(path, "w") as package:
        for name, body in [("说明.txt", b"utf8"), ("照片/猫.jpg", b"\xff\xd8\xff\xd9"),
                           ("日本語/テスト.txt", b"nihongo")]:
            package.writestr(name, body, zipfile.ZIP_STORED)


def build_comment(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as package:
        package.writestr("a.txt", "带注释的包：EOCD 前面挂着这一段".encode("utf-8"))
        package.comment = "这一段注释让写死末尾 22 字节的实现散架".encode("utf-8")


def build_gbk(path):
    """名字按 GBK 编码且**不**置 UTF-8 标志位 —— 中文 Windows 压出来的包就是这么写的。

    zipfile 只会写 ASCII 或 UTF-8，所以这里临时改掉它的私有编码钩子，
    造出真实世界里存在、但它自己造不出来的那种字节。
    """
    original = zipfile.ZipInfo._encodeFilenameFlags

    def gbk_name(self):
        return self.filename.encode("gbk"), self.flag_bits

    zipfile.ZipInfo._encodeFilenameFlags = gbk_name
    try:
        with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as package:
            package.writestr("中文文件.txt", b"gbk named")
            package.writestr("ascii.txt", b"plain")
    finally:
        zipfile.ZipInfo._encodeFilenameFlags = original


def read_back(path):
    """参照值一律取"zipfile 读回来看到什么"，不是"我以为写进去什么"。"""
    with zipfile.ZipFile(path) as package:
        infos = package.infolist()
    return {
        "names": "|".join(info.filename for info in infos),
        "sizes": "|".join(str(info.file_size) for info in infos),
        "methods": "|".join(str(info.compress_type) for info in infos),
        "crcs": "|".join(format(info.CRC, "d") for info in infos),
        "bytes": str(os.path.getsize(path)),
    }


def truth_lines(values):
    return "\n".join("%s=%s" % item for item in values.items()) + "\n"


def build_written_expected(path):
    """按 Kotlin 那边同样的载荷与同样的"这类扩展名存原文"规则压一份参照包。"""
    with zipfile.ZipFile(path, "w") as package:
        for name, body in payloads():
            stored = name.rsplit(".", 1)[-1].lower() in NO_DEFLATE
            package.writestr(name, body, zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED)
    return read_back(path)


def write(name, payload):
    os.makedirs(OUT, exist_ok=True)
    data = payload.encode("utf-8") if isinstance(payload, str) else payload
    with open(os.path.join(OUT, name), "wb") as handle:
        handle.write(data)


def main():
    os.makedirs(OUT, exist_ok=True)
    builders = {"plain.zip": build_plain, "names.zip": build_names,
                "comment.zip": build_comment, "gbk.zip": build_gbk}
    for name, build in builders.items():
        path = os.path.join(OUT, name)
        build(path)
        print(name, os.path.getsize(path), "字节")
    write("plain.zip.truth", truth_lines(read_back(os.path.join(OUT, "plain.zip"))))
    # 写侧参照：名字与 CRC 是跨实现必须一致的两项
    values = build_written_expected(os.path.join(OUT, "written-expected.zip"))
    write("written.zip.truth", truth_lines(
        {"names": values["names"], "crcs": values["crcs"], "methods": values["methods"]}))
    # 载荷单独落文件，Kotlin 测试直接读这三个字节，避免两边各写一份定义然后对不上
    for name, (label, body) in zip(("item-note.bin", "item-blob.bin", "item-random.bin"), payloads()):
        write(name, body)
        print("  载荷", label, len(body), "字节")
    write("written-expected.zip.truth", truth_lines(values))
    print("写到", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
