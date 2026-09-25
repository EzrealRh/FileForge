#!/usr/bin/env python3
"""用 Pillow 复核元数据清理的产物。

为什么要有这一步：清理是"照着字节规则搬段"的活，我家解析器认自己的输出不算数 ——
真正的风险是别家解码器打不开、或者看着打开了其实像素被动过。Pillow 是另一套完整实现，
它点头才算安全。

先跑测试（ImageMetaTest 会把清理前后的四份文件写到 core/build/meta-clean/）：

    ./gradlew :core:test

再跑本脚本：

    python tools/verify_meta_clean.py

判据三条：
 1. Pillow 打得开清理后的文件，解出来的像素与原图**逐像素相同**，尺寸和模式也相同
 2. 清理后读不到 EXIF / 文本块，但 ICC 原样还在（丢了 ICC 照片会变色，那叫损坏不叫清理）
 3. 清理前确实带着 EXIF / 文本块，否则这次复核等于空转
"""
import os
import sys

from PIL import Image

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认按 GBK 输出，中文判据会糊成乱码；强制 UTF-8 才看得下去
    sys.stdout.reconfigure(encoding="utf-8")

DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "core", "build", "meta-clean"
)

failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def load(name):
    with Image.open(os.path.join(DIR, name)) as handle:
        handle.load()                       # 真的解一遍像素，只 open 不算打开过
        return handle, handle.tobytes()


def verify_jpeg():
    print("JPEG（photo.jpg → cleaned-photo.jpg）")
    before, before_px = load("photo.jpg")
    after, after_px = load("cleaned-photo.jpg")
    check("Pillow 能打开清理后的文件", True)
    check("像素逐点相同", before_px == after_px,
          "" if before_px == after_px else "清理动了画质")
    check("尺寸与模式相同", before.size == after.size and before.mode == after.mode,
          f"{before.size}/{before.mode} → {after.size}/{after.mode}")
    check("原图确实带 EXIF（否则这条是空转）", bool(dict(before.getexif())))
    check("清理后 EXIF 读不到了", not dict(after.getexif()), str(dict(after.getexif()))[:80])
    check("原图确实带 ICC", bool(before.info.get("icc_profile")))
    check(
        "ICC 一个字节都没改",
        before.info.get("icc_profile") == after.info.get("icc_profile"),
        "%s → %s 字节" % (
            len(before.info.get("icc_profile") or b""),
            len(after.info.get("icc_profile") or b""),
        ),
    )
    check(
        "注释段已清掉",
        before.info.get("comment") and not after.info.get("comment"),
        "原 %r / 清后 %r" % (before.info.get("comment"), after.info.get("comment")),
    )


def verify_png():
    print("PNG（note.png → cleaned-note.png）")
    before, before_px = load("note.png")
    after, after_px = load("cleaned-note.png")
    check("Pillow 能打开清理后的文件", True)
    check("像素逐点相同", before_px == after_px)
    check("尺寸与模式相同", before.size == after.size and before.mode == after.mode)
    check("原图确实带文本块（否则这条是空转）", bool(before.text), ",".join(sorted(before.text))[:60])
    check("清理后文本块读不到了", not after.text, str(dict(after.text))[:80])
    check("原图确实带 ICC", bool(before.info.get("icc_profile")))
    check("ICC 一个字节都没改",
          before.info.get("icc_profile") == after.info.get("icc_profile"))


def main():
    if not os.path.isdir(DIR):
        print("找不到 %s，先跑 ./gradlew :core:test" % os.path.normpath(DIR))
        return 3
    print("复核目录", os.path.normpath(DIR))
    verify_jpeg()
    verify_png()
    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nPillow 认可：清理后的文件打得开、像素没动、身份信息已清空。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
