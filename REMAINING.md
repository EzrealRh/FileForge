# 还要做的（0.59 起）

当前状态：0.59.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.59.0`，asset `fileforge-v0.59.0-release.apk`，54,611,235 字节）；
`gate70` 闸门 **986 步 0 红**；版本 0.59.0 / versionCode 75 已推送。
**0.59 远端资产已核对**：GitHub API 的 `digest` 为
`sha256:688c67d03e4cfec0d1dd35d2b5d23b2f70c4dfa9354bde7782113c6ef2fe4997`，与本机 sha256 一致；
asset 大小也一致，本机 release dex 里搜得到多行水印文案「每一行都会落在画面上」（`check_release_0590.py` 退出 0，没有整包下载）。

## 本阶段已完成（0.59.0）

- **图片 / GIF / 视频水印支持多行文字**：换行即分行；单处与平铺都按整块对齐；单行落位与倾斜轴保持原样。
  - core：`WatermarkTextTest` 4 例（CRLF/CR、行内空白、首尾空行丢掉、中间空行保留、短行按对齐缩进、块高递推），core 共 939 例。
  - app：`ParametersPanelTest` 2 例（换行原样进操作、只有换行/空白仍拦下），app 共 33 例。
  - 像素：`WatermarkDrawTest` 用 Robolectric 原生图形把「AAA / BBB」真画到 400×400 画布，逐行数非透明像素，断言单行一条字带、两行两条字带且中间留空。
- 发布证据：`D:/fflogs/gate70.log` 末行 `OK 条数 986  RED 条数 0`；`D:/fflogs/notes-0590.md` 带本次 SHA256；
  `D:/fflogs/check_release_0590.py` 用 API digest 回读，不下载 54MB 整包。

## P1（已有实现，还差真机/外挂路径）

- **0.59 多行水印的真机 UI**：输入框已换成 1~4 行，产物要用真机人眼确认一次（本阶段只到 Robolectric 原生图形，未起模拟器）。
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

- 修水印「左下」：面板选左下时，文字与 Logo 现在都画在底部中间（`ImageEngine` 两处 `spotX` 把 2 与 0 一起居中），
  要补一条数墨迹位置的像素判据；已有产物会从底部中间换到真的左下。详见 `TODO.md`。
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
