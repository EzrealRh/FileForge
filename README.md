# 文件工坊 FileForge

本地运行的安卓文件转换工具。图片、PDF、GIF、视频都在手机上处理，不联网、不上传。

## 装到手机

1. 把 `app/build/outputs/apk/debug/app-debug.apk` 传到手机（数据线、网盘、聊天工具互传都行）
2. 在手机上点开这个 apk → 允许"安装未知应用"
3. 桌面出现「文件工坊」

## 用法

- 首页「添加文件」选一批文件（也可以在别的 App 里点分享 → 文件工坊）
- 勾选要处理的文件 → 底部「转换」→ 选操作 → 调参数 → 开始
- 转换结果会作为新文件出现在同一个列表里，**可以立刻再勾选继续做下一步**（例如 HEIC → JPG → 压缩 → 合成 PDF）
- 右上角文件夹图标导出：有选中就只导出选中，否则导出全部，落到你选的系统文件夹

## 当前功能

| 分类 | 能做 |
| --- | --- |
| 图片 | JPG / PNG / WebP / BMP / HEIC / AVIF 互转（按真机解码器支持情况），压缩到目标体积或质量，限最长边 |
| GIF | 压缩（降边长、降帧率、减色数），MP4 / WebM / MKV 转 GIF |
| PDF | 按目标体积分割（尾部不足一份的剩余页单独成文件，按 part01、part02… 顺序编号）、按页码截取（支持 `100-150,200-250` 多段、倒序、重复）、每页导出图片、多张图合成 PDF |
| 视频 | 硬件解码+硬件编码转码压码率（MP4/H.264；WebM/VP8 可选，WebM 不带音轨） |

按体积分割是真的把每份存一遍量字节，不是估算——PDF 里共享对象会在不同分组里变化，估算会切不准。

## 工程结构

```
core/   纯 Kotlin，无安卓依赖，可 JVM 单测
  core/pdf/    页码范围解析、按体积分组算法
  core/gif/    GIF89a 编解码（LZW + 中位切分量化）
  core/ops/    操作目录与参数模型
  core/naming/ 输出命名与编号规则
app/    安卓端：Material 3 界面 + 各引擎（MediaCodec / BitmapFactory / PdfRenderer / PDFBox）
tools/make_gif_fixtures.py  用 Pillow 生成 GIF 测试夹具
```

## 验证状态

已经在 CI 之外真跑过的：

- `./gradlew :core:test` 42 个用例全绿：页码解析（多段/开放端点/倒序/越界/坏输入）、按体积分组（含"试探次数远小于页数"的复杂度约束、强非单调 measure 的 fuzz）、命名补零与重名、大小解析、GIF 编解码、像素预算截断、disposal 与透明槽往返、视频码率反推
- GIF 解码器逐像素比对 **Pillow 生成的 4 份夹具**（含交错帧、多帧、40x40x200 色），真值也来自 Pillow
- GIF 编码器输出经 **Pillow 与 omggif 两个第三方解码器**验证：mincode=2 的 6000 像素码流全部还原一致；把 4 份夹具重编码后交给 Pillow 读回，3 份 0 差异，`rich`（跨帧 792 色压到 256 色）最大色差 40，属于调色板收敛的预期损失
- LZW 码宽时序、交错行序、KwKwK 特例、字典满 4096 重置、MedianCut 均经独立推导 + Pillow/omggif 实测确认
- `:app:assembleDebug` 出包成功，`aapt dump badging` 确认包名/图标/PDFBox 字体资源已进包

还没验证的（需要真机跑一次）：

- 真机上 SAF 选文件、导出到文件夹的完整往返
- MediaCodec 在具体机型上的解码/编码支持面（尤其 WebM/VP8 编码器和 HEIC 解码）
- PDF 分割在大文件（几百 MB）上的内存表现——已按临时文件模式加载，但没在真机压过量
- Material 3 界面在实机尺寸下的观感

## 已知取舍

- 视频只压码率，不改分辨率：安卓没有对外的编码器输入面缩放开关，要改分辨率得走 GPU 通路，是另一件事
- 目标体积算出来低于编码器码率下限时**不硬压**（反抬码率会让成品接近目标两倍），会回落到固定码率并在结果里说明
- GIF 压缩不做法帧差优化；每帧都是完整画布，透明槽（0 号索引）是整段动画的属性
- 长任务（大视频转码）在前台跑；切到后台可能被系统杀掉，加前台服务是下一步
- GIF 压缩不做法帧差优化（只降尺寸/帧率/颜色），体积还有下降空间

## 自己构建

```
sdk.dir 写进 local.properties（指向 Android SDK）
gradle wrapper && ./gradlew :core:test :app:assembleDebug
```
需要 JDK 17+、Android SDK platform 35 + build-tools 35。仓库里 `settings.gradle.kts` 配了阿里云镜像优先，个别 Maven Central 文件在部分网络下用 JVM 拉会 TLS 握手失败。
