#!/usr/bin/env python3
r"""TOML 的第三方裁判：Python 3.11+ 自带的 tomllib。

core 的 TomlTest 会落三份到 core/build/toml/：sample.toml、from-toml.json、out.toml、roundtrip.json。
这里核两件事：
  1. tomllib 读同一份 sample，与我们读出来的是同一棵树；
  2. 我们写出去的 out.toml，tomllib 读回来与 roundtrip.json 是同一棵树。
日期时间 tomllib 给的是 datetime 对象、我们保持原文；比较时两边都归一成 ISO 文本。

    python tools/verify_toml.py
"""
from __future__ import annotations

import datetime
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, HERE)
from verify_odt_read import check, results  # noqa: E402

import tomllib  # noqa: E402

DIR = os.path.join(ROOT, "core", "build", "toml")
DATEISH = re.compile(r"^\d{4}-\d{2}-\d{2}([Tt ].*)?$|^\d{2}:\d{2}(:\d{2}(\.\d+)?)?$")


def iso(value) -> str:
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    return value


def same(ours, ref, where="根"):
    if isinstance(ours, dict) and isinstance(ref, dict):
        if set(ours) != set(ref):
            return False, "%s 的键不同：我们 %s，tomllib %s" % (where, sorted(ours), sorted(ref))
        for key in ours:
            ok, why = same(ours[key], ref[key], "%s.%s" % (where, key))
            if not ok:
                return ok, why
        return True, ""
    if isinstance(ours, list) and isinstance(ref, list):
        if len(ours) != len(ref):
            return False, "%s 长度不同：%d 对 %d" % (where, len(ours), len(ref))
        for index, (a, b) in enumerate(zip(ours, ref)):
            ok, why = same(a, b, "%s[%d]" % (where, index))
            if not ok:
                return ok, why
        return True, ""
    if isinstance(ours, bool) or isinstance(ref, bool):
        return (ours is ref), "%s：%r 对 %r" % (where, ours, ref)
    if isinstance(ours, (int, float)) and isinstance(ref, (int, float)):
        return ours == ref, "%s：%r 对 %r" % (where, ours, ref)
    a, b = iso(ours), iso(ref)
    if isinstance(a, str) and isinstance(b, str) and DATEISH.match(a) and DATEISH.match(b):
        def parse(text):
            return datetime.datetime.fromisoformat(text.replace("Z", "+00:00").replace("z", "+00:00"))
        try:
            return parse(a) == parse(b), "%s：%s 对 %s" % (where, a, b)
        except ValueError:
            return a == b, "%s：%s 对 %s" % (where, a, b)
    return a == b, "%s：%r 对 %r" % (where, a, b)


def main() -> int:
    sample = os.path.join(DIR, "sample.toml")
    ours_path = os.path.join(DIR, "from-toml.json")
    out_path = os.path.join(DIR, "out.toml")
    back_path = os.path.join(DIR, "roundtrip.json")
    for path in (sample, ours_path, out_path, back_path):
        if not os.path.isfile(path):
            print("缺 %s —— 先跑 ./gradlew :core:test --tests com.fileforge.core.TomlTest" % path)
            return 1

    ours = json.loads(open(ours_path, encoding="utf-8").read())
    ref = tomllib.loads(open(sample, encoding="utf-8").read())
    ok, why = same(ours, ref)
    check(1, "tomllib 读 sample 与我们读出来的是同一棵树", ok, why)

    written = tomllib.loads(open(out_path, encoding="utf-8").read())
    again = json.loads(open(back_path, encoding="utf-8").read())
    ok, why = same(again, written)
    check(2, "我们写的 TOML 交给 tomllib 读，与写回前是同一棵树", ok, why)

    red = [item for item in results if not item[2]]
    print("共 %d 条，%d 条红" % (len(results), len(red)))
    for number, name, _ in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
