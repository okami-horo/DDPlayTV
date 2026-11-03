# 播放器开放式底栏实施与验证

日期：2026-11-03。用户已明确确认实施[进度条在上的设计](../designs/2026-11-03-player-bottom-open-design.md)。**原生调整及本机验证已完成；实体电视与用户最终验收待进行。**本轮仅调整底栏及其焦点反馈，不授权占用实体电视测试。

后续弹幕开关跟进：用户决定底部直接切换开关，详细配置保留在右侧设置栏。[补充设计与 D01–D04 矩阵](../designs/2026-11-03-player-bottom-open-design.md#弹幕开关补充设计用户已确认)于 2026-11-03 获“确认，可以实施”，**原生与本机验证已完成，实体电视/用户最终验收待进行**。新开关证据见本文末尾；下述 B05/B06 描述追加前的行为。

本次顺序：

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
