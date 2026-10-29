# libass 接入审查与后续任务依据

审查日期：2026-10-28；修复更新：2026-10-29。本文保留审查时的问题依据和后续验证，当前实现以本节修复状态及[字幕渲染](subtitle-rendering.md)为准，未进行库迁移。

## 2026-10-29 修复状态

| 问题 | 实际变更与证据 | 剩余边界 |
| --- | --- | --- |
| ASS-01 | 显示开关接到 renderer；隐藏提交透明帧并保留轨道；清轨释放 native track。Media3 decoder 释放通知原 sink，静音中的内嵌释放不再清掉外挂，sink 可重新初始化。渲染命令改为 FIFO，合并帧请求仍排在状态修改之后。native 像素测试及 Media3 禁用/重启测试通过 | 尚未逐项完成 Media3、mpv 与 TV UI 的完整验收矩阵 |
| ASS-02 | JNI 文件/内嵌初始化返回 Boolean；外挂加载挂起等待真实结果，取消或失效请求清理轨道，成功后才更新状态。失败不再静默改用简化 ASS 解析；清理记录实际 renderer 所有者。检查 EGL swap 结果并提示渲染故障。有效、空白、损坏、缺失文件和失败后恢复的真实渲染测试通过 | 未注入 EGL swap 失败；故障注入与目标电视仍待验证 |
| ASS-03 | 补齐 GLES 2 shader、GL_ALPHA 纹理和紧密行上传；透明 surface 排除无 alpha 配置。attach 按 surface/GL 错误处理；旧绑定任务按修订号丢弃，JNI 检查 Surface 有效性，销毁同步解绑，移除布局驱动的提前/重复绑定。EGL 释放先解绑，重建强制重绘；失效 surface 和重建像素测试通过 | GLES 2 分支已编译，尚未在 GLES 2 电视验证；无 CPU 自动接管 |
| ASS-04 | 本轮未增加 MKV 附件提取与字体传递 | 仍未关闭，需要容器/内核资源链路实现及字体画面对比 |
| ASS-05 | 修复额外发现的 decoder 实例共用缓冲池、sink 更换未重传格式和 native 毫秒时间被截为 Int；新增实例隔离、释放归属与大时间戳回归 | 现有 Media3 legacy API 和时间基数推断保留，复杂多 period 及集成库选型仍未关闭 |
| ASS-06 | 新增实际 JNI/EGL/libass 输出像素断言，而非仅检查 RecordingSink；补充字幕加载归属和 SRT 正常回退测试 | 复杂 ASS 动画、附件字体、HDR、物理电视和长时资源曲线仍待验证 |

失败策略：新的外挂加载失败时清除失败轨道并恢复内嵌输入，不保留旧外挂画面。普通 SRT 等已有文字解析格式继续正常回退，不再误弹“libass 不支持”提示。没有新增全局管理器、渲染后端或依赖。

## 结论与适用范围

以下为 2026-10-28 审查结论，代码缺口的当前状态见上表。审查时接入已具备功能框架，但显示状态与 native 轨道生命周期未完整联动、加载结果丢失、GLES 兼容路径不一致；此外有 MKV 字体附件缺失、Media3 接口与时间轴适配风险及最终渲染验证不足。

独立 overlay、独立渲染线程及多内核字幕桥接具有实际用途，可以保留。后续应先补齐状态、错误和资源管理，再评估是否复用现成的 Media3 libass 集成库。新增管理层或整体重写不是本次审查的结论。

### 证据基线

- 本地源码基线：`99f63887e6428efa3a2eac9e48b78777f88f8d39`，分支 `master`；文档整理前 `git status --short` 无变更，构建与临时输出位于忽略目录。源码位置以下述文件和符号为准，行号是本次核对时的辅助定位。
- Media3：`1.11.1`，版本来源为 [gradle.properties](../../gradle.properties) 的 `media3Version`；本项目工具链已升级，不应套用旧 AGENTS 中的 Kotlin/AGP 版本描述。
- 已检查：字幕设置与操作入口、外挂字幕管理、Media3 原始 ASS 解码、mpv `ass-full` 桥接、共享 libass 会话、JNI/EGL/OpenGL、字体入口、相关 JVM 和仪器测试源码。
- 外部比较：Jellyfin Android TV、peerless2012/libass-android、mpv、VLC 的公开主分支源码。未固定外部提交 SHA，也未在同一设备做性能对照；后续选型时必须重新核对拟采用版本。
- 本次审查执行了 23 项相关 JVM 测试；未执行 native GPU 画面对比、真机兼容、HDR 或长时间播放验收。
- 搜索工具限制：`rg`、`ast-grep`、`jscpd` 不可用，语义搜索工具启动失败；使用 `git grep`、`git ls-files` 和目标文件逐段读取。本文不包含 AST 精确扫描、重复率或安全扫描结论。

### 证据等级

| 标记 | 含义 |
| --- | --- |
| 源码确认 | 可从当前调用链、声明或配置直接确认 |
| 测试确认 | 本次实际执行的命令与结果，范围见验证记录 |
| 运行风险推断 | 根据源码推断触发条件及影响，尚未用设备画面复现 |
| 待评估方案 | 后续建议，不代表已批准实施或已证实优于现状 |

本文中的字幕残留、布局变化和特定设备失败表现属于运行风险推断。不能把这些表现写成已完成真机复现，也不能以 JVM 测试通过否定调用链中已确认的缺口。

## 当前接入结构

共享自定义渲染链为：

```text
Media3 ASS 原始样本 ───────────┐
mpv ass-full 活动事件桥接 ─────┼─> LibassGpuSubtitleSession
外挂 ASS/SSA 文件 ────────────┘      -> AssGpuRenderer
                                    -> AssGpuNativeBridge
                                    -> ass_gpu_bridge.cpp
                                    -> 独立字幕 overlay
```

这里的 GPU 路径仍调用 libass 生成字幕图像，再上传纹理并通过 OpenGL 合成；不能据此推定复杂 ASS 的全部计算都转移到了 GPU。

| 场景 | 当前职责分配 | 关键入口 |
| --- | --- | --- |
| 后端初始化 | 播放页固定设置 `LIBASS`；实际后端还受内核能力约束 | [PlayerActivity.kt](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt)，固定后端设置，约 907 行；[DanDanVideoPlayer.kt](../../player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt)，后端创建，约 363 行 |
| 用户设置 | 后端设置被隐藏，读取固定返回 LIBASS，写入不执行切换 | [SubtitleSettingFragment.kt](../../user_component/src/main/java/com/xyoye/user_component/ui/fragment/SubtitleSettingFragment.kt)，约 87 行 |
| Media3 内嵌 ASS/SSA | 自定义 TextRenderer/decoder 把原始样本送入 sink，返回空 Cue 避免重复显示 | [LibassAwareRenderersFactory.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassAwareRenderersFactory.kt)、[LibassSsaStreamDecoder.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassSsaStreamDecoder.kt) |
| 外挂 ASS/SSA | 直接向自定义 native 渲染器加载文件，期间关闭内嵌 sink | [LibassRendererBackend.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt)、[LibassGpuSubtitleSession.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) |
| mpv `mediacodec_embed` | 监听 `ass-full`、轨道和 codec private，解析活动事件后送入共享渲染器 | [MpvEmbeddedSubtitleBridge.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvEmbeddedSubtitleBridge.kt) |
| mpv `gpu`/`gpu-next`、VLC | 使用内核字幕能力；不能与自定义 libass 实例的行为混为一谈 | [MpvVideoPlayer.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt)、[mpv 配置说明](../mpv_conf_supported_options.md) |
| 普通文字、位图字幕 | 仍有原字幕控制器或内核路径；自定义后端不消费 `MixedSubtitle` | [LibassRendererBackend.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt)，`render()`；[SubtitleController.kt](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt) |

因此，不能把当前行为概括为“用户选择是否开启 libass”或“所有字幕都走同一个 libass 渲染器”。mpv `mediacodec_embed` 下内嵌位图字幕的既有限制也没有因接入 libass 自动解决。

## 问题清单

以下源码描述与行号保留审查时基线，不代表修复后代码仍存在同样缺陷。P1 为核心行为/设备可用性缺口，P2 为能力、维护及验收缺口；最新实现和验证范围见修复状态表。

### ASS-01 / P1：显示开关、清除轨道和清屏职责未完整接通

**源码确认：**

- [SubtitleController.kt](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt) 的 `setUserSubtitleVisible()` / `clearSubtitleViews()`（约 66–104 行）只管理文字、图片视图。未发现这一可见状态传给自定义 GPU session/overlay 的路径；TV 和移动端字幕切换动作最终使用该入口。
- [LibassGpuSubtitleSession.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) 的 `clearActiveTrack()`（约 122 行）重新启用并注册内嵌 sink，没有明确移除 native 外挂轨道。
- [SwitchableEmbeddedSubtitleSink.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/SwitchableEmbeddedSubtitleSink.kt) 的 `setEnabled()` 仅在存在缓存格式时重放格式。没有可恢复内嵌 ASS 格式时，不能依赖该操作覆盖旧外挂轨道。
- [SubtitleFrameCleaner.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitleFrameCleaner.kt) 调用的 `nativeFlush()` 位于 [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp)，约 992 行，实际只执行 `glFinish()`；没有清除 framebuffer 并提交透明帧，也没有清除 libass 事件。
- Media3 的 `deselectTrack()` 只修改轨道选择参数；decoder 没有负责通知共享 sink 清除轨道的 release 覆盖。取消选择与独立 native 实例之间缺少明确的生命周期联动。

**触发与影响推断：**正在显示自定义 ASS 时关闭字幕，overlay 可能继续显示；无内嵌 ASS 的影片取消外挂 ASS，旧 native 轨道可能继续输出；暂停或切轨边界不能仅靠当前 flush 消除残留帧。

**最小处理方向：**在现有 session 明确区分隐藏显示、清除当前轨道、清空嵌入事件和 seek 重绘；让所有字幕关闭入口控制真实输出。隐藏字幕应保留所需时间与轨道状态，清除字幕应明确释放或解除选中轨道，不能把两者都实现为清空文本视图。

**复杂度检查：**复用现有 session、renderer 和控制器，补齐调用契约，减少多个状态所有者之间的隐式假设；无需新建全局字幕管理器。

**验收：**Media3 与 mpv `mediacodec_embed` 分别覆盖播放中/暂停时隐藏和恢复、无内嵌轨时取消外挂、内外轨互切、ASS 切普通文字轨、禁用全部字幕。记录最后一帧被清除以及恢复后时间同步的画面。

### ASS-02 / P1：native 加载与输出结果没有可靠传回上层

**源码确认：**

- [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp) 的 `nativeLoadTrack()`（约 692–719 行）返回 `jboolean`，`ass_read_file()` 失败返回 `JNI_FALSE`。
- [AssGpuNativeBridge.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuNativeBridge.kt) 的同名 JNI 声明（约 190 行）没有 Boolean 返回值，调用方拿不到结果。
- [AssGpuRenderer.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuRenderer.kt) 的 `loadTrack()`（约 172 行）在 native 加载前设置 `trackLoaded = true`。
- [LibassRendererBackend.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt) 的 `loadExternalSubtitle()`（约 56 行）提交异步任务后返回 `true`，日志也提前报告 loaded。
- native 渲染中的 `eglSwapBuffers()`（约 975 行）未检查返回值，后续返回成功。不能以该成功值证明画面已提交。

**触发与影响推断：**文件被删除、不可读或 ASS 解析失败时，上层可能仍标记加载成功；后续无轨道渲染又可能归类为 GL 错误。EGL 交换失败也可能没有正确反馈，影响用户提示与恢复判断。

**最小处理方向：**统一 JNI 声明与返回值，沿现有异步调用链传递加载完成结果；成功后才更新 loaded 状态。区分读取/解析失败与 surface/EGL 输出失败，并检查交换结果。失败时旧轨道保留还是清除，需要在实际任务中明确并覆盖测试。

**复杂度检查：**修正现有接口含义，避免用后续渲染失败猜测加载状态；不引入另一套重试或异常管理框架。

**验收：**有效、空白、损坏、缺失文件及加载后替换文件；模拟输出失败；确认状态、日志和用户反馈一致，失败恢复后可以重新显示有效字幕。

### ASS-03 / P1：ES 2 配置与 ES 3 渲染实现矛盾，降级未提供替代输出

**源码确认：**

- [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp) 的 `EnumerateAndScoreConfigs()`（约 185 行）允许选择 GLES 2 context；shader（约 27、36 行）固定为 `#version 300 es`，纹理路径使用 `GL_R8`/`GL_RED`。配置选择本身不构成可工作的 GLES 2 渲染路径。
- [AssGpuRenderer.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuRenderer.kt) 的 `bindSurface()` 把 native attach 失败统一归为 `UNSUPPORTED_GPU`，未区分暂时的 surface/context 故障与实际能力不支持。
- [SubtitleRecoveryCoordinator.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitleRecoveryCoordinator.kt) 明确跳过 `UNSUPPORTED_GPU`；[PlayerActivity.kt](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 的 `onSubtitleBackendFallback()`（约 579 行）只记录日志。
- `FALLBACK_CPU` 是保留的降级状态名称，当前不存在接管输出的 CPU 渲染器。旧 `libass_bridge.cpp` 的存在不是证据：[CMakeLists.txt](../../player_component/src/main/cpp/CMakeLists.txt) 当前构建的自定义字幕桥接源文件是 `ass_gpu_bridge.cpp`。

**触发与影响推断：**仅支持 GLES 2 的电视无法依靠当前配置回退显示字幕；临时 attach 失败也可能被归入协调器不重试的原因。后者不等于所有其他 surface 回调都永远不能恢复，必须按实际生命周期验证。

**最小处理方向：**先明确最低 GPU 能力并修正错误分类；再根据支持设备范围决定实现真正的兼容合成路径，或提供明确、可操作的失败处理。若选 Canvas，仍需验证复杂动画下的开销；不能因另一项目提供 Canvas 就宣称本项目已有自动回退。

**复杂度检查：**先消除无效兼容分支和误分类，再增加有真实设备需求的输出方式。保留受控恢复边界，避免每帧重建 EGL/libass。

**验收：**至少包含目标 GLES 3 电视、明确的 GLES 2 能力处理、surface 销毁重建、暂时 attach 失败和输出交换失败。记录实际设备/GPU/系统、恢复触发及最终画面。

### ASS-04 / P2：MKV 字体附件没有接入当前自定义 libass 实例

**源码确认：**

- [LibassGpuSubtitleSession.kt](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) 的 `buildFontDirectories()` 使用项目字体目录与默认字体。
- [SubtitleFontCacheHelper.kt](../../core_storage_component/src/main/java/com/xyoye/common_component/utils/subtitle/SubtitleFontCacheHelper.kt) 支持外部字体文件缓存。因此，不能把现状写成“不支持自定义字体”。
- [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp) 的 `ConfigureFonts()`（约 315 行）选取第一个有效目录，配置默认字体并使用 `ASS_FONTPROVIDER_NONE`；当前共享桥接没有 `ass_add_font()` 调用，也未找到 MKV 字体附件转交该实例的链路。
- mpv `ass-full` 传递事件文本与轨道信息，不会自动把内核实例拥有的附件字体同步到项目创建的另一个 libass 实例。

**触发与影响推断：**当字幕依赖 MKV 附件中的字体且项目目录中没有等价字体时，可能出现字体替代、字宽和换行改变、标牌定位偏差或缺字。该发现限定于自定义共享渲染路径，不能推广为 mpv/VLC 原生字幕路径也不支持附件。

**最小处理方向：**将容器字体附件按媒体会话传入现有字幕实例，明确切换影片时的字体生命周期；不以继续增加默认字体替代附件支持。

**复杂度检查：**沿现有媒体数据与会话边界补齐缺失资源，不另建全局字体服务，也不把所有影片附件永久堆入同一缓存。

**验收：**同一 ASS 配合正确字体、缺失字体、MKV 附件字体三组样本；包括不同影片中的同名字体、标牌、中文/日文和复杂排版；用固定时间点画面比较确认字体实际生效。

### ASS-05 / P2：Media3 扩展点与时间基准依赖增加升级风险

**源码确认：**

- [LibassAwareRenderersFactory.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassAwareRenderersFactory.kt) 替换 TextRenderer 并调用 `experimentalSetLegacyDecodingEnabled(true)`。
- [LibassSsaStreamDecoder.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassSsaStreamDecoder.kt) 的 `normalizeSampleTimeUs()` / `inferSampleRelativeAnchorUs()`（约 252–321 行）在部分路径按 `1_000_000_000_000` 微秒步长推断 renderer 基准。
- [Media3SubtitleFrameDriver.kt](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleFrameDriver.kt) 将视频帧时间用于独立字幕渲染；样本时间与绘制时间需要保持同一基准。

**风险性质：**这是维护与复杂时间线风险，尚无本次测试证明当前版本已经出现同步错误。现有测试覆盖了部分时间戳归一化行为，不能据此保证跨 period、非零起播和连续换片的全部场景。

**最小处理方向：**评估当前 `SubtitleParser.Factory` 等扩展点，优先使用明确的时间偏移信息；保留原始 ASS 动画能力。任何适配变更都应先明确样本时间、播放器位置、渲染时间的语义。

**复杂度检查：**以减少旧接口适配和猜测规则为目标，不仅为了使用新 API 而增加第二套完整字幕流程。

**验收：**零点/非零起播、连续播放、反复前后 seek、暂停 seek、倍速、切轨，以及项目实际支持的多 period 输入；同时核对样本时间日志和最终画面。

### ASS-06 / P2：现有测试不足以证明最终渲染正确

**源码与测试确认：**本次 23 项 JVM 测试覆盖 decoder、格式分类、mpv 解析/去重、sink 开关、管线状态和负载策略；详见下文。现有 [Media3SubtitleCompatibilityTest.kt](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3SubtitleCompatibilityTest.kt) 使用 `RecordingSink`，禁用视频轨道，主要检查数据、时间和普通 Cue 回退，没有验证自定义 native GPU 输出的最终像素。

**影响：**上述测试可以在 ASS-01 至 ASS-04 仍存在时全部通过。历史 Media3 升级仪器测试的通过记录也不能自动关闭这些问题，覆盖限制见[升级账本](../../docs/superpowers/plans/2026-10-28-media3-upgrade.md)。

**最小处理方向：**状态/错误契约用单元或集成测试固定，JNI、字体、surface 和视觉行为用真实渲染链验证。新增测试应复现可观察行为，避免只断言 setter 被调用。

**复杂度检查：**按风险补齐小规模可复用样本与验收流程，无需先建立大型性能平台。

## 横向比较

| 实现 | 接入组织 | 值得借鉴的能力 | 适用边界 |
| --- | --- | --- | --- |
| DDPlayTV | 应用自己桥接多个内核并拥有独立 libass/GL overlay | 可共享显示参数和多内核渲染；已有帧合并和恢复基础 | 轨道、时钟、字体和生命周期需要自行闭合 |
| Jellyfin Android TV + libass-android | Media3 parser、Matroska extractor、renderer 与 overlay 配套接入 | 接近本项目 Android TV 场景，可减少应用维护的集成代码 | 使用库不代表消除 Media3 内部接口依赖或保证每台电视性能 |
| mpv | 原始字幕包直接进入内核 libass 管线 | 字体附件、原始包时间、缓存限制及按版本支持的事件裁剪 | 本项目 `ass-full` 到第二实例的桥接不能自动继承全部能力 |
| VLC | libass decoder 产生字幕图像并进入内核队列 | 解码、时间调度、合成及资源生命周期协作 | 是原生播放器管线参考，不是可直接替换 Media3 的组件 |

### Jellyfin / libass-android

Jellyfin Android TV 的 [VideoManager.java](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java) 根据 ASS direct play 设置创建 `AssHandler(OVERLAY_OPEN_GL)`，安装 parser/extractor/renderer，并将字幕 overlay 加入播放器字幕视图。这说明独立 overlay 本身是可行的组织方式。

libass-android 的具体参考点：

- [AssHandler.kt](https://github.com/peerless2012/libass-android/blob/master/lib_ass_media/src/main/java/io/github/peerless2012/ass/media/AssHandler.kt)：未选择 ASS 轨时解除渲染轨并禁用 overlay；管理待加载字体、缓存限制和渲染尺寸。
- [AssMatroskaExtractor.kt](https://github.com/peerless2012/libass-android/blob/master/lib_ass_media/src/main/java/io/github/peerless2012/ass/media/extractor/AssMatroskaExtractor.kt)：读取容器字体附件并调用 `addFont()`；同时通过反射访问 Media3 字段，这是升级时仍需验证的依赖。
- [AssSubtitleParserFactory.kt](https://github.com/peerless2012/libass-android/blob/master/lib_ass_media/src/main/java/io/github/peerless2012/ass/media/parser/AssSubtitleParserFactory.kt)：使用 `SubtitleParser.Factory` 接入，处理 ASS，其他格式委托默认解析。
- [集成说明](https://github.com/peerless2012/libass-android/tree/master/lib_ass_media)：提供 Cue、Canvas 和 OpenGL 等显示方式，外挂字幕使用 `MediaItem.SubtitleConfiguration`。Cue 模式有动画能力限制；提供多模式不等于自动故障切换。
- [AssRenderer.kt](https://github.com/peerless2012/libass-android/blob/master/lib_ass_media/src/main/java/io/github/peerless2012/ass/media/render/AssRenderer.kt)：当前仍减去固定的 `1000000000000L` 时间基数。因此，不能宣称迁移该库自动解决 ASS-05。

这些比较依据为源码和项目说明。本文不采纳 README 中的内存/性能比例作为 DDPlayTV 的测量结论，也不把库列出的 HDR 能力视为本项目已通过 HDR 验收。

### mpv 与 VLC

mpv 的 [sub/sd_ass.c](https://github.com/mpv-player/mpv/blob/master/sub/sd_ass.c) 将容器字体附件通过 `ass_add_font()` 交给 libass，直接处理 codec private 和字幕包，并设置缓存上限；满足相应 libass 版本条件时还配置事件裁剪。参考重点是资源、时间和内核生命周期的配合。

VLC 的 [modules/codec/libass.c](https://github.com/videolan/vlc/blob/master/modules/codec/libass.c) 加载附件字体，按输入包 PTS/时长调用 `ass_process_chunk()`，将结果交给字幕图像队列，并维护解码器与排队字幕共享资源的生命周期。不能将其 flush 行为简单描述为每次都清空 libass 所有事件。

对本项目而言，mpv `gpu`/`gpu-next` 和 VLC 已有原生路径，应优先利用已有能力。是否改变 mpv `mediacodec_embed` 必须结合 TV 视频输出与性能约束评估，不能仅因字幕集成较复杂就自动切换输出模式。

## 保留项与尚未成立的问题

- 保留独立渲染线程、帧请求合并和已有纹理复用；它们服务于动态字幕与 UI 响应，不能只因类多或调用链长就删除。
- 保留受控恢复边界；避免逐帧重新创建 EGL/native/libass。
- 保留 mpv 去重的 512 项上限与 60 秒历史窗口；这些仅限制 parser 去重状态，不代表整个 native 会话内存恒定。
- 未发现应用显式设置 native libass 缓存上限或完整事件裁剪策略；应测量长播放资源曲线，不能据此直接认定内存泄漏或库自身完全无缓存限制。
- 审查时 handler 混用普通和队首入队；2026-10-29 已统一 FIFO，并保留单个待渲染帧的合并，避免加载/清除及暂停重绘倒序。
- 未审计所有第三方二进制的可复现构建、安全问题及全部 ABI 行为，也未测量各实现的帧率、内存或 HDR 色彩一致性。

## 后续任务建议与关闭条件

以下保留原建议顺序和关闭条件，已实现部分见顶部状态表；库迁移原型尚未实施。

| 顺序 | 任务 | 完成条件 |
| --- | --- | --- |
| 1 | ASS-01：统一关闭、隐藏、清轨与清屏语义 | UI 状态与真实 overlay 输出一致；内外轨切换无旧字幕残留 |
| 2 | ASS-02：贯通加载与输出结果 | 文件/解析/图形错误分类准确；成功状态只在操作真正成功后更新 |
| 3 | ASS-03：明确 GPU 要求和失败行为 | 无无效 ES 2 兼容分支；暂时故障与能力不足分开；支持范围内有验证过的输出或恢复路径 |
| 4 | ASS-04：传递 MKV 字体附件 | 指定字体确实被使用，换片时资源隔离正确，参考画面排版一致 |
| 5 | ASS-05：评估 Media3 集成方式 | 原型比较现有实现与选定版本 libass-android；以减少自维护代码、保留功能及验收通过决定是否迁移 |
| 贯穿 | ASS-06：补行为与画面证据 | 每项修复有对应复现与验证；GPU、字体和兼容性结论有真实渲染证据 |

有限范围的 Media3 原型至少覆盖：内嵌/外挂 ASS、字体附件、动画与卡拉 OK、字幕偏移与缩放、轨道禁用、surface 重建、非零起播及 seek。记录新增依赖和删除的自维护代码，同时验证 TV、视频输出与 HDR 约束。原型结果出来前，不预设全面替换结论。

### 验收矩阵

| 编号 | 场景 | 验证方式与预期 | 状态 |
| --- | --- | --- | --- |
| V01 | 内嵌/外挂 ASS 显示开关 | 播放与暂停分别操作；隐藏立即无字幕，恢复时间正确 | renderer 隐藏/同时间恢复的 native 像素通过；完整 UI/双内核矩阵待验 |
| V02 | 无内嵌 ASS 时清除外挂 | 清除后无后续旧轨道输出，无最后一帧残留 | native 清轨、透明帧和无后续输出通过 |
| V03 | 内外轨、ASS/普通文字轨互切 | 轨道唯一生效，无重复字幕、串轨或错时 | 共享 sink 内外轨互切、重复释放重启及 Media3 禁用/重启通过；普通 SRT 回退 JVM 通过 |
| V04 | 无效文件与加载失败 | 单元/集成测试加真实加载；状态和提示一致，可加载有效文件恢复 | native 有效/空白/损坏/缺失及恢复通过；提示端到端未验 |
| V05 | MKV 附件与字体隔离 | 固定时间点画面对比，不同影片同名字体不互相污染 | 未验证 |
| V06 | 移动、渐变、变换、卡拉 OK、重叠标牌 | 复杂 ASS 样本对比，检查动画连续性、层级、裁剪和排版 | 未验证 |
| V07 | seek、非零起播、倍速与连续换片 | 样本/渲染时间日志和画面一致，暂停 seek 后刷新正确 | 未完整验证；已有部分数据级测试 |
| V08 | surface 销毁重建与图形故障 | 真实 overlay 重建；错误原因准确，无每帧重建循环，恢复结果可见 | ImageReader surface 上 EGL 销毁重建/同帧重绘通过；故障注入和真实 overlay 矩阵待验 |
| V09 | 目标电视 GPU、不同输出尺寸与 HDR | 记录设备/系统/GPU、内核/输出模式及画面；不能用模拟器替代所有设备结论 | 未验证 |
| V10 | 长时间播放和复杂字幕负载 | 记录 native/进程内存、纹理峰值、帧延迟、降载及恢复；比较趋势，不预设泄漏 | 未验证 |
| V11 | TV 与移动端控制回归 | TV 全程 DPAD 完成设置、关闭、切轨与返回；移动端仍可操作；验证设置持久化 | 未验证 |

V01–V08 中适用的共享渲染场景需要分别覆盖 Media3 与 mpv `mediacodec_embed`。mpv 原生输出和 VLC 作为行为对照，不能代替共享渲染链验收。测试证据按[项目格式](../monitoring/test_evidence_format.md)记录。

## 已执行验证

### 2026-10-29 修复验证

- 最终命令：`scripts/dev-env/gradlew-local.bat :player_component:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint :player_component:ktlintCheck verifyModuleDependencies verifyPlaybackBoundaries -I .tmp/handoff14-local.gradle -I .tmp/media3-upgrade/resolver-cache.gradle -I .tmp/libass-style.gradle --offline --max-workers=4 --continue --console=plain`，尾部 **BUILD SUCCESSFUL in 1m 16s**。包含 ARM64/ARMv7 native 构建、Debug/测试 APK、全量 JVM、全量 lint、变更文件 ktlint 和治理；未构建本轮 Release。
- JUnit XML 汇总 387 项：**385 通过，0 失败/错误，2 个外部协议夹具跳过**。新增 6 项 JVM 回归，覆盖 decoder 缓冲池隔离、释放归属、静音释放缓存、重启、SRT 回退和外挂 renderer 所有权。
- API 34 `Pixel_5_API_34`，`emulator-5558`、`device`、`sys.boot_completed=1`，x86_64 镜像通过 ARM64 转译运行打包 native 库。`LibassGpuRenderingTest` 5 项与 `Media3SubtitleCompatibilityTest` 3 项全部通过（**OK (8 tests)**，2.146 秒）。前者包含 ImageReader 实际像素断言，覆盖透明清屏、相同时间点恢复、surface 重建、内外轨释放/切换、错误文件和超过 Int 范围的时间戳，以及排队期间 surface 失效的错误分类；后者覆盖 ASS 数据、前后 seek、轨道禁用/重启及普通 Cue 路径。它们不等于物理电视、复杂字体或全播放器视觉验收。
- 初始离线测试因 Robolectric 缓存映射缺失失败，复用既有本地 resolver 脚本后解决；新缓冲池的 Kotlin 可空数组编译错误及临时 ktlint 脚本误作用于 buildSrc 已修正，最终命令通过。第一次 app 冒烟命令误用了不带 `.debug` 的 instrumentation 包名，没有执行测试，后续按 APK 元数据修正。
- 播放器组合回归首轮 3/4 通过，Media3 用例因 Activity 提前销毁失败；单独重跑该用例通过。随后组合重跑在字幕线程 `eglCreateWindowSurface` 进入模拟器 ARM 转译/Goldfish 驱动时崩溃，促使补齐上表 surface 生命周期修复。修复后的 8 项字幕测试通过，但组合冒烟又被模拟器 `system_server` 的 `Failed to create context, error = EGL_BAD_CONFIG` 中断。保留全部证据，不把早期重试写成通过；该 AVD 同时存在系统 GLSL shader 错误，不能仅凭 native 崩溃栈断言是应用单一根因。
- 随后以 `-no-snapshot -read-only -gpu swiftshader_indirect` 冷启动同一 AVD，重新安装相同最终 APK。8 项字幕测试再次 **OK (8 tests)**（1.783 秒），播放器组合回归 **OK (4 tests)**（23.932 秒）：Media3/mpv 即时 seek 持久化、加密请求重入/重建/DPAD 返回、内核重试保留播放源/位置/全局偏好。冷启动后的 crash buffer 无记录。此处为移动 UI mode 的软件 GPU/ARM 转译验证，不等于物理 TV 或宿主 GPU 故障已根治；未修改应用测试断言。
- 播放器运行命令：`adb -s emulator-5558 shell am instrument -w -r -e class com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#completedSeekImmediatelyPersistsEncryptedRecoveryPosition,com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#mpvCompletedSeekPersistsRecoveryPosition,com.okamihoro.ddplaytv.playback.PlaybackHandoffInstrumentedTest#encryptedRequestReentryAndRecreationKeepOneActivityAndOwnedSources,com.okamihoro.ddplaytv.playback.PlaybackEntriesInstrumentedTest#kernelRetryKeepsActiveSourcePositionAndGlobalPreference com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`。字幕命令的 class 为 `com.xyoye.player_component.ui.LibassGpuRenderingTest,com.xyoye.player_component.ui.Media3SubtitleCompatibilityTest`，runner 为 `com.xyoye.player_component.test/androidx.test.runner.AndroidJUnitRunner`。
- 冷启动最终日志：`.tmp/libass-device-cold.log`、`.tmp/libass-playback-cold.log`。本轮仅启动只读、不保存快照的测试实例，不改 AVD 配置；完成后关闭测试实例。
- 本机输出：`.tmp/libass-complete-validation.log`、`.tmp/libass-jvm-counts.json`、`.tmp/libass-device-complete.log`、`.tmp/libass-playback-crash.log`、`.tmp/libass-system-crash.log`。临时脚本只配置本机离线缓存和变更文件检查范围，不改变生产依赖。

### 本次审查的 JVM 测试

执行日期：2026-10-28。以下命令在仓库根目录通过本机离线缓存执行；本节记录的是审查阶段结果，文档整理没有重新执行这些测试。

```powershell
$auditTestArgs = @(
    ':player_component:testDebugUnitTest'
    '--tests', '*LibassSsaStreamDecoderTest'
    '--tests', '*Media3SubtitleFormatClassifierTest'
    '--tests', '*MpvAssFullSubtitleParserTest'
    '--tests', '*SwitchableEmbeddedSubtitleSinkTest'
    '--tests', '*LocalSubtitlePipelineApiTest'
    '--tests', '*SubtitleLoadSheddingPolicyTest'
    '-I', '.tmp/handoff14-local.gradle'
    '-I', '.tmp/media3-upgrade/resolver-cache.gradle'
    '--offline', '--max-workers=4', '--console=plain'
)
& .\scripts\dev-env\gradlew-local.bat @auditTestArgs
```

上述为实际调用参数的 PowerShell 可读形式。两个 `.tmp` 初始化脚本分别配置离线 Robolectric/CPU 配额和临时 Maven 缓存；这些文件与缓存是本机工件，不保证新检出中存在。新环境按[本地开发环境](../project_local_dev_env.md)准备，不能将找不到临时脚本误判为字幕逻辑回归。

| 测试类 | 用例数 | 主要范围 |
| --- | ---: | --- |
| `LibassSsaStreamDecoderTest` | 8 | 初始化、codec private、时间和 Dialogue 归一化 |
| `Media3SubtitleFormatClassifierTest` | 3 | 字幕格式分流 |
| `MpvAssFullSubtitleParserTest` | 7 | 活动事件解析、去重、reset 与容量限制 |
| `SwitchableEmbeddedSubtitleSinkTest` | 2 | 内嵌输入开关与格式重放 |
| `LocalSubtitlePipelineApiTest` | 2 | 降级和恢复状态契约 |
| `SubtitleLoadSheddingPolicyTest` | 1 | 负载策略 |
| 合计 | 23 | 失败 0、错误 0、跳过 0 |

已读取 Gradle 输出尾部：`BUILD SUCCESSFUL in 26s`，`230 actionable tasks: 66 executed, 164 up-to-date`。

原始本机日志：`.tmp/libass-audit-unit.log`；JUnit XML：`player_component/build/test-results/testDebugUnitTest/`。这些输出可能被清理或后续测试覆盖，所以本文保存命令、数量和范围，不将临时路径作为长期唯一证据。

### 后续维护规则

- 开始修复前核对当前源码与本基线的差异，按问题编号更新状态，保留实际变更和验证日期。
- 关闭问题需满足其行为验收条件；“编译成功”“已有恢复类”“测试名称涉及字幕”不能替代具体证据。
- 若评估采用外部库，固定实际版本或提交，重新核对本文列出的上游能力及限制。
- 修复后在[字幕渲染维护说明](subtitle-rendering.md)更新当前行为；本文保留问题由来、方案取舍和验证结果，避免两处重复维护完整实现说明。
