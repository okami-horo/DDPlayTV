# 播放器开放式底栏实施与验证

日期：2026-11-03。用户已明确确认实施[进度条在上的设计](../designs/2026-11-03-player-bottom-open-design.md)。**原生调整及本机验证已完成；实体电视与用户最终验收待进行。**本轮仅调整底栏及其焦点反馈，不授权占用实体电视测试。

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
