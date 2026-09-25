#!/usr/bin/env python3
"""造 ICO 测试夹具：一份我们自己按规范拼的 DIB 图标、一份 Pillow 写的 PNG 内嵌图标。

为什么两份都要：现实里的 .ico 就分这两类。Pillow 6+ 只会写 PNG-in-ICO，所以
"读 DIB" 这条路径没法拿 Pillow 的文件当输入 —— 那份由本脚本按微软那套目录格式
手工拼出来（BITMAPINFOHEADER + 自下而上的 BGRA + 全 0 的 AND 掩码），
拼完再让 Pillow 打开确认拼对了，两边都点头才算夹具成立。

输出到 core/src/test/resources/ico/：
  sample.json     同一批像素（ARGB 整数列表）与尺寸，读侧的期望值
  dib32.ico       DIB 载荷的 32×32 与 16×16 两帧
  pngico.ico      Pillow 写的 PNG 内嵌图标，用来验目录解析与"PNG 那条只交字节"
"""
import io
import json
import os
import struct

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "ico")
SIZES = [32, 16]


def pixels(side):
    """一块确定的渐变图：通道各不相同，透明度有三种值，掩码与 alpha 才有机会被分开验。"""
    out = []
    for y in range(side):
        for x in range(side):
            a = 255 if (x + y) % 3 == 0 else (128 if (x + y) % 3 == 1 else 0)
            # 每个通道都得先夹到 8 位：y*9 在 32 格上会到 261，不夹就会溢到红通道里去，
            # 于是"期望值"本身就比真实像素多 1 —— 这个坑由 tools/verify_ico.py 独立算表时对出来的
            out.append((a << 24) | (((x * 7) & 0xFF) << 16) | (((y * 9) & 0xFF) << 8) | ((x * 5 + y * 3) & 0xFF))
    return out


def dib_body(argb, side):
    """BITMAPINFOHEADER + 自下而上 BGRA + 全 0 AND 掩码。"""
    head = struct.pack("<IiiHHIIiiII", 40, side, side * 2, 1, 32, 0, side * side * 4, 0, 0, 0, 0)
    rows = []
    for y in range(side - 1, -1, -1):
        row = bytearray()
        for x in range(side):
            color = argb[y * side + x]
            row += bytes([color & 0xFF, (color >> 8) & 0xFF, (color >> 16) & 0xFF, (color >> 24) & 0xFF])
        rows.append(bytes(row))
    mask_row = (side + 31) // 32 * 4
    return head + b"".join(rows) + b"\x00" * (mask_row * side)


def build_dib_ico():
    bodies = [(side, dib_body(pixels(side), side)) for side in SIZES]
    out = io.BytesIO()
    out.write(struct.pack("<HHH", 0, 1, len(bodies)))
    offset = 6 + 16 * len(bodies)
    for side, body in bodies:
        out.write(struct.pack("<BBBBHHII", side, side, 0, 0, 1, 32, len(body), offset))
        offset += len(body)
    for _, body in bodies:
        out.write(body)
    return out.getvalue()


def build_png_ico():
    image = Image.new("RGBA", (max(SIZES), max(SIZES)))
    table = pixels(max(SIZES))
    image.putdata([(c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF, (c >> 24) & 0xFF) for c in table])
    buffer = io.BytesIO()
    image.save(buffer, format="ICO", sizes=[(s, s) for s in SIZES])
    return buffer.getvalue()


def main():
    os.makedirs(OUT, exist_ok=True)

    dib = build_dib_ico()
    write("dib32.ico", dib)
    probe = Image.open(io.BytesIO(dib))
    assert probe.format == "ICO", probe.format
    assert sorted(probe.info["sizes"]) == [(16, 16), (32, 32)], probe.info["sizes"]
    probe.load()
    big = list(probe.convert("RGBA").getdata())      # Pillow 给的是 (r, g, b, a)
    expected = pixels(32)
    assert len(big) == len(expected)
    # alpha=0 的像素 Pillow 会按预乘把颜色洗掉，所以那些只比 alpha；其余三个通道都比
    for index, (packed, got) in enumerate(zip(expected, big)):
        red, green, blue, alpha = got
        assert (packed >> 24) & 0xFF == alpha, (index, hex(packed), got)
        if alpha:
            assert (packed >> 16) & 0xFF == red, (index, hex(packed), got)
            assert (packed >> 8) & 0xFF == green, (index, hex(packed), got)
            assert packed & 0xFF == blue, (index, hex(packed), got)

    png = build_png_ico()
    write("pngico.ico", png)
    probe2 = Image.open(io.BytesIO(png))
    assert sorted(probe2.info["sizes"]) == [(16, 16), (32, 32)]

    write("sample.json", json.dumps({
        "sizes": SIZES,
        "argb32": [f"0x{c:08x}" for c in pixels(32)],
        "argb16": [f"0x{c:08x}" for c in pixels(16)],
        "dibBytes": len(dib),
        "pngBytes": len(png),
    }, ensure_ascii=False) + "\n")

    print("dib32.ico", len(dib), "字节 · pngico.ico", len(png), "字节 · 写到", os.path.normpath(OUT))


def write(name, payload):
    path = os.path.join(OUT, name)
    mode = "wb" if isinstance(payload, bytes) else "w"
    kwargs = {} if isinstance(payload, bytes) else {"encoding": "utf-8", "newline": ""}
    with open(path, mode, **kwargs) as handle:
        handle.write(payload)


if __name__ == "__main__":
    main()
