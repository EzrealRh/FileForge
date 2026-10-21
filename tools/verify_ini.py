#!/usr/bin/env python3
r"""INI 的第三方裁判：Python 标准库 configparser。

core 的 IniTest 落 sample.ini / from-ini.json / out.ini / roundtrip.json；
这里核"我们读的与 configparser 读的一样"和"我们写的 configparser 读得懂"。

    python tools/verify_ini.py
"""
from __future__ import annotations

import configparser
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, HERE)
from verify_odt_read import check, results  # noqa: E402

DIR = os.path.join(ROOT, "core", "build", "ini")


def read_ini(path: str) -> dict:
    parser = configparser.ConfigParser(interpolation=None)
    parser.optionxform = str
    parser.read(path, encoding="utf-8")
    tree = {}
    if parser.defaults():
        tree[""] = dict(parser.defaults())
    for section in parser.sections():
        tree[section] = dict(parser.items(section, raw=True))
    return tree


def same(ours, ref, where="根"):
    if isinstance(ours, dict) and isinstance(ref, dict):
        if set(ours) != set(ref):
            return False, "%s 的键不同：我们 %s，configparser %s" % (where, sorted(ours), sorted(ref))
        for key in ours:
            ok, why = same(ours[key], ref[key], "%s.%s" % (where, key))
            if not ok:
                return ok, why
        return True, ""
    a = "" if ours is None else str(ours)
    b = "" if ref is None else str(ref)
    return a == b, "%s：%r 对 %r" % (where, a, b)


def main() -> int:
    for name in ("sample.ini", "from-ini.json", "out.ini", "roundtrip.json"):
        if not os.path.isfile(os.path.join(DIR, name)):
            print("缺 %s —— 先跑 ./gradlew :core:test --tests com.fileforge.core.IniTest" % name)
            return 1
    ours = json.loads(open(os.path.join(DIR, "from-ini.json"), encoding="utf-8").read())
    ref = read_ini(os.path.join(DIR, "sample.ini"))
    ok, why = same(ours, ref)
    check(1, "configparser 读 sample 与我们读出来的是同一棵树", ok, why)

    written = read_ini(os.path.join(DIR, "out.ini"))
    again = json.loads(open(os.path.join(DIR, "roundtrip.json"), encoding="utf-8").read())
    ok, why = same(again, written)
    check(2, "我们写的 INI 交给 configparser 读，与写回前是同一棵树", ok, why)

    red = [item for item in results if not item[2]]
    print("共 %d 条，%d 条红" % (len(results), len(red)))
    for number, name, _ in red:
        print("  红：%d %s" % (number, name))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main())
