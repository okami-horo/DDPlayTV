# 播放底栏：开放式设计（进度条在上）

日期：2026-11-03。状态：**用户已确认基础布局、弹幕开关四态及移除侧栏重复总开关；原生调整与本机验证均已完成，实体电视/用户最终验收待进行，见[实施记录](../plans/2026-11-03-player-bottom-open.md)。**

字幕后续：用户进一步确定[单行滚动方案](#字幕快捷面板单行滚动用户已确认)，并明确授权原生实施。底部单选面板负责关闭与选轨，右侧负责详细设置。原生接入已完成，最终构建、DPAD 和视觉结果由实施记录单列；此前横排及纵向换行稿已被本方案替代。

本次修订替代此前“操作在上、进度条在下”的排列。保留独立圆形轨道按钮和选集小胶囊，收紧下方左侧操作的间距以避开原字幕区域；不恢复整组大托盘。

## 查看入口与依据

- [新稿 `variant=open`](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=open&focus=play)。
- [原 UI 与新稿 `variant=compare-open`](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=compare-open&focus=play)。
- [上一稿 `variant=new`](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=new&focus=play)保留供比较；原有 `variant=compare` 仍比较原 UI 与双托盘。
- 官方实例、日期和已查看视频截帧见[第一稿的苹果官方实图核对](2026-11-03-player-bottom-refinement-design.md#苹果官方播放器实图核对)。第二稿参考开放式时间轴与独立小按钮，并按 DDPlayTV 的九个操作和原字幕位置适配，未照搬苹果全部布局。
- 原 UI 为改动前 Android 源码的网页重建，核对依据由第一稿记录。设计阶段统一修正网页字幕层级，使三版字幕均在渐暗层之上；第一稿已生成图片中的偏暗字幕不作为第二稿比较基准。这项修正未改动原生字幕位置或代码。

## 本稿设计

| 部位 | 设计 |
| --- | --- |
| 层次 | 按用户偏好，长进度条在上、按钮行在下；保留开放布局和原顶部信息 |
| 左侧 | ±15 秒、播放/暂停、上一集、下一集直接呈现；选集使用小胶囊；没有共同外框 |
| 右侧 | 字幕、音轨、弹幕各用独立半透明圆形按钮，下面保留文字名称 |
| 时间轴 | 延伸到左右相同安全边距，降低轨道存在感；播放位置用短竖标；当前时间和总时长放到进度条上方两端 |
| 字幕 | 保持既定位置，中间区域不放操作；上下文中的弹幕与标题关系沿用已接受范围 |
| 焦点 | 当前按钮提亮、图标变深并略上浮；非文字播放键在聚焦时显示操作名称；播放主键常态保持浅色 |
| 材质 | 小控件采用半透明冷灰渐变与细边缘，不依赖实时模糊；底部渐暗范围缩小 |

1440×810 设计画布中，两端时间位于 y=633、进度条 y=669、按钮行 y=697。左侧按钮组收紧至 x=476 结束，字幕仍为 x=488、y=720；这些是网页设计像素，不是已确定的 Android dp/sp。三版使用同一静帧、进度、焦点与字幕内容。

## 弹幕开关补充设计（用户已确认）

用户后续决定底部弹幕按钮只提供开关，详细配置放到右侧设置栏；不需要弹幕来源切换。用户要求先确认开启、关闭的视觉样式，再实施原生交互。本提案保持已确认的进度条、九项操作与字幕位置，只补充弹幕按钮状态。

2026-11-03 用户回复“确认，可以实施”，确认下述四态样式并授权原生调整。开关通过既有弹幕显示能力生效，并自动保存偏好；没有选中轨道时也可先关闭，后续加载轨道不应覆盖用户开关。

- 开启：现有弹幕图标，名称为“弹幕开”。
- 关闭：弹幕图标加斜线，名称为“弹幕关”；保留与开启相同的圆形底和可见度，仍可聚焦操作。
- 聚焦：两种状态都使用现有浅底深图标和轻微上浮，避免把焦点误认为开关状态。
- 操作：确认键或点击直接切换，焦点留在弹幕按钮；开关状态不随焦点移入、移出而改变。

[可点击预览](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=open&review=danmaku&danmaku=on&focus=danmaku)沿用同一原型路由；工具条或底部切换器可以切换开关。所有行为只修改网页内存。背景静帧已包含弹幕文字，本预览不模拟消除这部分文字。

| 编号 | 状态 | 原型参数与本地图片 | 原生状态 |
| --- | --- | --- | --- |
| D01 | 开启、未聚焦 | `review=danmaku&danmaku=on&focus=play`；`on-idle-full.png` | 已实施；原生 `on-idle.png`，1.0/1.3 字体通过 |
| D02 | 关闭、未聚焦 | `review=danmaku&danmaku=off&focus=play`；`off-idle-full.png` | 已实施；原生 `off-idle.png`，可见、可达、可返回 |
| D03 | 开启、聚焦 | `review=danmaku&danmaku=on&focus=danmaku`；`on-focused-full.png` | 已实施；原生 `on-focused.png`，确认直接关闭且保留焦点 |
| D04 | 关闭、聚焦 | `review=danmaku&danmaku=off&focus=danmaku`；`off-focused-full.png` | 已实施；原生 `off-focused.png`，确认直接开启且保留焦点 |

图片与 [verification.json](../../../document/prototypes/tv-ui/previews/player-danmaku-toggle-2026-11-03/verification.json)位于本地 `previews/player-danmaku-toggle-2026-11-03/`。[四态对比图](../../../document/prototypes/tv-ui/previews/player-danmaku-toggle-2026-11-03/four-states.png)已实际查看，同时查看了关闭聚焦全屏和交互预览页。2026-11-03 执行 `node document/prototypes/tv-ui/render-player-danmaku-toggle.cjs` 得到 16 项 HTML 观察、浏览器运行异常 0；核对了四态文字与无障碍状态、九项操作、时间轴/字幕坐标、确认切换与焦点保留、离开/返回、隐藏/恢复、鼠标切换及工具条操作。预览服务返回 HTTP 200。

上述 16 项是设计阶段的 HTML 证据。确认后的原生开关、状态同步与自动保存已完成；[原生四态图](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/four-states.png)、[逐态设计对照](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/comparison-four-states.png)及 [verification.json](../../../document/prototypes/tv-ui/previews/player-danmaku-native-2026-11-03/verification.json)另行记录实际查看与源码哈希，结果由[实施记录](../plans/2026-11-03-player-bottom-open.md#弹幕开关原生实施与验证2026-11-03)维护。此前 B01–B06 不替代本次新开关验证。

## 弹幕侧栏职责调整（用户已授权实施）

2026-11-03 用户进一步要求右侧菜单负责详细设置，并回复“实施一下”。右侧“弹幕轨”移除“显示弹幕”总开关；总开关统一由底部按钮控制。轨道列表、添加弹幕轨、显示样式及其他详细配置保留；该时点的字幕开关由下述字幕方案进一步替代。底部关闭弹幕后仍可修改、保存详细设置，重新开启沿用这些设置。

这次调整直接移除既有侧栏的一行，沿用 A06 的尺寸、材质和列表结构。空轨道进入时聚焦“添加弹幕轨”，下键到“显示样式”；有轨道时聚焦列表。样式页 BACK 返回轨道页的既有默认焦点，继续 BACK 可逐层返回底部弹幕按钮。

原生空轨道、有轨道和关闭时样式设置的 S01–S04 覆盖、构建与 DPAD 结果见[本次实施记录](../plans/2026-11-03-player-bottom-open.md#弹幕侧栏移除总开关2026-11-03)。[调整前后原生对比](../../../document/prototypes/tv-ui/previews/player-danmaku-sidebar-2026-11-03/comparison.png)已实际查看；此前“底部与侧栏总开关双向同步”的描述仅代表追加前的验证历史。

## 字幕快捷面板：单行滚动（用户已确认）

2026-11-03 用户确定同一面板中提供“关、字幕轨道……”，选轨即开启。用户指出横排宽度不齐、长文件名和其他语言的问题后，进一步要求“长字幕名也是一排，然后自动滚动”，随后授权“按照我的方案实施一下”。本方案替代横排标签与纵向换行稿，无须再次确认实施。

- [当前单行滚动稿](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=open&review=subtitle&layout=vertical&tracks=many&subtitle=file&option=file&panel=quick)；`layout=horizontal` 保留旧横排供比较。
- 1440×810 设计画布中，面板宽 480px、右侧安全边距 60px，底边 y=607，时间轴和九项操作保持原位置。所有行同宽、等高 72px，随系统字号同比增高；不换行、不缩小字号。
- 长名称默认单行省略，只有聚焦后停留约 1 秒再自动滚动，循环展示；离焦停止并复位。短名称保持静止。名称和 ✓ 独立成列，滚动不带动勾选；阿拉伯文按自身方向显示和滚动。
- 浅色底表示焦点，✓ 表示当前生效项。上下浏览不应用；确认选轨即开启并收起，确认“关”关闭但保留真实轨道选择。BACK 取消浏览、返回底部字幕入口；重开定位生效项，关闭时定位“关”。
- 列表最高 300px，可上下滚动；滚动条与名称、勾选分离，边缘渐隐。原生使用 `SubtitleQuickView`、真实轨道数据和现有设置返回栈。
- 右侧字幕组只保留“样式与位置、时间同步、添加外挂字幕”，分别接现有真实配置和文件选择能力；不再重复开关与常用选轨。音轨和弹幕详细设置保持既有职责。
- 原生轨道接口只暴露列表，没有独立读取中/失败/重试状态。空列表保留“关”，说明可从右侧添加；播放/加载错误继续现有反馈。网页的读取/重试仅为设计演示，不引入虚假的原生重试入口。
- 沿用现有 `SubtitleController` 的生命周期和可见状态，不新增跨播放器重建的字幕总开关偏好；不能将面板重开验证等同于全局持久化。

| 编号 / 状态 | 设计与当前 HTML 证据 | 原生实现与验证归属 |
| --- | --- | --- |
| Q01 普通/一轨/关闭，焦点和生效项分开 | `on-selected.png`、`focus-away.png`、`off-selected.png`、`single-track.png` | `SubtitleQuickView`；选择、浏览、关闭、重开与返回见实施记录 |
| Q02 多轨、长文件名、离焦与短名称 | `many-tracks.png`、`marquee-file-waiting.png`、`marquee-file-moving.png` | 同宽等高、1 秒等待、真实像素变化、固定勾选与复位见实施记录 |
| Q03 德/法文与阿拉伯文 | `german.png`、`arabic.png`、`marquee-rtl-moving.png` | 真实原生布局和 RTL 运行验证见实施记录；演示轨道只在仪器夹具中 |
| Q04 1.3 倍字号 | `font130.png`、`long-name-font130.png`、`german-font130.png`、`arabic-font130.png` | 原生大字体与视觉结果单列，不用 HTML 代替 |
| Q05 空列表、后台轨道更新 | `empty.png`；读取/错误是网页模拟状态 | 原生空列表、状态更新和焦点保留见实施记录；未新增独立重试能力 |
| Q06 右侧详细配置与添加外挂字幕 | `details.png` 只示意入口结构 | 真实侧栏、样式/同步/文件选择及逐层 BACK 见实施记录，保留 A06 |
| Q07 双内核、底栏与既有弹幕/音轨 | 不用网页证明真实播放 | Media3/mpv 播放、字幕选择及相关回归见实施记录 |

本轮 `node document/prototypes/tv-ui/render-player-subtitle-menu.cjs` 得到 **40 项 HTML 观察、浏览器运行异常 0**，日志 `.tmp/subtitle-menu/render-marquee.log`。[验证 JSON](../../../document/prototypes/tv-ui/previews/player-subtitle-menu-marquee-2026-11-03/verification.json)及[状态汇总](../../../document/prototypes/tv-ui/previews/player-subtitle-menu-marquee-2026-11-03/states.png)属于本次单行方案，已实际查看。`player-subtitle-menu-vertical-2026-11-03/` 的 36 项换行观察及更早横排图片仅作历史。

原生构建、仪器测试、实际看图和实体电视验收分别由[实施记录](../plans/2026-11-03-player-bottom-open.md#字幕快捷面板原生实施2026-11-03)维护；早前已验收批次不包含本轮字幕面板。

## 基础布局页面与状态覆盖

本次图片和验证 JSON 位于本地目录 [player-bottom-open-top-2026-11-03](../../../document/prototypes/tv-ui/previews/player-bottom-open-top-2026-11-03/)。此前进度条在下的图片仍在 `player-bottom-open-2026-11-03/`，仅作修订历史。生成图片按仓库规则不追踪；原型、渲染脚本和 JSON 可复现与核对。

| 状态 | 原型入口 / 设计证据 | 原生实现与验证 |
| --- | --- | --- |
| 普通暂停、播放聚焦 | `variant=open&focus=play`；`open-full.png`、`comparison-bottom.png` | B01 已实现并核对原生截图 |
| 播放中 | `scene=playing`；图标与两版同状态检查 | B01/B06 播放、暂停与双内核真实播放通过 |
| 首集与末集 | `scene=first` / `last`；`open-states.png`，禁用占位保留 | B02 禁用占位、跳过焦点及换集通过 |
| 单个文件 | `scene=single`；`open-states.png`，收起三个剧集操作 | B03 显隐与纯 DPAD 通过 |
| 不可跳转 | `scene=unseekable`；`open-states.png`，禁用两键并跳过焦点 | B03 受控能力覆盖验证通过，不代表真实直播验证 |
| 九个操作的焦点 | `focus=...`；九项检查，`open-focus-forward.png`、`open-focus-subtitle.png` | B01 九项 DPAD、提示、聚焦图与上浮通过 |
| 较亮与较暗画面 | `background=bright` / `dark`；`open-bright.png`、`open-dark.png`，静帧亮度模拟 | B04 原生明暗静帧和 1.0/1.3 字体通过；实体设备待验收 |
| 隐藏与恢复 | Esc / Enter 网页模拟，恢复原焦点 | B05 原生 BACK 隐藏、方向下键恢复原焦点通过 |
| 轨道空/加载/错误与选集列表 | 本次未更换侧栏或左侧选集列表；继续沿用已接受面板 | B05 入口、空轨道、字幕选择与返回通过；加载/错误无新增专项结果 |

原生 B01–B06 的构建、DPAD、实际看图、保留差异与设备验收统一由[实施记录](../plans/2026-11-03-player-bottom-open.md)维护；[同静帧底栏对比](../../../document/prototypes/tv-ui/previews/player-bottom-native-2026-11-03/comparison-bottom.png)与验证 JSON 位于本地 `player-bottom-native-2026-11-03/`。

## 生成与核对

```powershell
node document/prototypes/tv-ui/server.cjs 4189
node document/prototypes/tv-ui/render-player-bottom-open.cjs
```

打开 `http://127.0.0.1:4189/player-bottom-refinement-prototype.html?variant=compare-open&focus=play`。工具条与底部切换器可切换三种设计；方向键、Enter、Esc 操作内存中的焦点、播放和进度模拟。

2026-11-03 本次修订重新渲染，完成 **25 个观察记录，浏览器运行异常 0**。检查了九个操作保留、画布边界、按钮位于时间轴下方且不压字幕、文字不压时间轴且不越过底边、原字幕坐标、三种边界状态、两版同焦点、网页方向键、15 秒跳转和隐藏/恢复。实际查看了新稿全屏、新旧底栏对比、字幕聚焦、首末集/单文件/不可跳转和明暗静帧。预览服务返回 HTTP 200，已核对响应包含本次“进度条在上、操作在下”文案。

以上是设计阶段的 HTML 证据。用户随后已确认实施；当前原生实现、构建、DPAD、视觉与设备验收分别在[实施记录](../plans/2026-11-03-player-bottom-open.md)跟踪，本页历史 HTML 检查不替代原生结果。
