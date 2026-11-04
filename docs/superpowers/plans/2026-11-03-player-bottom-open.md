# 播放器开放式底栏实施与验证

日期：2026-11-03。用户已明确确认实施[进度条在上的设计](../designs/2026-11-03-player-bottom-open-design.md)。**原生调整及本机验证已完成；实体电视与用户最终验收待进行。**本轮仅调整底栏及其焦点反馈，不授权占用实体电视测试。

后续弹幕开关跟进：用户决定底部直接切换开关，详细配置保留在右侧设置栏。[补充设计与 D01–D04 矩阵](../designs/2026-11-03-player-bottom-open-design.md#弹幕开关补充设计用户已确认)于 2026-11-03 获“确认，可以实施”，**原生与本机验证已完成，实体电视/用户最终验收待进行**。新开关证据见本文末尾；下述 B05/B06 描述追加前的行为。

最新跟进：用户授权移除右侧“显示弹幕”总开关，统一由底部控制；**实现与本机验证已完成，实体电视/用户最终验收待进行**。当前职责及 S01–S04 结果见[侧栏调整记录](#弹幕侧栏移除总开关2026-11-03)；早前侧栏总开关同步结果保留为历史。

字幕后续：用户确定底部单选面板“关、字幕轨道……”的交互，并在纵向换行稿基础上明确要求“长名称保持单行，自动滚动”，随后回复“按照我的方案实施一下”。**原生实施及本机验证已完成；实体电视/用户最终验收待进行。** 行宽和行高统一，只有聚焦的长名称停留约 1 秒后滚动，勾选固定，离焦复位；右侧只保留详细配置和添加外挂字幕入口。当前结果见[Q01–Q07 字幕记录](#字幕快捷面板原生实施2026-11-03)。此前 36 项换行稿 HTML 观察为历史，不替代本轮单行滚动及原生验证。

本次实施顺序：

1. 更新当前原型为单行等高和焦点滚动 → 看图并验证文字方向、停留、离焦与固定勾选。
2. 底部接入原生字幕单选面板，移除右侧重复操作 → 验证选轨开启、关闭、浏览不应用与返回原焦点。
3. 构建/JVM/lint、任务模拟器纯 DPAD、长名称/多语言/大字体截图与真实双内核回归 → 逐项记录；实体电视仍留待用户验收。

此前弹幕开关实施顺序：

1. 补充底栏开关与侧栏同步回归 → 先在旧实现验证直接开关断言失败。
2. 接入既有开关与已确认图标/文字 → 核对空轨道、后续加载、状态通知和自动保存。
3. 构建/JVM/lint、任务模拟器 DPAD 与四态截图对照 → 单列结果；实体电视验收仍待用户进行。

## 范围与顺序

1. 对齐 `player-bottom-refinement-prototype.html?variant=open` → 核对最新 `player-bottom-open-top-2026-11-03/open-full.png`；保留进度条在上、九个操作、原字幕区域和既有面板。
2. 修改底栏布局、局部图标/背景/焦点与操作提示 → 构建及播放器单元测试；不调整播放队列、轨道和设置业务。
3. 本机任务模拟器验证 → 同画面与密度截图、纯 DPAD、首末集/单文件/不可跳转及设置返回；分别记录构建、运行、视觉与用户验收。

## 页面与状态矩阵

| 编号 | 状态与确认设计 | 实现 | 验证 |
| --- | --- | --- | --- |
| B01 | 普通播放/暂停；九个操作聚焦 | `layout_player_bottom.xml`、局部状态资源、`PlayerBottomView` 操作提示 | 已纯 DPAD 遍历九项、核对操作提示与完整可见；查看了播放、快进、选集和字幕聚焦原生图 |
| B02 | 首集/末集禁用占位，上一集/下一集与选集 | 保留原业务，透明间隔替代分隔线；收紧左侧排列 | 首末集禁用/跳过焦点、选集切换、中间集前后集均通过；已查看边界截图 |
| B03 | 单文件收口与不可跳转 | 保留原显隐、禁用、跳过焦点与回退播放键 | 单文件隐藏剧集入口通过；受控 `isSeekable=false` 下两键禁用、焦点回播放、右移跳到字幕通过 |
| B04 | 明暗画面与大字体 | 小圆形按钮、选集胶囊、较短底部渐暗、完整文字 | 1.0/1.3 字体与明暗静帧均通过，文字无截断、底边完整；已实际看图 |
| B05 | 三个轨道入口、面板关闭、隐藏/恢复及退出 | 沿用 A06 面板；底栏记住九个入口的焦点 | 三入口、空字幕轨、字幕选择/禁用、BACK 返回调用者、隐藏禁用输入及下键恢复通过；加载/错误内容沿用已接受实现，未新增专项结果 |
| B06 | 真实播放、进度、设置持久化 | 保留 Media3/mpv 与原设置链路 | 两内核在面板后持续真实播放并推进进度通过；倍速/弹幕设置重新打开后持久化、字幕偏移重置和页面返回通过 |

## 证据与当前结果

- 已查看确认原型与同静帧原生对比：[底栏比较](../../../document/prototypes/tv-ui/previews/player-bottom-native-2026-11-03/comparison-bottom.png)、[原生完整界面](../../../document/prototypes/tv-ui/previews/player-bottom-native-2026-11-03/middle-play_iv.png)、[状态汇总](../../../document/prototypes/tv-ui/previews/player-bottom-native-2026-11-03/states.png)。[验证 JSON](../../../document/prototypes/tv-ui/previews/player-bottom-native-2026-11-03/verification.json)保留 24 张图片的 SHA-256、18 项坐标记录及本次源码哈希；图片按仓库规则只保留本地。
- 同画布 1440×810 / 240dpi，原生轨道实际 y=669、横向 x=60–1380；按钮行 y=697、左组结束 x=476，三圆形按钮 x=1158/1240/1322，均与设计对应。移除整组底板，保留九项操作；局部浅底深图标与 160ms 上浮反馈、聚焦名称已实施。
- 看图后修正了系统 SeekBar 默认相对内边距、选集图文居中，以及 Android 字体下名称偏低 6px 的差异。未改字幕、播放队列、轨道或 A06 设置面板代码。

## 构建与自动化

所有结果均查看了日志尾部，使用本机 JDK 17 入口 `.tmp/watch-history/gradle.ps1` 与现有离线依赖 init。

| 命令范围 | 结果与本地日志 |
| --- | --- |
| 初次 `:app:assembleDebug :player_component:testDebugUnitTest` | **BUILD SUCCESSFUL**，`.tmp/player-bottom-open/build-main.log` |
| `:app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies --continue` | **BUILD SUCCESSFUL**（13m 21s），`validation.log`；全量 Debug JVM、lint 和模块依赖治理通过 |
| 最终 `:app:assembleDebug :app:assembleDebugAndroidTest :player_component:lintDebug` 与两个改动 Kotlin 文件的定域 ktlint | **BUILD SUCCESSFUL**（25s），`build-visual-final.log`；播放器 JVM 最近一次结果在 `build-final.log`，同样成功 |
| 最终截图夹具与测试文件检查 `:app:assembleDebugAndroidTest :app:ktlintCheck` | **BUILD SUCCESSFUL**（9s），`test-fixture-final.log` |
| 底栏三项 + 原有面板/内核/持久化/字幕回归 | **OK (7 tests)**，`instrumentation-accepted.log` |
| 修正参考静帧插入层后复跑三个底栏用例 | **OK (3 tests)**，`instrumentation-visual.log` |
| 1.3 倍字体、明暗画面和隐藏恢复复核 | **OK (1 test)**，`instrumentation-font130-visual.log`；已恢复 1.0 |

仪器用例位于 `PlayerPanelLayersInstrumentedTest`，新增 `openBottomBarRetainsEveryActionAndRemotePanelReturn`、`openBottomBarSingleAndUnseekableKeepAUsableFocus`、`openBottomBarKeepsLabelsAndFocusOnBrightAndDarkFrames`。相关回归为同类的 `rightPanelKeepsBothRealKernelsPlayingAndReturnsByRemote`、`speedChangesPersistAcrossPanelReopeningAndResetByRemote`，以及 `UiPolishInstrumentedTest#firstEpisodeSkipsDisabledPreviousAndPanelBackRestoresCaller`、`FocusFeedbackInstrumentedTest#subtitleSelectionSurvivesFocusAndDisableRemainsAnAction`。

中间失败已纠正并重跑：首次定域格式命令使用了不存在的任务名；初次仪器断言把动画变换后的向外取整边界当作裁剪；隐藏后误用确认键期待展开（实际应使用方向下键）。参考静帧在换集后需要重新挂载，最终截图已修正夹具并重新生成。上述未归因于生产播放故障。lint 输出仍含 mpv Kotlin 元数据版本诊断，但该轮最终为 BUILD SUCCESSFUL，不据此宣称依赖兼容问题已治理。

## 对比边界与验收

- 原型时间与原生显示夹具均为 09:42 / 24:00，底层播放的是实际短测试片，不将该显示值作为真实视频位置。不可跳转用例使用可恢复的受控能力覆盖，不等同于真实直播流集成验证。
- 明暗图为同一静帧 RGB 亮度模拟；真实动态播放由 Media3/mpv 用例另行验证，不把静帧对比作为动态视频或实体设备证据。
- 中间集将相同字幕文本送入现有 SubtitleController。原生继续按既有字幕位置、尺寸、透明度渲染，HTML 使用固定 x=488/y=720 的展示文字；这部分已知差异沿用原生设置，本轮未修改。左右操作都不侵入字幕中间区域。
- 原生 SeekBar 沿用 `max=100`，40.42% 取整为 40%，竖标比 HTML 靠左约 6px；这是保留的进度精度，非横向内边距问题。时间及名称的字形栅格化存在 Android/浏览器差异。
- 本轮只在只读、无快照的任务模拟器 `emulator-5572` 安装 Debug 主包与仪器包；没有实体电视安装或导航。模拟器已结束；用户/实体电视最终验收仍待进行。此前历史批次的验收不包含本次新底栏。

## 弹幕开关原生实施与验证（2026-11-03）

用户确认后，底栏弹幕按钮改为派发既有 `PlayerAction.ToggleDanmu`。图标使用开/关 selector、关闭斜线和“弹幕开 / 弹幕关”文字，焦点仍沿用现有圆形背景与上浮，不改变进度条和按钮几何、字幕或其他操作。详细配置仍可经右侧设置栏进入。

显示偏好仍由 `DanmuView` 统一持有，通过既有轨道更新通知同步底栏与侧栏；新增 `DanmuConfig.showDanmu` 自动保存，默认开启，旧安装无需迁移。移除“未选中轨道则不能切换”的限制；无弹幕、晚到的轨道和重新创建播放器都保持用户开关。实际渲染仍同时要求轨道选中及用户开启，不因选择/加载轨道而覆盖偏好。

| 验证 | 结果与证据 |
| --- | --- |
| 原行为复现 | 新仪器用例在旧实现失败于 `Bottom danmaku must toggle directly`；`instrumentation-red.log`。早先一次测试编译受 app 无法访问弹幕 AAR 父类影响，改用现有 View 边界后编译成功；编译错误不计为行为复现 |
| 构建、全量 Debug JVM、lint、依赖治理 | `.tmp/danmaku-toggle/validation.log`：**BUILD SUCCESSFUL**（3m 11s）；命令 `:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies --continue` |
| 四个受影响 Kotlin 文件的定域格式检查 | `format-check.log`：**BUILD SUCCESSFUL**；未全仓格式化 |
| DPAD 与行为回归 | `instrumentation-final.log`：**OK (7 tests)**；新增直接开关/侧栏同步和重新创建播放器的两项回归，并重跑三项底栏、双内核面板、倍速保存用例 |
| 1.3 倍字体 | `instrumentation-font130.log`：**OK (1 test)**；四态截图已生成并实际查看关闭聚焦全屏，文字完整；已恢复 1.0 |
| 视觉核对 | 实际查看了[四态原生图](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/four-states.png)、[同静帧逐态比较](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/comparison-four-states.png)和关闭聚焦全屏；1440×810 / 240dpi，普通态用播放键聚焦，聚焦态用弹幕按钮 |
| 实体电视 / 用户最终验收 | **待进行**；本次仅使用任务模拟器，未安装或操作用户电视；任务模拟器已关闭 |

新增用例是 `PlayerPanelLayersInstrumentedTest#bottomDanmakuToggleKeepsFocusAndSynchronizesWithRightPanel` 与 `#bottomDanmakuPreferenceSurvivesARecreatedPlayer`。覆盖纯 DPAD 到达/切换、关闭时可聚焦、隐藏/恢复、MENU 进入右侧详细设置、开关双向同步、BACK 原焦点、空轨道与晚到轨道、实际渲染 View 显隐及跨播放器保存。原底栏回归已更新为字幕/音轨打开轨道面板，弹幕由新用例验证直接开关。

本地图片、10 项图片哈希/尺寸与源码哈希见 [player-danmaku-native-2026-11-03/verification.json](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/verification.json)。背景静帧内已有弹幕，不把静帧文字当作开关渲染证据，实际载入 XML 轨道的显隐另有断言。字幕设置、SeekBar 百分比量化与 Android/浏览器字形差异沿用基础布局。焦点材质保留原生内描边：中心 x=1351 的浅色实心像素 y=700–755，原型 y=699–756；这是既有内描边/原型外光圈差异，未更改 58px 控件框或独立焦点语义。

## 弹幕侧栏移除总开关（2026-11-03）

用户明确要求实施：底部负责弹幕总开关，右侧负责详细设置。该时点 `SettingTracksView` 的 `trackVisible` 仅在字幕轨道显示并切换字幕；此行为随后由下述字幕快捷面板替代。`trackStyle` 单独控制显隐，字幕、弹幕均保留样式入口。轨道列表、添加轨道、类型过滤、屏蔽和时间偏移保持既有能力；本小节所述弹幕调整未修改共享布局或字幕业务。

| 编号 | 状态与设计 | 实现与验证 |
| --- | --- | --- |
| S01 | 弹幕轨道为空，移除总开关 | 原生 `danmaku-tracks-empty.png` 已实际查看；总开关不可见且不能取得焦点，默认聚焦添加按钮，下键到显示样式 |
| S02 | 已加载弹幕轨道，移除总开关 | 原生 `danmaku-tracks-loaded.png` 已实际查看；保留列表、选中状态、添加和样式入口，列表有焦点 |
| S03 | 底部关闭弹幕后编辑详细设置 | 原生 `danmaku-style-while-off.png` 已实际查看；DPAD 修改字号并保存，重开后值保留，总开关仍关闭；返回时空轨道默认聚焦添加按钮，逐层 BACK 回到底部原按钮 |
| S04 | 字幕面板及播放器回归 | 保留字幕开关和样式入口；既有字幕空轨道默认焦点、选中/禁用、BACK 用例通过，弹幕偏好跨播放器保持，Media3/mpv 在侧栏后持续播放并推进进度 |

加载/错误状态的数据流及提示未改变；这次没有新增其专项截图或验收。原生对比使用相同静帧、1440×810 / 240dpi / 1.0 字体；已查看[调整前后对比](../../../document/prototypes/tv-ui/previews/player-danmaku-sidebar-2026-11-03/comparison.png)及三张新全屏图，确认移除行折叠，面板边界、底部按钮位置和材质沿用 A06。背景静帧内包含弹幕文字，实际轨道显隐由运行时断言另行验证。

| 验证 | 结果与本地证据（`.tmp/danmaku-sidebar/`） |
| --- | --- |
| 旧行为复现 | `build-test-baseline.log`：**BUILD SUCCESSFUL**；`instrumentation-red.log`：新用例在旧实现失败于 `The sidebar must not repeat the bottom danmaku toggle` |
| 构建、全量 Debug JVM、lint | `validation.log`：**BUILD SUCCESSFUL**（2m 49s）；命令 `:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint --continue`，已查看日志尾部 |
| 最终测试包、app lint、两个改动 Kotlin 文件的定域检查 | `final-test-check.log`：**BUILD SUCCESSFUL**（14s）；命令 `:app:assembleDebugAndroidTest :app:lintDebug :player_component:ktlintCheck :app:ktlintCheck`，ktlint 限定两个修改文件，未全仓格式化 |
| 任务模拟器仪器回归 | `instrumentation-final.log`：**OK (4 tests)**；覆盖底部开关/侧栏详细设置、重新创建播放器、双内核播放与返回、字幕选中/禁用与返回 |
| 实体电视 / 用户最终验收 | **待进行**；本次仅使用任务模拟器，未安装或操作用户电视 |

现用例名为 `PlayerPanelLayersInstrumentedTest#bottomDanmakuToggleKeepsFocusAndSidebarDetailsStayEditable`，替代前述 `#bottomDanmakuToggleKeepsFocusAndSynchronizesWithRightPanel`。另三项为 `#bottomDanmakuPreferenceSurvivesARecreatedPlayer`、`#rightPanelKeepsBothRealKernelsPlayingAndReturnsByRemote` 和 `FocusFeedbackInstrumentedTest#subtitleSelectionSurvivesFocusAndDisableRemainsAnAction`。

初次新实现回归在样式页 BACK 后失败：测试误期望焦点回到“显示样式”，源码 `SettingTracksView.onViewShowed()` 的既有规则实际会聚焦空轨道的首个可用操作“添加弹幕轨”。已将测试改为核对该默认焦点，并使用下键重新进入样式；最终四项回归全部通过。生产代码未为这条测试增加新的焦点恢复机制。

[本轮验证 JSON](../../../document/prototypes/tv-ui/previews/player-danmaku-sidebar-2026-11-03/verification.json)记录五张图片/对比图、源码哈希和结果。本轮未改依赖，沿用上一轮依赖治理证据；未把历史 1.3 字体结果记成本轮新结果。任务模拟器已关闭，已确认 adb 列表为空。

## 字幕快捷面板原生实施（2026-11-03）

用户在纵向换行稿后要求长名称保持单行并自动滚动，随后明确授权实施。本节对应[已确认的单行方案](../designs/2026-11-03-player-bottom-open-design.md#字幕快捷面板单行滚动用户已确认)，不将旧换行稿视为当前设计。**实现、本机构建、DPAD 与视觉核对已完成；实体电视/用户最终验收待进行。**

新增 `SubtitleQuickView` 和两份局部布局，接入既有 `SettingController` 返回栈；底部字幕按钮直接打开此面板。真实轨道前统一加“关”，过滤原接口的合成禁用项。选轨调用既有选择能力并开启显示；“关”只关闭渲染，保留真实轨道选择。底部图标根据有效显示状态同步，关闭使用斜线图标，焦点样式保持独立。右侧字幕组改为“样式与位置、时间同步、添加外挂字幕”，进入现有配置页与文件选择器；不保留重复总开关和常用选轨入口。

| 编号 | 已确认状态 / 实现 | 当前验证 |
| --- | --- | --- |
| Q01 | 同一单选列表：关与真实字幕；浏览不应用、确认应用 | Media3/mpv + 实际外挂 SRT；空/一轨、开启/关闭、取消浏览、重开生效项、BACK 原焦点通过；A02 字幕回归通过 |
| Q02 | 同宽等高、长名称单行；聚焦停留 1 秒后滚动，离焦复位、短名静止，✓ 独立 | 400ms 前后文字像素相同、随后实际变化；离焦后保持复位；短名等待后不变，行宽/行高一致，✓ 坐标固定、焦点项完整可见；初始化选中末项、DPAD 返回首项通过 |
| Q03 | 多语言与 RTL | 与原型相同的九条中/英/德/法/日/阿拉伯文名称；RTL 段落方向为 -1，实际像素变化和静态图均检查；✓ 不随文字移动 |
| Q04 | 1.3 倍字体，保持单行与行高一致 | 字号 1.3 的相同长列表/RTL/关闭/短名用例通过；明暗画面、底栏标签、隐藏/恢复和字幕面板返回通过；已恢复系统字号 1.0 |
| Q05 | 空列表与轨道更新 | 空列表保留可操作的“关”和添加提示；关闭时收到轨道通知不会开启字幕，焦点仍在关；无需滚动的列表隐藏滚动轨道 |
| Q06 | 右侧只负责详细配置与添加外挂字幕 | 断言不含“字幕轨”“显示字幕”；纯 DPAD 进入样式、字幕时间偏移、外挂字幕文件选择，逐层 BACK 返回原底部入口；A06 材质和栈清理回归通过 |
| Q07 | 双内核、底栏其他操作与弹幕详细设置 | 两内核在新面板后保持真实播放且进度推进；九项底部操作、音轨入口、弹幕关闭时编辑/保存详细配置、倍速保存与字幕偏移重置通过 |

字幕可见状态沿用 `SubtitleController` 生命周期，不新增跨播放器重建的全局开关偏好。轨道 API 只有列表，空列表提示可从右侧添加；网页的独立 loading/error/retry 仅为演示，原生继续使用现有播放/加载错误反馈，未引入虚假的重试能力。

### 构建与运行证据

所有 Gradle 结果均查看了日志尾部；使用 `.tmp/watch-history/gradle.ps1`、现有离线依赖 init。定域 Kotlin 检查限定本次九个改动 Kotlin 文件，未全仓格式化。

| 验证 | 结果与本地日志（`.tmp/subtitle-quick/`） |
| --- | --- |
| 主包、测试包、全量 Debug JVM、lint、模块治理 | `:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies --continue`：**BUILD SUCCESSFUL**（7m 35s），`validation.log` |
| 最终视觉修正后的主包/测试包、播放器 JVM、app/player lint、九文件 ktlint | `:app:assembleDebug :app:assembleDebugAndroidTest :player_component:testDebugUnitTest :player_component:lintDebug :app:lintDebug :data_component:ktlintCheck :player_component:ktlintCheck :app:ktlintCheck`：**BUILD SUCCESSFUL**（1m 35s），`validation-visual-final.log` |
| 最终 RTL 取图修正后的测试包与定域 app ktlint | **BUILD SUCCESSFUL**（25s），`test-final-build.log`；没有替代或削弱真实位移断言 |
| 字幕专项及相关仪器回归 | **OK (8 tests)**（111.219s），`instrumentation-accepted.log` |
| 大字体与明暗画面/底栏 | **OK (2 tests)**（22.437s），`instrumentation-font130.log` |
| 隐藏短列表滚动轨道后的两项字幕复核 | **OK (2 tests)**（49.979s），`instrumentation-visual-final.log`；含双内核与长名/RTL |
| 同一最终主包的 1.3 倍字体字幕复核 | **OK (1 test)**（14.531s），`instrumentation-font130-final.log`；实际资源配置为 1.3，结束恢复 1.0 |

八项回归为 `PlayerPanelLayersInstrumentedTest` 的 `subtitleQuickWorksWithBothKernelsAndSidebarContainsOnlyDetails`、`subtitleQuickLongNamesStaySingleLineAndOnlyFocusedTextMoves`、`openBottomBarRetainsEveryActionAndRemotePanelReturn`、`rightPanelKeepsBothRealKernelsPlayingAndReturnsByRemote`、`speedChangesPersistAcrossPanelReopeningAndResetByRemote`、`bottomDanmakuToggleKeepsFocusAndSidebarDetailsStayEditable`、`stackChangesKeepOneDimLayerAndClearItForOtherPanelFormsAndRelease`，以及 `FocusFeedbackInstrumentedTest#subtitleSelectionSurvivesFocusAndDisableRemainsAnAction`。大字体另包含 `openBottomBarKeepsLabelsAndFocusOnBrightAndDarkFrames`。

没有执行完整新功能在旧字幕实现上的行为红绿对照。首轮原生运行发现平台 marquee 首次立即滚动，已显式加入 1000ms 等待及取消逻辑；侧栏遍历补齐动画完成条件。扩展测试曾误用共享时间面板返回的枚举判断字幕时间页，现验证真实“字幕时间偏移”标题及焦点。RTL 原生截图实际在滚动，旧测试直接画 TextView 得到同样的空图；记录确认其 `scrollX=1048214`，修正 Canvas 的滚动坐标后，原有像素变化断言通过，未修改或放宽 RTL 行为。中途 ktlint 为 **BUILD FAILED**，定域格式化后通过；滚动条属性直接写颜色导致 AAPT **BUILD FAILED**，改为 drawable 引用后上述最终构建通过。

### 原生视觉与验收边界

- 已实际查看[设计/原生同尺寸对照](../../../document/prototypes/tv-ui/previews/player-subtitle-quick-native-2026-11-03/comparison.png)、[原生状态汇总](../../../document/prototypes/tv-ui/previews/player-subtitle-quick-native-2026-11-03/states.png)、1.3 字体全屏、空列表、真实 SRT 选中和右侧三个详细入口。最后看图补正滚动条与选项间距，并隐藏短列表不必要的滚动轨道；修正后再次查看空列表、对照图和大字体全屏。
- 同画面、1440×810 / 240dpi，1.0/1.3 字体；菜单 x=900–1380、底边 y=607、列表高度 300px 与设计对应。原生行高分别为 72/93px，原型为 72/93.6px。Android 背景描边不占布局空间，列表左边比 CSS 边框盒靠左 1px、选项宽 438px 对原型 436px；平台标题字体测量使面板顶部为 y=228/219，对原型 231/222，差 3px。已实际量测与看图，未归因于缩放。
- 长名对照使用相同九条名称、生效项、焦点与列表底部位置，局部按原像素裁切。字体字形、基线和省略点存在平台差异；原有字幕渲染位置/尺寸/透明度、SeekBar 百分比量化和首集上一集禁用保持现状，完整画面不冒充它们与中间集网页完全一致。
- 多轨/多语言为受控仪器夹具，只替换 quick 面板和底栏读取的数据；结束恢复原 wrapper，生产代码不包含演示轨道。真实 Media3/mpv 验证另用实际外挂 SRT，不把模拟多轨截图当作真实多内封字幕媒体集成结果。文件选择验证覆盖进入与逐层返回，不将程序添加 SRT 等同于遥控器选文件全过程。
- [verification.json](../../../document/prototypes/tv-ui/previews/player-subtitle-quick-native-2026-11-03/verification.json)保存原生图像哈希、16 份布局记录及当前源码哈希；HTML 的 40 项单行观察单列于设计记录，不替代原生结果。
- 本次仅使用任务模拟器 `emulator-5572` / 专用 adb 5055 安装 Debug 包并测试；结束后模拟器已关闭、该 adb 设备列表为空。**未操作或安装到实体电视，用户最终验收待进行**。较早批次的用户验收不包含本次字幕调整。

## Release 4.2.6 打包（2026-11-03）

用户要求版本号加一并打包 Release。`Versions.kt` 从 `4.2.5 / 72` 更新为 **`4.2.6 / 73`**；包含当前底栏、弹幕与字幕菜单改动，没有新增功能修改。打包阶段未操作实体电视，随后另行授权的安装见下节。

首轮构建在 Gradle 启动时遇到 `Unable to establish loopback connection`；daemon 堆栈指向 Java Unix-domain socket 的 `Invalid argument: connect`。沿用前一轮有效配置，在该构建进程的 `JAVA_TOOL_OPTIONS` 中指定 `-Djdk.net.unixdomain.tmpdir=E:\Project\DDPlayTV\.tmp\wh-jvm -Djava.io.tmpdir=E:\Project\DDPlayTV\.tmp\wh-jvm` 后，执行 `scripts/dev-env/gradlew-local.bat :app:assembleRelease --no-daemon --console=plain --stacktrace`，日志尾部确认为 **BUILD SUCCESSFUL in 4m 6s**（689 tasks）。没有跳过 Release vital lint，也未改动全局环境或默认包装脚本。两轮日志保存在 `.tmp/release-4.2.6/assemble-release.log` 和 `assemble-release-retry.log`。

三个 APK 均使用 `aapt dump badging` 核对实际 Manifest：包名 `com.okamihoro.ddplaytv`、版本 `4.2.6 / 73`、非 debuggable，原生 ABI 与各包类型一致；`apksigner verify --verbose --print-certs` 和 `zipalign -c 4` 均通过。项目缺少专用 Release 凭证，按既有规则回退到项目调试签名；三个包的证书 SHA-256 均为 `e1b82b2732cb93db0c809d21e2f0c90ef3bdd4958c2fc89ef217c082a070cdc6`，与实际核验的上一版 `4.2.5` ARM64 Release 一致。原有单元、lint 与 DPAD/视觉结果见上节，本次版本递增未重跑仪器测试。

产物目录为 `app/build/outputs/apk/release/`；完整校验记录位于 `.tmp/release-4.2.6/release-verification.json`。

| APK | 字节数 | SHA-256 |
| --- | --- | --- |
| `ddplaytv_v4.2.6_arm64-v8a-release.apk` | 68344195 | `1638879a04bb11e0b13759b5877f9bfccd9e6a53323e707b2504b65d28d07aee` |
| `ddplaytv_v4.2.6_armeabi-v7a-release.apk` | 64580533 | `c3d8c28194101515dd4f323dedf33b114a10229616d22de158ab74d2dc9ab82a` |
| `ddplaytv_v4.2.6_universal-release.apk` | 102414037 | `11fd0148a4196c7e3ee3d96c4bb1166bdaac024bf540ef09e019b8905cfbca7f` |

## Release 4.2.6 电视安装（2026-11-03）

用户追加要求“连接 tv 并安装”。使用本任务独立 ADB 端口 **5091** 连接 `192.168.31.70:5555`，确认设备处于 `device` 状态，为 **Hisense / VIDAA_TV**、Android 11 / API 30，支持 `arm64-v8a`。安装前复核本地 ARM64 APK 的 SHA-256 与上一节一致，以 `adb install -r` 覆盖更新，返回 **Success**。

| 电视读回项目 | 安装前 | 安装后 |
| --- | --- | --- |
| 版本 | 4.2.5 / 72 | **4.2.6 / 73** |
| 最近更新时间 | 2026-11-02 21:49:17 | **2026-11-03 21:28:53** |
| UID | 10111 | 10111 |
| 首次安装时间 | 2026-02-12 10:47:55 | 相同 |
| 主 ABI | arm64-v8a | arm64-v8a |

电视安装后 `base.apk` 的 SHA-256 为 `1638879a04bb11e0b13759b5877f9bfccd9e6a53323e707b2504b65d28d07aee`，与本地 ARM64 Release 完全一致；包名为 `com.okamihoro.ddplaytv`，没有 DEBUGGABLE 标志。覆盖安装保留现有应用数据，未卸载或清除数据；安装验证没有启动应用、导航、播放或截图，不能代替实体电视 UI/运行验收。

原始连接、安装、前后包信息、安装包哈希及结构化核验结果保存在 `.tmp/release-4.2.6/tv-*`，主要记录为 `tv-install-verification.json`。已断开电视并关闭本任务私有 ADB 5091，确认该端口不再监听；没有重启或关闭默认 ADB 服务。此节替代此前 4.2.5 为最新安装版本，较早实施阶段“未安装电视”的说明保留为当时验证边界。
