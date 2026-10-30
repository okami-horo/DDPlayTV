# TMDB 媒体库元数据待办

记录日期：2026-10-30。状态：**用户已确认选型，待实现**。本次仅记录待办，不启动 TMDB 接入或 Android UI 调整。

## 已确认决策

- 电影、电视剧、番剧统一采用 **TMDB** 作为媒体库在线元数据源，包括作品资料、海报、背景图、季度信息和单集剧照。
- 替代此前讨论的“普通影视使用 TMDB、番剧使用弹弹play详情”的分源方案；不为新媒体库另接 Bangumi、TVmaze 等元数据源。
- 这一决策针对媒体库元数据。既有弹弹play 弹幕匹配与下载仍属于独立能力；本次不授权删除或改造现有番剧、弹幕功能。
- 新 UI 已选定 **沉浸放映（`cinema`）**：暗色、苹果系风格，顶部导航、大背景，突出继续观看。网页原型默认展示该方案，Android 页面调整仍待实现；见 [UI 原型](../prototypes/tv-ui/README.md)。

## 待办范围

- [ ] 接入 TMDB 搜索与详情，获取电影、剧集、番剧及作品/季度/单集图片。
- [ ] 解析文件名中的片名、年份、季与集，建立媒体文件与 TMDB 条目的关联；处理特别篇、连续集数及命名歧义，提供手动纠错。
- [ ] 缓存匹配结果、元数据和图片；缺图、未匹配或网络不可用时使用文件名与本地缩略图，保持浏览和播放可用。
- [ ] 基于已选的沉浸放映方案，用真实元数据完善首页、海报墙、作品详情和选集设计，并实施原生 UI；同时覆盖未识别、缺图状态。
- [ ] 接入前确认 API 凭证配置、目标网络可达性和来源署名；实施后验证匹配准确性、缓存/失败回退与 TV DPAD 主路径。

## 已知边界与参考

- TMDB 提供搜索和资料查询，文件名解析与文件到作品/季/集的映射需要由项目实现。单集图片通常是剧照，不能假定每个条目都有完整图片。
- 当前网页使用示例数据，尚未接入 TMDB；未完成真实请求、识别率、电视网络或原生页面验证。
- 2026-10-30 已查阅官方文档：[搜索与详情流程](https://developer.themoviedb.org/docs/search-and-query-for-details)、[作品图片](https://developer.themoviedb.org/reference/tv-series-images)、[季度图片](https://developer.themoviedb.org/reference/tv-season-images)、[单集图片](https://developer.themoviedb.org/reference/tv-episode-images)。
- 免费条件按 [官方 FAQ](https://developer.themoviedb.org/docs/faq) 为非商业用途并注明来源；正式接入时复核当时条款。
