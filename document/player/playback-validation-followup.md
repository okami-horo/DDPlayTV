# TV 体验问题与播放验证记录

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

整理日期：2026-10-28；体验问题更新：2026-11-02；用户实机验收归档：2026-11-03。UX01、UX02 与顺序续播验证均已由用户确认通过。下文保留原实施与测试记录；有效设计约束归入对应维护／设计文档。

综合验收由用户负责，进度见[交接实施账本](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)。关闭以下验证项时需给出对应场景的实际证据，不能以部分构建／设备结果代替完整验收。

## TV 体验问题记录

本节最初按用户“先记录”的要求维护。UX02 和文件菜单精简已独立实施；用户于 2026-11-02 授权处理 UX01，双加载界面已在任务模拟器复现，当前修复与证据见下文。

### UX01：点击视频后出现两种加载界面

- **反馈日期与状态**：2026-10-31 记录；2026-11-02 获用户授权后，在 API 34 AVD 的 Media3、mpv 真实播放中复现并修复，本地构建、状态回归、遥控器交互及截图检查通过。尚未交付或操作物理电视。
- **复现方式**：[PlaybackLoadingInstrumentedTest](../../app/src/androidTest/java/com/okamihoro/ddplaytv/playback/PlaybackLoadingInstrumentedTest.kt) 使用真实 WebDAV 来源，分别阻塞 PROPFIND 和视频响应；两个阶段稳定呈现“全屏文字 → 居中转圈”。原生产代码上两项双内核测试均因来源阶段没有使用同一加载面板而失败，随后均已实际播放真实视频。
- **根因**：来源解析属于会话准备，内核准备属于播放状态；两个独立视图分别消费这两套状态。仅统一文字不能消除视图替换、位置变化及重复叠层风险。

修复前的两条显示链路：

| 位置 | 修复前行为 |
| --- | --- |
| [PlayerActivity.kt](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 的 `preparationView` / `renderPlaybackScreen` | `state.preparing` 时显示文本；首次播放为全屏黑底“正在准备播放…”，已有播放时为顶部“正在准备新视频…” |
| [LoadingView.kt](../../player_component/src/main/java/com/xyoye/player/controller/video/LoadingView.kt) 与 [layout_loading.xml](../../player_component/src/main/res/layout/layout_loading.xml) | 内核进入准备或暂停缓冲状态时，显示转圈与“正在准备播放…”或“正在缓冲…”的居中面板 |

#### 实现与状态覆盖

`PlayerActivity.renderPlaybackScreen` 将来源准备状态交给 `VideoController`，与内核状态共同驱动原有 `LoadingView`。移除 Activity 的独立文字覆盖层；沿用 [layout_loading.xml](../../player_component/src/main/res/layout/layout_loading.xml) 的居中转圈与卡片样式。来源准备和内核准备统一显示“正在准备播放…”，播放中缓冲显示“正在缓冲…”。

等待超过 200ms 才显示面板；已显示后跨来源／内核交接保持连续，不重新计时。内核 READY／初始 seek 的缓冲结束不等于首帧，准备反馈保留到播放／暂停画面到达。失败、完成、释放和退出取消待显示回调；来源准备失败时复用现有错误与旧播放恢复逻辑。

| 场景 | 设计／实现与检查 | 验证边界 |
| --- | --- | --- |
| 首次慢来源 → 慢内核，Media3 / mpv | 同一个居中面板；真实 WebDAV / 视频；交接绘制帧连续性断言；查看两个阶段截图 | 最终三项加载／取消 AVD 用例通过，播放后面板隐藏 |
| 快速准备、初始 seek、暂停首帧 | 200ms 延迟、取消延迟回调、READY／缓冲结束保留反馈 | [LoadingViewTest](../../player_component/src/test/java/com/xyoye/player/controller/video/LoadingViewTest.kt) 九项通过 |
| 播放中缓冲与恢复 | 同一面板显示缓冲文字，恢复后消失 | JVM 状态回归；真实网盘卡顿矩阵仍单列 |
| 准备中 BACK、失败、换集与返回 | 实际遥控器取消阻塞中的来源，确保不迟到启动；复用错误、双内核文件返回、跨季 DPAD 和设置持久化用例 | 最终十项兼容 AVD 用例通过；完整文件级轨道绑定仍见顺序续播待办 |
| 物理电视及其他真实来源 | 未操作用户电视 | 待 Release 交付及用户／物理电视验收 |

#### 复现、构建与验证记录

基线为 `513d37928` 加本轮三个生产 Kotlin 文件及两份测试。原失败证据：`.tmp/ux01/red-build.log` 为 **BUILD SUCCESSFUL in 3m 17s**；`red-device.log` 两项真实双内核用例均按预期失败。`red-unit.log` 为 **BUILD FAILED in 36s**，五项中两项正确暴露快速闪现和 READY 过早隐藏；首轮修复后同组五项 **BUILD SUCCESSFUL in 26s**。补查首帧前 seek 的准备状态用例在 `red-first-frame.log` 中正确失败（**BUILD FAILED in 17s**），补齐准备状态保持逻辑后，最终九项全部通过。

最终构建命令：

```powershell
& .tmp/watch-history/gradle.ps1 :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :app:lint :player_component:lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/ux01/scoped-format.gradle --offline --console=plain
```

设备用例前置夹具为 `.tmp/lease-fixtures/share/playback-long.mp4`（120 秒真实视频），需推送至 `/sdcard/Download/ddplaytv-lease-playback-long.mp4` 并授予 Debug 包 `READ_MEDIA_VIDEO`。测试自行建立／清理本地 WebDAV 来源并恢复原内核设置；运行类为 `com.okamihoro.ddplaytv.playback.PlaybackLoadingInstrumentedTest`，runner 为 `com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`。本轮使用任务实例 `127.0.0.1:5583`、私有 ADB `-P 5081`，未接入用户电视。

- `.tmp/ux01/full-gates.log`：本轮完整 JVM、全仓标准 lint、构建与治理检查，尾部 **BUILD SUCCESSFUL in 7m 57s**。补齐初始 seek 边界后，上述最终命令的 `final-gates.log` 为 **BUILD SUCCESSFUL in 47s**；复用未变模块的 lint 结果，重跑受影响 app／player lint。ktlint 仅检查本轮五个 Kotlin 文件。
- 最终 included 模块的 JVM XML 共 115 套件、518 项：**516 通过、2 项既有跳过、0 失败／错误**，包含新增九项加载状态回归；未统计已退役模块的遗留报告。
- 最终版本首次三项设备复查有一项 Media3 失败，记录于 `device-final-attempt1.log`。日志显示受控视频请求持续等待到 35 秒网络超时；检查本地 AndroidX Test Core 1.7.0 字节码，确认 `ActivityScenario.onActivity` 先调用 `waitForIdleSync`，不适合持续动画期间的及时采样。测试改为直接主线程读取状态，截图在测试线程采集；仍保留同一面板、交接帧连续性及真实内核播放断言。测试修改后的首次构建因换行格式失败（`final-test-harness-format-failed.log`，**BUILD FAILED in 15s**），限定格式化后，AndroidTest 构建、app lint、scoped ktlint 在 `final-test-harness.log` 中 **BUILD SUCCESSFUL in 14s**。
- 最终安装的 Debug／测试 APK 哈希均与本地一致。API 34、1920 × 1080、320dpi、默认字体的任务 AVD 上，`device-final.log` 为 **OK (3 tests)**／11.088s，`smoke-final.log` 为 **OK (10 tests)**／74.171s，共 **13 个不同场景**。包含真实 Media3／mpv、DPAD／BACK、重建／会话重入、手动／原生下一集、历史恢复、错误返回及设置持久化；用例全名、源码／APK／截图哈希见[结果与身份记录](ux01-loading-2026-11-02/results.json)。

实际查看的同场景截图如下。两内核修复前均为“纯文字 → 卡片”，修复后来源与内核阶段使用相同位置、大小及文字的卡片；转圈角度随采集时间变化。

| 内核／阶段 | 修复前 | 最终修复后 |
| --- | --- | --- |
| Media3 来源准备 | [截图](ux01-loading-2026-11-02/before-media3-source.png) | [截图](ux01-loading-2026-11-02/after-media3-source.png) |
| Media3 内核准备 | [截图](ux01-loading-2026-11-02/before-media3-kernel.png) | [截图](ux01-loading-2026-11-02/after-media3-kernel.png) |
| mpv 来源准备 | [截图](ux01-loading-2026-11-02/before-mpv-source.png) | [截图](ux01-loading-2026-11-02/after-mpv-source.png) |
| mpv 内核准备 | [截图](ux01-loading-2026-11-02/before-mpv-kernel.png) | [截图](ux01-loading-2026-11-02/after-mpv-kernel.png) |

本轮没有运行物理电视验收、全部真实云账号／卡顿矩阵或覆盖率测量；没有 Release 构建、版本变更或提交。任务 AVD 5582／私有 ADB 5081 在验证后关闭，默认 ADB 5037 未操作。UX01 的本地修复已完成，电视交付与用户验收继续保留。

### UX02：常规遥控器操作说明过多

- **反馈日期与状态**：2026-10-31 记录；2026-11-01 按用户后续授权完成代码与原型清理，并进行本地构建、模拟器交互及截图检查。未安装到用户电视。
- **用户观察**：上下左右导航、确认、返回属于常规 TV 操作，界面无需反复解释，常驻说明增加视觉负担。用户随后补充：播放器底部菜单部分元素获得焦点时还出现多余的中文说明，也应纳入精简。
- **适用范围**：排查应用模块的 Kotlin 文案、XML 布局和字符串资源，覆盖播放器、文件浏览、元数据、设置、弹窗、番剧列表及搜索。保留错误恢复、权限/登录、输入格式、单位、状态、必要动作名及无障碍描述。

| 清理前已核实样例 | 处理方向 |
| --- | --- |
| [文件浏览底栏](../../storage_component/src/main/res/layout/activity_storage_file.xml)：方向键选择、确定打开、菜单更多、返回上一级 | 移除常驻按键教学；保留同区域内有用的真实状态，不因删说明留下空白占位 |
| [文件预览](../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt)：按确定打开文件夹、按确定播放、按菜单查看文件操作 | 删除重复主操作说明；不把移除的文件菜单换成另一段常驻教学 |
| [播放器选集面板](../../player_component/src/main/res/layout/layout_switch_video_source.xml)：上下选择、确认切换、返回后回到选集按钮 | 删除常规导航说明，以选中态和清晰控件表达操作 |
| [PlayerBottomView.kt](../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt) 与 [底部布局](../../player_component/src/main/res/layout/layout_player_bottom.xml)：六个播放/选集控件聚焦时，将 `contentDescription` 复制到浮动的 `transport_hint`，包括播放/暂停、前后跳转、上一集/下一集、选集 | 精简重复解释图标或按钮名称的焦点附加文字；保留必要按钮名称、跳转步长等有效信息、焦点高亮及无障碍描述 |
| [作品详情提示](../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataDetailActivity.kt)：选择文件播放、长按更正资料、合并集共享进度混在同一段 | 拆分评估：删除常规教学；有实际影响的状态/规则按需呈现，特殊入口随其功能退役安排处理 |

实施结果与保留原则：

- [x] 删除方向键、确认、常规返回教学，包括文件浏览底栏/预览、选集、轨道面板的说明；未新增教学开关。
- [x] 删除 `transport_hint` 及聚焦、播放状态更新时的写入，保留 `lastFocusedControlId`、焦点导航、播放/暂停无障碍描述和 A 单排结构。
- [x] 保留错误、空状态、权限、真实任务进度及数值。任务行使用“取消任务 / 重试”动作名，重试入口继续可聚焦。
- [x] 删除头像、分词、外观、扩展名、字体缓存等重复说明及设置页宣传句；移除孤立说明控件及其专用资源，修正相邻约束/间距。
- [x] 删除文件浏览空目录、加载、搜索无结果和空分页的重复教学；保留对应状态标题、操作按钮与授权/错误恢复说明，空说明不占位。
- [x] 关键词列表用“移除关键字”标明动作；作品详情仅在实际有合并集时显示共享进度规则，无文件时保留访问检查提示。
- [x] 在本轮覆盖场景检查布局、焦点与返回；未将所有真实服务/物理电视状态标记为通过。

与[文件浏览菜单精简任务](../../docs/superpowers/plans/2026-10-31-file-browser-menu-simplification.md)重叠的教学文字已在 UX02 独立清理。UX02 当时未修改文件菜单和按键语义；2026-11-01 后续菜单任务已移除这些入口、统一 MENU/SETTINGS 刷新，并在预览中承接长名阅读，最新证据以该任务账本为准。UX01 的两套加载界面和自动刮削均未在这两个任务中实施。

#### 页面与状态覆盖

原型沿用已选 A/C 及 Cinema，已同步五份 HTML；本轮原型/原生截图均为 1440 × 810，模拟器密度 240 dpi。比较说明位置、焦点与内容边界；播放原型背景与原生彩条夹具不同，不据此声称真实字幕/弹幕内容的全量视觉验收。截图根目录为 `.tmp/ui-hints/`。

| 页面/状态 | 设计与实现 | 本轮验证 |
| --- | --- | --- |
| 播放单排、首/中/末集、选集 | [A 原型](../prototypes/tv-ui/player-controls-prototype.html)、[选集原型](../prototypes/tv-ui/deep-pages.html)；删除焦点附加文字/页尾教学 | 查看 `after-player-focused.png`、`after-episodes.png` 与 `native/ui-polish-verification/player-*.png`；原有选集返回、禁用控件跳过用例通过 |
| 字幕空轨道、音轨列表、关键词空/有数据 | [深层面板原型](../prototypes/tv-ui/deep-pages.html)；删除返回说明，关键词列表约束接标题 | 实际播放后 DPAD 打开/返回；查看 `native/tracks.png`、`audio.png`、`keywords-empty.png`、`keywords-list.png`、`subtitle-return.png`；添加测试词、聚焦后确认移除，XML 回读验证移除成功。输入法使用系统现状，未改其行为 |
| 文件目录/视频、长名、空/加载/错误/分页 | [C 原型](../prototypes/tv-ui/files-prototype.html)；删除底栏、预览及空状态中的重复教学 | `after-files.png`、`after-files-empty.png`、`after-files-loading.png` 与 `native/file-browser-c/`；浏览组 5 项复查通过，首次批量失败保留如下 |
| 设置、外观、头像、分词、扩展名、字体缓存 | [Cinema](../prototypes/tv-ui/index.html)、[其余页面](../prototypes/tv-ui/remaining-pages.html)；删除宣传与重复说明 | 查看 `native/settings.png`、`ui-polish-verification/SwitchThemesActivity.png`、`ui-remaining-verification/` 中头像、分词、扩展名、字体进度截图；相关焦点、持久化及返回用例通过 |
| 作品详情/更正、空扫描任务、番剧列表各状态 | 既有原型结构；删除教学并保留数据与动作 | 元数据详情/更正返回及空任务用例通过；查看 `native/tmdb-verification/ui-polish-detail.png` 和列表状态截图。合并集提示、运行中任务文案、搜索错误文案仅完成源码/构建检查，未逐一造现场 |

#### 构建与交互证据

源码基线 `3ade60dfa` 加本轮工作区修改；本轮 24 个生产源码/资源文件与五份原型变更，无测试代码变更。最终命令：

```powershell
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/ui-hints/style.gradle --offline --console=plain
```

- 最终日志 `.tmp/ui-hints/final-build.log` 尾部为 **BUILD SUCCESSFUL in 1m 23s**；包含完整 JVM、lint、依赖治理及八个受影响 Kotlin 文件的 scoped ktlint。复用本机离线依赖与短临时目录配置，没有改业务逻辑绕过验证。
- `.tmp/ui-hints/jvm-results.json`：116 个测试套件、482 项，其中 **480 通过、2 项既有跳过、0 失败/错误**。
- 任务模拟器 `emulator-5564`（API 34）直接运行 Debug runner。`.tmp/ui-hints/device-final.log`：**18 项中 17 通过、1 失败**；涵盖真实 Media3/mpv 播放返回、底部焦点、设置与弹窗、列表错误/恢复、元数据页面。
- 首次失败为 `FileBrowserPreviewInstrumentedTest.directoriesPreviewAndExactMoreCallerSurviveBackAndRecreation`：关闭菜单并快速反向移动后，BACK 未在等待期内回到父目录。未修改源码/用例，单项复查 `.tmp/ui-hints/browser-repro.log` **OK (1 test)**，同类整组复查 `.tmp/ui-hints/browser-suite.log` **OK (5 tests)**。根因未确认，不能把首次批量结果改写为 18/18，也不能认定已修复偶发时序问题。
- 最后补充清理文件浏览空状态说明后，运行同一组 init scripts 下的 `:app:assembleDebug :app:assembleDebugAndroidTest :storage_component:testDebugUnitTest :storage_component:lint verifyModuleDependencies ktlintCheck`，日志 `.tmp/ui-hints/final-empty-build.log` 尾部 **BUILD SUCCESSFUL in 31s**；重新安装任务模拟器并运行整个文件浏览组，`.tmp/ui-hints/browser-final-empty.log` **OK (5 tests)**。已更新并查看空/加载/错误截图，其余未变模块复用前述验证。
- 补查在任务模拟器建立 SAF Movies 夹具并真实播放，用 DPAD 检查轨道面板、关键词添加/移除和逐级返回；夹具建立使用了系统目录授权和触摸，不能将这段补查记成纯 DPAD 来源配置验收。纯 DPAD 播放/返回路径证据来自上述仪器用例。
- 初次误用不带 `.debug` 的 runner 包名导致未启动用例，之后已修正；不计入应用用例失败。真实服务全矩阵及物理电视验收仍待完成，本任务未操作电视。
- 收尾检查：16 个变更 XML 解析通过，五份原型脚本语法与 85 处本地文档链接检查通过，`git diff --check` 通过。任务模拟器、独立原型浏览器与本地原型服务已关闭，日志/截图保留。

## 顺序续播

2026-11-01 补充：[统一剧集队列账本](../../docs/superpowers/plans/2026-11-01-series-playback-queue.md)已记录双内核整剧跨季、自动下一集、版本取消/确认、Activity 重建、目录原生队列及返回的本地 AVD 通过证据，并链接 JVM/构建门禁与实际查看的截图。可复用其对应场景；下列完整轨道绑定、真实云账号和物理电视验收仍保留，不能以这次局部验证全部勾选。

以下两项于 2026-11-03 由用户确认实机验证通过并关闭；相关 Gradle 结果沿用已记录的原构建，不新增自动化通过声明。现行职责和行为边界见[活动会话设计第 12 节](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md#123-顺序续播的产品边界)。

- [x] **Media3 集成／冒烟场景**（2026-11-03 用户确认）。分别记录以下四类结果：有下一项时在原播放页续播；无下一项时正常完成；手动切换后旧准备结果／旧切换回调到达不覆盖当前来源和绑定；预准备失败时当前项不受影响，完成后按目标可用性降级切源或正常完成。同时核对目标项标题、历史、弹幕、字幕和外挂音轨，避免沿用上一项文件级绑定。
- [x] **相关 Gradle 验证与 TV 播放主路径**（沿用历史工程证据，2026-11-03 用户确认实机主路径）。将适用的构建、单元测试、lint／治理结果与上述场景对应，并以纯 DPAD 完成进入播放器、续播／完成、返回及退出，确认焦点可见、可达、可返回。Gradle 证据必须包含命令、源码版本／工作区差异以及尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

已有 [SequentialPlaybackCoordinatorTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinatorTest.kt)、[Media3SequentialPlaybackAdapterTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapterTest.kt) 和 [ActivePlaybackSessionTest](../../player_component/src/test/java/com/xyoye/player_component/playback/session/ActivePlaybackSessionTest.kt) 可作为策略、队列和会话层证据入口；源码中存在这些用例不等于已经完成上述播放器集成与 TV 冒烟。

交接账本中的字幕／弹幕连续性及完整导航验收与这里存在交集，本次一并按用户确认关闭。历史自动化结果仍按原范围保留，不把用户验收换算为新增测试数量；未来变更按实际受影响场景复测。
