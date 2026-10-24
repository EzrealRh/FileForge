# 还要做的（0.58 起）

当前状态：0.57.0 已发并回读核对（远端字节/sha256 与 dex「解压 7z」一致）；`gate68` 闸门 **986 步 0 红**。
0.58 开发中：OCR 已实现并真机验过（中文图片 8/8、两页图片型 PDF 页界），音频合并已真机逐样本验过。

## 已完成（0.58）

- **0.57.0 远端回读**：`D:/fflogs/check_release_0570.py` 通过；`LOCAL-PATHS.md` 已指向 `gate68`。
- **OCR（图片 / 扫描 PDF 提字）**：`com.google.mlkit:text-recognition-chinese:16.0.1`（AAR 自带 Hani/Latn 模型与检测器）。
  `OcrEngine` 用 `Tasks.await`；图片整张认，PDF 用 `PdfRenderer` 逐页渲染（长边 2200、先刷白底）；输出 txt 带页界；
  飞行模式下真机验过（`D:/fflogs/device-ocr.txt`：图片 4/4、PDF 4/4）。
- **音频合并真机验证**：两支 WAV 多选合并，产物 132300 样本与两段原文件逐样本一致（`D:/fflogs/pulled-ocr/merge.wav`）。
- **视频裁画面 / 加水印 / 烧字幕 / 抽字幕**：Media3 Transformer（GL 效果管线）落地。
  真机 **18/18 通过**（`D:/fflogs/check_video_outputs.py`、`video-checks.txt`）：裁画面 320×180 全红块、水印中心 0→1236 个亮像素、
  烧字幕的没字幕帧与两句字幕帧都按像素验过、MP4（mov_text）与 MKV（Matroska 自解析）抽字幕的文本、条数与时间轴都对。
  外挂字幕（SRT/VTT/ASS/LRC）与自带轨走同一层，UI 取外挂文件那一路还没单独真机跑。
- **MKV 字幕轨**：系统 `MediaExtractor` 不暴露 MKV 字幕轨，改用 `core/video/Matroska.kt` 自解析
  （Tracks/Cluster/SimpleBlock/BlockGroup + ASS 九段正文）；真机导出 523/1523、2023/2823 两条。
- **MOBI / AZW3**：`Mobi.kt` 解析 PDB/MOBI 头与 EXTH，PalmDOC LZ77 展开；HUFF/CDIC 与 DRM 明确拒绝。
  `MobiTest` 7 项（真实夹具、压缩/未压缩、回指、DRM、HUFF、嗅探）全绿；可抽 txt 或转 EPUB。
- **旧版 Office（.doc / .xls / .ppt）**：`Ole2.kt` 读 CFB（FAT + miniFAT + 目录），
  `OldOffice.detect` 按流名把 OLE2 细化成 Doc/Xls/Ppt（`Workspace` 导入时就走这一串）。
  `Biff.kt` 读 BIFF8 单元格 + XF/FORMAT 日期；`Word97.kt` 读 FIB/CLX/piece table；`PptText.kt` 走记录树并去重修订。
  单测用 xlwt 生成的真实 .xls、POI 的 simple.doc / simple.ppt（Apache-2.0）与独立字节串对照。
  XLS→CSV 已真机验过（两张表、日期、带逗号与 007）；DOC/PPT 的解析在 JVM 夹具上验过，未单独跑真机。

## P1（未做）

- **iWork（Pages / Keynote / Numbers）**：包体是 IWA（Snappy 压缩的 protobuf），要还原文字得实现 IWA 解包与各文档的模型映射，工作量大且没有公共规范；当前会按普通 zip 认，不会硬读。
- **RAR5**：`junrar 7.5.5` 不支持（`UnsupportedRarV5Exception`），当前会明确拒绝并提示转 ZIP/RAR4；要支持需换库或原生实现。

## P2（平台或依赖限制）

- 编码输出：AVIF / HEIC、MP3 / FLAC / Opus —— Android 公共 API 没有这些编码器；要么引 NDK 库，要么维持“只读/不写”。
- PDF：改纸张/缩放、表单填写；扫描件 OCR（并入 P0 的 OCR）。
- 数据：SQL / Parquet；元数据清理扩展到 WebP/HEIC；图片加密（或明确不做）。

## 每次重活的铁律（本机 16 GB）

0. **起模拟器前先杀构建守护进程**：`gradlew --stop` 之后还有 `KotlinCompileDaemon` 与另一个 `GRADLE_USER_HOME` 的 daemon，共占约 4.4 GB commit；不杀时基线 71% + qemu → 90.1% `MEMHIGH`，杀了回到 50%。
1. 构建与模拟器**串行**；只用 `MiniListAudit`（2 GB，`D:/minilist-review/start-emulator.ps1`），不要用 4 GB 的 Pixel。
2. 测完（失败也）立刻 `adb -s emulator-5554 emu kill`，再确认 `qemu-system-x86_64-headless` 已退出。
3. 每步前看 `D:/crashwatch/watch.log`：`commitPct >= 85` 或 `memAvailMB < 800` 立即停并报告。
4. `gradle.properties` 里的 2g / 4 workers / 不并行不要覆盖。
5. 发版/判据/模拟器不要与第二个会话或 Computer Use 同时跑。
