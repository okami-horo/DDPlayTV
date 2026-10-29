# mpv 原生字幕接入

## 目标与边界

按用户 2026-10-29 的决定，由固定版本 Plezy AAR 内的 mpv 直接解析、计时和渲染内嵌/外挂字幕。保留 `SurfaceView + vo=mediacodec + hwdec=mediacodec`，视频不经过应用 GPU；独立透明 OSD Surface 位于视频上方、控制器/弹幕下方。Media3 保留现有 libass 链路。

参考 Plezy `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5` 的 `MpvPlayerCore.kt`、`shared/PlayerSurfaceHost.kt`，以及已固定 `mpv-build` 的 `MpvPlayer.attachSurfaces` 和 `0100-vo-mediacodec-timed-surface-with-osd-plane.patch`。只复用 AAR 公共接口，不引入自有 mpv JNI 或升级依赖。

字幕显示开关映射 `sub-visibility`，时间偏移映射反号 `sub-delay`，字号偏移映射 `sub-scale`，垂直偏移映射 `sub-pos`。保持 ASS 样式及容器附件字体；复杂定位/位图的缩放与位置遵循 mpv 本身能力。mpv 没有等价全局透明度系数，mpv 模式隐藏该控制，不修改 Media3 保存值。

## 实施与验收

- [x] Java ABI 和 native bridge 管理视频/OSD 双 Surface 生命周期、独立 generation，保留暂停位置恢复。
- [x] mpv 专用字幕后端接入已有外挂轨道管理和设置；异步加载以实际 native 结果为准，取消/切轨/释放不遗留旧字幕。
- [x] 移除 mpv ASS 快照解析/转交链及旧位图限制；保留 Media3 共享设施。
- [x] 原生像素测试验证内嵌 ASS、外挂 SRT/ASS、隐藏/恢复、偏移、暂停 seek、双 Surface 重建、切轨及失败清理；必要 JVM 回归。
- [x] 构建、JVM、lint、范围格式、模块/播放边界检查；AVD 纯 DPAD 播放/设置/退出，检查字幕与弹幕/控制器层级。
- [x] 更新维护说明、旧设计适用范围及 memory；记录真实验证结果与设备限制。

## 验证记录

生产接入与本地验证完成。依赖版本、AAR 字节和视频输出策略不变；未提交或发布。

### 构建与自动检查

- `scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :player_component:assembleDebugAndroidTest testDebugUnitTest lint ktlintCheck verifyModuleDependencies verifyPlaybackBoundaries -I .tmp/handoff14-local.gradle -I .tmp/mpv-native-subtitles/style.gradle --offline --console=plain`：`.tmp/mpv-native-subtitles/verification-1.log` 结尾 **BUILD SUCCESSFUL in 4m 31s**。
- JVM XML 汇总：388 项，**386 通过、0 failures/errors、2 外部协议夹具跳过**，见 `.tmp/mpv-native-subtitles/jvm-counts.json`。原 mpv ASS parser 的七个用例随被替代的代码删除；本轮改用真实原生 OSD 和应用像素检查。没有新增只镜像参数实现的 JVM 测试。
- 改动范围 ktlint format：`format-1.log`，**BUILD SUCCESSFUL in 10s**。最后新增应用层外挂/像素检查后，`app-format.log` 为 **BUILD SUCCESSFUL in 6s**；补跑 `:app:assembleDebugAndroidTest :app:lint :app:ktlintCheck`，`app-checks.log` 为 **BUILD SUCCESSFUL in 14s**。生产代码与上述全量验证一致。
- app lint 报告 0 errors、17 warnings、1 hint；仍有上游 Kotlin 元数据分析的既有诊断，没有关闭 lint 或绕过编译元数据校验。不声称全仓库 ktlint 基线通过。

### 设备检查

- 安装实际生成的 player test APK，运行 `MpvSurfaceLifecycleTest,LibassGpuRenderingTest,Media3SubtitleCompatibilityTest`，runner 为 `com.xyoye.player_component.test/androidx.test.runner.AndroidJUnitRunner`：`native-device-final.log` 为 **OK (13 tests)**。
- mpv 新像素断言包含：内嵌 ASS 的有字幕/空档/暂停 seek、隐藏清屏与恢复、时间偏移、垂直位置、字幕禁用重选、视频/字幕双 Surface 重建；外挂 SRT/ASS、字号缩放、移除、缺失/空文件真实失败、内外轨切换，以及 HTTP 字幕原生 IO 已开始时取消后不得留下外轨。释放后的挂起加载必须返回失败，不得悬挂。
- 原 mpv 视频解码失败、HTTP 头转义、暂停窗口重建/位置/出帧/换源，以及应用 libass 和 Media3 字幕回归继续通过。
- 安装 Debug ARM64 app 和 app test APK，授予已声明的 READ_MEDIA_VIDEO/AUDIO。`app-device-1.log` 为 **OK (3 tests)**：mpv/Media3 面板导航与暂停继续、mpv seek 恢复持久化。其中 mpv 用例经真实 `VideoController -> ExternalSubtitleManager -> native OSD` 加载 SRT，PixelCopy 断言字幕 Surface 存在实际像素；检查透明度项隐藏、位置设置持久化、返回焦点及保留透明度值。
- 第一条 app 命令中的一个重入方法名不匹配，因此只执行三项，未将其算作通过；使用正确的 `PlaybackEntriesInstrumentedTest#mpvExternalIntentReentersExistingPlayer` 与 `PlayerSettingDpadNavigationTest` 补跑，`app-device-2.log` 为 **OK (5 tests)**。本轮设备共 **21 项通过**。
- 已查看 `mpv-native-subtitles.png`：实际视频、中文字幕“MPV 原生字幕测试”与顶部绿色弹幕同时显示；布局层级断言控制器/弹幕位于字幕平面上方。另有 `smoke-subtitle-style.png` 可见实际播放画面、字幕样式说明和遥控器焦点。

### 实际失败与修正

初次 `native-device-1.log` 五项中一项失败，`native-pair-2.log` 复测记录 **before=3500 / after=3500**。低帧率夹具 seek 3200ms 后实际停在下一帧 3500ms；旧断言错误地将恢复位置与请求值比较。最终先等待 playback restart，再验证重建前位置处于第二条字幕区间、重建后与该实际位置相差小于 250ms；字幕像素断言仍保留。没有为通过此测试修改视频输出或放宽实际 Surface 位置恢复要求。

### 遥控器与设备边界

纯 ADB DPAD 冒烟从导出 TvSplashActivity 开始，经“我的 -> 播放器设置 -> mpv”，回到媒体库串流历史并播放 `/sdcard/Download/ddplaytv-lease-playback-long.mp4`，MENU -> 字幕样式 -> 左右调整/还原 -> BACK 恢复到原样式卡 -> 退出回历史项。`smoke-kernel-saved.xml`、`smoke-subtitle-style.xml`、`smoke-style-return.xml`、`smoke-exit.xml` 和 `smoke-logcat.log` 记录实际状态；过程中无触摸输入。样式设置说明未被视频 Surface 遮挡。初次 launcher 后过早 dump 得到 null root，重新等 UI 稳定后获取，不作为应用失败。

任务使用 `Pixel_5_API_34` 独立只读实例 `emulator-5558`，SwiftShader、ARM64 translation、普通 UI mode 0x11；应用 UI 阶段临时 1920×1080/160dpi。结束已读回恢复到 1080×2340/440dpi、uid 2000、UI mode 0x11（`avd-restored.log`），随后核对父 PID 34304、子 PID 27680、端口与只读参数后关闭本次实例。再次检查父子进程均退出，`avd-closed.log` 设备列表为空。只读安装、权限和偏好随会话丢弃。

文档链接检查与 `git diff --check` 通过；源码扫描无旧 mpv ASS parser、snapshot 事件或桥接残留。此次没有修改 AAR，因此原生字节来源以固定校验和及上一轮打包审计为准，不将那次审计描述为本轮重新执行。

物理 Android 11 TV 的 PQ/HDR、不同 SoC、复杂 ASS 性能、全部位图格式及嵌入字体素材矩阵仍需真机/对应素材验收。本轮没有性能对比数据，不能量化性能提升。Media3 的附件字体、时钟和性能完善设计仍未实施；此次 mpv 原生接入只取代旧设计中 mpv 共用应用 libass 的前提。
