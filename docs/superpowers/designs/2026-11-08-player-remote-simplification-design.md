# 播放底栏简化：遥控器优先

日期：2026-11-08。状态：**用户已选择 B 七项布局，并将播放键视觉状态及上一集／下一集正圆焦点纳入同一设计。Web 已更新，整稿待确认后一起实施；本任务尚未进行原生变更。**

用户认为底部快退、快进按钮与遥控器重复，要求重新设计简化，并明确要求「先出方案与 web ui 参考，我确认后才能实施」。用户随后选择 B，追加播放键视觉状态设计，并在确认前后集椭圆焦点问题后要求一同纳入本次设计。三项合并为一个交付范围；B 布局不再是未决选项，最终视觉整稿及原生实施仍待确认。

## 查看方案

- [B：已选七项布局＋播放键新稿](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?review=remote&variant=open&layout=retain&playstyle=focus&focus=subtitle)。
- [B：播放键原样式对照](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?review=remote&variant=open&layout=retain&playstyle=original&focus=subtitle)。
- [A：五项备选（未采用）](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?review=remote&variant=open&layout=compact&focus=play)。
- [当前九项底栏对照](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?review=remote&variant=open&layout=baseline&focus=play)。
- [本地三稿对比图](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/comparison.png)。

启动命令：`node document/prototypes/tv-ui/server.cjs 4189`。浏览器打开 [B 新稿本机预览](http://127.0.0.1:4189/player-bottom-refinement-prototype.html?review=remote&variant=open&layout=retain&playstyle=focus&focus=subtitle)。顶部「播放键」可切换原样式与新稿，「焦点」和「状态」可观察四种组合；A 和当前九项仅保留比较。点击画面后用方向键、Enter、Esc 操作；M 模拟 MENU。页面仅使用内存演示数据。

## 依据与判断

本次重新检查的源码：

- [`RemoteKeyDispatcher.kt`](../../../player_component/src/main/java/com/xyoye/player/remote/RemoteKeyDispatcher.kt)：控制条隐藏时，左右键跳转 **10 秒**，确认键播放/暂停，下键展开，上键打开选集；无多集队列时上键改为展开控制条。控制条打开后，左右键交给控件移焦。
- [`TvVideoController.kt`](../../../player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt)：连续跳转已有累计与 800ms 延迟提交，不需要为删按钮重写播放逻辑。
- [`PlayerBottomView.kt`](../../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt)、[`layout_player_bottom.xml`](../../../player_component/src/main/res/layout/layout_player_bottom.xml)：现有屏幕按钮为 **±15 秒**；底部有九项动作，进度条本身不接受 DPAD 焦点。恢复控制条会恢复可用的上次焦点，否则回到播放键。
- [11 月 3 日开放式设计](2026-11-03-player-bottom-open-design.md)：进度条在上、字幕中间留空、独立轨道按钮、弹幕开关与单行字幕菜单是现有约束。

本次保留遥控器的现有 10 秒行为，不把屏幕 ±15 秒按钮的数字改写成遥控器行为。底栏打开时继续左右移焦；若要跳转，返回收起后使用左右键。

## 两档方案

| 部位 | A：精简五项（未采用） | B：保留七项（用户已选） |
| --- | --- | --- |
| 左侧 | 播放/暂停、选集 | 播放/暂停、上一集、下一集、选集 |
| 右侧 | 字幕、音轨、弹幕开/关 | 同 A |
| 快退/快进 | 移除屏幕按钮，使用遥控器 | 同 A |
| 换集 | 打开既有选集列表，选择目标集 | 保留直接前后换集，也可打开选集列表 |
| 单个文件 | 收起选集，共四项 | 收起三个剧集动作，共四项 |
| 权衡 | 焦点停靠点更少；换集要先打开列表 | 更适合频繁直接换集；左侧仍有四项 |

初稿曾推荐 A 以减少焦点停靠点，用户随后明确选择 B。最终布局保留直接前后换集，移除 ±15 秒屏幕按钮；不按此前推荐继续实施 A。

播放键仍保留：底栏打开时确认键用于当前控件，保留播放键可以在菜单内直接播放/暂停，也明确显示当前动作。字幕、音轨和弹幕无需藏入更多菜单，继续一层可达。

## B 追加：播放键的状态设计（待确认）

问题是同一种白底承担了「主操作强调」和「当前焦点」两种含义：现有 [`cinema_play_circle.xml`](../../../core_ui_component/src/main/res/drawable/cinema_play_circle.xml) 的默认分支也是白底，而右侧轨道按钮在聚焦时才使用白底。焦点移到字幕后，会出现两个同样显眼的圆键。

Apple 的[焦点说明](https://developer.apple.com/documentation/uikit/adding-user-focusable-elements-to-a-tvos-app)区分聚焦与选择，要求当前焦点在视觉上与其他项目不同；[按钮指南](https://developer.apple.com/design/human-interface-guidelines/buttons)要求自定义按钮有按压反馈，并建议同组选项保持尺寸一致。这些原则支持下面的本地设计判断，但不意味着 Apple 禁止所有常态白色主按钮，也不等于要求复制 Liquid Glass。

- 播放键从 64px 收至 **58px**，与右侧圆形轨道键一致；图标从 31px 调整至 28px。上一集／下一集按下述追加设计同步统一尺寸，进度条和字幕位置保持原稿。
- 未聚焦：半透明冷灰圆底、浅色图标，细边缘与右侧轨道键一致；不使用常亮实心白底。
- 聚焦：浅底深图标、单一焦点边缘、轻微上浮 2px。删除播放键独有的深色内描边，沿用底栏共同焦点样式。
- 按压：暂时压暗并略回落，松开恢复聚焦态；移焦恢复常态。不产生持久「选中」标记。
- 暂停时显示 ▶，含义是按下继续播放；播放时显示 Ⅱ，含义是按下暂停。播放状态只改变图标/动作说明，不决定底色。
- 保留首次展开默认聚焦播放键的行为；此时白底是实际焦点。对照预览故意把焦点放在字幕，以检查播放键是否仍误显高亮。
- 以后原生接入时保留 `playIv.isSelected` 驱动播放/暂停图标的既有语义，仅让背景与 tint 根据焦点/按压变化；不能为删除「选中外观」而破坏图标状态。

| 编号 | 播放状态与焦点 | 设计表现 | 参考图片 |
| --- | --- | --- | --- |
| P01 | 已暂停、未聚焦 | 半透明圆底＋▶ | `play-focus-middle-subtitle.png` |
| P02 | 已暂停、已聚焦 | 浅底深色 ▶ | `play-focus-middle-play.png` |
| P03 | 播放中、未聚焦 | 半透明圆底＋Ⅱ | `play-focus-playing-subtitle.png` |
| P04 | 播放中、已聚焦 | 浅底深色 Ⅱ | `play-focus-playing-play.png` |
| P05 | 按下、释放、移焦、打开面板 | 按压是瞬态，移焦后不残留白底，面板打开后不误显焦点 | `play-pressed.png` 及验证 JSON |
| P06 | 明暗画面、1.3 字号 | 常态仍可辨识，焦点和标签不越界 | `b-play-bright.png`、`b-play-dark.png`、`b-play-font130.png` |
| P07 | 上一集／下一集，正常与 1.3 字号 | 两键均为 58×58 正方形边界，聚焦背景为正圆，间距和字幕避让保留 | `episode-previous-round-font1.png`、`episode-next-round-font1.png`、`episode-previous-round-font1.3.png`、`episode-next-round-font1.3.png` |

[同焦点前后对比](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/play-focus-comparison.png)与[四态对照](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/play-focus-states.png)已实际查看。P01–P06 的 Web 实现与检查已完成；原生实施、构建/设备验证、视觉及用户验收均待确认后的合并实施。

### 上一集／下一集正圆焦点：已纳入设计，Web 已修订

2026-11-08 用户要求核查上下集按钮的椭圆焦点。独立浏览器在 B 新稿分别聚焦 `previous`、`next`，实际测得两者均为 **46×54 px**、`border-radius: 50%`，变换矩阵仅上移 2px、没有非等比缩放；[上一集截图](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/episode-previous-existing.png)和[下一集截图](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/episode-next-existing.png)已实际查看，确为竖向椭圆。Android 的 `iv_previous_source`、`iv_next_source` 同为 **30.667×36 dp**，配合 `cinema_transport_button.xml` 的 `oval` 背景，源码也存在同一宽高问题；本次未进行原生截图或设备验证。

原因是非正方形按钮框绘制椭圆背景，圆角百分比不保证正圆。用户随后明确将此问题纳入本次设计；B 原型已把两键统一为与新播放键一致的 **58×58 px 设计尺寸**、28px 图标。未聚焦时保留简洁透明底，聚焦时使用底栏共同的正圆浅底、深色图标与轻微上浮；选集仍为文字胶囊。首集禁用上一集、末集禁用下一集，单文件收起剧集操作的规则保持不变。原生确认后再按逻辑尺寸适配。

P07 已在正常与 1.3 字号分别聚焦两键，实际查看截图，并检查每个 B 场景的两键宽高均为 58px、左侧相邻按钮间距至少 10px、操作不侵入字幕区域。此前 53 项检查未断言前后集宽高相等；本次新增这项几何检查及四个聚焦场景，最新共 57 项。**Web 修订完成，Android 布局未改；随 B 底栏和播放键样式一起等待整稿确认与原生实施。**

## 布局与交互边界

- 两稿均保持开放式布局、上方长时间轴、两端时间信息。1440×810 网页画布中，时间轴 y=669、操作行 y=697、字幕左上角 x=488/y=720；这些是设计坐标，不直接当作 Android dp。
- A 的左侧只剩播放圆键与选集小胶囊；不把剩余按钮均匀拉满整行。右侧轨道组位置沿用既有设计，中间继续留给字幕。
- 焦点保持浅底深图标；弹幕开/关由文字与斜线独立表示。确认切换后焦点留在弹幕按钮。
- 字幕继续由既有单行等高、聚焦长名滚动的快捷面板负责，Off/轨道选择、侧栏详细设置职责不变。本页短轨道示意不替代完整字幕设计。
- 选集、音轨、设置面板仅演示入口与返回。网页中简化面板的几何、静态集数与文本不是新的原生设计要求；保留真实数据、既有面板与换集流程。
- 无轨道时入口仍可达并可返回；不新增字幕独立加载/重试 API。缓冲/失败是视频状态示意，原生仍沿用现有加载与错误反馈。
- 无自动隐藏计时、真实媒体解码、长按累计、持久化或真实服务访问；这些网页模拟不能证明播放器行为。

## 页面与状态覆盖

设计实现为 [`player-remote-simplification-prototype.js`](../../../document/prototypes/tv-ui/player-remote-simplification-prototype.js)，通过 `review=remote` 挂在既有原型路由。以下原生实施、构建/仪器测试、原生视觉与实体电视验收均为**未开展，等待用户确认**。

| 编号 | 页面/状态 | Web 参考与检查 | 原生范围（确认后） |
| --- | --- | --- | --- |
| R01 | A/B/当前，暂停与播放 | `layout=compact/retain/baseline`；5/7/9 项及同画面对比 | 底栏布局、焦点顺序、播放图标 |
| R02 | 首集、末集、单文件 | `scene=first/last/single`；B 禁用跳过、单文件四项 | 队列能力显隐及失效焦点回退 |
| R03 | 底栏隐藏与恢复 | Esc → 左右 → Enter → 下键；10 秒跳转、暂停、原焦点恢复 | 保留现有遥控器分发与延迟提交 |
| R04 | 选集、字幕、音轨与返回 | 点击相应入口；浏览不应用、确认应用、返回调用入口 | 复用现有面板与真实轨道/队列 |
| R05 | 弹幕开/关 | 点击或确认切换；斜线、文字、焦点保留 | 保留现有自动保存与渲染开关 |
| R06 | 空轨道 | `tracks=empty`；字幕保留关，音轨显示空状态，可返回 | 不新建加载/重试状态 |
| R07 | 不可跳转、缓冲、失败 | `scene=unseekable/loading/error`；禁止模拟跳转，显示相应状态 | 保留既有播放能力与恢复路径 |
| R08 | 明暗画面、1.3 字号 | `background=bright/dark`、`font=1.3`；边界和实际截图核对 | 同逻辑画布对照原生大字号与焦点 |

## 本轮验证与下一步

2026-11-08 运行 `node document/prototypes/tv-ui/render-player-remote-simplification.cjs`。初稿为 33 项 HTML 观察，播放键设计阶段为 53 项；追加上下集正圆设计后，最新为 **57 项 HTML 观察，浏览器运行异常 0**。[结果 JSON](../../../document/prototypes/tv-ui/previews/player-remote-simplification-2026-11-08/verification.json)记录实际状态、几何和播放键计算样式。除原有按钮/字幕/时间轴、返回、10 秒跳转、轨道与空状态检查外，覆盖四态、同焦点前后对照、按下/释放、确认键重复事件、B 七项移焦、打开面板无错误高亮、明暗及大字号、样式 URL 恢复，以及前后集正圆尺寸、间距和大字号聚焦。预览页面与脚本均返回 HTTP 200。

已实际查看当前开放式基线，以及新稿全屏、三稿对比、选集面板、1.3 字号、明暗及空/缓冲/失败/跳转状态图。首次看图发现 `loading/error` 被旧路由的状态白名单归一为普通场景；已限于 `review=remote` 扩充白名单、添加场景一致性检查并重新生成证据。

本任务只有方案、原型与相关文档变动；未修改 Android 源码，未执行 Gradle、安装应用或操作实体电视。下一步由用户确认包含播放键样式和上下集正圆焦点的 B 整稿，再一并开展原生变更、适当构建/测试、纯 DPAD 和同画面视觉验证。现有实体电视验收状态与本轮设计确认分开记录。
