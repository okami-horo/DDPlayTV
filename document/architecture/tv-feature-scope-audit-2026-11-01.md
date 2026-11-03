# TV 功能范围与冗余审查（2026-11-01）

后续状态：用户已授权清理全部 A01–A08，代码、依赖、资源及 Room 21→22 升级已实施，并于 2026-11-03 确认实机验收通过；结果与验证范围见[清理执行账本](../../docs/superpowers/plans/2026-11-01-tv-scope-cleanup.md)。B01–B03 和可选产品设计取舍未纳入当时的清理；用户随后要求移除低价值和有限价值的旧待办，当前处理如下。

| 旧项 | 2026-11-03 处理 |
| --- | --- |
| B01 启动动画精简／关闭时跳过布局 | 收益有限，取消待办；启动实现和动画开关保持现状 |
| B02 主题配色一致性 | 保留按实际可读性问题核对的方向，未排期；现有主题与保存功能继续保留，原“移除主题切换”建议不作为实施方案 |
| B03 年份选择器替换 | 随[旧番剧发现链路](../../docs/superpowers/plans/2026-11-01-legacy-anime-discovery-cleanup.md)删除年份选择器及专用滚轮后失效，关闭替换任务 |

以下保留原始审查证据与当时的验证限制；“仍存在”“可清理”和源码行号描述的是清理前状态。已删除文件可从实施基线 `d0ee40e29` 查看，不能把历史描述当作当前实现。

审查对象为 `cb0c27dff` 加当前工作区已有的全来源元数据、PC 退役、ARouter 升级修复等暂存变更。判断依据是当前“仅维护 TV/DPAD”的产品范围，以及源码、依赖和组件声明；旧版项目介绍不作为功能仍然存在的证据。本次没有修改应用代码、依赖、数据库或现有产品设计，也没有操作实体电视。

结论：最值得先处理的是无业务消费者却进入 Release 的下载/投屏依赖，以及播放器每次前台启动都会绑定的空消费服务。其次是 Media3 迁移期脚手架、没有发送入口的弹幕发送链路、孤立模块和已退役能力的残留。没有发现应直接删除整个动漫、存储、播放器、用户设置或元数据模块的依据。

本文的“可清理”是审查建议，不表示已实施；涉及产品功能的取舍另列，不把“TV 不常用”等同于“已授权删除”。P2 表示有明确的运行、体验或维护成本，P3 表示低优先级清理。

## 确定的冗余与清理候选

### A01 · P2：未使用的 Media3 下载、投屏和会话依赖进入 Release

- 证据：[player_component/build.gradle.kts](../../player_component/build.gradle.kts#L175) 仍声明 `media3-exoplayer-workmanager`、`media3-session`、`media3-cast`。生产源码未检出 `androidx.work`、`androidx.media3.exoplayer.workmanager`、`androidx.media3.cast`、`androidx.media3.session` API 的使用，也没有业务 `Worker`、`CastPlayer` 或 `CastContext`。
- 实际影响：本次 `releaseRuntimeClasspath` 查询确认下载依赖引入 `androidx.work:work-runtime:2.8.1`，Cast 依赖引入 `play-services-cast-framework:22.3.1`。已有 Release 合并 Manifest 中存在 `WorkManagerInitializer`、WorkManager 服务/接收器、Cast 重连服务/媒体接收器，以及 Media3 会话库的 `BluetoothValidationActivity`。WorkManager 已具备随 AndroidX Startup 初始化的入口，不能当作只有几行无害声明。
- [Media3CastManager](../../app/src/main/java/com/okamihoro/ddplaytv/app/cast/Media3CastManager.kt#L38) 默认 `castSenderEnabled=false`，没有生产构造/调用方；`prepareCastSession` 也只是准备数据，没有真正的投屏发送流程。关联的 `LegacyCapabilityMapper`/`CodecFallbackHandler` 仅连接这套旧辅助链路，没有接入当前播放主路径。
- 建议：优先移除没有消费者的三个直接依赖及孤立的发送/旧映射辅助代码，再检查依赖树、合并 Manifest 和构建结果。保留当前的手机投到 TV 的接收服务，保留真正被字幕代码使用的 `media3-ui`。
- 复杂度检查：减少依赖、初始化入口和无实际行为的分支，不需要引入替代框架。当前 Release 未启用 minify，因此不能假定无引用代码已被裁剪；未进行前后 APK 对比，不宣称具体体积或启动耗时收益。

### A02 · P2：播放器绑定 Media3SessionService 后没有使用服务

- 证据：[PlayerActivity.onStart](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt#L477) 使用 `BIND_AUTO_CREATE` 绑定服务；[连接回调](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt#L367) 只设置 `media3ServiceBound`，未保存客户端，也未调用其 `playback()` 或 `dispatch()`。`onStop` 再解除绑定。
- [Media3SessionService](../../app/src/main/java/com/okamihoro/ddplaytv/app/service/Media3SessionService.kt#L15) 是 `LifecycleService` 加普通 Binder，只转发已有 `PlaybackRequestService` 的状态/命令，并非实际的 AndroidX `MediaSessionService`。服务没有其他生产客户端。
- [Media3BackgroundCoordinator](../../app/src/main/java/com/okamihoro/ddplaytv/app/service/Media3BackgroundCoordinator.kt#L18) 明确拒绝后台/画中画；[PlayerActivity.onPause](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt#L499) 仍暂停播放。
- 实际影响：每次播放器进入前台都承担一次无结果的服务绑定及对应生命周期、路由和错误处理维护。该链路当前没有提供媒体通知、后台播放或画中画。
- 建议：清理此绑定、空消费者服务、专用 Intent Provider/客户端接口及只服务该链路的转发层，继续由现有播放会话拥有状态和命令。不要顺带删除真实的 `PlaybackRequestService`/`ActivePlaybackSession`。
- 复杂度检查：移除无消费者的中转路径，不增加第二个会话所有者，也不需要为删除服务新建抽象。

### A03 · P2：Media3 灰度、遥测、下载校验仍保留迁移期结构

- 证据：[Media3Repository](../../core_network_component/src/main/java/com/xyoye/common_component/network/repository/Media3Repository.kt#L8) 的遥测方法直接返回成功，下载校验固定返回兼容；[Media3Service](../../core_network_component/src/main/java/com/xyoye/common_component/network/service/Media3Service.kt#L10) 仍声明两个远端接口，但当前服务工厂未创建它。
- [Media3TelemetryRepository](../../player_component/src/main/java/com/xyoye/player_component/media3/telemetry/Media3TelemetryRepository.kt#L41) 保留队列、批量提交和重试，没有生产实例化方；[Media3LocalStore](../../core_database_component/src/main/java/com/xyoye/common_component/media3/Media3LocalStore.kt#L11) 的快照/下载校验存取也未找到生产调用方。Room 仍注册 `media3_rollout_snapshot` 和 `media3_download_asset_check` 两张表。
- [Media3ToggleProvider](../../core_system_component/src/main/java/com/xyoye/common_component/config/Media3ToggleProvider.kt#L8) 和构建开关仍存在，`gradle.properties` 为 `media3_enabled=false`，但当前默认内核是 Media3。启动时 [Media3CrashTagger.init](../../player_component/src/main/java/com/xyoye/common_component/media3/Media3CrashTagger.kt#L18) 仍把这个迁移开关写成 `media3.boot_enabled`；其 `tagSession`/`tagSnapshot` 没有生产调用。该标签不能代表实际使用的内核。
- 实际影响：维护一套没有接入业务的旧会话/灰度/网关模型，并留下会误导排障的启动标签。未连接的遥测队列不产生当前播放期间的上传或排队开销，不能把它描述成正在持续上报。
- 建议：移除失效的迁移期结构及恒成功占位 API；若继续需要崩溃内核标签，应直接来自实际内核。数据库表的清理由单独的版本迁移处理，不能直接删除实体导致已有用户升级失败。
- 复杂度检查：删除未使用的政策与数据流，保留现有日志、HTTP 日志、字幕遥测和真实播放状态，无需重建遥测平台。

### A04 · P3：弹幕发送界面已不存在，上传和文件写入链路仍在

- 证据：[PlayerViewModel.sendDanmu](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerViewModel.kt#L249) 及后续上传/追加 XML 方法仍保留；生产源码/XML 中未找到 `sendDanmu` 的调用方或 `SendDanmuBean` 的业务构造方。
- [InterDanmuController](../../player_component/src/main/java/com/xyoye/player/wrapper/InterDanmuController.kt#L77) 仍声明 `allowSendDanmu`、`addDanmuToView`，实现只在这一组接口内部流转。`ResourceRepository.sendOneDanmu` 也只由上述不可达方法调用。
- 实际影响：当前 TV 用户无法通过界面完成发送，却仍维护发送专属的接口、模型、时间/颜色转换、上传和本地写入。当前没有执行这些网络请求或文件写入的证据。
- 建议：以移除这组不可达发送代码为默认简化方向；若后续明确需要手机遥控输入再单独设计。保留弹幕匹配、下载、播放、屏蔽，以及播放历史中的弹幕绑定。
- 复杂度检查：删除没有入口的端到端链路，不把 TV 输入需求扩展为新的遥控服务。此结论也不意味着历史“弹幕发送待办”已经完成。

### A05 · P3：panel_switch 是孤立的构建模块

- 证据：[settings.gradle.kts](../../settings.gradle.kts#L21) 仍包含 `:repository:panel_switch`；[其构建文件](../../repository/panel_switch/build.gradle.kts#L1) 只包装 `panelSwitchHelper-androidx.aar`。所有当前 Gradle 项目依赖中，没有其他模块依赖它；其余引用属于依赖治理白名单/说明和许可文件。
- 实际影响：继续维护、配置一个没有消费者的模块。没有依赖边意味着不能据此声称该 AAR 已进入主 APK。
- 建议：移除项目注册和包装模块，并同步治理及许可清单。
- 复杂度检查：只减少一个构建节点，不合并任何仍有业务职责的 feature/core 模块。

### A06 · P3：PC 扫描、手机电量和磁力下载的残留可集中清理

- PC 扫描：[RadarScanView](../../storage_component/src/main/java/com/xyoye/storage_component/ui/weight/RadarScanView.kt#L28) 和 [ScanWindowView](../../storage_component/src/main/java/com/xyoye/storage_component/ui/weight/ScanWindowView.kt#L18) 仅在各自实现与 styleable 中被检出，没有布局实例或代码构造方。两文件合计约 580 行，相关属性/颜色仍保留。
- 手机电量：[BatteryHelper.kt](../../player_component/src/main/java/com/xyoye/player_component/utils/BatteryHelper.kt#L3) 整体被注释；Activity 的创建/绑定/注册/释放调用也被注释。`BatteryView`、`BatteryBroadcastReceiver` 和属性资源没有活动的消费链路。它们不代表当前播放器还在监听电量。
- 磁力下载：[DownloadConfigTable](../../core_system_component/src/main/java/com/xyoye/common_component/config/DownloadConfigTable.kt#L10) 仍生成 DHT/下载并发设置，但没有生产读写方。
- 其他旧设置：[AppConfigTable](../../core_system_component/src/main/java/com/xyoye/common_component/config/AppConfigTable.kt#L27) 的 `showFTPVideoTips`、`jsoupUserAgent` 没有生产读取方；不能因为名称是配置就认为有有效功能。
- 建议：把这些确定不可达的实现及专属资源一起删除。保留云盘二维码登录、投屏接收所用的 `RemoteScanData`、PC 旧数据解码字段及迁移逻辑；它们与被删扫描界面不是同一用途。
- 复杂度检查：减少退役能力的尾部维护，不新增兼容层；兼容旧数据的代码只在确认升级策略后处理。

### A07 · P3：四个旧模块入口和备用 Manifest 已脱离构建

- 证据：anime/local/storage/user 中仍各有 `ui.activities.main.MainActivity`、空 `MainViewModel` 和配套资源。这四个 Activity 没有注册到当前主 Manifest，也没有当前路由入口或显式启动方。
- 五个 feature 下仍保留 `src/main/app_manifest/AndroidManifest.xml`；当前各模块使用固定的 library 插件，[moduleSetup](../../buildSrc/src/main/java/setup/Module.kt#L15) 也没有引用这些备用 Manifest。当前 Gradle 源码和属性中未检出 `IS_APPLICATION_RUN` 或 `app_manifest` 的配置入口。
- 实际影响：看起来仍支持“单模块应用运行”，实际没有构建开关，容易让后续维护落到不会运行的入口。它们不是仍在运行的手机主界面。
- 建议：删除停用的开发入口及其独占资源/文档说明；保留主应用的旧组件名 alias，以兼容已保存的启动入口。
- 复杂度检查：保留唯一真实组合根，不为恢复一个没有需求的开发模式增加构建变体。

### A08 · P3：部分基础依赖已不再服务当前代码

- [core_storage_component/build.gradle.kts](../../core_storage_component/build.gradle.kts#L24) 仍引入 Jsoup，但当前生产源码没有 `org.jsoup` 使用，相关 User-Agent 配置也失去消费者。它是清理候选，应在移除后核对依赖和构建，不能把它与 WebDAV 使用的 XML/JAR 依赖一起删掉。
- [IApplication](../../app/src/main/java/com/okamihoro/ddplaytv/app/IApplication.kt#L14) 仍调用 `MultiDex.install` 并引入兼容库；当前 [minSdk 为 25](../../buildSrc/src/main/java/Versions.kt#L4)，已经使用原生支持多 DEX 的 ART。旧 Dalvik 安装兼容层没有当前平台目标。删除兼容安装层与关闭多 DEX 输出是两件事，不建议据此把 `multiDexEnabled` 改为 false。
- 复杂度检查：移除确认无消费者/不适用平台的依赖；不修改字幕编码处理、协议解析或 DEX 容量策略。

## 有明确简化价值、但属于设计取舍的项目

### B01 · P2：默认关闭的启动动画仍先创建完整动画布局

- [BaseAppCompatActivity](../../core_ui_component/src/main/java/com/xyoye/common_component/base/BaseAppCompatActivity.kt#L26) 先执行 `initDataBinding()`，随后才调用子类 `initView()`；[BaseSplashActivity](../../app/src/main/java/com/okamihoro/ddplaytv/ui/splash/BaseSplashActivity.kt#L42) 到这时才检查“展示启动页”。
- [activity_splash.xml](../../app/src/main/res/layout/activity_splash.xml#L9) 无条件包含 SVG 和文字路径控件；[TextPathAnimView 构造](../../app/src/main/java/com/okamihoro/ddplaytv/utils/image_anim/path/TextPathAnimView.kt#L64) 会创建文字 Path/动画对象，关闭开关并不会跳过这些构造。
- 开启动画时还会等待路径动画和额外 350ms，并在此期间拦截 BACK。五个自定义路径/动画文件合计约 2,300 行，只服务这个展示。
- 建议：TV 启动直接进入主界面，删除动画开关和专属引擎；若保留动画，至少在关闭时避免 inflate。收益是减少一次展示和专属实现，不需要新的启动框架。当前没有测量冷启动毫秒数。

### B02 · P2：固定暗色 Cinema 与“普通/深色模式”互相冲突

- [Cinema 颜色](../../core_ui_component/src/main/res/values/cinema.xml#L3) 固定暗色，没有相应浅色覆盖；旧 `text_black`/`layout_bg_color` 仍随日夜资源切换。
- [主题选择页](../../user_component/src/main/res/layout/activity_switch_themes.xml#L47) 自己就在固定暗色 Cinema surface/card 中使用 `text_black`。普通模式下该字色是 `#353535`，暗色 surface 是 `#202026`，静态色值对比约为 1.32:1；页面既切不了整套 Cinema，又可能让旧字色与新背景混用。
- [SwitchThemesActivity](../../user_component/src/main/java/com/xyoye/user_component/ui/activities/switch_themes/SwitchThemesActivity.kt#L39) 还保留保存、重启确认和 `exitProcess(0)`；当前 [ThemedContext](../../core_ui_component/src/main/java/com/xyoye/common_component/utils/theme/ThemedContext.kt#L15) 也为旧主题模型维护缓存。
- 建议：若继续采用已确认的 Cinema 视觉，固定深色并移除这套切换；若产品确需浅色，应先形成完整浅色设计。删除选择器可减少无效选项、退出重启和资源组合维护。这是源码推导，本次未打开电视复现浅色截图，也不宣布已修改视觉设计。

### B03 · P2：单个年份选择维护约 3,200 行触摸滚轮

- [DatePickerDialog](../../anime_component/src/main/java/com/xyoye/anime_component/ui/dialog/date_picker/DatePickerDialog.kt#L20) 的实际需求只是选择 `1980..当前年`。
- 它独占自定义 `NumberPicker.java` 和 `Scroller.java`，合计约 3,200 行，包含触摸速度跟踪、fling、软键盘、滚轮、长按和虚拟可访问节点等完整通用数字选择实现。
- 当前 NumberPicker **已经有 DPAD 处理和边界测试**，不能报告成遥控器不可用。冗余的是为一个有界整数维持的通用触摸实现。
- 建议：用简单的左右步进或年份列表保留该功能，继续验证边界、长按、焦点和返回。复杂度收益来自缩小组件责任，不能通过删掉可访问性支持或年份筛选功能来凑代码量。

## 可选产品精简，不能当作死代码

| 候选 | 已确认的实际行为 | TV 取舍建议 |
| --- | --- | --- |
| 本地头像选择、昵称/密码修改 | [PersonalFragment](../../user_component/src/main/java/com/xyoye/user_component/ui/fragment/personal/PersonalFragment.kt#L96) 维护本地头像索引；[UserInfoActivity](../../user_component/src/main/java/com/xyoye/user_component/ui/activities/user_info/UserInfoActivity.kt#L50) 有真实账户修改请求 | 头像选择与播放无关，优先精简；账户维护可移到手机/网页。登录、追番、云历史仍有消费者，不能连同整个 user 模块删除 |
| 运营横幅与通用内嵌网页 | [HomeFragment](../../anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home/HomeFragment.kt#L56) 会取横幅；[HomeBannerAdapter](../../anime_component/src/main/java/com/xyoye/anime_component/ui/adapter/HomeBannerAdapter.kt#L48) 进入 WebView | 可去掉横幅/网页链路以减少外链导航和网页适配；保留每周番剧、搜索和详情。没有访问实时接口，不断言横幅实际内容或网页遥控可用性 |
| “账户与工具”挂入整张旧个人页 | [AccountToolsActivity](../../user_component/src/main/java/com/xyoye/user_component/ui/activities/setting_app/AccountToolsActivity.kt#L17) 直接复用 PersonalFragment；内含播放器/应用设置、扫描、缓存、常用目录、字幕搜索、投屏等 | 功能本身多有价值，但与主设置重复，且字幕/投屏被放进账户分类。可将保留工具归回媒体/播放/维护类别，缩小个人页；这属于导航设计建议，不是要求删除功能 |

## 明确保留及排除的误判

- **投屏接收、九类存储、普通播放缓存、Media3/mpv、字幕/弹幕、TMDB 和动漫资讯**均在当前范围内。网盘/网络协议对 TV 获取内容有直接价值，不能因不在本地播放主路径就删除。
- **PlayerGestureView** 当前是遥控快进/快退预览，`TvVideoController` 调用其位置反馈；名字过时不等于仍有手机手势。
- **Dependencies.Github.keyboard_panel** 实际指向 `juniversalchardet`，四个字幕格式解析器使用 `ReaderFactory`。不能因为注释写“键盘面板”就删除它；它也不是孤立的 panel_switch 模块。
- **耳机/音频变吵广播和屏幕关闭广播**对蓝牙耳机/音响断连、关屏播放行为仍有意义，不能当手机专属逻辑移除。
- **SwipeRefreshLayout** 仍承担文件列表加载指示及刷新状态；触摸下拉可简化，但当前不是完全无用途的依赖。
- **旧字段、历史枚举、升级清理和启动 alias**承担兼容职责。PC 旧行保留隐藏与移除 PC 功能并不矛盾。
- **日志与开发工具**对无法接 adb 的电视排障有实际价值，应保留；未调用的旧 Media3 遥测脚手架与现有 HTTP 日志/字幕遥测是不同链路。
- 当前 `AGENTS.md` 的模块概览、旧技术版本、Bilibili/手机/磁力和单模块运行说明有过时内容；`README` 的 PC 媒体库列表也未同步退役。这是文档债，不能据此报告上述能力仍然存在。本次未修改 agent 规则。

## 覆盖范围与验证

- 枚举当前 `settings.gradle.kts` 的 **22 个模块**，检查入口/Manifest、五个业务模块的页面与路由、设置配置、依赖声明，以及上述候选的生产 Kotlin/Java/XML 引用；重点逐段读取候选与实际调用方。
- core_storage/core_metadata 的保留协议、扫描和身份边界按相关范围抽查，没有把全部实现逐行审计。未审查第三方 AAR/native 内部源码、实时云端响应、完整安全面或性能基准；本次也不是视觉/DPAD 验收。
- 工具：`rg`、`ast-grep` 和 `jscpd` 不在 PATH；无可调用的 Claude Context，`ccc status` 报 uv trampoline 路径错误。因此使用 `git ls-files`、`git grep`、Node 引用枚举和人工源码核对，**不声称 AST 完整调用图或全仓死代码证明**。未安装工具或重建索引。此次不涉及安全审查，未运行 Semgrep。
- 关键文件 SHA-256 和引用结果保存在本机 `.tmp/tv-scope-audit/source-evidence.json`；构建/依赖日志同目录。Release 合并 Manifest 是已有构建产物，本次以新查询的 Release 依赖树交叉核对，没有重新打 Release APK 或测量删减收益。
- 检查结果反映命令执行时的工作区。审查期间还出现了独立的元数据匹配/解析/TMDB 客户端改动，本次没有改写或验证这些改动；收尾重新核对的九个审查关键文件哈希未变，本文检查结果不作为并行元数据工作的回归证据。

| 检查 | 命令（仓库根目录） | 实际结果 |
| --- | --- | --- |
| 模块依赖治理 | `scripts/dev-env/gradlew-local.bat --offline --console=plain verifyModuleDependencies` | 尾部确认 `BUILD SUCCESSFUL` |
| 首次 Debug lint | `scripts/dev-env/gradlew-local.bat --offline --console=plain verifyModuleDependencies :app:lintDebug` | `BUILD FAILED`：离线缓存缺少 coroutines-test 1.7.1，未据此判断源码失败 |
| 使用已有本地 Maven 配置重试 lint | `scripts/dev-env/gradlew-local.bat --offline --console=plain -I .tmp/ui-repairs-local.gradle :app:lintDebug` | 尾部确认 `BUILD SUCCESSFUL`；报告为 24 条 warning，其中 8 条为 app 自身未用资源；不是全仓零警告 |
| Cast 运行依赖 | `scripts/dev-env/gradlew-local.bat --offline --console=plain :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency media3-cast` | 尾部确认 `BUILD SUCCESSFUL`，确认 `player_component` 引入 |
| 完整 Release 依赖树 | `scripts/dev-env/gradlew-local.bat --offline --console=plain :app:dependencies --configuration releaseRuntimeClasspath` | 尾部确认 `BUILD SUCCESSFUL`，核对 WorkManager/Cast 的传递依赖 |

没有改动应用代码，因此没有重复运行 JVM/仪器测试或占用实体电视。上述静态检查不能替代实际裁剪后的构建、升级兼容和保留功能回归；既有 Release NotificationPermission 问题也没有因此被修复。

原审查建议的 A01–A08 已有独立实施与验收记录。B 类当前仅保留 B02 的按需配色核对；B01 已取消，B03 已随旧功能退役关闭。上文历史建议不自动形成新任务。所有数据库清理仍须保护现有用户数据和升级路径。
