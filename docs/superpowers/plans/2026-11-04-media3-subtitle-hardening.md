# Media3 字幕逻辑完善

**状态：本地实施与验证完成（2026-11-04）；原 115 视频的无字幕问题仍未关闭，实体电视/用户验收未执行。用户已明确授权本轮实施，禁止操作实体电视的约束继续有效。**

目标：排查当前设计不成熟之处，综合成熟项目做法完善自身；参考项目提供证据，不要求复制其实现。此前内嵌 ASS 无字幕的[诊断记录](../../../document/player/subtitle-rendering.md#media3-embedded-ass-diagnosis)保留，真实 115 视频根因尚未确认。本方案不把一项兼容性修复等同于已解决该片源问题。

## 问题与证据分级

| 现象或设计问题 | 已有证据 | 处理优先级 |
| --- | --- | --- |
| CHS/CHT 丢失区分，均显示“中文” | 真实日志和 `Media3VideoPlayer.getTracks()` 确认使用语言优先的默认命名 | 直接修复，保留标签语义及必要的角色信息 |
| 应用为 libass 合成了 Media3 专用 Events 格式 | 源码确认；桌面 libass 对照中错误格式没有像素，标准/原生回退格式有像素；JVM 也出现预期失败 | 直接修复数据契约；本次视频未命中此分支，继续单独记录 |
| 查询能力可能获取新 decoder sink，推进轨道代际 | `hasSink()` 调用有副作用的 provider；短暂 JVM 探针确认能力查询实际获取了 sink。source-tag 路径与 fallback 路径要分别验证 | 拆分“查询”和“获取”，以活动 decoder 不失效为验收 |
| 重复绑定同一 sink 会重传头部、重放样本 | 源码及短暂 JVM 探针确认同一样本被再发一次 | 使重复绑定幂等，保留真正重建时的缓存重放 |
| 日志只证明转发，无法证明 libass 接受了事件 | 本片日志有样本、对齐的时间和 Active/Rendered，但缺 native 事件数及正文长度 | 增加分层结构诊断，作为定位本片及后续兼容问题的基础 |
| 字体附件重同步、释放预算、异常格式边界 | 后续真实 EBML 用例复现重同步漏附件和中断后预算未归还；未证实属于原片根因 | 已按复现修复，保留现有提取器与预算机制 |

这些问题反映的薄弱点是：不同阶段的数据格式混用、只读查询与所有权变更耦合、重复绑定语义不清、成功状态不区分解析与绘制，以及部分测试只检查字符串拼接。方案围绕这些边界完善，不扩展成整套播放器重构。

## 完成标准与约束

- 修正已证实的字幕数据契约、状态所有权和命名问题；保留当前 Media3/libass 分工及普通 Cue、mpv、外挂入口和 TV 交互。
- 仅在 decoder 边界选择真实 codec private；由 libass 的 codec-private API 负责原生事件格式回退，移除应用重复且错误的事件格式合成。尾部 NUL 规范化不改写正文或自定义 Events 格式。
- 能力查询只读，decoder 创建才获取所有权；重复绑定同一 sink 不清轨或重放。字体附件延续当前媒体预算和公开 EBML 扩展点。
- native 诊断报告实际事件/样式/正文长度等结构信息，避免“数据已转发”等同于“解析成功”，不记录对白正文。
- 不操作实体电视，不恢复其设置，不要求用户下载片源。设备验证只用任务专属模拟器与专用 adb 服务；当前电视 DEBUG 尚未恢复这一事实仍保留。
- 不升级依赖、不引入另一套解复用器、不采用参考项目的私有反射、不重做播放器 UI、不提交用户暂存内容。

## 目标职责与行为契约

1. **提取与媒体所有权**：继续由 Media3 提取容器，每个 source 的 `Media3SubtitleInput` 管理自己的字体和输入代际。能力查询只读取可用性，创建 decoder 时一次性获取该代际的 sink；旧媒体/旧轨回调不能影响新输入。
2. **ASS 数据边界**：decoder 只负责选取真实 codec private、去掉尾部 NUL、移除 Media3 时间包装并传递时间/时长。保留原始 UTF-8、逗号、ASS 特效与自定义格式，不凭字节长度猜测截断或自动改写正文。缺省事件格式由已使用的 `ass_process_codec_private` 统一回退；同时检查没有初始化数据时 native 是否仍完成默认初始化。
3. **轨道与显示分离**：名称使用明确标签和语言/角色信息，CHS/CHT 显示为简体/繁体。选轨沿用稳定轨道 ID；“关”只控制可见性，恢复显示仍使用实际选择。相同名称仍应可区分，不能依赖名称作为轨道身份。
4. **生命周期幂等**：同一 sink 的重复绑定不清轨、不重放；真正重建输入绑定才恢复缓存。外挂替换 native 轨道后的恢复显式先解绑再绑定，不再依赖重复绑定的隐式副作用。seek、轨道更换、媒体切换、外挂加载/失败/移除、显示隐藏和 Surface 重建分别按既有契约处理，保持字体的媒体作用域。
5. **分层诊断**：分开记录输入包的字节/分隔符/NUL 位置、native 样式及事件数/正文长度、实际字幕图像或像素输出。采用现有采样与日志基础设施，保持有界开销，不打印对白或链接凭据。合法空事件、重复事件、解析失败和当前时刻无字幕应能区分；不能把所有“没有像素”都当错误。

## 实施与验证

| 顺序 | 结果 | 主要位置 | 验证 |
| --- | --- | --- | --- |
| 1 | 真实 ASS 头部保持原始格式，去尾部 NUL，缺格式交给 libass 回退 | LibassSsaStreamDecoder；native init | JVM 原字节/特殊格式；缺 Events/有 padding 的实际 native 像素；已有桌面反例 |
| 2 | 能力查询与输入所有权分开；重复绑定幂等 | LibassSubtitleDecoderFactory、LibassAwareRenderersFactory、Media3VideoPlayer、Media3SubtitleInput | 查询不获取 sink、活动 decoder 不失效、晚到旧轨无效、重绑不清除样本 |
| 3 | 字幕名称保留原始标签、角色及强制标记，区分 CHS/CHT | Media3 字幕命名；现有 getTracks | JVM CHS/CHT、标签、空标签、角色、语言回退 |
| 4 | 附件边界与可诊断性补齐 | LibassMatroskaExtractor、AssGpuNativeBridge、ass_gpu_bridge.cpp、AssGpuRenderer | 附件层级/释放预算；native 事件数、正文长度与像素对应；不包含正文 |
| 5 | 验证既有字幕生命周期与真实 UI | 现有 Media3SubtitleCompatibilityTest、LibassGpuRenderingTest、TV 回归 | 构建、Debug JVM、lint、定域 Kotlin、模块治理；独立模拟器 ASS/Cue、seek/切轨/字体/显隐及 DPAD 冒烟 |

上述第 1～3 项以已证实行为为首批实施范围；第 4 项中的附件改动先通过复现确定必要性。每批独立验证，不叠加无法归因的兼容性猜测。实现若涉及更大范围的职责调整，先更新方案说明理由。

## 验证矩阵与完成判定

| 层级 | 必须覆盖 | 能证明的结果 |
| --- | --- | --- |
| JVM | 原始/单块/双块 ASS 头部、缺 Events、多尾部 NUL、自定义事件格式；标签/语言/强制与辅助字幕；查询幂等、重复绑定、过期回调、预加载输入隔离 | 数据保持与状态转移正确；不能替代 native 渲染 |
| Native 像素 | 实际使用应用 decoder 处理后的头部/样本，经过 JNI/libass；缺 Events/有 padding/中文/特效；空及无效事件 | 解析结果与真实可见像素对应，能检出原错误格式；不依赖 Rendered 标志判通过 |
| Media3 集成 | MKV 内嵌 ASS 与普通 Cue、字幕切换/关闭/恢复、seek 前后、暂停与 Surface 重建、外挂成功/失败恢复、附件字体与换媒体隔离 | 真实提取/decoder/渲染协作与生命周期契约成立 |
| TV UI 回归 | 独立模拟器仅 DPAD 进入播放器、打开字幕列表、选择/关闭、返回、退出；同时检查设置持久化、异常恢复与返回栈 | 本项目菜单和交互保持可达、可见、可返回；不替代实体电视验收 |
| 工程检查 | 与改动匹配的构建、`testDebugUnitTest`、`lint`、定域 ktlint 和模块依赖治理 | 必须阅读 Gradle 输出尾部，分开报告成功、既有失败及未执行项 |

不能以“与参考源码一致”、JVM 通过或构造样本显示代替原片源验收。若实施后仍没有实际 115 片源证据，完成报告必须单列该问题尚未关闭；不为补证据擅自占用电视。

参考已核对的 Plezy 固定提交 `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5`（`android/libass/.../media/AssHandler.kt`、`parser/AssHeaderParser.kt`、`text/AssTrackOutput.kt`）、peerless2012/libass-android、Media3 `1.11.1` 及 libass `0.17.3` 的公开契约。采用原生 codec-private 回退，是根据本项目使用的 API 做出的本地设计；Plezy 的 readBuffer 入口需要自行补 Events，二者不能直接照搬。

- [Plezy 头部规范化](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/parser/AssHeaderParser.kt)：参考其保留原头部、清理 padding 的意图，根据本项目 native 入口收敛处理。
- [Plezy 轨道与输入管理](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/AssHandler.kt)：参考媒体/轨道/字体生命周期；保留本项目已有 source 预加载隔离。
- [Media3 提取实现](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)、[libass 解析实现](https://github.com/libass/libass/blob/0.17.3/libass/ass.c)：作为输入和 native 解析契约依据，避免靠参考项目的私有反射补丁维持功能。

## 前期证据

- 方案于 2026-11-04 获得继续实施授权，当前结果见下方实施记录。
- 既有桌面构造样本证明错误合成格式无像素、原始头部/native 标准回退有像素；不证明本次实际视频已经修好。
- 用户要求停止实施前，曾加入短暂 JVM 探针并运行 `:player_component:testDebugUnitTest` 的三个定域测试类。日志在中止前记录头部处理三项、查询副作用一项、重复绑定一项预期失败；任务随后被主动终止，没有 Gradle 最终 BUILD 结论，不能算完成的测试运行。日志保留在 `.tmp/media3-subtitle-hardening/red-tests.log`。
- 方案阶段的上述临时探针已撤回，当次模拟器和专用 adb 已关闭；本轮获授权后重新添加正式回归并启用任务模拟器。没有实体电视操作，用户已有暂存内容保持原样。

<a id="subtitle-hardening-2026-11-04"></a>

## 2026-11-04 实施记录

- 已移除错误的 Events 格式合成，保留真实头部并清理尾部 NUL；无头部时也调用 native codec-private 回退。
- 能力查询与 sink 获取分离，同对象绑定幂等；外挂恢复使用明确的解绑/重绑重放缓存。
- CHS/CHT 显示为简体/繁体，保留其他原始标签、语言、角色及强制标记；重名加序号，轨道 ID 与选择保持不变。
- 真实 EBML 测试复现附件重同步和中断后的预算泄漏。Media3 的 `release()` 为 final，释放清理放在外层 `Extractor` 委托包装中；附件解析仍由原 Matroska 扩展处理。
- 增加采样式 native 事件/样式/正文长度及 ASS 图像计数；重复查询最多检查最近 4096 个事件，无法精确判断时报告 `UNCHANGED`。关闭详细 libass 日志，避免输出对白正文；图像计数不等于实际可见像素。native 或本次请求关闭指标时均报告 -1，避免把未采集误判为零图像。

### 工程验证

基于 `16329498458d22e26454b65f869fa261fc025397` 及本轮未提交源码/测试改动。进程局部设置短 JVM 临时路径，沿用 `.tmp/handoff14-local.gradle` 的离线 Robolectric 配置；没有修改默认包装脚本或依赖。定域格式脚本只覆盖本轮涉及的 Kotlin 文件。

```powershell
& 'scripts/dev-env/gradlew-local.bat' testDebugUnitTest `
  :player_component:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest `
  :player_component:lintDebug :app:lintDebug :player_component:ktlintCheck verifyModuleDependencies `
  -I '.tmp/handoff14-local.gradle' -I '.tmp/media3-subtitle-hardening/scoped-format.gradle' `
  --offline --continue --console=plain
```

已阅读最终日志 `.tmp/media3-subtitle-hardening/validation-delivery.log` 尾部：**`BUILD SUCCESSFUL in 3m`**，1108 个任务，47 个执行、1061 个保持最新。

| 检查 | 结果与边界 |
| --- | --- |
| Debug JVM | 533 项声明、0 失败/错误，2 项既有 FTP/SMB 环境集成用例跳过；播放器 175 项全部通过，其中本轮相关 5 类 32 项通过。其他未变模块复用本轮先前成功结果 |
| APK/native | 应用 Debug、两个测试 APK 构建通过；ARM64/ARM32 native 均编译。实施验证阶段只在任务模拟器安装 Debug；后续另行授权的 Release 打包见下节 |
| 静态检查 | 播放器和应用 Debug lint、定域 ktlint、模块依赖治理通过；未宣称全局 ktlint 或其他构建变体的 lint 通过 |
| 格式 | 定域 `ktlintFormat --max-workers=1` 为 `BUILD SUCCESSFUL`，后续修改通过定域检查 |

### 模拟器与行为验证

仅使用任务 AVD `Pixel_5_API_34` / `emulator-5574`、私有 adb 5057，1440×810 / 240dpi，ARM64 原生库经模拟器运行。头文件/契约参考为 libass `0.17.3`；实际运行日志报告现有二进制 API `0x1704000`、source `0.17.4-0-gbbb3c7f1570a4a021e52683f3fbdf74fe492ae84-dirty`，本轮没有更换二进制。

| 检查 | 结果与证据 |
| --- | --- |
| Native 与 Media3 集成 | `LibassGpuRenderingTest` + `Media3SubtitleCompatibilityTest` 共 **19 项通过**；最终日志 `instrument-player-delivery.log` 为 `OK (19 tests)` |
| 实际像素 | 应用 decoder 处理的缺 Events、尾 NUL、单块/无/空头部共 6 组中文/ASS 标签/逗号样本；旧错误格式控制反例无像素。真实 MKV → decoder → GPU 在 0.5s 有像素、1s 结束边界为空，seek、外挂成功/失败恢复、暂停显隐、Surface 重建通过 |
| 状态与兼容 | 普通 Cue、裁剪/多 period/倍速/前后 seek、预加载媒体隔离、旧字体隔离、实际附件字体改变字形、64 位时间与失败清屏均通过；未采集指标为 -1 的补充断言通过 |
| 应用 DPAD | 两项 `TvDpadRewriteInstrumentedTest` 的 Media3/mpv 面板、暂停恢复、设置返回通过，见 `instrument-app-permissions.log` 对应成功项；`subtitleQuickWorksWithBothKernelsAndSidebarContainsOnlyDetails` 补齐既有背景夹具后为 `OK (1 test)`，见 `instrument-app-quick.log`。合计 3 个独立用例通过，覆盖真实 SRT、选择/Off/重开、选中保留、侧栏职责与返回栈 |
| 最终包冒烟 | 重新安装最终 Debug ARM64；冷启动 → 首页 → 媒体库 → BACK 回到导航 → 设置 → BACK 退出，均使用 DPAD。实际查看了首页/媒体库/设置截图，焦点可见；退出读回为系统 launcher，证据 `smoke-*.xml/png/txt` |
| 日志边界 | 最终日志包含 `EmbeddedSampleDiagnostics`、非零 `assImages` 和合法零图像帧；未包含测试正文 `Private subtitle sentinel`、verbose `Event at` 或完整 `[Script Info]`。结果见 `log-structure-check.json`，此检查针对本轮样本，不等于对所有第三方日志作保证 |

上述日志、截图和状态文件均在本地被忽略的 `.tmp/media3-subtitle-hardening/`，不加入 Git。任务模拟器进程及私有 adb 监听已于 **10:31:30** 确认关闭，见 `cleanup-status.json`。用户原有暂存区保持不变，本轮未 stage、commit 或发布。

### 失败与修正记录

- 有效行为红灯：`red-behavior.log` 为 27 项中 11 项失败、`BUILD FAILED`；`green-core-red-attachments.log` 仅剩两个附件用例失败。最终相关用例均通过。首次受保护方法探针编译失败不算行为复现。
- 首轮工程验证因试图覆盖上游 final `release()` 和新增测试格式失败，`validation.log` 明确为 `BUILD FAILED`。已改为 `Extractor` 委托释放并定域格式化；初次 Windows 文件映射锁在单 worker 重试后解除。
- 首轮 19 项仪器测试的新增集成用例误在 1s 字幕结束边界断言可见；native 已正确接收事件并返回零图像。改为 0.5s 验证可见，同时保留 1s 透明断言，后续通过，没有改写媒体时间逻辑。
- 追加关闭指标用例复现期望 -1、实际 0（`instrument-diagnostic-red.log`）；修正复用指标缓冲区的未知值初始化后，最终 19 项通过。
- 应用回归首次缺媒体读取权限，补齐后仅剩缺少既有 `cinema-frame.jpg` 夹具；补齐夹具后通过。没有为通过测试修改生产 UI、删除断言或操作实体电视。

### 仍未关闭的事项

原 115 视频没有修复后的提取/解析/像素证据；其日志头部未命中旧错误合成分支，不能将本轮兼容性修复当作已确认原片根因。实体电视、复杂字幕性能和原片最终验收均未执行。此前电视 DEBUG 尚未恢复的事实保留，不为恢复设置连接设备；后续如重新排查原片，可用新增结构诊断缩小范围。

<a id="release-427-2026-11-04"></a>

## Release 4.2.7 打包（2026-11-04）

用户另行要求版本号加一并打包 Release。`buildSrc/src/main/java/Versions.kt` 从 `4.2.6 / 73` 更新为 **`4.2.7 / 74`**，包含上文已验证的字幕完善代码；本阶段没有新增功能修改或设备操作。

沿用进程局部 JVM 临时目录配置执行：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=E:\Project\DDPlayTV\.tmp\wh-jvm -Djava.io.tmpdir=E:\Project\DDPlayTV\.tmp\wh-jvm'
& 'scripts/dev-env/gradlew-local.bat' :app:assembleRelease --no-daemon --offline --console=plain --stacktrace
```

已阅读 `.tmp/release-4.2.7/assemble-release.log` 尾部：**`BUILD SUCCESSFUL in 4m 24s`**，689 个任务，687 个执行、2 个保持最新。包含 ARM64/ARM32 原生编译及 `lintVitalRelease`，没有跳过 Release 必要检查；未重新运行此前通过的 JVM/仪器回归。

三个 APK 均通过实际 Manifest 版本/包名、非 debuggable、原生 ABI、`apksigner verify --verbose --print-certs` 和 `zipalign -c 4` 校验。证书 SHA-256 为 `e1b82b2732cb93db0c809d21e2f0c90ef3bdd4958c2fc89ef217c082a070cdc6`，与打包前实际核验的 `4.2.6` ARM64 Release 一致。完整校验记录在 `.tmp/release-4.2.7/release-verification.json`，校验时间 **12:38:33 +08:00**。

产物目录为 `app/build/outputs/apk/release/`：

| APK | 字节数 | SHA-256 |
| --- | --- | --- |
| `ddplaytv_v4.2.7_arm64-v8a-release.apk` | 68362068 | `45b10622750566e5e71fe83d1640ea5ad5cb3af13db98acbd8d0a54d3412556c` |
| `ddplaytv_v4.2.7_armeabi-v7a-release.apk` | 64598411 | `b34bd4caf08483090715adb925f1330377263d7995c40cc9bc09054d6ff0f4d0` |
| `ddplaytv_v4.2.7_universal-release.apk` | 102431914 | `afbc04128ae36cdd1fe6c87ba664792e59dd1f25591b2b4e26484114c9de9af4` |

本次只完成本地打包，未安装、发布或提交，保留用户原有暂存区。原 115 视频和实体电视验收状态不因 Release 构建成功而改变。
