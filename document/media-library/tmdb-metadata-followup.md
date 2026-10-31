# TMDB 媒体库元数据待办

记录日期：2026-10-30；实施：2026-10-31。状态：**用户已授权实施，代码已接入，验证结果单独记录**。本页保存产品范围，实际检查与未验收项目见 [实施记录](../../docs/superpowers/plans/2026-10-31-tmdb-media-library.md)。

详细职责、参考源码、数据模型、识别/纠错、扫描完整性、缓存、TV 交互及分期验收统一见 [TMDB 媒体库与刮削设计](../../docs/superpowers/designs/2026-10-31-tmdb-media-library-design.md)。实施采用独立元数据模块、Room 18→19、原生页面与手动目录扫描，首批仅开放本地已有索引、SAF、SMB、WebDAV。

## 已确认决策

- 电影、电视剧、番剧统一采用 **TMDB** 作为媒体库在线元数据源，包括作品资料、海报、背景图、季度信息和单集剧照。
- 替代此前讨论的“普通影视使用 TMDB、番剧使用弹弹play详情”的分源方案；不为新媒体库另接 Bangumi、TVmaze 等元数据源。
- 这一决策针对媒体库元数据。既有弹弹play 弹幕匹配与下载仍属于独立能力；本次不授权删除或改造现有番剧、弹幕功能。
- 新 UI 已选定 **沉浸放映（`cinema`）**：暗色、苹果系风格，顶部导航、大背景，突出继续观看。Android 首页已接入本地 Room 状态；网页 [UI 原型](../prototypes/tv-ui/README.md) 仍使用独立示例数据。

## 待办范围

- [x] TMDB 搜索、作品和季详情，使用返回的默认海报、背景和剧照；图片配置拼接地址。
- [x] 片名/年份/季集解析、保守匹配、手动纠错与季集范围；连续集数保留待确认，不猜季度。
- [x] Room 持久化文件/作品/关联与人工锁定，独立 HTTP 缓存和 Coil 图片缓存；缺图使用文件图标，原文件浏览与播放保留。
- [x] 原生沉浸首页、作品库、详情和已持有选集，单个物理文件播放与精确历史续播。
- [x] 手动目录扫描、任务计数/取消/重试、目录完成标记与映射预览；未支持协议不显示扫描入口。
- [x] `TMDB_READ_ACCESS_TOKEN` 构建注入（环境变量 → Gradle 属性 → local.properties），缺省明确提示；关于页面显示官方标志和声明。
- [x] 使用项目凭证完成主机 TMDB API/图片请求与 Android MetadataRepository/Coil 的真实背景、海报加载；证据见 [原生 UI 记录](../../docs/superpowers/plans/2026-10-31-cinema-ui.md)。
- [ ] 完成四类真实来源联调，测量人工标注样本准确性；完成物理电视验收。

## 使用入口

从“来源”打开本地/SAF/SMB/WebDAV 文件，使用文件菜单“识别 / 更正资料”；选择作品后确认季与单集，或通过左右键设定合并集范围。来源目录菜单可手动扫描；首页“资料与任务”提供取消、重试和缓存清理。解除关联后默认忽略自动识别，仍可再次手动选择资料。详情只播放拥有的文件，长按确认键或“更正资料”进入纠错。

资料语言当前固定 `zh-CN`，成人搜索关闭；跨作品自动连播、其他协议索引、常驻后台任务、NFO 与自选图片不在本次交付范围。

## 已知边界与参考

- TMDB 提供搜索和资料查询，文件名解析与文件到作品/季/集的映射需要由项目实现。单集图片通常是剧照，不能假定每个条目都有完整图片。
- 当前网页仍使用示例数据；Android 页面读取真实数据库记录。固定响应测试与 AVD 结果不能代替真实请求、识别率或物理电视网络验收。
- 2026-10-30 已查阅官方文档：[搜索与详情流程](https://developer.themoviedb.org/docs/search-and-query-for-details)、[作品图片](https://developer.themoviedb.org/reference/tv-series-images)、[季度图片](https://developer.themoviedb.org/reference/tv-season-images)、[单集图片](https://developer.themoviedb.org/reference/tv-episode-images)。
- 免费条件按 [官方 FAQ](https://developer.themoviedb.org/docs/faq) 为非商业用途并注明来源；正式接入时复核当时条款。
