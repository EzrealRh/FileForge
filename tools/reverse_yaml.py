#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证「YAML 读写」这套判据有没有牙：挨个把实现改坏，看对应那条会不会红。

绿了不算数 —— 只有改坏它确实变红，这条判据才在守东西。
每轮都重跑落盘测试（产物必须对应改坏后的代码）；有条判据没牙就退出码非 0。

跑法：python tools/reverse_yaml.py
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
YAML_DIR = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "fileforge", "core", "data")
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
DUMP = 'gradlew.bat --offline :core:test --rerun --tests "com.fileforge.core.YamlFixtureTest"'
STAMP = os.path.join(ROOT, "core", "build", "yaml", "typing.out.yaml")

# (说明, 原样, 改成, 期望红的那条)
BREAKS = [
    ("数字一律照原样写（1.5e3 这种两家读法不同的数不再改写成等价十进制）",
     'private val BOTH_READ_IT = Regex("""^-?(\\d+\\.\\d+([eE][-+]\\d+)?|\\.\\d+([eE][-+]\\d+)?|\\d+([eE][-+]\\d+)?)$""")',
     'private val BOTH_READ_IT = Regex("""^.*$""")',
     "PyYAML 读我们写出去的 YAML 得到我们那棵树"),
    ("六十进制的防护摘掉（1:2:3 会被 PyYAML 读成 3723）",
     'Regex("""^\\d{1,3}:\\d{1,2}(:\\d{1,2}(\\.\\d+)?)?$"""),',
     'Regex("""^\\d{1,3}:\\d{2}:\\d{2}$"""),',
     "PyYAML 读我们写出去的 YAML 得到我们那棵树"),
    ("看见井号就切注释（值里的 http://a#b 被吃掉一截）",
     "ch == '#' && quote == ' ' && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '\\t') ->",
     "ch == '#' && quote == ' ' ->",
     "PyYAML 读同一份夹具与我们读出来是同一棵树"),
    ("块标量碰到空行就收（日志块被截断）",
     "if (line.isBlank() || line.takeWhile { it == ' ' }.length >= base) {",
     "if (!line.isBlank() && line.takeWhile { it == ' ' }.length >= base) {",
     "块标量原样搬"),
    ("折叠块里的空行不再变换行",
     "line.isBlank() -> { out.append('\\n'); open = false }",
     "line.isBlank() -> { out.append(' '); open = true }",
     "折叠块标量"),
    ("块标量末尾的 keep 处理退化成 clip",
     'chomp == "+" -> text + "\\n".repeat(if (text.isEmpty()) trailingBlanks else trailingBlanks + 1)',
     'chomp == "+" -> text + "\\n"',
     "三种各归各的"),
    ("首尾带空格的字符串不加引号（空格被读的人吃掉）",
     "if (text.isEmpty() || text != text.trim()) return true",
     "if (text.isEmpty()) return true",
     "PyYAML 读我们写出去的 YAML 得到我们那棵树"),
]


def main():
    path = os.path.join(YAML_DIR, "Yaml.kt")
    source = io.open(path, encoding="utf-8").read()
    verdicts = []
    try:
        for label, needle, replacement, expect in BREAKS:
            if source.count(needle) != 1:
                verdicts.append("跳过（要改坏的那行没找到或有重，count=%d）：%s" % (source.count(needle), label))
                continue
            before = os.path.getmtime(STAMP)
            io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(needle, replacement, 1))
            build = subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)
            text = build.stdout.decode("utf-8", "replace") + build.stderr.decode("utf-8", "replace")
            if build.returncode != 0:
                log = os.path.join("D:", os.sep, "fflogs", "revyaml.log")
                with io.open(log, "w", encoding="utf-8") as handle:
                    handle.write(text)
                why = "编译就红了" if any(l.startswith("e: ") for l in text.splitlines()) else "落盘测试先红了"
                verdicts.append("%s -> %s（外部这套没测到，少一层算多层保险）" % (label, why))
            elif os.path.getmtime(STAMP) == before:
                verdicts.append("%s -> 产物没被重写，这轮没测到" % label)
            else:
                result = subprocess.run("python tools/verify_yaml.py", cwd=ROOT, shell=True,
                                        capture_output=True, env=ENV)
                out = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
                red = [line.strip()[3:] for line in out.splitlines() if line.strip().startswith("未过：")]
                if result.returncode != 0 and not red:
                    red = ["判据脚本整个崩了"]
                hit = [item for item in red if expect in item]
                verdicts.append("%s -> PyYAML 侧红了 %d 条 %s，本该红的「%s」%s" % (
                    label, len(red), [item[:34] for item in red], expect,
                    "红了" if hit else "没红（看上面哪条红）"))
            io.open(path, "w", encoding="utf-8", newline="\n").write(source)
    finally:
        io.open(path, "w", encoding="utf-8", newline="\n").write(source)
        subprocess.run(DUMP, cwd=ROOT, shell=True, capture_output=True, env=ENV)
    print("\n".join(verdicts))
    toothless = [line for line in verdicts if "没红" in line or "跳过" in line]
    if toothless:
        print("\n这些判据没牙：\n" + "\n".join(toothless))
    return 1 if toothless else 0


if __name__ == "__main__":
    sys.exit(main())
