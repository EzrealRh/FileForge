# 文件工坊 FileForge

本地运行的安卓文件转换工具。图片、PDF、GIF、视频都在手机上处理，不上传任何东西；只有你主动点「检查更新」时才访问 GitHub。

## 装到手机

1. 从 GitHub Release 下载 `fileforge-v<版本>-release.apk`（或 `fileforge-v<版本>-debug.apk`）传到手机（数据线、网盘、聊天工具互传都行）
2. 在手机上点开这个 apk → 允许"安装未知应用"
3. 桌面出现「文件工坊」

## 用法

- 首页「加 PDF」直接挑 PDF；「相册」在应用里翻网格多选图片和视频；GIF、HEIC 等其他类型走系统文件选择器
- 勾选文件 → 底部「转换」→ 选操作 → 调参数 → 开始
- 转换结果作为新文件留在同一个列表里，可以继续勾选再加工（GIF 拆成图片、图片合成 GIF、视频抽一帧做封面都走这条路）
- 转换完成后自动复制一份到手机存储的 `文件工坊` 目录（个别系统不让建顶层目录，就落到 `Download/文件工坊`）
- 顶栏图标依次是：存到手机（复制到手机存储的 文件工坊 目录）、导出到指定文件夹、分享（选中几个分享几个）、删除（有选中只删选中，没选中清空整个工作台）
- 筛选条最右边是「检查更新」；详情页有「打开」，把成品交给系统里能处理它的 App（PDF 给阅读器、视频给播放器、图片给相册），没有能接的应用就自动退回分享面板
- 长按卡片看详情：GIF、动图 WebP、视频会直接播放；详情页显示文件位置（可一键复制）、能打开也能分享
- 筛选条上的「按日期」：列表按 今天 / 昨天 / 本周 / 更早 分组；「按批次」：同一次导入的文件、以及这批文件转出来的结果算一组，点组头整批选中
- 也可以在别的 App 里点分享 → 文件工坊

## 当前功能

| 分类 | 能做 |
| --- | --- |
| PDF | 压缩（可指定目标体积）、按体积分割、拆成 N 份、按页码截取（多段/倒序）、删除页、旋转页面、合并、提取文字、**转 Word**（按字号与位置把标题层级、列表与段落还原回来）、**加页码**（三种样式、四个位置、可只给部分页）、**加文字水印**（居中或平铺、深浅和倾斜可调、中文可用）、每页导出图片、图片合成 PDF、**加打开密码与权限限制**、**去掉密码出明文副本** |
| 图片 | JPG / PNG / WebP / BMP / HEIC / AVIF 互转，压缩到目标体积或质量，限最长边；做成多尺寸 .ico 图标，也能把 .ico 里的画面拆出来 |
| 元数据 | 查看与清除 JPEG / PNG 的 EXIF、GPS、机型、软件、注释：**按段照抄，像素一个字节不重新编码**，ICC 色彩配置与影响显示的段保留 |
| 压缩包 | 多份文件打包成 ZIP（任何类型都行，重名自动编号）；解 ZIP —— 认 zip64、UTF-8 与 GBK 文件名、Deflate 与直存，条目清单在详情页直接看；**打包成 tar.gz** 与 **解开 tar / tar.gz / 单个 .gz** —— 长名字的三种写法（ustar 的 `prefix`、GNU 的长名条目、POSIX 的 PAX 扩展头）都认，符号链接与设备节点跳过并说明，头块校验和坏了就停在那一条上 |
| 数据格式 | JSON 格式化（缩进/压平、键排序、非 ASCII 转码，**数字原文不被改写**）、JSON ↔ CSV 互转（分隔符自动识别、RFC 4180 引号、引号内换行）、JSON ↔ XML 互转、**YAML ↔ JSON 与 YAML ↔ CSV**（类型按 YAML 1.2 核心模式判：`yes` 与 `1:30` 保持文字，不会变成 `true` 与 `90`）、**XML → CSV / Excel**（取重复出现最多的那个元素当行，列名走元素路径 `作者.姓名`，属性带 `@`，元素自己的文字进「文本」列） |
| Office 文档 | docx / pptx 抽正文文字成 txt（表格拍平成行、修订里删掉的字不混进来、丢了什么逐条写明），也能直接印成 PDF 或**重排成一份新 .docx**；xlsx 每张表转一份 CSV —— 日期按样式认出并转成 ISO 写法，稀疏行列按引用对位；**反向也有**：CSV / JSON 写成一份真 .xlsx，格子类型只按字面判（`007`、`1.50`、15 位以上的编号保持文字） |
| 写成 Word | 文本 / Markdown / 网页 / PDF → .docx：标题、列表、表格、引用、代码块、加粗斜体删除线走 Word 的**样式**，链接是**能点开的真链接**，列表记号由 Word 自己画（删一行序号不会错位）；按内容挑路 —— 有网页骨架按网页排，有 Markdown 记号按 Markdown 排，PDF 则按字号与位置把结构**推**回来 |
| GIF | 压缩（降边长、降帧率、减色数），逐帧拆成图片或只取首帧，多张图片合成 GIF，MP4 / WebM / MKV 转 GIF |
| 视频 | 硬件转码压缩（MP4/H.264、WebM/VP8），可按目标体积反推码率，按秒数抽一帧成图片 |
| 文本 | 换编码（UTF-8 / GB18030 / GBK / Big5 / Shift_JIS / ISO-8859-1 / UTF-16），顺带统一换行风格（LF / CRLF / CR）；把 txt 印成 PDF（A4/A5/Letter，字号·行距·页边距·首行缩进·页码可调，自动断行分页）；Markdown 转 HTML（带 charset 的完整页面）与转纯文本 |
| 网页 | HTML 抽文字成 txt（段落空行、列表记号、表格分列留着）与转 Markdown（标题、列表、表格、链接、图片写成标记）；**网页里的表格逐张转 CSV**（`colspan` / `rowspan` 按跨度占位，跨过的格子留空）；标签没闭合的按浏览器那套补并说明补了几处，脚本样式页眉丢掉、认不出的实体照字面留 |
| 字幕 | SRT / WebVTT / LRC / ASS 四方互转；源格式按文件签名 > 扩展名 > 逐个试 的顺序认 |
| 电子书 | EPUB 抽全书文字成 txt、转 Markdown、写成 .docx —— 章节按 spine 的阅读顺序排（**不按文件名**），章名先取目录里写的（NCX 与 EPUB3 的 `nav` 都读），没有才退回文档自己的标题；页内锚点在摊平成一篇之后没有落点，只留文字并说明几处；样式与图片不是文字，只数不搬；EPUB 2 时代留下的 UTF-16 章节照样读。反方向也通：**文本 / Markdown / 网页 / Word 写成 .epub** —— 按一级标题切章，目录交 NCX 与 `nav` 两份，新旧阅读器都抓得到，书名作者可填、语言按正文数出来，同一份内容每次导出的书号是同一个 |
| 音频 | MP3 / FLAC / OGG / M4A / WAV 转成 **M4A 或 WAV**，可按目标体积反推码率；从视频里把声音单独提出来（源音轨本来就是 AAC 时原样搬出，不重编） |
| 取文件 | 应用内相册选择器、系统文件选择器（可只看 PDF；音频等其他类型都走这个入口）、接收其他 App 分享 |
| 给文件 | 自动复制到手机存储 文件工坊、用其他应用打开、分享出去、导出到指定文件夹、存到相册 |
| 更新 | 筛选条上的「检查更新」→ 面板里直接下载安装包 → 拉起系统安装器；仓库公开，零配置可用（留了 token 输入框备用） |
| 工作台 | 结果留在列表里可继续再加工、按日期或按批次分组、详情页播放动图和视频、显示文件位置 |

文件处理全程在本机完成，不上传任何东西。

## 工程结构

```
core/   纯 Kotlin，无安卓依赖，可 JVM 单测
  core/pdf/    页码范围解析、按体积分组、按份数均分、压缩档位阶梯、页码/水印的排版与旋转页坐标换算、纯文本→PDF 的断行与分页（宽度由调用方给尺子，规则本身无字体 API）；
               反方向的 PdfDoc 把"画在纸上的样子"推回文档结构：正文基准取按字数加权的字号众数，
               标题要"比正文大 8% 以上、这一档占字不到 12%、且行平均够长"三条都中才算，
               重面字按字体名认（/Flags 的 Bold 位在中文文件里不可信）、跨页重复且落在纸上下带子里的行当页眉删，
               行距比例当折行信号、页界不并段、英文行尾的软连字符拼回词
  core/gif/    GIF89a 编解码（LZW + 中位切分量化）、多图合成 GIF 的画布夹算
  core/json/   只读用的极简 JSON 树（为的是"有没有新版"能在 JVM 上单测）
  core/meta/   图片容器拆段（JPEG 标记 / PNG 块）、EXIF 的 TIFF 读取、按段照抄的元数据清理
  core/archive/  zip 的读与写：EOCD 从尾部倒找、zip64 哨兵值、名字编码（UTF-8 标志位缺失时按 UTF-8→GBK→CP437 退）、本地头先占位再回填；
               tar 的读与写：512 字节的头块按 POSIX 那张表取字段，八进制与二进制补码两种数都认，
                 名字按 ustar 的 prefix / GNU 的 L 条目 / PAX 的 path 三种写法认，写出去时长名字走 PAX、
                 纯 ASCII 的深层路径走 prefix；头块校验和只算自己那一块（把那 8 字节当空格），不中就在原地停下并报数；
               gzip（RFC 1952）的读与写：头部 FEXTRA/FNAME 自己解（库不给那个原始文件名），CRC32 与未压缩长度随尾核对；
               解压决策（压平名字、跳过口令与符号链接与设备、体积上限）两种容器共用一套，条目抽象成一个接口
  core/data/   ICO 图标的目录与 DIB/PNG 两种内嵌载荷、CSV 的读写（RFC 4180 引号、分隔符按引号外的票猜、BOM 与 CRLF）、JSON↔表的桥（列取并集、嵌套压成一格文本、会丢什么逐条声明）、XML↔JSON（约定：子元素成数组、属性加 @、文字进 #text；带 DTD 一律不解析；属性按名字排序再落树，DOM 不保证属性顺序，不排就导致同一份文件在安卓与桌面上转出不同列顺序）、XML→表（哪一处重复元素当行：条数最多优先、字段数次之、浅的优先；单值嵌套摊成 父.子、真嵌套压成一格并点名）、
               YAML 子集读写（块式映射与序列、行内 [] {}、块标量 | 与 >、两种引号、注释、锚点与 << 合并；
               类型按 1.2 核心模式判，写出时对"两家读法不同"的值加引号或改成等价写法 —— 目标是任何人读回去都是同一个值）
  core/book/   EPUB 的读入侧：包里的 container.xml → OPF 的 manifest 与 spine（**spine 才是阅读顺序**，
               href 要解百分号、去掉片段、按 OPF 所在目录相对、`..` 折回包根），章名先取目录里写的
               （NCX 与 EPUB3 的 `nav` 都读，两份都在时听 NCX 的 —— spine 上 toc 指的那份优先；
               规范里 navLabel 在 content 前面，只能一个 navPoint 一组地攒），没有才退回文档自己的 title；
               `properties="nav"` 那份是目录不是正文，不排进章节也不算"没排进顺序的部件"；
               线性为 no 的项不排进正文，图片与没进顺序的部件只数不搬，缺件与对不上的 idref 报数不崩；
               内容文档按 BOM 与 XML 声明认 UTF-8 / UTF-16；正文解析继续用 core/doc 那套容错 HTML，
               抽文字 / Markdown / Word 三条出路共用一个判断，不会这边是列表那边成段落
               写出侧：同一棵 Doc 打成一份合法包 —— mimetype 排第一且不压缩、container 指 OPF、
               manifest 与 spine 互相对得上、目录交 NCX + `nav` 两份、章节是 XHTML（标签按结构拼、
               `&` 与 `<` 一律转义、`<!DOCTYPE html>` 不带公共标识符以免解析器联网取 DTD）；
               章按一级标题切（标题也留在正文里，读回来才不少一级），号按"书名 + 原文"用 RFC 4122
               §4.3 的名字算法折出来（同一份内容每次导出同一个号），语言按文档树里的字符数（标签名不算正文）
  core/text/   编码识别与严格解码、换行风格、字幕四方格式的时间轴
  core/office/ OOXML（docx / xlsx / pptx）读与写：按部件名判类型、按文件自己的大纲取页序表序（不靠文件名排序）、
               正文走树抽字（一段一行、表格拍平成制表符、修订删掉的字与域代码不混进正文、丢了什么逐条声明）、
               工作簿按格子引用对位（稀疏行列不错位）、样式里的日期序列号转成 ISO（数字写法一律原样搬）；
               写入侧是同一条链子的另一半：最小但齐全的 SpreadsheetML 包（部件清单 / 关联 / 样式），
               格子类型只按"变成数字后能不能一字不差读回来"判，表名收敛到 Excel 认的 31 字与非法字符规矩；
               WordprocessingML 的写入侧共用同一套包骨架（[Content_Types] / _rels / 部件），正文走样式而非直接格式
               （Heading 1..6 / Quote / List Paragraph / Source Code / VerbatimChar 都用 Word 认的内置名），
               列表记号写在 numbering.xml 里由 Word 画，链接走文档外的关联表（TargetMode="External"）
  core/doc/    Markdown 渲染：块级切分（围栏代码、ATX/Setext 标题、引用、可嵌套列表、GFM 表格与任务列表、
               HTML 块）+ 行内一套（强调、代码、链接与引用式链接、图片、自动链接、删除线、转义），
               HTML 与纯文本两种输出共用同一套判定；认不出的写法照字面留下并说明。
               另一路是 HTML 读取：容错词法（原始文本元素、void 标签、不闭合、老式不带分号的实体）+
               按浏览器规矩建树，再出纯文本与 Markdown（补了几处标签、丢了几段脚本、几处实体认不出都说得清）；
               表格按 colspan/rowspan 跨度摆进方格，三种输出（纯文本 / Markdown / CSV 逐张）共用这一套对位。
               中间还夹一层 Doc 文档模型（段落/表/分隔线 + 词上的记号）：纯文本、Markdown、网页三种来源
               共用一个"排成 Word"的算法，来源按内容挑路（有网页骨架走网页、有记号走 Markdown、否则按空行分段），
               挑中的那条路会写进结果说明
  core/audio/  音频直通判据、码率反推、WAV 文件头
  core/update/ GitHub release 解析、版本号比较、选哪个 apk 下发
  core/ops/    操作目录与参数模型
  core/naming/ 输出命名与编号规则
app/    安卓端：Material 3 界面 + 各引擎（MediaCodec / BitmapFactory / PdfRenderer / PDFBox）
tools/make_ico_fixtures.py     造 ICO 夹具：DIB 那份按微软的目录格式手工拼（Pillow 只会写 PNG 内嵌），拼完先让
                             Pillow 打开确认拼对了才当参照用；像素表一起落成 .json
tools/verify_ico.py            拿 Pillow 复核 Kotlin 写的 .ico：打得开、目录里尺寸齐、每格像素与打包前相同
                             跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_ico.py`
tools/make_gif_fixtures.py  用 Pillow 生成 GIF 测试夹具
tools/make_meta_fixtures.py  用 Pillow 造带 EXIF/GPS/ICC/注释的 JPEG 与带文本块的 PNG，并把 Pillow 自己看到的
                             字段值、段清单、像素 sha256 写成 `.truth` 参照文件
tools/make_archive_fixtures.py  用 zipfile 压出 zip 夹具（普通包、带注释的包、GBK 名字的包、多语言名字包），
                             `.truth` 记的是 zipfile **读回来**看到的名字表、长度、压缩方式与 CRC；
                             同时把写侧要用的三份载荷落成 .bin，两边不各写一份定义
tools/verify_archive.py      拿 zipfile 复核 Kotlin 的 ZipWriter 产物：打得开、testzip 逐条 CRC 过、
                             条目齐、内容与打包前逐字节相同、该存原文的那条确实没被再压一遍
                             跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_archive.py`
tools/make_xml_fixtures.py     用 ElementTree 走一遍 XML，按同一套约定产出参照结构（脚本自己也体检：author 必须是数组、注释必须不在）
tools/make_data_fixtures.py    用 json / csv 标准库造数据夹具：`.truth` 记的是 Python 自己读回来再写出去的样子
                               （紧凑 JSON、DictReader 的行、csv.reader 的格子）
tools/verify_data.py           拿 Python 复核 Kotlin 的数据产物：三份 JSON 都 loads 得开且与源夹具语义相同、
                               数字写法没被改写、转义那份与 dumps 逐字节相同、CSV 往返与 csv.reader 完全一致
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_data.py`
tools/verify_xml.py            拿 ElementTree 复核 Kotlin 写出的 XML：打得开、读回来是同一棵树、特殊字符全以实体写出
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_xml.py`
tools/verify_yaml.py           拿 **PyYAML** 复核 Kotlin 的 YAML 读与写：同一份夹具两家读出同一棵树、
                               PyYAML 读我们写出去的 YAML 也得到我们那棵树（少一对引号、数字写法不对都会在这儿露出来）、
                               块标量与折叠块逐字比、1.1 与 1.2 的十三处分歧**逐条按声明出现**（哪天顺手把 yes 变成
                               true，这条会红而不是静悄悄改掉别人的配置）
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_yaml.py`
tools/reverse_yaml.py          上面那 15 条的反向验证：数字照原样写、六十进制防护摘掉、见井号就切注释、
                               块标量碰空行就收、折叠块的空行不变换行、`|+` 退化成 clip、首尾带空白的串不加引号，
                               各看对应那条会不会红；带产物新鲜度检查与还原后的重跑
                               跑法：`python tools/reverse_yaml.py`
tools/make_office_fixtures.py  手工拼 OOXML 部件再用标准库 zipfile 装成 docx/pptx 夹具，book.xlsx 交给
                               **openpyxl** 压（本机没有 Word，让真实现去压文件比手写更像真文件）；
                               每个部件生成前先过一遍 ElementTree，期望值按"一行一段"写成 .expect/.truth
tools/verify_office.py         拿 **pandoc**（自带一套完全独立的 docx/pptx 读取器）复核 Kotlin 抽出的文本：
                               段落逐字逐序相同，两处规矩不同的地方（段内制表符、文本框、幻灯片表格）
                               作为声明过的差异写死在脚本里，漂了就会红
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_office.py`
tools/verify_xlsx.py           拿 **openpyxl** 复核 Kotlin 读的 xlsx：表名与表序、每张格子逐字相同、日期从
                               序列号变成 ISO、带逗号的格子经 `csv` 标准库读回原文、稀疏行列对位
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_xlsx.py`
tools/verify_markdown.py       拿 **pandoc** 复核 Markdown 渲染：把两边 HTML 都过一遍 html.parser，
                               比"标签树 + 文字"逐事件相同（各家把语言写在 pre 还是 code、代码要不要着色、
                               表格对齐写 align 还是 style 都不参与比对，另有条判据单独看语言标记留没留）；
                               纯文本那路比词的先后；认不出的写法（四格缩进代码、脚注、内嵌 HTML）
                               作为声明过的差异逐条钉住
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_markdown.py`
tools/verify_html.py           反向复核（HTML 读进来）：**Python 标准库 html.parser** 当第二套容错词法，
                               两边归一成 S/T/E 事件流逐事件相同（标签切分一错后面全歪，且成品文字看不出来）；
                               **pandoc** 当第二套 HTML 读取器：抽出的字与先后相同（中文按字切，两家在行内
                               元素边界加不加空格是排版不是内容）、我们写的 Markdown 读回来的**字与标点**按序
                               完全相同（这条是 Markdown 转义唯一的裁判）、Markdown 的 AST 形状与 pandoc
                               直接读 HTML 相同（标题掉级、表格拍平、列表掉记号都在这里红）；外加两条纯文本
                               判据：表格列没粘在一起、源文件的换行缩进并成了一个空格
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_html.py`
tools/reverse_html.py          同一套 HTML 检查的**反向验证**：挨个把实现改坏（标题不写 `#`、表格列粘一起、
                               缩进照搬、转义关掉），确认对应那条检查真的会红 —— 只有绿的检查不算数。
                               跑完自动还原 Html.kt，有条检查没牙就退出码非 0
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/reverse_html.py`
tools/verify_html_tables.py    拿 **pandas.read_html**（另一套完整的"网页表格→二维表"）复核表格对位：
                               每个格子埋一个唯一标记，两边各数出标记落在第几行第几列再对 ——
                               跨过的格子 pandas 重复值、我们留空，所以判"我们那格必须是 pandas 那几个里最左上的"。
                               刻意**不比整格文字**（两家格内排版本来不同，比了全是噪音）；
                               两处声明过的差异钉住：整行空的我们保留、只有散 td 没写 tr 的那张 pandas 不看
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_html_tables.py`
tools/reverse_html_tables.py   上面那 7 条的反向验证：colspan / rowspan 完全不参与对位、散 td 不并成行、
                               跨格数量乱报，各看对应那条会不会红；带产物新鲜度检查与还原后的重跑
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/reverse_html_tables.py`
tools/verify_xlsx_write.py     拿 **openpyxl** 复核 Kotlin 写的 xlsx（写入侧）：包打得开、每格的值与源 CSV
                               （源数据由 Python 标准库 `csv` 独立解析）一字不差、每格的数字/文字属性与
                               Kotlin 自己声明的账一致、`007`/`1.50`/17 位长号/`=1+1`/全角数字全部还是文字、
                               表名收敛到位、`[Content_Types]` 与 rels 报的部件包里都有（openpyxl 对命名空间
                               宽松，这条得自己查）、同一批数据让 openpyxl 自己写一份再比读回来的值
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_xlsx_write.py`
tools/reverse_xlsx_write.py    写入侧那 11 条的反向验证：把前导零判据失效、命名空间串台、引用丢行号、
                               表名不收敛、说明里的计数造假各做一遍，看对应那条会不会红。
                               **带产物新鲜度检查** —— Gradle 会把测试判成无需重跑，那样读到的是上一轮的产物，
                               绿得毫无意义（这一版就是这么被自己发现的）
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/reverse_xlsx_write.py`
tools/verify_docx_write.py     拿 **pandoc** 复核 Kotlin 写的 docx（写入侧）：包自己报的部件清单与实际内容对得上、
                               pandoc 读回来的**块结构与词上的记号**与它直接读源文件相同（行内文字按字切，
                               两家合并相邻同格式文字的习惯不同，按字切才不会被排版带跑）、字与标点交给
                               pandoc 的 plain 输出两边各跑一次相比、标题带级别、列表/代码块/引用/表各自还在、
                               普通文字按空行分段且一个字不丢
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/verify_docx_write.py`
tools/reverse_docx_write.py    上面那 11 条的反向验证：样式名不用 Word 认的内置名、等宽退回直接格式、
                               列表引一个不存在的编号、正文引关联表里没有的链接、numbering 部件不进包，
                               五处各看对应那条会不会红（全部红在它该红的那条上）；带产物新鲜度检查与还原后的重跑
                               跑法：先 `./gradlew :core:test` 落盘，再 `python tools/reverse_docx_write.py`
tools/make_pdf_fixtures.py     用 **PyMuPDF** 排一份结构已知的 PDF 夹具（不用 PDFBox：安卓侧读就是 PDFBox，
                               夹具再用它写就是自证），并落 manifest.json 声明"该认出哪些块、每行多大字号、
                               哪些行是页眉"。造完自检：拿 fitz 把文件读回来，声明的每行必须真在纸上
                               （字体缺字会把中文画成控制字符，这一步就是当场逮到的）
                               跑法：`python tools/make_pdf_fixtures.py`
tools/measure_pdf_fonts.py     量真 PDF 的字号与粗细分布（每档占多少字、行平均多长、字体名带不带粗），
                               PdfDoc 那几个阈值就是这里量出来的 —— 量之前以为 `/Flags` 的 Bold 位能用，
                               量到宋体在中文文件里被标成粗体占八九成才换的按字体名认
                               跑法：`python tools/measure_pdf_fonts.py <pdf ...>`
tools/verify_pdf_docx.py       「PDF 转 Word」的三条独立复核：**pdfminer** 自己按字符量同一份文件的字号与
                               位置，与我们逐行对（差 >0.25pt 就红）；**pandoc** 读我们写的 docx，块类型 /
                               标题层级 / 列表项数 / 块首逐块与声明对；**pdftotext**（poppler）出的文字里
                               每行都还找得到。另两条比字：整份的非空白字符序列相同、拉丁文的词形相同
                               （断词拼回去没拼、该空的地方没空，这条会红而前面几条都看不见）
                               跑法：见脚本头部三步（造夹具 → :core:jar → pdfprobe 的 pdfdoc 子命令）
tools/reverse_pdf_docx.py      上面那 6 条的反向验证：字号门槛抬高、占比闸门放开、页眉不删了、折行不并段、
                               断词横线不去掉、量的时候取行内最小字号 —— 六处各红在它该红的那条上。
                               每轮都重编 :core 的 jar 并重跑探针，跑完自动还原
                               跑法：`python tools/reverse_pdf_docx.py`
tools/make_epub_fixtures.py    用标准库 zipfile 按规范拼两本书：book.epub（spine 顺序与文件名相反、href 带
                               %20 与片段、NCX 的章名写在 content 之前、线性为 no 的重复项、清单里有但没排进
                               顺序的附录、图片与 CSS、一次段内换行）与 utf16.epub（一章用 UTF-16 带 BOM）。
                               manifest 记的章序与章名由脚本自己用 ElementTree 再算一遍核对，mimetype 必须
                               是第一个条目且不压缩也当场体检 —— pandoc 与 ElementTree 都得认它才配当夹具
tools/verify_epub.py           拿 **ElementTree** 与 **pandoc**（都是能读 EPUB 的实现）复核拆书与三条出路：
                               章序与章名由 ElementTree 独立走一遍 container→OPF→spine 再比、每章的块文字
                               按文档顺序一句都不许丢、我们的 Markdown 与 pandoc 读 EPUB 得到的 AST 逐块比
                               类型/层级/列表项数/强调数、我们的 docx 再让 pandoc 读回来比同一套序列、
                               外部链接要带着地址出现在 rels 里且 TargetMode 是外部的、UTF-16 那本要能读出汉字
                               跑法：先 `python tools/make_epub_fixtures.py` 与 `./gradlew :core:test` 落盘，
                               再 `python tools/verify_epub.py`
tools/reverse_epub.py          上面那 14 条的反向验证，两段：改坏实现（章序按文件名排、href 片段不去、
                               清单里的部件一律当正文、线性为 no 也排进来、图片不计数、BOM 不按 UTF-16 认、
                               页内锚点照样写成链接、斜体与代码块丢记号、外链不标 External）与
                               改坏落盘的产物（章序调个、说明少一行、一章少一段、地址改一个字母、
                               UTF-16 那本换掉两个字）—— 每处各红在它该红的那条上，跑完自动还原
                               跑法：`python tools/reverse_epub.py`
tools/make_epub_write_fixtures.py 写"导出 EPUB"的来料与期望值：五份真文件（Markdown 两份、网页、纯文本、
                               英文 Markdown）+ manifest.json。期望值由 **Python 另一套实现**算：
                               书号按 RFC 4122 §4.3 用 hashlib 折、语言按正文数（先拆标签再去记号）、
                               章名从一级标题里读 —— 与 Kotlin 那边一行代码都不共用，两边各算各的
tools/verify_epub_write.py     判写出来的包：zipfile 看 mimetype 排第一且不压缩与部件齐全、**ElementTree**
                               判每份 XML/XHTML 良构且没有一处 DTD 指向外部地址、OPF 的清单↔文件↔spine
                               三方对得上且顺序就是章序、元数据逐项比 manifest（没有作者时不许出现 creator）、
                               NCX 与 nav 两份目录的条数章名与指向都在，最后拿 **pandoc** 独立读回来：
                               块序列与"pandoc 直接读来料"逐个相等、文字一字不差、书名语言书号读得出同一个
                               跑法：先 `./gradlew :core:test --tests '*EpubWriteFixtureTest'` 落盘，
                               再 `python tools/verify_epub_write.py`
tools/reverse_epub_write.py    上面那 50 条的反向验证：17 个反例各只破一处（mimetype 排第二/被压缩、少一份
                               目录、少闭标签、DTD 指外面、spine 指不存在的项、章序调换、正文没排进顺序、
                               清单指的文件不在包里、语言标错、书号被换、没作者却写了一个、NCX 章名不一致、
                               nav 指错、有序表被写成无序、少一个词、书名没了），断言每条都点到自己那一号；
                               另给 `--comply` 断言"照规格做的会被收"；原字节留内存跑完写回并按 sha1 核还原
                               跑法：`python tools/reverse_epub_write.py`
tools/make_tar_fixtures.py     用**标准库 tarfile** 打几份成员已知的包：ustar / GNU / PAX 三种格式各一份
                               （长名字的落法不一样）、一份带 250KB 的二进制（要跨几百个块）、一份 tar.gz、
                               一份只装单个文件的 .gz（名字写在 FNAME 上）、一份**故意改坏头块校验和**的；
                               每份都现场用 tarfile 读回来核对成员数与符号链接那条在不在，manifest 里记的
                               就是 tarfile 自己报的那份清单（"坏块之前能读出几条"也是数出来的，不是写死的）
tools/verify_tar.py            拿 **tarfile 与 gzip** 复核 Kotlin 的 tar 读与写：七类判据 9 条 ——
                               每份包成员清单逐字段相同（名字/长度/时间/类型语义/内容 sha256）、
                               tarfile 读我们写的 tar 与 tar.gz 一字不多不少（PAX 记账不许漏成可见文件）、
                               我们写的 gz 头里 FNAME 按 RFC 1952 的位置解出来对得上、
                               坏包我们与 tarfile 停在同一条上、链接与设备两边认成同一种东西
                               跑法：先 `python tools/make_tar_fixtures.py` 与 `./gradlew :core:test` 落盘，
                               再 `python tools/verify_tar.py`
tools/reverse_tar.py           上面那 9 条的反向验证，两段：改坏实现（内容不按 512 补齐、取内容少一字节、
                               长名字不出 PAX 扩展头、FNAME 名字长度上限写成 0、按 Latin-1 解中文原名、
                               校验和不对还往后读）与改坏产物（成员清单里改一个类型、写的 tar 与重新压好
                               的 tar.gz 各改一个内容字节、gunzip 结果多一字节、说明少一行、坏包多一条）
                               —— 每处各红在它该红的那条上，跑完自动还原
                               跑法：`python tools/reverse_tar.py`
tools/verify_xml_table.py      拿 **ElementTree、openpyxl 与 pandas** 复核「XML → CSV / Excel」：这一步没有
                               标准答案可抄（各家挑哪处当行、列怎么命名都不一样），所以判的是三件事 ——
                               挑的是不是"重复最多那处"（ElementTree 自己数）、列名与每格内容是否与
                               同一套公开规则在 Python 里独立摊出来的一致、产物 openpyxl 读得动且与 CSV 逐格相同；
                               pandas.read_xml 当第三意见（它把 12.50 读成 12.5、嵌套整列丢成 nan，
                               这两点正好反过来证明我们没改写法、没丢结构）
                               跑法：先 `./gradlew :core:test --tests 'com.fileforge.core.XmlTableFixtureTest'` 落盘，
                               再 `python tools/verify_xml_table.py`
tools/reverse_xml_table.py     上面那 7 条的反向验证：改坏打分（不看条数 / 越深越优先）、单值嵌套不再往下钻、
                               真嵌套压出来的那格不是合法 JSON、元素文字换列名，加上改坏 catalog.csv 与 notes.txt；
                               夹具里的 media 与 supply 是专门造的（条数与字段数各压住一条打分规则），
                               否则"浅的优先"这条改坏也没人发现
                               跑法：`python tools/reverse_xml_table.py`
tools/verify_meta_clean.py  拿 Pillow（另一套完整实现）复核清元数据的产物：打得开、像素逐点相同、
                             身份信息已清空、ICC 一个字节没改。先 `./gradlew :core:test` 落盘再跑
                             跑法：`python tools/verify_meta_clean.py`
tools/pdfprobe/  PDF 语义实验台：桌面版 PDFBox 2.0.27（和 pdfbox-android 同内核）在本机 JVM 上量页手术、压缩、
                 盖页码和水印的真实效果（把页面渲染成图数墨迹），带断言
                 textlayout 子命令把 :core 那份排版器直接接上真字体（不是在 Java 里重写一套断行），
                 排版→画 PDF→150dpi 渲染→量墨迹边界，验"字体报的宽度=纸上占的宽度"这个模型成不成立
                 pdfdoc 子命令是「PDF 转 Word」的安卓侧替身：桌面版 PDFBox 逐行量字号与位置（与 app 里
                 PdfLineScribe 同一套规矩），再调 :core 那份判断层，落 lines.tsv / shape.tsv / structure.docx
                 给 verify_pdf_docx.py 判 —— 没有安卓设备，这条链子照样能在本机跑通并被第三方复核
                 跑法：`tools/pdfprobe/run.sh verify /tmp/pdfprobe`、
                       `tools/pdfprobe/run.sh textlayout /c/Windows/Fonts/simhei.ttf /tmp/pdfprobe/textlayout.pdf`、
                       `GRADLE_USER_HOME=/d/gradle-home tools/pdfprobe/run.sh pdfdoc 夹具.pdf 输出目录`
                       （前者依赖一次性下载到 D:/toolchain/pb/desktop，见该脚本头部注释；后两者还要先 `./gradlew :core:jar`。
                        传给 bash 那侧的路径要写 /d/... 这种写法：run.sh 用冒号拼 javac 的 classpath，
                        `D:/gradle-home` 会被那个冒号切成两截）
tools/updateprobe/ 应用内更新验证台：直接拿 :core 那份真代码打线上 GitHub API，验请求头、版本比较、选包、
                 302 跳板后远端包与本机构建产物的条目 CRC（仓库改回私有时加一个只读 token 跑同一条路）
                 跑法：`bash tools/updateprobe/run.sh <只读token> [本地apk]`
```

## 验证状态

已经在 CI 之外真跑过的：

- `./gradlew :core:test` 551 个用例全绿：页码解析（多段/开放端点/倒序/越界/坏输入）、按体积分组（含"试探次数远小于页数"的复杂度约束、强非单调 measure 的 fuzz）、按份数均分不重不漏、压缩档位阶梯单调、命名补零与重名、大小解析、GIF 编解码、像素预算截断、disposal 与透明槽往返、视频码率反推、多图合成 GIF 的画布夹算（外接矩形/最长边/帧数×像素预算/兜底不兼容）、日期分组与批次血缘、跨类型操作的可用矩阵（含网页与纯文本共用一族操作、`<!doctype>`/`<div>` 认出 HTML、`<?xml` 不算 HTML、BOM 开头也算 HTML）、页码与水印排版（起始偏移、三种样式、四个位置、旋转页的视觉坐标换算、平铺格子中心、字号反算与夹取）、版本号比较与 release 解析（用线上抓下来的真回包当夹具）、图片元数据（拆段、EXIF/TIFF 读取、清理前后像素 sha256 相等）、纯文本排版（中文在字之间断、收尾标点不顶行首、开括号不留行尾、500 字长串不卡死、每页行数与基线坐标、段首缩进既排窄也整行右移、单字比栏宽还宽要报溢出而不是静悄悄）、Markdown 渲染（标题/围栏代码/引用/可嵌套与松紧列表/表格/任务列表、强调与转义、链接与引用式定义、认不出的写法照字面留并说明、没有记号的文本直接拒绝）、网页表格对位（横竖跨度占位、散 td 并成一行、嵌表另出一张、表题当名字、格内换行、跨度夹住、纯文本与 Markdown 与 CSV 三条共用同一套对位）、xlsx 写入（列号与列字母互逆、引用带行号、数字判据的通过与拒绝两侧、表名收敛、控制字符与回车、部件清单与包内容对得上、超过 Excel 行列上限要拒）、HTML 读取（原始文本元素与 void 标签、不闭合补全并计数、实体带不带分号、属性里的 `>` 与 `&` 不切标签、表单与内嵌框架退化成文字、多余收尾标签不带跑父节点、`<pre>` 里不再套反引号）、文本→Word 的来源挑路（稿子里嵌一个 `<br>` 不被判成网页、带网页骨架的不按 Markdown 排、句子里的 `#` 不算标题、写成 docx 再用自家读路读回来一个字不差且 `#` 与 `**` 不再露在正文里）、PDF 的结构还原（正文里占字三成的 12pt 拉丁文不被提成标题、一个大字符一行不是标题、整份都是黑体时"粗"不再是层级、页面中间重复的字段名不是页眉、翻页不并段、不间断空格与软连字符先归一、英文行尾的断词拼回去）、YAML 读写（嵌套与列表减号不缩进、列表项里带映射、行内 `[]` `{}`、块标量里的假列表与假注释、`<<` 合并、锚点复用、`-` 与 `+` 的 chomping、tab 缩进与 `!!` 标签直接拒、`key: &a` 后面跟一块、六十进制每一位都要加引号）、EPUB 拆书（章序按 spine 不按文件名、href 带 %20 与片段要折回包内路径、目录(NCX)给的章名优先于文档自己的 title、目录里名字写在路径之前所以只能一个 navPoint 一组地攒、线性为 no 的不排进正文、缺件与对不上的 idref 只报数不崩、带 BOM 的 UTF-16 一章读得出字、没有 container.xml 的 zip 直说不是 EPUB、没有作者时是空而不是编一个）、tar 与 gzip（内容按 512 补齐后才是下一条的起点、八进制与二进制补码两种数、prefix 与 GNU 长名与 PAX 的 path 三种长名字、头块校验和只算自己那一块、目录不当文件解、链接与设备跳过且话术与 zip 一致、写出去的长度与实际字节数对不上就报错、FEXTRA 之后才够得着 FNAME、JDK 的 gzip 库读得动我们写的且倒过来也一样）、XML 摊成表（两处条数一样、字段数不一样、深浅不一样时各按哪条打分、元素自己那句话进「文本」列、`父.子` 摊平与真嵌套压平的分工、摊出来的列名撞上时编号而不是互相盖掉、属性按名字排以免换了解析器就换列顺序）、EPUB 写出侧（mimetype 必须第一条且不压缩、一级标题既当章名也留在正文里、二级以下不另起章、没有一级标题时整本一章且用文件名当章名、标题之前的内容算"开篇"一个字不丢、XHTML 由 JVM 自带解析器判良构且 `&` 与 `<` 与引号都逃净、列表按层级套起来且开闭齐整、连着导两本书不共用列表状态、spine 的每个 idref 都在清单里、`<!DOCTYPE html>` 不许带公共标识符、书号按内容折且两次导出字节一模一样、语言按文档树数（标签名不算正文）、没有作者时是空而不是编一个）、EPUB3 的 `nav` 目录（只有 nav 的书也拿得到章名、landmarks 那一份不混进章名、两份都在时听 NCX 的、nav 不算"没排进 spine 的正文"）
- 应用内更新用 `bash tools/updateprobe/run.sh` 打了线上真接口，**全程不带任何凭据**，14 条断言全过：匿名就能读到发布页（所以应用内更新零配置）、没有 User-Agent 会 403 所以头必须带、选包选到 `*-release.apk`（7.6MB）而不是 debug（25MB）、下载端点是 api 域名、**手动跟 302 到 release-assets 域名后，远端包与本机构建产物的 `classes.dex` / `AndroidManifest.xml` / `resources.arsc` 条目 CRC 三项全等**。这里踩过一次坑：最早那条判据是"前 4KB 逐字节相同"，但重新打包会换 zip 时间戳导致体积相同而字节不同 —— 那是判据写错，不是产物错，所以改成比条目 CRC
- GIF 解码器逐像素比对 **Pillow 生成的 4 份夹具**（含交错帧、多帧、40x40x200 色），真值也来自 Pillow
- GIF 编码器输出经 **Pillow 与 omggif 两个第三方解码器**验证：mincode=2 的 6000 像素码流全部还原一致；把 4 份夹具重编码后交给 Pillow 读回，3 份 0 差异，`rich`（跨帧 792 色压到 256 色）最大色差 40，属于调色板收敛的预期损失
- LZW 码宽时序、交错行序、KwKwK 特例、字典满 4096 重置、MedianCut 均经独立推导 + Pillow/omggif 实测确认
- PDF 语义用 `tools/pdfprobe/run.sh verify` 在 JVM 上实测，45 条断言全过：继承资源（`/Resources` 挂在 `/Pages` 上）的页经 `importPage` 会丢字体——整页墨迹 10.81%→10.50%，少的正好是字的部分；补上 `importedPage.setResources(page.getResources())` 后逐页墨迹与原文件一致（差 <0.05pp）。结构体检（每页 `/Parent` 落在本文档页树、`/Count` 一致、MediaBox 为正）在复制/旋转/压缩产物上都过；复制两页的间接对象数 13→9，说明没把源文档对象整串拖进结果。4 页图片型 PDF 压缩 3.47MB→452KB（13.0%），页数不变、文字层还在、逐页墨迹差 <0.01pp；旋转后页面宽高互换且内容仍读得出来。带软掩膜的图专门造了夹具：修之前掩膜会被重编成 DeviceRGB 的 JPEG（规范不允许，安卓侧渲染会坏），修之后断言「掩膜还是灰度、尺寸还跟图一致」才过
- 盖页码和水印是**渲染出来量的**，不是看代码猜的：造一份"只有左上角有标记"的三页文件（第 2 页故意带 `/Rotate 90`），盖章前底部墨迹为 0，盖完三页底部墨迹都 >0.15% 且左上角那块一分没动，文字层抽得出 `1 / 3`…；**旋转页的页码也必须落在你看到的底部** —— 这条一开始是失败的：我按推导把 90° 的反旋转写成 -90°，实测就是打在页面外，改正后才过。中文字体走子集内嵌：9.5MB 的字体，盖"内部资料"四个字 + 平铺 2x2 后文件只长 3.7KB；挑字体的判据也单独验了 —— 拿 arial 去 encode 中文必须失败，否则"能不能显示"就没法自动判断

- **压缩视频在真机上跑通并量过了**（此前只在编译层验过，这条是第一次有设备证据）：一段 2.2GB / 1920x1080 / 50fps / 6分14秒 的原始片段压到 117MB（5.2%），产物用 ffprobe 读出 `h264 1920x1080@50`、**18720 帧、时长 374.4 秒**，与源片段等长。同一台机器上顺手抓出两个必现缺陷：① 视频轨只查不选（`selectFirst` 不 `selectTrack`），于是解码器一帧都拿不到，写出 3.2KB 空 mp4 还因为"字节数 > 0"报成功；② 无压缩 PCM 音轨（MP4 里 fourcc 是 `twos`/`lpcm`/`ipcm`，MediaExtractor 报成 `audio/raw`）被 MediaMuxer 拒绝，`addTrack` 直接抛 "Failed to add the track to the muxer"，整件事失败。现在改成：音轨按封装能力预判 + `addTrack` 兜底失败就只压画面并写明原因，写盘后要求**真的编出过帧**否则报错不产出空文件

- `:app:assembleDebug` 出包成功，`aapt dump badging` 确认包名/图标/PDFBox 字体资源已进包
- 文本编码与字幕这一族**完全不含安卓 API**，判断全在 `:core`，新增 29 条单测（累计 161 条全绿）：UTF-8 严格语法逐字节判（overlong、截断、代理区、超范围全要拒）、指定源编码读不干净就拒绝产出、丢字按码点而不是按 char 数（emoji 是代理对）、四种字幕格式各自的时间轴写法、ASS 的列顺序由文件自己的 `Format:` 行决定、源格式识别里签名优先于扩展名。
  夹具字节全部由 **Python 标准库 codecs** 生成（独立实现），不是照记忆手编。写这套判据时被测试当场逮到一个 bug：编码器给的 `ByteBuffer` 按最大长度分配，用 `array()` 会把尾部零字节一起带出去 —— 连 UTF-8 自转都过不了。

- PDF 加/解密的四条行为是 `tools/pdfprobe/run.sh security` **量出来的**，不是猜的：不设密码直开必被拒（`InvalidPasswordException`）；打开密码和所有者密码都能开；**只用打开密码就能解除保护**（所以界面只强制填一个）；所有者密码留空时库会随机造一个（所以实现里改成照抄打开密码）。另外量到「不允许复制」这类权限位**不拦程序抽取**，界面对此照实说明，没吹成加密锁。

- 音频这一类的**判断**都在 `:core` 里跑过 24 条单测（`AudioPlanTest` + `WavHeaderTest` + `FileKindSniffTest`）：魔数识别（含"带 ID3 标签的 mp3"、"ADTS 与 MP3 靠 layer 两位分开"、"同是 RIFF 靠第二标记分 WAV/WebP"、".m4a 不再算视频"）、直通只在 AAC→m4a 这一条上成立、挑轨返回下标、安卓解不开的格式提前判掉、按目标体积反推码率并夹在 64~320 kbps、成功判据是"搬过样本"而不是"文件有字节"、以及操作矩阵（音频只拿到音频操作，混选时不给半可用按钮）。
  WAV 文件头是这套东西里唯一不碰安卓 API 的产物，所以拿 **Python 标准库 `wave` 写出的真文件头做了逐字节比对**（44 字节全等）——只跟自己一致不够，得跟别人能读一致。

- ICO 这一族也由 **Pillow** 复核，而且它逮到两处真错：一是**读 DIB 的行号基准** —— 自下而上的行号要以像素区的末尾算，
  拿 `body.size` 当底会把 AND 掩码那排 0 当像素读进来；二是**夹具自己的通道溢出**（`y*9` 到 261 会溢进红通道，
  于是"期望值"本身比真实像素多 1）—— 只有两个脚本各算一遍同一批像素才对不出来。另外 Pillow 一份 .ico 只给一个画面
  （它挑最大的那张，seek 会抛 EOFError），所以逐尺寸的比对走"每个尺寸单独写一份"，不假装能翻帧。
- 压缩包这一族的参照物是 **Python 标准库 zipfile**：读侧四份夹具由 zipfile 压出来，`.truth` 里记的是 zipfile **读回来**看到的名字表、长度、压缩方式与 CRC（不是我以为写进去的）；写侧产物由 `tools/verify_archive.py` 交给 zipfile 打开复核，11 条判据全过 —— 打得开、`testzip()` 逐条 CRC 全过、条目名与 CRC 与参照包一致、每条内容与打包前逐字节相同、名字带 .zip 那条按规则直接存原文。
  这套判据当场逮到两个真错：一是**字节序** —— 一个 `u16` 从图片那边抄成了大端，名字长度读成 1280，中央目录一条都走不到，表现是"这包是空的"而不是报错；二是**中央目录头少写 2 字节的「version made by」**，导致后面每个字段整体错开一位 —— 那是"两种头共用一段前缀"的写法造成的，改成两张表各自照规范抄。
- XML 这一族的参照物是 **Python 的 ElementTree**：`.truth` 是它自己解析同一份字节、按本项目约定走出来的结构（不是我以为的映射结果），Kotlin 的解析结果与它**逐键相等**；写侧再由 `tools/verify_xml.py` 交给 ElementTree 打开复核，7 条全过。
  这条复核逮到一个真缺陷：混合内容（元素既有自己的文字又有孩子）我原先写完孩子再补文字，那段文字就变成了上一个孩子的 tail，
  再解析回来元素的正文整个没了 —— 那不是「顺序不保留」，是丢数据。改成文字先写，并加了一条往返断言钉住。
- YAML 这一族的裁判是 **PyYAML**（`python tools/verify_yaml.py`，16 条全过）：三份夹具（形状 / 两家一致的标量类型 /
  故意不一致的那批）由我们自己写死在测试资源里，Kotlin 读出来的树要与 PyYAML 逐键逐值相同，**我们写出去的 YAML
  再交给 PyYAML 读也必须是我们那棵树**。这条一晚上就逮到两处真错：`1:2:3` 没加引号（PyYAML 按六十进制读成 3723）、
  `1.5e3` 照原样写出去（1.1 的浮点式子要求指数带正负号，别人会读成字符串）—— 两处都是"我们自测全绿、别人读回去值就变"
  - 类型按 **YAML 1.2 的核心模式**判，与 PyYAML 的 1.1 有十三处分歧（`yes`/`no`/`on`/`off`、`1:30`、`012`、日期、
    `.inf`…），这批逐条钉在 `divergent.yaml` 与判据脚本里：我们保持文字，PyYAML 变成别的类型，两边各按声明出现。
    选 1.2 的理由是"不改别人的数据"—— 一份 `version: 1:30` 被读成 90 是转换工具最坏的一类输出
  - 反向验证七处破坏：六处红在外部判据（PyYAML 那几条），一处（块标量碰空行就收）先红在落盘测试 —— 少一层算多层保险
- EPUB 这一族的裁判是两个能读 EPUB 的独立实现：**ElementTree**（真 XML 解析器，按 XML 声明与 BOM 认编码）与
  **pandoc**（自带一套 EPUB 读取器）。`python tools/verify_epub.py` 14 条全过：章序与章名由 ElementTree 从
  container → OPF → spine 独立再走一遍（href 解百分号、去片段、按 OPF 所在目录相对）跟我们落的清单逐行对，
  每章的块文字按文档顺序一句都不许少，我们的 Markdown 与 pandoc 读 EPUB 得到的 AST 逐块比类型/层级/列表项数/
  强调数，我们的 docx 再让 pandoc 读回来比同一套序列（外链还要在 rels 里带着地址且 TargetMode 是外部的）
  - 造夹具时被这两个裁判各教了一次：spine 里一个对不上的 idref 就让 pandoc 整本报 `parseSpine` 退出，
    一章 UTF-16 的正文让它报 `Invalid UTF-8 stream` 退出 —— 于是 book.epub 保持合规（坏件的宽容行为由单元测试管），
    UTF-16 单开一本交给 ElementTree 判（它按声明与 BOM 解，认得）。EPUB 3.3 写明正文部件要用 UTF-8，
    但 EPUB 2 时代的书里确实有 UTF-16：照 BOM 认是白捡一章，不认是一章没了
  - 反向验证 16 处（`python tools/reverse_epub.py`）：改坏实现 11 处 —— 七处先红在单元测试（顺序、片段、
    清单全当正文、线性、图片计数、BOM、xhtml 类型），四处红在外部判据（页内锚点不交代、斜体丢记号、
    代码块丢围栏、外链不标 External）；再改坏**落盘的产物** 5 处（章序调个、说明少一行、一章少一段、
    地址改一个字母、UTF-16 那本换两个字）全红在外部判据 —— 第二段专门证明那 14 条不是恒绿
  - 写的那一侧另有一套（`verify_epub_write.py` 50 条、`reverse_epub_write.py` 17 个反例全有牙）：
    裁判是 zipfile + ElementTree + pandoc **读回我们自己写的包**，再与 pandoc 直接读同一份来料的 AST 逐块比
    （块序列相等、文字一字不差、书名语言书号读得出同一个值）；期望值那份 manifest 由 Python 另一套实现算
    （hashlib 折书号、按正文数语言、从一级标题读章名），与 Kotlin 不共用一行代码
  - 这一族被裁判与夹具各教了三课，都记在代码注释里：① 带公共标识符的 `<!DOCTYPE>` 会让 JVM 的 XML 解析器
    **联网去取 DTD**（离线当场报错），而我们自己读 XML 那一侧正因为同一个理由拒收带 DTD 的文件 ——
    写出侧改成 `<!DOCTYPE html>`，与 pandoc 出的书对齐；② 一级标题只当章名、不留在正文里，读回来
    （EPUB → Markdown / Word）就少一整级标题，pandoc 与 Calibre 的章文件里都有那个 `<h1>`；
    ③ 语言按原文数会把中文网页标成 `en` —— 源码里 `html`、`charset`、`div` 这些标签名的拉丁字母比正文汉字还多
    （这条是生成期望值的脚本先算出 `en`、与我们的 `zh` 冲突才发现的），改成按文档树数
  - 一处退化是明写的：页内锚点（`<a href="#这里">`）在整本摊平成一篇之后没有落点，只留文字并说明几处；
    pandoc 会把它改写成 `#ch2.xhtml_这里` 留在产物里，点开没反应
- tar 这一族的裁判是 **Python 的 tarfile 与 gzip**（`python tools/verify_tar.py`，9 条全过），而且**两个方向都验**：
  夹具由 tarfile 打（ustar / GNU / PAX 三种格式各一份，长名字的落法各不相同），我们读出来要和 tarfile 自己报的
  成员清单逐字段相同；反过来**我们写的 tar 与 tar.gz 交给 tarfile 读**，成员一字不多一字不少 —— 中文长名走 PAX
  扩展头这件事，只有别人读得出来才算成了
  - 头块坏了怎么办是量出来的：把第 3 个头块的校验和改一位，tarfile 数到 2 条就停，我们也在同一条停下并报"校验和不对"
    —— 那个"停在第几条"是脚本里现数出来的期望值，不是我写死的
  - 两种容器共用同一套解压判断（`PackagedEntry` 一个接口）：符号链接在 zip 与 tar 里给出的理由**字符串相同**，
    这条单独钉了一行测试 —— 因为界面是同一句说明，两处各写一套迟早分叉
  - gzip 的原始文件名（FNAME）是"单个 .gz 该解成什么名字"的唯一依据，而 JDK 的 GZIPInputStream 读了头也不给这个字段，
    所以头部那 10 字节加可选字段自己解；写侧同理（库写出的头恒为"无名字、时间 0"）。JDK 的库与我们互为反方：
    我们写的它要读得动，它写的我们读得动
  - 反向验证 13 处（`python tools/reverse_tar.py`）：改坏实现 6 处（四处先红在单元测试、两处红在外部判据）
    + 改坏产物 7 处全红在外部判据。其中"改坏 tar.gz 里那份 tar 的一个字节再重新压好"是专门造的：
    直接动 gz 的原始字节会让 Python 报错退出，那样测不出判据指向哪一条
- XML → 表这一步**没有标准答案可抄**（各家挑哪处元素当行、列怎么命名都不一样），所以判据不是"跟别人一样"，
  而是把我们自己宣布的规则拿到 Python 里独立走一遍：`tools/verify_xml_table.py` 7 条 —— ElementTree 自己数
  "重复最多那处"、按同规则摊出列与每格内容、openpyxl 读我们的 xlsx 与 CSV 逐格相同、pandas.read_xml 当第三意见
  - pandas 那两个行为正好反证了我们的取舍：它把 `12.50` 读成 `12.5`（写法丢了），
    还把 `<tags><tag>…</tag></tags>` 这类嵌套**整列丢成 nan**（结构没了且不吭声）。
    我们改成"压成一格 JSON 文本 + 点名是哪一列"，判据里两条都钉着（它改写法的地方我们必须没改）
  - 夹具里的 `<media>` 与 `<supply>` 是专门为"打分被改坏要能红"造的：`media.disc` 与 `book` 条数、字段数全一样
    只差一层深度，`supply.item` 字段更多但条数更少 —— 缺一条，"条数优先"或"浅的优先"被改坏时外部看不出来
- 数据格式这一族的参照物是 **Python 的 json 与 csv**，而且**只在该信它的地方信**：Python 的 `dumps` 会把 `1.50` 改写成 `1.5`、把 `1e20` 写成 `1e+20`，"不改写数字"恰恰是这里的诉求，所以数字用文本断言钉、转义与 CSV 单元格才拿 Python 当逐字节参照。`tools/verify_data.py` 21 条判据全过。
  这套区分当场纠了一个错判：一开始把"开类型识别后 `1.50` 还是不是字符串"当成了实现 bug，实际是 —— `1.50` 按 JSON 语法就是数字，认了类型之后**写法**这层信息在 JSON 里根本没有容身之处（渲染文本仍写 1.50，任何库读回去都是 1.5）。现在这条作为声明的丢法写在界面上。
- 元数据这一族是**两头都拿 Pillow 卡的**：夹具由 Pillow 写（EXIF 含制造商/机型/方向/GPS 子 IFD/Exif 子 IFD，外加 ICC 与注释段），`.truth` 里记的是 Pillow 读回来的段清单、字段值和像素 sha256，不是照我家解析器反推的；清完的产物再交给 `tools/verify_meta_clean.py` 用 Pillow 复核，14 条判据全过 —— 打得开、**像素逐点相同**、EXIF 与文本块清空、ICC 一字节未改、注释段没了。清理的实现只记原始字节区间并整段照抄，既不重算 JPEG 的长度字段也不重算 PNG 的 CRC，所以"没重编码"是结构上的事实而不是承诺
- 文本→PDF 的宽度模型由 `tools/pdfprobe/run.sh textlayout` **量**出来的：探针不重写第二套断行规则，而是把 `:core` 那份排版器（编译 `core.jar` 进 classpath）接上真字体 `simhei.ttf`，排 240 行 → 画 6 页 → 150dpi 渲染 → 数墨迹边界。29 条断言全过：每页墨迹左 56.2pt / 右 528.5pt（栏尾 539pt）、上下都落在页边距内，文字层抽回来 10200 字与源文本一字不差。**反向也验了**：把宽度模型故意改成 0.8 倍重跑，第 1 页墨迹右端立刻冲到 594.2pt（越过栏尾、快到纸边）并判失败 —— 说明这条判据能红，不是恒绿。同时这条链子当场逮到两处真错：一是**段首缩进只排窄不挪位置**（用户看到的变成"右边齐、左边缺一块"，中文习惯要的整行右移根本没发生），二是**逐字符量宽与整行量宽各记一次缺字**，缺字数会被报成两倍
- OOXML 抽取的参照物是 **pandoc**（它自带一套完全独立的 docx / pptx 读取器，不是我家第二实现）：
  夹具由标准库 zipfile 装成真的 .docx/.pptx，pandoc 与 Kotlin 抽出的段落**逐字逐序相同**，14 条判据全过；
  反向把产物里删掉一段重跑，两条判据立刻变红 —— 说明这条对照能红，不是恒绿。
  三处规矩不同的地方钉成"声明过的差异"：段内 `w:tab` Kotlin 留成制表符而 pandoc 的纯文本写法把它并掉、
  文本框里的字 Kotlin 收（pandoc 走 `mc:Choice` 那条备用写法就不收）、幻灯片里的表 Kotlin 收（pandoc 的 pptx 读取器不认 `a:tbl`）。
  这条第三方对照也逮到两处错：一处是**夹具自己**（超链接被挂成 body 的直接孩子，那在 OOXML 里不合法，pandoc 直接当没有这段）；
  另一处是实现（嵌套段落被收了两次尾，文本框里那段自己换一次行、外面那段又补一次，于是每个文本框前面凭空多一个空行）
- xlsx 这一族换了 **openpyxl** 当参照物：夹具由它压（真实现压出来的文件比手写的更像会碰到的文件），
  `.truth` 记的是它**读回来**看到的格子，Kotlin 的产物由 `tools/verify_xlsx.py` 用 `csv` 标准库读回来逐格比对，
  10 条判据全过（反向把一个日期格改回序列号 `45047`，两条判据立刻变红）。
  这条链子当场纠了两处：一是**Excel 1900 纪元的算法** —— 原先按"1899-12-31 加 (序号-1) 天"推，
  算出来第 1 天落在 1899-12-31，与 openpyxl 存在文件里的序号对不上（45047 必须是 2023-05-01），改成"61 以后按 1899-12-30 起算、
  1~59 单独算、60 那格照 Excel 的写法给出不存在的 1900-02-29"；二是**格式码判日期**：跳方括号段时拿同一种字符去找结尾
  （`[红色]yyyy` 里再找 `[`），结果带颜色段的日期格式被判成非日期，改成找配对的 `]`
- Markdown 渲染的对照物还是 **pandoc**：`common.md` 这份只放两家规矩一致的语法，渲染出的 HTML 过一遍
  `html.parser` 后**标签树与文字逐事件相同（160/160）**，纯文本按词的先后相同（54 个词）；
  `prose.md` 专门放规矩不同的写法（四格缩进代码、脚注、内嵌 HTML），逐条断言"照字面留下 + 交代清楚"。
  判据能红：把产物里一个嵌套列表项的字抹掉，结构与 pandoc 的比对立刻报错。
  这条链子当场逮到自己两处错：**`Regex.matches` 要整串匹配** —— 只锚行首的 `HTML_START`/`HEADING` 用它，
  结果是 `<div>` 块整个不被认（当普通段落还转义了）、`# 标题` 判成"没有 Markdown 记号"，两处都不报错只静默失效，
  改成 `containsMatchIn`；另一处是自动链接 `<https://x>` 被 HTML 块规则抢走，
  因为 CommonMark 的 HTML 块要求"整行只有这一个标签"，收紧正则后才对
- HTML 读进来这一路有**两个**对照物，各管一段：`python tools/verify_html.py` 共 13 条判据全过
  - **Python 标准库 `html.parser`** 管词法：两份夹具（结构正常的页 122 个事件、标签不闭合的脏页 50 个事件）
    两边归一成 S/T/E 事件流**逐事件相同**。这一步必须单独比：标签切分一旦不同，后面结构全歪，
    而"少收一个 `</p>` 把两段并成一段"在成品文字上完全看不出来
  - **pandoc** 管语义：抽出的字与先后相同（120 个，中文按字切 —— 两家在行内元素边界加不加空格是排版选择不是内容差异）、
    我们写的 Markdown 交给 pandoc 读回来**字与标点按序完全相同**（272 个字，这条是 Markdown 转义唯一的裁判：
    `*星号*` 没转义就被读成着重标记，两个星号静悄悄消失）、Markdown 的 AST 形状与 pandoc 直接读同一份 HTML
    相同（34 个节点 —— 标题掉级成段落、表格被拍平、列表掉记号都在这里红）
  - 判据能红：标题的 `#` 不写、表格列粘在一起、源文件缩进照搬、转义整个关掉，四处分别让对应那条变红
    （其中"转义关掉"这一条是补出来的 —— 原先的检查对它在绿，那是检查没牙）
  - 比对时两边都要按 HTML 规范的"空白"归一：Python 的 `split()` 把 `&nbsp;` 解出来的那个不换行空格（U+00A0）
    也当空白吃掉，Java 的 `trim`/`\s` 不吞 —— 两边统一成"只并 ASCII 空白"之后才对得上
- xlsx 写入侧的对照物是 **openpyxl**（`python tools/verify_xlsx_write.py`，11 条全过）：包打得开、
  每格的值与源 CSV 一字不差（源由 Python 标准库 `csv` 独立解析，等于三道手互认）、每格的数字/文字属性与
  Kotlin **自己声明的那本账**一致、表名按 Excel 的规矩收敛、`[Content_Types]` 与 rels 报的部件包里都有、
  同一批数据交给 openpyxl 自己写一份再比读回来的值。反向验证五处破坏，四处红在预期那条上（前导零判据失效 →
  三条同时红、命名空间串台 → 清单那条、引用丢行号 → "打得开"、说明里的计数造假 → 账实那条），
  表名不收敛那处先红在落盘测试（少一层算多层保险）
  - 这条链子当场逮到两处自己写出来的错：**格子引用漏了行号**（`r="A"` —— 自家读路按出现顺序兜底猜对了，
    单测全绿，openpyxl 直接拒绝整包）；**`[Content_Types].xml` 用了 relationships 的命名空间**（同样自家读路
    不看命名空间，测不出来）
  - 反向脚本还逮到一种**假绿**：Gradle 会把测试判成无需重跑，于是判据读到的是上一轮的产物。现在每轮比对
    产物的修改时间，没重写就直接判定"这轮没测到"而不是报绿
- docx 写入侧的对照物也是 **pandoc**（`python tools/verify_docx_write.py`，11 条全过）：它自带一套完全独立的
  WordprocessingML 读取器，把我们写的包读回来的**块结构与词上的记号**，与它直接读同一份源文件的结果逐行相同 ——
  这一条挡的是"样式名没用在 Word 认的内置名上""等宽退回直接格式""列表引了不存在的编号""表格被并成一段"这类
  在自家读路上量不出问题的错（自家读那一路宽容，包结构写错照样读得出，xlsx 那轮就是这么漏的）；
  包自己报的部件清单（`[Content_Types]` 与 rels）与实际内容也逐条对，正文里引了关联表没有的 `rId` 会当场红。
  反向验证五处破坏，五处都红在它该红的那条上：样式名换成自造名 → 块结构那条、等宽退回直接格式 → 块结构那条、
  编号引到不存在的 numId → "还是列表"那条、正文引关联表外的链接 → 部件清单那条、numbering 不进包 → 部件清单那条
- 「PDF 转 Word」这条链子有**三个互不相干的**裁判（`python tools/verify_pdf_docx.py`，6 条全过）：
  夹具由 **PyMuPDF** 排（不是 PDFBox —— 安卓侧读就是 PDFBox，用自家写的东西造夹具只能证明自洽），
  **pdfminer** 独立按字符量同一份文件的字号与位置，与我们逐行对（31 行全对上，差 >0.25pt 就红）；
  **pandoc** 读我们写出的 docx，块类型、标题层级、列表项数与块首逐块与声明对（17 块全对）；
  **pdftotext**（poppler）再数一遍字。另外两条比的是"排版噪音"：整份非空白字符序列相同、
  拉丁文的词形相同（断词没拼回去、该空的地方没空，前五条都比不出来，这条能）
  - 反向验证六处破坏，六处都红在它该红的那条上：字号门槛抬高 → 块序列、占比闸门放开 → 块序列、
    页眉不删 → 字不丢、折行不并段 → 块序列、断词横线不去掉 → 拉丁词形、量取值取行内最小字号 → pdfminer 那条
  - 阈值是 `python tools/measure_pdf_fonts.py` 量真文件量的，量的过程改了两个原判据不成立的判断：
    **`/Flags` 的 Bold 位在中文文件里不可信**（宋体被标成粗体占到八九成，照它判会把每行短的都排成标题）、
    **正文里夹的拉丁文常比中文大 0.5pt 且能占三成字**（光看"比正文大"会把它们全提成标题）
  - 真文件上过一遍才补的规矩：十页表单里每页重印的"姓名 / 联系电话"是正文不是页眉（原来把 61 行里的 46 行
    当页眉删了），所以页眉还得落在纸的上/下那条带子里；英文期刊那份（两栏）确实会被"从上到下扫"交叉读，
    现在会说出来（"N 页看着像多栏"）而不是默默产出一份顺不顺得看的文档
- 网页表格对位的裁判是 **pandas.read_html**（`python tools/verify_html_tables.py`，7 条全过）：
  每格一个唯一标记，两边各数出它落在第几行第几列再对；跨过的格子判"我们那格必须是 pandas 那几个位置里最左上的"。
  反向验证里 `colspan` 与 `rowspan` 各自完全不参与对位都能红。
  这条链子当场逮到自己一处错：**容器判据只看一层** —— `<body>` 里除表外没有别的块级元素时（整页只有一张表，
  邮件里全是这种），整张表被当成一坨行内文字吞掉，格子间的分隔符全没了；改成递归看底下有没有块级元素才对。
  另两处声明过的差异钉在判据里：整行空的我们保留、只有散 `<td>` 没写 `<tr>` 的那张 pandas 直接不看（我们认）

还没验证的（需要真机跑一次）：

- 加过密码的 PDF 在**第三方阅读器**里的实际表现：WPS / Adobe 会不会照限制弹提示、去掉密码后的副本在文件管理器和阅读器里是不是真的不要密码（本机的 PDF 渲染通路是 `PdfRenderer`，它对权限位本来就无所谓）
- **音频整条链路**：安卓的解码器/编码器支持面只能真机问出来。要验的是 MP3/FLAC/OGG → M4A 出来的声音对不对（变速、缺尾、只有一边声道都是这层的典型症状）、WAV 成品在别的播放器里能不能正常放、以及 AAC 直通那条是不是真无损
- **数据格式这一族在真机上的一圈**：手机上存的 json / csv（微信导出的账单、备忘录导出的表）编码五花八门，自动认编码 + 自动猜分隔符能不能认对、认错时提示是否够用；产物发给电脑端的 Excel 与 VS Code 读出来是否一致
- **图标在真机上的一圈**：做出来的 .ico 在文件管理器/启动器/别的 App 里能不能被当图标认出来；拆 .ico 时 PNG 内嵌与 DIB 两种都碰到过吗（微信收藏里导出的图标两种都有）
- **Markdown 在真机上的一圈**：手机上存的 .md 多是备忘录/Typora/obsidian 导出的，带各自的前置属性与扩展语法；
  转出的 .html 用系统浏览器打开能不能正常显示中文（charset 那一条的实测）、以及在电脑端 VS Code / 微信里打开是否一致
- **真 Word / Excel 文件的一圈**：判据用的夹具是脚本拼出来的（docx/pptx 由标准库 zipfile 装、xlsx 由 openpyxl 压），第三方读取器（pandoc、openpyxl）已经逐字对过，但真机上的文件更野 —— 微信/网盘下载的 docx 里那些 `mc:AlternateContent` 双写法、带图表与透视表的 xlsx、几千行的表、Mac 那边来的 1904 纪元文件，都得拿真文件再走一遍；大 pptx（几百 MB 带视频）在手机上按部件区间读会不会吃紧也要真机量
- **真网页的一圈**：夹具是手写的两份（结构正常的页 + 标签不闭合的脏页），与 html.parser / pandoc 逐事件对过；真页面上等着的是另一批东西 —— 页面里几十个 `<div>` 套八层的博客正文、正文与导航评论混在一起（这里不辨"哪块是正文"，整页照抽）、GBK 老页、`<frameset>`、以及浏览器另存为时那种带 `<!--StartFragment-->` 的剪贴板页。另存为 .html 与复制网页→粘贴成 .txt 两种来源都要在真机上各走一遍
- **写出来的 xlsx 在真 Excel / WPS / Numbers 里的一圈**：openpyxl 判"结构合法、值与类型对得上"，但桌面上的 Excel 会更挑 —— 要验的是打开时会不会弹"发现不可读取的内容"、数字列右对齐与可求和是否符合预期、表名里被换掉的那张在 Excel 里显示成什么、以及**手机自己转出的 xlsx 再选回来能不能被自家的读路读成同一张表**（这条是闭环，理论上一格不差，真文件里那些带审计标记的另存版本要试过才知道）
- **写出来的 docx 在真 Word / WPS 里的一圈**：pandoc 判的是"结构与记号对得上"，桌面上的 Word 会更挑 ——
  要验的是打开会不会弹"发现不可读取的内容"（末尾的 `w:sectPr` 与内置样式名就是为它准备的）、列表在 Word 里
  按 Tab 升降级会不会照层走（`ilvl` 是从缩进层数来的）、`Source Code` 那样式在别人机器上没有 Consolas 时退成什么、
  链接在 Word 里点下去真不跳、另存为 PDF 后版式还在不在，以及**手机转出的 docx 再选回来能不能被自家读路读出同样的正文**
- **安卓侧那条量字号的路径没跑过真机**：`PdfLineScribe` 是 Kotlin 版，判据走的桌面版同内核（`tools/pdfprobe` 的 `pdfdoc` 子命令），两边一份规矩。要真机确认的是 `TextPosition.getFontSizeInPt / getXDirAdj / getPageHeight` 在 pdfbox-android 上给的数与桌面一致（安卓版换过图形层，字号这条链路依赖字体矩阵换算）、几百页的大 PDF 逐页量会不会吃紧、以及手机上存的 PDF（微信/网盘下来的）里那些带 `/Rotate` 的页量出来的位置对不对
- **YAML 在真机上的一圈**：判据走的是 PyYAML 与自家两份实现，真配置还有这一版没认的写法（`!!binary`、复杂键、跨行的流式集合、多份文档、`?` / `:` 显式键）—— 手机备忘录/网盘里导出的 .yml 会不会撞上它们、撞上了提示够不够清楚；以及 GitLab CI 那种满页锚点与合并的文件转出去还能不能被编辑器接受
- **tar / tar.gz 在真机上的一圈**：判据全程在内存里喂字节，真机走的是文件随机读与那条 64 MB / 512 MB 的上限 —— 一份几百 MB 的 tar.gz 在手机上解压会不会先撞哪条；`.tar` 没有魔数，只在前 512 字节里认 `ustar` 加校验和，**手机上从网盘/微信另存出来的包**（可能带口令、可能被截断、可能是 GNU 与 PAX 混写）认不认得出来；单个 .gz 解出来的名字用 FNAME 时，中文原名在 MediaStore 那一层能不能落成同一个名字
- **EPUB 在真机上的一圈**：判据全程是在内存里喂字节跑的，真机走的是 SAF 随机读与那条 32 MB / 512 MB 的上限 —— 一本几百 MB、上千张图的书能不能跑完、先撞哪条；夹具是自己按规范拼的四章书，真实出版链子（各家转换工具、Calibre）做的书里还有 EPUB3 的 `nav` 文档、`properties="cover"`、SVG 章节、三层以上的目录，章名与顺序对不对；带 DRM 的包要给一句能看懂的拒绝而不是一份空正文
- **导出来的书在真阅读器里的一圈**：`nav` + NCX 两份目录、无封面、无样式表这三件事在 Calibre / Apple 图书 / 国产阅读器里各自怎么显示（有没有拿 `nav` 当第一页、目录面板能不能跳、语言标错会不会怪），以及从 SAF 挑一份 Markdown 走到"电子书"按钮的那条 UI 路径 —— 判据只证明别的实现读得动这些字节，不证明有人打开过它
- **清除元数据在真机上的完整一圈**：详情页能不能列出自己相册里那些手机照片的字段（夹具是 Pillow 造的，真机相机的 EXIF 写法更野）、清完的图在系统相册和微信里发出去是否真读不到位置、带方向的照片按提示"先转格式再清"之后画面方向对不对

- 真机上 SAF 选文件、导出到文件夹的完整往返
- 分享：社交 App 能不能收到（FileProvider 的 authority 是 `包名.fileprovider`，只暴露 workspace 和 staging 两个目录）
- 「按日期」分组的观感（今天/昨天/本周/更早，周一为一周起点）
- 转换后自动放出去的那份：详情页「位置」显示的是 `/storage/emulated/0/文件工坊/...` 还是退回了 `Download/文件工坊/...`，文件管理器和社交 App 的文件选择器能不能直接选到
- webm→GIF 换成顺序解码后到底动不动（结果说明会写「N 帧 …」；只解出一张画面时直接报错，不再假装成功）
- 新增的三类互转：GIF 逐帧拆图（帧多的 GIF 会出几十个文件，够不够用）、多张图片合成 GIF（尺寸不一时是否真按外接矩形居中、白底能不能接受）、视频按秒数抽帧（横拍竖存有没有转正、指定秒数取到的是不是想要那一帧）
- 「检查更新」挪到筛选条上的文字按钮后好不好找；详情页「打开」能不能正常拉起阅读器/播放器（Android 11+ 的包可见性靠 manifest 里的 queries，装了 WPS/播放器的机器才算验到）；「所有文件访问权限」开完之后成品是否真落到顶层 文件工坊 目录
- 应用内更新在真机上整条跑一遍：手机上能不能连 `api.github.com` 和 302 之后的 `release-assets.githubusercontent.com`（两个域名都得通）、定制系统的"安装未知应用"引导能不能跳过去、装完工作台列表和 文件工坊 目录里的成品在不在
- MediaCodec 的**其余**支持面：H.264 转码已在真机跑通，WebM/VP8 编码器和 HEIC 解码还没在这台机上验过
- 页码和水印在真机上的观感：位置对不对、盖在扫描页上会不会糊掉字、你这台系统的中文字体能不能被找到（找不到会直接报错，不会盖出空白）
- PDF 全套产物在真机上打开核对：分割/截取/删页/旋转/拆 N 份/合并/提取文字，尤其**带继承资源的那类文件**（本版本才修，之前会丢字）
- **文本印成 PDF 在真机上的一圈**：断行与分页的宽度模型是桌面版同内核 PDFBox 量出来的，但"这台机器的 `/system/fonts` 里到底哪个字体能显示中文"只有真机答得出来（全找不到会报错，不会出空白页）。要看的是成品在阅读器里的断行观感（标点收尾、段首空两格、页码落在下边距那条带子里）、安卓自己那条 `PdfRenderer` 通路画出来是否与桌面同一张版式
- PDF 压缩在真机上的表现：图片解码走安卓 `Bitmap`，这条通路只在本机 JVM 的桌面版 PDFBox 上验过；另外安卓侧 JPEG 重编的实际大小与压缩比会和桌面数字有出入
- PDF 分割/压缩在大文件（几百 MB）上的内存表现——读和写都走应用缓存目录的临时文件模式，但没在真机压过量
- **PDF 的权限位不是锁**：勾「不允许复制」只是给阅读器看的行为约定，程序照旧抽得出文字（实测）。真正挡住别人的只有打开密码。加密码时所有者密码留空会按打开密码填同一个 —— 库在留空时会自己造一个随机值，那样以后连自己都解不开
- Material 3 界面在实机尺寸下的观感
- 详情页「动起来」只能真机看：这台机器没有渲染面，编得过但看不到。GIF 已改成自家 `:core` 解码器逐帧放（产物本身经字节级测试 + Pillow 复核：29 帧、每帧 100ms、无限循环，所以问题只可能在播放侧），动图 WebP 仍走 `ImageDecoder`→`AnimatedImageDrawable`（API 26/27 退回静帧），视频走 `MediaPlayer`→`TextureView`

## 权限

只申请**读媒体库**：Android 13+ 用 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`，Android 14 额外支持 `READ_MEDIA_VISUAL_USER_SELECTED`（"部分照片"模式，此时只列你授权过的那些，界面会写明），Android 12 及以下用 `READ_EXTERNAL_STORAGE`。不申请写外部存储——成品走 MediaStore 或 SAF，由系统管。拒绝授权也能用，退回系统文件选择器。

「用其他应用打开」不需要任何权限，走的是系统分享/打开那一套。另有一项**可选**权限 `MANAGE_EXTERNAL_STORAGE`（所有文件访问）：不开也能用，成品落在 `Download/文件工坊/`；开了才会直接建 `/storage/emulated/0/文件工坊/` 这种顶层目录（真机实测 MediaStore 那条路会被系统塞回 Download 下）。应用不主动索取，只在成品落不到顶层时提一次，去不去系统设置里开由用户决定。

更新功能额外申请两项：`INTERNET`（只用于访问 `api.github.com` 查版本和下载 apk，你的文件一个字节都不出去）和 `REQUEST_INSTALL_PACKAGES`（Android 8+ 从应用里拉起安装器要这项，系统还会另外问你要不要允许"安装未知应用"）。

## 已知取舍

- 视频只压码率，不改分辨率：安卓没有对外的编码器输入面缩放开关，要改分辨率得走 GPU 通路，是另一件事
- 目标体积算出来低于编码器码率下限时**不硬压**（反抬码率会让成品接近目标两倍），会回落到固定码率并在结果里说明
- GIF 压缩不做法帧差优化；每帧都是完整画布，透明槽（0 号索引）是整段动画的属性
- 压缩视频遇到无压缩 PCM 音轨（不少运动相机/无人机的原始片段就是）会**只压画面、丢掉声音**并在结果里写明：这种音轨 MP4 封装收不下，而音轨是原样搬运不重编码的
- 长任务（大视频转码、大 PDF 压缩）在前台跑；切到后台可能被系统杀掉，加前台服务是下一步
- PDF 压缩只动内嵌图片：纯文字型 PDF 压不动，遇到「一张图都没重编成」或「压完不比原件小」会直接报错、不产出骗人的 `_压缩.pdf`；带透明通道的图（连它的软掩膜对象一起排除，重编会把掩膜变成规范不允许的 RGB 图）、1 位掩膜图、1 位二值扫描图、非 RGB/Gray 色彩空间、单张超 12M 像素的图都不碰（最后一条是防手机端爆内存）
- 提取文字只出文字层：整份抽不出字（纯扫描件）就报错并提示改走「每页导出图片」，不会给一个只有页标题的空 txt
- 其它 PDF 操作不支持带着密码直接做（要先跑一次「PDF 去密码」），因为每一步都重填一次密码容易把自己绕进去；去密码只认打开密码，所有者密码不能用来打开
- 页码和水印是**在页面末尾再叠一层内容**，不是重排版面：所以它们会进文字层（斜着排的水印被文字抽取拆开成"内 部 资 料"这样），要拿干净文字就别先盖章
- 中文水印依赖系统里能找到可用字体：按候选路径逐个试并用 `encode` 实测能否显示，全都失败就明确报错并让你改用英文数字，不会给你一份空白盖
- 页码数字跟着物理页走：只给 3-20 页加号，第 5 页就还是印 5，不会因为跳过前两页就串号
- 按体积分割是真的把每份存一遍量字节，不是估算：PDF 里共享对象在不同分组里大小不同（实测 4 页共用一张图的文档拆两半，每半约 55% 而不是 50%）
- PDF 压缩是原地换掉图片对象，所以全篇共用的一张只重编一次；给了目标体积会按 4 档阶梯一档一档往下试，每档都真存一遍量实际大小
- webm/视频转 GIF 改成**顺序解码**（不再 seek 取帧）：代价是要把窗口内的采样逐个解一遍，比按时间取帧慢，但这是唯一能保证「帧帧不同」的办法 —— 按时间取帧在不少机型上对 WebM 只回关键帧，转出来就是几十张同一画面
- 抽帧时相邻完全一样的画面会合并成一张、停留时间累加；只解出一张画面时直接报错，不出一个假装成功的 GIF
- 成品自动复制到手机存储的 `文件工坊` 目录（顶层建不了就落 `Download/文件工坊`），所以同一份结果在应用里还留一份工作副本 —— 这是为了能继续再加工，代价是占两份空间
- 应用内更新只认同签名覆盖：现在 release 也用 debug key 签，能一路覆盖升级；哪天换正式签名密钥，装新版前必须先卸载，系统不让不同签名的包互相覆盖
- 更新只往 `api.github.com` 发两个请求（查版本、下安装包），你的文件一个字节都不出去；但"全程不联网"这条从今天起要理解成"处理文件不联网"
- 更新走的是仓库当前的可见性：仓库公开时匿名即可查版本和下包；哪天改回私有，面板会把 GitHub 的 404 原话翻成"要么填只读 token，要么把仓库转公开"，不会假装"已是最新版本"
- API 29 以下没有 MediaStore 这套写法，那部分系统只能用「导出」手动选文件夹
- **音频转不成 MP3**：安卓压根没有 MP3 编码器（只有解码器），所以目标格式只给 M4A 和 WAV 两个 —— 与其给一个必失败的选项，不如不做
- 提取音频只有源音轨本来就是 AAC 时才原样搬出（无损）；MP3/FLAC/OGG 要转 M4A 必须解码重编，会有损失，且损失多少取决于系统那个 AAC 编码器的档位
- WAV 是解码出来的裸数据，成品通常比源大十倍上下（128kbps 的三分钟 mp3 → 约 30MB WAV），要小文件就选 M4A
- OGG 只认到容器为止：里面是 vorbis 还是 opus 由系统解码器决定，应用不去猜（猜错的表现是报错而不是坏产物）
- 位深按解码器报的 `KEY_PCM_ENCODING` 算，遇到非整数位 PCM 直接报错让你改用 M4A，不会写一个变速或噪音的 WAV 头
- 文本类文件上限 64MB（防爆内存，不是格式限制）；转编码时**源编码认不干净就直接报错**，不会硬转出一份能打开的乱码 —— 猜错编码代价太大，宁可让你手工指定
- LRC 没有结束时间、一行只放一句，精度只到 10 毫秒；ASS 精度也是 10 毫秒。转到这些格式会丢什么由格式自己声明并显示在界面上，不假装无损
- 字幕只读 `[Events]` 里的 `Dialogue:` 行，样式与 `Comment:` 不搬；输出固定写 UTF-8（播放器对非 UTF-8 字幕普遍直接显示乱码）
- 文本类文件（含字幕）在类型层只标"文本"，具体是哪种字幕不在类型层猜 —— 交给解析器判，判不动会说明是哪种都解不通
- **只认 Office 2007 起的 OOXML**（docx / xlsx / pptx，本质是装了固定部件名的 zip）；老式 .doc / .xls / .ppt 是二进制 OLE 流表，另有一套规矩，没做透就不给入口，遇到会直说"这不是 docx"
- 抽正文**丢图不丢字**：图片、图表、页眉页脚、脚注与尾注的正文、母版里的固定文字、演讲者备注都搬不过来，抽完在结果里逐条列出丢了几处 —— 一份"看着挺全"其实少了三条脚注的文本，比一份写明显少了什么更难查
- 表格里合并的单元格只有左上那一格有字（其余是空格子），条件格式、图表、透视表不搬
- xlsx 的公式给的是文件里存着的**算过的结果**；别的工具刚写完、Excel 还没打开过的文件里没有结果可给，那些格是空格并写明有几格
- 日期靠样式认：样式说这格是日期才转 ISO，纯数字样式一律照文件里的写法搬（不猜）。1900 纪元里那个不存在的 1900-02-29 按 Excel 的写法原样给
- 幻灯片按演示大纲里声明的顺序出页（不是文件名顺序），认不出大纲时退回文件名顺序并在结果里写明
- Markdown 认 CommonMark + GFM 的常用语法，**不认** 脚注、四格缩进代码块、属性花括号那类扩展：这些照字面留下并在结果里说明，不猜。
  内嵌的 HTML 原样搬过去，不解析它、也不检查它是不是合法标签
- 完全没有 Markdown 记号的 txt 走「Markdown 转 HTML」会直接拒绝：硬转只会得到一份"看着一样但少了星号"的文件，
  那种需求该用「文本转编码」
- 转 HTML 会包一层 DOCTYPE 与 `<meta charset>`：不是啰嗦，是没有 charset 的 .html 交给浏览器多半按系统码猜，中文成片乱码
- 「印成 PDF」遇到带 Markdown 记号或 HTML 标签的文本会先抽成纯文本再排版（否则 `#`、`**` 和整个 `<div>` 会一起印到纸上），结果里写明抽了哪一层。判 HTML 在先：`<!doctype>`、`<div>` 这种记号普通文章里不会出现，比 Markdown 那套更具体
- 印成 PDF 排的仍然是**纯文本**：抽掉标记之后按字排版，不照网页的 CSS 摆版面（那是另一件事）
- 网页按标记读，不看 CSS：`display:none` 的字照样抽出来（那是给人看的文字，只是当前不显示）；`<script>` `<style>` `<head>` `<svg>` `<iframe>` `<canvas>` `<template>` `<noscript>` 整段丢掉并写明几段（里面装的不是这篇页面上的字），注释按规范本来就不算正文，丢掉不计数
- 网页的标签补全是浏览器那套的常用子集：`<p>` 遇块级元素收尾、同级 `li / td / th / dt / dd / 标题` 互收、多余的收尾标签忽略、到文件末尾还开着的算闭合。没按规矩闭合的地方会计数并说出来 —— 源文件写坏了，抽出来的结构可能不是作者想的那样，装作没发生才是坑
- 命名实体只带常用的一批（HTML5 那张表两千多条，手机上用不到）：认不出的**照字面留下并计数**，不会静悄悄少字，也不会把 `&nbsp;` 之外的生僻实体猜成别的字
- 表单控件（`input` `select` `button` `label`）只剩标签文字：填进去的值、选中的那项都在属性里，转成 Markdown 也没人读得出来 —— 会写明几处退化成了文字。`textarea` 里的原文按原样留着（那是人写的草稿，不是控件状态）
- 表格的合并单元格**按跨度占位**：`colspan` / `rowspan` 盖住的位置留空格子（后面的格子跳过它们），因为 CSV / 纯文本里没有"跨格"这个概念，只能占位不能表达跨度
- 纯文本与 Markdown 里的表格：格子里的换行并成空格（一行只能摆一格）；要保住格内换行走「网页表格转 CSV」（CSV 里是带引号的换行）
- 写 xlsx 时**格子类型只按字面判**：能一字不差读回来的写法（`12`、`-3.5`）才写成数字，`007`、`1.50`、`1e5`、15 位以上的编号一律保持文字。反过来想要"整列都是数字"就请先在源文件里把它写成规范形式 —— 猜是猜不出来的，猜错的代价是改掉数据
- 写 xlsx 不带样式、列宽、公式与共享字符串表：成品是一张纯值表（默认字体、默认列宽）。日期**不认也不写** —— 源 CSV 里的 `2023-05-01` 会原样作为文字存进去，因为"这列是日期"这件事只有作者知道，表里看不出来
- 写 docx 只有**文字结构**：没有图片（`<img>` 只剩替代文字，几处会说明）、页眉页脚、脚注、目录与批注 —— 那些各要一套部件与关联，做半套比不做更容易误导人
- 写 docx 的版式是固定的一套（A4、页边距与字号都写在 styles.xml 里）：不读源文件的 CSS，也不套 Word 模板。段内的换行写成 Word 的软回车（`<w:br/>`），空行才分段
- 来源挑路有个明说的取舍：既有 Markdown 记号、又有网页骨架（`<div>` `<table>` 这类块级标签）时按**网页**排，稿子里的 `#` 与 `**` 会照字面留在正文 —— 结果说明里会写明按哪条路排的。只出现 `<br>` `<img>` 这种行内标签不算骨架
- PDF 转 Word 的结构是**推**出来的，不是文件里读来的：那几个分寸按量到的真文件定，遇到"整份就一个字号、层级只靠缩进撑"的稿子会认不出标题（认出来的是段落，字一个不少）
- PDF 里的多栏版面**不重排**（会说明哪几页看着像多栏）、表格线还原不成 Word 表（格子的字会按扫读顺序连着成段）、图片与页眉页脚不搬 —— 这三样都是"下一步的事"，现在选择了说明而不是默默产出一份看着挺全的东西
- 以 `=` `+` `@` 或制表符开头的格子按文字存并说明数量：写成公式等于替原作者在别人的电脑上执行一遍（表格注入的经典招式）
- 表名不合 Excel 规矩时（`/ : ? * [ ]`、超过 31 字、重名、`History` 这个保留字）会改掉并说明改了几张 —— 不改 Excel 直接判文件打不开，只改不说又会让用户对不上自己起的名字
- 版式只到"字落在纸上哪个位置"这一层：不分栏、不做页眉、不加目录；首行缩进固定两个字宽（中文的规矩就是空两格），页码固定在下边距那条带子里正中
- 字体里没有的字是**剔掉**而不是画成方框，缺了几个会在结果里报数 —— 所以一份混了生僻字的文本可能出「缺 12 个字」这种提示，那是要换字体而不是文件坏了

- **元数据清理只做了 JPEG 和 PNG**，别的图片类型不给这个入口：GIF / WebP / HEIC 的元数据各自是一套容器规则，拿 JPEG 的段规则硬套会产出打不开的图。这两类也正是"发出去被人看到机型和拍摄地"的主要载体
- 清理**保住像素、丢掉身份**：整段照抄，不重新编码，所以画质零损失，但也因此**不能顺手把方向烙进画面**。原图靠 EXIF 站着（方向不是 1）时界面会提前提醒"清完可能横过来"，要保住方向就先做一次格式转换（那条通路会按方向把像素重画正）再清
- ICC 色彩配置、JFIF 密度、Adobe 通道序这些**影响显示**的段一律保留：删掉它们照片会变色或通道序错乱，那叫损坏不叫清理。EXIF 连着它的内嵌缩略图一起丢 —— 缩略图是最常见的"以为删了其实还在"
- **压缩包支持 zip 与 tar 家族**（tar / tar.gz / tgz / 单个 .gz）：rar 是专有格式、7z 要 LZMA，安卓 `java.util.zip` 里没有对应解码器，硬套只会产出垃圾 —— 遇到会直说这不是 zip 也不是 tar，不假装在解
- tar **不保留权限与属主**：读进来只看名字、长度、时间与类型，写出去固定 0644 / 0755（手机上没有 uid/gid 这套，写进去也是骗人的）；稀疏文件的块映射那套不解，遇到直说
- tar 里没有内容校验：zip 每条带 CRC32，tar 只有头块自己带校验和 —— 所以头部坏了我们会停在原地并报数，内容字节错了没人能发现，这一点不吹成"完整性可验"
- 单个 `.gz` 解出来的名字优先用头里的 FNAME（那是打包时记的原名），没有才退回去掉 `.gz` 的那段；`tar.gz` 里那份 tar 是**看内容**认的（第一块头校验和对得上），不按扩展名猜
- 解压**不保留目录结构**：解出来的文件平铺在工作台里，路径压进名字（`包名_目录_文件.扩展名`）。这一条顺带让 `../../` 那类路径穿越变成结构性不可能 —— 我们从不拿条目名去拼路径
- 带口令的条目跳过并在结果里说明（手机上口令通路容易把自己锁死）；符号链接一律不解；整包都是口令包就直接不做
- 打包时 `jpg / mp4 / zip` 这类**已经压过的直接存原文**，判据是扩展名而不是"先压一遍比大小" —— 流式打包不能把整份先攒在内存里。代价是极少数其实能压的类型会白存一遍
- 单条上限 64 MB、整包声明上限 512 MB：超了直接拒，不解到一半 OOM。这两个数是沿用工作台的文本内存预算，**还没按真机的 `ActivityManager` 堆上限复核过**
- 加/解密 zip 都不做，也不改包内条目：只读目录、只出文件
- **XML ↔ JSON 靠的是约定不是标准**：子元素一律成数组（只有一个也是），这样「有几个孩子」不会丢、转回去还数得出来；属性名前面加 `@`、元素自己的文字进 `#text`、空元素是 `null`、根元素自己不套数组。注释与处理指令不保留
- 带 **DTD 或实体定义**的 XML 一律不解析：外部实体能让一个号称纯本地的转换工具去访问声明里写的地址（XXE），内部实体能无限展开把内存吃光。这条不看解析器脸色，扫源文本也判
- YAML 只到**常用子集**：`!!binary` / `!!python` 那类标签、`? 显式键`、`%YAML` 指令、多份文档（`---` 分隔）里的第二份、
  跨行的 `[]` / `{}` —— 都直接说明并拒绝，不硬读。理由跟 XML 那条一样：猜出来的东西看起来跟成功一样，实际改了数据
- YAML 的类型按 1.2 核心模式判：`yes` / `no` / `on` / `off` / `1:30` / `2023-05-01` 全保持**文字**，
  不会变成 `true` 与 `90`；写 YAML 时这些值会加引号，`1.5e3` 这类"两家读法不同"的数改成等价写法。想按 1.1 读就别走这条路
- `.inf` / `.nan` 到了 JSON 里变成**字符串**：JSON 没有无穷与非数，造一个读不回来的数不如照字面留
- **EPUB 只搬文字那一层**：图片、CSS、字体不搬，只数出几张并在结果里说明没搬；正文里的 `<img>` 只剩替代文字（同网页那条规矩）。想连图一起要的是"整包重排成另一种书"，那是另一件事
- **写成 EPUB 也是纯文字**：源文件里本来就没有可搬的图（Markdown 的图片语法只留替代文字、网页的 `<img>` 同理），所以产出的书没有封面图与样式表；章节是 XHTML，不带 CSS。要连图一起写得先把图片来源定下来（本地文件 / 网页抓取），那是另一件事
- **章只按一级标题切**：Markdown 的一个 `#`、网页的 `<h1>`；二级到六级留在章内当小标题。一份长文没有 `#` 就整本一章（章名用书名），这是"不猜"的代价 —— 猜哪几段算一章会把人的稿子切成没人认的形状
- **Markdown 里长得像标签的一段**（如 `<尖括号>`）我们当**字面文字**留在书里，pandoc 把它当原样 HTML 处理 —— 方向是保守的：内容在，只是不把它当标记执行
- 书号（`dc:identifier`）按"书名 + 原文"折出来，**同一份内容每次导出同一个号**；改了正文就是另一本书。这不是随机 UUID，也不是按文件名 —— 阅读器拿它当"是不是同一本书"的判据
- 章节顺序**只认 spine**：清单里有但没排进 spine 的部件（附录、封面页、备份）不当正文，只在说明里报数；`linear="no"` 的那一次重复也不排进去 —— 一本书读两遍不是我们该做的事
- spine 里对不上的 idref、清单里指向不存在文件的部件：**报数跳过**，不像有些工具整本拒绝。少一章并在结果里说清楚，比一份都不给更可用来赌运气
- 章名的来源按「目录里写的 > 文档自己的 `<title>` > 第一个标题 > 文件名」：目录是作者给读者看的名字，文档 title 常常是"chapter.xhtml"这种工留下的
- 页内锚点（`<a href="#这里">`）摊平成一篇之后没有落点，只留文字并说明几处：留一个点了没反应的链接，不如不留
- 带 BOM 或按 XML 声明是 UTF-16 的章节照编码读（EPUB 3 要求正文用 UTF-8，EPUB 2 时代留下的书里确实有 UTF-16）；加密/带 DRM 的包读不出正文章节，会直说而不是给一份空文件
- 单章 32 MB、整包解压后 512 MB 上限：超了直说不做（同一套防 zip 炸弹的预算，也**还没按真机堆上限复核**）；选多本 EPUB 只给打包，三条出路都是"一本书一份产物"
- 键名不能当 XML 标签的（数字开头、含空格、含尖括号）直接报错让改名，不悄悄换成别的 —— 静悄悄改名等于给用户一份他自己没写过的文件
- 中文元素名是合法的（XML 的 Name 规则允许 Unicode 字母），不会被当坏名字拒掉
- **图标一次出多个尺寸**（默认 16/32/48/256）：只给一个尺寸时，换个场合会被系统强制缩放而发虚。非方图先缩到短边等于目标、再居中裁方，不拉扁
- 写图标一律用 **32 位 DIB**，不用 PNG 内嵌：PNG-in-ICO 要 Vista 之后才被广泛认，而图标是发给别人系统用的
- 拆图标时 PNG 内嵌的那帧**原样交出内嵌字节**，不解一遍再重编 —— 那本来就是完整 PNG，重编只会掉画质
- 带调色板（1/4/8 位）的老图标不解：那是另一套查表逻辑，宁可报"只支持 24/32 位"也不猜出一张错色的图
- **JSON ↔ CSV 是「形状对得上才转」**：JSON 最外层必须是数组（对象与标量没有"行"可摆，会直说而不是硬拆）；空数组定不出列也直接拒。列取各条目键的**并集**，缺的格子留空
- **XML → 表挑"重复最多的那处元素"当行**：条数最多优先、字段数次之、浅的优先。这份 XML 里还有几处也像表会在结果里点名（"另有 N 处…"）—— 一张表装不下两处，硬拼成一张才是丢数据。一处都没有（整份都是"一个元素一个值"的配置文档）就直说不做，并把下一步指到「XML 转为 JSON」
- 列名走**元素路径**（`作者.姓名`），属性前面带 `@`，元素自己的文字进「文本」列：`<a id="1"><id>2</id></a>` 里那两个本来就不是一个东西，撞成一列就是丢一份。摊出来的列名真撞上时加 `#2` 编号而不是互相盖掉
- 一个条目里还有多条的（`<tags><tag>散文</tag><tag>随笔</tag></tags>`）**压成一格 JSON 文本并点名是哪一列**：一行摆不下多条，而压成文本还能被 `JSON 转 CSV / 转表` 那一路再解开；不像有的工具那样整列丢成空
- 数字与真假一律保持源文件里的**写法**（`12.50` 过去还是 `12.50`）：XML 里那串字符就是作者写的样子，认成数就回不去了
- CSV → JSON **默认不猜类型**，格子一律当字符串。开着识别时也只认 **JSON 语法的数字**：`007` 是编号、`1,5` 是欧区小数、`1e` 是坏值，这三个猜错就再也回不去。开了识别要认的账：数字格子的**写法**会丢（`1.50` 读回去是 `1.5`），界面会把这句挂出来
- JSON 里的**注释、重复键、NaN/Infinity 不支持**：那三种都不是合法 JSON。带注释的"JSON"（`jsonc`）会直接报语法错误在哪一步，不会静悄悄吞掉注释再给你一份"看起来一样"的文件
- 分隔符靠投票猜（逗号 / 分号 / 制表符 / 竖线，只数引号以外的），猜错时可以在参数里改选
- 数据格式三个操作只摆在"文本"类型上：跟字幕一样，具体是不是合法 JSON、形状对不对，交给解析器判，判不动会直说
- 没有可清理的段时**不产出文件**，直接告诉你这张本来就没元数据；缺 EOI / IEND 的残档也原样退回。宁可少给一份成品，也不给一份和源文件一模一样的假结果


## 自己构建

```
sdk.dir 写进 local.properties（指向 Android SDK）
gradle wrapper && ./gradlew :core:test :app:assembleDebug
```
需要 JDK 17+、Android SDK platform 35 + build-tools 35。仓库里 `settings.gradle.kts` 配了阿里云镜像优先，个别 Maven Central 文件在部分网络下用 JVM 拉会 TLS 握手失败。
