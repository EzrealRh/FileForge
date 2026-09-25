#!/usr/bin/env python3
"""生成图片元数据测试的夹具，并记下 Pillow 自己看到的东西当参照。

为什么要这个脚本而不是手造字节：元数据清理的判据本质是"另一个实现怎么看这份文件"——
PIL 认不认得 EXIF、清完之后像素变不变，都不是我自己说了算。

输出到 core/src/test/resources/meta/：
  photo.jpg        带 EXIF（制造商/机型/方向/时间 + GPS 子 IFD + Exif 子 IFD）、ICC、COM 注释
  photo.jpg.truth  PIL 看到的 APPn 标记、字段值、以及扫描数据（像素）的 sha256
  note.png         带 tEXt 文本块与 ICC
  note.png.truth   块清单与 IDAT 的 sha256

用法：python tools/make_meta_fixtures.py
"""
import hashlib
import io
import os
import struct
import sys

from PIL import Image
from PIL import PngImagePlugin
from PIL.TiffImagePlugin import IFDRational

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认按 GBK 输出，中文提示会糊成乱码
    sys.stdout.reconfigure(encoding="utf-8")

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "meta"
)


def fake_icc() -> bytes:
    """内容随意：判据是 APP2 的载荷以 'ICC_PROFILE' + NUL 开头，只要前缀对就行。"""
    return b"ICC_PROFILE\x00" + bytes(range(40))


def jpeg_scan_data(data: bytes) -> bytes:
    """SOS 头之后、下一个真标记之前的熵数据。清理时必须一字节都不动。

    段 = FF DA + 2 字节长度 + (length-2) 字节载荷，所以熵数据起点是 i + 2 + length。
    """
    i = data.index(b"\xff\xda")
    length = struct.unpack(">H", data[i + 2:i + 4])[0]
    start = i + 2 + length
    j = start
    while j + 1 < len(data):
        nxt = data[j + 1]
        if data[j] == 0xFF and nxt not in (0x00, 0xFF) and not (0xD0 <= nxt <= 0xD7):
            break
        j += 1
    return data[start:j]


def png_chunks(data: bytes):
    i, out = 8, []
    while i + 8 <= len(data):
        length = struct.unpack(">I", data[i:i + 4])[0]
        kind = data[i + 4:i + 8].decode("latin-1")
        out.append((kind, data[i + 8:i + 8 + length]))
        i += 12 + length
        if kind == "IEND":
            break
    return out


def make_jpeg() -> None:
    image = Image.new("RGB", (24, 12))
    for x in range(24):
        for y in range(12):
            image.putpixel((x, y), ((x * 9) % 256, (y * 21) % 256, (x + y) % 256))

    exif = Image.Exif()
    exif[0x010F] = "MakeTest"                         # 制造商
    exif[0x0110] = "ModelTest-1"                      # 机型
    exif[0x0112] = 6                                  # 方向：右下角朝上，清掉 EXIF 画面就会躺下
    exif[0x0132] = "2026:01:02 03:04:05"              # 修改时间
    exif[0x8825] = {                                  # GPS 子 IFD：位置是最要紧的隐私
        1: "N",
        2: [IFDRational(30, 1), IFDRational(15, 1), IFDRational(30, 1)],
        3: "E",
        4: [IFDRational(120, 1), IFDRational(6, 1), IFDRational(0, 1)],
    }
    exif[0x8769] = {0x9003: "2026:01:02 03:04:05", 0x8827: 200}   # Exif 子 IFD

    buffer = io.BytesIO()
    image.save(
        buffer,
        format="JPEG",
        exif=exif.tobytes(),
        icc_profile=fake_icc(),
        comment="作者注释：清理要去掉它",
        quality=95,
    )
    data = buffer.getvalue()
    reopened = Image.open(io.BytesIO(data))
    markers = ",".join(sorted(reopened.app))         # PIL 给的是 'APP0' / 'APP1' 这样的键名
    orientation = reopened.getexif().get(274)        # 274 == 0x0112，读回来而不是写进去
    lines = [
        "make=MakeTest",
        "model=ModelTest-1",
        "date_time=2026:01:02 03:04:05",
        "orientation=%s" % orientation,
        "gps_lat=30.0,15.0,30.0",
        "gps_lon=120.0,6.0,0.0",
        "gps_refs=N,E",
        "markers=" + markers,
        "bytes=%d" % len(data),
        "scan_sha=" + hashlib.sha256(jpeg_scan_data(data)).hexdigest(),
    ]
    write("photo.jpg", data)
    write("photo.jpg.truth", "\n".join(lines) + "\n")
    print("photo.jpg", len(data), "字节，PIL 看到的 APPn:", markers)


def make_png() -> None:
    image = Image.new("RGBA", (16, 10), (10, 200, 30, 255))
    text = PngImagePlugin.PngInfo()
    text.add_text("Author", "Zhang San")
    text.add_text("Software", "MetaFixture")
    text.add_itxt("Description", "iTXt 的说明文字", lang="en")
    exif = Image.Exif()
    exif[0x0110] = "PngModelTest"
    buffer = io.BytesIO()
    # PNG 的参数名和 JPEG 不一样：文本走 pnginfo，ICC 走 icc_profile，EXIF 走 exif
    image.save(
        buffer,
        format="PNG",
        pnginfo=text,
        icc_profile=fake_icc(),
        exif=exif.tobytes(),
    )
    data = buffer.getvalue()
    kinds = [kind for kind, _ in png_chunks(data)]
    idat = b"".join(payload for kind, payload in png_chunks(data) if kind == "IDAT")
    lines = [
        "author_text=" + ("1" if "tEXt" in kinds else "0"),
        "chunks=" + ",".join(kinds),
        "bytes=%d" % len(data),
        "idat_sha=" + hashlib.sha256(idat).hexdigest(),
    ]
    write("note.png", data)
    write("note.png.truth", "\n".join(lines) + "\n")
    print("note.png", len(data), "字节，块:", kinds)


def write(name: str, payload) -> None:
    os.makedirs(OUT, exist_ok=True)
    data = payload.encode("utf-8") if isinstance(payload, str) else payload
    with open(os.path.join(OUT, name), "wb") as handle:
        handle.write(data)


if __name__ == "__main__":
    make_jpeg()
    make_png()
    print("写到", os.path.normpath(OUT))
