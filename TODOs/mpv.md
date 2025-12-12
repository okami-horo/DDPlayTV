# mpv 集成最佳实践（TODO）

目标：在现有 DanDanPlayForAndroid 架构下新增 mpv 内核，**默认使用 mpv/libass 内核字幕渲染（对齐 VLC 行为）**，先保证字幕能正确显示；字幕样式/设置映射等增强能力后续再做。

## 1. 现有播放器架构（决定接入点）

- 播放内核通过 `player_component/.../kernel/inter/AbstractVideoPlayer.kt` 统一接口；具体实现目前有 Media3/Exo（`.../impl/media3/Media3VideoPlayer.kt`）和 VLC（`.../impl/vlc/VlcVideoPlayer.kt`）。
- 内核工厂在 `player_component/.../kernel/facoty/PlayerFactory.kt` 按 `data_component/.../PlayerType.kt` 选择。
- 渲染视图抽象在 `player_component/.../surface/InterSurfaceView` + `SurfaceFactory.kt`，VLC 有专用 `RenderVLCView.kt`。
- 应用侧字幕/弹幕是独立覆盖层：文本字幕走 `SubtitleController`，ASS/SSA 走应用内 GPU libass 后端（见 `TODOs/opencc_ass.md`），只对 Media3 的字幕输出生效；VLC 内核不回调字幕事件，字幕完全由 VLC 自己渲染。

结论：mpv 最稳妥的接法是**作为第三种内核**接入，行为对齐 VLC：

- 音视频解码 + 渲染由 mpv 内核负责。
- **字幕默认由 mpv 内核（libass）渲染**，覆盖内嵌字幕与外挂字幕。
- 弹幕仍走现有覆盖层（danmaku），不依赖内核。

## 2. 内核层最佳实践：新增 MpvVideoPlayer + Factory

- 在 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/` 新增 `MpvVideoPlayer : AbstractVideoPlayer`，实现所有控制/状态/轨道接口。
- 新增 `MpvPlayerFactory`，并在 `PlayerFactory.getFactory()` 里分发。
- `PlayerType.kt` 增加 `TYPE_MPV_PLAYER(x)`，`valueOf()` 支持回读；`common_component/config/PlayerConfigTable.kt` 默认/持久化字段同步扩展。
- 保持事件语义一致：mpv 的 event loop（`mpv_wait_event`）放在后台线程，映射到 `VideoPlayerEventListener` 的 `onPrepared/onInfo/onCompletion/onError/onVideoSizeChange`，与 Media3/VLC 的状态码对齐（`PlayerConstant`）。

## 3. 渲染层最佳实践：做专用 RenderMpvView

- mpv Android 端推荐用 `libmpv + mpv_render_context(OpenGL)`（`vo=gpu` 或 `gpu-next`，`gpu-context=android`）。
- 参考 VLC 的做法：实现 `RenderMpvView : InterSurfaceView`（内部用 `GLSurfaceView`/自定义 `GLTextureView`），在 `SurfaceFactory.getFactory()` 里按 `playerType` 直接返回 `MpvViewFactory()`，忽略 `surfaceType`。
- 为了保证弹幕覆盖层正常显示：尽量让 mpv 渲染视图处在普通 View 层级里（Texture/GLTextureView 优于纯 SurfaceView），否则需要处理 SurfaceView 的 Z-order 与透明覆盖问题。

## 4. 选项/功能映射（阶段 1）

- `setOptions()`：集中设置 mpv 参数，来源尽量复用 `PlayerInitializer/PlayerConfig` 的已有开关（倍速、循环、硬解策略、音频输出、日志开关等）。
- **字幕策略（阶段 1）**
  - 不关闭 mpv 内核字幕，保持默认可见，充分利用 mpv/libass 的兼容性。
  - `supportAddTrack(TrackType.SUBTITLE)=true`，外挂字幕通过 `sub-add`/等价命令交给 mpv 内核加载渲染（与 VLC 行为一致）。
  - 仅实现必要能力：字幕开关/轨道切换/时间偏移（`sub-delay`、`sid` 等），先保证“能正确渲染字幕”。
  - mpv 内核选中时应禁用/不初始化应用侧 `SubtitleRendererBackend`，避免双重字幕。
  - 字幕样式/设置（字号、颜色、描边、位置、字体、透明度等）映射到 mpv 属性暂缓实现，留到后续阶段。
- 轨道：用 mpv 的 `track-list/aid/sid/vid` 实现 `getTracks()/selectTrack()/deselectTrack()`；外挂音轨用 `audio-add` 映射 `addTrack()`。
- 网络 header：`setDataSource(path, headers)` 里先设置 `http-header-fields`（或等价属性/命令）再 `loadfile`，保证现有源的鉴权/Referer 逻辑不丢。

## 5. 构建与打包最佳实践（当前工程已有 NDK 约束）

- `player_component/build.gradle.kts` 只启用 `armeabi-v7a/arm64-v8a`，mpv 的 `.so` 也只需这两种 ABI；放入 `player_component/libs/**` 或引入对应 AAR（注意版本与 ndk/AGP 兼容）。
- 处理 native 冲突：项目已 `pickFirst("lib/**/libc++_shared.so")`，mpv 包内若也带同名库需确认不会引发 ABI/符号冲突；尽量用共享 C++ 运行库一致的 mpv 构建。
- 体积控制：建议配合 app 的 ABI split/压缩符号（strip），否则 mpv+ffmpeg 会显著增大安装包。

## 6. 法务/发行层必须先评估

- mpv/libmpv 默认是 GPLv2+；若带 GPL ffmpeg/特性，整个应用可能被 GPL 传染。
- 最佳实践是：在决定技术方案前先做许可证评估（是否能接受 GPL 开源、是否能构建 LGPL-only 的 libmpv、或仅作为可选插件发布）。

## 7. 回归验证

- 对照 `player_component` 现有 Media3/VLC 的 instrumented tests（如 `Media3PlaybackSmokeTest.kt`）新增 mpv 播放烟测、轨道切换、seek/倍速/暂停恢复、字幕加载/切换/偏移测试，确保事件与 UI 状态一致。

## 后续阶段（不在 Phase 1 做）

- 将应用侧字幕样式/设置映射到 mpv 属性（字号、颜色、描边、位置、透明度、字体等）。
- OpenCC 简繁转换若要支持 mpv 内核字幕，需要对 ASS/Text 做预处理或提供可选“应用渲染字幕”模式。
