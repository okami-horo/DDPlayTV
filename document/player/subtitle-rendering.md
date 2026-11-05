# 字幕渲染维护说明

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

源码核对日期：2026-11-04，Media3 `1.11.1`。libass 头文件及解析契约参考为 `0.17.3`，本轮模拟器 ARM64 运行日志报告现有二进制为 `0.17.4-0-gbbb3c7f1570a4a021e52683f3fbdf74fe492ae84-dirty`；未更换依赖或二进制。本文说明当前实现边界；旧变更的勾选仅是历史任务记录，不作为本轮测试结果。字幕简繁转换属于[另一项待实现需求](subtitle-language-conversion.md)。

问题由来与历史修复见 [libass 接入审查](libass-integration-audit.md)。Media3 已补齐 MKV 字体附件、公开时间映射、媒体/轨道作用域、完整帧请求与几何/资源防线，验证命令及范围见[实施账本](../../docs/superpowers/plans/2026-10-30-media3-libass.md)。目标 GLES 2 电视、复杂字幕矩阵与长期资源曲线仍未关闭。

职责、契约与测量门槛见[接入完善设计](../../docs/superpowers/designs/2026-10-29-media3-libass-integration-design.md)。当前使用单渲染线程、逐图像纹理与立即提交；精确呈现、atlas、线程拆分和预热均未启用。

参考 Plezy 升级 Atlas 绘制管线已记为[待办](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#libass-atlas-rendering-todo-2026-11-04)，尚未实施。验收需比较画面正确性、实际渲染开销与内存，不将 Atlas 与其他调度优化捆绑启用。

## 渲染职责

mpv 使用原生字幕，Media3 使用应用字幕渲染。运行时后端分别为 `MPV_NATIVE` 和 `LIBASS`；mpv 不修改 Media3 的后端偏好。

Media3 内嵌 ASS/SSA 与其外挂 ASS/SSA 使用以下渲染链：

```text
LibassRendererBackend
  -> LibassGpuSubtitleSession
  -> AssGpuRenderer
  -> AssGpuNativeBridge
  -> ass_gpu_bridge.cpp
```

[LibassRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt) 负责后端入口；[LibassGpuSubtitleSession](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt) 管理独立 overlay、渲染线程、帧驱动和 ASS 输入 sink。当前采用固定 Plezy 提交的格式分流设计：内嵌与外挂 ASS/SSA 均交 libass；SRT/WebVTT/TTML 等普通格式保留 Media3 完整 Cue，由现有控制器中的 SubtitleView 显示文字、位图、定位与嵌入样式。来源影响输入方式，格式决定渲染器。

- 内嵌 MKV ASS：[LibassMatroskaExtractor](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassMatroskaExtractor.kt) → [LibassSubtitleExtractorOutput](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassSubtitleExtractorOutput.kt) → source 独立的 [Media3SubtitleInput](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleInput.kt)。公开 TrackOutput 在提取阶段取得事件与容器时间，实际文本 renderer 选轨后才送入显示 sink。
- 官方支持的外挂：异步读取并校验内容后，通过 `MediaItem.SubtitleConfiguration` 并入真实轨道。ASS parser 用 `ass_read_memory` 输入完整文档；普通格式委托默认 parser。支持的扩展名为 ASS/SSA/SRT/VTT/TTML/DFXP/XML；SCC/STL 等既有格式保留原控制器兼容路径。
- ASS 使用 NoOp Cue 输出，避免双重绘制；旧 `LibassSsaStreamDecoder` / `LibassSubtitleDecoderFactory` 接管已移除。mpv 的原生字幕职责保持不变。

当前没有真正接管自定义 libass 渲染的 CPU fallback。`SubtitlePipelineMode.FALLBACK_CPU` 是保留的状态字段名；它在这里表示 GPU 管线降级，不能解释为已有 CPU 渲染器在输出字幕。后续更名需核对状态消费者和兼容性，不是本次新增功能承诺。

以下显示、轨道、降级与恢复说明适用于应用 libass 后端；mpv 的独立契约见后文。

## 显示、轨道和加载契约

- `SubtitleController.setUserSubtitleVisible()` 同时控制现有视图和 libass renderer。隐藏只提交透明帧，不删除轨道；恢复使用当前播放位置重绘。会话初始化继承控制器可见状态。
- Media3 真实轨道互斥选中；Off 仅隐藏并保留选中轨道。切换 ASS 轨时重放该轨头部/文档/事件，普通 Cue 轨选中时清除 ASS 画面。同对象重复绑定幂等；显式更换 sink 才重放缓存。
- 官方外挂在 IO 线程读取和解析校验，单文件上限 16 MiB，成功后缓存到当前播放器会话目录；释放播放器时清理缓存。通过真实轨道出现后的回调更新选轨与菜单，失败/取消不会提前取消旧轨。重建当前 source 保留队列、位置、暂停、倍速、Surface、请求头、缓存及 MediaItem 裁剪/元数据；清除失效的文本 TrackGroup override。合并 ID 仅接受 Media3 的数字子轨前缀匹配，选择仍使用完整实际 ID，不按名称或列表序号猜测。
- 同一当前媒体的外挂配置按原始资源 URI 生成稳定身份：普通路径与规范化的 `file://` 等价，content URI 保留 provider/document 身份，不按显示名去重。缓存文件仍独立命名；再次添加已附加资源只复用/选择原轨道，不重复读取或替换 source。完成操作仍在主线程排队，后续显式选轨或取消可以撤销尚未执行的请求。
- 旧控制器兼容路径的 `loadExternalSubtitle()` 仍等待渲染线程 native 结果；直接加载 ASS 时暂时关闭流式 sink，清除/失败后显式解绑重绑恢复内嵌输入。此路径不再承担官方支持的 Media3 外挂轨道管理。
- native `flush` 提交透明帧并标记下一次必须重绘，不删除事件；`flushEmbeddedEvents` 才清空流式事件，`clearEmbeddedTrack` 则释放轨道。surface 重建也强制刷新相同时间点，避免 libass 的“图像未变化”判断留下空画面。
- surface 绑定带修订号，销毁/重新绑定后跳过旧队列任务；JNI 入口检查 Surface 有效性。销毁回调等待渲染线程解绑（最多 1.5 秒，超时记录警告），避免回调返回后继续使用旧窗口。尺寸变化只使用实际的 SurfaceHolder 回调，不在布局变化时提前或重复重建 EGL。
- Media3 视频 Surface 销毁时，`RenderSurfaceView` 按实际绑定所有者调用 `clearVideoSurface(surface)`；重新创建后再绑定，避免 MediaCodec 向失效窗口继续输出。非零暂停位置的视频/字幕双 Surface 重建已有应用像素回归。
- 渲染状态和轨道命令按 FIFO 执行。多帧请求合并为一个待渲染任务，并移至已提交状态命令之后，避免暂停时重绘先于显示/seek/切轨操作。
- 提取输出按 TrackOutput 的分段数据和 metadata size/offset 组装样本，保留下一样本的尾部数据。创建轨道才获取输入代际，能力查询不改变轨道所有权；旧媒体或被替换轨道的回调不能接管新输入。native 绘制时间保留 64 位毫秒。
- 字幕名称保留原始标签、语言、角色和强制标记；CHS/CHT 分别显示为简体/繁体，同名加序号，轨道 ID 与选择逻辑保持独立。
- [Media3AssSample](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3AssSample.kt) 选择真实 codec private，只清除尾部 NUL，不拼入 Media3 的 Dialogue 包装格式。缺 Events、空头部或无初始化数据均由 `ass_process_codec_private` 提供原生回退，保留自定义格式、正文原字节及 ASS 标签。
- 普通 Cue 不再压成字符串；文字/位图混合、Spanned、定位和空 Cue 清屏均传入 SubtitleView。字号、颜色、透明度、阴影和垂直偏移映射现有设置，保留 Cue 自身样式。官方 CaptionStyle 的描边宽度不可任意指定，当前正描边值映射为标准 outline，无描边时才使用阴影，不能声称与 libass 的描边/阴影组合完全等价。

样式面板按当前实际选中轨道分流，`VideoTrackBean.subtitleMimeType` 保留提取前的类型；Media3 Cue 格式从原始 codec 信息恢复类型。普通文字显示字号、描边、颜色、透明度和位置；ASS 显示字号偏移、透明度和位置；PGS/VobSub/DVB 位图仅显示透明度和位置；mpv 保留字号偏移和位置。Off 仍保留选轨，关闭字幕时可以继续调整该轨道的配置。没有有效选轨时展示普通文字默认配置。

普通文字大小与 ASS/mpv 字号偏移分别生效，ASS 缩放不再叠乘到原生 Cue 或旧兼容文字视图。重置与“已修改”状态只涉及当前可用设置；切轨导致控件隐藏、重置按钮消失时将焦点移回当前可用项。本文未新增任意描边宽度或覆盖字幕文件嵌入样式的能力。

## Media3 时间、字体与资源

时间偏移继续全局保存到 MMKV。`PlayerInitializer.Subtitle.offsetPosition` 现在代理同一个 `SubtitlePreferenceUpdater` 读写源，避免界面与 mpv 使用未回填的 0；不在对象初始化时提前访问 MMKV。Media3 保留独立的已应用偏移值，以识别控制器先保存、随后通知内核的真实变更。正值表示提前，mpv 写 `sub-delay` 时取反；本轮没有新增设置作用域。

[LibassTimelineRenderer](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassTimelineRenderer.kt) 使用公开 `ForwardingRenderer`、`SampleStream` 和 Timeline，在真实文本轨 enable/replace/format-read 时选择所属 source 与轨道。提取事件以 period 内容微秒保存，显示时加 `period.positionInWindowUs`，保留裁剪窗口开始前仍重叠的负起点；seek 显式重放缓存，覆盖不触发 extractor.seek 的缓冲内 seek。合成窗口无子 source tag 时通过弱轨道所有权索引定位，避免强引用保留 source。视频 Format 随解码输出携带 period 位置，避免读取下一 period 时错误修改尚未输出帧的坐标；视频包装继续转发官方预热 renderer 创建。

普通 Cue 的时间偏移加到文本 renderer 时钟。官方 TextRenderer 和 SampleQueue 都会丢弃消费过的 Cue；原位置 seek 可能被忽略，禁用/恢复文本轨也可能只读到流末尾。因此普通字幕修改延迟时重建当前 source 并恢复实际选轨、位置、暂停、倍速和队列，可能出现短暂缓冲；相同值不重建。ASS 直接更新 libass 时钟，不触发这次重建。

[Media3SubtitleFrameDriver](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleFrameDriver.kt) 将视频 PTS 归一为 window 内容微秒，保留有效 `releaseTimeNs` 为可选纳秒目标。`TIME_UNSET` 转为无目标时间；250ms 没有 metadata 后，每 50ms 使用 `contentPosition` 立即提交。metadata 恢复后抑制位置提交，停止时移除回调；暂停和设置重绘也立即提交。正时间偏移加到内容时间，正值使字幕提前，不重复调整目标时间。

`SubtitleFrameRequest` 将内容时间、目标时间、状态版本、Surface 版本和入队时间作为一个快照发布。状态命令 FIFO，帧合并；隐藏、清屏、seek、换轨、字体/样式、几何及 Surface 更新会使旧请求失效。JNI 在计算前及 swap 前检查原子版本；已经进入系统 BufferQueue 的帧由后续透明/正确帧覆盖。目标时间目前用于诊断，未调用 EGL presentation timestamp，不能把 swap 成功当作精确呈现。

[Media3MediaSourceHelper](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3MediaSourceHelper.kt) 为每个 source 创建独立 `Media3SubtitleInput`、parser/extractor factory 和请求头配置，并绑定到 MediaItem tag；默认 ExoPlayer factory 与连续播放预加载沿用此入口。预加载只缓存，当前 MediaItem 切换时才接入显示 sink；实际选中轨的字体、头部、完整文档或事件按顺序重放。旧媒体的字体/样本与旧轨道的格式/释放不能接管新输入。同媒体 seek、切轨和 Surface 重建保留字体；换媒体重置 native track、字体和纹理。

[LibassMatroskaExtractor](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassMatroskaExtractor.kt) 在内部使用 Media3 的公开 EBML 扩展点，只替换默认 Matroska extractor，其他格式保持官方提取器。Attachments 登记为一级元素以支持损坏 ID 后的重同步；外层 `Extractor` 委托包装在释放时归还未完成附件的预算，因为上游 `release()` 为 final。附件数据可早于名称/MIME 到达，验证字体类型与 sfnt signature；不使用私有字段反射或第二套解复用器。晚到字体注册后重建 ASS renderer 的字体/字形缓存，保留当前 ASS track 并重绘；字体在内嵌输入静音时仍可注册。

Block 层还原 MKV 文字轨的显式/缺省 zlib 和 header stripping，早于官方文字组装及首 NUL 截断。范围为 ASS/SSA/SRT/WebVTT；其他 zlib 轨不新增音频兼容实现。解压有 16 MiB 与比例上限，损坏/尾部多余数据/压缩文字 lacing 明确失败。公开 `SubtitleTranscodingExtractorOutput` 与 ASS 捕获输出组合，普通格式继续官方转换。这个兼容性缺口已用自建样本复现；原 115 视频是否使用该压缩仍未确认。

压缩字幕局部失败可能影响整段视频的容错调整已记为[待办](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#compressed-subtitle-error-tolerance-todo-2026-11-04)，尚未实施；当前仍保留上述明确失败行为。

当前防线是单字体 16 MiB、媒体累计 32 MiB，累计包含待注册预留与已保留输入，重复字体去重；不可用/超预算附件跳过并记录原因，沿用回退字体。此限制是输入预算，不能解释为 Java/native 字体总内存上限。覆盖纹理最多 64 MiB、4096 张图像并检查 `GL_MAX_TEXTURE_SIZE`；超限拒绝整帧并进入现有故障策略，不静默丢弃尾部图像。libass cache 显式设置 `ass_set_cache_limits(0, 0)` 使用随附 API 的默认配置，不采用上游 fork 的缓存数值。

源视频 storage width/height、像素比和媒体旋转独立传入；ASS raster frame 与 Surface output 分开，默认比例保持 1.0，GL 映射 90/180/270 度媒体旋转。Surface 仍对齐视频 View，不改变 fit/fill/zoom 的既有裁剪。当前未启用降采样，这些限制尚未在代表性物理电视上定型。事件保留到 flush/换轨/媒体释放，不擅自裁剪可能被 seek 使用的数据；长期内存和队列曲线仍待测量。

诊断日志包含 `queueUs`、`targetDeltaUs`、CPU layout/upload/draw+swap、纹理峰值、字体累计和 `presentation=immediate`。native 区分实际提交与静态复用；CPU 阶段耗时与目标差值不代表 GPU 完成或实际显示时间。既有 25ms/连续 5 次/500ms 冷却策略保留，动画负载下的影响仍需实机对照。

## 降级与恢复

GL、GPU 或 overlay surface 故障后，应保留字幕会话上下文并暂停无效渲染。[AssGpuRenderer](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuRenderer.kt) 的失败标记和 Degraded 状态阻止继续提交帧；恢复由既有 [SubtitleRecoveryCoordinator](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitleRecoveryCoordinator.kt) 在受控边界发起，不应在每一帧重建 EGL／native／libass。

已接线的恢复边界包括：

- overlay surface 创建、重新绑定或尺寸变化；
- seek、字幕轨道变化和时间线跳变；
- 外挂字幕加载或清除。

恢复协调器要求管线处于降级状态、surface 与输出目标有效。attach 失败按无效 surface 或 GL 故障分类，不再一律归为不可恢复的 `UNSUPPORTED_GPU`。恢复尝试先标记 recovery pending，重新绑定和初始化成功后才回到 Active；正常 surface 绑定完成后也请求暂停重绘。取消旧初始化任务不报告初始化故障。

GLES 3 使用 GLSL 300 es 和 R8/RED 覆盖纹理；GLES 2 使用 attribute/varying shader、GL_ALPHA 纹理及紧密行上传。两者均要求 alpha 通道的 EGL 配置，以保留视频透出。输出检查 GL/swap 错误，不能将未提交的帧算作成功；目标 GLES 2 电视尚待实机验证。

[SubtitleFallbackDispatcher](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/SubtitleFallbackDispatcher.kt) 向页面通知故障，不切换渲染后端。[PlayerActivity](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 记录 degraded／recovery pending，并提示重新选择字幕或切换内核；[SubtitleFallbackReporter](../../core_log_component/src/main/java/com/xyoye/common_component/log/SubtitleFallbackReporter.kt) 的 `SUB-FALLBACK` 日志区分 `degraded`、`recovery_pending` 和 `recovery_result`，保留原因、surface、触发条件与结果。诊断入口见[日志系统](../monitoring/logging-system.md)。

## 运行时垂直偏移

会话启动时应用当前字幕垂直偏移，后续设置更新沿 `updateVerticalOffset()` 传到 [AssGpuNativeBridge](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/AssGpuNativeBridge.kt) 和 [ass_gpu_bridge.cpp](../../player_component/src/main/cpp/ass_gpu_bridge.cpp)。native 在媒体旋转完成后的屏幕方向施加偏移，等价于按输出高度计算：

```text
offsetPx = outputHeight * offsetPercent / 100
adjustedY = subtitleImageY - offsetPx
```

因此正值上移、负值下移，方向与原字幕视图保持一致。native 将百分比限制在 -100～100；修改设置影响后续帧，暂停时会话请求一次重绘，无需重新开始播放。该路径同时覆盖上述内嵌和外挂 ASS/SSA，不应改用平移整个 overlay View，否则会改变 surface 裁剪边界。

## mpv 原生字幕

[MpvSubtitleRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/MpvSubtitleRendererBackend.kt) 仅创建透明 `SubtitleSurfaceOverlay`，通过 Java ABI 的 `attachSurfaces(video, osd, videoGeneration, osdGeneration)` 交给固定版本 Plezy AAR。mpv 负责字幕解复用、字体、时间与绘制，应用不再观察 `ass-full`、拆解事件或向第二个 libass 实例注入。旧 `MpvEmbeddedSubtitleBridge`、`MpvAssFullSubtitleParser` 及其去重测试已删除。

- 视频继续 `SurfaceView + vo=mediacodec + hwdec=mediacodec`；原生 OSD 自行合成字幕，不把视频转到应用 GPU。字幕 Surface 使用 media-overlay 层级，位于视频之上、应用控制器与弹幕之下，并跟随实际视频布局。`vo-mediacodec-video-rect` 传递这一坐标边界。
- 视频与字幕各有独立 Surface generation。销毁字幕仅解绑 OSD，销毁视频交接到占位 Surface 并暂时解绑 OSD；视频回来后重新绑定仍有效的字幕 Surface。可 seek 的暂停视频维持先前位置并重绘。
- 内嵌 ASS、普通文字和位图均由 mpv 原生处理；不再提示位图字幕必须切换内核。`embeddedfonts=yes` 允许 mpv 使用 MKV 字体附件，应用默认字体目录仍可作为回退。具体字体、位图格式的完整兼容矩阵仍需实际素材验收。
- 外挂仍经过现有 `ExternalSubtitleManager`（包括 content URI 本地缓存），随后由 native `sub-add` 探测格式；SRT/ASS 不再转到应用解析器。等待真实命令结果后返回成功。取消中的请求有独立修订/所有权，不能覆盖后来选中的内嵌轨；清理只移除本次添加的外部轨道。界面保留一份外挂入口，隐藏 mpv 重复的外挂枚举。
- 显示开关用 `sub-visibility`，隐藏保留轨道。应用时间偏移取反后写 `sub-delay`；字号偏移写 `sub-scale=1+offset/100`；正值上移对应 `sub-pos=100-offset`，保留 ASS 自身样式。特殊定位、位图与向屏幕下方移动的裁剪遵循 mpv 的限制。
- mpv 没有等价的全局字幕透明度系数：此模式隐藏透明度控制并说明样式限制；重置只修改当前可用的字号与位置，不覆盖 Media3 的透明度偏好。

参考 [mpv 0.41.0 字幕参数](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst)、[固定上游 OSD 实现](https://github.com/edde746/mpv-build/blob/b15bf59c043b45f9ad7fa227b2cc7d3164daf58f/patches/mpv/pool/0100-vo-mediacodec-timed-surface-with-osd-plane.patch) 和 [Plezy Surface 布局](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/shared/PlayerSurfaceHost.kt)。具体实施与验证见[本轮记录](../../docs/superpowers/plans/2026-10-29-mpv-native-subtitles.md)。

<a id="tv-subtitle-luminance"></a>

## 电视画质调整与字幕亮度（2026-11-03 调研）

用户报告：电视设置把画面调暗后，字幕仍然很亮；补充确认两种内核都测试过。本轮核对源码和上游资料，没有操作物理电视，也没有修改播放器。具体设置名称、片源 HDR 状态及逐项亮度测量尚未记录。

**已确认的是视频和软字幕分层输出；最符合现象的解释是电视的视频画质处理没有同样作用于字幕图层。** 后半句属于结合用户观察与实现得出的推断，尚未通过实机合成器或亮度测量证明。此前的字幕功能验收不覆盖这次新增的亮度问题。

### 当前实现证据

| 路径 | 已核对行为 | 对此问题的含义 |
| --- | --- | --- |
| Media3 ASS/SSA | `LibassGpuSubtitleSession.attachOverlay()` 创建独立 `SubtitleSurfaceOverlay`；`ass_gpu_bridge.cpp` 直接使用 ASS 颜色和用户透明度绘制 | 字幕没有合入视频像素，没有读取或跟随电视画质亮度的逻辑 |
| Media3 普通文字／位图 | 沿用 `SubtitleController` 下的字幕视图，独立于视频输出 | 不能把全部字幕的处理能力等同于 libass；新增亮度控制要覆盖这些路径 |
| mpv | `MpvNativeBridge.VIDEO_OUTPUT=mediacodec`；`setSubtitleSurface()` 绑定第二个 Surface | 与 Media3 一样存在独立字幕层；更换内核不会自动合并输出 |
| 现有字幕设置 | Media3 有透明度；`MpvSubtitleRendererBackend.updateOpacity()` 无实现，界面明确隐藏该项 | 当前不存在两个内核通用的字幕亮度控制 |

源码入口：[Media3 overlay](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassGpuSubtitleSession.kt)、[应用字幕 shader](../../player_component/src/main/cpp/ass_gpu_bridge.cpp)、[mpv 桥接](../../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt)、[mpv 字幕后端](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/MpvSubtitleRendererBackend.kt)、[样式面板](../../player_component/src/main/java/com/xyoye/player/controller/setting/SettingSubtitleStyleView.kt)。固定版本的 [mpv 上游补丁](https://github.com/edde746/mpv-build/blob/b15bf59c043b45f9ad7fa227b2cc7d3164daf58f/patches/mpv/pool/0100-vo-mediacodec-timed-surface-with-osd-plane.patch)也明确将解码视频直送系统合成器，将字幕画到另一个透明 Surface。

历史设备记录为 **Hisense／VIDAA_TV／MT9653，Android 11／API 30**，来自[2026-11-02 安装记录](../../docs/superpowers/plans/2026-11-02-a03-container-hierarchy.md#用户追加安装到电视2026-11-02-2149)，不是本轮重新读取的设备状态。

### 系统与参数边界

- **HDR 可能放大差异，但不能直接定性为 HDR 故障。** Android 的 SDR/HDR 混合显示支持分别调节 SDR 图层，其效果依赖系统版本、硬件和 OEM 配置；Android 13 的改进也不能反推 Android 11 电视已有同等实现。[Android 混合合成说明](https://source.android.com/docs/core/display/mixed-sdr-hdr)
- **系统背光值不能代替电视画质参数。** `Settings.System.SCREEN_BRIGHTNESS` 定义的是背光；API 34 的 `Display.getHdrSdrRatio()` 返回 HDR 峰值与 SDR 参考白之比，两者都不等于电视菜单中的视频亮度／Gamma 设置。[背光 API](https://developer.android.com/reference/android/provider/Settings.System#SCREEN_BRIGHTNESS)、[HDR/SDR 比例 API](https://developer.android.com/reference/android/view/Display#getHdrSdrRatio())
- **新版画质 API 不能作为现有设备的通用解法。** Android 16 才引入 Media Quality framework，仍涉及厂商 HAL、能力和权限；其存在不能证明旧电视能公开读回画质设置，也不能证明字幕层自动共享视频处理。[MQF 官方说明](https://developer.android.com/training/tv/mqf)
- **不能只给字幕 Surface 调 `alpha`。** 官方说明 Android 14 才支持 SurfaceView 的任意 alpha 混合；早期版本会忽略 0 与 1 之间的值。当前实机基线是 Android 11。[SurfaceView 文档](https://developer.android.com/reference/android/view/SurfaceView)
- **mpv 的现成参数不覆盖当前输出方式。** 锁定版本 `0.41.0` 的 `sub-hdr-peak`／`image-subs-hdr-peak` 仅用于 `vo=gpu-next`，当前使用 `vo=mediacodec`；`sub-color` 主要控制无样式文本，强制覆盖 ASS 样式也不能完整覆盖位图字幕。[固定版本参数文档](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst)

### 处理方案评估（均未实施）

| 方案 | 可以解决的部分 | 限制与判断 |
| --- | --- | --- |
| 使用现有 Media3 字幕透明度 | 可先缓解字幕过亮 | 会透出背景；不等于降低不透明字幕自身的亮度，mpv 当前也没有等价控制 |
| 新增独立字幕亮度 | 可保留视频硬解直出，只调暗字幕；建议优先评估 | Media3 要覆盖 libass、普通文字和位图；mpv 需要原生 OSD 扩展或独立字幕后处理，不能只接一个界面滑杆 |
| 自动读取电视画质后同步字幕 | 理论上可做特定系统／厂商适配 | 本轮没有找到覆盖当前 Android 11 设备及项目支持范围的通用接口；需要设备能力证据，不能承诺自动跟随 |
| 将字幕真正合入视频再输出 | 有机会让字幕参与同一条视频处理链 | 涉及视频合成路径、颜色空间和 HDR/Dolby Vision；必须另做原型与实机验证，不能当成一个参数修复 |

独立亮度的建议实现方向是在字幕最终颜色输出中降低 RGB，并保持原 alpha、布局与时序。这样可以保留 ASS 配色和不透明度；百分比应表示应用调节系数，不能未经校准宣称等于物理亮度或尼特。mpv 当前锁定补丁的文字、位图和预合成字幕 shader 分支都需要覆盖；原生依赖变更遵循[固定产物与升级流程](../mpv-build-notes.md)，不改写现有 AAR。

mpv 的 `blend-subtitles` 描述的是 GPU 视频合成阶段的行为，不能套用于当前 MediaCodec 直出。改成 TextureView 也不保证接入电视视频画质链路，且官方提示 HDR 可能转成 SDR 并丢失细节；因此本次不建议为此直接替换视频输出。[mpv 固定版本说明](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst)、[Android HDR 播放说明](https://developer.android.com/media/grow/hdr-playback)

### 尚需证实的实机行为

下一步若开展真机诊断，使用同一暂停画面，让视频内的参考白／灰块与外挂字幕白／灰块并排，逐项切换实际菜单设置，并分别记录 Media3/mpv、SDR/HDR 和设置值。视频内参考白也不变而中间调变暗，可能只是灰阶曲线变化；视频参考白明显变暗而字幕参考白不变，才更有力地支持图层处理差异。HDR 各层的参考白需要先约定，不能仅凭相同 RGB 数值当成同等物理亮度。

屏幕截图／PixelCopy 可验证应用输出，但不足以证明面板最终画质处理；若拍摄对照，需锁定曝光和白平衡，定量结论需要实际亮度测量。当前结论为源码与资料调研，字幕独立亮度和电视自动跟随都尚未实现、未进行本轮物理电视验证。

## 验证与已知限制

本节列出维护时可用的测试入口。2026-11-04 最新[功能修复记录](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-feature-fixes-2026-11-04)包含成功的 Debug 构建、JVM/Debug lint/定域格式/依赖检查：535 项 JVM 中 533 通过、2 跳过，播放器 177 项全部通过；另有 26 项播放器与 4 项应用模拟器回归，以及两项 1.3 字体重跑和实际截图检查。此前的[管线实施](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#plezy-subtitle-implementation-2026-11-04)、[初次完善](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-hardening-2026-11-04)、[2026-10-30 账本](../../docs/superpowers/plans/2026-10-30-media3-libass.md)和[更早历史](libass-integration-audit.md#已执行验证)保留各自时点的结果。原 115 视频、本轮物理电视和完整字幕矩阵仍待验收；最新三项修复尚未进入电视 Release。

| 测试 | 实际覆盖范围 |
| --- | --- |
| [LocalSubtitlePipelineApiTest](../../player_component/src/test/java/com/xyoye/player/subtitle/gpu/LocalSubtitlePipelineApiTest.kt) | 降级原因保留、恢复中状态及 recoverable 标记 |
| [MpvSurfaceLifecycleTest](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/MpvSurfaceLifecycleTest.kt) | 原生 OSD 像素、内嵌/外挂字幕、显示/时间/样式控制、双 Surface 重建、取消与失败清理，以及既有视频/HTTP 回归 |
| [Media3AssSampleTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3AssSampleTest.kt) | Dialogue 包装归一化、真实 codec private、自定义格式、中文/标签/逗号及尾部 NUL |
| [ZlibSubtitleExtractionTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/ZlibSubtitleExtractionTest.kt) | 显式/缺省 zlib 与未压缩/header stripping 对照、损坏/上限、提取期输入及分段 size/offset 样本 |
| [Media3CueOutputTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3CueOutputTest.kt) | 完整文字/位图混合 Cue、Spanned、定位及空 Cue 清屏 |
| [Media3SubtitleTrackNameProviderTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleTrackNameProviderTest.kt) | 简繁标签、语言/角色/强制信息、重名区分与轨道身份 |
| [SubtitleOffsetPreferenceTest](../../player_component/src/test/java/com/xyoye/player/info/SubtitleOffsetPreferenceTest.kt) | 非零保存偏移的界面初始化与后续步进一致性 |
| [Media3SubtitleFormatClassifierTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleFormatClassifierTest.kt) | 原始和 Cue 包装后的普通文字、ASS、PGS/VobSub/DVB 类型恢复 |
| [Media3ExternalSubtitleTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3ExternalSubtitleTest.kt) | 路径/file URI 等价、显示名不改变身份、不同 content provider/document 不合并 |
| [Media3SubtitleInputTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleInputTest.kt) | 媒体/轨道代际、预加载、字体预算/去重、同 sink 幂等与真正重建 |
| [LibassMatroskaExtractorTest](../../player_component/src/test/java/com/xyoye/player/kernel/impl/media3/LibassMatroskaExtractorTest.kt) | 真实 EBML 附件重同步与中断释放预算 |
| [LibassGpuRenderingTest](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/LibassGpuRenderingTest.kt) | ASS 输入 → JNI/libass 中文像素、缺 Events/空头部回退、压缩/未压缩像素对照、完整文档及窗口偏移，以及附件字形、旋转/PAR/显隐/Surface/大时间戳 |
| [Media3SubtitleCompatibilityTest](../../player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3SubtitleCompatibilityTest.kt) | 真实默认/预加载 source、简繁轨选择、裁剪/倍速/多 period/seek、视频 metadata；MKV → GPU 像素；外挂 ASS/SRT/VTT/TTML 及重复添加、失败/取消、暂停/偏移/位置/队列与 Surface 恢复；普通文字实际像素不受 ASS 缩放影响 |
| [PlayerPanelLayersInstrumentedTest](../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/PlayerPanelLayersInstrumentedTest.kt) | 双内核真实 SRT/Off、保存偏移与步进/重置、按格式样式显隐/重置范围/焦点和 DPAD；ASS/位图面板使用格式能力夹具，不代表实际 PGS 几何验证 |
| [TvDpadRewriteInstrumentedTest](../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvDpadRewriteInstrumentedTest.kt) | 应用真实 Media3 ASS/mpv OSD、PixelCopy、Media3 非零暂停双 Surface 重建、控制层级及 DPAD 面板/返回/暂停继续 |

上述 JVM 用例不证明 EGL surface 恢复、native 坐标、跨内核画面或视觉裁剪正确。修改这些路径时，应在 Media3 和 mpv `mediacodec` 上分别检查内嵌／外挂 ASS/SSA、播放中及暂停时的正负偏移、surface 重建、seek／换轨后的恢复，并记录实际日志和画面结果。

模拟器不能证明物理电视上的 PQ/HDR、字体完整覆盖或复杂字幕性能。性能收益不由删减代码量或减少桥接推断，需同素材真机比较。

<a id="media3-embedded-ass-diagnosis"></a>

## 内嵌 ASS 无字幕与轨道重名（2026-11-03 排查）

用户报告 115 视频在 Media3 下无字幕、mpv 正常，菜单为“关、中文、中文”，并补充旧版也有内嵌字幕问题。首次排查只诊断，未修改播放器源码。用户随后明确要求停止占用电视；后续调查仅使用本地源码、此前日志和参考实现。2026-11-04 用户另行授权实施字幕完善，当前契约见上文，验证进度见[字幕完善方案](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md)。以下保留首次诊断证据，不代表实际片源已验收。

### 首次诊断确认的问题

- **轨道重名**：原 [Media3VideoPlayer.getTracks()](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) 使用 `DefaultTrackNameProvider`。日志两轨分别为 `label=CHS`、`label=CHT`，语言同为 `zh`，默认命名优先语言，均变为“中文”。这是名称丢失区分信息，实际存在两条独立轨道；“关”是正常的显示开关项。
- **缺少 Events 定义时的头部合成错误**：原 `LibassSsaStreamDecoder.buildCodecPrivateForLibass()` 把 Media3 的 `Start, End, ReadOrder, Layer, ...` 格式补入原始 ASS 头部，但后续送给 native 的是原始 Matroska chunk。libass 已先取走 ReadOrder/Layer，再跳过事件格式的前三列；此合成格式会使 Style 等字段错位，正文可能被解释为 Effect，Text 为空。当时 JVM 测试只断言拼接结果，不能发现实际无像素。该 decoder 在后续提取阶段方案中已移除。

### 与参考实现的关键区别

参考固定的 Plezy 提交 `1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5`，对应目录为 `android/libass/src/main/java/com/edde746/plezy/libass/media/`：

| 环节 | Plezy | 首次排查时的本项目实现与判断 |
| --- | --- | --- |
| 头部规范化 | [AssHeaderParser.kt](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/parser/AssHeaderParser.kt) 去掉全部尾部 NUL，缺少 Events 时追加 `Layer, Start, End, Style, ...` | 本项目没有去尾部 NUL，缺定义时追加 Media3 专用格式；后者已用构造样本证实会导致无字 |
| 事件交接 | [AssTrackOutput.kt](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/libass/src/main/java/com/edde746/plezy/libass/media/text/AssTrackOutput.kt) 去掉两个时间字段后调用 readChunk | 本项目经 decoder 去掉相同包装，调用 `ass_process_chunk`；不能仅凭多一层 decoder 判断这里有错 |
| native 头部入口 | AssHandler 的 readBuffer 最终使用 `ass_process_data` | 本项目使用 `ass_process_codec_private`，后者在缺事件定义时自带标准格式回退。因此“未去尾部 NUL”本身不能直接认定为本次根因 |

[libass 0.17.3 实现](https://github.com/libass/libass/blob/0.17.3/libass/ass.c) 核对了上述格式跳列及 codec-private 回退行为。[Media3 1.11.1 MatroskaExtractor](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java) 在输出字幕样本前按首个 NUL 截断；Plezy 使用该缓冲区的实际 limit，也不能自动绕开此截断。mpv 的 FFmpeg 解复用与本项目的 Media3 提取链不同，不能从 mpv 正常推断 Media3 输入正文完整。

### 本地验证及本片的未决边界

构造样本脚本：[header_compat_probe.py](../../Temp/media3-subtitle-diagnosis-2026-11-03/header_compat_probe.py)。它只在电脑生成字幕 MKV，通过本地 FFmpeg `7.0.2` 的 libass 流式接口渲染；实际日志版本为 libass API `0x1703000`、source `0.17.3-6-gc5bb87e2f5d6c18763b4614817c206a4f4d2332a`。这不是 Android 应用、实际片源或电视验证。

同一个 `0,0,Default,,0,0,0,,Regression` 事件，标准头部和缺 Events 的 native 回退均得到 5172 个非零 RGB 字节；补入本项目的 Media3 专用格式后为 0。标准头部和缺 Events 的头部追加尾部 NUL 后仍能显示。六个断言全部通过，[结果](../../Temp/media3-subtitle-diagnosis-2026-11-03/header-probe/results.json)及逐项 FFmpeg 日志保留在同目录。原始 HTTP 日志仅保留于该 Temp 调查目录，不纳入版本控制。

**本片无字幕的唯一根因仍未确认。** 已有日志显示 decoder 收到 `[90,1182]` 两块初始化数据，最终 codec private 仍为 1182 字节，说明本次使用了原头部，未命中上述错误拼接分支。已选中 ASS 轨，格式和事件已送到 GPU，事件时间与播放时间相符；已记录事件剥掉固定 32 字节包装后只有 15～17 字节，且长度随播放阶段变化，值得优先检查正文完整性及原始事件格式。日志没有正文、完整头部或 native 解析事件结果，不能把长度直接等同于截断，也不能凭管线 Active/Rendered 宣称已有可见字幕。

本片问题仍需针对原始头部/事件解析建立证据，不能用修复另一种构造样本代替验证。当前不再操作电视或要求用户下载片源。此前临时打开的 DEBUG 日志级别尚未恢复，已告知用户；未经新的明确授权，不为恢复设置继续操作设备。

### 当前实施与诊断边界（2026-11-04）

用户已明确解除实施延期，本地实现和验证已完成。当前实现修正头部、查询所有权、重复绑定和轨名，并按真实 EBML 复现修复附件重同步/释放；完整范围和逐项验证由[实施记录](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-hardening-2026-11-04)维护。

采样日志新增输入包的 NUL 位置/分隔符数、native 事件/样式数、最新事件正文长度与解析结果；重复判断只检查最近 4096 个事件，无法精确区分时报告 `UNCHANGED`。帧指标包含 ASS 图像数，未采集时为 -1；图像存在仍不能代替实际 alpha 像素验证。libass verbose 日志关闭，避免输出完整对白和头部。上述结构信息可缩小后续原片排查范围，但当前没有该视频修复后的新证据。
