# TV 体验问题与播放验证待办

整理日期：2026-10-28；体验问题更新：2026-11-01。本文集中记录本轮用户反馈的 TV 体验问题与尚未完成的顺序续播验证；有效设计约束归入对应维护／设计文档。

综合验收由用户负责，进度见[交接实施账本](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)。关闭以下验证项时需给出对应场景的实际证据，不能以部分构建／设备结果代替完整验收。

## TV 体验问题记录

本节最初按用户“先记录”的要求维护。用户随后授权排查并删除全应用无意义的指导说明，UX02 已实施；文件菜单精简随后也已独立实施，证据见其任务账本。UX01 仍待办。

### UX01：点击视频后出现两种加载界面

- **反馈日期与状态**：2026-10-31，用户报告；已核对相关源码，尚未在设备上复现或修复。
- **用户观察**：点击视频开始播放时，会出现两种“正在加载”的界面，用户质疑这种重复是否必要。具体来源、内核及两种界面的实际切换时序尚未确认。
- **初步判断**：内部可以有播放源准备、播放器初始化和缓冲等多个阶段，但没有必要仅因阶段切换而展示两套不同的加载界面。建议让首次打开视频呈现连续、一致的等待反馈，减少闪切和重复提示；保留必要的错误提示与退出能力。

2026-10-31 源码核对发现两套相关反馈，作为后续定位线索，不据此宣称已经复现用户所见的完整过程：

| 位置 | 当前行为 |
| --- | --- |
| [PlayerActivity.kt](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 的 `preparationView` / `renderPlaybackScreen` | `state.preparing` 时显示文本；首次播放为全屏黑底“正在准备播放…”，已有播放时为顶部“正在准备新视频…” |
| [LoadingView.kt](../../player_component/src/main/java/com/xyoye/player/controller/video/LoadingView.kt) 与 [layout_loading.xml](../../player_component/src/main/res/layout/layout_loading.xml) | 内核进入准备或暂停缓冲状态时，显示转圈与“正在准备播放…”或“正在缓冲…”的居中面板 |

后续处理方向与验收要点（待实施任务确定，不在本次修改代码）：

- [ ] 复现并确认两种界面的出现顺序、持续时间与触发来源；区分首次播放、播放中换集和卡顿缓冲。
- [ ] 首次播放各准备阶段使用一致的加载反馈，同一时刻只显示一套提示，不因切换界面产生闪白、闪黑或短暂重复。
- [ ] 快速完成时避免无意义的加载闪现；慢网络时仍有持续反馈，失败能说明原因并返回，不能简单隐藏提示留下无反馈黑屏。
- [ ] 保留播放中缓冲、换集失败恢复及返回/取消行为；覆盖 Media3 与 mpv，不改变播放源、历史、字幕和弹幕的既有准备流程。

目前只有用户反馈与源码线索；没有新增截图、设备验证或构建结果。

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

以下两项继续待验收。现行职责和行为边界见[活动会话设计第 12 节](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md#123-顺序续播的产品边界)。

- [ ] **Media3 集成／冒烟场景**。分别记录以下四类结果：有下一项时在原播放页续播；无下一项时正常完成；手动切换后旧准备结果／旧切换回调到达不覆盖当前来源和绑定；预准备失败时当前项不受影响，完成后按目标可用性降级切源或正常完成。同时核对目标项标题、历史、弹幕、字幕和外挂音轨，避免沿用上一项文件级绑定。
- [ ] **相关 Gradle 验证与 TV 播放主路径**。将适用的构建、单元测试、lint／治理结果与上述场景对应，并以纯 DPAD 完成进入播放器、续播／完成、返回及退出，确认焦点可见、可达、可返回。Gradle 证据必须包含命令、源码版本／工作区差异以及尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

已有 [SequentialPlaybackCoordinatorTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinatorTest.kt)、[Media3SequentialPlaybackAdapterTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapterTest.kt) 和 [ActivePlaybackSessionTest](../../player_component/src/test/java/com/xyoye/player_component/playback/session/ActivePlaybackSessionTest.kt) 可作为策略、队列和会话层证据入口；源码中存在这些用例不等于已经完成上述播放器集成与 TV 冒烟。

交接账本中的字幕／弹幕连续性及完整导航验收与这里存在交集；只有实际覆盖对应场景的记录才能复用，同一证据无需重复执行，尚未覆盖的场景继续保留。
