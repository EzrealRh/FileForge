"""用 Pillow 造参考 GIF，作为 Kotlin GifDecoder 的独立夹具。

每份夹具同时输出 .gif（被测文件）和 .gifx（PIL 自己解出来的像素真值），
这样 Kotlin 测试比对的是另一个实现的结果，而不是我照着自家解码器反推的期望值。

重新生成：python tools/make_gif_fixtures.py
"""
from PIL import Image, ImageSequence
import os
import struct

OUT = os.path.join("core", "src", "test", "resources", "gif")
os.makedirs(OUT, exist_ok=True)

WHITE, RED, GREEN, BLUE, BLACK = (255, 255, 255), (255, 0, 0), (0, 255, 0), (0, 0, 255), (0, 0, 0)


def with_palette(rgb, palette):
    flat = [c for p in palette for c in p]
    pal = Image.new("P", (1, 1))
    pal.putpalette(flat + [0] * (768 - len(flat)))
    return rgb.quantize(palette=pal, dither=Image.Dither.NONE)


def checker(w, h, a, b):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = a if (x + y) % 2 == 0 else b
    return img


def stripes(w, h, a, b):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = a if y % 2 == 0 else b
    return img


def truth_file(path, delays):
    """把 PIL 逐帧解出来的画布写成 .gifx，供 Kotlin 侧比对。"""
    im = Image.open(path)
    im.seek(0)
    body = bytearray()
    frames = 0
    for frame in ImageSequence.Iterator(im):
        rgba = frame.convert("RGBA")
        if rgba.size != im.size:
            raise SystemExit("%s 帧尺寸不一致" % path)
        body += rgba.tobytes()
        frames += 1
    cs = [int(round(d / 10.0)) for d in delays]
    header = struct.pack("<4sHHHH", b"GFX1", im.size[0], im.size[1], frames, len(cs))
    for value in cs:
        header += struct.pack("<H", value)
    with open(os.path.splitext(path)[0] + ".gifx", "wb") as handle:
        handle.write(header + bytes(body))
    return im.size, frames


def emit(name, frames, **kwargs):
    path = os.path.join(OUT, name)
    delays = [int(round(d)) for d in (kwargs.pop("duration") if isinstance(kwargs.get("duration"), list)
                                      else [kwargs.pop("duration")] * len(frames))]
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=delays, **kwargs)
    size, count = truth_file(path, delays)
    print("%-18s %dx%d frames=%d truth=%d %d bytes" %
          (name, size[0], size[1], count, count, os.path.getsize(path)))


two = [with_palette(checker(8, 6, WHITE, RED), [WHITE, RED]),
       with_palette(stripes(8, 6, GREEN, BLUE), [GREEN, BLUE])]
emit("two_frames.gif", two, duration=[200, 300], loop=0)

rows = Image.new("RGB", (6, 32))
px = rows.load()
for y in range(32):
    for x in range(6):
        px[x, y] = (y * 8 % 256, y * 4 % 256, (255 - y * 8) % 256)
emit("interlaced.gif", [with_palette(rows, [(y * 8 % 256, y * 4 % 256, (255 - y * 8) % 256) for y in range(32)])],
     duration=120, loop=1, interlace=True)

single = Image.new("RGB", (16, 16))
spx = single.load()
for y in range(16):
    for x in range(16):
        spx[x, y] = (x * 16, y * 16, 128)
emit("non_square_single.gif", [with_palette(single, [(x * 16, y * 16, 128) for y in range(16) for x in range(16)])],
     duration=200, loop=0)

gradi = []
for f in range(4):
    img = Image.new("RGB", (40, 40))
    ipx = img.load()
    for y in range(40):
        for x in range(40):
            ipx[x, y] = ((x * 6 + f * 13) % 256, y * 6 % 256, (x * y + f) % 256)
    gradi.append(img)
emit("rich.gif", [g.quantize(colors=200, method=Image.Quantize.MEDIANCUT) for g in gradi],
     duration=80, loop=0)
