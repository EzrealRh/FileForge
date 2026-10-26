# 还要做的（0.62 起）

当前状态：0.62.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.62.0`，asset `fileforge-v0.62.0-release.apk`，54,611,235 字节）；
`gate73` 闸门 **986 步 0 红**；版本 0.62.0 / versionCode 78 已推送。
**0.62 远端资产已核对**：GitHub API 的 `digest` 为
`sha256:c7dcc650c7265448d92ce78ce3f8e0e8afd7a16477b89215f31d7408a5c95e32`，与本机 sha256 一致；
asset 大小也一致，本机 aapt2 读出 `0.62.0 / 78`，release dex 含 WebP 清理文案（`check_release_0620.py` 退出 0）。

## 已完成

### 0.62.0 · WebP 元数据清理

- 详情读出 WebP 的 EXIF（机型、方向、时间等）；清理删掉 EXIF / XMP，重算 RIFF 总长度、清掉 VP8X 的对应标志位。
- 画面块与 ICC 原字节保留；WebP 详情只读块头与 EXIF 载荷，不把整图搬进堆。
- 判据：`ImageMetaTest` 3 例新增（段清单、随机读写报告、清理后画面块 sha 与标志位），core 944 例；
  Pillow 复核像素逐点相同、EXIF 读不到、ICC 一字节没改、RIFF 长度与 VP8X 标志位正确。

### 0.61.0 · 视频截取段预览

- 写好第一段就显示起点附近的一帧，300ms 防抖；走与「抽帧成图片」同一条带旋转的解码路，预览 360px。
- core `TrimPreviewTest` 2 例；真机预览与成片开头的一致性仍在 P1。

### 0.60.0 · 水印「左下」落位

- 文字与 Logo 两处 `spotX` 修正；像素判据改前红（左边距 150px）、改后绿（13px）。

### 0.59.0 · 水印多行文字

- 图片 / GIF / 视频水印换行即分行，单处与平铺按整块对齐；core 4 例 + 参数面板 2 例 + 逐行数字带的像素判据。

## 重活期间的内存记录（本机 16 GB）

- gate70 / gate71 / gate72 / gate73 都是串行跑：构建 2g / 4 workers / 不并行，模拟器全程没启动。
- 四次闸门期间 crashwatch **没有 MEMHIGH**：峰值 61.9% / 63.1% / 78.9% / 78.8%，最低可用 1727 MB；
  都在 85% / 800 MB 停下线以上。gate73 用时约 57 分钟。
- gate 之外 10-25 10:18 出现过 82.7%，是 Brotato / powershell，不是构建或模拟器。

## P1（已有实现，还差真机/外挂路径）

- **视频截取预览的真机确认**：预览与成片开头对不对得上、长 GOP 视频取帧要多久。
- **水印真机 UI**：多行输入框与左下位置要用真机人眼确认一次。
- **DOC / PPT 真机各跑一次**：JVM 夹具验过解析，UI 入口还没真机跑。
- **外挂字幕烧进视频的真机验证**：目前真机烧的是自带 mov_text 轨，工作台选 `subs.srt` 那一路还没单独跑。
- **iWork / RAR5**：iWork 是 IWA（Snappy protobuf），RAR5 要换库；两者都还没做。

## P2（平台或依赖限制）

- 编码输出：AVIF / HEIC、MP3 / FLAC / Opus —— Android 公共 API 没有这些编码器；要么引 NDK 库，要么维持“只读/不写”。
- PDF：改纸张/缩放、表单填写（读取/填写 AcroForm）。
- 数据：SQL / Parquet。
- 元数据清理扩展到 HEIC；图片加密（或明确不做）。

## 下一批（“全都做”的可行部分）

- PDF 改纸张/缩放：内容矢量缩放，旋转页按视觉坐标处理；Robolectric + 真实 PDFBox 判页框与文字位置。
- PDF 表单填写：读字段名、按 `名称=值` 填，填完压平成静态；判据用真实 AcroForm 夹具。
- 上述两项完成后做 FLAC 输出（纯 JVM 编码器，ffmpeg 复核），再评估 Opus / SQL。
- 全仓 bug 与布局复查：先查参数面板滚动、长文本溢出与小屏按钮可达性。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon，
   共占约 4.4 GB commit；不杀时基线 71% + qemu → 90.1% `MEMHIGH`，杀了回到 50%。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB，`D:/minilist-review/start-emulator.ps1`），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
6. 推送 TLS 抖动时先试 `git -c http.version=HTTP/1.1 push origin HEAD`；Clash 7897 节点全部 502 时，
   用控制管道 `\\.\pipe\verge-mihomo-production-<hash>` 只切运行时选中节点（不动分组/分流），
   本机实测「🇩🇪 直连德国」可用；`api.github.com` 直连稳定。
