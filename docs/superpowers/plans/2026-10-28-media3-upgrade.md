# Media3 1.11.1 升级适配

## 目标与范围

用户于 2026-10-28 授权从 Media3 1.8.0 升级至最新稳定版并完成适配。Google Maven 元数据已确认稳定版 1.11.1；1.12.0-alpha02 属于预览版，不在范围内。不提交 Git commit。

保留当前 Media3/VLC/mpv 入口、播放接力、TV DPAD 和 libass 字幕行为。用户已知并接受新版本的最低 Android 6.0 要求。只调整完成升级所需的工具链、接口和回归问题，不新增章节菜单等功能。

## 已核实的依赖要求

- 官方 1.11.1 AAR 的 `aar-metadata.properties` 要求 compileSdk 36；POM 引入 Kotlin stdlib 2.2.10。原始发布包/POM 保存在忽略目录 `.tmp/media3-upgrade/`。
- 采用 Kotlin 2.2.10、AGP 8.10.1、Gradle 8.11.1、compileSdk 36、minSdk 23。targetSdk 保持 35，JDK 17、JVM target 1.8 和 NDK 保持不变。
- `gradle.properties` 是 Media3 版本的唯一声明；播放器依赖和诊断 BuildConfig 使用相同属性。
- 官方依据：[Media3 发布记录](https://developer.android.com/jetpack/androidx/releases/media3)、[AGP 8.10 兼容表](https://developer.android.com/build/releases/agp-8-10-0-release-notes)、[Kotlin Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)。

## 执行与验证

- [x] 升级构建配置，安装项目私有 SDK 36，确认依赖解析和 AAR 元数据检查。
- [x] 适配生产与测试源码；重点检查自定义 RenderersFactory、旧式字幕解码/提取开关、MediaSession 和轨道选择。
- [x] Debug/Release 和测试 APK 构建、全量 JVM 测试、lint、模块与播放边界检查。
- [x] 可用 AVD 上验证 Media3 播放、seek、接力/恢复、字幕和纯 DPAD 主路径；恢复测试配置。
- [x] 核对差异并更新项目开发文档与记忆，准确保留尚未验证的设备/媒体场景。

## 验证记录

开始前工作区干净；历史 VLC 即时 seek 快照测试超时已有记录，不能算作本次通过。

- 项目私有 SDK 已安装 Android 36，`android.jar` 存在。Gradle 8.11.1 官方分发包 SHA-256 为 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`，已验证并固定在 Wrapper 配置中。
- 首轮 `:player_component:compileDebugKotlin :app:compileDebugKotlin`：**BUILD FAILED**。buildSrc 的内置 Kotlin 2.0.20 无法读取 2.2 元数据，且 `KotlinJvmOptions` 已升级为编译错误。已显式指定 buildSrc Kotlin 编译器并迁移到 `compilerOptions`；继续验证。
- 已新增 `Media3SubtitleCompatibilityTest`，使用真实 MKV/ASS 提取和渲染器，覆盖 libass 数据桥的前后 seek、时间戳/时长，以及无 libass sink 时的 Media3 cue 输出。此测试不证明 GPU 字幕最终像素或硬件视频解码。
- 第二轮编译因 Moshi 1.15.1 的 KAPT 元数据读取器不支持 Kotlin 2.2 而 **BUILD FAILED**；仅把 `data_component`、`core_storage_component` 的 Moshi codegen 切到 KSP，保留 ARouter/MMKV/DataBinding 的 KAPT。
- 第三轮使用 KSP2 时，自定义 `@JsonQualifier` 触发 Moshi 的已知反射异常，**BUILD FAILED**。按 [Moshi #1874](https://github.com/square/moshi/issues/1874) 使用 KSP `2.2.10-2.0.2` 的 KSP1 兼容实现；不引入 Moshi 2 预览版。
- 第四轮 Moshi 已通过，Room 2.6.1 的 KAPT 处理器因只支持到 2.0 的元数据而 **BUILD FAILED**。按 [Android 官方 KSP 迁移说明](https://developer.android.com/build/migrate-to-ksp) 将 Room 处理器和原有参数迁到 KSP。
- 第五轮 Room 报两个带 `@IgnoredOnParcel` 的跨模块实体 `MissingType`，**BUILD FAILED**。为数据库模块增加 `compileOnly(kotlin("parcelize-runtime"))` 后，`:core_database_component:kspDebugKotlin --offline --console=plain --stacktrace` **BUILD SUCCESSFUL in 9s**（`room-ksp.log`）；导出的 schema 无差异，Room 运行库保持 2.6.1。
- 第六轮因下载 Lifecycle 2.9.4 时 TLS 连接失败而 **BUILD FAILED**；改用有网络权限的独立 Gradle 进程继续验证，不降低 TLS 校验。
- 新增 `MoshiCodegenCompatibilityTest`，覆盖生成适配器的 JSON 字段别名、默认值、字符串/数字 null qualifier，以及空数组/对象响应兼容性。
- 第七轮主工程编译 **BUILD SUCCESSFUL in 1m 51s**（`compile-7.log`）。现有 Media3 播放器、自定义音视频/字幕 RenderersFactory 和桥接接口可继续使用，无需修改生产播放器逻辑。
- 完整验证的初始本地 init 脚本未跳过 buildSrc，随后修正；第一轮综合检查的失败包括 Robolectric 下载 TLS 错误、新增字幕测试的 ktlint 格式，以及本地依赖核验脚本的 AGP 变体选择方式。后两者已修正。离线重试发现 Gradle 新缓存缺少测试依赖映射；从 Maven Central 下载/核验官方 SHA-1 后，将六个已有精确版本放入忽略目录的临时 Maven 仓库，用 `resolver-cache.gradle` 仅覆盖这些测试依赖，不修改正式仓库配置或关闭校验。
- 已核对 Debug/Release 的 app 与 player 运行时 Media3 组件全部解析为 1.11.1，Kotlin stdlib 为 2.2.10，没有恢复生产 `media3-effect`。生成的 `MEDIA3_VERSION` 为 1.11.1。ARM64 Debug APK 的 aapt 结果为 compileSdk 36 / minSdk 23 / targetSdk 35。
- 完整 lint 发现 Media3 1.11.1 的 `experimentalSetLegacyDecodingEnabled` 新增 `ExperimentalApi` opt-in 要求，已在 `buildTextRenderers` 方法局部声明，保留 libass 原始 ASS 解码路径。另修正忽略文件 `local.properties` 中 SDK 路径冒号的转义，并单独重跑缓存的 anime lint 分析；没有关闭 lint 规则。
- TV 模式 API 34 AVD 上，`Media3SubtitleCompatibilityTest` **OK (2 tests)**（`subtitle-device-1.log`，1.817 秒）。首次安装因旧的同名测试包签名不同而失败，卸载的仅为 `com.xyoye.player_component.test` 测试包，正式 Debug 应用保留数据更新。
- 第一组设备回归与完整构建并行时，即时 seek 快照超时，并出现解码器/Surface 错误；DPAD 面板用例长时间等待空闲后通过人工 BACK 解除等待，因此该轮不能视为通过（9 项、2 失败，`playback-device-1.log`）。停止并行构建后，两个用例分别 **OK (1 test)**；未放宽断言或改动播放器逻辑。并行负载是观察到的条件差异，未证明是唯一原因。
- 随后在无并行构建条件下，完整所选 TV 回归 **OK (9 tests)**（`playback-device-2.log`，35.499 秒）：Media3/mpv 即时 seek 快照、加密请求重入/重建/BACK、内核重试、设置切换/持久化/分类和主页/播放器 DPAD。没有重跑历史已知失败的 VLC 即时 seek 快照用例，不宣称全设备或所有内核场景通过。
- 诊断使用 `ddplaytv-http-logs`：配置的 direct-http 服务不可达，`/api/v1/logs/segments` 等 HTTP 日志未取得；`emulator-5558` 为 `device`，run-as 兜底也没有 `files/http_logs`。输出仅有 `.tmp/media3-upgrade/http-logs/20261028-220946/collection-info.txt`。实际诊断依据是定向 logcat 与测试结果，保存在 `playback-logcat-*.log`、`device-final-logcat.log`；没有清空历史日志。
- MKV 夹具由同目录的 `.ass` 和 FFmpeg 合成黑色视频生成，无外部版权素材。重建命令：

```text
ffmpeg -f lavfi -i color=c=black:s=64x64:r=2:d=5 -i subtitle-compatibility.ass -map 0:v -map 1:s -c:v libx264 -preset ultrafast -g 2 -pix_fmt yuv420p -c:s ass -disposition:s:0 default -y subtitle-compatibility.mkv
```

## 最终构建与验证

2026-10-28 最终命令如下，日志为 `.tmp/media3-upgrade/validation-7.log`，结尾 **BUILD SUCCESSFUL in 1m 48s**。init 脚本仅提供本机离线测试缓存、变更文件 ktlint 范围和依赖版本核验，正式构建脚本不依赖这些文件。

```text
scripts/dev-env/gradlew-local.bat :anime_component:lintAnalyzeDebug --rerun :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :player_component:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck verifyMedia3UpgradeVersions -I .tmp/handoff14-local.gradle -I .tmp/media3-upgrade/style.gradle -I .tmp/media3-upgrade/dependencies.gradle -I .tmp/media3-upgrade/resolver-cache.gradle --offline --max-workers=4 --continue --console=plain
```

- 按当前 `settings.gradle.kts` 模块汇总 JVM XML：381 项，**379 通过，0 失败，0 错误，2 跳过**。跳过的是外部 FTP/SMB fixture；包含 3 项新增 Moshi 兼容测试、5 项数据库迁移/结构测试。明细为 `unit-counts.json`。
- 全量 lint、模块和播放边界检查通过；ktlint 仅覆盖本次变更的 Kotlin 源文件，未把全仓历史格式问题算作通过。`git diff --check` 通过，Room schema 无差异。
- Debug、Release、app/player instrumentation APK 均构建通过。Release 沿用原有配置：未启用 minify，缺少正式签名凭证时回退 debug 签名，不是正式签名发行产物。
- 移除本任务 TV feature 文件，恢复 1080×2340 / 440 dpi；读回为 `device`、`sys.boot_completed=1`、手机 UI mode `0x11`、非 root `uid=2000(shell)`。保留测试前已禁用的 SystemUI、锁屏等状态。证据为 `device-baseline.log` 和 `device-restored.log`。
- 恢复手机模式后，安装最终 Debug/app-test APK，`AppFeaturePageSmokeTest` **OK (5 tests)**（`mobile-device.log`，48.369 秒），覆盖主栏目与设置、搜索、本地和存储页面。连同字幕 2 项和 TV 9 项，本次有 16 项不同的设备测试通过；这些是 API 34 AVD 证据。
- buildSrc 显式 Kotlin 2.2.10 与 Gradle 内置 kotlin-dsl 2.0.20 的版本警告、既有 compiler-embeddable 构建类路径警告仍可见，未抑制；本次编译和治理任务已通过。
- 未验证实体电视、Android 6/16 真机、完整 HDR/DRM/网络媒体矩阵或最终 GPU 字幕像素。历史 VLC 即时 seek 快照问题保留，不属于本次已修复结果。未提交 Git。
