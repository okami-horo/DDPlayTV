# TV 深层界面补充设计

日期：2026-10-31。依据：用户授权开始修复覆盖审查发现的 UI；延续已选「沉浸放映」，播放操作采用已选 A。文件浏览 C 仍单独暂缓。

## 视觉与交互基准

- [主原型](../../../document/prototypes/tv-ui/index.html?variant=cinema)、[播放 A](../../../document/prototypes/tv-ui/player-controls-prototype.html?variant=grouped) 为既定基准；[深层页面补充稿](../../../document/prototypes/tv-ui/deep-pages.html) 表达本轮页面组织。补充稿是现有能力的设计样例，不是接入真实服务的应用。
- 网页画布为 1440 × 810；原生对照为 960 × 540 dp、240 dpi。通常按 1.5 px / dp 比较结构和位置，字体按实际 Android 字形核查。
- 页面使用固定标题、36 dp 横向留白和 24 dp 底部留白。工具页按“概览或分类 / 操作内容”分栏；不要给原本独立的工具增加虚构的侧栏导航。
- 播放浮层统一为右侧 264 dp 面板，外边距 15 dp、内边距 18 dp。方向键调整当前控件，BACK 逐层返回原调用者；竖向设置列表的左右键不跳到标题关闭按钮。
- 通用弹窗居中，通常宽 440 dp，文件选择宽 560 dp；标题和操作栏固定，内容滚动。窗口高度受屏幕可用空间约束。来源设置保留自动保存，只增加明确的“返回”，不增加提交确认。
- 主操作使用浅色填充、深色文字；普通操作深色填充。focused 的描边与 selected 的状态底色分开。长文件名允许两行省略，选集另有完整标题区。

## 页面与原生映射

| 组 | 补充稿参数 | 内容组织与能力约束 | 主要原生布局 |
| --- | --- | --- | --- |
| A | `player-controls-prototype.html?variant=grouped` | 播放三键与剧集三键单排分组；首末禁用占位；单文件只保留播放组；提示跟随焦点 | `layout_player_bottom` |
| U01 | `page=episodes` | 当前项、总数、真实文件列表、长标题、滚动；切换沿用现有会话 | `layout_switch_video_source` / `item_video_source` |
| U02 | `page=advanced,offset,style,search,keywords` | 一级菜单、±10/5/0.5 秒双列步进、固定重置；样式保留按内核显示的实际控件；搜索与屏蔽词进入同一侧面板体系 | `layout_player_setting`、`layout_setting_*`、`layout_search_danmu`、`layout_keyword_block` |
| U03 | `page=binding,download,subtitle,files` | 搜索、当前文件与分词入口、左侧弹幕/字幕分类、右侧作品/分集结果；下载选项与压缩包详情、解压选文件分开 | `activity_bind_extra_source`、两种绑定 Fragment、下载与字幕 Dialog |
| U04 | `page=source&kind=FTP/SMB/WebDAV/PC/Alist/SAF/Open115/Cloud115/Baidu`，`page=auth` | 常驻字段名、滚动表单、测试状态；按协议保留差异；短表单按内容居中、长表单限高 | 九种来源 Dialog，115 / 百度授权 Dialog |
| U05 | `page=tools&kind=scan/folders/cast/about/themes/license`，默认缓存 | 缓存概览+缓存类型；扫描分类+目录；常用目录+开关；投屏连接信息+服务控制；关于保留署名和链接 | 缓存、扫描、常用目录、投屏、主题、关于、许可布局 |
| U06 | `page=metadata,match,tasks` | 海报/资料、季、持有文件、管理/返回；纠错结果区在加载或空时仍占位，动作留底；批量起止纵排，左右步进、上下换项 | 三种 metadata Activity、`activity_metadata_settings` |
| U07 | `page=history` | 全量来源历史、进度、更多菜单；空态有可见返回动作 | `activity_play_history`、现有视频行、`layout_empty` |
| U08 | `page=account,profile,anime`，`page=auth&kind=developer` | 登录说明+表单；资料与编辑/改密；番剧固定海报侧栏及信息/剧集/推荐区域；不恢复旧番剧首页 | 登录、账户、认证、改密、番剧详情布局 |
| U09 | `page=files,confirm,preference` | 路径+列表+固定动作；确认/输入/加载/空态复用共有组件；保留 AndroidX 选择框行为和当前 Cinema 主题 | 共用 Dialog、空态、分页尾部、Preference 行 |
| U10 | `page=splash` | 居中品牌、原有 SVG/描边动画、版本；启动开关保持不变 | `activity_splash` |

上表逗号表示分别选择 `page`，不是一个 URL 参数值。`state=normal/empty/loading/error` 为可用的示例状态；部分页面不具有全部四种业务状态，不能将网页状态选择器视为原生状态覆盖证明。

## 协议与状态细节

- FTP：地址/端口、名称/编码、账号/密码、匿名/账号、主动/被动、连接测试。SMB：地址/端口、名称/目录、凭据、匿名与版本；不支持的 SMB 1 明确禁用且跳过焦点。
- WebDAV：地址、名称、凭据、匿名、解析模式与 TLS 模式，保留既有风险提示和连接测试。PC：地址、名称、密码与测试，不恢复摄像头扫码入口。Alist：地址、名称、账号/密码与测试。
- SAF：名称、当前授权目录、系统根目录选择入口。Open115：名称、访问令牌、刷新令牌、测试、清除授权。百度/115 Cloud：名称、授权状态、账号标识、授权/重新授权/断开连接；不虚构根目录 ID 字段。
- 来源测试中/成功/失败在同一状态行更新，表单保留；匿名切换连同字段标签一起收起。真实测试/授权成功仍需对应服务，不能把测试注入的状态当作联网验证。
- 字幕样式按现有渲染后端决定可调整项目：字号、描边、颜色、透明度、字号偏移、垂直偏移等。原生字幕模式隐藏不支持项；本轮不改变用户接受的默认字幕位置或弹幕覆盖标题行为。
- 元数据缺图使用现有占位图；电影没有季栏。纠错默认展示搜索阶段，补充稿用 `kind=series&step=episodes` 展示选集阶段；季通过列表选择，不增加季数输入框。纠错保留电影/剧集类型切换、刷新、忽略、列表选季/起止、批量预览和确认的条件显隐。补充稿不能替代这些原有业务条件。
- 共用文件选择中的空目录保留路径和返回；确认与输入保留取消，加载可依原有语义取消。历史空态的可操作焦点落在“返回”，避免整块空列表出现无意义的系统高亮。

## 验收边界

实施和实际证据以 [执行账本](../plans/2026-10-31-ui-polish.md) 为准。普通、空、加载、失败、返回和焦点状态分别记录。账户、网盘授权、远端字幕/弹幕及真实海报不能因布局构建通过而算联网验收；可选启动动画、物理电视和遥控器长期操作仍需相应证据。文件浏览 C 不在本轮实施范围。
