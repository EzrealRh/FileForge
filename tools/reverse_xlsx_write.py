#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「CSV → xlsx」这套检查有没有牙：挨个把写入侧改坏，看 openpyxl 那 11 条里对应的那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条检查才在守东西。
跑法：先 ./gradlew :core:test，再 python tools/reverse_xlsx_write.py
（跑完自动还原 XlsxWrite.kt；有条检查没牙就退出码非 0）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
TARGET = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "office", "XlsxWrite.kt")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = "gradlew.bat --offline :core:test --rerun --tests \"com.fileforge.core.XlsxWriteFixtureTest\""

BREAKS = [
    # 名字：改成什么，期望哪条判据红
    ("前导零那条判据失效（007 会被写成数字）",
     "        if (whole.length > 1 && whole[0] == '0') return false",
     "        if (whole.length > 60 && whole[0] == '0') return false",
     "全部还是文字"),
    ("Content_Types 用错命名空间",
     'out.append(DECL).append("<Types xmlns=\\"").append(CT_PKG)',
     'out.append(DECL).append("<Types xmlns=\\"").append(REL_PKG)',
     "声明的部件包里都有"),       # openpyxl 对命名空间宽松，红在"清单与包内容对得上"这条上
    ("格子引用丢掉行号",
     "        val ref = columnRef(column) + row",
     "        val ref = columnRef(column)",
     "打得开"),               # 引用写坏是整包不可读，红在"打得开"这条上
    ("表名不收敛（非法字符原样写）",
     'var name = raw.replace(ILLEGAL_IN_NAME, " ").trim().take(NAME_LIMIT).trim().trimEnd(\'\\\'\')',
     'var name = raw',
     "表名按 Excel"),
    ("说明里的文字格子数少报一半",
     'notes += "${tally.numbers} 格按原样写成数字',
     'notes += "${tally.numbers / 2} 格按原样写成数字',
     "格子数与实际一致"),
]


def product_stamp():
    """三份产物的修改时间：变了才说明这轮真的重跑并落盘了。"""
    folder = os.path.join(ROOT, "core", "build", "xlsx-write")
    return tuple(
        os.path.getmtime(os.path.join(folder, name)) if os.path.isfile(os.path.join(folder, name)) else 0
        for name in ("table.xlsx", "table.notes", "table.cells"))


def run(command):
    return subprocess.run(command, cwd=ROOT, shell=True, capture_output=True, env=ENV)


def red_checks():
    out = run("python tools/verify_xlsx_write.py").stdout.decode("utf-8", "replace")
    return [line.strip()[3:] for line in out.split("\n") if line.strip().startswith("未过：")]


def main():
    backup = TARGET + ".reverse-backup"
    shutil.copyfile(TARGET, backup)
    original = io.open(backup, encoding="utf-8").read()
    verdicts = []
    try:
        for name, needle, replacement, expect in BREAKS:
            if needle not in original:
                verdicts.append("跳过（找不到要改坏的那行）：" + name)
                continue
            io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original.replace(needle, replacement, 1))
            stamp = product_stamp()
            dump = run(DUMP)
            stale = product_stamp() == stamp
            if dump.returncode != 0:
                log = os.path.join("D:", os.sep, "fflogs", "rev-%d-dump.log" % len(verdicts))
                text = dump.stdout.decode("utf-8", "replace") + dump.stderr.decode("utf-8", "replace")
                with io.open(log, "w", encoding="utf-8") as handle:
                    handle.write(text)
                first = [line for line in text.splitlines() if line.startswith("e: ")][:1]
                reason = "编译就红了" if first else "落盘测试先红了"
                verdicts.append("%s -> %s（外部这套没测到）%s" % (name, reason, first))
            else:
                result = run("python tools/verify_xlsx_write.py")
                with io.open(os.path.join("D:", os.sep, "fflogs", "rev-%d-verify.log" % len(verdicts)), "w",
                             encoding="utf-8") as handle:
                    handle.write(result.stdout.decode("utf-8", "replace"))
                out = result.stdout.decode("utf-8", "replace")
                red = [line.strip()[3:] for line in out.splitlines() if line.strip().startswith("未过：")]
                if stale:
                    verdicts.append("%s -> 产物没被重写（Gradle 判成无需重跑），外部这套没测到" % name)
                    io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original)
                    continue
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> openpyxl 侧红了 %d 条 %s，本该红的「%s」%s" % (
                    name, len(red), red, expect, "红了" if hit else "没红（换个名字：看上面哪条红）"))
            io.open(TARGET, "w", encoding="utf-8", newline="\n").write(original)
    finally:
        shutil.copyfile(backup, TARGET)
        os.remove(backup)
        # 还原之后再落一次盘：产物必须对应还原后的代码，不然下一次跑判据会拿到坏产物
        run(DUMP)
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些检查没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
