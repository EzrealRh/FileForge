#!/usr/bin/env python3
r"""
判「改图片方向与边界」这一族：旋转 / 翻转 / 裁边在纸上到底变成了什么。

四角对证的另外两角在这里是：
  * **算** —— 由 `tools/imgprobe` 调 `:core` 那份 `ImageGeometry`（应用真用的同一份，不是这里重写一套）；
  * **裁判** —— **Pillow** 自己转一遍、自己裁一遍，逐像素对。Pillow 的 `rotate()` 是**逆时针**为正，
    而我们的口径是顺时针，所以每一步都显式取反（`ROTATE_270` 就是我们说的"往右 90°"）。

夹具由 `tools/make_image_fixtures.py` 用 Pillow 画（非方的 60×40 + 四角不同色 + 一条偏心横杠 + 一个偏置透明洞）：
只靠四角分不出"转 180°"与"上下翻"，那条横杠与那个洞就是为分它们放的。

    跑法：python tools/verify_image_transform.py            （要先 ./gradlew :core:jar）
    反例：python tools/reverse_image_transform.py

百分比那两条刻意取**不靠近半数的整数**（10% 与 80% 配 60×40 全是整数），这样判据核的是规则而不是
两边各自取整凑出来的巧合；`12.5 → 13` 那一种由 :core 的 ImageGeometryTest 钉着。
"""
from __future__ import annotations

import math
import os
import re
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
FIXTURES = os.path.join(ROOT, "core", "build", "imgtransform")
OUT = os.path.join(FIXTURES, "out")
ENV = dict(os.environ,
           IMGPROBE_OUT=os.environ.get("IMGPROBE_OUT", "D:/fflogs/imgprobe"),
           GRADLE_USER_HOME=os.environ.get("GRADLE_USER_HOME", "D:/gradle-home"))

# 翻法的编号与探针那一份对得上：0 左右、1 上下、2 两者、3 不翻
NONE, HORIZONTAL, VERTICAL, BOTH = 3, 0, 1, 2


def probe(source, target, degrees=0, flip=NONE, crop="-", aspect="-"):
    """跑替身探针：算的那一步在 :core，画的那一步在 Java2D。"""
    if os.path.exists(target):
        os.remove(target)        # 旧产物会让"没跑成"说成"跑对了"
    run = subprocess.run(["bash", RUN, "transform", source, target, str(degrees), str(flip), crop, aspect],
                         cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", env=ENV)
    if run.returncode != 0 or not os.path.exists(target):
        raise RuntimeError("探针跑不动 %s：%s" % (os.path.basename(source), (run.stderr or run.stdout)[-320:]))
    return run.stdout


def path(name):
    return os.path.join(FIXTURES, name)


def out(name):
    return os.path.join(OUT, name)


def same(left, right):
    """两张图逐像素对不对得上；对不上时给出第一处差异，方便判"差在哪"而不是只说"差了多少"。"""
    if left.size != right.size:
        return False, "尺寸 %s 对 %s" % (left.size, right.size)
    a, b = left.convert("RGBA"), right.convert("RGBA")
    for y in range(a.height):
        for x in range(a.width):
            if a.getpixel((x, y)) != b.getpixel((x, y)):
                return False, "(%d,%d) 我们 %s，该是 %s" % (x, y, a.getpixel((x, y)), b.getpixel((x, y)))
    return True, ""


def note_of(stdout, keyword):
    for line in stdout.splitlines():
        if line.startswith("说明 ") and keyword in line:
            return line[len("说明 "):]
    return ""


def claimed_size(stdout):
    numbers = re.search(r"出来是 (\d+)×(\d+)", stdout)
    return (int(numbers.group(1)), int(numbers.group(2))) if numbers else (0, 0)


def aspect_expect(ratio_w, ratio_h, width, height):
    scale = min(width / ratio_w, height / ratio_h)
    crop_w, crop_h = int(ratio_w * scale), int(ratio_h * scale)
    left, top = (width - crop_w) // 2, (height - crop_h) // 2
    return left, top, left + crop_w, top + crop_h


def main() -> int:
    if not os.path.isdir(FIXTURES):
        subprocess.run(["python", os.path.join(ROOT, "tools", "make_image_fixtures.py")], cwd=ROOT, check=True)
    os.makedirs(OUT, exist_ok=True)
    for stale in os.listdir(OUT):
        os.remove(os.path.join(OUT, stale))
    marker, alpha_png, flat, photo = path("marker.png"), path("alpha.png"), path("flat.png"), path("photo.jpg")
    base = Image.open(marker).convert("RGBA")

    # ---- 1~5 旋转 ----
    for number, degrees, expect, word in (
        (1, 90, base.transpose(Image.ROTATE_270), "顺时针转 90°"),
        (3, 180, base.transpose(Image.ROTATE_180), "转 180°"),
        (4, 270, base.transpose(Image.ROTATE_90), "逆时针转 90°"),
    ):
        said = probe(marker, out("r%d.png" % degrees), degrees=degrees)
        got = Image.open(out("r%d.png" % degrees)).convert("RGBA")
        ok, why = same(got, expect)
        check(number, "转 %d° 后每个像素都落在该落的地方（与 Pillow 逐像素对）" % degrees, ok,
              "%s；说明=%r" % (why, note_of(said, "转")))
        if degrees == 90:
            check(2, "转 90° 的产物尺寸与说明里那句「出来是」一致，且宽高真的换了边",
                  got.size == claimed_size(said) == (base.height, base.width),
                  "纸上 %s，说明 %r" % (got.size, note_of(said, "出来是")))

    said = probe(marker, out("rn90.png"), degrees=-90)
    ok, why = same(Image.open(out("rn90.png")).convert("RGBA"), base.transpose(Image.ROTATE_90))
    check(5, "负角与补成正角是同一格（-90 就是往左 90°）", ok, why + "；说明=%r" % note_of(said, "转"))

    # ---- 6~8 翻转 ----
    for number, mode, expect, word in (
        (6, HORIZONTAL, base.transpose(Image.FLIP_LEFT_RIGHT), "左右镜像"),
        (7, VERTICAL, base.transpose(Image.FLIP_TOP_BOTTOM), "上下镜像"),
        (8, BOTH, base.transpose(Image.FLIP_LEFT_RIGHT).transpose(Image.FLIP_TOP_BOTTOM), "左右再上下镜像"),
    ):
        probe(marker, out("f%d.png" % mode), flip=mode)
        got = Image.open(out("f%d.png" % mode)).convert("RGBA")
        ok, why = same(got, expect)
        check(number, "%s 逐像素对得上（镜像不挪位置也不改尺寸）" % word, ok,
              "%s；尺寸 %s" % (why, got.size))

    # ---- 9~10 裁边 ----
    box9 = (10, 5, 40, 25)
    said = probe(marker, out("cpx.png"), crop="10,5,30,20")
    got = Image.open(out("cpx.png")).convert("RGBA")
    ok, why = same(got, base.crop(box9))
    check(9, "按像素裁「10,5,30,20」得到的就是那块（后两段是宽高，不是右下脚）", ok,
          "%s；说明=%r" % (why, note_of(said, "起裁")))

    box10 = (round(0.10 * 60), round(0.10 * 40), round(0.90 * 60), round(0.90 * 40))
    said = probe(marker, out("cpct.png"), crop="10%,10%,80%,80%")
    got = Image.open(out("cpct.png")).convert("RGBA")
    ok, why = same(got, base.crop(box10))
    check(10, "四段都带 %% 时按各自那条边折算（裁出 %s）" % (box10,), ok, "%s；尺寸 %s" % (why, got.size))

    # ---- 11~13 宽高比 ----
    box11 = aspect_expect(1, 1, 60, 40)
    said = probe(marker, out("a11.png"), aspect="1:1")
    ok, why = same(Image.open(out("a11.png")).convert("RGBA"), base.crop(box11))
    check(11, "按 1:1 居中裁：取放得下的最大那块，左右留得一样", ok, "该是 %s，%s" % (box11, why))

    box12 = aspect_expect(16, 9, 60, 40)
    probe(marker, out("a169.png"), aspect="16:9")
    ok, why = same(Image.open(out("a169.png")).convert("RGBA"), base.crop(box12))
    check(12, "按 16:9 居中裁（60×40 得到 %s，向下取整不顶出图外）" % (box12,), ok, why)

    said = probe(marker, out("both.png"), crop="0,0,20,20", aspect="1:1")
    ok, why = same(Image.open(out("both.png")).convert("RGBA"), base.crop((0, 0, 20, 20)))
    check(13, "裁边与宽高比同时给了：按显式那块，并说出来", ok and "宽高比没用上" in said,
          "%s；说明=%r" % (why, note_of(said, "宽高比")))

    # ---- 14~17 任意角 ----
    # 画布尺寸这里按三角公式**独立**推一遍：Pillow 的 expand 是 int(extent) + 1，比"刚好包住"多留一列，
    # 拿它的尺寸当最小值会判错方向 —— 我们只要求等于独立算出的最小格，且不超过 Pillow 那一张。
    exact = 40 * abs(math.cos(math.radians(30))) + 40 * abs(math.sin(math.radians(30)))
    smallest = math.ceil(exact - 1e-9)
    reference = Image.open(flat).convert("RGBA").rotate(-30, expand=True, resample=Image.NEAREST)
    said = probe(flat, out("r30.png"), degrees=30)
    got = Image.open(out("r30.png")).convert("RGBA")
    check(14, "转 30° 的画布是刚好包住的最小整数格（独立按三角推 %.3f → %d）" % (exact, smallest),
          got.size == (smallest, smallest) and got.width <= reference.width,
          "我们 %s，独立算 %d，Pillow expand %s（它多留一列）" % (got.size, smallest, reference.size))

    alpha = got.split()[3].getdata()
    opaque = [[1 if alpha[y * got.width + x] > 128 else 0 for x in range(got.width)] for y in range(got.height)]
    touches = [any(row) for row in (opaque[0], opaque[-1])] + \
              [any(opaque[y][0] for y in range(got.height)), any(opaque[y][-1] for y in range(got.height))]
    count = sum(sum(row) for row in opaque)
    check(15, "转 30° 后四条边都碰得到内容（画布没切掉一角）", all(touches), "各边=%s" % (touches,))
    check(16, "转 30° 的内容面积与源一致（旋转不增不减；容差按周长 %d 算）" % (4 * 40),
          abs(count - 40 * 40) <= 4 * 40, "数到 %d 个不透明像素" % count)
    check(17, "任意角那条说明要讲清重采样与扩画布", "重采样" in said and "出来是" in said,
          said.replace("\n", " · ")[:180])

    # ---- 18~19 输出容器 ----
    said = probe(photo, out("r30.jpg"), degrees=30)
    jpg = Image.open(out("r30.jpg")).convert("RGB")
    corner = [jpg.getpixel(xy) for xy in ((1, 1), (jpg.width - 2, 1), (1, jpg.height - 2))]
    check(18, "JPG 转 30°：空出来的角填白，而不是安卓默认的涂黑", all(min(rgb) >= 235 for rgb in corner),
          "角上取到 %s" % (corner,))
    check(19, "那句话由判层出：JPG 填白这件事写在说明里（应用侧读的是同一串）",
          "填白" in said, said.replace("\n", " · ")[:180])
    # 说明不能只会喊：整倍数那一格画布被内容铺满，没透明可填，这时候提"填白"就是编
    said = probe(photo, out("r90.jpg"), degrees=90)
    check(23, "JPG 转 90°（画布被内容铺满）时不许提填白", "填白" not in said,
          said.replace("\n", " · ")[:180])

    # ---- 20~21 透明通道 ----
    probe(alpha_png, out("flipa.png"), flip=HORIZONTAL)
    flipped = Image.open(out("flipa.png")).convert("RGBA")
    hole = all(flipped.getpixel((x, y))[3] == 0 for x in range(44, 56) for y in range(20, 32))
    filled = all(flipped.getpixel((x, y))[3] == 255 for x in range(4, 16) for y in range(20, 32))
    check(20, "PNG 翻转后那个透明洞跟着搬到对面，原来那处变实", hole and filled,
          "洞在对面=%s，原处补实=%s" % (hole, filled))
    probe(alpha_png, out("r30a.png"), degrees=30)
    rotated = Image.open(out("r30a.png")).convert("RGBA")
    check(21, "PNG 转 30° 之后仍然有透明（没被偷偷铺成实底）",
          any(value[3] < 255 for value in rotated.getdata()), "整张都不透明了")

    # ---- 22 报错也要指名 ----
    bad = subprocess.run(["bash", RUN, "transform", marker, out("nope.png"), "0", "3", "10,5,30", "-"],
                         cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", env=ENV)
    check(22, "裁边写成三段时报错说的就是这件事（不硬裁也不静默改）",
          bad.returncode != 0 and "四段" in (bad.stderr + bad.stdout), (bad.stderr or bad.stdout)[-160:])

    total = len(results)
    red = [item for item in results if not item[2]]
    print("共 %d 条，%d 条红" % (total, len(red)))
    for number, name, ok in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
