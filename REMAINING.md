# 还要做的（0.58 起）

当前状态：0.57.0 代码已推送；`gate68` 闸门 **986 步 0 红**；APK 已出（`app/build/outputs/apk/release/fileforge-v0.57.0-release.apk`）。
0.57.0 Release 已发（asset 已挂）；远端 sha256 回读还没做。

## P0

- **发 0.57.0 Release**：`D:/fflogs/make_release_0570.py` + `notes-0570.md`，发完用 `check_release_0570.py` 回读远端 sha256 与 dex 里的「解压 7z」；更新 `LOCAL-PATHS.md` 到 `gate68`。
- **OCR（图片 / 扫描 PDF 提字）**：依赖已确认可取 —— `com.google.mlkit:text-recognition-chinese:16.0.1`（2 MB AAR，内置中文 Hani 与拉丁模型，离线）。
  要做：`OcrEngine`（`Tasks.await` 调 ML Kit）+ `ImageOcr` / `PdfOcr`（`PdfRenderer` 逐页渲染）+ 目录/界面/入口；
  真机用「中文文字图片」与「图片型 PDF」各验一次，确认输出文字与页分隔。
- **音频合并真机验证**：目前只有 `Pcm16Test`（拼接/采样率校验）与音量/降噪的真机证据；合并要选两段真机跑一次。

## P1

- **视频裁画面 / 加水印 / 烧字幕 / 抽字幕**：转格式与合并已完成并真机验过；这四条需要逐帧解码→Canvas 叠加→重编码（或字幕轨解析），是下一块大工程。
- **RAR5**：`junrar 7.5.5` 不支持（`UnsupportedRarV5Exception`），当前会明确拒绝并提示转 ZIP/RAR4；要支持需换库或原生实现。
- **旧 Office**：`.doc/.xls/.ppt`（POI-scratchpad 在安卓的可行性未验）；iWork（Pages/Keynote/Numbers）；MOBI/AZW3。

## P2（平台或依赖限制）

- 编码输出：AVIF / HEIC、MP3 / FLAC / Opus —— Android 公共 API 没有这些编码器；要么引 NDK 库，要么维持“只读/不写”。
- PDF：改纸张/缩放、表单填写；扫描件 OCR（并入 P0 的 OCR）。
- 数据：SQL / Parquet；元数据清理扩展到 WebP/HEIC；图片加密（或明确不做）。

## 每次重活的铁律（本机 16 GB）

1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB，`D:/minilist-review/start-emulator.ps1`），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
