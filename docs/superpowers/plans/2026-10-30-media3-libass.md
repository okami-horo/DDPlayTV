# Media3 libass 实施与验证

依据：[接入设计](../designs/2026-10-29-media3-libass-integration-design.md)。本轮用户已授权实现；不提交 Git、不修改 mpv AAR 或视频输出。

## 实施顺序

1. 时间与帧：通过 Media3 公开的 stream offset 和 period/window 信息替代量级猜测；不可变帧请求保留微秒、可选纳秒目标和状态版本；没有 metadata 时使用位置驱动，恢复后停止替代提交。
2. 输入与资源：继承现有 Matroska extractor 的公开扩展点提取字体；按媒体隔离字体、样本和加载；保留同媒体字体；区分 storage、frame 和 output，限制分项资源。
3. 验证：补充时间、作用域、竞态和真实 native 像素用例；执行 JVM、Debug/Release、lint、模块及播放边界检查，按实际设备运行 DPAD 回归。

## 决策与边界

- 保留原始 ASS decoder 和普通文字/位图路径，不引入 Media3 私有字段反射或第二套解复用器。
- 字幕 Surface 继续对齐视频 View。默认栅格比例为 1.0，不改变裁剪产品行为。
- 所有帧基础交付采用立即提交；纳秒目标保留为调度证据。精确呈现调度、atlas、计算/GL 拆线程和推测预热，按设计的测量门槛决定，不能因传递目标时间就宣称实际同步。
- 物理电视画质/PQ/HDR、完整复杂字幕矩阵和长期性能需要对应设备证据；模拟器与 JVM 结果分别记录。

## 实现结果

- [x] A1：公开 `ForwardingRenderer`/`SampleStream` 将真实 stream offset 与 period/window 关系带入 ASS 解码，删除 `1e12 us` 猜测；视频 Format 的 period 标记随真实解码输出流转，避免下一 period 预读错配。普通 Cue 保留，官方 secondary renderer 预热创建继续转发。
- [x] A2：公开 Matroska EBML 扩展点提取附件，包括数据早于名称/MIME 的顺序。每个 source 独立 input，通过 MediaItem tag 激活；预加载字体/样本暂存，旧媒体及旧解码器的格式/样本/释放不能接管新输入。
- [x] B：内容微秒、目标纳秒、状态/Surface 版本和入队时间作为不可变请求。JNI 计算前及 swap 前检查版本；FIFO 状态命令不丢弃。metadata 超时 250ms 后每 50ms 使用位置驱动，恢复后抑制位置提交，停止后清理回调。
- [x] C 基础：storage/frame/output 分离，传递真实 storage/PAR/媒体旋转，默认 raster scale 1.0；字体 16 MiB/份、32 MiB/媒体（pending + retained），覆盖纹理 64 MiB/4096 图像及 GL 最大尺寸防线。缓存显式选 libass 默认值。超限字体记录并回退；超限渲染拒绝整帧，沿原故障路径处理。
- [x] 保留单渲染线程与立即提交，区分静态复用和真实提交，记录 CPU 分段、队列及目标差值。晚到字体重建 renderer 字形/字体缓存，保留 ASS track；切媒体清理字体、track 和纹理。
- [x] Media3 视频 Surface 销毁时按实际绑定所有者执行 `clearVideoSurface`，不再向失效窗口输出；保留原输出类型。mpv AAR 与固定直出不变。
- [x] 增加 JVM、设备实际字形、几何、时间、连续播放和应用像素/DPAD 回归；更新维护说明、设计状态、导航与 memory。

C 的代表性 TV 参数定型、长期资源曲线与降采样评估尚未验收。D/E 的精确呈现、atlas、计算/GL 拆线程、推测和预热未启用，没有同设备瓶颈/收益证据，不提前实现。

## 参考与取舍

在已有设计对 Jellyfin/Plezy 的固定源码研究基础上，本轮核对 Media3 `1.11.1` 的公开 [BaseRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/BaseRenderer.java)、[ForwardingRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ForwardingRenderer.java)、[TextRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/text/TextRenderer.java)、[MatroskaExtractor](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)、[MediaCodecVideoRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java) 和 [DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)。不引入私有字段反射、额外依赖或另一套解复用器；legacy decoder 仍存在升级耦合。

ccc 因 uv trampoline 路径错误不可用，当前没有 rg，使用 Git 文件列表、Git 文本搜索和定向读取；没有安装工具或重建索引。部分固定上游 raw URL 读取失败，不将既有 Jellyfin/Plezy 研究说成本轮重新验证或同机性能对比。

## 构建与自动检查

最终命令：

```powershell
& scripts/dev-env/gradlew-local.bat `
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest `
  :player_component:assembleDebugAndroidTest testDebugUnitTest lint ktlintCheck `
  verifyModuleDependencies verifyPlaybackBoundaries `
  -I .tmp/handoff14-local.gradle -I .tmp/media3-libass-style.gradle `
  --offline --console=plain
```

`.tmp/media3-libass-validation-final-4.log` 尾部为 **BUILD SUCCESSFUL in 51s**，1723 项 tasks。包含两个 ARM ABI native 编译、Debug/Release/test APK、全量 JVM、全量 lint、变更范围 ktlint、模块和播放边界检查。没有修改生产依赖或启用元数据校验绕过；Release 使用本机既有 debug signing fallback，此处不声称发布签名产物。

JUnit XML 汇总 **395 项：393 通过、0 failures/errors、2 外部协议夹具跳过**，见 `.tmp/media3-libass-jvm-counts.json`。app lint 报告 0 errors、17 warnings、1 hint；上游 Kotlin 元数据分析仍有既有诊断但任务成功。ktlint 仅检查本轮变更的 Kotlin 文件，不声称全仓存量基线通过。最终格式命令日志为 `media3-libass-surface-format.log`，尾部 **BUILD SUCCESSFUL in 6s**。

## 设备验证

使用任务独立的 `Pixel_5_API_34` 只读冷启动，`emulator-5558` 为 `device`，SwiftShader、ARM64 translation、普通 UI mode `0x11`。应用 UI 阶段临时使用 1920×1080/160dpi，视频夹具为已有 `.tmp/lease-fixtures/share/playback-long.mp4`，推送到 `/sdcard/Download/ddplaytv-lease-playback-long.mp4`，向实际目标包 `com.okamihoro.ddplaytv.debug` 授予声明的媒体读取权限。

设备测试用实际生成的 APK 安装后直接运行 AndroidJUnitRunner。Gradle `connectedDebugAndroidTest` 因离线缺少 UTP `android-test-plugin-host-additional-test-output:31.10.1` 未启动成功，`.tmp/media3-libass-device-2.log` 为 **BUILD FAILED**；直接 ADB runner 是本轮设备结果依据，不将 UTP 任务写成通过。

最终两个 runner **串行执行，共 36 项通过**：

| 组别 | 结果 | 本机原始记录 |
| --- | --- | --- |
| `Media3SubtitleCompatibilityTest`、`LibassGpuRenderingTest`、`MpvSurfaceLifecycleTest`、`Media3DecoderLifecycleTest`、`Media3CodecFallbackTest` | **OK (25 tests)**，32.03 秒 | `.tmp/media3-libass-native-device-final.log` |
| 两内核面板/字幕像素、三项 seek/加密重入、两内核外部入口重入、四项播放器设置 | **OK (11 tests)**，144.993 秒 | `.tmp/media3-libass-app-device-final.log` |

应用组安装第四轮完整构建生成的 APK 后运行，两个 runner 未并发。其日志中模拟器 `s_glBindAttribLocation` 的 stderr 被 PowerShell 标为 `NativeCommandError`，导致外层命令退出码为 1；AndroidJUnitRunner 最终明确为 `OK (11 tests)`，没有测试断言失败。此结果与上面的 Gradle UTP 失败分别记录。

可复现的设备命令（仓库根目录，先安装对应 APK）：

```powershell
$adbPath = '.dev-env/android-sdk/platform-tools/adb.exe'
& $adbPath -s emulator-5558 install -r player_component/build/outputs/apk/androidTest/debug/player_component-debug-androidTest.apk
& $adbPath -s emulator-5558 shell am instrument -w -r -e class 'com.xyoye.player_component.ui.Media3SubtitleCompatibilityTest,com.xyoye.player_component.ui.LibassGpuRenderingTest,com.xyoye.player_component.ui.MpvSurfaceLifecycleTest,com.xyoye.player_component.ui.Media3DecoderLifecycleTest,com.xyoye.player_component.ui.Media3CodecFallbackTest' com.xyoye.player_component.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath -s emulator-5558 install -r app/build/outputs/apk/debug/ddplaytv_v4.2.2-debug_arm64-v8a-debug.apk
& $adbPath -s emulator-5558 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$appChecks = @(
    'com.okamihoro.ddplaytv.ui.tv.TvDpadRewriteInstrumentedTest#playerPanelArrowsDoNotSeekAndBackReturnsToTheCallingSetting'
    'com.okamihoro.ddplaytv.ui.tv.TvDpadRewriteInstrumentedTest#mpvPlayerPanelSupportsDpadPauseResumeAndBack'
    'com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#completedSeekImmediatelyPersistsEncryptedRecoveryPosition'
    'com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#mpvCompletedSeekPersistsRecoveryPosition'
    'com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#encryptedRequestReentryAndRecreationKeepOneActivityAndOwnedSources'
    'com.okamihoro.ddplaytv.playback.PlaybackEntriesInstrumentedTest#externalIntentUsesIdOnlyNavigationAndReentersExistingPlayer'
    'com.okamihoro.ddplaytv.playback.PlaybackEntriesInstrumentedTest#mpvExternalIntentReentersExistingPlayer'
    'com.okamihoro.ddplaytv.ui.setting.PlayerSettingDpadNavigationTest'
) -join ','
& $adbPath -s emulator-5558 shell am instrument -w -e class $appChecks com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner
```

应用截图已拉取并查看：`.tmp/media3-libass/media3-libass-subtitles.png` 和 `mpv-native-subtitles.png` 均显示实际视频及中文字幕；像素断言与层级断言见测试源码，不以截图替代时序测量。

### 纯 DPAD 冒烟与环境恢复

- 以 `am start -W -n com.okamihoro.ddplaytv.debug/com.okamihoro.ddplaytv.ui.splash.TvSplashActivity` 启动，随后只使用 `input keyevent 19/20/21/22/23/82/4` 操作，无触摸或直接设置焦点。
- 路径为“我的 → 播放器设置 → 选择 Media3 → BACK 回原卡 → 媒体库 → 串流播放历史 → 播放夹具 → 暂停 → MENU → 字幕轨 → 添加字幕轨 → 文件列表选择 `Media3-DPAD.ass` → 选中字幕”。ASS 为本轮小型中文夹具，预先推送到测试应用外部 files 目录；真实 libass 画面可见。
- 进入字幕样式，DPAD RIGHT 将垂直偏移从 `0%` 改为 `+1%`，暂停画面立即重绘且字幕上移；BACK 回原“样式”卡，再进入仍为 `+1%`，LEFT 还原 `0%`。透明度保持 `100%`、字号偏移保持 `0%`。BACK 回面板及播放器后，双 BACK 退出回历史原条目，焦点可见、可达、可恢复。
- 第一轮浏览文件时两分钟夹具自然结束；最终主路径重新播放后在约 4.5 秒暂停完成，没有将自然结束当作 Surface/播放故障或修改产品逻辑。字幕原位置及 `+1%` 截图分别为 `.tmp/media3-libass/smoke-style.png`、`smoke-style-changed.png`；已实际查看。逐步 XML、按键记录和 logcat 位于同目录的 `smoke-*.xml`、`smoke-actions.log`、`smoke-logcat.log`。
- 结束前执行 `wm size reset`、`wm density reset`，读回 **1080×2340、440dpi、普通 UI mode `0x11`、非 root 的 uid 2000**，设备仍为 `device`；见 `.tmp/media3-libass/avd-restored.log`。模拟器控制台不支持本轮 `emu kill`，沙箱停止进程也被拒；核对任务 PID 文件、只读/AVD/5558 参数及父子关系后，通过受控权限仅关闭本轮 PID 9872/10372。最终确认两个 PID 均已不存在、`adb devices -l` 无设备；见 `avd-closed.log`。没有保存快照或修改 AVD 配置文件。

### 验证范围

- 解码器大时间戳红绿回归、原始 duration/codec private、非零裁剪、0.5/1/2 倍速、多 period 前后 seek。
- 真实视频解码 metadata 覆盖裁剪、自然 period 过渡、前后 seek；没有 metadata 时的位置提交、恢复与 `TIME_UNSET`/停止。
- 实际连续播放通过 source tag 激活匹配 input，预加载输入保留，旧媒体延迟字体不污染当前媒体；旧轨道晚到格式的 JVM 用例。
- 附件夹具动态把 AVD 的 Noto Serif 系统字体加到 MKV（数据先于名称/MIME），走真实 extractor → native 注册，对比实际字形；系统字体二进制不加入仓库。同媒体字体保留、媒体重置后恢复 fallback。
- JNI/EGL 的显隐、透明清屏、内外轨释放/恢复、同时间重绘、重建、错误文件、大时间戳、旧版本拒绝，以及非方形像素/旋转/屏幕方向垂直偏移的像素检查。
- mpv 原生内外字幕、真实取消/失败、视频/OSD 双 Surface、HTTP 头/输入错误；Media3 初始化回退与实际 decoder 生命周期。
- 应用真实 ASS/原生 OSD Surface、中文字幕与弹幕层级、DPAD 面板/暂停继续/设置返回；Media3 非零暂停时视频及字幕 Surface 同时销毁重建后，两个 Surface 的 PixelCopy 都有实际像素且位置保持。

### 初始失败与修正

1. `.tmp/media3-libass-red.log` 确实复现旧 `1e12` 猜测错误：期望 `2000000400000`，实际 `400000`，**BUILD FAILED**。新映射不按量级调整真实大时间戳。
2. 初次设备 14 项中两项测试准备错误：裁剪用例错误等待暂停期间 decoder flush，改为恢复后验证；ConcatenatingMediaSource2 的 progressive 子源需显式 5000ms placeholder duration。修正后 `media3-libass-instrument-2.log` 为 **OK (14 tests)**。后续另补真实视频输出测试，不用该早期结果覆盖最终改动。
3. `media3-libass-scoped-build.log` 编译失败，因为 Media3 Matroska 的 `release` 为 final；删除该尝试，保留 binary IO 失败及 seek 时释放 pending reservation。源释放后 input 随 source 清理，未为 no-op release 增加代理层。
4. 范围格式首轮失败，包含两个不可自动修正的单行 if；格式工具已展开并在后续重跑通过，未关闭规则。
5. 全量首轮 `media3-libass-validation-final.log` 为 **BUILD FAILED in 3m 29s**：新测试 `replacedDecoder_cannotReclaimTrackWithALateFormat` 复现旧格式抢回新轨道。增加 decoder 代际，并避免支持探测创建输入所有者；全量第二轮 **BUILD SUCCESSFUL in 3m 20s**。
6. 应用第一轮 1/2 通过，Media3 初始窗口无焦点；测试启动前向错误包名授权，后续改为实际 instrumentation target。第二轮进入真实字幕后，双 Surface 重建失败：日志明确显示 `RenderSurfaceView.surfaceDestroyed` 未解绑视频，MediaCodec 向 obsolete surface 输出后报 `ERROR_CODE_DECODING_FAILED`。补所有者匹配的 Media3 解绑；第三轮两个应用用例通过，包括两层实际像素及暂停位置检查。没有改断言或加入重试恢复来掩盖 decoder 错误。
7. Surface 修复后的全量第三轮 **BUILD FAILED in 1m 5s**，lint 指出新 Media3 调用缺失显式 opt-in。已局部标注，第四轮全量检查通过。实际失败日志均保留。

## 文档与差异检查

维护说明、设计状态、导航、审查矩阵及受影响 memory 已同步并读回。8 份变更 Markdown 的本地链接/UTF-8 检查为 **0 errors**，新增未跟踪文本也检查了尾随空白；普通 `git diff --check` 原生退出码为 **0**。Git 的 LF/CRLF 提示与 PowerShell 的 stderr 状态单独保留，不通过全文件换行转换处理。原有暂存文档和四项技能删除保持在暂存区，本轮变更未暂存或提交。最后一轮代码验证后仅补文档与测试证据，未重复执行已通过且输入未变的完整构建。

## 剩余边界

本轮证明基础管线及所列 API 34 AVD/JVM 行为，不代表物理 Android 11 TV 的 PQ/HDR、DRM、不同 SoC/GLES 2、完整字体/位图/复杂 ASS 动画/卡拉 OK、长期内存或高负载节流已经验收。固定裁剪、多 period 和 seek 已有真实视频回归，动态移动的直播 window 时间线未专门验证。当前预算为明确保护边界，尚未由代表性电视样本定型；默认没有降采样。实际呈现时间、跨 Surface 同刷新同步和性能提升没有测量结论。后续启用精确调度/atlas/多线程前仍须按设计取得同机数据。

没有提交、合并或发布。保留进入任务时用户已有的暂存文档和技能删除改动。
