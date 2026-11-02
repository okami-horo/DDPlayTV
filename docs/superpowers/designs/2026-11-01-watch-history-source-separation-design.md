# 观看记录与媒体来源分离设计

日期：2026-11-01。阶段：**用户已授权局部清理，已实施并完成本地验证；未安装 TV。** 用户确认按作品聚合，随后指出首页已经承担观看记录职责，因此撤回新增独立历史页和管理页的建议。用户进一步确认清理播放历史及投屏在媒体库中的入口和占位逻辑，并要求“执行一下”。本轮保留首页记录、进度和投屏接收播放；不增加页面或首页展开。此前 L01 焦点留白修复保留，不在此处重新实施或交付。

## 结论与范围

首页已经实现作品聚合、原文件续播与详情 B 导航。本轮移除媒体库中的虚拟历史来源及初始化/导航耦合，同时清理投屏发送检查和来源配置残留；投屏接收所需的临时适配对象保留。仅隐藏卡片会留下反复创建占位行的逻辑，因此同时清理创建源头。

| 选择 | 结果与成本 | 建议 |
| --- | --- | --- |
| 只删除来源卡片 | 虚拟来源继续生成，错误归类仍留在初始化中 | 清理不足 |
| 复用首页，清理虚拟来源及其创建/导航代码 | 保留已实现的观看路径，解决错误归类，无需增加页面 | 用户已授权并实施 |
| 另建完整历史页及管理页 | 重复首页职责，扩大展示、焦点和导航维护范围 | 撤回原建议 |

此前用户对“按作品聚合”的选择只确认展示单位，不应被解释为已确认独立页面架构。

## 实施前的源码确认

最初分析基线为 `1d66ab393` 加首页与 L01 改动，以下记录的是实施前的问题及复用边界。执行基线已是包含上述修复的 `f6a2783c2`；本轮改动和验证见文末实施记录。

- [MediaLibraryEntity](../../../data_component/src/main/java/com/xyoye/data_component/entity/MediaLibraryEntity.kt) 原先定义固定 ID 4、类型 `OTHER_STORAGE` 的 `HISTORY` 虚拟媒体库；[MediaViewModel](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaViewModel.kt) 原先将最近播放 URL 写入它，再与真正的本地来源一起插入。首页 [CinemaFragment](../../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/CinemaFragment.kt) 与媒体库页面都会调用这段初始化。本轮删除该常量、URL 回填和历史行插入，仅初始化真实本地来源。
- [MediaLibraryRepository](../../../core_database_component/src/main/java/com/xyoye/common_component/database/repository/MediaLibraryRepository.kt) 对插入项维护 `catalog_source` 与版本状态；虚拟历史因此也被套入来源生命周期，但 [StorageFactory](../../../core_storage_component/src/main/java/com/xyoye/common_component/storage/StorageFactory.kt) 并不支持它作为可浏览存储。
- [MediaFragment](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt) 原先将这个类型特判跳转到 `PlayHistoryActivity`，是完整历史页唯一的生产路由调用者。本轮撤掉该跳转及“播放记录”来源副文案。首页“浏览媒体库”跳媒体库，观看足迹按作品去重后仍最多显示 8 项。
- [PlayHistoryEntity](../../../data_component/src/main/java/com/xyoye/data_component/entity/PlayHistoryEntity.kt) 已独立存入 `play_history`，真实身份为 `unique_key + storage_id`；[PlayHistoryDao](../../../core_database_component/src/main/java/com/xyoye/common_component/database/dao/PlayHistoryDao.kt) 独立提供历史读取与写入。历史数据不需要依附 ID 4 的虚拟来源。
- [PlayHistoryActivity](../../../local_component/src/main/java/com/xyoye/local_component/ui/activities/play_history/PlayHistoryActivity.kt) 当前默认类型是本地媒体，[PlayHistoryViewModel](../../../local_component/src/main/java/com/xyoye/local_component/ui/activities/play_history/PlayHistoryViewModel.kt) 又用 `OTHER_STORAGE` 表示“所有历史”。这是旧页面的参数耦合；当前不新增独立入口，也不为假设中的新入口重写这套查询。
- [MetadataHistory](../../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataHistory.kt) 已提供真实文件历史到作品的保守投影；[MetadataPlaybackRequests](../../../core_metadata_component/src/main/java/com/xyoye/common_component/metadata/MetadataPlaybackRequests.kt) 已把识别剧集历史接入整剧队列。没有理由另外实现一套作品身份、进度合并或播放解析。

## 确认的产品路径

1. **首页：承接观看记录与继续观看。** 复用已有主视觉、作品聚合足迹和详情入口，不再建议把“浏览媒体库”改为跳独立历史页的“全部记录”。不重复实现已完成的按作品展示逻辑。
2. **媒体库：来源与已入库作品。** 来源栏只包含可浏览的真实来源，不再出现播放历史，也不新增第二个历史来源。
3. **更早记录：按需要扩展首页。** 当前首页最多展示 8 个聚合项，这是已确认的实现限制。若确有查看更早记录的需求，优先考虑同页展开或继续加载；本轮不自动增加展开功能，更不以该限制作为新建页面的理由。超过展示范围的历史数据继续保留。
4. **旧管理代码：本轮保留。** 原逐文件页面及删除、清空、解绑代码保留，但媒体库不再提供其入口；这些动作尚未迁移到首页。管理入口的新位置或旧页面彻底退役均不在本次授权范围，不据此新增管理页或面板。

本轮沿用选定 Cinema 首页、媒体库与详情 B，无需为已撤回的独立历史页制作新稿。首页展开和管理面板只是可选方向，未获实施授权，不应因本设计文档而自动加入范围。

## 逻辑职责与改造边界

| 范围 | 建议调整 | 保留的边界 |
| --- | --- | --- |
| 来源初始化 | 只确保真实的本地来源存在，移除历史占位行创建及最近 URL 回填；引用消失后删除 `HISTORY` 常量 | 不直接删除整段本地初始化，真实本地来源仍被续播与元数据使用 |
| 来源列表与导航 | 统一排除历史和投屏来源；撤除历史跳转，特殊类型共用不可浏览提示 | 原始 DAO 读取、按 ID/类型读取保持兼容；既有 PC 过滤不变，九类真实来源保留 |
| 投屏入口残留 | 删除媒体库闲置的发送设备检查和配置页专用发送提示分支 | 保留 `SCREEN_CAST`、接收服务、播放解析和 `ScreencastStorage`；不删除接收能力 |
| 旧历史页 | 保留页面/查询/管理代码，仅撤掉媒体库入口；新管理位置另行设计 | `OTHER_STORAGE` 仍是旧枚举/未知值解码的兼容值，不删除历史数据，不声称管理动作已迁移 |
| 首页作品聚合 | 现有 `projectMetadataHistory` 与最近 8 项展示直接复用，无需重做 | 同一作品跨来源合并展示，但文件进度、唯一键、来源与多版本保留；歧义或文件已变更时沿用现有保守回退 |
| 数据与播放 | 沿用现有历史观察、详情路由和播放请求构建 | 不新建历史仓库，不改 Room 表、刮削范围、整剧队列、播放器或自动跳集策略 |

本轮已停止使用并隐藏旧虚拟来源行，保留兼容存量数据，数据库仍为版本 22。未物理删除 ID 4 或投屏来源旧行，也未调用真实来源的级联删除，更未清空 `play_history`。

## 状态与验收设计

| 状态/场景 | 预期 | 实施后的验证 |
| --- | --- | --- |
| 新装、升级、反复切换首页/媒体库 | 不创建或展示虚拟历史来源，真实本地来源仍可用 | 数据库与来源列表集成检查 |
| 存量记录与首页足迹 | 移除虚拟来源不清空历史；现有最近 8 项展示不受影响，其他记录仍被保存 | 同一组超过 8 部作品的夹具及数据保留检查 |
| 同剧多集、多来源、多版本、电影 | 一部作品一项，使用最近记录说明进度，详情与续播沿用实际来源/文件 | 投影单测与真实详情/队列回归 |
| 未识别、关联更新、文件改变/来源失效 | 文件记录仍可见，不错误挂到作品；可恢复的记录正常继续，失败可读且可返回 | 投影、错误提示、恢复和返回焦点检查 |
| 旧管理动作 | 保留旧页面及管理代码，入口撤除；新位置尚未实现 | 静态核对旧类/查询未改，不将其记作新入口已交付 |
| 加载、空、错误、放大字体 | 无虚假空记录；空态可返回/浏览媒体；长片名与信息完整；焦点始终可见 | Web 状态稿、原生对照与字体 1.0/1.3 |
| 遥控器路径 | 首页足迹→作品详情→播放→分层 BACK；媒体库真实来源仍可进入与返回 | 任务模拟器纯 DPAD；TV 验收需另有当前授权 |

以上是设计覆盖要求；本轮局部清理的实际证据如下。首页聚合、电影/多版本、超过八项展示范围、加载及放大字体没有新增实现，不因本次通过而视为重新验收。既有 Cinema、详情 B、文件浏览 C 和 L01 的证据仍各归原账本。

## 实施与本地验证（2026-11-01）

生产代码仅改四个文件：`MediaLibraryEntity.kt`、`MediaViewModel.kt`、`MediaFragment.kt`、`StoragePlusActivity.kt`。来源展示筛选留在 `MediaViewModel`；原始库查询、存储工厂、历史保存/投影、整剧队列和投屏接收链路未修改。没有数据库迁移、新依赖、版本递增或提交。

新增 [MediaSourceCleanupInstrumentedTest](../../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/MediaSourceCleanupInstrumentedTest.kt)，使用独立内存 Room 库验证真实初始化和来源观察。原实现两项均按预期失败：新库生成 ID 4；升级夹具把历史占位带入列表，并改写其 URL。修复后两项均通过。

| 覆盖 | 已验证结果 | 证据 |
| --- | --- | --- |
| 新库反复初始化 | 三次初始化仅创建真实本地库，不创建历史 `catalog_source` | 新增测试第一项 |
| 升级兼容与九类来源 | 十二种存量类型中仅九种真实来源进入列表；原始来源行、历史身份、进度、弹幕/字幕路径均保留 | 新增测试第二项 |
| 添加来源及配置 | 八种可新增来源，加自动本地来源共九类；FTP/WebDAV 配置、全部选项、MENU/取消/BACK 与焦点恢复通过 | `MediaSourceEntryInstrumentedTest` |
| 本地/网络授权与返回 | 视频和音频授权齐备后可进本地库；拒绝视频授权时仍可进网络目录并返回原焦点 | `MediaSourcePermissionNavigationTest` 两项 |
| 首页记录与播放 | 未识别文件可播，真实空态有媒体库入口；失败不清进度并恢复焦点；Media3/mpv 均完成首页→整剧详情→播放→分层返回 | `WatchHistoryInstrumentedTest` 四项 |
| 媒体库、搜索和设置 | 来源页、搜索过滤、顺序播放设置切换及恢复、DPAD/BACK 通过 | `CinemaPrototypeInstrumentedTest` 一项 |
| 接收投屏 | 空闲接收及播放中替换均进入真实播放请求，发送地址/请求头正确，复用播放器 | `PlaybackEntriesInstrumentedTest` 投屏项 |

构建和检查：

- 原实现 Debug/test 构建：`BUILD SUCCESSFUL in 41s`。两项红灯测试为行为失败，不是环境或编译失败。
- 完整门禁：Debug/test APK、`testDebugUnitTest`、app/local/storage/data 的 Debug lint、依赖治理与限定格式检查最终 **`BUILD SUCCESSFUL in 4m 45s`**。JUnit XML 合计 **505 通过、2 项既有跳过、0 失败/错误**。
- 初次完整门禁因新增测试链式调用换行失败（`BUILD FAILED in 4m 12s`），修正后通过。后续投屏测试清理代码也触发同一格式规则（`BUILD FAILED in 16s`），最终 AndroidTest 构建、app Debug lint 与限定格式检查 **`BUILD SUCCESSFUL in 13s`**。
- 首轮十项模拟器场景为八项通过、两项失败，耗时 67.365s。核对任务库和系统权限窗口确认：投屏测试遗留 `idle-cast`/`foreground-cast` 两条历史，空历史夹具因而不为空；本地入口测试只授予视频权限，缺少应用同样要求的音频权限。仅补齐测试自身历史清理及授权前提，没有修改产品权限或历史逻辑。
- 受影响的投屏、未识别/空首页、本地入口三项复测 **`OK (3 tests)`，11.751s**；拒绝视频授权的网络入口单独通过 **`OK (1 test)`，1.572s**。合并原先已通过且未改动的场景，共 **11 项不同场景全部取得通过证据**，不是一次十一项整批运行。

命令和完整日志位于 `.tmp/source-cleanup/`，Gradle 沿用 `.tmp/watch-history/gradle.ps1` 和本地依赖 init；限定格式脚本仅覆盖本轮八个 Kotlin 文件。模拟器为任务只读 AVD `emulator-5576`，独立 ADB `5077`，API 34、1920×1080、320dpi、字体 1.0。已实际查看[来源栏截图](../../../document/media-library/source-entry-cleanup-2026-11-01/library.png)和[新增来源截图](../../../document/media-library/source-entry-cleanup-2026-11-01/source-chooser.png)，确认来源栏不显示历史/投屏，菜单保留真实来源和可见焦点。结束前字体读回 1.0，任务模拟器及独立 ADB 已关闭，既有 5037/5053 进程保持运行。

来源、APK、截图哈希及各次运行结果见[验证身份](../../../document/media-library/source-entry-cleanup-2026-11-01/results.json)。本轮没有新增视觉稿或改布局；未复测 1.3 字体、真实云账号和物理电视，未构建/安装 Release、操作 TV 或递增版本。首页展开、旧管理动作新位置和物理删除兼容来源行仍未实施。
