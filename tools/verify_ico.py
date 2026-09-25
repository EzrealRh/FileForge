#!/usr/bin/env python3
"""用 Pillow 复核 Kotlin 写的 ICO。

判据：别人（Pillow）打不打得开、里面有哪几个尺寸、每个尺寸的像素跟打包前**逐格相同**。
自家 reader 读自家 writer 读开不算证据 —— 图标是要发给别人的系统用的。

先跑测试（IcoTest 会把产物写到 core/build/ico/）：

    ./gradlew :core:test

再跑本脚本：

    python tools/verify_ico.py
"""
import json
import os
import sys

from PIL import Image

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")        # Windows 控制台默认 GBK，中文提示会糊

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
BUILD = os.path.join(ROOT, "core", "build", "ico")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "ico")

failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def argb_table(side):
    out = []
    for y in range(side):
        for x in range(side):
            a = 255 if (x + y) % 3 == 0 else (128 if (x + y) % 3 == 1 else 0)
            out.append((a, (x * 7) & 0xFF, (y * 9) & 0xFF, (x * 5 + y * 3) & 0xFF))   # a, r, g, b
    return out


def compare(pil_image, side, label):
    """比像素。alpha=0 的格子不比颜色：Pillow 按预乘会把颜色洗成黑，那是它的合法行为。"""
    want = argb_table(side)
    data = list(pil_image.convert("RGBA").getdata())
    if len(data) != len(want):
        check(label, False, "格子数 %d vs %d" % (len(data), len(want)))
        return
    bad = None
    for index, ((a, r, g, b), got) in enumerate(zip(want, data)):
        if got[3] != a:
            bad = (index, "alpha", a, got[3]); break
        if a and (got[0], got[1], got[2]) != (r, g, b):
            bad = (index, "rgb", (r, g, b), got[:3]); break
    check(label, bad is None, "" if bad is None else str(bad))


def main():
    if not os.path.isdir(BUILD):
        print("找不到 %s，先跑 ./gradlew :core:test" % os.path.normpath(BUILD))
        return 3
    print("Kotlin 写的图标", os.path.normpath(BUILD))

    path = os.path.join(BUILD, "written.ico")
    try:
        opened = Image.open(path)
    except Exception as bad:                        # noqa: BLE001 —— 判据就是"能不能打开"
        print("  失败  Pillow 打不开 written.ico：%s" % bad)
        return 1
    check("Pillow 打得开 written.ico", True)
    sizes = sorted(opened.info.get("sizes") or [])
    check("目录里两个尺寸都在", sizes == [(16, 16), (32, 32)], str(sizes))

    # Pillow 的 ICO 插件不把多尺寸当多帧（seek 会抛 EOFError），所以逐尺寸比对靠
    # "每个尺寸单独写一份"，而不是假装能翻帧。它自己挑的是最大的那张。
    check("双尺寸文件里 Pillow 选中最大的一张", opened.size == (32, 32), str(opened.size))
    opened.load()
    compare(opened, 32, "双尺寸文件里 32×32 的像素与打包前逐格相同")
    opened.close()

    for side in (32, 16):
        single = Image.open(os.path.join(BUILD, "only%d.ico" % side))
        check("only%d.ico 打得开且尺寸正确" % side, single.size == (side, side), str(single.size))
        single.load()
        compare(single, side, "only%d.ico 的像素与打包前逐格相同" % side)
        single.close()

    print("再复核一遍读→写往返")
    resaved = Image.open(os.path.join(BUILD, "resaved.ico"))
    check("重新保存的那份 Pillow 也打得开", resaved.format == "ICO", str(resaved.format))
    check("尺寸没在往返里丢掉", sorted(resaved.info["sizes"]) == [(32, 32)], str(resaved.info["sizes"]))
    resaved.load()
    compare(resaved, 32, "往返之后像素仍然相同")

    with open(os.path.join(FIXTURES, "sample.json"), encoding="utf-8") as handle:
        sample = json.loads(handle.read())
    check("夹具与产物是同一批像素", len(sample["argb32"]) == 32 * 32 and len(sample["argb16"]) == 16 * 16)

    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nPillow 认可：Kotlin 写的 .ico 别人打得开，尺寸齐、像素逐格相同。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
