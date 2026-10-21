#!/usr/bin/env python3
r"""
判 BMP / TIFF 这一层：编码产物交给 **Pillow** 逐像素核；解码器吃 Pillow 自己造的四种 TIFF
（raw RGBA / Deflate RGB / PackBits RGB / 灰度），LZW 必须被明确拒绝而不是给一张错色图。

    python tools/verify_raster.py        （先 ./gradlew :core:jar）
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, HERE)
from verify_odt_read import check, results  # noqa: E402

from PIL import Image  # noqa: E402

RUN = os.path.join(ROOT, "tools", "imgprobe", "run.sh")
FIX = os.path.join(ROOT, "core", "build", "raster")
OUT = os.path.join(FIX, "out")
ENV = dict(os.environ,
           IMGPROBE_OUT=os.environ.get("IMGPROBE_OUT", "D:/fflogs/imgprobe"),
           GRADLE_USER_HOME=os.environ.get("GRADLE_USER_HOME", "D:/gradle-home"))


def probe(*args, must_fail=False):
    run = subprocess.run(["bash", RUN, *args], cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace", env=ENV)
    if must_fail:
        return run.returncode, (run.stdout or "") + (run.stderr or "")
    if run.returncode != 0:
        raise RuntimeError("探针跑不动 %s：%s" % (args, ((run.stderr or "") + (run.stdout or ""))[-320:]))
    return (run.stdout or "").strip()


def path(name):
    return os.path.join(FIX, name)


def out(name):
    return os.path.join(OUT, name)


def same(got, want, tol=0):
    if got.size != want.size:
        return False, "尺寸 %s 对 %s" % (got.size, want.size)
    if got.mode != want.mode:
        got = got.convert(want.mode)
    for y in range(want.height):
        for x in range(want.width):
            a, b = got.getpixel((x, y)), want.getpixel((x, y))
            if isinstance(a, int):
                a, b = (a,), (b,)
            if any(abs(va - vb) > tol for va, vb in zip(a, b)):
                return False, "(%d,%d) 我们 %s，该是 %s" % (x, y, a, b)
    return True, ""


def fixtures() -> None:
    os.makedirs(OUT, exist_ok=True)
    for stale in os.listdir(OUT):
        os.remove(os.path.join(OUT, stale))
    gradient = Image.new("RGB", (7, 5))
    for y in range(5):
        for x in range(7):
            gradient.putpixel((x, y), (x * 31 % 256, y * 47 % 256, (x + y) * 23 % 256))
    gradient.save(path("photo.png"))

    alpha = Image.new("RGBA", (6, 4), (250, 250, 250, 255))
    alpha.putpixel((1, 1), (220, 30, 30, 255))
    alpha.putpixel((4, 2), (30, 180, 60, 128))
    alpha.putpixel((0, 3), (0, 0, 0, 0))
    alpha.save(path("alpha.png"))

    alpha.save(path("raw_rgba.tiff"), compression="raw")
    gradient.save(path("deflate_rgb.tiff"), compression="tiff_deflate")
    gradient.save(path("packbits_rgb.tiff"), compression="packbits")
    gradient.convert("L").save(path("gray.tiff"), compression="raw")
    gradient.save(path("lzw.tiff"), compression="tiff_lzw")


def main() -> int:
    fixtures()
    photo = Image.open(path("photo.png")).convert("RGB")
    alpha = Image.open(path("alpha.png")).convert("RGBA")
    white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
    over_white = Image.alpha_composite(white, alpha).convert("RGB")

    probe("raster", path("photo.png"), out("photo.bmp"))
    ok, why = same(Image.open(out("photo.bmp")).convert("RGB"), photo)
    check(1, "BMP 不透明图：Pillow 打开后逐像素与源一致", ok, why)

    probe("raster", path("alpha.png"), out("alpha.bmp"))
    ok, why = same(Image.open(out("alpha.bmp")).convert("RGB"), over_white, tol=2)
    check(2, "BMP 塞不下透明：透明处铺白（与源按白底合成逐像素对，容差 2）", ok, why)

    probe("raster", path("photo.png"), out("photo.tiff"))
    ok, why = same(Image.open(out("photo.tiff")).convert("RGB"), photo)
    check(3, "TIFF 不透明图：Pillow 读回来逐像素一致", ok, why)

    probe("raster", path("alpha.png"), out("alpha.tiff"))
    ok, why = same(Image.open(out("alpha.tiff")).convert("RGBA"), alpha)
    check(4, "TIFF 保住透明通道（RGBA 逐像素对）", ok, why)

    for number, name, want in (
        (5, "raw RGBA", "raw_rgba.tiff"),
        (6, "Deflate RGB", "deflate_rgb.tiff"),
        (7, "PackBits RGB", "packbits_rgb.tiff"),
        (8, "灰度", "gray.tiff"),
    ):
        probe("tiffread", path(want), out("decoded-%d.png" % number))
        got = Image.open(out("decoded-%d.png" % number))
        expected = Image.open(path(want))
        ok, why = same(got, expected)
        check(number, "解外部 TIFF（%s）：Pillow 造、我们解，逐像素一致" % name, ok, why)

    code, message = probe("tiffread", path("lzw.tiff"), out("lzw.png"), must_fail=True)
    check(9, "LZW 压缩的 TIFF 明确拒绝并点名，不产出错色图", code != 0 and "LZW" in message,
          message[-180:])

    red = [item for item in results if not item[2]]
    print("共 %d 条，%d 条红" % (len(results), len(red)))
    for number, name, ok in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
