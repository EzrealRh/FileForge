#!/usr/bin/env python3
"""用 ffmpeg 复核 FLAC 编码器：解回来的 PCM 必须与源 WAV 的 data 逐字节相同。

源与产物由 FlacEncoderTest 写到 core/build/flac/：
  source.wav / encoded.flac
"""
import hashlib
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "build", "flac")
failures = []


def check(label, ok, detail=""):
    print(("  通过  " if ok else "  失败  ") + label + (("：" + detail) if detail else ""))
    if not ok:
        failures.append(label)


def wav_data(path):
    data = open(path, "rb").read()
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise SystemExit("source.wav 不是 RIFF/WAVE")
    i = 12
    while i + 8 <= len(data):
        size = int.from_bytes(data[i + 4:i + 8], "little")
        if data[i:i + 4] == b"data":
            return data[i + 8:i + 8 + size]
        i += 8 + size + (size & 1)
    raise SystemExit("source.wav 没有 data 块")


def main():
    if not os.path.isdir(DIR):
        print("找不到 %s，先跑 :core:test" % os.path.normpath(DIR))
        return 3
    src = wav_data(os.path.join(DIR, "source.wav"))
    flac = os.path.join(DIR, "encoded.flac")

    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0",
                            "-show_entries", "stream=sample_rate,channels,duration",
                            "-of", "default=nw=1", flac], capture_output=True, text=True)
    print(probe.stdout.strip())
    check("ffprobe 能读", probe.returncode == 0, probe.stderr.strip()[:120])
    check("采样率 48000", "sample_rate=48000" in probe.stdout)
    check("双声道", "channels=2" in probe.stdout)

    raw = open(flac, "rb").read()
    md5_in_file = raw[26:42].hex() if raw[:4] == b"fLaC" else ""
    check("STREAMINFO 的 MD5 与源 PCM 一致", md5_in_file == hashlib.md5(src).hexdigest(),
          "%s vs %s" % (md5_in_file, hashlib.md5(src).hexdigest()))

    decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", flac, "-f", "s16le", "-acodec", "pcm_s16le", "-"],
                            capture_output=True)
    check("ffmpeg 能解码", decoded.returncode == 0, decoded.stderr.decode("utf-8", "replace")[:120])
    check("PCM 与 WAV data 逐字节相同", decoded.stdout == src,
          "%d vs %d 字节" % (len(decoded.stdout), len(src)))

    if failures:
        print("\n%d 项不通过：" % len(failures))
        for item in failures:
            print("  -", item)
        return 1
    print("\nffmpeg 认可：FLAC 解码回来与源 PCM 一字节不差。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
