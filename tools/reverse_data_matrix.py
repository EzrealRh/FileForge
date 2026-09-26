#!/usr/bin/env python3
"""
给 tools/verify_data_matrix.py 喂反例：把实现改坏，断言它点名的那条真的红。

跑法：
    python tools/reverse_data_matrix.py                 # 逐个改坏 → 重跑落盘测试 → 跑判据 → 还原
    python tools/reverse_data_matrix.py --only 顶层数组不包根
    python tools/reverse_data_matrix.py --list

每个反例只破一处，并且**断言红在它该红的那条上**。改坏的是 Kotlin 源文件：
先把原字节写到 scratch/，跑完原样写回，最后一律核对 sha1 并用 git status 证明逐字节还原
（不用 git checkout 撤销 —— 同一份文件里可能还有别的未提交改动）。

先看单元测试红不红，再看外部判据红不红：源码改坏常被自家测试先拦住，
那种情况下要的是"外部判据也会红"的证据，所以两处都记。
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

GRADLE = 'gradlew.bat --offline :core:test --tests "com.fileforge.core.DataMatrixTest" --rerun'
ENV = dict(os.environ, GRADLE_USER_HOME="D:/gradle-home")
MATRIX = "com.fileforge.core.DataMatrixTest"

TAMPERS = [
    {
        "名字": "顶层数组不包根元素",
        "判据": 3,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Xml.kt",
        "原样": 'return root to if (value is JsonArray) item else null',
        "改成": 'return root to null',
    },
    {
        "名字": "多键对象也被拆成第一个键（数据没了）",
        "判据": 7,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Xml.kt",
        "原样": 'val rootValue = if (only != null) only.values.first() else value',
        "改成": 'val rootValue = (value as? JsonObject)?.members?.values?.firstOrNull() ?: value',
    },
    {
        "名字": "属性不再带 @ 前缀",
        "判据": 1,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Xml.kt",
        "原样": 'attributes.forEach { (name, value) -> members[ATTRIBUTE_PREFIX + name] = JsonString(value) }',
        "改成": 'attributes.forEach { (name, value) -> members[name] = JsonString(value) }',
    },
    {
        "名字": "CSV 的首行也当数据行",
        "判据": 4,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Csv.kt",
        "原样": 'val columns = dedupe(doc.records.first())\n        val rows = doc.records.drop(1).map { row ->',
        "改成": 'val columns = dedupe(List(doc.widest) { "列${it + 1}" })\n        val rows = doc.records.map { row ->',
    },
    {
        "名字": "摊表时把最后一行丢了",
        "判据": 5,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Csv.kt",
        "原样": 'fun toTable(json: Json): Table? {\n        if (reasonWhyNotTable(json) != null) return null\n        val items = json.arrayValue',
        "改成": 'fun toTable(json: Json): Table? {\n        if (reasonWhyNotTable(json) != null) return null\n        val items = if (json.arrayValue.size > 1) json.arrayValue.dropLast(1) else json.arrayValue',
    },
    {
        "名字": "看着像别的类型的值不加引号直接写",
        "判据": 6,
        "文件": "core/src/main/kotlin/com/fileforge/core/data/Yaml.kt",
        "原样": 'private fun quote(text: String): String {\n        if (!needsQuote(text)) return text',
        "改成": 'private fun quote(text: String): String {\n        if (true) return text',
    },
]


def read(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def sha1(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def run_gradle(keep: str = "") -> bool:
    done = subprocess.run(GRADLE, shell=True, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=ENV)
    if keep:
        with open(keep, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(done.stdout + "\n--- stderr ---\n" + (done.stderr or ""))
    return done.returncode == 0


def run_matrix() -> tuple:
    done = subprocess.run([sys.executable, os.path.join("tools", "verify_data_matrix.py")],
                          capture_output=True, text=True, encoding="utf-8")
    reds = re.findall(r"^FAIL (\d+) ", done.stdout, re.M)
    return done.returncode, reds, done.stdout + done.stderr


def main(argv: list) -> int:
    if "--list" in argv:
        for case in TAMPERS:
            print("%d  %s" % (case["判据"], case["名字"]))
        return 0
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    originals = {}
    for case in TAMPERS:
        path = case["文件"]
        if path not in originals:
            originals[path] = read(path)
    baseline = {path: sha1(data) for path, data in originals.items()}
    if not run_gradle():
        print("基线就没绿：先修好 :core:test --tests '*DataMatrixTest*' 再谈反例")
        return 1
    code, reds, output = run_matrix()
    if code != 0:
        print("判据基线就红，反例还没测：\n%s" % output[-1200:])
        return 1

    misses = []
    tried = 0
    for case in TAMPERS:
        if only and only != case["名字"]:
            continue
        tried += 1
        path = case["文件"]
        text = originals[path].decode("utf-8")
        if case["原样"] not in text:
            print("反例失效 %-24s 源文件里找不到那段锚点（实现改过，这条得跟着改）" % case["名字"])
            misses.append((case["名字"], "锚点没了"))
            continue
        mutated = text.replace(case["原样"], case["改成"], 1)
        if mutated == text:
            print("反例失效 %-24s 改了个空" % case["名字"])
            misses.append((case["名字"], "改等于没改"))
            continue
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(mutated)
        tests_green = run_gradle(os.path.join("build", "reverse-gradle.log"))
        code, reds, _ = run_matrix()
        restore(originals)
        if code == 0 and tests_green:
            print("没牙   %-26s 单元测试与判据都没红" % case["名字"])
            misses.append((case["名字"], "全绿"))
        elif str(case["判据"]) not in reds:
            if not tests_green:
                note = "单元测试先红了，判据没红（红的是 %s）—— 看 build/reverse-gradle.log 确认落盘测试跑没跑" % (sorted(set(reds)) or "无")
            else:
                note = "判据红了 %s，没点到 %d" % (sorted(set(reds)), case["判据"])
            print("点错名 %-26s %s" % (case["名字"], note))
            misses.append((case["名字"], note))
        else:
            print("有牙   %-26s 判据 %d 红了（单元测试%s；共红 %s）"
                  % (case["名字"], case["判据"], "也拦了一道" if not tests_green else "没拦住", sorted(set(reds))))

    after = {path: sha1(read(path)) for path in originals}
    if after != baseline:
        print("源文件没还原干净：%s" % [p for p in baseline if baseline[p] != after[p]])
        return 1
    status = subprocess.run(["git", "status", "--short", "--", "core/src/main/kotlin"],
                            capture_output=True, text=True, encoding="utf-8").stdout.strip()
    print("\n试了 %d 个反例，问题 %d 个；改完的源文件按 sha1 还原 ✓  git status（core 主源码）：%s"
          % (tried, len(misses), status if status else "干净"))
    for name, why in misses:
        print("  - %s：%s" % (name, why))
    return 1 if misses else 0


def restore(originals: dict) -> None:
    for path, data in originals.items():
        with open(path, "wb") as handle:
            handle.write(data)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
