# TV 界面设计覆盖与旧布局残留排查

日期：2026-10-31。状态：**本文件保留实施前审查；用户随后已授权修复。** 当前补稿、原生调整和未覆盖状态见 [实施账本](2026-10-31-ui-polish.md)。下面的“当前/仍/未实施”均指本次静态审查时；不能用于否定后续改动。审查阶段只核查和记录，不修改原生代码、不操作电视。源码基线为 `f7c2a23f4` 及当前工作区；不能把此前构建通过或安装成功当成这些页面的视觉验收。

## 结论与判断口径

**仍有旧布局残留，也有已改成 Cinema 样式、但没有完整视觉稿的页面。** 除已确认的文件浏览 C 和播放按钮 A 外，本次归纳出 10 组需要补齐或核实设计覆盖的界面。组数不是页面数，也不是运行时缺陷数。

这里的“缺设计”指：在仓库现有三份网页原型及相关设计文档中，未找到足以指导该页面及其状态实现、对照验收的已确认视觉稿。功能需求、焦点规范、主题色、历史原生截图各有价值，但不能代替页面设计。特别是 TMDB 详情 / 纠错 / 任务有明确功能设计，并非完全没设计过。

- **已定稿待实现**：文件浏览 C「列表与预览」；播放器左下 A「单排分组」。不重新选择，也不把它们列作没有设计。
- **保留旧结构且缺稿**：代码仍采用独立旧页面、旧浮层、旧列表或表单组织，原型未覆盖该具体结构。
- **已更新样式但缺稿**：用了 Cinema 组件、颜色或新布局，具体内容组织和边界状态仍没有可核对的定稿。
- **条件入口**：登录态、启动页开关等条件满足才会进入；不据此断言用户当前电视正在显示。

字幕位置、字幕与控制区的相对关系、弹幕覆盖标题，是用户明确接受的现状，**不在整改清单内**。

## 主要发现

### U01 · 完整选集面板仍缺设计，A 只解决按钮组（优先）

入口为播放页的“选集”。[PlayerBottomView:97](../../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt#L97) 打开 `SWITCH_VIDEO_SOURCE`；实际面板仍是 [layout_switch_video_source.xml:8](../../../player_component/src/main/res/layout/layout_switch_video_source.xml#L8) 的 320 dp 左侧全高矩形列表，含自己的标题、条目和滚动。

[A 设计](../designs/2026-10-31-player-controls-design.md) 已明确三项示例列表只验证入口和返回，不能替代真实来源列表。需要补完整列表的当前项、长文件名、滚动、首末项、切集失败与返回焦点稿。接到既有 `UI-PLAYER-CONTROLS` 的 P06 / P08 / P09，不另造播放队列或改变来源语义。

### U02 · 播放器高级设置及其二级面板未被三种基础轨道面板覆盖（优先）

顶部设置按钮由 [PlayerTopView:47](../../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerTopView.kt#L47) 打开完整设置列表；[PlayerSettingView:170](../../../player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt#L170) 继续进入倍速、画面比例、字幕 / 弹幕样式、时间偏移和弹幕配置。轨道面板还可经 [SettingTracksView:161](../../../player_component/src/main/java/com/xyoye/player/controller/setting/SettingTracksView.kt#L161) 进入文件选择，再由 [SwitchSourceView:215](../../../player_component/src/main/java/com/xyoye/player/controller/setting/SwitchSourceView.kt#L215) 进入网络弹幕搜索。

明确的结构残留包括：

- [时间偏移条](../../../player_component/src/main/res/layout/layout_setting_offset_time.xml)：底部 80 dp 高独立浮条，多组 ±秒按钮。
- [弹幕搜索](../../../player_component/src/main/res/layout/layout_search_danmu.xml)：300 dp 左侧全高搜索列表。
- [关键词屏蔽](../../../player_component/src/main/res/layout/layout_keyword_block.xml)：500 × 320 dp 输入与列表弹窗；由 `SettingDanmuConfigureView:94` 打开。
- 倍速、比例、字幕 / 弹幕样式部分已换为 `cinema_player_panel`，内部滑杆、重置、多行配置仍需具体设计，不能认定整个面板未改，也不能认定仅换外壳就完成。

主原型 [playerPanel():48](../../../document/prototypes/tv-ui/index.html#L48) 仅展示字幕轨道 / 字号、音轨选择、弹幕开关与来源示例。应在同一侧面板体系内补齐上述层级和状态，复用现有控制器。

### U03 · 文件的弹幕 / 字幕绑定、搜索与下载流程保留旧布局（优先）

文件更多菜单经 [StorageFileAdapter:428](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt#L428) 进入绑定页；[BindExtraSourceActivity:70](../../../local_component/src/main/java/com/xyoye/local_component/ui/activities/bind_source/BindExtraSourceActivity.kt#L70) 提供“搜弹幕 / 搜字幕”两类。当前 [绑定页布局](../../../local_component/src/main/res/layout/activity_bind_extra_source.xml) 仍有自己的 actionBar 高度工具条、搜索框、分类与两组结果列表。

进一步可达 [弹幕下载选项](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragment.kt#L141)、[字幕详情 / 压缩包文件列表 / 本地选择](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_subtitle/BindSubtitleSourceFragment.kt#L120)，以及“账户与工具”中的[字幕下载页](../../../local_component/src/main/res/layout/activity_shooter_subtitle.xml)。这些具体页面没有已确认的 Cinema 视觉稿。

文件 C 原型展示了绑定操作入口，不等于覆盖搜索、候选、密钥配置、下载进度、失败、解压选文件等后续页面。补稿应沿用同一绑定链路，保留现有能力和文件身份。

### U04 · 来源编辑表单和网盘授权只完成了部分统一（优先）

来源新增 / 编辑由 [StoragePlusActivity](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_plus/StoragePlusActivity.kt) 按类型打开实际编辑器。现有主原型的 [dialogMarkup():49](../../../document/prototypes/tv-ui/index.html#L49) 只有来源类型选择，没有完整连接表单和授权状态稿。

涉及 SMB、FTP、WebDAV、PC、Alist、SAF 根目录、Open115、115 Cloud、百度网盘。代表布局为 [SMB 表单](../../../storage_component/src/main/res/layout/dialog_smb_login.xml) 与 [115 Cloud 授权](../../../storage_component/src/main/res/layout/dialog_cloud115_login.xml)。后者确由 [Cloud115StorageEditDialog:136](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Cloud115StorageEditDialog.kt#L136) 打开；百度授权也有对应入口。

**不是全部没改**：[BaseBottomDialog:30](../../../core_ui_component/src/main/java/com/xyoye/common_component/weight/dialog/BaseBottomDialog.kt#L30) 已使用 Cinema 主题和居中窗口。本次查看的历史 `source-ftp.png` 也显示了新版外壳与焦点。缺口在具体字段组织、测试中 / 成功 / 失败、扫码等待 / 过期、重新授权和断开连接设计；旧表单结构仍在外壳内部。

应复用同一表单 / 授权模板，再明确协议差异，不复制出多套新的窗口系统。PC 来源的摄像头扫码入口已隐藏，不作为本轮确定可达缺口。

### U05 · 设置深层页面未被四分类主设置稿覆盖

主设置“关于”经 [AppSettingFragment:95](../../../user_component/src/main/java/com/xyoye/user_component/ui/fragment/AppSettingFragment.kt#L95) 到“账户与工具”，其 [PersonalFragment:166](../../../user_component/src/main/java/com/xyoye/user_component/ui/fragment/personal/PersonalFragment.kt#L166) 继续提供播放设置、扫描管理、缓存管理、常用文件夹、字幕下载、投屏接收、应用设置和开发者选项。

确认保留独立旧结构的代表：

- [缓存管理](../../../user_component/src/main/res/layout/activity_cache_manager.xml)：工具条、路径与容量卡片、缓存类型列表，路径文字 10 sp。
- [扫描管理](../../../user_component/src/main/res/layout/activity_scan_manager.xml)：工具条下 40 dp 分类行，再进入扫描扩展 / 屏蔽列表与扩展名配置。
- [常用文件夹](../../../user_component/src/main/res/layout/activity_commonly_folder.xml)：独立分组行与路径选择。
- [投屏接收](../../../storage_component/src/main/res/layout/activity_screen_cast.xml)：服务开关、连接二维码、地址 / 端口说明。
- 主题切换、完整关于 / 许可证 / 内嵌网页、开发者认证等页面，也未找到完整视觉稿。

高级播放 / 字幕 / 弹幕偏好和账户工具主体已有部分新布局或公共样式，不宜整组称为完全旧版。但 [BasePreferenceFragmentCompat:39](../../../core_ui_component/src/main/java/com/xyoye/common_component/base/BasePreferenceFragmentCompat.kt#L39) 特意保留 `SeekBarPreference` 的专用行，部分选择对话框仍委托 AndroidX；原型未定义这些完整状态。补稿以现有设置分类为基础，不新增设置存储层。

### U06 · TMDB 作品详情、纠错、扫描任务有功能设计，但没有完整视觉稿（优先）

[TMDB 设计第 9 节](../designs/2026-10-31-tmdb-media-library-design.md#9-页面与播放行为) 已规定作品详情、候选、季集、文件版本、任务和失败行为；现有三个 HTML 中没有这些完整页面。

当前实现已明显采用新样式，不能称作旧 UI：

- [作品详情](../../../local_component/src/main/res/layout/activity_metadata_detail.xml)：背景、海报、季列表、持有文件与资料管理；多版本选择由 [MetadataDetailActivity:207](../../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataDetailActivity.kt#L207) 打开通用操作框。
- [识别 / 更正](../../../local_component/src/main/res/layout/activity_metadata_match.xml)：搜索、电影 / 剧集切换、候选、起止集、批量目录预览与确认。
- [作品资料与扫描任务](../../../user_component/src/main/res/layout/activity_metadata_settings.xml)：任务列表、配置状态和缓存动作。
- “全部作品”独立页与筛选、无图、空结果等状态同样缺少与主媒体库区分后的完整稿。

这些页面已在当前来源 / 媒体库链路中可达。应补视觉与状态矩阵，复用现有元数据语义，不以此次补设计扩大扫描协议或修改匹配策略。

### U07 · 独立播放历史页没有完整稿

媒体来源里的历史入口由 [MediaFragment:222](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt#L222) 打开独立页面。[activity_play_history.xml](../../../local_component/src/main/res/layout/activity_play_history.xml) 仍为工具条加全屏列表；[PlayHistoryAdapter:62](../../../local_component/src/main/java/com/xyoye/local_component/ui/activities/play_history/PlayHistoryAdapter.kt#L62) 使用共用空态及 `item_storage_video`。

共用视频行已有 Cinema 调整，所以这是“旧页面组织 + 部分新行样式”。首页横向继续观看不能作为全量历史页及删除 / 解绑菜单的设计。建议复用既定列表语言补独立历史页，保持已有来源隔离与精确续播语义。

### U08 · 账户及关联番剧信息页为条件可达的旧界面

[PersonalFragment:134–158](../../../user_component/src/main/java/com/xyoye/user_component/ui/fragment/personal/PersonalFragment.kt#L134) 保留账户资料、追番记录和云端历史入口；未登录转到登录页。[登录页](../../../user_component/src/main/res/layout/activity_login.xml)、开发者认证、账户编辑 / 改密 / 头像及后续番剧详情没有新稿。

已登录时可经追番 / 云端历史进入番剧详情；[activity_anime_detail.xml](../../../anime_component/src/main/res/layout/activity_anime_detail.xml) 仍使用 `AppBarLayout`、310 dp 的 `CollapsingToolbarLayout` 与标签 / 分集 / 推荐子页。是否在当前用户会话中实际可用取决于登录和服务状态，本次未访问服务，不宣称运行验证。

不能把 anime 模块全部 23 个布局算作当前主路径。当前 TV 壳未挂旧番剧首页；番剧搜索 / 季度页是否需要重新挂入口，应另行确认产品范围，不自动恢复。

### U09 · 共用文件选择、确认和异常反馈未形成完整组件状态稿

本地文件 / 目录选择不只是文件浏览页 C：例如 [StorageFileAdapter:441](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt#L441)、常用文件夹、扫描扩展、字幕解压都调用 [FileManagerDialog](../../../core_ui_component/src/main/java/com/xyoye/common_component/weight/dialog/FileManagerDialog.kt#L45)。其 [布局](../../../core_ui_component/src/main/res/layout/dialog_file_manager.xml) 仍为 30 dp 路径行 + 350 dp 文件列表。

同时存在共用确认 / 输入框、带“不再提示”的确认、加载框、分页尾部、[共用空态](../../../core_ui_component/src/main/res/layout/layout_empty.xml)、播放器加载 / 消息反馈。部分已继承新外壳，不能全判成旧弹窗；具体的成功 / 空 / 错误 / 重试 / 取消 / 长文案及 DPAD 返回稿仍不完整。

文件 C 已画的空目录等状态只适用于那一页，不能代表所有共用组件。建议补一套现有共用组件的状态稿，再映射各调用处，避免逐页各自改样式。

### U10 · 启动动画页未纳入沉浸放映设计

[BaseSplashActivity:42](../../../app/src/main/java/com/okamihoro/ddplaytv/ui/splash/BaseSplashActivity.kt#L42) 根据启动动画开关决定是否显示旧启动动画；[activity_splash.xml](../../../app/src/main/res/layout/activity_splash.xml) 仍使用原 SVG / 文字描边动画、版本和网站布局。三个原型没有启动页。

这是开关相关、优先级较低的设计覆盖项。先决定是否沿用并记录验收，不自动删除动画或更改用户设置。

## 覆盖范围与排除项

枚举了当前文件系统中 163 个 `src/main/res/layout/*.xml`：app 3、core_ui 20、local 33、player 32、storage 22、user 30、anime 23。它们包含条目、子布局和独立模块占位页，**不是 163 个独立页面或 163 个缺陷**。布局清单和基础资源引用索引保存在 `.tmp/ui-design-audit/layouts.json`。

审查从 TV 主壳开始，沿首页 / 媒体库、设置、播放页追踪路由、点击监听、面板枚举、布局与共用样式；主要风险链路逐项读源码。Manifest 仅用作入口清单，未把注册过的 Activity 一律认定可达。程序生成的全部文案、每种数据组合和当前设备尺寸未穷举。

已排除或不作缺陷判断：

- 首页 / 主媒体库 / 搜索弹窗 / 四分类主设置 / 三类基础轨道面板已有基准，不能因本次发现深层缺口就全部重算为“没设计”。它们的全部状态验收仍遵循原账本。
- `BaseBottomDialog` 名称虽有 Bottom，实际已是居中 Cinema 窗口；`layout_bg_color`、`text_black` 等旧资源名在夜间也已映射新颜色。不能靠类名、文件日期或是否含 `cinema` 字符判断旧 UI。
- 多个 feature 的 `activity_main.xml` 为模块开发入口 / 占位；当前主壳没有直接使用，不列为用户当前 UI 残留。
- 截图 / 后台播放设置项在 `PlayerSettingView.generateItems()` 中被排除；PC 摄像头扫描按钮在 `RemoteStorageEditDialog` 中隐藏。这些不计入已确认可达缺口。
- 系统权限框、系统文件授权界面不归应用自行重画；只需设计调用前后说明和焦点恢复。
- 已退役的 Bilibili、磁力 / Torrent、手工串流和外部视频入口不恢复。

## 证据边界与后续使用

- 工具：`git ls-files` 枚举、`git grep` / PowerShell 文本定位、Node 读取资源和生成索引，随后读取调用方与实现。当前 PATH 无 `rg`、`ast-grep` / `sg`，也无可调用的 Claude Context 工具；未安装工具或重建索引。这是文本定位与人工核对，**不声称 AST 完整调用图**。
- 本次实际查看了历史本地 `source-ftp.png`、`native-settings-playback.png`，帮助区分已改外壳与缺稿内容；没有截图当前电视，也没有新的原生运行验证。
- 没有运行 Gradle / Android 测试；这次没有修改源码，构建成功不能判定视觉稿覆盖，历史通过数不挪作本轮结果。也没有进行无关的架构、安全或重复代码审计。
- 建议先补 U01–U04、U06 的主要操作链，再收敛 U05 / U07 / U09，最后核实 U08 / U10 的产品保留范围。共用组件稿可先定义，再供各链路复用；不要求另建主题系统。
- 后续任务必须把“页面 / 入口 / 状态 → 已选稿 → 原生实现 → 截图 / DPAD → 用户或电视验收”逐项关联。缺稿项保持开放；旧实现可暂留，但不能以统一颜色、通过构建或操作入口存在代替视觉完成。
