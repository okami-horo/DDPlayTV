# Media3 字幕逻辑完善

**当前状态（2026-11-08）：用户确认 115 原视频的 Media3 无字幕问题已经修复，原问题已关闭。字幕管线、功能修复及 Atlas 已完成本地实施与验证；压缩字幕局部失败容错仍是独立待办。**

<a id="original-115-user-closeout-2026-11-08"></a>

## 115 原视频问题关闭（2026-11-08）

用户明确反馈：“115 原视频的 Media3 无字幕问题已经修复”。据此关闭原问题，不再要求该片复测或继续追查根因。本次仅更新记录，没有新增构建、自动化测试或电视操作；用户未指定本次确认对应的 APK 哈希，不能据此补写交付记录或认定其他页面、格式与性能均已验收。

下文按日期保留此前失败复测、实现和验证记录，其中原片“待复测／未关闭”的表述属于当时状态，现由本节覆盖。历史调查没有确认该片压缩方式及唯一根因，关闭问题不改变这些证据边界。压缩字幕局部容错仍按[独立待办](#compressed-subtitle-error-tolerance-todo-2026-11-04)跟踪。

原目标是排查字幕设计并参考成熟项目完善实现；此前内嵌 ASS 无字幕的[诊断记录](../../../document/player/subtitle-rendering.md#media3-embedded-ass-diagnosis)继续保留。Plezy [按格式分流、统一轨道管理的方案](#plezy-subtitle-implementation-2026-11-04)已实施，取代早期“内嵌默认全部原生”的建议。

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
- 实施验证阶段不操作实体电视，不恢复其设置，不要求用户下载片源。运行验证只用任务专属模拟器与专用 adb 服务；当前电视 DEBUG 尚未恢复这一事实仍保留。后续用户单独授权的连接、安装与版本读回见末节，不扩展到导航、播放或设置操作。
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

打包阶段只完成本地产物，未安装、发布或提交，保留用户原有暂存区；随后另行授权的安装见下节。原 115 视频和实体电视运行验收状态不因 Release 构建成功而改变。

<a id="release-427-tv-install-2026-11-04"></a>

## Release 4.2.7 电视安装（2026-11-04）

用户追加要求“连接到 tv 然后安装”。以独立 ADB 端口 **5091** 连接 `192.168.31.70:5555`，确认处于 `device` 状态，为 **Hisense / VIDAA_TV**、Android 11 / API 30，支持 `arm64-v8a`。复核本地 ARM64 APK 的 SHA-256 后，执行 `adb install -r`，返回 **Success**。

安装后实际读回 **4.2.7 / 74**，电视记录更新时间 **13:50:54**。读取已安装 `base.apk` 的 SHA-256 为 `45b10622750566e5e71fe83d1640ea5ad5cb3af13db98acbd8d0a54d3412556c`，与本地 Release 一致；UID `10111`、首次安装时间 `2026-02-12 10:47:55` 均保持不变。没有清除应用数据、启动应用、导航、播放、截图或更改设置；原视频及电视运行验收仍待后续确认。

安装日志及读回证据在 `.tmp/release-4.2.7/tv-install.log`、`tv-package-before.log`、`tv-package-after.log`、`tv-installed-apk.sha256` 和 `tv-install-verification.json`。本次连接已断开，私有 ADB 服务已于 **13:52:03 +08:00** 关闭并确认 5091 无监听，见 `tv-cleanup-verification.json`；没有停止其他 ADB 服务。本次没有源码变更、提交或发布，用户原有暂存区保持不变。

<a id="release-427-user-retest-2026-11-04"></a>

## 4.2.7 用户复测与原生字幕评估（2026-11-04）

用户安装后自行测试，反馈“Media3 还是看不到字幕”，随后询问内置字幕是否应交回 Media3 自行处理。这是实际播放仍失败的证据；上文构造样本、模拟器及构建通过的结果不能据此关闭问题。以下源码核对基于当前 `a33dd791a`，本次没有修改播放器代码或新增渲染策略。

通过 `ddplaytv-http-logs` 技能直连既有 HTTP 日志服务，成功取得索引、最新段和完整 ZIP，三个端点没有报错，未使用 ADB、导航、截图或改变设备设置。证据移入被忽略的 `.tmp/media3-subtitle-followup-2026-11-04/http/20261104-135602/`，原采集路径与移动位置保存在 `collection-info.txt`。当前段共 13 条：10 INFO、2 WARN、1 ERROR；记录了 13:54 的 115 MKV 播放、视频/音频解码器初始化，没有新增的 `EmbeddedSampleDiagnostics`、`normalizedCommas` 或 `assImages`。

结构诊断在源码中使用 `LogFacade.d`，当前段没有 DEBUG 记录；不能由其缺失证明字幕已解析或未解析，也不能直接确认当前日志配置。唯一 LOGCAT 错误为 `No such file or directory`，没有调用上下文，不能认定为字幕文件或字体缺失。当前原始头部、事件解析和可见像素的证据缺口仍存在。

确认的设计边界：

- `LibassSubtitleDecoderFactory` 在 sink 可用时接管 ASS；`LibassSsaStreamDecoder` 向 sink 转发后始终输出 `EmptySubtitle`，因此 Media3 原生 Cue 显示不会同时保底。
- `PlayerActivity.onSubtitleBackendFallback()` 只记录降级和显示提示，不切换到原生渲染；渲染器成功提交透明帧也不会自动触发该故障回调。尚未证实本次实际播放命中了哪一个失败环节。
- Media3 `1.11.1` 自带 [SsaParser](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaParser.java)，可生成基础 ASS/SSA Cue。[SsaStyle](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaStyle.java) 只保留部分覆盖信息，例如 move 使用终点，并移除其余标签，不能等同于 libass 完整特效。原生与当前路径共享 Matroska 提取，切换绘制方式不能保证修复上游输入问题。

初步建议，**尚未实施**：先建立同片 Media3 原生字幕对照，确认基础可见性；保留普通格式的原生链路，将 libass 用于需要完整 ASS 效果的增强，并明确原生切回/失败恢复。来源可以作为默认分工，但仍须区分格式能力和复杂 ASS 的效果取舍。不能用短时间零图像自动判错，因为合法字幕空窗同样没有图像。用户随后提出“外挂用 libass、内嵌用 Media3”，具体参考对照和建议见下节。

<a id="source-based-routing-assessment-2026-11-04"></a>

## 外挂 libass、内嵌 Media3 的方案评估（2026-11-04）

**状态：历史评估，未实施；本节的来源分工建议已由下节 Plezy 方案替代。** 评估限定 Media3 内核；mpv 继续自行管理字幕。当前源码基线为 `a33dd791a`，Media3 为 `1.11.1`。本次未操作电视、采集新设备日志、运行播放器或构建。

当时建议采用有格式边界的来源分工：**内嵌字幕默认走 Media3 官方解析与 Cue 显示；外挂 ASS/SSA 走现有 libass；普通外挂文字字幕优先使用官方链路。** 这是针对当前内嵌桥接复杂度的简化建议，不能保证解决原片，也不代表成熟项目普遍按来源分配渲染器。用户随后接受以 Plezy 设计为主要参考，本项目不再将降低内嵌 ASS 效果作为首选方案。

### 参考项目实际做法

| 参考与固定版本 | 内嵌 ASS 与外挂 ASS | 普通字幕与可借鉴部分 |
| --- | --- | --- |
| Plezy `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5` | [AssSubtitleParserFactory](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/parser/AssSubtitleParserFactory.kt) 按 SSA MIME 接管；Matroska 内嵌由 AssTrackOutput 送事件，非内嵌由 [AssFullSubtitleParser](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/parser/AssFullSubtitleParser.kt) 把完整文档送入同一 libass 轨道体系。来源决定输入方式，两者均由 libass 绘制 | 普通文字交给默认 parser；[ExoPlayerCore](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L3891) 用 SubtitleConfiguration 接入外挂、原生选轨与 Cue 显示，并处理新增轨道时的重新准备及进度保留；PGS 另有解析增强 |
| Jellyfin Android TV `f299a2c4e2aa8c81869b4c201be9fc77ce208037`（提交时间 2026-11-03） | [VideoManager](https://github.com/jellyfin/jellyfin-androidtv/blob/f299a2c4e2aa8c81869b4c201be9fc77ce208037/app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java#L219) 在 assDirectPlay 开启时组合 ASS parser、MKV 支持与 libass View；[deviceProfile](https://github.com/jellyfin/jellyfin-androidtv/blob/f299a2c4e2aa8c81869b4c201be9fc77ce208037/app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt#L558) 对 ASS/SSA 同时声明内嵌和外挂能力，没有按来源分别选 libass/原生 | SRT、WebVTT、TTML 等使用原生链路；外挂携带 ID、语言、标签和选择标记。新 [ExoPlayerBackend](https://github.com/jellyfin/jellyfin-androidtv/blob/f299a2c4e2aa8c81869b4c201be9fc77ce208037/playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt#L171) 也在启用 libass 时按格式增强，并把普通 Cue 完整交给 SubtitleView |

两项目都在保留 ASS 效果的前提下按格式增强，不能据此说“成熟方案就是内嵌全部用 Media3”。Jellyfin 还通过 [PlaybackController](https://github.com/jellyfin/jellyfin-androidtv/blob/f299a2c4e2aa8c81869b4c201be9fc77ce208037/app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java#L89) 协同服务端烧录/转码；115 直连没有这项兜底能力。可借鉴的是完整使用原生选轨/Cue、显式声明支持范围、维护轨道和媒体生命周期。

### 当前项目的关键差距

| 已核对的源码 | 当前行为与方案影响 |
| --- | --- |
| [Media3VideoPlayer](../../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt)、[LibassAwareRenderersFactory](../../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassAwareRenderersFactory.kt)、[Media3MediaSourceHelper](../../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3MediaSourceHelper.kt) | 全局 LIBASS 后端会关闭提取阶段字幕解析、替换 TextRenderer，并将内嵌 ASS 送到自定义桥接。外挂 libass 与内嵌策略绑在同一个开关；不能只关闭 sink 就认为恢复了官方链路 |
| 原 `LibassSsaStreamDecoder`（后续已移除）与 [PlayerActivity](../../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) | 当时 ASS 接管后原生输出为空，故障回调只有提示。内嵌改走原生能绕过这段桥接及其 GPU 绘制依赖；这不是已经证实的原片根因 |
| [Media3VideoPlayer.onCues](../../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) | 文字 Cue 被拼成 String，再转换成 MixedSubtitle；Spans、定位、对齐、字号和层次等信息丢失。位图保留了 Cue。采用原生方案必须把文字 Cue 也完整交给 SubtitleView，不能只替换 decoder |
| [ExternalSubtitleManager](../../../player_component/src/main/java/com/xyoye/subtitle/ExternalSubtitleManager.kt)、[LibassRendererBackend](../../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt)、[ass_gpu_bridge.cpp](../../../player_component/src/main/cpp/ass_gpu_bridge.cpp) | 现有 libass 外挂能力仅声明 ASS/SSA，native 直接 ass_read_file。它不是 SRT/VTT/TTML 或位图字幕的通用解码器；当前普通外挂仍走自有解析与进度轮询 |
| [ControlWrapper](../../../player_component/src/main/java/com/xyoye/player/wrapper/ControlWrapper.kt)、[LibassGpuSubtitleSession](../../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) | 已有内外选轨互斥，但 libass 会话启动、移除外挂时仍绑定/恢复内嵌 sink。分工后应拆开这项副作用，保留外挂需要的时间、暂停刷新、Surface 与字体处理；加载失败不能让菜单选中状态与实际显示脱节 |

### 建议的职责与取舍

| 字幕来源与格式 | 建议处理 | 能力边界 |
| --- | --- | --- |
| 内嵌普通文字、Media3 支持的位图字幕 | Media3 官方提取/解析、TextRenderer、完整 Cue → SubtitleView | 支持范围仍取决于容器与编码，不能承诺所有内嵌格式都可播 |
| 内嵌 ASS/SSA | 默认同上 | 保留基础对白与部分样式/定位，接受复杂 ASS 效果损失；需要完整效果时继续使用现有 mpv |
| 外挂 ASS/SSA | 直接由现有 libass 加载完整文件 | 保留 ASS 排版和效果；不再为此接管内嵌字幕 |
| 外挂 SRT、WebVTT、TTML 等官方支持的文字格式 | 优先用 Media3 原生外挂链路 | 不为统一渲染器新增 SRT → ASS 等转换层；已有格式也不能在迁移时静默丢失 |
| 当前自有解析器支持的 SCC、STL 等兼容格式 | 保留现有兼容路径，按实际格式验证 | [FormatFactory](../../../player_component/src/main/java/com/xyoye/subtitle/format/FormatFactory.kt) 还接收 ASS/SCC/SRT/STL/XML；不能将“其他都走原生”理解为删除这些能力 |

[Media3 官方列表](https://developer.android.com/media/media3/exoplayer/supported-formats#standalone-subtitle-formats) 明确支持独立 SRT、WebVTT、TTML、SSA/ASS，但“支持 ASS”不等于完整 libass 效果。[1.11.1 SsaParser](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaParser.java#L370) 生成基础样式和定位；[SsaStyle](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaStyle.java#L408) 不按 ASS 字体名装配附件字体，move 仅取终点，其余未实现的覆盖标签会被移除。复杂动画、卡拉 OK、绘图与字体还原不能保证，内嵌与外挂的同一份 ASS 可能呈现不同效果。这是方案本身的能力取舍。

来源分工的预期收益是让内嵌字幕回到一个官方管线，减少本项目维护原始 ASS 事件、头部、缓存重放与 sink 代际的责任。尚无性能测量，不承诺更快或更低功耗。原生与现有方案仍共享 Media3 的容器提取边界；若真实原因在提取/源数据，改渲染器仍可能无字。

### 来源分工方案的实施范围与验收建议（已被替代）

1. **先恢复内嵌官方管线**：提取阶段解析、默认 TextRenderer 与完整 Cue 显示配套调整，保留 CHS/CHT、原标签和稳定轨道身份。验证内嵌 SRT、基础 ASS、位图实际显示，区分原生能力限制与项目数据丢失。
2. **外挂 libass 独立持有资源**：不再注册内嵌 ASS sink；仍使用播放器时间和现有字体/Surface 能力。验证播放、暂停、seek、倍速、显示开关与 Surface 重建，防止为减少桥接误删外挂依赖。
3. **维护单一有效选择**：外挂成功后抑制内嵌显示，切回内嵌清掉外挂；加载失败/取消恢复先前有效选择。Off 只隐藏且记住原轨，重开、换媒体、预加载晚到回调均不能产生双字幕或旧字幕。
4. **逐项映射现有设置**：字号、颜色、描边、透明度、位置、时间偏移必须对照原生 Cue/SubtitleView 能力验证；保留脚本定位，明确用户覆盖规则，不能把所有 Cue 强制压成底部一行。普通外挂迁移与历史格式兼容单独验证，不扩大为字幕转换项目。
5. **分开记录验证结论**：先用本地可控 MKV/外挂和任务模拟器验证渲染、设置、异常恢复、DPAD/BACK；只有实际 115 视频复测成功才能关闭原问题。没有新电视操作授权时不为补证据占用设备。

上述顺序是当时的来源分工建议，未实施，后续工作按下节组织。该次参考文件保留在被忽略的 `.tmp/subtitle-source-routing-assessment-2026-11-04/`，Plezy ExoPlayerCore 已与早前本地缓存逐文件哈希核对一致，引用均固定提交。该次三个变更文档共 72 个本地链接均存在，评估锚点唯一，`git diff --check` 通过；这些是历史文档检查，不是播放器运行验证。

<a id="plezy-architecture-target-2026-11-04"></a>

## 以 Plezy 完整字幕设计为主要参考（2026-11-04）

**方案状态：用户随后明确授权“实现一下”，实施与验证见[下方账本](#plezy-subtitle-implementation-2026-11-04)。** 用户表示“他的方案成熟直接按照此项目设计来也行”，据此将主要目标调整为 Plezy 的格式分流和轨道管理。采用其架构比按来源降低内嵌 ASS 效果更符合保留字幕能力的目标；这里的可采用结论是源码设计判断，不是对全部片源或本片修复结果的保证。

### Plezy 的完整链路

固定参考提交仍为 `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5`。

| 职责 | 实际实现 | 映射到本项目的目标 |
| --- | --- | --- |
| 格式分流 | [AssSubtitleParserFactory](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/parser/AssSubtitleParserFactory.kt) 将 ASS/SSA 分给 libass，其他普通文字委托默认 parser | 内嵌和外挂 ASS/SSA 都保留 libass 效果；SRT/WebVTT/TTML 等使用原生 Cue。来源只影响输入方式 |
| 内嵌 ASS 输入 | AssMatroskaExtractor 配合 [AssTrackOutput](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/text/AssTrackOutput.kt)，在提取阶段按轨道 ID、起点与时长向 handler 注入事件；ASS 使用 NoOp parser 避免重复绘制 | 用提取/轨道输出边界取代当前 legacy decoder 接管；事件数据、时间映射、选轨和绘制各有明确所有者 |
| 外挂输入与选择 | ExoPlayerCore.addSubtitleTrack 通过 SubtitleConfiguration 添加 ID、语言、标签、格式；新增轨道需要重新准备时保存进度和播放/暂停状态，清理旧 TrackGroup override | 官方支持的外挂并入 Media3 轨道体系，继续向现有 TV 菜单暴露真实轨道。失败/取消恢复与用户选择一致的状态 |
| 轨道与字体 | [AssHandler](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/AssHandler.kt) 按 ID 保存 ASS tracks，监听实际选中轨；字体可先缓存，换媒体时统一释放轨道、renderer 和字体 | 字幕管理器属于媒体 source，渲染会话负责选中轨的显示。保留当前预加载与旧回调隔离，不让离屏 source 接管当前画面 |
| 显示与时间 | [ExoPlayerCore](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L1103) 完整保留普通 Cue，用独立 SubtitleView 显示文字/位图；libass 由视频帧时间驱动，暂停关字幕会显式清层 | 修正本项目文字 Cue 被压平的问题，沿用现有视频 Surface、字幕覆盖层和时钟映射；不移植未经测量的呈现补偿、预渲染和自动校准 |

**ASS 不输出原生 Cue 本身不是缺陷。** Plezy 的内嵌 NoOp parser 和外挂完整文档 parser 同样不输出原生 Cue，因为绘制归 libass。此前确认本项目没有自动原生回退，只说明现有恢复能力的边界；不能把 EmptySubtitle 单独当成实际无字幕的根因。需要验证的是输入是否完整、轨道是否正确选中、libass 是否生成有效图像以及覆盖层是否显示。

### 新确认的具体兼容性差距：MKV zlib 字幕

实际 ExoPlayerCore 使用的是 [ZlibMatroskaExtractor](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ZlibMatroskaExtractor.kt)，不只有 libass 模块里的基础 extractor。它在 Matroska Block 层解压 SRT/ASS/SSA/WebVTT 的 zlib 内容，再交给父提取器组装字幕。

- [Matroska 规范](https://www.matroska.org/technical/elements.html#ContentCompAlgo) 规定压缩算法的缺省值为 0，即 zlib；算法字段可以因采用缺省值而省略。[Media3 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java) 只接受显式算法 3（header stripping），没有 zlib 解压路径；组装文字样本时还会按首个 NUL 截断。
- 本项目 LibassMatroskaExtractor 当前只补字体附件，未处理这类压缩。若未解压的二进制数据到达文字组装阶段，之后再换 decoder、libass 或原生显示都不能补回已丢失的内容。这个差距已由源码确认，实际损坏表现尚未在本项目运行复现。
- Plezy 有 [ZlibTextSubtitleExtractionTest](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/test/kotlin/com/edde746/plezy/exoplayer/ZlibTextSubtitleExtractionTest.kt)，对照压缩/未压缩 MKV 的 45 个事件、正文、开始和持续时间。本轮只阅读测试，没有运行它，也没有确认用户视频的 ContentCompression。

因此该差距应成为下一步优先复现项，但不能将此前 15～17 字节事件直接解释为 zlib 或把它写成已找到原片根因。修复位置必须在文字组装之前，不能在已截断的 TrackOutput/decoder 样本上尝试解压。

### 接入方式与保留边界

Plezy 的 [AssMatroskaExtractor](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/extractor/AssMatroskaExtractor.kt) 反射读取 `extractorOutput` 和 `subtitleSample` 私有字段。本项目采用其职责划分，优先用已有外层 Extractor 委托、公开 TrackOutput 与 [SubtitleTranscodingExtractorOutput](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/SubtitleTranscodingExtractorOutput.java) 验证等价接入。MatroskaExtractor.init 为 final，适配放在外层委托，不计划覆盖该方法或修改上游源码。这个组合尚未经过编译/运行验证，应先做最小集成验证再替换现有桥接。

继续保留本项目已验证的真实 codec private 选择、尾 NUL 处理、缺 Events 的 native 回退、字体输入预算、媒体/轨道代际隔离、时间坐标映射及 Surface 失效保护。Plezy 中固定读取 initializationData[1]、从合并 ID 截取后缀等具体假设不能替代本项目的稳定身份和输入校验。PGS 修正另按样本确定需要，不连带移植其音频、Dolby Vision、tunneling 或呈现优化。

UI、Off 隐藏并保留选中轨、CHS/CHT 命名、既有设置和 mpv 原生字幕保持既有契约；官方不支持的 SCC/STL 等已有外挂格式暂留兼容路径，不为统一管理新增转换框架或静默删格式。不新增自动回退，也不以短时零图像判断字幕失败。

### 实施顺序与完成标准

| 顺序 | 工作与主要位置 | 验证 |
| --- | --- | --- |
| 1 | 优先在 LibassMatroskaExtractor 和现有测试中复现压缩字幕差距，覆盖显式/缺省 zlib；按 Block 解压补齐实际缺口 | 同一 ASS 压缩/未压缩 MKV 的事件正文、数量、时间一致；无压缩与 header stripping 不回归，损坏/超限输入有界；再经过真实 libass 验证像素 |
| 2 | 在 Media3MediaSourceHelper、Media3SubtitleInput 建立提取阶段 ASS 分流与按轨道管理的最小集成，普通格式继续官方转换 | 验证公开适配可行性、选择/切轨、字体、source 预加载隔离、裁剪/多 period/seek 时间映射；通过后再移除被替代的 decoder 接管，保留外挂及视频时钟仍使用的部分 |
| 3 | 将 Media3 支持的外挂并入真实轨道管理；普通文字/位图完整 Cue 显示，映射现有样式和时间偏移 | 外挂添加时保留进度/暂停/倍速，选轨互斥，失败/取消恢复，Off/重开/换媒体无残留；复杂 ASS 和嵌入字体保持效果，SRT/VTT/TTML 保留定位与样式 |
| 4 | 删除本次替代后无用的恢复/轮询支路，更新维护说明与必要回归 | 相关 JVM、native/Media3、构建/lint/依赖检查及任务模拟器 DPAD/BACK、设置与异常恢复通过；原片用户复测单列，继续遵守不占用电视的约束 |

前两步先建立证据和接入边界，避免同时替换所有层后无法归因。以上是授权实施前的目标与源码判断；随后实现及实际验证由下方账本维护。已安装的电视 Release 仍是 4.2.7，不能把工作区变化当作电视已更新。

<a id="plezy-subtitle-implementation-2026-11-04"></a>

## Plezy 字幕方案实施（2026-11-04）

**状态：实现与本地验证完成，原 115 视频待用户复测。** 基线 `a33dd791a`，Media3 `1.11.1`。保留授权前已有的方案与 memory 改动；本轮不暂存、不提交、不增版本、不打 Release。只使用任务模拟器与专用 ADB，不连接或操作实体电视，也不要求下载 115 原片。

### 实际变更

| 范围 | 结果 |
| --- | --- |
| MKV 压缩文字 | 公开 EBML Block 扩展点在文字组装/NUL 截断之前处理显式/缺省 zlib 与 header stripping。ASS/SSA/SRT/WebVTT 文字轨有解压预算与损坏输入检查；不移植音频解压或上游私有反射 |
| ASS 输入与所有权 | 外层 Extractor 组合公开捕获输出与 SubtitleTranscodingExtractorOutput；提取阶段按轨缓存，实际文本 renderer 选轨后显示。移除 legacy ASS decoder/factory，保留头部规范化、字体预算、source/轨道代际隔离及视频时钟 |
| 时间与预加载 | period 内容时间加窗口位置；缓冲内 seek 重放缓存，合成窗口通过弱轨道所有权索引找回子 source。默认播放器 factory、普通播放和顺序预加载统一入口；每 source 的 headers/parser/input 独立 |
| 官方外挂 | 支持的格式校验成功后并入 SubtitleConfiguration。ASS 完整脚本经 ass_read_memory；普通格式用默认 parser。实际轨道出现后选中/通知 UI；保留位置、暂停、倍速、队列、Surface 和 MediaItem 配置，取消/失败不提前取消旧轨，缓存随播放器释放 |
| 普通 Cue | 保留 Spanned、定位、文字/位图混合及空 Cue；映射现有样式设置。描边映射为官方标准 outline，宽度数值与 libass 不完全等价；普通字幕改延迟时重建当前 source，恢复实际选轨和播放状态，可能短暂缓冲；ASS 仍直接更新时钟 |
| 保留契约 | 简繁命名、Off 隐藏并保留选择、TV 菜单/返回/设置、原字体/Surface 防线、SCC/STL 等旧外挂兼容及 mpv 原生字幕保持。没有新增自动回退、零像素超时判错或呈现优化 |

维护契约见[字幕渲染说明](../../../document/player/subtitle-rendering.md)。新增三个很小的双轨 MKV 均为自创测试数据，可用 [generator](../../../scripts/test-fixtures/generate_media3_subtitles.py) 重现，不包含用户视频。

### 复现与修正记录

证据目录：`.tmp/plezy-subtitle-implementation/`（本地忽略产物）。

- `zlib-red.log`：基线的显式 zlib、缺省 zlib、header stripping 三项真实提取断言失败。初次显式 Robolectric SDK 过滤导致“找不到测试”的环境错误不算行为复现；去掉冲突配置后才取得上述失败。
- `zlib-green.log`：修复后压缩提取及既有附件用例通过，`BUILD SUCCESSFUL in 34s`。
- `instrument-initial.log`：19 项中 4 项失败，定位到缓冲内 seek 未重放、ConcatenatingMediaSource2 无子窗口 tag；补齐重放与弱所有权查找。
- `instrument-integration.log`：22 项仅新双轨 fixture sniff 失败；补齐 EBML 文件头，原有时间/seek/像素/预加载/Surface 回归已通过。
- `instrument-complete.log`：23 项 2 项失败；一项是测试只检查 TrackGroup 第 0 项，未遍历实际组内轨道；另一项确认 Media3 合并 ID 的数字前缀使外挂自动选中失效。修正组内选择与完整 ID 匹配。
- `instrument-fixed.log`：23 项 1 项失败，简繁轨已通过；新增暂停负偏移断言复现原位置 seek 被忽略、旧 Cue 未清除。改为禁用/恢复文本选择重读，后续验证见下方最终结果。
- `integration-fixes-build.log`：播放器 JVM/测试 APK 构建 `BUILD SUCCESSFUL in 2m 41s`；`format.log`：31 个改动 Kotlin 文件定域格式 `BUILD SUCCESSFUL in 29s`。最终状态不能仅由这些中间成功推断。
- `instrument-final.log`、`instrument-offset-final.log`、`instrument-offset-probe.log`：仍复现暂停恢复偏移失败。探针确认禁用/恢复文本轨后 SampleStream 已返回 EOS，没有重读消费过的 Cue；取消该方案，复用当前 source 替换流程恢复实际选轨和播放状态。`instrument-offset-source.log` 的外挂/偏移链路通过。
- `instrument-final-source.log`：24 项中一项取消加载断言失败，其他 23 项通过；单独重跑未复现。`instrument-load-probe.log` 带探针的 24 项通过，但不足以排除竞态。源码显示 Main.immediate 启动和很快完成的 IO 可能让替换发生在 addTrack 返回前，故改为始终在 Main 入队，最终用例连续取消 20 次。所有临时探针已删除。
- 另核对到真实控制器先保存偏移再通知 kernel；按持久化值比较会错误跳过应用，改为记录播放器已应用值并增加先保存再设置的断言。`final-cancellation-validation.log` 的 7 项 JVM 失败暴露该状态在构造时过早读取 MMKV，已移到 initPlayer；`final-verified-build.log` 的一次 Kotlin 检查因注释前缺空行失败，已修正。它们均为中间失败，不并入最终通过结果。

### 最终验证与未决边界

| 范围 | 实际结果与证据 |
| --- | --- |
| 全模块 JVM、lint 与依赖 | `final-validation.log`：root `testDebugUnitTest lint verifyModuleDependencies`、定域 Kotlin 检查和 Debug APK 构建，末尾 `BUILD SUCCESSFUL in 11m 50s`。XML 共 528 项，0 failures/errors，2 skipped，统计在 `jvm-test-counts.json`。lint 输出含既有 mpv Kotlin metadata 诊断文字，最终任务仍成功 |
| 最终源代码复验 | `final-verification.log`：播放器 JVM、播放器/应用测试 APK、应用 Debug、app/player lint、定域 ktlintCheck、模块依赖检查，末尾 `BUILD SUCCESSFUL in 1m 30s`。最终播放器 XML 为 172 项，全部通过 |
| 最终 native / Media3 | `instrument-verified-final.log`：`OK (24 tests)`，覆盖 zlib/未压缩 ASS 像素、真实简繁切轨、完整文档、字体、seek/裁剪/多 period/预加载、视频时钟、Surface/显隐、外挂 ASS/SRT/VTT/TTML、失败、连续 20 次取消及暂停正负偏移恢复 |
| 最终应用 DPAD | `app-dpad-final.log`：`OK (3 tests)`。两个 TvDpadRewrite 播放器用例覆盖双内核真实像素、设置/BACK、暂停恢复及 Media3 双 Surface 重建；SubtitleQuick 用例覆盖双内核菜单、Off 保留选轨、重开、实际外挂与详细设置入口。首次误用无 debug 后缀 runner 未执行测试，保留在 `app-dpad-runner-error.log`；按实际安装的 `com.okamihoro.ddplaytv.debug.test` 重跑通过 |
| 画面与启动冒烟 | 实际查看 `media3-libass-subtitles-final.png`、`mpv-native-subtitles-final.png` 和两内核字幕快捷面板，Media3 ASS/普通 Cue 与 mpv 原生字幕的证据分开。最终包冷启动进入首页，DPAD 进入媒体库及本地目录空态，BACK 返回调用项并保留可见焦点，再退出至 Launcher。`smoke-start/core-ready/return/exit` 图片、XML 与 activity 输出保留；媒体权限为任务模拟器的测试准备，核心导航仅用 DPAD |
| 输入与文档 | 三个自创 MKV 与 generator 逐字节一致；维护说明已更新，删除失效旧类链接。文档链接、diff 检查和任务模拟器清理结果见收尾记录 |

普通字幕调整延迟可能重新缓冲；官方 SubtitleView 的描边宽度及描边/阴影组合与 libass 不完全等价。没有新增自动原生回退或零像素超时判错。**原 115 视频的压缩方式及唯一故障原因仍未确认；本地构造样本通过不等于原片或物理电视验收。** 本轮不修改电视上的 DEBUG 设置。电视仍是先前安装的 4.2.7 Release，本轮 Debug 只安装到任务模拟器。

收尾：最终应用 ARM64 Debug SHA-256 为 `0a223c02114f66a2397f42ddc5a9fa4efddd0309a9083e25e701d1b5bf400fc5`；任务模拟器 `emulator-5574` 收到 `emu kill` 的 OK，随后专用 ADB `5057` 关闭。进程/端口复查：对应 emulator/qemu 进程 0，5057 监听 0。没有重置其他 ADB 或访问实体电视。日志、截图及预览均为忽略的本地产物。

最终文档核对：本轮四个变更文档的 127 个本地链接均存在，实施锚点唯一，三个 MKV 与 generator 一致，`git diff --check` 通过；暂存区为空，新增未追踪文件仅为本次源码、测试和自创夹具/generator。

<a id="plezy-release-427-tv-install-2026-11-04"></a>

## Plezy 实施后 Release 打包与电视安装（2026-11-04）

**状态：打包、覆盖安装与读取校验完成；原 115 视频待用户复测。** 用户随后明确要求“打包release然后安装到tv”。本次保持 **4.2.7 / 74**，没有再次递增版本；工作区基于 `a33dd791a`，包含上节未提交的 Plezy 字幕实现。以下安装替换的是 13:50 安装的旧 4.2.7，同一版本号不能区分两次构建，须以本节哈希和更新时间为准。

### 构建与包校验

证据目录：`.tmp/release-4.2.7-plezy-subtitles/`，均为忽略的本地产物。

- 命令：`scripts/dev-env/gradlew-local.bat :app:assembleRelease --no-daemon --offline --console=plain --stacktrace`；沿用已验证的进程级 JVM 临时目录参数，未修改构建配置。
- `assemble-release.log` 尾部为 **`BUILD SUCCESSFUL in 2m 7s`**，689 项任务，232 执行、457 已最新，包含 Release vital lint。
- `release-verification.json` 与各 APK 的 badging/signature/zipalign 日志确认：实际包名 `com.okamihoro.ddplaytv`、版本 `4.2.7 / 74`、非 debuggable、预期 ABI、ZIP 对齐及签名有效。签名证书 SHA-256 为 `e1b82b2732cb93db0c809d21e2f0c90ef3bdd4958c2fc89ef217c082a070cdc6`，与此前已安装包一致。
- 各目标 ABI 都包含 `libass.so` 和 `liblibass_bridge.so`。ARM64 DEX 中实际存在新的 `LibassSubtitleParserFactory` / `LibassSubtitleExtractorOutput`，已移除的 `LibassSsaStreamDecoder` / `LibassSubtitleDecoderFactory` 不存在，见 `release-subtitle-classes.json`。

三个包位于 `app/build/outputs/apk/release/`：

| APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| `ddplaytv_v4.2.7_arm64-v8a-release.apk` | 68,362,086 | `47c6c8d0d6b96f2b9ae705b2959301c90550d606a7ff8eac7d8cdbed713b2787` |
| `ddplaytv_v4.2.7_armeabi-v7a-release.apk` | 64,598,418 | `a03cb702b359997e5ef91905c439d3c95108e0b389f69f133cdeec18db2aab41` |
| `ddplaytv_v4.2.7_universal-release.apk` | 102,431,922 | `3a721828029609d2eacc44b5f8d7b517972081f97bdd694483f1302b5ee6f2aa` |

### 电视安装与收尾

专用 ADB 端口 `5091` 连接 `192.168.31.70:5555`，确认 `device` 状态及 Hisense / VIDAA_TV / Android 11（API 30）/ ARM64 身份后，以 `adb install -r` 安装上述 ARM64 包，返回 **Success**。未卸载或清空应用数据。

`tv-install-verification.json`、安装前后 package 日志及 `tv-installed-apk.sha256` 确认：

- 版本实际读取为 `4.2.7 / 74`，`lastUpdateTime` 从 `2026-11-04 13:50:54` 更新为 **`2026-11-04 16:45:30`**。
- 已安装 `base.apk` 的 SHA-256 与本次 ARM64 包完全一致。
- UID 仍为 `10111`，首次安装时间仍为 `2026-02-12 10:47:55`。
- **16:46:41** 断开本次电视连接并停止专用 ADB，复查 5091 无监听；没有重置默认 ADB 服务。

没有启动应用、导航、播放、截图或修改电视设置，也没有进行新的设备字幕测试。本次沿用上节仍有效的源码测试证据，打包/安装成功不能代替原片验收。未暂存、提交或修改版本配置。

<a id="compressed-subtitle-error-tolerance-todo-2026-11-04"></a>

## 待办：压缩字幕局部失败容错（2026-11-04）

- [ ] 调整 Media3 的压缩字幕异常处理，让可隔离的坏字幕事件尽量不影响音视频继续播放。

**状态：用户要求记录为待办，尚未实施。** 保留当前公开接口接入方案；本项不涉及替换提取器架构、GPU 渲染优化或再次操作电视。

源码核对基线为 `a33dd791a` 工作区 / Media3 `1.11.1`：[LibassMatroskaExtractor](../../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassMatroskaExtractor.kt) 对损坏、超限及压缩文字 lacing 直接抛出 `ParserException`，现有 [ZlibSubtitleExtractionTest](../../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/ZlibSubtitleExtractionTest.kt) 也按拒绝输入断言。从调用路径判断，局部字幕错误可能升级为整段视频播放错误；尚未用含音视频的坏字幕样本验证这一影响。

参考固定 Plezy 提交的 [ZlibMatroskaExtractor](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ZlibMatroskaExtractor.kt)：解压失败时记录日志，将原块交回 Media3。借鉴其保护播放连续性的容错方向；原块透传不代表字幕恢复，也不能保证后续不报错。安全跳过坏事件或隔离问题字幕轨的具体方式，需验证上游 Block/BlockGroup 状态后确定。

实施边界与验收：

- 保留压缩块大小、解压大小与比例上限及采样日志；仅处理能够确认边界的字幕内容错误，不吞掉容器结构损坏、真实读取失败或取消信号。
- 增加含音视频、正常字幕与损坏/超限字幕事件的真实 Media3 回归，验证音视频继续、坏事件受控、后续正常字幕仍可显示，以及 seek/切轨后状态正确；不能仅以“不抛异常”作为通过标准。
- 保持正常显式/缺省 zlib、header stripping、无压缩字幕、字体附件及原有轨道/时间映射回归通过。本待办独立于原 115 视频故障，其根因和用户复测状态不因此改变。

<a id="libass-atlas-rendering-todo-2026-11-04"></a>

## 待办：参考 Plezy 升级 Atlas 字幕渲染管线（2026-11-04）

- [x] 参考 Plezy 的 Atlas 设计，评估并升级 Media3/libass 当前逐图像纹理绘制路径，通过图集与合批减少纹理切换和绘制调用，并验证实际收益。

2026-11-05：用户已授权完成本项，实现及本地验证完成。基线为 `ba9d6d045`、干净工作区；本轮证据目录为 `.tmp/atlas-rendering/`。先给现有路径增加测量和测试控制，再取得优化前数据，随后实现并用同一设备对比。实施范围不包含压缩字幕容错或原片故障修复；仅使用任务模拟器，不操作物理电视。

1. 基线与参考：核对固定 Plezy 提交的 `AssPack.c/.h`、`AssKt.c` 和 `AssSubtitleAtlasPipeline.kt`，记录当前布局、上传、绘制/提交、纹理切换和资源峰值。
2. 有界图集：在现有串行 native 线程内按原合成顺序打包，以顶点携带颜色合批；复制边缘像素保持线性采样，超出图集容量时保留受预算约束的逐图像路径，不丢弃字幕。
3. 验证：GLES 2/3 逐通道对照、多页/扩容/同时间重绘/透明度/Surface/换媒体回归，实际性能和资源比较；构建、lint、JVM、播放器集成及 DPAD 冒烟分别记录。

**状态：2026-11-05 实现及本地验证完成；像素、性能、资源与验证范围见[实施记录](#atlas-rendering-implementation-2026-11-05)。** 参考入口为固定 Plezy 提交的 [AssSubtitleAtlasPipeline.kt](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/widget/AssSubtitleAtlasPipeline.kt)；实施前已读取对应图集打包、GL 合成和资源管理源码。现有 [Atlas 正确性与测量门槛](../designs/2026-10-29-media3-libass-integration-design.md#9-atlas-采用条件与正确性)继续适用，不能将模拟器结果扩大为物理电视收益。

实施边界与验收：

- 范围限定应用 libass 的 GPU 绘制层；保留公开提取器、输入与时间映射、字体、切轨/seek、显隐/暂停及 Surface 恢复契约。mpv 继续原生渲染，精确呈现、线程拆分和推测/预热不随本待办一并启用。
- 图集打包和合批保持 ASS_Image 原有合成顺序、颜色/透明度、定位/裁剪和设置效果；覆盖交叠、描边/阴影、多页、同时间重绘及 Surface 重建，不能通过重排或丢图换取性能。
- 保留纹理/内存预算和超限恢复，分别验证 GLES 2/3。具体机制沿用上述设计的正确性要求，不在记录待办时锁定打包算法、页数或缓存参数。
- 先取得当前路径基线，再用相同设备和素材对比 Atlas：记录纹理切换/绘制次数、上传与合成耗时、掉帧及峰值内存，覆盖普通字幕、复杂 ASS/卡拉 OK 和长期播放。确认画面等价及实际收益后再判断采用范围；设备测试遵循届时授权，不因记录待办占用电视。

<a id="subtitle-feature-comparison-2026-11-04"></a>

## 字幕功能差异与成熟度评估（2026-11-04）

**评估阶段状态：源码评估完成，当时未修改播放器实现。** 用户要求详细比较双方项目功能方案并选择更成熟的做法。完整范围、证据、逐项取舍与建议优先级见[字幕功能方案评估](../../../document/player/subtitle-feature-comparison-2026-11-04.md)。随后授权的三项具体修复见下节，其他设计建议仍未实施。

结论是以 Plezy 的字幕功能策略为主要参考：借鉴语义选轨与跨集继承、设置作用域和实际应用值管理、文字/位图分治；保留本项目公开提取器、完整轨道枚举和简繁命名、外挂预校验/取消保护、普通字幕偏移恢复及 source 作用域。新增源码确认的问题是偏移保存值、界面单例与双内核初始化不一致，以及样式设置按会话 backend 而非当前轨道能力显示；冷启动和实际设置表现尚未在本轮运行验证。不能把这些发现直接认定为原 115 视频的故障原因。

评估阶段没有构建、设备操作或新运行测试，未改变当时已有实现/安装验收状态；压缩块容错和 Atlas 继续是上方两项未实施待办。报告的其他建议只用于后续决策，不自动进入实施。

文档检查：报告、activeContext 和本账本共 83 个本地链接、28 个锚点引用均存在，显式锚点无重复；30 个 Plezy 文件链接均固定上述提交并与该提交的文件树核对存在。`git diff --check` 通过，新报告另检查无尾随空白；这些结果不代表播放器构建或运行验证。

<a id="subtitle-feature-fixes-2026-11-04"></a>

## 字幕功能缺陷修复（2026-11-04）

用户随后要求“修复发现的问题”。本轮先处理评估中三项具体实现缺陷；跨集语义偏好和文字/位图几何属于进一步功能设计，不随本轮加入。全局偏移保存规则保留，媒体库设置作用域不恢复。仅使用任务模拟器 `emulator-5574` / 私有 ADB `5093`，不操作电视，不修改版本或打包 Release。

1. 统一偏移读写来源 -> 验证已保存非零值的界面初始化、步进与双内核应用。
2. 按实际轨道区分样式能力 -> 验证普通文字、ASS、位图和 mpv 的设置显隐、重置范围与 DPAD 返回；普通文字大小与 ASS 缩放分别生效。
3. 给外挂资源稳定身份并复用已有轨道 -> 验证文件路径与 file URI、content 身份、重复添加、失败、连续取消及播放队列/暂停/速度/位置。

已确认的修复前复现：`offset-red.log` 的两项断言分别为期望 1500、实际 0，期望 1000、实际 -500；`external-red.log` 为重复添加后轨道数由 2 变 3；`style-red-ready.log` 为真实 Media3 SRT 的字号控件不可见。`device-red-build.log` 为 `BUILD SUCCESSFUL`。最初 `style-red.log` 因测试视频读取权限失败，补齐模拟器运行时视频权限和背景夹具后才取得行为红灯；该环境失败不计作缺陷复现。

**状态：三项修复及本地验证完成，原片与物理电视验收仍待用户复测。** 本轮基于 HEAD `a33dd791a` 加未提交工作区，Media3 `1.11.1`；产物位于忽略的 `.tmp/subtitle-feature-fixes/`。不以这些缺陷解释原 115 视频的唯一原因。

### 实际改动

- 偏移：`PlayerInitializer.Subtitle.offsetPosition` 直接委托全局偏移读写，不再保留未回填的 0 初值，也不在对象初始化时提前读取 MMKV。设置界面、Media3 和 mpv 使用同一保存来源；Media3 继续单独记录已应用值，控制器先保存再通知内核时仍会真正更新。保留 mpv 的符号映射与既有全局保存规则。
- 样式：轨道元数据保留原始 MIME，面板按有效轨道区分普通文字、ASS、位图和 mpv。普通文字恢复字号、颜色、描边等可用设置；ASS 字号偏移不再叠乘普通文字大小。重置及“已修改”状态只涉及当前可用设置，不清空隐藏格式的配置；切轨隐藏控件或重置按钮消失后恢复可见焦点。
- 外挂：原始资源 URI 规范化后生成稳定 ID，文件路径与对应 file URI 等价，content URI 保留 provider/document 身份。重复添加复用已有配置和轨道，不重复读取、不替换 source；保留真实合并 ID、主线程排队及显式选择/取消优先权。原有预校验、16 MiB 上限、队列/暂停/速度/位置恢复不变。

额外取得 `scale-red.log` 的实际像素失败：改变 ASS 缩放会影响普通文字大小，已补像素回归。首次截图还发现位图重置后焦点丢失，修复后在应用用例中断言三类重置的可见焦点。`green-targeted-build.log` 的失败来自 PowerShell 未引用 `-P` 参数，已修正命令；`scale-red-build.log` 的 Kotlin daemon fallback 后构建成功。这些中间结果均不代替以下最终验证。

### 最终验证

最终 Gradle 调用经已有 `.tmp/watch-history/gradle.ps1` 使用本地 wrapper 和进程级 JVM 临时目录参数，未改仓库构建配置：

```text
testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
:player_component:assembleDebugAndroidTest
:player_component:lintDebug :app:lintDebug :data_component:lintDebug
:player_component:ktlintCheck :data_component:ktlintCheck
verifyModuleDependencies -I .tmp/subtitle-feature-fixes/scoped-format.gradle
-Pkotlin.compiler.execution.strategy=in-process --no-daemon --offline --console=plain
```

| 范围 | 结果与证据 |
| --- | --- |
| 构建、JVM 与静态检查 | `verification.log` 尾部为 **`BUILD SUCCESSFUL in 12m 45s`**，1114 项任务。`junit-summary.json` 按当前包含模块汇总 XML：535 项，533 通过、2 跳过，0 failures/errors；其中播放器 177 项全部通过。Debug lint 覆盖 app/player/data；定域 ktlint 仅覆盖本轮 player/data 相关文件，不代表全仓或 app 测试文件格式检查 |
| native / Media3 / mpv | `player-green.log` 为 **`OK (26 tests)`**，包括真实 ASS/mpv 像素、Surface/时间映射、外挂 ASS/SRT/VTT/TTML 及逐项重复添加、失败、连续 20 次取消、正负偏移、暂停/速度/位置/队列、普通文字不受 ASS 缩放影响 |
| 应用 DPAD | 最终构建的 `app-final.log` 为 **`OK (4 tests)`**：双内核 SRT/Off/侧栏、保存 1500 ms 后的界面值/步进/重置、按轨道样式切换与隐藏设置保留、可见焦点，以及 Media3/mpv 面板返回/暂停恢复。与上一行合计 30 个独立用例 |
| 大字体与视觉 | `app-large-font.log` 在 1.3 字体下重跑其中两项，为 **`OK (2 tests)`**，不重复计入 30 项。实际查看 `screenshots-final/` 与 `screenshots-large-font/` 的 SRT、ASS、位图、mpv 设置截图，1440×810 / 240dpi，无文字截断且焦点清晰；SRT/mpv 为真实加载，ASS/位图面板为格式能力夹具，不能据此宣称实际 PGS 几何验收 |
| 最终包冒烟 | ARM64 Debug `4.2.7-debug / 74` SHA-256 为 `a03ed85489b793b41fc7d47791e1a6cb94676f9ea68f5cf676f5628af18fc7ad`。`smoke-launch.log` 确认 TvSplashActivity 冷启动进入 TvMainActivity；DPAD 进入媒体库、返回并退出，XML 和 `smoke-exit.txt` 确认最终回到 Launcher |
| 任务环境清理 | `cleanup-status.json` 记录 **2026-11-04 21:50:50 +08:00**：任务模拟器 5574 对应进程 0，5574/5575/私有 ADB 5093 监听 0。没有访问电视或重置默认 ADB |

本轮仅构建和安装模拟器 Debug，没有修改版本、打包 Release、安装电视、暂存或提交；16:45 的电视 Release 不包含本轮三项修复。普通 Cue 偏移仍可能短暂缓冲；原生描边/阴影能力限制保留。跨集语义偏好、文字/位图几何、压缩块容错、Atlas 未随本轮实施，原 115 视频的根因与用户复测仍未关闭。截图和测试日志不加入 Git。

文档收尾：五份相关文档的 174 个本地链接、64 个锚点引用均存在，显式锚点无重复，见 `doc-links-final.json`；`git diff --check` 通过，暂存区为空。再次只读复查确认任务模拟器进程与私有端口监听均为 0。

<a id="atlas-rendering-implementation-2026-11-05"></a>

## Atlas 图集与合批实施（2026-11-05）

用户要求完成既有 Atlas 待办。本轮基于 `ba9d6d045`；进入时工作区干净。仅改应用 Media3/libass 绘制、测量及相关测试，mpv、提取器、时钟、字体输入、选轨与现有恢复所有权保持。任务模拟器为 API 34 / `emulator-5576`，私有 ADB `5095`，SwiftShader 与 ARM64 translation；原始证据保留在忽略的 `.tmp/atlas-rendering/`。

### 参考映射与实现

参考 Plezy 固定提交 `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5` 的 `AssSubtitleAtlasPipeline.kt`、`AssKt.c` 与本轮实际读取的 [AssPack.c](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/cpp/AssPack.c) / [AssPack.h](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/cpp/AssPack.h)：借鉴顶点携带颜色、连续合成区间装页、每页上传/绘制和强制重绘的职责，不复制其双线程、槽位、精确呈现、推测、预热或默认大缓冲。

- [ass_atlas.h](../../../player_component/src/main/cpp/ass_atlas.h) 使用顺序 shelf 打包，页数单调递增，最多四页。页宽按面积/最宽图像选择，页高按实际占用取整，默认边长不超过 2048 或实际 GL 限制；原链表绘制顺序不变。
- [ass_gpu_bridge.cpp](../../../player_component/src/main/cpp/ass_gpu_bridge.cpp) 复用一个 CPU 页缓冲和一份顶点流，每页一次纹理上传与 draw，整帧一次顶点上传。复制四周一像素边缘，保留 stride、原颜色/覆盖率/逐图像透明度、旋转、偏移及裁剪。
- 超过图集页数、加边缘后尺寸不合适、单图像及 GLES 2 缺少足够 fragment highp 时保留原逐图像路径，继续受 64 MiB/4096 图像/GL 尺寸防线约束；不截断内容，不引入 RGBA 合成语义变化。
- 首次、扩容、内容变化、样式/几何与 Surface 失效均重新填充；`changed == 0` 且当前内容有效时继续复用。换媒体释放 CPU 页/顶点/布局缓存和原字体/轨道/纹理，JNI 前后版本检查及串行线程保持。
- [AssGpuNativeBridge](../../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuNativeBridge.kt) 增加 pack、draw/bind、上传字节、纹理/staging、页数与 GLES 统计。测试控制仅供附着 Surface 前选择对照路径/缩小页/指定 GLES，无产品设置或持久化。pack 成本纳入现有节流预算；没有改变节流参数。

### 已取得的对照数据

`baseline-build.log` 尾部为 **BUILD SUCCESSFUL in 7m 4s**。优化前仅加入测量/测试控制的 APK 已单独保存；`baseline-tests.log` 的三项检查中，benchmark 通过，另两项分别在“必须合批”和“必须多页”处失败，确认检测到了原路径的缺口。

第一轮实现的五项检查通过，多页夹具因实际图像尺寸使其超过四页而进入正确回退，测试准备修正为能装入三页的小页夹具，后续该项通过。第一轮测量还显示逐字节清空 staging 的成本，改为 `memset` 后重新构建和测量；没有用删除内容换性能。

以下为最终 native 代码的同包对照；每组 180 帧、前 30 帧预热，后 150 帧全部为实际提交。素材包含中文移动对白、动画/卡拉 OK，以及 180 个半透明交叠图像。测试时未并行运行 Gradle；相同设备、字体、640×360 输出、时间序列和透明度。耗时为 `renderFrame()` CPU 调用中位数，包含排版/打包/上传/draw/swap，不是 GPU 完成或实际呈现时序。

| 素材 | GLES | 原路径 → Atlas draw/bind | 原路径 → Atlas 耗时 | 降幅 |
| --- | --- | --- | --- | --- |
| 移动对白 | 2 | 3 → 1 | 1.190 → 0.792 ms | 33.4% |
| 卡拉 OK | 2 | 9 → 1 | 3.089 → 2.296 ms | 25.7% |
| 密集叠加 | 2 | 180 → 1 | 14.892 → 6.299 ms | 57.7% |
| 移动对白 | 3 | 3 → 1 | 3.168 → 0.860 ms | 72.9% |
| 卡拉 OK | 3 | 9 → 1 | 9.078 → 2.211 ms | 75.6% |
| 密集叠加 | 3 | 180 → 1 | 14.678 → 6.184 ms | 57.9% |

原始逐帧数据为 `baseline-benchmark.json`、`atlas-benchmark-first.json` 和 `atlas-benchmark-final.json`。最终 Atlas pack 中位数为 0.025–0.040 ms（对白/卡拉 OK）与 0.352–0.359 ms（密集叠加）；上传分别约 0.112–0.126 ms 和 0.465–0.477 ms。图集增加上传/分配面积：对白纹理峰值由 32,207 增至 65,536 字节，卡拉 OK 由 41,079 增至 65,536 字节，密集叠加由 552,960 增至 1,048,576 字节。密集样本 CPU staging 为 1,087,256 字节，含一页像素、顶点和布局容量。纹理统计不含 VBO/驱动/libass；每个图像 192 字节的 VBO 数据继续受 4096 图像上限约束。

GLES 2/3 像素回归对比 RGBA 全通道，容差 2/255；覆盖交叠、描边/阴影、中文、动画、卡拉 OK、透明度、多页、小页回退、同时间重绘及 Surface 重建。已实际查看 `pixel-comparison.png` 的原路径/Atlas 对照，GLES 3 三个主要样本最大通道差分别为 0、1、2/255，没有位置、层叠或内容差异。旋转 0/90/180/270、非方形像素、缩放与垂直偏移的逐像素回归亦通过。原始 PNG 位于 `pixels/`。

### 最终验证与边界

最终 Gradle 调用经已有 `.tmp/watch-history/gradle.ps1` 使用本地 wrapper 和进程级环境，不改仓库构建配置：

```text
testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest lint
:player_component:ktlintCheck verifyModuleDependencies
-I .tmp/atlas-rendering/scoped-format.gradle
-Pkotlin.compiler.execution.strategy=in-process --offline --console=plain --continue
```

| 范围 | 结果与证据 |
| --- | --- |
| 构建与静态检查 | `verification.log` 尾部为 **`BUILD SUCCESSFUL in 7m 39s`**，1117 项任务；Debug 应用、根 `lint` 和模块依赖治理通过，native 同时构建 ARM64/ARM32。ktlint 定域于本轮修改的 Kotlin 文件，不代表全仓格式检查；播放器测试 APK 的最终构建记录 `atlas-focused-build.log` 同样为 `BUILD SUCCESSFUL` |
| JVM | `junit-summary.json` 按当前包含模块汇总 XML：**536 项，534 通过、2 项既有跳过，0 failures/errors**；播放器 178 项全部通过，新增回归确认打包成本计入现有节流预算 |
| GLES 与播放器 | `player-final.log` 为 **`OK (37 tests)`**，其中 7 项新 Atlas 用例；覆盖 GLES 2/3 画面与合批、多页/回退、旋转/PAR/缩放/偏移、整帧预算拒绝与恢复、seek/字体/Surface/媒体清理，以及已有 Media3/mpv 字幕和时钟契约 |
| 循环与释放 | 每个 GLES 执行 3 轮、每轮 1800 帧、16 ms 节奏，并反复 seek/调整字体。`atlas-resource-cycles-final.json` 的六轮纹理峰值均为 65,536 字节，CPU staging 均为 67,736 字节；释放断言通过。native heap 快照约 23.735–23.747 MB，无单调增长；进程 PSS 快照约 221–227 MB。这些是所列循环与快照，不能据此宣称数小时播放或进程峰值内存已验证 |
| 真实视频掉帧 | `atlas-video-drops.json` 的 GLES 2/3 × 两条路径各运行 8 秒，均解码并以视频驱动 16 帧字幕，视频掉帧均为 0。夹具本身帧率低，不能据此宣称高帧率/4K 播放掉帧改善 |
| 应用与 DPAD | `app-final.log` 为 **`OK (6 tests)`**：真实 Media3 ASS 像素、弹幕层、DPAD/暂停/BACK/双 Surface 重建，mpv 原生字幕与设置/返回，以及四项设置持久化/重建回归。合计 43 项设备用例通过 |
| 最终包冒烟与画面 | ARM64 Debug `4.2.7-debug / 74` SHA-256 为 `ff35eabdad263370f9827801992eec536538a7a75f912ed619bc9fb03998dd33`。实际查看 Media3/mpv 字幕截图及启动/媒体库/BACK 截图，1440×810 / 240dpi，字幕与焦点可见；冷启动后纯 DPAD 进入媒体库、返回导航项并退出，`smoke-exit.txt` 确认 Launcher 恢复 |
| 环境清理 | `cleanup-status.json` 记录 **2026-11-05 11:59:53 +08:00**：任务模拟器 5576 及私有 ADB 5095 已关闭，对应进程和 5576/5577/5095 监听均为 0；未重置默认 ADB 或访问物理电视 |

本轮没有修改版本、打包 Release、安装电视、暂存或提交；电视现有 Release 不含本轮 Atlas。当前收益只代表所列 API 34 模拟器，图集内存/上传面积代价见上表，不代表物理电视 SoC、PQ/HDR 或原 115 视频已经验收。压缩字幕局部容错仍是独立未实施待办；精确呈现、线程拆分和预热保持未启用。

文档收尾：同步维护说明、设计导航、历史状态提示与 memory，Atlas 待办已勾选完成。初次链接检查发现设计文档两处已删除旧 decoder 的链接，已映射到当前提取器和样本回归。最终八份文档的 268 个本地链接、73 个锚点引用均存在，显式锚点无重复，UTF-8 与尾随空白检查通过，见 `doc-links-final.json`；`git diff --check` 通过，暂存区为空。
