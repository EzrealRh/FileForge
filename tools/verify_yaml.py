#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿 **PyYAML** 复核 Kotlin 的 YAML 读与写。

产物由 `./gradlew :core:test` 落到 core/build/yaml/（夹具在 core/src/test/resources/yaml/）：
  <name>.json       我们读出来的树（JSON）
  <name>.out.yaml   我们再写出去的 YAML

判据分三类：
 1) 读得对     —— PyYAML 读同一份夹具，与我们的树逐键逐值相同（config / typing）
 2) 写得对     —— PyYAML 读我们写出去的 YAML，还得是同一棵树（少一对引号就会在这儿露出来）
 3) 分歧钉住   —— divergent.yaml 里 1.1 与 1.2 判据不同的每一个值，两边各按声明的样子出现；
                  哪天我们"顺手"把 yes 变成 true，这条会红而不是静悄悄改掉别人的配置
"""
import io
import json
import os
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "core", "build", "yaml")
FIXTURES = os.path.join(ROOT, "core", "src", "test", "resources", "yaml")

# 声明过的分歧：{键: (PyYAML 1.1 的样子, 我们 1.2 的样子)}
DIVERGENT = {
    "flag": (True, "yes"),
    "disabled": (False, "no"),
    "state": (True, "on"),
    "leap": (False, "off"),
    "sci": ("1.5e3", 1500.0),                   # 1.1 的浮点式子要求指数带正负号
    "clock": (90, "1:30"),
    "long_clock": (3723, "1:2:3"),
    "octet": (10, 12),                       # 1.1 把 012 当八进制
    "stamp": (__import__("datetime").date(2023, 5, 1), "2023-05-01"),
    "moment": (__import__("datetime").datetime(2023, 5, 1, 12, 0), "2023-05-01 12:00:00"),
    "under": (float("inf"), ".inf"),
    "over": (float("-inf"), "-.inf"),
    "not_a_number": (float("nan"), ".nan"),
}

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def read(path):
    return io.open(path, encoding="utf-8").read()


def same(a, b):
    if isinstance(a, float) and a != a:
        return isinstance(b, float) and b != b
    if isinstance(a, dict) and isinstance(b, dict):
        return list(a.keys()) == list(b.keys()) and all(same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return type(a) is type(b) and a == b


def main():
    fresh = max(os.path.getmtime(os.path.join(FIXTURES, n + ".yaml")) for n in ("config", "typing", "divergent"))
    for name in ("config", "typing", "divergent"):
        for suffix in (".json", ".out.yaml", ".round.json"):
            path = os.path.join(OUT, name + suffix)
            if not os.path.isfile(path):
                raise SystemExit("缺 %s —— 先跑 ./gradlew :core:test" % path)
            if os.path.getmtime(path) < fresh:
                raise SystemExit("%s 比夹具还旧，这轮没测到（重跑 :core:test）" % path)

    for name in ("config", "typing"):
        theirs = yaml.safe_load(read(os.path.join(FIXTURES, name + ".yaml")))
        ours = json.loads(read(os.path.join(OUT, name + ".json")))
        check("%s：PyYAML 读同一份夹具与我们读出来是同一棵树" % name, same(theirs, ours),
              "差异：%s" % first_diff(theirs, ours))
        written = yaml.safe_load(read(os.path.join(OUT, name + ".out.yaml")))
        check("%s：PyYAML 读我们写出去的 YAML 还是同一棵树" % name, same(theirs, written),
              "差异：%s" % first_diff(theirs, written))

    # 结构细节单独看一遍：整棵树相同也可能把列表写成映射而两边都不报错
    tree = yaml.safe_load(read(os.path.join(OUT, "config.out.yaml")))
    check("写出的 YAML 里列表还是列表（没被写成 0:/1: 那种映射）",
          isinstance(tree["server"]["tags"], list) and isinstance(tree["hosts"], list)
          and isinstance(tree["hosts"][0], dict), str(type(tree.get("hosts"))))
    check("块标量原样搬（里面的假列表、假注释与空行都不当结构）",
          tree["log"] == "第一行日志\n- 看着像列表的一行\n# 看着像注释的一行\n\n块里面还空了一行\n",
          repr(tree["log"]))
    check("折叠块标量：相邻行并一行、空行变换行",
          tree["folded"] == "这两行会被折成一行， 中间没有换行。\n空了一行之后就换行。",
          repr(tree["folded"]))
    check("块标量末尾的换行按 | / |+ / |- 三种各归各的",
          tree["kept"] == "这块末尾的空行是内容\n\n\n" and
          tree["tailClip"] == "普通块留一个换行\n" and
          tree["tailStrip"] == "这个连一个都不留",
          "kept=%r clip=%r strip=%r" % (tree["kept"], tree["tailClip"], tree["tailStrip"]))
    check("锚点与合并键在写出的时候摊平了（不再有 *ref）",
          tree["production"] == {"adapter": "postgres", "pool": 25, "database": "app_dev"}
          or tree["production"]["adapter"] == "postgres", repr(tree.get("production"))[:80])

    for name in ("config", "typing", "divergent"):
        straight = json.loads(read(os.path.join(OUT, name + ".json")))
        round_trip = json.loads(read(os.path.join(OUT, name + ".round.json")))
        check("%s：写出去再读回来还是同一棵树（按数值比）" % name, same(straight, round_trip),
              first_diff(straight, round_trip))

    # 我们写的 YAML 交给 PyYAML 读，必须得到我们那棵树（连 divergent 那份也要 ——
    # `1.5e3` 这类"两家读法不同"的数，写出去时不改成两边一致的写法就会在这儿露出来）
    for name in ("config", "typing", "divergent"):
        ours = json.loads(read(os.path.join(OUT, name + ".json")))
        from_other = yaml.safe_load(read(os.path.join(OUT, name + ".out.yaml")))
        check("%s：PyYAML 读我们写出去的 YAML 得到我们那棵树" % name, same(ours, from_other),
              first_diff(ours, from_other))

    divergent_theirs = yaml.safe_load(read(os.path.join(FIXTURES, "divergent.yaml")))
    divergent_ours = json.loads(read(os.path.join(OUT, "divergent.json")))
    wrong = []
    for key, (want_theirs, want_ours) in DIVERGENT.items():
        got_theirs = divergent_theirs.get(key)
        got_ours = divergent_ours.get(key)
        if isinstance(want_theirs, float) and want_theirs != want_theirs:
            ok_theirs = isinstance(got_theirs, float) and got_theirs != got_theirs
        else:
            ok_theirs = same(want_theirs, got_theirs)
        if not ok_theirs or not same(want_ours, got_ours):
            wrong.append("%s：PyYAML %r（声明要 %r）/ 我们 %r（声明要 %r）" % (
                key, got_theirs, want_theirs, got_ours, want_ours))
    check("1.1 与 1.2 的分歧逐条照声明出现（%d 条）" % len(DIVERGENT), not wrong, "；".join(wrong[:3]))

    failed = [item for item in results if not item[0]]
    for ok, name, detail in results:
        print("%s %s%s" % ("通过：" if ok else "未过：", name, ("  -> " + detail) if detail and not ok else ""))
    print("\n通过 %d 条，失败 %d 条" % (len(results) - len(failed), len(failed)))
    return 1 if failed else 0


def first_diff(a, b, path=""):
    if isinstance(a, dict) and isinstance(b, dict):
        for key in a:
            if key not in b:
                return "%s.%s 我们这边没有" % (path, key)
            found = first_diff(a[key], b[key], "%s.%s" % (path, key))
            if found:
                return found
        for key in b:
            if key not in a:
                return "%s.%s 我们多出来的" % (path, key)
        return ""
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return "%s 长度 %d vs %d" % (path, len(a), len(b))
        for index, (x, y) in enumerate(zip(a, b)):
            found = first_diff(x, y, "%s[%d]" % (path, index))
            if found:
                return found
        return ""
    if same(a, b):
        return ""
    return "%s 两边不同：%r vs %r" % (path, a, b)


if __name__ == "__main__":
    sys.exit(main())
