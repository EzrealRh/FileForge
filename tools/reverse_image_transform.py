#!/usr/bin/env python3
r"""
`tools/verify_image_transform.py` 那 23 条的反例：改坏判层（或替身那层）→ 重编 → 重跑判据 →
必须点名到该红的那一条 → 按 sha1 还原。

    python tools/reverse_image_transform.py                 # 全跑
    python tools/reverse_image_transform.py --only swap      # 只跑名字里带这个 ASCII 标记的

一处交底：**4/6/7/18/21/23 这几条只能改替身探针那层**（`tools/imgprobe/ImgProbe.java`）——
Java2D 画、安卓 `Bitmap`+`Matrix` 画，是两份实现，桌面判据能核的是"判层给的尺寸与落点对不对"，
"应用那层画出来与判层说的一致"这一截只能靠真机那一圈（`D:/fflogs/device_image.py`）。
判层那 11 条（旋转格数、负角、裁剪坐标、百分比、居中、取整、两个给法、画布、那句话、三段报错）改的是 `:core`，与产品共用同一份代码。
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
GEOM = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "image", "ImageGeometry.kt")
PROBE = os.path.join(ROOT, "tools", "imgprobe", "ImgProbe.java")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home", IMGPROBE_OUT="D:/fflogs/imgprobe")
REBUILD = "GRADLE_USER_HOME=D:/gradle-home ./gradlew --offline :core:jar -q"
VERIFY = ["python", "tools/verify_image_transform.py"]

CASES = [
    {
        "该红": "1 转 90", "名字": "整倍数不换边 noswap", "文件": GEOM,
        "原样": "quarterTurn(degrees)?.let { return if (it % 2 == 0) width to height else height to width }",
        "改成": "quarterTurn(degrees)?.let { return width to height }",
    },
    {
        "该红": "5 负角", "名字": "负角当没转 flipneg", "文件": GEOM,
        "原样": "        return if (turned < 0f) turned + 360f else turned",
        "改成": "        return if (turned < 0f) 0f else turned",
    },
    {
        "该红": "9 按像素裁", "名字": "后两段当右下脚 xyxy", "文件": GEOM,
        "原样": "        var right = left + cropWidth\n        var bottom = top + cropHeight",
        "改成": "        var right = cropWidth\n        var bottom = cropHeight",
    },
    {
        "该红": "10 四段都带", "名字": "百分比一律按宽 allwidth", "文件": GEOM,
        "原样": "        val bases = listOf(width, height, width, height)",
        "改成": "        val bases = listOf(width, width, width, width)",
    },
    {
        "该红": "11 按 1:1", "名字": "居中改贴左上 topleft", "文件": GEOM,
        "原样": "        val left = (width - cropWidth) / 2\n        val top = (height - cropHeight) / 2",
        "改成": "        val left = 0\n        val top = 0",
    },
    {
        "该红": "12 按 16:9", "名字": "取整往上顶 ceilaspect", "文件": GEOM,
        "原样": "        val cropHeight = (ratioHeight * scale).toInt()",
        "改成": "        val cropHeight = ceil(ratioHeight * scale).toInt()",
    },
    {
        "该红": "13 裁边与宽高比", "名字": "两个都给时听比例 aspectwins", "文件": GEOM,
        "原样": "        val chosen = explicit ?: aspect",
        "改成": "        val chosen = aspect ?: explicit",
    },
    {
        "该红": "14 转 30", "名字": "画布直接截断 truncanvas", "文件": GEOM,
        "原样": "    private fun tighten(value: Double): Int =\n        if (abs(value - value.roundToInt()) <= 1e-6) value.roundToInt() else ceil(value).toInt()",
        "改成": "    private fun tighten(value: Double): Int = value.toInt()",
    },
    {
        "该红": "17 任意角那条说明", "名字": "任意角不说重采样 silentscale", "文件": GEOM,
        "原样": "            else -> notes += \"转 ${formatDegrees(turned)}°（不是 90 的整倍数，像素要重采样，画布扩到装得下整张图才不切角）\"",
        "改成": "            else -> notes += \"转了一下\"",
    },
    {
        "该红": "19 那句话由判层出", "名字": "JPG 填白那句没了 nofallback", "文件": GEOM,
        "原样": "    fun transparentFallback(opaqueFormat: Boolean): String? =\n        if (opaqueFormat) \"JPG 不留透明，空出来的地方填白\" else null",
        "改成": "    fun transparentFallback(opaqueFormat: Boolean): String? = null",
    },
    {
        "该红": "22 裁边写成三段", "名字": "少一段也不管 loosecrop", "文件": GEOM,
        "原样": '        require(parts.size == 4) { "裁边要四段「左,上,宽,高」，你给的是「$text」" }',
        "改成": '        require(parts.size >= 3) { "字段数不对" }',
    },
    {
        "该红": "6 左右镜像", "名字": "左右那一下没生效 nohorizon", "文件": PROBE, "重编": "java",
        "原样": "        float horizontal = plan.getFlip() == Flip.Horizontal || plan.getFlip() == Flip.Both ? -1f : 1f;",
        "改成": "        float horizontal = 1f;",
    },
    {
        "该红": "7 上下镜像", "名字": "上下那一下没生效 novertical", "文件": PROBE, "重编": "java",
        "原样": "        float vertical = plan.getFlip() == Flip.Vertical || plan.getFlip() == Flip.Both ? -1f : 1f;",
        "改成": "        float vertical = 1f;",
    },
    {
        "该红": "4 转 270", "名字": "角度符号反了 reverseangle", "文件": PROBE, "重编": "java",
        "原样": "        at.rotate(Math.toRadians(plan.getDegrees()), baseWidth / 2f, baseHeight / 2f);",
        "改成": "        at.rotate(Math.toRadians(-plan.getDegrees()), baseWidth / 2f, baseHeight / 2f);",
    },
    {
        "该红": "18 JPG 转 30", "名字": "铺的是黑底 blackfill", "文件": PROBE, "重编": "java",
        "原样": "        g.setColor(Color.WHITE);\n        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());",
        "改成": "        g.setColor(Color.BLACK);\n        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());",
    },
    {
        "该红": "23 JPG 转 90", "名字": "没透明也照说填白 alwaysfill", "文件": PROBE, "重编": "java",
        "原样": "        String fallback = isJpeg(target) && translucent ? ImageGeometry.INSTANCE.transparentFallback(true) : null;",
        "改成": "        String fallback = isJpeg(target) ? ImageGeometry.INSTANCE.transparentFallback(true) : null;",
    },
    {
        "该红": "21 PNG 转 30", "名字": "画布本身就没有透明 opaquecanvas", "文件": PROBE, "重编": "java",
        "原样": "        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);",
        "改成": "        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);",
    },
]


def sha1_of(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha1(handle.read()).hexdigest()


def rebuilt() -> tuple:
    run = subprocess.run(["bash", "-c", REBUILD], cwd=ROOT, capture_output=True, env=ENV)
    out = (run.stdout or b"").decode("utf-8", "replace") + (run.stderr or b"").decode("utf-8", "replace")
    lines = [line for line in out.splitlines() if line.startswith("e: ") or "FAILED" in line]
    return run.returncode != 0, " / ".join(lines[:3]) or "退出码 %s" % run.returncode


def verifier() -> tuple:
    run = subprocess.run(VERIFY, cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace", env=ENV)
    out = (run.stdout or "") + (run.stderr or "")
    return re.findall(r"^FAIL (.+)$", out, re.M), "条红" in out, out


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for case in CASES:
            print("%-34s 该红：%s（改 %s）" % (case["名字"], case["该红"], os.path.basename(case["文件"])))
        return 0
    only = args[args.index("--only") + 1] if "--only" in args else None
    files = sorted({case["文件"] for case in CASES})
    originals = {path: open(path, "rb").read() for path in files}
    digests = {path: hashlib.sha1(data).hexdigest() for path, data in originals.items()}
    print("开工：%s" % "、".join("%s sha1 %s…" % (os.path.basename(p), digests[p][:10]) for p in files))
    bad, passed = [], 0
    try:
        for case in CASES:
            if only and only not in case["名字"]:
                continue
            path = case["文件"]
            source = originals[path].decode("utf-8")
            hits = source.count(case["原样"])
            if hits != 1:
                bad.append(("锚点不唯一", case, "找到 %d 处" % hits))
                print("跳过 %-40s 锚点找到 %d 处（应当正好 1 处）" % (case["名字"], hits))
                continue
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source.replace(case["原样"], case["改成"], 1))
            broken = False
            clue = ""
            if case.get("重编") != "java":
                broken, clue = rebuilt()
            reds, finished, out = verifier()
            with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(source)
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(originals[path])
                print("停下：%s 还原后 sha1 不一致（已按原字节写回）" % os.path.basename(path))
                return 1
            if broken:
                bad.append(("改不动（编译先红）", case, clue[:160]))
                print("%-42s 改不动：编译就红了 —— %s" % (case["名字"], clue[:110]))
            elif not finished:
                bad.append(("判据没跑完", case, (out.strip().splitlines() or ["无输出"])[-1][:160]))
                print("%-42s 判据没跑完（崩了或没起来）" % case["名字"])
            elif not any(case["该红"] in line for line in reds):
                bad.append(("判据没点名", case, "红了：%s" % "、".join(reds[:3])))
                print("%-42s 判据没点到 %r（实际红：%s）" % (case["名字"], case["该红"], "、".join(reds[:3]) or "无"))
            else:
                passed += 1
                print("%-42s 红对了：%s" % (case["名字"], next(line for line in reds if case["该红"] in line)[:66]))
    finally:
        for path, data in originals.items():
            if sha1_of(path) != digests[path]:
                with io.open(path, "wb") as handle:
                    handle.write(data)
                print("收尾补还原：%s" % os.path.basename(path))
        rebuilt()
    print("\n%d 条反例按预期红，%d 条有问题" % (passed, len(bad)))
    for kind, case, clue in bad:
        print("  %s：%s —— %s" % (kind, case["名字"], clue))
    if bad:
        return 1
    reds, finished, out = verifier()
    print("收尾复跑：%s（红：%s）" % ("全绿" if finished and not reds else "不是全绿", "、".join(reds[:3]) or "无"))
    return 0 if finished and not reds else 1


if __name__ == "__main__":
    sys.exit(main())
