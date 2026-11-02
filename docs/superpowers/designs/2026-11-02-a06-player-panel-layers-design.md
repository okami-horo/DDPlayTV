# A06：播放器侧栏与背景层次调整设计

日期：2026-11-02。核对基线：`c047019c3`。状态：**用户已回复“确认”，按此方案实施；当前实施与原生验证状态见 [执行账本](../plans/2026-11-02-a06-player-panel-layers.md)。**

用户要求先给出任务设计及调整前后 UI 对比，确认后再实施。设计阶段按 Superpowers 流程核对现状、比较取舍并制作局部材质稿；随后用户明确确认。本文件保留设计阶段依据，运行结果由执行账本单独记录。

## 建议结果

保留播放器 A 的布局、当前侧栏宽度及已有 A02 焦点表达，用“视频背景 → 右侧渐暗过渡 → 稳定深色面板 → 操作内容”建立层次。面板后方的时间、进度条和操作入口不再透进面板；左半画面保持当前亮度，字幕和弹幕的位置保持现有规则。

本轮目标是让操作层从视频里清楚分离，同时继续看到播放上下文。推荐稳定的深色渐变面板配合局部压暗。更高透明度会让色块、字幕和底层控件继续干扰阅读；实时模糊还需要双内核、视频承载方式和不同电视平台的独立可行性验证，暂不纳入此方案。

## 对比参考与证据边界

- [交互对比稿](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html)：可切换选集、字幕／音轨／弹幕轨、字幕／弹幕样式、播放设置、时间偏移、倍速、比例和弹幕搜索，另有播放画面／高亮色块／暗场、适用内容状态、1.0／1.3 字体及前后并列／单图。
- [选集前后对比](../../../document/prototypes/tv-ui/previews/player-panel-layers/compare-episodes-cinema-normal-font-1.png)、[字幕轨高亮背景对比](../../../document/prototypes/tv-ui/previews/player-panel-layers/compare-tracks-bright-normal-font-1.png)、[字幕样式对比](../../../document/prototypes/tv-ui/previews/player-panel-layers/compare-style-cinema-normal-font-1.png)、[播放设置对比](../../../document/prototypes/tv-ui/previews/player-panel-layers/compare-settings-cinema-normal-font-1.png)。左为当前资源重建，右为建议调整。
- 原生依据来自 A02 的 [N23 选集](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/images/N23.png)、[N18 字幕轨](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/images/N18.png)、[N26 字幕样式](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/images/N26.png)。这些是既有任务模拟器截图，本轮没有抓取新原生或实体电视截图。

**对比两侧都是设计重建，不是改前／改后 APK 的运行截图，也不构成逐像素复刻。** 两侧使用相同内容、背景、焦点位置和面板几何；列表文字、图标及控件细节为近似重绘，最终以现有原生控件为准。背景从历史播放截图的画面区域裁切，不携带旧返回按钮；示例视频与轨道名称不会写入应用。

画布为 1440 × 810，对应 A02 截图的 240dpi，即每 dp 为 1.5 画布像素。因此面板宽 396px、距右／上各 22.5px、高 765px、内边距 27px、外圆角 30px。原生继续采用当前 264dp 宽、15dp 外边距、18dp 内边距、20dp 圆角；不能直接把网页像素当作 dp。

## 已核对的现状

| 依据 | 确认结果及设计含义 |
| --- | --- |
| `player_component/src/main/res/drawable/cinema_player_panel.xml` | `#F51C1C22`，约 96.1% 不透明，20dp 圆角、1dp／约 15% 白色边线；极亮背景与底层控件仍会隐约透出 |
| `layout_player_setting.xml`、`layout_setting_tracks.xml`、`layout_switch_video_source.xml` | 主要右侧栏均为 264dp 宽、15dp 外边距、18dp 内边距；标题、正文、底部操作已有分工，可保留 |
| `item_player_setting.xml`、`PlayerSettingView.kt`、`SettingAction.kt` | 设置行复用 `cinema_button`；已有“视频／弹幕／字幕／其它”分组。材质稿保留分组和列表滚动，不新增业务项目 |
| A02 当前资源及 N18／N23 | 焦点填充、单层边缘与独立勾选已实施；普通选集行已较轻，不应为了 A06 再加一层底板或重做 A02 |
| `SettingController.kt` | 现有栈维护面板切换及 BACK；首次显示／最后关闭回调不能完整表示子面板之间的切换 |
| `VideoController.kt` | 已保存并恢复呼出入口焦点，面板期间停止控制条自动淡出；当前顶部／底部控件留在面板后方 |
| `BaseSettingView.kt`、`SwitchSourceView.kt`、`KeywordBlockView.kt` | 同一个面板 drawable 被 11 个布局引用；左侧来源选择与按 CENTER 动画处理的关键词视图也在其中。不能全量替换共用 drawable 或对全部设置栈盲目套用右侧遮罩 |

本次定向覆盖 **9 个右侧布局模板**，轨道模板同时承载字幕、音轨、视频轨与弹幕轨；时间偏移模板承载字幕／弹幕偏移。轨道是否可见及多视频轨入口继续由当前内核／数据条件决定。左侧本地文件选择、关键词视图和其它弹窗保持原有独立处理，不作为本次结构改造对象。

## 调整规则

| 层 | 当前表现 | 建议调整 | 保留项 |
| --- | --- | --- | --- |
| 视频与后方控制层 | 右侧面板直接叠在画面上 | 在右侧叠一层黑色渐变；左 50% 为透明，70% 位置约 26% 黑色，最右约 44%。实际参数需原生截图核对 | 左半画面亮度、视频比例、字幕／弹幕位置和播放状态 |
| 面板主体 | 96.1% 不透明的单色背景 | 全不透明的冷灰渐变，候选 `#27272F → #1B1B22`，消除后方控件透影 | 面板尺寸、布局、圆角、标题字号及内边距 |
| 面板边缘 | 整圈约 15% 白色边线 | 边线减为约 10% 白色；外侧以柔和静态阴影分离。标题区加入极轻的组内分界，使用现有空间 | 边缘强调弱于 A02 的操作焦点，不加第二圈焦点 |
| 设置普通行 | 每行独立深色底板 | 仅在播放器设置列表内减轻普通行底色，以现有分组／留白及必要的细分界组织；真正的操作按钮仍保留按钮底 | 普通行尺寸、图标、排序、实际功能及滚动行为 |
| 焦点、选中、禁用 | A02 填充、边缘和勾选 | 沿用现有 A02 状态值；普通行的材质调整不能覆盖这些状态 | 字幕勾选在焦点离开后仍可见，禁用控件跳过焦点 |

阴影只是辅助，最终效果必须在阴影不可用时仍靠面板稳定底色和背景抑制成立。优先使用现有 View 叠层与静态 drawable，不采集视频帧，不添加实时模糊或新的材质框架。

只对当前顶部为右侧面板时启用这一层右侧压暗。从右侧进入现有左侧选择器／关键词等其它形态时应切换为对应旧规则；BACK 回到右侧时恢复。多层栈不叠加多个遮罩，快速切换不闪回亮背景，最后关闭后完全清除，并恢复原入口焦点。遮罩不参与焦点、点击或无障碍导航。

## 页面／状态覆盖矩阵

下表保留确认前的设计覆盖要求，“原生待验”是设计阶段状态；各项当前证据统一见 [执行账本的 M01–M11](../plans/2026-11-02-a06-player-panel-layers.md)。网页状态只用于观察材质关系；没有给原本没有加载／错误状态的面板新增这些状态。搜索失败沿用现有状态文字、搜索和本地文件操作，轨道空态沿用现有添加入口。

| 编号 | 当前布局／数据场景 | 设计入口 | 必须核对的状态与交互 | 本轮结果 |
| --- | --- | --- | --- | --- |
| M01 | `layout_switch_video_source.xml`，选集 | [`panel=episodes`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=episodes) | 当前集与焦点重合／分离、长名称、列表滚动、切集后恢复 | 材质稿已渲染；原生待验 |
| M02 | `layout_setting_tracks.xml`，字幕／音轨／弹幕／条件视频轨 | [`panel=tracks`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=tracks)、`audio`、`danmaku` | 空轨、非空轨、勾选失焦、长名称、显示开关、添加／样式入口、多轨条件 | 字幕／音轨／弹幕材质稿已渲染；条件视频轨复用同一壳，真实条件与原生待验 |
| M03 | `layout_setting_subtitle_style.xml` | [`panel=style`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=style) | 字幕样式滑杆焦点、数值可读、1.3 字体、按现有规则即时预览 | 材质稿已渲染；原生待验 |
| M04 | `layout_setting_danmu_style.xml` | [`panel=danmaku-style`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=danmaku-style) | 滑杆／选项、长面板滚动、弹幕预览和恢复 | 材质稿已渲染；原生待验 |
| M05 | `layout_player_setting.xml` | [`panel=settings`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=settings) | 原有分组、普通行／焦点行／选中行、滚动到其它组、内核条件入口 | 材质稿已渲染；原生待验 |
| M06 | `layout_setting_offset_time.xml`，字幕／弹幕偏移 | [`panel=offset`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=offset) | 双列按键 DPAD、正负值、重置、即时生效和持久化 | 材质稿已渲染；原生待验 |
| M07 | `layout_setting_video_speed.xml` | [`panel=speed`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=speed) | 沿用真实滑杆，不改成倍率列表；按当前规则显示重置入口 | 材质稿已渲染；原生待验 |
| M08 | `layout_setting_video_aspect.xml` | [`panel=aspect`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=aspect) | 保留列表结构，不新加原布局没有的标题；默认／其它比例选中态与返回 | 材质稿已渲染；原生待验 |
| M09 | `layout_search_danmu.xml` | [`panel=search`](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=search) | 输入与搜索同一行、加载／空／失败／长结果、选择本地文件及子页面返回 | 各适用材质状态已渲染；IME、真实服务与原生待验 |
| M10 | 面板栈与退出 | 所有上述右侧入口；与现有左侧／关键词形态交替 | 原入口恢复、逐层 BACK、连续开关、只有一个有效遮罩、关栈后清除 | 只有交互规格；原生待验 |
| M11 | 背景／字体交叉覆盖 | `scene=cinema/bright/dark`、`font=1/1.3` | 高亮色块下不透出后方文字；暗场仍能分离；放大字体能滚动且操作可达 | 11 个展示场景 × 3 类背景已检查几何／内容一致；字体示意已渲染；动态画面与原生待验 |

## 确认后的任务安排

以下为用户确认的任务顺序，执行状态与结果由上述账本维护。

1. **T1：限定右侧资源。** 新建右侧面板专用背景并只映射上述 9 个模板；播放器设置行的普通材质采用局部资源，A02 焦点／选中分支保留。验证：资源使用点与 11 个旧引用逐一比对，左侧／其它形态及全局按钮样式不受影响。
2. **T2：接入单一背景层。** 在 `VideoController` 的后方控制层与设置层之间管理一个不聚焦的遮罩；由 `SettingController` 在顶部面板变化时报告当前形态，沿用现有开栈、返回和焦点恢复规则。验证：右侧→子面板→其它形态→逐层返回、连续开关和播放器销毁后均不残留或叠黑。
3. **T3：原生视觉与 DPAD 验收。** 任务自有 AVD 上覆盖 M01–M11，用相同背景、内容、焦点／滚动位置、逻辑视口对照设计；普通／1.3 字体、高亮／暗场、Media3／mpv 分别记录。验证：九个模板都有原生证据，焦点可见／可达／可返回；样式、倍速与偏移仍按原规则预览／持久化；实际播放烟测与设置返回路径可完成。
4. **T4：工程检查与证据收口。** 按改动范围构建 Debug／测试 APK，执行 `testDebugUnitTest`、`lint`、`verifyModuleDependencies` 及限定格式检查；必要的状态回归针对遮罩栈切换与入口焦点恢复，不写只复述 drawable 的测试。验证：查看每次 Gradle 输出尾部并记录 `BUILD SUCCESSFUL`／`BUILD FAILED`；分别列出构建、测试、截图比对、DPAD 和仍待用户验收的部分。

范围限定为播放器右侧操作层。A01 字号重整、跨页面 A03 容器改造、首页／详情／文件页结构及其它审查项继续按各自授权处理。原生实施确认后也不自动扩展为 Release、电视安装、实体电视操作、版本修改、暂存或提交。

## 确认前的设计验证

- 本地 Chromium 渲染并实际查看对比图；核对同一对比两侧的面板几何与内容一致。
- 页面／背景组合共检查 33 组，另生成 11 张对比图和 8 张单屏图，脚本错误为 0；检查了参数切换、URL 刷新恢复、760px 视口、不适用状态禁用以及原生依据文件存在。
- 放大字体、空轨道、搜索加载／错误、暗场及高亮背景保留独立对比图。布局细节仍属设计模拟，不能据此判定真实控件排版、IME、DPAD 或性能通过。
- 最初网页检查误用自动化工具对 `<option>` 的禁用判定；DOM 的 `disabled` 已正确设置，改为读取该属性后通过，没有据此修改应用。
- 图像与 HTML 身份、历史背景的裁切坐标和来源哈希见 [设计验证记录](../../../document/prototypes/tv-ui/previews/player-panel-layers/verification.json)。确认前阶段未修改 Android 源码，未执行 Gradle、AVD 或电视操作；确认后的工作另见执行账本。

用户已确认采用稳定深色面板、右侧渐暗与普通设置行减轻底色的组合。原交互稿和设计截图继续作为设计参考，不能代替确认后的原生截图与运行验证。
