#!/usr/bin/env python3
r"""
产「改图片方向与边界」的来料：四份小图，落在 `core/build/imgtransform/`。

刻意**不用我们自己的写侧**做夹具：这几份由 Pillow 直接画，裁判（也是 Pillow）与替身探针
（Java2D）之间没有共享的实现，"转 90° 后那块红应在右上"这种事实由图自己带着。

  跑法：python tools/make_image_fixtures.py
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "core", "build", "imgtransform")

RED = (220, 30, 30, 255)
GREEN = (30, 180, 60, 255)
BLUE = (40, 70, 210, 255)
YELLOW = (235, 210, 40, 255)
BAR = (12, 12, 12, 255)
WHITE = (250, 250, 250, 255)
SIZE = (60, 40)          # 非方、非对称：转错方向或把宽高弄反都会露出来


def marker(path, hole=False):
    image = Image.new("RGBA", SIZE, WHITE)
    image.paste(Image.new("RGBA", (10, 10), RED), (0, 0))
    image.paste(Image.new("RGBA", (10, 10), GREEN), (SIZE[0] - 10, 0))
    image.paste(Image.new("RGBA", (10, 10), BLUE), (0, SIZE[1] - 10))
    image.paste(Image.new("RGBA", (10, 10), YELLOW), (SIZE[0] - 10, SIZE[1] - 10))
    # 中间那一横条偏左偏上：只靠四角认不出"转了 180°"和"上下翻"的区别
    image.paste(Image.new("RGBA", (14, 4), BAR), (18, 12))
    if hole:
        # 故意偏着放：居中洞在左右镜像后落在同一处，那种夹具问不出"翻没翻"
        image.paste(Image.new("RGBA", (12, 12), (0, 0, 0, 0)), (4, 20))
    image.save(path)


def flat(path):
    """一张纯色方图：任意角旋转后要看的是"画布刚好装下、四边都碰到"，纯色最好数。"""
    Image.new("RGBA", (40, 40), (127, 63, 191, 255)).save(path)


def gradient(path):
    image = Image.new("RGB", (80, 60))
    for y in range(60):
        for x in range(80):
            image.putpixel((x, y), (x * 3 % 256, y * 4 % 256, (x + y) * 2 % 256))
    image.save(path, quality=95)


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    marker(os.path.join(OUT, "marker.png"))
    marker(os.path.join(OUT, "alpha.png"), hole=True)
    flat(os.path.join(OUT, "flat.png"))
    gradient(os.path.join(OUT, "photo.jpg"))
    for name in ("marker.png", "alpha.png", "flat.png", "photo.jpg"):
        path = os.path.join(OUT, name)
        with Image.open(path) as made:
            print("OK   %s %s %s %d 字节" % (name, made.size, made.mode, os.path.getsize(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
