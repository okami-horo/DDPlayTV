# Apple 风格 UI 设计审查

日期：2026-11-02。源码基线：`c69409f33`。范围：当前选定的 Cinema 首页、播放控件 A、文件浏览 C、作品详情 B，以及深层设置、来源表单、弹窗和番剧子页。

当前状态（2026-11-03）：A02、A06 与 A03 已完成实施，并由用户确认实机验收通过，见 [A02 焦点记录](../../docs/superpowers/plans/2026-11-02-a02-focus-feedback.md)、[A06 侧栏材质记录](../../docs/superpowers/plans/2026-11-02-a06-player-panel-layers.md)及 [A03 容器减层与原生对比](../../docs/superpowers/plans/2026-11-02-a03-container-hierarchy.md)。用户随后要求清除低价值和有限价值的旧待办，**A04、A05、A07 已取消，不再排期**；A01 仅保留按实际阅读需要核对的方向，尚未安排实施。取消不表示对应界面已经修改。本报告及原问题图库保留原始审查基线，不构成新的实施任务。

原审查结论（2026-11-02）：发现 7 类值得调整的问题，当时建议优先处理小字号、焦点样式和媒体库重复标题；首页、详情 B、文件浏览 C 的主体结构可以保留。深层页面的主要差距是容器与轮廓偏多、信息主次不足，部分地方更接近工具面板。当前取舍以上段和下表为准。

判断依据为 Apple 的 [tvOS 设计指南](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos)、[排版](https://developer.apple.com/design/human-interface-guidelines/typography)、[焦点与选择](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection)、[布局](https://developer.apple.com/design/human-interface-guidelines/layout)和[材质](https://developer.apple.com/design/human-interface-guidelines/materials)。以下分别标明可从源码确认的问题与审美建议；Apple 平台的尺寸单位不直接套用为 Android sp/dp。

本次重新渲染并查看 **64 个原型页面／状态视图**，另查看 **20 张既有原生截图**，以当前源码核实问题是否仍然存在。原生截图是历史证据，不能证明当前 APK、实体电视或动态效果已经重新验收。

按用户要求整理的 [问题截图确认图库](apple-ui-audit-2026-11-02/confirmation/index.html) 包含 **40 张截图**（20 张历史原生、20 张原型），可按 A01–A07 筛选、放大、隐藏标框并打开原图；[分类总览与说明](apple-ui-audit-2026-11-02/confirmation/README.md) 适合逐项确认。这次整理另补充收录了 FTP、WebDAV 滚动后的两张历史错误状态图，不改变上段原审查抽样统计，也不构成新原生抓取。40 张复制图的哈希均与来源一致，图库筛选、放大、标注开关、键盘和本地文件打开已验证，页面脚本错误为 0；见 [图库验证](apple-ui-audit-2026-11-02/confirmation/verification.json)。以上为设计确认前记录，A02／A03／A06 的后续实施状态见报告开头。

## 发现

优先级：P1 影响远距离信息读取；P2 影响视觉层级或风格一致性；P3 为局部细节。

| 编号 | 原优先级 | 问题 | 主要位置 | 原证据性质 | 当前处理 |
| --- | --- | --- | --- | --- | --- |
| A01 | P1 | 辅助信息过小，重要状态也被压成脚注 | 详情 B、剧集卡、设置说明、媒体库标签 | 源码确认；视觉判断 | 按实际阅读需要保留，未排期 |
| A02 | P2 | 焦点偏重描边，主按钮双轮廓，各类控件表达不统一 | 共用按钮、导航、播放器列表 | 源码确认；审美建议 | 已实施并验收 |
| A03 | P2 | 容器套容器、逐行方块较多，留白没有承担足够的分组作用 | 深层工具、播放面板、番剧子页 | 原型及部分原生证据；审美建议 | 已实施并验收 |
| A04 | P2 | 同一张作品卡出现两次相同标题 | 媒体库“最近添加”横卡 | 源码及既有原生截图确认 | 低收益，已取消待办 |
| A05 | P2 | 基础连接信息与高级参数同级，表单偏密 | FTP、WebDAV，部分 SMB 配置 | 源码确认；组织方式建议 | 中低收益，已取消待办 |
| A06 | P2 | 播放浮层主要依赖近乎实色的叠层，材质层次较弱 | 选集、轨道、样式等右侧面板 | 资源确认；审美建议 | 已实施并验收 |
| A07 | P3 | 关于页品牌图形缺少主体，只显示圆角白块 | 关于页 | 源码及既有原生截图确认 | 低收益，已取消待办 |

下文 A01–A07 的发现、建议和截图保留为原审查正文；已取消或已验收的项不得据这些历史建议自动恢复为待办。

## A01：先调整辅助文字的阅读尺度

详情页的“上次观看”标记为 **8sp**，剧集卡进度／来源为 **9sp**，续播说明为 **9sp**；作品元信息、季标签及部分次级操作为 **10sp**。通用设置行的说明为 **11sp**。这些信息直接帮助用户判断看到哪一集、正在操作什么，并非全部属于可忽略的装饰性脚注。

证据：[剧集卡布局，第 6、10 行](../../local_component/src/main/res/layout/item_metadata_episode.xml)、[详情布局，第 17–24 行](../../local_component/src/main/res/layout/activity_metadata_detail.xml)、[设置行，第 4 行](../../core_ui_component/src/main/res/layout/item_cinema_preference.xml)。[详情原型对照](apple-ui-audit-2026-11-02/03-detail.jpg)、[原生页面对照](apple-ui-audit-2026-11-02/10-native-main.jpg)可看到主标题与辅助信息的尺度落差。

Apple 强调结合观看距离检验易读性，并以字号、字重、颜色建立层级。[排版指南](https://developer.apple.com/design/human-interface-guidelines/typography)

建议优先为进度、来源、季集、设置说明建立少量统一文字级别；可先以 **12–14sp** 作为当前逻辑视口的验证候选。增大后相应减少同屏说明、调整容器高度，核对正常／放大字体和客厅距离。主标题无需随辅助文字同比放大。

复杂度：优先复用现有文字样式，减少局部字号覆盖；不增加字体框架。XML 扫描发现 19 个文件含 39 处不超过 11sp 的直接属性，其中包含隐藏或条件控件，这个数量不能直接当作 39 个可见缺陷。

## A02：统一焦点的视觉语言

当前主要靠白色轮廓区分焦点：普通按钮为 2dp，播放器列表为 3dp；主按钮还在浅色填充外保留 5dp 间隔的外框，形成双轮廓。导航的 focused 与 selected 都采用浅色填充。各个状态分别可见，但跨页面的强调强度、轮廓与层次并不一致。

证据：[主按钮，第 2–19 行](../../core_ui_component/src/main/res/drawable/cinema_primary.xml)、[普通按钮](../../core_ui_component/src/main/res/drawable/cinema_button.xml)、[导航](../../core_ui_component/src/main/res/drawable/cinema_navigation.xml)、[播放器列表，第 2–3 行](../../player_component/src/main/res/drawable/background_player_setting_text.xml)。共用按钮还明确关闭默认 `stateListAnimator`：[Cinema 样式，第 23 行](../../core_ui_component/src/main/res/values/cinema.xml)。这些资源只能证明对应状态的静态定义，不能据此断言整套应用完全没有动画。

Apple 的 tvOS 焦点强调前景提升、照明和响应，同时区分焦点、选中与禁用状态。[焦点指南](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection)

建议先统一普通／焦点／选中／禁用的表达强度：选中保留稳定标记，焦点以适度提亮和清楚的边缘反馈表达；减轻主按钮双框。轻量位移、缩放或阴影属于后续可验证选项，必须保留已有焦点留白和裁剪修复，并检查连续 DPAD、放大字体与减少动态效果的场景。

复杂度：修改现有 selector 和共用样式即可先取得一致性；无需逐页新增动画控制器。

## A03：减少重复的容器边界

补充稿中，深层工具常采用“大圆角面板 → 带边框的列表行 → 独立按钮”；番剧子页也有整块内容底板和内层卡片。部分工具左栏信息很少，却仍把有底色的面板拉至大半屏高度。页面整体较整齐，但边界比内容更突出，分组层级不够轻。

证据：[深层稿样式及工具页，第 5、53 行起](../../document/prototypes/tv-ui/deep-pages.html)、[子页稿样式，第 5 行](../../document/prototypes/tv-ui/remaining-pages.html)；可直接查看[工具页面](apple-ui-audit-2026-11-02/06-tools.jpg)、[番剧子页](apple-ui-audit-2026-11-02/08-anime-pages.jpg)及[原生工具页](apple-ui-audit-2026-11-02/12-native-forms-tools.jpg)。原生选集的普通行已比补充稿克制，不能把原型中的每条边框都算作当前原生问题。

Apple 建议通过留白、容器或分隔线表达关联，并用渐进展开控制初始信息量。[布局指南](https://developer.apple.com/design/human-interface-guidelines/layout)

建议每组只保留必要的一层主容器；组内普通行优先使用留白或细分隔线，把明显填充留给焦点／选中项。说明性侧栏可直接置于页面背景，内容短时按内容收口。保持既定分栏和真实功能。

复杂度：减少重复背景、边框和无意义的填满高度即可，不需要新建页面壳或改变模块边界。

## A04：媒体库横卡的标题仍然重复

“最近添加”横卡同时在图片下沿与图片下方显示同一标题。当前 `MetadataCardAdapter` 把 `card.title` 同时赋给 `card_title` 与 `card_caption`；横卡布局保留了这两个可见控件。

证据：[适配器，第 99–100 行](../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataCardAdapter.kt)、[横卡，第 4、7 行](../../local_component/src/main/res/layout/item_metadata_landscape.xml)。[原生页面对照右上角](apple-ui-audit-2026-11-02/10-native-main.jpg)与[当前主原型右上角](apple-ui-audit-2026-11-02/01-main.jpg)均有重复。

建议沿用首页已经采用的单标题结构：标题保留在图片下方，图片承载画面与必要的进度信息。这样能减少竞争，也给长标题留出更稳定的阅读位置，符合内容与信息层级的组织方向。[布局指南](https://developer.apple.com/design/human-interface-guidelines/layout)

复杂度：处理横卡中一个重复标题即可；首页的重复标题已经修复，本次不将其重新列为未修复问题。

## A05：来源表单需要进一步区分基础与高级信息

FTP 将地址、端口、显示名、编码、账号、密码、登录模式、主动／被动模式及连接测试连续展开；WebDAV 同时展开登录方式、解析模式、TLS 模式与测试状态。标签和输入框提示也经常重复同一个名称。需要配置的内容是真实能力，但当前呈现方式让多数项目具有接近的视觉权重。

证据：[FTP 表单，第 130、274、310 行附近](../../storage_component/src/main/res/layout/dialog_ftp_login.xml)、[WebDAV 表单，第 199、235、271 行附近](../../storage_component/src/main/res/layout/dialog_web_dav_login.xml)；[来源设计对照](apple-ui-audit-2026-11-02/05-sources.jpg)、[既有原生表单](apple-ui-audit-2026-11-02/12-native-forms-tools.jpg)。后者部分由测试直接打开在启动器之上，其宿主背景不作为产品页面缺陷。

建议优先显示地址与认证；编码、传输模式、解析和 TLS 参数归入明确的高级区域，并显示当前选择摘要。保留常驻字段名，将输入提示改为有帮助的格式例子，避免重复标签。连接测试保留明确状态与可达入口。这符合 Apple 的渐进展开原则。[布局指南](https://developer.apple.com/design/human-interface-guidelines/layout)

复杂度：在各现有表单内调整分组和显隐，不新增统一协议表单引擎，也不改变认证、测试或自动保存语义。

## A06：播放器浮层的材质层次可以更清楚

右侧面板使用固定色 `#F51C1C22`，即约 **96% 不透明度**，加 1dp 边线。现有截图中，它主要表现为覆盖视频的深色长面板；普通行再叠加另一层深色底。与 Apple TV 风格相比，面板和视频之间的前后层次较弱，少量透出的高对比画面／底层控件也未形成有帮助的上下文。

证据：[面板资源，第 1 行](../../player_component/src/main/res/drawable/cinema_player_panel.xml)、[播放器列表资源](../../player_component/src/main/res/drawable/background_player_setting_text.xml)、[既有原生播放器面板](apple-ui-audit-2026-11-02/11-native-player.jpg)。这是视觉方向判断；本次没有重新测量动态视频下的对比度或 GPU 成本。

建议先通过组内层次、面板边界与背景抑制改善分离；对有干扰的背景，可以采用更稳定的不透明底。仅在兼容性与性能允许时试验局部模糊／柔和阴影。Apple 明确要求节制使用 Liquid Glass，并按语义选材质；内容层也有标准材质可用。[材质指南](https://developer.apple.com/design/human-interface-guidelines/materials)

复杂度：优先调整已有面板资源；不引入全应用实时模糊，也不将“增加玻璃效果”设为整体改造目标。

## A07：关于页缺少完整品牌图形

关于页的 `app_iv` 直接显示 `cinema_brand`，而该资源只有圆角浅色背景。主导航是在同一背景上另放播放三角，因此关于页会显示为没有主体图形的白色圆角块。

证据：[关于页，第 34–38 行](../../user_component/src/main/res/layout/activity_about_us.xml)、[品牌背景](../../core_ui_component/src/main/res/drawable/cinema_brand.xml)、[主导航，第 6 行](../../app/src/main/res/layout/activity_tv_main.xml)；[原生工具页中“关于”截图](apple-ui-audit-2026-11-02/12-native-forms-tools.jpg)。

建议复用现有背景与播放图形，形成完整标识，并核对尺寸和光学居中。Apple 强调图标的可识别性与视觉一致性。[图标指南](https://developer.apple.com/design/human-interface-guidelines/icons)

复杂度：复用现有资源即可，无需重新设计品牌。

## 页面与状态覆盖

| 页面组 | 本次查看范围 | 图板 | 主要结论 |
| --- | --- | --- | --- |
| 主界面 | Cinema 首页、媒体库、设置、搜索、播放 A、文件根目录 | [01](apple-ui-audit-2026-11-02/01-main.jpg) | 主体结构保留；A01、A02、A04 |
| 文件浏览 C | 视频、长路径、空、加载、失败、刷新失败、分页失败、登录失效 | [02](apple-ui-audit-2026-11-02/02-files.jpg) | 列表与预览分工合理；小文字随 A01 统一 |
| 详情 B | 普通、多季、多版本、空、加载、电影、失效、首次读取 | [03](apple-ui-audit-2026-11-02/03-detail.jpg) | 结构保留；A01、A02 |
| 播放器深层 | 选集、设置、偏移、样式、搜索、关键词、下载、字幕详情 | [04](apple-ui-audit-2026-11-02/04-player-panels.jpg) | A02、A03、A06 |
| 来源表单 | FTP、SMB、WebDAV、Alist、SAF、Open115、115 Cloud、百度 | [05](apple-ui-audit-2026-11-02/05-sources.jpg) | A05；表单设计不等于真实服务验收 |
| 深层工具 | 缓存、常用目录、投屏、关于、主题、许可 | [06](apple-ui-audit-2026-11-02/06-tools.jpg) | A03、A07；投屏稿仅作历史设计抽样 |
| 小弹窗与反馈 | 搜索词、扩展名、头像、字体进度、加载、提示 | [07](apple-ui-audit-2026-11-02/07-child-dialogs.jpg) | 结构较统一；侧重 A01、A02 |
| 番剧与账户列表 | 追番、云端历史、简介、分集、推荐、详情 | [08](apple-ui-audit-2026-11-02/08-anime-pages.jpg) | A03；账户服务状态未重新验证 |
| 其他深层页 | 绑定、任务、登录、资料、文件选择、确认、内核选择、启动 | [09](apple-ui-audit-2026-11-02/09-other-pages.jpg) | 共享 A01–A03；不恢复已撤下入口 |
| 既有原生证据 | 主界面 6 张、播放器 6 张、表单与工具 8 张 | [10](apple-ui-audit-2026-11-02/10-native-main.jpg)、[11](apple-ui-audit-2026-11-02/11-native-player.jpg)、[12](apple-ui-audit-2026-11-02/12-native-forms-tools.jpg) | 逐项回查当前源码，避免以旧图认定当前缺陷 |

## 设计资产的一致性问题

部分网页仍保留已被后续产品决定覆盖的内容：页面返回按钮、播放器关闭按钮、旧提示、PC 来源及历史／投屏等旧页面示例；主原型的部分二级页面还重复显示时钟。这些属于**设计稿维护问题**，不能直接认定当前 Android 页面仍有对应控件。

当前权威关系仍以 [projectBrief](../../memory-bank/projectBrief.md) 和各任务账本为准。后续整理原型时应同步标记适用范围，避免把旧稿再次实现回来；本次未修改原型或恢复入口。

## 保留的设计与验证边界

- Cinema 的内容主导、顶部导航、横向作品卡，详情 B 的作品信息／剧集架，以及文件浏览 C 的列表／预览分工，均可继续作为当前设计基础。
- 现有低饱和配色与共用线性图标可以保留。Android 系统字体、遥控器 BACK、不同用途的圆角半径、少量实色面板均不单独构成“苹果风不合格”。Apple 建议按内容和语义使用材质。[材质指南](https://developer.apple.com/design/human-interface-guidelines/materials)
- 首页单标题、近期卡片焦点留白及显式图片圆角已经有修复记录，本次没有将旧问题重新列为未实施。字幕位置与弹幕覆盖标题按用户既定选择保留。
- 新图来自本地 HTML 的 1440×810 逻辑画布；为截图隐藏评审栏并固定外层缩放，没有修改原型内容或样式源文件。截图脚本曾因选择器／评审外框导致不完整捕获，已修正并重新生成、重新查看最终图板；不完整截图未作为发现证据。
- Playwright 最终渲染 **64/64 完成，页面脚本错误 0**；XML 解析扫描 **152 个布局**。这不是运行时、DPAD、联网或动画测试。
- 审查阶段没有构建／测试应用，也没有操作电视。该阶段修改仅为审查报告、证据图板与记忆索引；七项建议当时尚未实施，新的整体视觉方案也未被选定。后续独立确认与实施状态见报告开头。

图像来源见 [图板清单](apple-ui-audit-2026-11-02/image-manifest.json)，当前源码和原型身份、工具及扫描信息见 [审查证据](apple-ui-audit-2026-11-02/audit-evidence.json)。
