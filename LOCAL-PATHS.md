# 本机文件位置（审计用）

这份文档记的是**跑这套判据与发版流程所需要的本机路径**：仓库里的 `tools/*` 脚本会引用仓库外的目录（探针的编译缓存、字体来料、闸门脚本、真机驱动），
换一台机器或换一个模型来核，先照这里对齐，再用第 6 节那段命令一次性复查。

三条前提，先说清楚：

1. `core/build/`、`app/build/`、`local.properties` 都在 `.gitignore` 里 —— 判据产物与包**不进版本控制**，仓库里只有代码与 `core/src/test/resources/**` 那批真夹具。
2. `D:/fflogs/` 整目录**不在版本控制里**（闸门脚本、真机驱动、发版脚本、日志、从手机拉回的产物都在这里）。仓库里的脚本引用它时都留了环境变量，默认值指向这里。
3. 构建与判据都要显式带 `GRADLE_USER_HOME=D:/gradle-home`；不带会用 C 盘那个默认家目录，测试工作进程起不来（`GradleWorkerMain` ClassNotFoundException）。

---

## 1 仓库里

| 路径 | 是什么 | 怎么核对 |
| --- | --- | --- |
| `D:/projects/fileforge/` | 仓库根，远端 `EzrealRh/FileForge`，分支 `master` | `git -C D:/projects/fileforge remote -v` |
| `core/` | 纯 Kotlin 判层（无安卓依赖，可 JVM 单测） | `./gradlew :core:test` |
| `app/` | 安卓端：Material 3 界面 + 各引擎 | `./gradlew :app:assembleRelease` |
| `tools/verify_*.py`、`tools/reverse_*.py` | 判据与反例（闸门按 glob 全跑） | `ls tools/verify_*.py \| wc -l` |
| `tools/pdfprobe/`、`tools/imgprobe/` | 桌面替身探针（Java，调 `:core` 同一份判层） | `bash tools/imgprobe/run.sh`（无参数会打用法） |
| `core/src/test/resources/**` | **提交进仓库**的真夹具，例如 `odsread/ledger.ods` | `git ls-files core/src/test/resources \| head` |
| `core/build/<族>/` | 判据现造的夹具与产物（`imgtransform/`、`pdfdoc/`、`csvpdf/`、`epubods/`…），不进仓库 | `ls core/build \| head` |
| `app/build/outputs/apk/release/fileforge-v<版本>-release.apk` | 发版包 | `ls -l app/build/outputs/apk/release/` |
| `local.properties` | `sdk.dir=D:/android-sdk`，不进仓库 | `git check-ignore -v local.properties` |

## 2 仓库外：构建与判据的工具链

| 路径 | 是什么 | 注意 |
| --- | --- | --- |
| `D:/android-sdk` | `local.properties` 里那份 SDK；`build-tools/34.0.0`、`35.0.0`（`aapt2` 核产物用的是 35.0.0）、`platform-tools` | 与下一行**并存**，脚本各用一个 |
| `D:/Android/Sdk` | 模拟器那份 SDK：`emulator/emulator.exe`、`platform-tools/adb.exe`（真机驱动脚本用的都是这里的全路径） | 两处 SDK 不是同一个目录 |
| `D:/platform-tools/adb.exe` | PATH 上那个 adb（第三个） | `command -v adb` |
| `D:/gradle-home` | `GRADLE_USER_HOME`；`wrapper/dists/gradle-8.9-bin`、依赖缓存、`kotlin-stdlib` jar（探针编译要用） | 必须显式带，见前提 3 |
| `D:/toolchain/pb/desktop/` | 桌面 PDFBox 三个 jar：`pdfbox-2.0.27.jar`、`fontbox-2.0.27.jar`、`commons-logging-1.2.jar`（`tools/pdfprobe` 用） | 换机器要先按 `tools/pdfprobe/run.sh` 头注下载 |
| JDK 21（`java` 在 PATH） | 编译与跑探针 | `java -version` |
| Python 3.14.4：`C:\Users\于稷山\AppData\Local\Python\pythoncore-3.14-64\python.exe` | 判据与夹具脚本用的解释器；装好 `Pillow`、`fontTools`、`openpyxl`、`pdfplumber`、`PyMuPDF(fitz)`、`pandas`、`PyYAML` | 这些是**裁判**，缺一个对应那族判据就跑不动 |
| `…\WinGet\Packages\JohnMacFarlane.Pandoc_…\pandoc-3.9.0.2\pandoc` | docx / pptx / odt 族的第三方裁判 | `pandoc --version` |
| `C:/Program Files/Git/mingw64/bin/pdftotext.exe` | poppler，PDF 文字层的第二个裁判；**必须带 `-enc UTF-8`**，不带会把中文全丢掉 | 见 README「差点把调用姿势错写成产品限制」 |
| `C:/Program Files/GitHub CLI/gh` | 发 Release 与远端回读 | `gh auth status` |
| `C:/Windows/Fonts/simhei.ttf` | 桌面探针印 PDF 用的字体（`PDF_TABLE_FONT` / 探针参数） | 子集缺字形判据要用 fontTools 单独核 |

## 3 仓库外：闸门、发版与真机（`D:/fflogs/`）

| 路径 | 是什么 |
| --- | --- |
| `gateNN.sh` + `gateNN.log` | 发版闸门（串行跑全部判据与反例、重出包、重刷产物、复核）。当前是 `gate69.sh`（面向 0.58.0）→ `gate69.log`：986 步 0 红；上一轮 `gate68.sh` → `gate68.log`：986 步 0 红 |
| `device_*.py` | 真机驱动：`device_image.py`（改图片方向这一族）、`device_extra.py`（BMP/TIFF 与 GIF/ICO）、`device_pdf.py` / `device_insert.py`（PDF 新操作）、`device_audio.py`（音量/降噪）、`device_eo.py`（EPUB / ODS 印成 PDF）、`device_xlsx.py`、`device_diag.py`、`device_sheet_list.py`、`device_epub_msg.py`；日志 `device-*.txt` |
| `make_release.py`、`check_release.py` | 发 GitHub Release（带包与说明）与**发完后按 sha256 回读远端资产** |
| `notes-05xx.md` | 每个版本的 Release 说明正文（发版脚本 `--notes-file` 指它） |
| `pdfprobe/`、`imgprobe/` | 探针的编译产物与输出缓存（`PDFPROBE_OUT` / `IMGPROBE_OUT` 的默认值；闸门里显式传这两个） |
| `pulled-eo/`、`pulled-image/`、`pulled-*/` | 从手机共享存储拉回来的产物，判据读的就是这些 |
| `wenkai.ttf`（25 MB 完整字体）、`wenkai-sub.ttf`（上一版子集留档）、`wenkai-latin.ttf`、`lxgw.json` | `tools/make_pdf_font_subset.py` 的**源字体**（LXGW WenKai v1.522，SIL OFL 1.1，不进仓库） |
| `draft/` | 写代码前的草稿（判层、判据、夹具的定稿前版本），不参与闸门 |

## 4 手机上（模拟器 `Pixel_10_API37`）

| 路径 | 是什么 |
| --- | --- |
| `C:\Users\于稷山\.android\avd\Pixel_10_API37.avd` | AVD 目录（启动：`D:/Android/Sdk/emulator/emulator.exe -avd Pixel_10_API37`） |
| `/sdcard/Download/` | 真机一圈的来料（`adb push` 后要发一次 `MEDIA_SCANNER_SCAN_FILE` 广播，SAF 的 Downloads 视图走 MediaStore） |
| `/storage/emulated/0/文件工坊/` | **成品的落点**（共享存储；只有应用私有目录等于没交付） |
| 包名 `com.fileforge.converter` | `pm clear` 清工作台；`appops set … MANAGE_EXTERNAL_STORAGE allow` 给"All files access" |

`find /sdcard -iname '*.pdf'` **不下探应用建的目录**，别拿它的"没有"当事实 —— 用 `ls -l /storage/emulated/0/文件工坊`。

## 5 已知的机器依赖问题（要审就看这几条）

1. **`tools/make_ods_fixtures.py` 今天在这台机器上重跑不了。** 它要求第三方裁判 `odfpy`：`ODFPY_PATH` 默认提示指向 `D:/tmp/pylibs`，而这个目录现在不存在，解释器里也没装 `odfpy`（`python -c "import odf"` 报 ModuleNotFoundError）。
   仓库里那 4 份 `.ods` 夹具（`core/src/test/resources/odsread/`）是它早前产好并提交进去的，`tools/verify_ods.py` 读的是这批文件，所以闸门照过 —— 但**"重造这批夹具"这条路是断的**，改 ODS 侧之前要先把这个依赖补上。
2. **两处 Android SDK 并存**（`D:/android-sdk` 与 `D:/Android/Sdk`）再加 PATH 上第三个 `adb`。判据里 `aapt2` 用前者、真机驱动用后者，改脚本时别混。
3. **探针默认输出在 `/tmp`**：`tools/pdfprobe/run.sh` 与 `tools/imgprobe/run.sh` 不设环境变量时会写到 `/tmp/...`。Git Bash 的 `/tmp` 在调用之间不可靠，所以闸门与判据一律显式传 `PDFPROBE_OUT` / `IMGPROBE_OUT` 到 `D:/fflogs/*`。
4. **`GRADLE_USER_HOME` 一定要显式带**（前提 3）；反例脚本与闸门里都写死了 `D:/gradle-home`。
5. **`tools/updateprobe/run.sh` 会打线上接口**（GitHub Releases 的公开 API，不带任何凭据），它不在闸门里；发版之后的核对走"远端资产 sha256 回读"（本机用 `D:/fflogs/check_release.py`）。
6. **Windows 的两个编码坑一直在背景上生效**：命令行参数走 cp936（所以判据与夹具都写成 UTF-8 的 `.py` 文件而不是内联 heredoc，Java 侧统一带 `-Dstdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`），以及 MSYS 会把像路径的参数改写（所以往设备发文本要 `MSYS_NO_PATHCONV=1`）。

## 6 一次性把上面核一遍

```bash
for p in /d/projects/fileforge /d/android-sdk /d/Android/Sdk /d/platform-tools /d/gradle-home \
         /d/toolchain/pb/desktop /d/fflogs /d/fflogs/pdfprobe /d/fflogs/imgprobe /d/fflogs/draft \
         /c/Windows/Fonts/simhei.ttf "/c/Users/于稷山/.android/avd/Pixel_10_API37.avd"; do
  printf '%-46s %s\n' "$p" "$([ -e "$p" ] && echo 存在 || echo 没有)"
done
python -c "import PIL, fontTools, openpyxl, pdfplumber, fitz, pandas, yaml; print('裁判库齐')"
python -c "import odf" 2>&1 | tail -1                      # 第 5 节第 1 条：现在应该是 ModuleNotFoundError
ls /d/toolchain/pb/desktop                                  # 三个 jar
pandoc --version | head -1; gh --version | head -1
git -C /d/projects/fileforge status --sb | head -2
ls /d/projects/fileforge/app/build/outputs/apk/release/     # 发版包
```
