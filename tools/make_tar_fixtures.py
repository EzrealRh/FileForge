#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""造 tar / gzip 夹具：用**标准库 tarfile** 打几份结构与名字都已知的小包，并写下 manifest。

为什么拿 tarfile 造而不是手搓字节：这一份要给 Kotlin 侧读、又要给 tarfile 自己当裁判，
"造"与"判"用同一个实现才说明"我们认得别人写的东西" —— 反方向（我们写、别人读）由
`tools/verify_tar.py` 拿 tarfile 复核 Kotlin 产的包。

故意放进去的东西，每一个都对应一条判据：
  中文名字、名字长过 100 字节（ASCII 的深层目录走 ustar 的 prefix、中文的走 PAX 的 path）、
  零字节文件、一份 200KB 的二进制（要跨几百个块，长度算错后面全错位）、
  目录条目、符号链接与字符设备（都要跳过并说清为什么）、mtime 是两位数与四位数（八进制的空格垫法）、
  三种格式各一份（ustar / GNU / PAX，长名字的写法不一样），最后一份是**故意改坏校验和**的包
  —— 头部坏了必须停下来讲清楚，不能接着往后错位读

产出：core/src/test/resources/tar/*.tar{,.gz} 与 manifest.json（跟着仓库走）
跑法：python tools/make_tar_fixtures.py
"""
import gzip
import hashlib
import io
import json
import os
import tarfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "core", "src", "test", "resources", "tar")

MTIME = 1_700_000_000
LONG_ASCII = "deep/" + "dir/" * 26 + "report.txt"        # 115 字节纯 ASCII：ustar 的 prefix 放得下
LONG_CJK = "目录/" + "很长的中文名字" * 12 + ".txt"       # 远超 100 字节：只能走 GNU 长名或 PAX 的 path


def body(seed):
    """按种子生成可复现的内容（不用随机：夹具与 manifest 要永远对得上）。"""
    return bytes(((index * 31 + seed * 7) % 256) for index in range(0, 512))


def files(kind, fmt):
    """每份包里放什么。三种格式同一套思路，差别在长名字怎么落。

    ustar 那一份故意**不放**中文长名：那个格式在 tarfile 手里会因为字节数放不下直接报
    "name is too long" —— 这也是为什么后来的工具都改走 GNU 长名条目或 PAX 扩展头。
    """
    out = [("短.txt", "第一份是中文名字。\n".encode("utf-8"), MTIME), ("空.txt", b"", MTIME)]
    if kind == "big":
        out.append(("两万五千.bin", b"".join(body(index) for index in range(500)), 12345))
    out.append((LONG_ASCII, b"ascii long name\n", MTIME - 86_400))
    if fmt != tarfile.USTAR_FORMAT:
        out.append((LONG_CJK, "中文长名字的内容\n".encode("utf-8"), MTIME))
    return out


def build_tar(path, kind, fmt=tarfile.USTAR_FORMAT):
    """按声明造一份 tar；目录、符号链接与字符设备各塞一条。"""
    with tarfile.open(path, "w:" + ("gz" if path.endswith(".gz") else ""), format=fmt) as pack:
        for name, data, mtime in files(kind, fmt):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = mtime
            pack.addfile(info, io.BytesIO(data))
        folder = tarfile.TarInfo("一层/二层/")
        folder.type = tarfile.DIRTYPE
        folder.mtime = MTIME
        pack.addfile(folder)
        link = tarfile.TarInfo("指个链接")
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"
        link.mtime = MTIME
        pack.addfile(link)
        device = tarfile.TarInfo("设备节点")
        device.type = tarfile.CHRTYPE
        device.mtime = MTIME
        pack.addfile(device)


KINDS = {
    "0": "file", "\x00": "file", "5": "dir", "2": "symlink", "1": "link",
    "3": "char", "4": "block", "6": "fifo", "L": "longname", "x": "pax", "g": "pax-global",
    "S": "sparse", "V": "volume",
}


def listing(path):
    """独立读一遍：成员名、长度、时间、类型与内容的 sha256。manifest 与判据都以这份为准。

    类型记成语义名（file / dir / symlink / …）而不是原始字符：tarfile 在不同版本里
    对"普通文件"要嘛给 '0' 要嘛给 NUL，我们那侧也分两种 —— 判据不该被这个细节绊住。
    """
    out = []
    with tarfile.open(path, "r:*") as pack:
        for info in pack:
            data = b""
            if info.isreg() and info.size:
                stream = pack.extractfile(info)
                data = stream.read()
            raw = info.type
            char = raw.decode("ascii") if isinstance(raw, bytes) else str(raw)
            out.append(
                {"name": info.name, "size": info.size, "mtime": int(info.mtime),
                 "kind": KINDS.get(char, "other:" + char),
                 "sha": hashlib.sha256(data).hexdigest()}
            )
    return out


def break_checksum(path):
    """把中间某个头块的校验和改一位：读到这里必须停下来说明，而不是接着错位往后读。"""
    with open(path, "rb") as handle:
        raw = bytearray(handle.read())
    target = 512 * 3                       # 第四块的头部
    raw[target + 148] = (raw[target + 148] + 1) % 256
    with open(path, "wb") as handle:
        handle.write(raw)
    return raw[:target + 156]


def build_gz(path):
    """单个文件的 gz（不是 tar）：名字要写在头里的 FNAME 字段上。

    gzip 模块不给独立控制 FNAME（它拿输出路径当名字），所以这里按 RFC 1952 自己拼头，
    再用 Python 的 gzip 读回来核对 —— 名字与内容都由别人验一遍。
    """
    data = "这是一份单文件的 gzip，不是 tar 归档。\n".encode("utf-8") * 40
    name = "报告/正文.txt".encode("utf-8")
    head = bytes([0x1F, 0x8B, 8, 8]) + MTIME.to_bytes(4, "little") + bytes([0, 3])
    crc = zlib.crc32(data) & 0xFFFFFFFF
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)        # -15 = 裸 DEFLATE，不带 zlib 的头尾
    with open(path, "wb") as handle:
        handle.write(head + name + b"\x00" + compressor.compress(data) + compressor.flush()
                     + crc.to_bytes(4, "little") + len(data).to_bytes(4, "little"))
    with gzip.GzipFile(fileobj=io.BytesIO(open(path, "rb").read())) as handle:
        assert handle.read() == data, "Python 读不动这份 gz"     # FNAME 那一段它认得并跳得过去
    return hashlib.sha256(data).hexdigest(), len(data)


def main():
    os.makedirs(OUT, exist_ok=True)
    plan = [
        ("plain.tar", "plain", tarfile.USTAR_FORMAT),
        ("big.tar", "big", tarfile.USTAR_FORMAT),
        ("gnu.tar", "plain", tarfile.GNU_FORMAT),
        ("pax.tar", "plain", tarfile.PAX_FORMAT),
        ("book.tar.gz", "plain", tarfile.PAX_FORMAT),
    ]
    manifest = {"archives": [], "formats": {"ustar": "plain.tar", "gnu": "gnu.tar", "pax": "pax.tar"}}
    for name, kind, fmt in plan:
        path = os.path.join(OUT, name)
        build_tar(path, kind, fmt)
        rows = listing(path)
        files_here = [row for row in rows if row["kind"] == "file"]
        assert len(files_here) == len(files(kind, fmt)), (name, len(files_here))
        assert any(row["kind"] == "symlink" for row in rows), "符号链接那条没写进去"
        manifest["archives"].append(
            {"file": name, "members": rows, "real": len(files_here),
             "skipped": {"link": 1, "device": 1}, "dirs": 1}
        )
    broken = os.path.join(OUT, "broken.tar")
    build_tar(broken, "plain")
    break_checksum(broken)
    rows_before = listing(broken)                     # 坏块之前 Python 自己数到几条
    assert rows_before, "改坏之后一份都读不出来，这夹具没意义"
    manifest["broken"] = {"file": "broken.tar", "good_before": len(rows_before),
                          "names": [row["name"] for row in rows_before]}
    gz = os.path.join(OUT, "single.gz")
    sha, size = build_gz(gz)
    manifest["gz"] = {"file": "single.gz", "name": "报告/正文.txt", "sha": sha, "size": size}
    io.open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(manifest, ensure_ascii=False, indent=1))
    with open(os.path.join(OUT, "single.gz"), "rb") as handle:       # FNAME 是写进头里的，验一眼
        head = handle.read(64)
    assert head[:2] == b"\x1f\x8b" and head[3] & 8, "gzip 头里没记下文件名，这夹具不成立"
    print("tar 夹具 %d 份 · 成员合计 %d 条" % (
        len(manifest["archives"]) + 2, sum(len(a["members"]) for a in manifest["archives"])))


if __name__ == "__main__":
    main()

