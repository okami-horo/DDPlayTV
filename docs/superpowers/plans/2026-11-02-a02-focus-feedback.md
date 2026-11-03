# A02：焦点反馈统一的界定、设计与实施任务

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-11-02。源码基线：`c69409f33`。状态：**推荐方案已实现并完成本地验证；2026-11-03 用户确认实机验收通过，已归档。**

用户先要求：“A02 你准备如何修复……完成界定与任务设计，带我确认后进行实现。”随后明确确认：“可以，按照此方案进行实现。”授权范围为下述 A02 推荐方案及必要验证；其他暂缓项不因此恢复。

## 目标与依据

让遥控器所在位置清楚、各页强调强度一致，同时区分“当前焦点”“已经选中”“不可用”。本轮只处理 [Apple 风格审查 A02](../../../document/architecture/apple-ui-audit-2026-11-02.md#a02统一焦点的视觉语言)，不连带实施 A01、A03–A07。

设计时的改前源码证据（保留问题基线；当前实现见下方记录）：

- [cinema_primary.xml](../../../core_ui_component/src/main/res/drawable/cinema_primary.xml)：焦点时外框与内侧浅色填充间隔 5dp，形成双轮廓。
- [cinema_button.xml](../../../core_ui_component/src/main/res/drawable/cinema_button.xml)：普通按钮焦点 2dp；[播放器列表](../../../player_component/src/main/res/drawable/background_player_setting_text.xml) 为 3dp，选中但未聚焦时还有 1dp 白边。
- [cinema_navigation.xml](../../../core_ui_component/src/main/res/drawable/cinema_navigation.xml)：focused 与 selected 都用浅色底；通用分组页签 [cinema_tab.xml](../../../core_ui_component/src/main/res/drawable/cinema_tab.xml) 的 focused＋selected 又使用 3dp 灰边，由 `item_tv_category.xml`／`TvCategoryPages.kt` 使用。实际详情季按钮 [item_metadata_season.xml](../../../local_component/src/main/res/layout/item_metadata_season.xml) 走的是 `cinema_category`；实施时需把季按钮背景和文字一起接入更新后的页签状态，不能只改一个未接线的资源便宣称覆盖。
- [cinema_play_circle.xml](../../../core_ui_component/src/main/res/drawable/cinema_play_circle.xml)：主播放键的焦点是 3dp 灰色内边。[次级播放键](../../../player_component/src/main/res/drawable/cinema_transport_button.xml) 已是适合紧凑尺寸的单层 1.5dp；[输入框](../../../core_ui_component/src/main/res/drawable/cinema_input.xml)、[文件行](../../../storage_component/src/main/res/drawable/file_browser_row.xml) 已有单层 2dp 焦点，不把所有框都当成重复绘制。
- [TvMainActivity](../../../app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvMainActivity.kt) 点击导航才更新 selectedSection；[SettingTracksView](../../../player_component/src/main/java/com/xyoye/player/controller/setting/SettingTracksView.kt) 的 selected 来自实际轨道，点击才切换。焦点和选中必须继续独立，不能用视觉整改改变这些行为。

Apple 对 [tvOS 可聚焦元素](https://developer.apple.com/documentation/uikit/adding-user-focusable-elements-to-a-tvos-app) 的说明区分焦点与确认，并要求焦点元素明显可辨；[设计原则](https://developer.apple.com/design/human-interface-guidelines/design-principles) 强调一致反馈。本提案借鉴这些原则，具体 2dp、颜色与静态反馈是 DDPlayTV 的适配选择，不是 Apple 规定的 Android 参数。

## 已确认的方案

推荐 **“连续主按钮＋单层细边＋独立选中标记”**。固定页面结构、尺寸、可点击区域、文字大小、既有焦点留白和导航行为，只统一状态表现。

对照稿：[焦点反馈原型](../../../document/prototypes/tv-ui/focus-feedback-prototype.html)。`mode=current` 为当前资源样式复刻；`mode=proposed` 为建议。两者均是设计稿，不是原生改后截图。原型包含八类控件、五列状态及方向键预览；无业务意义的 selected 组合显示“—”。按 1dp≈1.5px 表达，不直接把网页 px 当作 Android dp。

| 控件 | 建议效果 | 实施约束 |
| --- | --- | --- |
| 首页／详情主按钮 | 常态保留原浅色内块；聚焦时把原 5dp 间隔填满，成为一个连续浅色圆角实体，取消独立外框 | 在现有 View 和焦点外边界内绘制；内容位置、点击区和布局尺寸不变。圆角沿用外 14dp／内 9dp 的同心关系 |
| 普通按钮、深层操作行 | 聚焦时适度提亮，只有一层 2dp 浅色边 | 先使用现有 `tv_focus_stroke_width`；新增颜色只服务这组状态，不改整个主题。普通底色按控件原语义保留 |
| 顶部导航、通用分组／季页签 | 焦点为浅色填充＋深色字；已选但失焦为深色底＋短下划标记；焦点与选中重合时保留深色短标记 | 只消费已有 selected 状态；不改点击切页，不让焦点移动触发业务选择。季按钮使用统一页签背景和文字状态，标记在原尺寸内绘制；版本选择是现有 BottomActionDialog 列表，按列表验收，不新增版本页签 |
| 设置分类 | 焦点用统一细边／提亮，当前分类保持已有稳定填充 | 分类与焦点独立；不新增强制确认或菜单层 |
| 音轨、字幕轨、选集 | 焦点从 3dp 统一为 2dp；selected 失焦用原勾选和稳定底色表达，取消其额外白边；focused＋selected 同时保留焦点和勾选 | 原生普通选集行已有透明版本，继续保留透明普通行；不把原型密集边框重新加回。不同用途的既有圆角不强行改成一个值 |
| 主播放键 | 保留白色主键与图标，焦点灰色内边由 3dp 改为单层 2dp | 播放 A 的单排分组、尺寸、位置和可达性沿用 |
| 次级播放键、输入框、文件行、作品卡片 | 紧凑次级播放键沿用现有单层 1.5dp；输入框、文件行、作品卡片沿用单层 2dp。只纠正确认发现的额外轮廓／状态冲突 | 已符合规则的资源仅回归验证；保留卡片圆角和裁剪修复，不为“统一”重做结构 |

建议颜色候选：普通深色控件焦点填充 `#383842`、细边 `#E3E3EB`；浅色导航焦点延用 `#F5F5F7` 与深字 `#111114`。实际验证比较相同数据和视口；若仅需常规颜色微调，在已确认方向内迭代，并记录差异。

备选是“所有操作控件聚焦时统一白底黑字”。这种方案更醒目，但现有播放器操作行的子文字和图标是固定浅色，需要同步修改更多状态传播与颜色；大面积焦点行也更抢视频注意力。本轮推荐保留深色播放行的单层细边方案。未制作、未选择备选的完整设计。

## 状态契约

| 状态 | 必须满足 |
| --- | --- |
| 普通 | 控件可识别，边界强度低于焦点 |
| focused | 只有当前可操作项获得焦点强调；方向移动后及时更新 |
| selected | 表示实际页面、季／版本、轨道或当前集，与焦点移动无关；沿用真实勾选／标记 |
| focused＋selected | 焦点仍明显，实际选中标记仍在；不再叠出第二圈 |
| pressed＋focused | 按下有短暂明暗反馈，焦点边缘仍在；松开后恢复当前组合，不改变尺寸 |
| disabled | 低亮且不可操作；沿用已实现的焦点跳过和禁用占位，不重复叠加透明度 |

资源匹配要覆盖 disabled、pressed＋focused、focused＋selected、focused、selected、pressed 和普通的有效组合，避免 selector 提前命中吞掉组合状态。原型的状态矩阵是静态语义样例；真正按键和 Android 状态匹配在原生阶段验证。

**“禁用”文字不是 `isEnabled=false`。** 轨道列表中的“禁用”是关闭该类轨道的可选动作，必须继续可聚焦、可确认；只有真正不可用的控件（例如首集的上一集）使用 disabled 样式并跳过。

第一轮采用静态反馈，不新增缩放、位移、实时模糊、阴影和全局动画控制器。原控件已有的可用动画与无障碍行为沿用。

## 范围界线

- 保留 Cinema 首页、详情 B、文件 C、播放 A、遥控器 BACK、默认焦点／焦点恢复、字幕位置及弹幕覆盖的既定决定。
- 不处理字号、面板材质、表单分组、作品重复标题、关于页标识；这些仍是 A01／A03–A07。
- 优先改现有 drawable／ColorStateList。只有为选中标记或禁用／焦点颜色确有需要才调整绑定；不新建焦点管理器，不重构业务，不恢复已撤下入口。
- 网页旧返回／关闭按钮不作为本轮整改依据。本原型单独存在，在确认前不替换现有 Cinema／A／B／C 权威稿。
- 实施与验证使用任务模拟器；当前没有安装、导航或占用实体电视的授权。本轮不提交、不发 PR、不打 Release、不调整版本。

## 页面／状态覆盖矩阵

“回归”表示沿用已有正确资源。以下记录本轮实际覆盖的代表状态；服务账号、完整 IME 和实体电视边界另列，不以一个页面样例代替所有条件分支。原生图片编号来自 [39 张截图图库](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/index.html)。

| ID | 页面／入口与状态 | 设计／实际资源 | 原生与 DPAD 结果 | 截图 |
| --- | --- | --- | --- | --- |
| F01 | 首页、详情 B 主／次操作；读取中、空态、文件缺失与失败恢复 | 主按钮／普通按钮；`cinema_primary`、`cinema_button` | 本地通过：连续填充和按下保边的绘制断言；主操作可达，缺失文件移到可用项，失败后恢复触发项；布局尺寸保持 | N01–N04、N34 |
| F02 | 导航 focused、selected、组合与确认 | 导航五态；`cinema_navigation`、两组颜色状态 | 本地通过：首页已选而媒体库聚焦的真实场景；确认才切页；20 次快速左右往返、向下进入内容和 BACK 恢复；标记与文字对比度检查 | N05–N06 |
| F03 | 分组／季页签的焦点与选中分离，版本弹窗 | 页签五态；`cinema_tab`／`cinema_tab_text` 接入真实 `item_metadata_season` | 本地通过：选中第二季而聚焦第一季、确认切季；版本取消／重建恢复；真实季布局两态的 baseline／padding 不跳动 | N07–N09、N35 |
| F04 | 设置分类、设置行及持久化 | 分类／普通行；`cinema_category`、`cinema_button` | 本地通过：播放／字幕分类和内容间导航、BACK；设置经确认、重建、重新进入仍保留 | N10–N11 |
| F05 | 媒体库及首页作品卡的焦点、圆角／进度 | 原作品卡资源，未改动 | 本地通过：媒体库可见列往返、首页卡片导航和边界／标题度量；卡片资源保留。完整多行／大数据分页不是本轮新增视觉覆盖 | N12 |
| F06 | 文件 C、长名称／路径、工具、空态／错误恢复 | 原文件行及输入框，未改动 | 本地通过：路径阅读滚动、BACK 恢复原文件、搜索／排序／工具返回；加载、空态、失败和刷新恢复 | N13–N14；空态见 N33 |
| F07 | 来源表单输入、普通操作和连接错误 | 输入框回归；普通操作使用 `cinema_button` | 本地通过：FTP／WebDAV／SMB 表单导航与滚动、测试动作及错误焦点、BACK；未用真实账号执行成功保存／认证 | N15–N16、N36 |
| F08 | 轨道 selected A／focused B、组合、关闭轨道与空态 | 列表五态；`background_player_setting_text`、`cinema_track_check` | 本地通过：实际字幕轨焦点移动不切换选择，确认“禁用”关闭轨道，勾选有绘制空间；音轨／弹幕面板和空态回归。非空弹幕／多音轨的全部组合未逐项新增夹具 | N17–N22、N37 |
| F09 | 选集当前项、其他焦点项、长名称与切换 | 列表五态；`background_player_setting_text_transparent` | 本地通过：透明普通行、单层边；选集／上一集切换、首末集边界和 BACK 恢复选集入口 | N23–N24 |
| F10 | 时间偏移、字幕／弹幕样式 | 普通按钮；现有滑杆资源回归 | 本地通过：+0.5 秒生效，重置后按钮禁用且焦点回 +0.5；滑杆 DPAD 修改写入配置，再开面板保留进度；各层 BACK 恢复。测试 finally 还原设置 | N25–N27、N38 |
| F11 | 播放 A 主／次键、右侧入口、首末集与单文件 | 主键 `cinema_play_circle` 为 2dp；次键保留 1.5dp | 本地通过：禁用上一／下一集跳过且占位保留；实际 Media3／mpv 播放与返回；真实首页按历史位置续播 | N28–N30、N39 |
| F12 | 播放器文件选择、搜索／关键词与长内容 | 普通行／输入框；原路径／选择资源回归 | 本地通过：本地选择、首行空间、关键词增删／搜索和空态返回。软键盘真实弹出／关闭后的完整组合未在本轮单独重验 | N31–N33 |

## 实施任务与依赖

| 任务 | 产出与可能文件 | 依赖 | 验证与完成条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| D0 设计确认 | 用户确认本页推荐规则与原型；记录需调整项和选择 | 无 | 具体方案已可看、可比较；用户明确确认 | 已完成：用户确认推荐方案 |
| T1 共用状态资源与季按钮接线 | `core_ui_component` 的 `cinema_primary/button/navigation/tab/category`、对应颜色与主播放键；复用现有 2dp token；`local_component` 的季按钮接入页签背景和文字状态 | D0 | 组合状态符合契约；尺寸和内容位置不变；标记在边界内；季页签实际接线 | 已完成；见 F01–F04、F11 |
| T2 播放器与实际调用点 | `background_player_setting_text*.xml`、`cinema_transport_button.xml`、`cinema_track_check.xml` | T1 | 实色／透明版本焦点一致，保留普通透明行、旧圆角和真实勾选；不改轨道选择逻辑 | 已完成；另修复勾选初始绘制空间，见 F08–F11 |
| T3 有效状态与遥控器验证 | 新增 `FocusFeedbackInstrumentedTest.kt` 五项，补充真实季页签截图及播放器样式／重置验证 | T1、T2 | 焦点与选择独立、按下保边、禁用跳过、快速往返、实际轨道行为和布局度量 | 已完成；原生验证记录按用例列明 |
| T4 构建和回归 | Debug／测试 APK、全量 JVM／lint、依赖治理、相关格式检查；任务 AVD 普通／1.3 字体回归 | T3 | 构建尾部确认 BUILD SUCCESSFUL；28 个不同普通字体、10 个不同大字体原生用例通过，含双内核冒烟 | 已完成本地验证；保留早期失败证据与矩阵边界 |
| T5 原生看图与验收记录 | 已确认原型不变；39 张原生图库、总览、来源／哈希和本矩阵 | T4 | 查看主按钮、导航／季页签、轨道及播放键，核对 1.3 字体；图库筛选和原图链接通过 | 本地已完成；2026-11-03 用户确认实机验收通过 |

已执行的完整本地门禁（复用现有 JDK／短临时目录及离线依赖入口）：

```powershell
& .tmp/watch-history/gradle.ps1 :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies :app:ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/a02-focus/scoped-format.gradle --offline --console=plain
```

AVD 仪器验证复用实际检查过的 `CinemaPrototypeInstrumentedTest`、`WatchHistoryInstrumentedTest`、`SharedCardRenderingInstrumentedTest`、`FileBrowserPreviewInstrumentedTest`、`UiPolishInstrumentedTest` 和 `GlobalSettingsInstrumentedTest` 中与矩阵对应的用例，以及新增状态契约用例。先核对设备与测试方法是否仍有效，使用任务专属设备；不把整个历史类的所有用例都当成必须重复的集合。若出现不相关既有失败，保留并区分其范围。

## 设计阶段验证

本节保留用户确认前的设计资产验证过程；原生结果另记于下一节。

- 设计阶段新增独立原型和本任务文件，当时未修改产品源码。
- 已渲染并实际查看 [当前样式](../../../document/prototypes/tv-ui/previews/focus-feedback/current.png)、[建议状态表](../../../document/prototypes/tv-ui/previews/focus-feedback/proposed.png)、[局部对照](../../../document/prototypes/tv-ui/previews/focus-feedback/comparison.jpg) 及交互预览截图。八类控件／五列语义的有效样例和说明均在 1440×810 逻辑画布内，无文字溢出；两版的控件尺寸一致。
- `.tmp/apple-ui-audit/verify-focus-design.cjs` 已检查焦点移动不改变页面／轨道选择、Enter 才确认、不可用下一集跳过、关闭字幕动作仍可选、Esc 恢复焦点及样式切换；页面脚本错误为 0。[检查记录](../../../document/prototypes/tv-ui/previews/focus-feedback/inspection.json) 保存原型 SHA-256、视口、观察项和验证边界。它是网页检查，不是 Android／DPAD 通过记录。
- 用户随后确认推荐方向与范围，按 T1–T5 实施；以下为实际结果。

## 原生实现与验证（2026-11-02）

### 实现及视觉对照

- 主按钮聚焦时填满原边界，取消 5dp 间隙和外框；普通态内缩与内容 padding 保留。普通按钮、设置分类、播放器实色／透明行使用已有 2dp token，按下仍有焦点边。
- 导航／页签以浅色填充表示焦点；已选但失焦用原深色填充及 20×2dp 短标记，距底 3dp。标记 layer-list 显式零 padding，真实季按钮的文字 baseline 和 padding 在状态变化前后相同。
- 主播放键的灰边改为 2dp；紧凑次级键保留 1.5dp。输入框、文件行、作品卡保留原资源与尺寸。
- 看原生截图时发现轨道选中勾实际没有显示：初始未选中状态为透明颜色，无 intrinsic size，TextView 未预留绘制宽高。`cinema_track_check` 增加 `constantSize=true`；新增测试先复现零空间失败，再验证真正的选中／失选绘制。未修改轨道业务选择。
- 产品变更限于三个模块的 XML 资源和季按钮接线，无产品 Kotlin 改动。保留 Cinema、详情 B、文件 C、播放 A 的结构、字号、真实数据绑定、焦点行为及已接受的字幕／弹幕位置；A01、A03–A07 未实施。
- 已实际查看确认稿、原生总览、导航／季页签、轨道勾选、播放键及六张大字体截图。确认稿是按控件说明的状态矩阵，原生是整页：统一按 240dpi（1dp=1.5px）比较焦点形状、颜色及组合语义；**首页主按钮仍为 50dp、详情仍为 47dp**，不为复刻矩阵样例高度改布局。字体与来源表单密度不在 A02；大字体下 WebDAV 的 TLS 文案仍按既有布局换行，不记作本轮排版修复。

### 构建、测试与失败记录

| 验证 | 实际结果与证据 |
| --- | --- |
| Debug／仪器 APK、全量 JVM、全仓 lint、依赖治理与三文件格式检查 | `.tmp/a02-focus/gates-verified.log` 尾部 **BUILD SUCCESSFUL in 1m 1s**。JVM XML 汇总 516 项：**514 通过、2 跳过、0 失败／错误** |
| 后续仅测试源码变更 | `.tmp/a02-focus/gates-final-tests.log`、`gates-final-sync.log` 分别 **BUILD SUCCESSFUL in 15s／13s**；最终补充重置／滑杆断言后 `gates-final-behavior.log` 为 **BUILD SUCCESSFUL in 14s**，含最新测试 APK、app lint 与三文件格式检查。产品资源未再变化，复用上一行全量结果 |
| 普通字体原生 | `regression-normal.log` 的 22 项、`final-normal.log` 的 8 项和最新 `panel-final-normal.log` 的 1 项均通过；去重 **28 个不同用例**。快速往返、BACK、设置持久化、详情失败／空／加载、Media3／mpv 和实际首页续播均有对应测试；不是声称一次运行了 28 项 |
| 1.3 倍字体原生 | `final-font130.log` 先通过 9 项；修正测试同步后 `panel-final-font130.log` 的剩余 1 项通过，合计 **10 个不同用例**。最终面板用例在两种字体下均通过，并断言偏移重置／焦点与滑杆保存／再开面板读取 |
| 早期全量 JVM 失败 | 两次初始全量构建在既有 `M115CryptoTest` 随机空 payload 出现 `Invalid payload size=16`。未改加密代码；单独该类 4 项与最终全量通过。属于已记录的随机测试风险，未据此称其修复 |
| 选中勾红／绿 | `.tmp/a02-focus/check-red-device.log` 在修复前复现绘制空间为零；`constantSize` 修复后五项新测试在两种字体下通过，N18／N37 可直接看到失焦勾选 |
| 放大字体测试同步失败 | 首轮新增弹幕样式等待在页面惰性创建前空指针；仅改 nullable 等待后，失败焦点 ID 映射为 `subtitle_vertical_offset_sb`，证明确认键抢在目标设置行获得焦点之前发出。测试改为等待目标 item 确实聚焦且列表停止滚动，再确认；产品逻辑未因此修改。原始失败日志保留于 `.tmp/a02-focus/` |

### 截图、设备与验收边界

[原生截图图库](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/index.html) 包含 **33 张普通字体＋6 张大字体原图**（均为 1440×810），覆盖 F01–F12；[总览](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/overview.jpg) 用于快速查看。画面使用任务测试夹具，不向产品写入示例内容。

[验证记录](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/verification.json) 保存原生用例、早期失败、源文件／APK／原型／图片哈希及图片来源；[图库检查](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/gallery-verification.json) 确认 39 张图均能解码、哈希与来源一致，F12 筛选 3 张、大字体筛选 6 张、41 个链接成功、原图弹出有效、脚本错误为 0。

设备为任务专用 `emulator-5584`／私有 ADB `5083`，API 34、1440×810、240dpi，运行测试前确认为 `device`；字体已恢复 1.0。结束时通过该 serial 关闭任务模拟器，确认其两个进程退出及 5083 不再监听；默认 5037 未操作。实体电视未安装、未导航、未播放，原有电视包不包含本轮 A02；电视及用户视觉验收仍待完成。真实账号／协议成功分支、非空弹幕与多音轨全组合、完整 IME 与多行大数据卡片覆盖保持显式边界，不以此次通过数推断全应用／全设备验收。

本轮不提交、不暂存、不打 Release、不改版本；原有暂存内容保留，当前实现留在工作区。
