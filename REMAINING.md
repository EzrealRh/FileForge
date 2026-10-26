# 还要做的（0.60 起）

当前状态：0.60.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.60.0`，asset `fileforge-v0.60.0-release.apk`，54,611,235 字节）；
`gate71` 闸门 **986 步 0 红**；版本 0.60.0 / versionCode 76 已推送。
**0.60 远端资产已核对**：GitHub API 的 `digest` 为
`sha256:a89a1fc5e00943b8d85c347e7b365db8a640142940b1fb0ac92e9204b122805d`，与本机 sha256 一致；
asset 大小也一致，本机 aapt2 读出的包版本是 `0.60.0 / 76`（`check_release_0600.py` 退出 0，没有整包下载）。

## 已完成

### 0.60.0 · 水印「左下」落位

- `ImageEngine` 的文字与 Logo 两处 `spotX` 不再把 2 与 0 一起居中：选左下时真的贴左下。
- `WatermarkDrawTest` 新增墨迹边界判据：改前红（文字左边距 150px），改后绿（贴左边距 13px）；
  文字与 Logo 各量一次，底边同时断言贴底。
- 多行水印在左下按整块左对齐，短行往左缩进。

### 0.59.0 · 水印多行文字

- 图片 / GIF / 视频水印换行即分行；单处与平铺都按整块对齐；单行落位与倾斜轴保持原样。
- core `WatermarkTextTest` 4 例（CRLF/CR、行内空白、首尾空行丢掉、中间空行保留、短行按对齐缩进、块高递推），core 共 939 例。
- app `ParametersPanelTest` 2 例（换行原样进操作、只有换行/空白仍拦下）；app 全量 34 例。
- 像素 `WatermarkDrawTest`：Robolectric 原生图形把「AAA / BBB」真画到 400×400 画布，逐行数非透明像素，
  断言单行一条字带、两行两条字带且中间留空。

## P1（已有实现，还差真机/外挂路径）

- **水印真机 UI**：0.59 的多行输入框（1~4 行）与 0.60 的左下落位，要用真机人眼确认一次
  （本阶段只到 Robolectric 原生图形，未起模拟器）。
- **DOC / PPT 真机各跑一次**：解析在 JVM 真实夹具上验过（POI 的 `simple.doc` / `simple.ppt`），
  OLE2 与类型细化由 XLS 真机覆盖；但 `DOC 提取文字` / `PPT 提取文字` 的 UI 入口还没单独真机跑。
- **外挂字幕烧进视频的真机验证**：目前真机烧的是视频自带的 mov_text 轨；工作台选 `subs.srt` 那一路还没单独跑
  （多选/取消选中的自动化容易踩，`D:/fflogs/device_video.py` 里有草稿）。
- **iWork（Pages / Keynote / Numbers）**：包体是 IWA（Snappy 压缩的 protobuf），要还原文字得实现 IWA 解包与
  各文档模型映射，工作量大且没有公共规范；当前按普通 zip 认，不会硬读。
- **RAR5**：`junrar 7.5.5` 不支持（`UnsupportedRarV5Exception`），当前明确拒绝并提示转 ZIP/RAR4；要支持需换库或原生实现。

## P2（平台或依赖限制）

- 编码输出：AVIF / HEIC、MP3 / FLAC / Opus —— Android 公共 API 没有这些编码器；要么引 NDK 库，要么维持“只读/不写”。
- PDF：改纸张/缩放、表单填写（读取/填写 AcroForm）；扫描件 OCR 已并入 0.58 的 OCR。
- 数据：SQL / Parquet。
- 元数据清理扩展到 WebP / HEIC；图片加密（或明确不做）。

## 下一阶段候选

- 视频截取的段预览（选段前先看那一秒的画面）。
- 操作参数面板的小结文案统一。
- 上面 P1 的真机验证。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon，
   共占约 4.4 GB commit；不杀时基线 71% + qemu → 90.1% `MEMHIGH`，杀了回到 50%。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB，`D:/minilist-review/start-emulator.ps1`），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
6. 推送 TLS 抖动时先试 `git -c http.version=HTTP/1.1 push origin HEAD`；Clash 7897 节点 502 时直接绕过代理：
   `git -c http.https://github.com.proxy= -c http.version=HTTP/1.1 push origin HEAD`（本机直连 github.com 间歇可用，
   `api.github.com` 直连稳定）。
