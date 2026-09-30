"""写三份 .rtf 夹具到 core/src/test/resources/rtfread/。

RTF 没有可靠的第三方写者可用（pandoc 只有读者、没有写者；本机没有 Word），所以夹具是
手写的 —— 按 RTF 规范那一套控制字写，段落起头写 `\\pard\\plain`（真实写者就是这么写的）。
为了让"手写的那一份是不是真按规范"这件事有旁证，每份都要能让 pandoc 的独立 RTF 读者
读出对应的结构；读不出来的写法就换掉。

字节全用 ASCII：非 ASCII 的字一律走 `\\uN` 加一个兜底写法，这样"编码怎么解"这件事本身
是被测对象，而不是被 Python 顺手替我们做掉了。
"""

import os

OUT = os.path.join("core", "src", "test", "resources", "rtfread")

STYLE = r"""{\fonttbl{\f0\fswiss Helvetica;}{\f4\fmodern Courier New;}}
{\stylesheet
{\s1\sbasedon0\snext0\outlinelevel0\b\fs32 Heading 1;}
{\s2\sbasedon0\snext0\outlinelevel1\b\fs28 Heading 2;}
{\s3\sbasedon0\snext0\fs24 Body Text;}
}
"""

BASIC = r"""{\rtf1\ansi\ansicpg1252\deff0
""" + STYLE + r"""{\colortbl ;\red0\green0\blue255;}
{\*\generator Test Writer 1.0;}
\pard\plain \s1 季度报告\par
\pard\plain \s2 分节标题\par
\pard\plain 这是普通的一段，里面有 \b 粗体\b0 、\i 斜体\i0 、\ul 下划线\ulnone 与 \strike 删除线\strike0 。第二行接在同一个段落里\line 这是段内换行，下一格是制表：\tab 制表后面。\par
\pard\plain \s1 \u20013?\u25991?\u26631?\u39064?\par
\pard\plain 引号按 1252 那一页：\'93 quoted \'94 与 \'85 还有 \'92turn\'92。带音标的 \u233\'e9 与破折号 \u8212?。\par
\pard\plain \f4 等宽的字\plain 回到普通字体。\par
\pard\plain \s3 这一格是正文样式（样式表里没写层级），不该当成标题。\par
\pard\plain 列表：\par
\pard\plain \ilvl0 {\listtext\bullet }圆点第一项\par
{\listtext\bullet }圆点第二项\par
\pard\plain \ilvl0 {\listtext 1.}编号第一项\par
{\listtext 2.}编号第二项\par
\pard\plain \ilvl1 {\listtext\bullet }更深一层的圆点\par
\pard\plain \ilvl1 {\listtext a)}更深一层的编号\par
\pard\plain 链接在 {\field{\*\fldinst{HYPERLINK "https://example.com/report"}}{\fldrslt 这里}}，后面是没有链接的字。\par
\pard\plain 页码这种域搬它算出来的字：{\field{\*\fldinst PAGE }{\fldrslt 3}}。\par
\pard\plain 脚注后面这段{\footnote 这段脚注正文不该出现在正文里}接着说。\par
\pard\plain 图片跟着的文字仍在正文里。{\pict\wmetafile8 0102030405060708090A0B0C0D0E0F}\par
\pard\plain 大括号与反斜杠是字面：\{ 与 \} 与 \\。\par
\pard\plain 零宽字符\| 与可选连字\- 不占字。\par
\pard\plain 不认的控制字 \madeupword 后面这句照搬。\par
}
"""

TABLE = r"""{\rtf1\ansi\ansicpg1252\deff0
{\fonttbl{\f0\fswiss Helvetica;}}
{\*\generator Test Writer 1.0;}
\pard\plain 先看表外面的一段。\par
\trowd\trhdr\trgaph108\trleft0\clvertalt\cellx2000\clvertalt\cellx4000\clvertalt\cellx6000
\intbl 名称\cell\intbl 数量\cell\intbl 备注\cell\row
\trowd\trgaph108\trleft0\clvertalt\cellx2000\clvertalt\cellx4000\clvertalt\cellx6000
\intbl 苹果\cell\intbl 12\cell\intbl 一格的备注\cell\row
\trowd\trgaph108\trleft0\clvertalt\cellx2000\clvertalt\cellx4000\clvertalt\cellx6000
\intbl 只有两格的行\cell\intbl 第三格没写\cell\row
\trowd\trgaph108\trleft0\clvertalt\cellx2000
\intbl 只声明了一列\cell\row
\pard\plain 表后面这段把表结束。\par
\pard\plain 没有表头的第二张表：\par
\trowd\trgaph108\trleft0\clvertalt\cellx2000\clvertalt\cellx4000
\intbl 甲\cell\intbl 乙\cell\row
\trowd\trgaph108\trleft0\clvertalt\cellx2000\clvertalt\cellx4000
\intbl 一个格里两段\par 这是第二段\cell\row
\pard\plain 第三张表：每行都只写了一格，但都声明了三列。\par
\trowd\trgaph108\trleft0\clvertalt\cellx1000\clvertalt\cellx2000\clvertalt\cellx3000
\intbl 三列只写了一格\cell\row
\trowd\trgaph108\trleft0\clvertalt\cellx1000\clvertalt\cellx2000\clvertalt\cellx3000
\intbl 又一格\cell\row
\pard\plain 表完了。\par
}
"""

NESTED = r"""{\rtf1\ansi\ansicpg1252\deff0
{\fonttbl{\f0\fswiss Helvetica;}}
\pard\plain 外层的一段。\par
\trowd\trgaph108\trleft0\clvertalt\cellx3000\clvertalt\cellx6000
\intbl 外格开头\trowd\trgaph108\cellx1000\intbl 内A\nestcell\intbl 内B\cell\nestrow\cell
\intbl 外格第二列\cell\row
\pard\plain 表完了。\par
}
"""

CODEPAGE = r"""{\rtf1\ansi\ansicpg936\deff0
{\fonttbl{\f0\fnil SimSun;}}
{\*\generator Test Writer 1.0;}
\pard\plain 中文按 936 那一页：\'c4\'e3\'ba\'c3\'ca\'c0\'bd\'e7，后面是句号\'a1\'a3\par
\pard\plain 混着写：\uc2\u20013\'c4\'e3\uc1 后面按默认\uc2\u22909\'ba\'c3\uc1 结束。\par
\pard\plain \uc0\u66\'41\uc1 这一格写着 uc0 与两个拉丁字母。\par
\pard\plain 二进制流 \bin4abcd 后面的正文字。\par
\pard\plain 跳掉的块 {\*\madename 这一整块的字不该进正文}后面接着说。\par
\pard\plain 不在表里的 \cell 这句按段落收。\par
}
"""


def esc(text: str) -> str:
    """非 ASCII 的字按 RTF 的写法出：`\\uN` 带一个 `?` 兜底（`\\uc` 默认就是 1）。

    夹具的字节因此全是 ASCII —— 编码怎么解这件事本身就是被测对象，不能让 Python 顺手做掉。
    """
    return "".join(ch if ord(ch) < 128 else "\\u%d?" % ord(ch) for ch in text)


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for name, body in (("basic.rtf", BASIC), ("table.rtf", TABLE), ("nested.rtf", NESTED), ("codepage.rtf", CODEPAGE)):
        data = esc(body).encode("ascii")
        with open(os.path.join(OUT, name), "wb") as handle:
            handle.write(data)
        print(name, len(data), "字节")


if __name__ == "__main__":
    main()
