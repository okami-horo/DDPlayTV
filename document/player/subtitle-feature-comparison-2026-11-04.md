# DDPlayTV 与 Plezy 字幕功能方案评估（2026-11-04）

**结论：字幕功能策略以 Plezy 为主要参考更成熟。** 优势集中在轨道元数据、跨集选择意图、自动选择优先级、偏移设置作用域，以及普通文字与位图的独立布局。本项目当前的公开提取器接入、完整轨道枚举、外挂预校验和取消保护、播放队列隔离、已验证的普通字幕偏移恢复应保留。

原评估阶段只记录建议。用户随后授权“修复发现的问题”，**偏移一致性、按实际轨道显示样式能力、外挂资源去重三项具体缺陷已修复并完成本地验证**，见[实施账本](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-feature-fixes-2026-11-04)。普通文字与 ASS 缩放已分离，重置保留隐藏格式的配置并恢复可见焦点；全局偏移保存规则保留。下文对比表与问题分析保留修复前评估基线，不代表这些缺陷仍存在；跨集语义偏好、文字/位图几何优化及此前压缩块容错、Atlas 待办未随本轮实施。

## 对比范围与证据边界

- DDPlayTV 评估基线：HEAD `a33dd791a` 加当时工作区已实施的字幕管线改动，Media3 `1.11.1`；不包含随后本轮三项修复。不能只用 HEAD 版本代表当时安装方案。
- Plezy：固定提交 [`1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5`](https://github.com/edde746/plezy/tree/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5)，核对 Android ExoPlayer、Dart 轨道策略和对应测试源码，未运行 Plezy。
- 覆盖：选轨与命名、Off/显隐、跨集继承、外挂加载、时间偏移、样式能力、文字/位图布局及预加载资源所有权。入口为本项目 `SubtitleQuickView → ControlWrapper → Media3VideoPlayer / SubtitleController`，参考入口为 `TrackManager → TrackSelectionService → PlayerAndroid → ExoPlayerCore`。
- 本项目相关职责分布在 `player_component` 的播放/显示、`data_component` 的轨道数据、`core_system_component` 的设置、`core_storage_component` 的同目录外挂匹配。建议保持现有模块边界。
- 评估阶段未评估整个应用，也未重新测量复杂 ASS 性能、HDR/PQ、全部字体与字幕格式；当时没有操作电视、启动模拟器、构建或安装。后续修复使用任务模拟器，具体测试与限制由上方修复账本维护，原 115 视频仍待用户复测。
- 下文“源码确认”“风险”及尚未运行的描述均属于修复前评估时点，不可替代运行证据；随后已修三项的复现与最终结果见上方修复账本。更早测试见[管线实施账本](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#plezy-subtitle-implementation-2026-11-04)。

## 逐项取舍

| 项目 | 评估时本项目方案 | Plezy 方案 | 选取结论 |
| --- | --- | --- | --- |
| 轨道元数据与偏好模型 | 界面轨道主要保存 ID、拼好的名字、来源资源和选中状态 | 独立保存语言、标题、codec、default/forced、外挂身份；区分当前文件身份与跨文件意图 | **选 Plezy 的模型**，保留本项目简繁命名修正 |
| 自动选择与跨集继承 | Media3 默认优先 `zh`，另有每文件外挂绑定和同目录文件名优先级 | 明确的用户选择、源端选择、账户偏好、默认轨、Off 梯级；等待迟到轨道并防止覆盖用户选择 | **选 Plezy 的策略**，去掉本项目不需要的服务端账户同步 |
| 枚举与菜单呈现 | 枚举组内全部轨道、完整 format ID、重名区分，TV 单行菜单 | ExoPlayer 按每个文字组首轨建立菜单；上层组合语义和技术标签 | **保留本项目枚举和 TV 菜单**，补结构化元数据 |
| Off 与隐藏恢复 | Off 隐藏绘制并保留真实选轨，状态属于控制器生命周期 | 区分显式 Off 与显隐；Android 隐藏时禁用文本轨，另存恢复目标 | **保留当前已确认语义**，借鉴 Plezy 状态分离，暂不更换底层隐藏方式 |
| 外挂加载、取消与去重 | 先读入并验证，再替换当前 source；有大小限制、取消修订和队列恢复；重复添加生成新 ID | URI 去重、保留语言/标题，新增时重建 source 并恢复播放状态 | **保留本项目加载流程**，补 Plezy 的去重与元数据 |
| 偏移值管理与保存范围 | 全局 MMKV、界面单例值、播放器已应用值并存，初始化没有统一来源 | 播放器实例记录实际应用值，设置可按本次、全局、媒体库、作品保存，换片重新解析 | **选 Plezy 的职责划分**；本地按实际需求缩减作用域 |
| 播放中修改普通字幕偏移 | 重建当前 source 恢复已消费 Cue，保留位置、暂停、速度和队列，可能短暂缓冲 | renderer 只改变传入的渲染时间，不重建 source | **保留本项目已验证实现**；未证明轻量方案等价前不替换 |
| 普通文字布局、字号、位置 | 完整 Cue 交给一个 SubtitleView，DIP 字号，整体垂直平移 | 按视口比例字号；默认定位与脚本定位区别处理；另有无定位并发 Cue 堆叠补偿 | **选 Plezy 的布局原则**，迁移时保持当前可见字号和产品位置规则 |
| PGS/VobSub 等位图布局 | 与文字共用视口、透明度和整体位移 | 独立位图 View，可由 Cue 反推字幕合成平面比例，再与视频区域适配 | **选 Plezy 的位图平面处理**，保留无可靠信息时的回退 |
| 样式能力与设置项 | 按整个会话 backend 判断，SRT/VTT 也可能被当作 libass 模式隐藏文字设置 | 普通文字样式和 ASS 缩放分别应用，但部分通用设置没有 ExoPlayer 对应实现 | **修正本项目按轨道判能力**，参考 Plezy 分流，不能照搬其所有设置项 |
| 换片与预加载所有权 | 每 source 有独立字幕输入、字体与修订，当前 renderer 只绑定有效 source | ASS handler 随当前媒体切换重置 | **保留本项目 source 作用域**，无需移植单一 handler 生命周期 |
| 提取器、压缩容错、GPU 绘制 | 公开接口组合已实施；容错和 Atlas 尚有待办 | 存在私有字段适配；容错和 Atlas 有参考实现 | **保留公开接入**；另两项沿用既有待办和验证门槛 |

## 优先问题与具体理由

### 1. 偏移的持久化值、界面值和内核值没有统一初始化（P1，源码确认）

本项目至少存在三份有关联的状态：

- [SubtitlePreferenceUpdater](../../core_system_component/src/main/java/com/xyoye/common_component/config/SubtitlePreferenceUpdater.kt) 从 MMKV 读写全局偏移。
- [PlayerInitializer.Subtitle](../../player_component/src/main/java/com/xyoye/player/info/PlayerInitializer.kt) 的 `offsetPosition` 初始为 0；[SettingOffsetTimeView](../../player_component/src/main/java/com/xyoye/player/controller/setting/SettingOffsetTimeView.kt) 直接用它显示和累加。
- [Media3VideoPlayer](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) 初始化时读取持久化值；普通字幕时钟与 libass 会话也从持久化读取。mpv 的 [MpvSubtitleRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/MpvSubtitleRendererBackend.kt) 绑定时却从 `PlayerInitializer.Subtitle.offsetPosition` 应用延迟。

对生产源码的定向检索没有找到把已保存偏移回填到 `PlayerInitializer.Subtitle.offsetPosition` 的初始化路径。**已有非零保存值后重启进程，按这条代码路径，Media3 可能应用旧偏移，界面却显示 0；mpv 则从界面单例初值应用 0。** 此后点击步进也会从错误基数累加。这是源码推导的冷启动风险，本轮没有运行该情景；不能据此声称所有当前播放都已错位。

Plezy 的 [PlayerSyncOffsets](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/services/player_sync_offsets.dart) 让界面读取播放器实例实际应用的值，换媒体通过 `applyFor()` 重新解析偏移；[ScopedPlayerPrefs](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/services/scoped_player_prefs.dart) 单独负责保存范围，避免把“界面值”“已写到内核的值”“应为本片恢复的值”混在一起。

**选择 Plezy 的状态归属原则。** 最小改进是统一偏移的初始化、应用和界面读数，并区分保存值与内核已应用值。当前修复保留本项目既有全局保存规则，未新增本次/本片作用域，也不恢复已移除的媒体库设置作用域；其他粒度需要独立需求和真实媒体身份设计。

全局保存并非天然错误，但当前每次临时调校都会写入全局；为某片修正后，下一片也会继承。Plezy 把这个选择显式化，更适合字幕调校的实际使用情景。未来作品级/来源级键必须映射本项目真实媒体身份，不能只按片名保存。

### 2. 样式设置按会话 backend 判断，无法表达混合渲染（P2，源码确认）

[DanDanVideoPlayer.configureSubtitleRenderer()](../../player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt) 为 Media3 会话注册 libass 后端；[LibassRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt) 同时明确让普通文字/位图继续走控制器。当前格式分流本来就允许一个会话内存在两种绘制路径。

但 [SettingSubtitleStyleView.kt:110–129、349–352](../../player_component/src/main/java/com/xyoye/player/controller/setting/SettingSubtitleStyleView.kt) 只检查 registry 的 backend：只要不是 `LEGACY_CANVAS`，就隐藏文字大小、文字颜色、描边宽度和描边颜色等设置。因此选择实际由 [SubtitleImageView](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleImageView.kt) 显示的 SRT/VTT 时，这些原生视图具备的能力也被一并隐藏。

Plezy 的 [ExoPlayerCore.setSubtitleStyle()](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L3990) 分别更新普通 SubtitleView 与 ASS 缩放，这种职责区分更适合当前混合管线。

**选择按当前有效轨道的格式和真实输出能力显示设置。** 普通文字、ASS、位图、mpv 分别处理；没有有效轨道时明确展示默认配置含义。沿用已有格式判定即可，不需要新建一套插件能力系统。

也不能把 Plezy 的设置页直接照搬：

- 它普通文字的 `borderSize` 同样只是决定是否启用标准 outline，不能证明任意数值都对应不同描边宽度。本项目现有百分比描边对原生 Cue 也有相同表达限制。
- 它的通用设置页有 ASS override 模式，但所核对的 Android [ExoPlayerPlugin 属性入口](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerPlugin.kt#L1223) 没有将该属性应用给 ExoPlayer/libass；`setSubtitleStyle` 对 ASS 主要设置字号缩放。UI 中有选项不能当作所有内核均已支持。
- 本项目对 mpv 隐藏无等价实现的透明度、保留 ASS 原样式，是合理的能力边界。

### 3. 选轨偏好缺少跨文件语义（P2，源码确认的设计差距）

[VideoTrackBean](../../data_component/src/main/java/com/xyoye/data_component/bean/VideoTrackBean.kt) 没有独立的语言、codec、default/forced 等字段；从 Media3 格式构造菜单项时，许多信息只剩显示名字。[Media3VideoPlayer.initPlayer()](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) 固定优先 `zh`，无法表达“简体全文”“繁体全文”“只显示强制字幕”等用户意图。

本项目并非没有恢复能力：[PlayerActivity.restoreSourceScopedState()](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 会加载本文件已绑定的外挂；[PlayerViewModel.storeTrackAdded()](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerViewModel.kt) 按真实文件/存储身份保存路径；[SubtitleFinder](../../core_storage_component/src/main/java/com/xyoye/common_component/utils/subtitle/SubtitleFinder.kt) 有同目录文件名优先级。但这些不能代替跨集的内嵌字幕偏好，内核自身的默认选择或 override 延续也不是明确的应用层语义匹配。

Plezy 的 [SubtitlePreference](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/services/subtitle_preference.dart) 把 Off、当前文件轨道身份和跨文件选择意图区分开。跨集时身份降为 language/forced/title/codec 等语义；[选择服务](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/services/track_selection_service.dart#L379) 优先匹配语言和字幕类别，再比较标题及技术属性，顶层证据仍并列时不猜轨道。

它还用明确的选择梯级处理缺省行为，在轨道尚未到齐时等待，并在[用户显式选择时撤销待执行自动选择](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/services/track_manager.dart#L446)。这种状态管理比零散的默认语言、文件名规则更完整。

**选择 Plezy 的语义模型和显式优先级。** 本项目只需保留当前文件精确选择、跨集语义意图、默认策略这几层，现有修订/取消机制继续复用。无需复制 Plex/Emby 账户规则和服务器保存协议。CHS/CHT 与 `zh-Hans/zh-Hant` 的实际匹配需要保留并单独验证，不能把双方都是 `zh` 当成简繁一致。

### 4. 文字与位图共用整体几何，边界处理不够细（P2，源码确认的设计差距）

本项目已经完整保留 Cue，包括样式和定位；不能沿用旧结论说“文字仍被压平”。当前 [SubtitleImageView](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleImageView.kt) 将完整文字/位图交给一个 SubtitleView，跟随视频 View 尺寸，字号使用 DIP，位置使用整个 View 的 `translationY`。

Plezy 的 [SubtitleCueLayout](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/SubtitleCueLayout.kt) 优先处理默认垂直位置，保留脚本明确定位；[SubtitleViewLayout](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/SubtitleViewLayout.kt) 区分文字布局与位图合成平面。后者能处理字幕平面与裁剪后视频比例不一致的素材，证据不足时回到可见视频区域。

**选择 Plezy 的文字/位图分治和合成平面计算。** 这解决的是两个已经存在、布局依据不同的输出类型，不需要泛化为新渲染框架。适配时应维持本项目已确认的 video viewport、fit/fill/zoom 与位置设置语义；是否允许用户把字幕放进黑边属于另外的产品选择，本报告不恢复该需求。

文字字号采用视口比例更利于不同 TV 分辨率和密度下保持一致，但不能直接把 Plezy 的数值代入本项目：应映射当前可见大小和百分比，避免升级后字幕突然变大或变小。它的并发无定位 Cue 堆叠补偿需要先核对本项目所用 Media3 `1.11.1` 的行为，不能无条件叠加一个旧版本补丁。

### 5. 外挂重复添加缺少稳定身份（P2，源码确认）

[Media3ExternalSubtitle.prepare()](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3ExternalSubtitle.kt) 每次生成新 UUID 与缓存文件；[Media3VideoPlayer.addTrack()](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) 将新配置追加到当前 item。当前路径没有按原始资源去重，同一字幕再次添加会成为另一条轨道并再次替换 source；缓存目录在播放器释放时统一清理。

Plezy 在 [addSubtitleTrack()](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L3891) 先按 URI 查重，已有轨道直接选择。**这项选 Plezy。** 用本项目原始资源身份维护当前媒体内的去重即可，无需引入跨片全局字幕缓存。

其他加载机制保留本项目：先验证真实 ASS 事件或普通 Cue、16 MiB 上限、可取消 IO、source/revision 检查、真实轨道到达后回调，再保留队列/位置/暂停/速度/Surface。Plezy 新增外挂也会重新准备 source；不能把对齐它理解成一定消除缓冲。

## 应保留的本项目设计

1. **公开接口提取器组合。** 用户已明确认可；迁移参考项目的私有字段反射不会改善项目功能成熟度。压缩失败的局部恢复另见[既有容错待办](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#compressed-subtitle-error-tolerance-todo-2026-11-04)。
2. **组内完整枚举与本地命名。** [Media3VideoPlayer.getTracks()](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt) 遍历全部 group/track，保存完整 format ID；[命名器](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleTrackNameProvider.kt) 明确显示简体/繁体、角色/强制信息，重名另加序号。Plezy ExoPlayer 当前用每组首轨代表该组，不必照搬。轨道元数据与单行显示可以兼得。
3. **Off 保留实际选择。** [快捷菜单](../../player_component/src/main/java/com/xyoye/player/controller/setting/SubtitleQuickView.kt) 的 Off 调用显隐而非取消选择，[控制器](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt) 同时管原生 Cue 和 libass 层。用户已确认这一行为及控制器生命周期，不应因参考项目而改为新的跨播放器设置。
4. **有效的异步取消保护。** 当前 `selectTrack()`、`deselectTrack()` 都会取消旧外挂加载，不存在“本项目完全没有用户选择优先保护”的证据。外挂完成回调经 `ControlWrapper → VideoController → PlayerActivity` 保存绑定，没有直接重开显隐；不能把“异步加载可能覆盖 Off”的猜测列为已发现故障。未来增加自动选轨时仍需覆盖完整的意图优先级。
5. **普通字幕偏移的已消费 Cue 恢复。** 本项目此前实际验证了仅改变 renderer 时间、禁用再启用文本轨仍不能恢复已消费 Cue，最终用 source 替换通过暂停和正负偏移回归。Plezy [SubtitleDelayRenderer](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/PlezyRenderersFactory.kt#L484) 只修改 `render()` 时间，目前不能证明满足同样场景。代码短和无重建不能代替正确性证据；有等价回归证据后再优化。两项目正负定义不同：本项目正值是提前，Plezy 正 delay 是延后；本项目 mpv 已取反映射，这一点不是符号故障。
6. **按 source 隔离预加载字幕和字体。** [Media3SubtitleInput](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3SubtitleInput.kt) 与[时间映射 renderer](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassTimelineRenderer.kt) 处理实际来源与时间窗口；[顺序播放适配器](../../player_component/src/main/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapter.kt) 有真实队列/预加载需求。Plezy 的当前媒体 handler 不能直接替换这些责任；这也不等于已证明 Plezy 有预加载缺陷。

## 本轮证据和验证建议

使用 `ast-grep 0.40.5` 核对 Kotlin 结构，命中后读取实际源码。主要查询如下；不是全项目缺陷计数。

| 范围 | 模式 | 命中 | 结论用途 |
| --- | --- | ---: | --- |
| 本项目播放器生产源码 | `$R.setPreferredTextLanguage($V)` | 1 | 确认固定 `zh` 的默认入口 |
| `SubtitleQuickView.kt` | `$R.setUserSubtitleVisible($V)` | 2 | 确认 Off 和选轨开启的调用 |
| `SettingSubtitleStyleView.kt` | `SubtitleRendererRegistry.current()?.backend` | 2 | 确认样式能力按会话 backend 分流 |
| `Media3VideoPlayer.kt` | `MixedSubtitle.fromCues($CUES)` | 1 | 确认完整 Cue 输出 |
| 同上 | `cancelSubtitleLoad()` | 8 | 阅读换源、选轨、取消/完成边界 |
| Plezy `ExoPlayerCore.kt` | `SubtitleCueLayout.layout($$$ARGS)` | 1 | 确认布局辅助类实际被调用 |
| 同上 | `$R?.setFractionalTextSize($V)` | 1 | 确认视口比例字号实际被调用 |

Dart 不受此版本 ast-grep 支持，使用定向源码读取；负面结论不从 AST 零命中推导。Claude Context 本轮不可用，使用 `rg`、AST 和文件阅读。没有开展重复率或安全审计，未运行 jscpd/semgrep；本次功能方案评估也没有修改生产代码，因此没有重跑 Gradle。参考源码、下载和 AST 记录只在被忽略的 `.tmp/subtitle-feature-comparison/`，不要求纳入 Git。

已阅读 Plezy 的 [SubtitlePreference 测试](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/test/services/subtitle_preference_test.dart)、[隐藏恢复测试](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/test/mpv/player_android_subtitle_visibility_test.dart)、[文字布局测试](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/test/kotlin/com/edde746/plezy/exoplayer/SubtitleCueLayoutTest.kt) 和[位图/视口测试](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/android/app/src/test/kotlin/com/edde746/plezy/exoplayer/SubtitleViewLayoutTest.kt)。它们分别描述身份降级、隐藏跨集、显式定位保留、裁剪与位图比例等目标行为；本轮未执行，不能写成测试已通过。

原评估给出的建议顺序如下；当前只实施第 1 项及第 3 项的具体缺陷修复，其余仍是设计建议或独立待办：

1. 先收敛偏移状态和样式能力判定。验收非零保存值冷启动、设置界面读数、双内核切换、SRT/ASS/位图设置项和实际效果。
2. 补轨道元数据，再做跨集语义偏好。验收简繁顺序互换、全文/forced 区分、无语言/重名歧义、Off、迟到轨道及用户选择覆盖自动任务。
3. 在现有加载流程补外挂去重。验收连续添加相同本地/content 资源、加载失败/取消、换片与队列恢复，不能产生重复菜单项或旧结果接管。
4. 分开验证文字布局与位图平面。覆盖多密度/分辨率、原有样式定位、并发字幕、裁剪视频、fit/fill/zoom 与 Surface 重建；先确认上游已处理的情景再决定是否加补偿。
5. 压缩块容错和 [Atlas 待办](../../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#libass-atlas-rendering-todo-2026-11-04) 按各自既有边界推进。Atlas 收益仍需同素材测量，不自动启用预热、多线程或呈现补偿。

Plezy 的跨平台能力也不能直接计入 Android Media3：其 [PlayerAndroid](https://github.com/edde746/plezy/blob/1dd75b3e5e95913cdaa5b8c22911490e72dfb7c5/lib/mpv/player/platform/player_android.dart#L64) 明确不支持第二字幕轨。因此本轮没有把双字幕、在线服务同步或新增设置面板列为必须补齐的项目差距。

**原 115 视频仍待新版用户复测。** 这份方案比较不能证明原片已恢复，也不能把任何新发现自动认定为它无字幕的唯一原因。
