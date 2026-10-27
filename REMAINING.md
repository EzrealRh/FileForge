# 还要做的（0.64 起）

当前状态：0.64.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.64.0`，asset `fileforge-v0.64.0-release.apk`，54,611,235 字节）；
`gate75` 闸门 **990 步 0 红**；版本 0.64.0 / versionCode 80 已推送。
**0.64 远端资产已核对**：GitHub API 的 `digest` 为
`sha256:f1dd23f6df591f461b55dc27d6bc73275fc3e078313aacb477ed1c3521be67bf`，与本机 sha256 一致；
asset 大小也一致，本机 aapt2 读出 `0.64.0 / 80`，release dex 含表单入口文案（`check_release_0640.py` 退出 0）。

## 已完成

### 0.64.0 · PDF 填表单 + 面板布局

- 读 AcroForm 字段名，按「字段名=值」填；文本、复选框、单选/下拉走 PDFBox 字段类型；可压平或保留可编辑。
- 面板列出字段名；字段写错会点名实际可用的字段。
- 判据：core `PdfFormFillsTest` 4 例（core 955）；app 3 例填表 + 参数面板 1 例（app 41）；
  `tools/verify_pdf_form.py` 用 PyMuPDF 复核压平后无控件、文字层有值、复选框区域变黑、可编辑模式字段值。
- 布局：底部按钮随长校验文案增高、说明行放宽到 6 行，参数区仍滚动。

### 0.63.0 · PDF 改纸张/缩放

- A4/A5/Letter，等比留白/裁边/拉伸；矢量缩放不转图，旋转页按视觉方向；PyMuPDF 复核缩放比例。

### 0.62.0 · WebP 元数据清理

- 删 EXIF/XMP、重算 RIFF 长度、清 VP8X 标志；画面块与 ICC 原样；Pillow 复核。

### 0.61.0 · 视频截取段预览

- 起点附近一帧，300ms 防抖；与抽帧成图片同一条带旋转的解码路。

### 0.60.0 · 水印「左下」落位 / 0.59.0 · 水印多行

- 左下像素判据先红后绿；多行水印 core+像素判据。

## 重活期间的内存记录（本机 16 GB）

- **gate75 第一次跑到 85.2% 出现 MEMHIGH，立即整树停掉闸门与构建**；清理后基线从 84.3% 回落到 66.4%，
  再跑一次完整闸门：峰值 77.3%、最低可用 4148 MB、无 MEMHIGH、用时约 50 分钟。
- gate70–gate74 峰值 61.9% / 63.1% / 78.9% / 78.8% / 83.0%，均无 MEMHIGH。
- 模拟器全程未启动；`qemu-system` 不在进程表里。

## 下一批（“全都做”的可行部分）

- FLAC 输出：纯 JVM 编码器（verbatim/fixed 子帧 + Rice 编码 + MD5），ffmpeg 复核无损；MP3/Opus 仍受系统编码器限制。
- SQL 转 CSV/JSON：解析 INSERT 与建表列名，先做只读；Parquet 仍缺库。
- HEIC 元数据清理：容器是 HEIF box，需另写解析器；RAR5/iWork 仍需换库或大工程。
- 真机复查：PDF 表单各阅读器显示、改纸张观感、水印/预览 UI。

## P1（已有实现，还差真机路径）

- 视频截取预览与成片开头一致性；水印多行/左下真机观感。
- DOC/PPT 的 UI 入口真机跑一次；外挂字幕烧进视频的真机验证。
- iWork / RAR5：明确未做。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon，
   共占约 4.4 GB commit；不杀时基线 71% + qemu → 90.1% `MEMHIGH`，杀了回到 50%。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
6. 推送 TLS 抖动时先试 `git -c http.version=HTTP/1.1 push origin HEAD`；Clash 7897 节点全部 502 时，
   用控制管道只切运行时选中节点（不动分组/分流）；「🇩🇪 直连德国」实测可用。
