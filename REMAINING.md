# 还要做的（0.65 起）

当前状态：0.65.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.65.0`，asset `fileforge-v0.65.0-release.apk`，54,627,619 字节）；
`gate76` 闸门 **992 步 0 红**；版本 0.65.0 / versionCode 81 已推送。
**0.65 远端资产已核对**：GitHub API 的 `digest` 为
`sha256:33b05a4eadc63d2128d3093dcd0f09d49e88b475ad1d2744df1cfec8c4ee3f4a`，与本机 sha256 一致；
asset 大小也一致，本机 aapt2 读出 `0.65.0 / 81`，release dex 含 FLAC/SQL 文案（`check_release_0650.py` 退出 0）。

## 已完成

### 0.65.0 · FLAC 输出 + SQL dump 转 CSV

- 音频目标 M4A / WAV / **FLAC**；FLAC 是纯 JVM 的流式 verbatim 编码，PCM 按块读写。
  `verify_flac.py` 用 ffprobe/ffmpeg 复核：STREAMINFO 的 MD5 与源 PCM 一致、解码 PCM 与 WAV data 逐字节相同。
- SQL dump 只读解析 `CREATE TABLE` + `INSERT ... VALUES`，每张表一份 CSV，可只导一张；
  引号、逗号、分号、NULL、多行与无列名 INSERT 都有 core 判据，引擎层出多份产物。

### 0.64.0 · PDF 填表单 + 面板布局

- AcroForm 字段名、`字段名=值` 填写、压平/保留可编辑；PyMuPDF 复核文字层与复选框。
- 底部按钮随长校验文案增高、说明行放宽到 6 行。

### 0.63.0 · PDF 改纸张/缩放

- A4/A5/Letter、等比留白/裁边/拉伸、旋转页按视觉方向；PyMuPDF 复核页框与缩放比例。

### 0.62.0 · WebP 元数据清理 / 0.61.0 · 截取预览 / 0.60.0 · 左下 / 0.59.0 · 多行水印

- 均已发版，证据见对应 gate70–gate74 日志与 README「验证状态」。

## 重活期间的内存记录（本机 16 GB）

- gate76 成功的一次（10:57–11:52）：峰值 **76.2%**、最低可用 3566 MB、无 MEMHIGH、约 55 分钟。
- 期间有两次撞线：gate75 85.2%、gate76 第一次 85.0%，都**立即整树停掉闸门与构建**，没有硬撑。
- 中断后发现反例脚本留下 `Xml.kt` 与 `PdfTable.kt` 的临时改动（前者正是 XML 判据变红的原因）；恢复源文件后 core 判据转绿。
  这两条已写进铁律：**闸门中断后先查 `git diff` 里有没有反例残留，再重跑**。
- 模拟器全程未启动，`qemu-system` 不在进程表里。

## 已到平台/工程量边界的格式

- 写不出：MP3 / Opus（系统没有编码器）、AVIF / HEIC（公共 API 没有编码器）。
- 要做需换库或大工程：HEIC 元数据清理（HEIF box 重排）、RAR5（junrar 不支持）、iWork（IWA/Snappy protobuf）、Parquet。
- 这些不是入口没做，而是当前依赖/平台确实做不到；入口会明确拒绝或只读，不假装支持。

## P1（已有实现，还差真机路径）

- PDF 表单在各阅读器的显示、改纸张观感、FLAC 产物在系统播放器里的播放与时长。
- 视频截取预览与成片开头一致性；水印多行/左下真机观感；外挂字幕烧进视频。
- DOC/PPT 的 UI 入口真机跑一次。

## 技术债

- 视频截取首帧的关键帧语义待核实（`VideoEngine.trim` 丢掉起点前的关键帧，见 `TODO.md`）。
- 真机布局复查：小屏参数面板滚动、长校验文案、字段列表截断。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. **闸门被中断后**先 `git status`/`git diff` 查反例脚本残留（Xml.kt / PdfTable.kt 都出过事），恢复后再重跑。
6. 推送 TLS 抖动时先试 `git -c http.version=HTTP/1.1 push origin HEAD`；Clash 7897 节点全部 502 时，
   用控制管道只切运行时选中节点（不动分组/分流）；「🇩🇪 直连德国」实测可用。
