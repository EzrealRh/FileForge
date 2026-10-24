# 还要做的（0.59 起）

当前状态：0.58.0 已发布（`https://github.com/EzrealRh/FileForge/releases/tag/v0.58.0`，asset `fileforge-v0.58.0-release.apk`，54,611,235 字节）；
`gate69` 闸门 **986 步 0 红**；版本 0.58.0 / versionCode 74 已推送。
**0.58 远端 sha256 回读还没跑完**（`D:/fflogs/check_release_0580.py` 后台下载被断电打断；本机 sha256 `e4aa5a617bbeedc8f0db61bb53f35fee2b8fbeaa249105ce7aace5133a18462c`）。

## P0（恢复后第一件事）

- **回读 0.58.0 远端资产**：`cd D:/fflogs && PYTHONIOENCODING=utf-8 python check_release_0580.py v0.58.0`；
  应看到远端字节/sha256 与本地一致，且 dex 里有 `视频烧字幕`（脚本里的 marker）。
- 回读通过后把本节状态改成“已核对”，`LOCAL-PATHS.md` 已指向 `gate69`。

## P1（已有实现，还差真机/外挂路径）

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

## 0.58 的验证证据（断电前已落盘）

- 视频 18/18：`D:/fflogs/check_video_outputs.py` → `video-checks.txt`；裁画面 320×180 全红块、水印中心亮像素 0→1236、
  烧字幕三帧像素、MKV/MP4 抽字幕文本与 0.5/2.0 秒时间轴。
- OCR：`D:/fflogs/device-ocr.txt`（飞行模式，图片 4/4、PDF 4/4）；音频合并：`D:/fflogs/pulled-ocr/merge.wav`。
- XLS：`D:/fflogs/device-xls.txt` 与 `pulled-xls/sheet1.csv`、`sheet2.csv`（两张表、日期、带逗号、007）。
- 闸门：`D:/fflogs/gate69.log` 末尾 `OK 条数 986  RED 条数 0`。
- 0.58 包比 0.57 大约 5 倍：ML Kit OCR 的 native 库带了 arm64-v8a / armeabi-v7a / x86 / x86_64 四套；
  若要减体积可在 release 里只留前两套（代价是 x86 模拟器/Chromebook 跑不了）。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon，
   共占约 4.4 GB commit；不杀时基线 71% + qemu → 90.1% `MEMHIGH`，杀了回到 50%。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB，`D:/minilist-review/start-emulator.ps1`），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
6. 推送 TLS 抖动时用 `git -c http.version=HTTP/1.1 push origin HEAD`。
