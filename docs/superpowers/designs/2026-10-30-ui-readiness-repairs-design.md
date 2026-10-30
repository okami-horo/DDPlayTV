# 新 UI 改造前的逻辑修复设计

日期：2026-10-30；本地验证完成于 2026-10-31。状态：**已实施并完成本地验证**。实际结果和物理电视／真实服务等未测边界以[修复计划执行记录](../plans/2026-10-30-ui-readiness-repairs.md)为准。

源码核对基线：`9944e9eaf8a89da5ec15da9196ae68bc5e18ec70`，开始规划时工作区干净。本文保留行为契约与问题来源；具体实现、测试结果和设备限制以[修复计划](../plans/2026-10-30-ui-readiness-repairs.md)为准。

## 1. 目标与范围

先消除会被新首页、海报墙、选集与播放器面板继承的身份、异步、恢复及页面生命周期问题，再实施新的布局。沿用当前模块、稳定播放描述符、活动会话、传输租约、DpadRecyclerView 和 Media3/mpv 字幕所有权。

本次设计包括：历史归属隔离、浏览错误传播、查询与目录状态、权限入口、延迟快进、弹幕结果归属、续播位置、页面订阅与弹窗事件，以及真实新增来源选择器的诊断和修复。

TMDB、作品／季／集／文件关联、元数据缓存、继续观看首页查询与新布局仍属于[媒体库后续工作](../../../document/media-library/tmdb-metadata-followup.md)。本计划不新增已看完字段或完成阈值，不恢复退役功能，不升级依赖或重写全局状态框架。

## 2. 已确认的续播决策

用户于 2026-10-30 确认：**优先恢复上次位置；“已看完”与是否续播分开判断。**

- 移除视图控制器中 `>=90%` 不恢复的规则；90%、95% 等有效位置也应恢复。
- 保留当前进度输入的来源：普通播放使用已准备来源的历史；进程恢复使用快照；配置重建使用挂起进度；内核重试使用当前后端进度。后面的视图接线不得重新读取历史覆盖显式输入，包括合法的 `0`。
- 使用现有 `PlaybackPositionConstraints.normalize()`：负数归零，已知正时长限制到末尾前，可确认不可 seek 的来源不执行定位，未知时长不构成丢弃有效进度的理由。
- 位置在当前后端实际准备好、seek 能力可靠时一次性应用；旧来源和旧后端的准备回调不得影响当前来源。
- “已看完”的展示分类、阈值、自动从头重播及首页筛选没有在本次选定，不通过修复续播暗中新增这些规则。

## 3. 已核对问题与归属

| 问题 | 源码入口 | 所属任务 |
| --- | --- | --- |
| 兼容查询跨来源取历史并改写 `storageId` | `StorageFileFragmentViewModel.getHistory`、`PlayHistoryDao`、`RemoteStorageFile.uniqueKey` | R1 |
| 协议列目录失败被转换为空列表 | `WebDavStorage.listFiles`、Remote／FTP／SMB／Alist 浏览分支 | R2 |
| 本地重复过滤、旧远程查询覆盖新结果、目录快照读取共享可变状态 | `StorageFileFragmentViewModel.searchByText`、`StorageFileActivity.onDirectoryOpened`；番剧／磁链搜索 ViewModel | R3 |
| 网络库及历史统一依赖本地读取权限 | `MediaFragment` 的来源点击分发 | R4 |
| 待提交快进缺少来源／后端归属和生命周期清理 | `TvVideoController`、`VideoController`、播放器后端 apply／detach | R5 |
| 弹幕下载无来源版本，自动／手动结果竞争，搜索失败已被下层抹平 | `PlayerDanmuViewModel`、`PlayerActivity.initListener`、`DanmuQueryImpl.search` | R6 |
| 控制器的 90% 规则覆盖已准备进度，未知时长提前清空恢复点 | `VideoController.considerSeekToLastPlay`、`PlayerActivity.updatePlayer` | R7 |
| View 订阅绑定 Fragment；筛选缓存回放直接打开弹窗；历史查询不切换 | `AnimeEpisodeFragment`／ViewModel、`PersonalFragment`、`SearchMagnetFragment`／ViewModel | R8 |
| 新增来源选择器空白且没有默认焦点；实施时确认九项数据存在，但列表视口高度为 0 | `MediaFragment.showAddStorageDialog`、`BottomActionDialog`、`BaseBottomDialog` | R9 |

R8 的补充核对：`episodeHistoryFlow` 使用 `flatMapConcat` 订阅持续的 Room Flow；更新剧集 ID 后旧查询不会正常结束，应切换到当前 ID 集合对应的查询。该点是本轮设计时新增确认的问题。

R9 规划时只有[此前设备观察](../plans/2026-10-30-global-settings.md#剩余边界)。实施期间已在当前 Debug 包复现：滚动容器以未限定高度测量 DpadRecyclerView，九项数据对应的实际视口高度为 0。修复按实际行内容测量并限制可用高度，宽度变化时重新测量；不采用固定行高或无限重试。红绿证据和完整入口覆盖见执行账本。

## 4. 修复契约

### 4.1 历史身份与旧记录回填

正常历史继续以 `(uniqueKey, storageId)` 隔离，优先使用当前来源已有记录。旧记录认领仅用于 `storage_id IS NULL`、非空键、相同类型且有可信来源证据的唯一候选。使用当前真实来源 URL／已保存原始地址等已有证据验证，不以相同 Hash、路径或媒体类型独立证明来源。

数据库事务内重查精确记录、检查候选、条件更新并重读结果；更新条件同时限定记录 ID 和 `storage_id IS NULL`。不再用 `INSERT REPLACE` 搬动历史。其他来源已归属、多候选、动态地址无法验证或并发冲突时保留原记录，不猜测、不删除、不批量重建媒体键。

由数据库保证原子性，存储侧提供可信来源事实。沿用现有 PC 无 Hash／分组切换的有边界兼容，不将本次修复扩散到播放解析器所有回填入口。无需 schema 升级。

### 4.2 浏览结果、查询与目录归属

保留现有 `Storage.openDirectory(...): List<StorageFile>` 契约：成功空目录返回空列表，协议失败向上传播，协程取消重新抛出。仅调整浏览失败分支；不能靠全局启用 `throwPlaybackErrors` 改变播放解析、`openFile/pathFile` 的全部语义。

页面对首次加载、内容、成功为空、失败、需重新登录、保留内容的刷新失败作明确区分。使用当前页面的局部状态，不新增全局 loading 管理器。旧请求的结果、错误和 `finally` 均不能结束或覆盖新请求状态。

浏览请求身份至少包含来源、请求目录、查询条件与递增版本。成功结果携带其请求目录和完整原始列表；Activity 不再从可能已被其他 Fragment 改变的 `storage.directory/directoryFiles` 推断该结果的归属。会修改同一 Storage 或分页游标的操作在当前浏览所有权内串行，结果提交仍需核对版本；仅取消协程不能代替归属检查。

原始目录数据与显示列表分离，搜索、排序和历史标记从原始数据派生；过滤结果不再成为完整目录快照。清空查询也使旧搜索失效。刷新失败保留同目录旧列表及与之匹配的分页状态，搜索结果不混入目录分页。

番剧、季度和磁链搜索分别在现有 ViewModel 内冻结完整参数、管理当前 Job／版本，无需为相同的几行机制建立通用查询框架。

### 4.3 播放中的延迟操作与弹幕结果

快进预览绑定当前来源及当前后端生命周期。切源、后端 apply／detach、同源内核重试、释放和销毁均取消待提交操作并清理预览。提交时再次验证归属；同源正常按键累计与 800ms 合并行为保留。同源重试可能保持 `sourceRevision`，因此不能只校验版本号。

弹幕自动匹配和手动选择使用已有 `PlaybackSessionVersion`，并增加该来源内的请求序号。手动选择使此前自动匹配失效，同来源连续选择也只接收最新结果。后端 `apply` 期间显式传递其准确版本，不能读取尚未更新的 ViewModel runtime 推测版本。

成功结果保持为可核验、可取走的单次 pending 结果，携带版本、请求序号及消费身份。Activity 在主线程核对并取走后添加轨道；后续持久化也必须绑定同一目标来源。真正换源使旧结果失效；同版本配置重建保留尚未消费的结果，已消费结果不回放。旧 Activity 销毁不得清空新 Activity 仍使用的保留 ViewModel。

弹幕搜索需要保留真实失败语义。下层目前把请求失败转为空列表，故需局部增加保留 `Result` 的入口；其他依赖空列表回退的匹配调用保留原语义。面板表达查询中、内容、空结果、错误及下载中／失败；成功后关闭，失败保留重试入口和焦点。

### 4.4 权限、View 生命周期与弹窗

MediaStore 本地库在实际访问边界申请媒体权限；网络来源与历史页面可直接进入。SAF 来源使用其树 URI 授权，播放本地历史项等实际操作自行检查对应访问能力，不把媒体读取权限等同于 SAF 权限。

所有操作 View 的订阅绑定 `viewLifecycleOwner`，同一 Fragment 的 View 销毁后旧订阅结束。已存在的 `stateIn` 不重复增加共享层；渲染不能因重新订阅额外发起刷新、写库或播放。分集历史按最新 ID 集合切换查询。

分类与字幕组列表仍是缓存状态，打开弹窗是用户动作。使用单消费者、可保留未消费动作的局部事件通道，避免缓存回放直接 `.show()`；动作带目标语义并核对当前页面，已关闭弹窗不因重建重开。节点保存后按当前配置重新判断，不保留旧错误永久触发。

### 4.5 新增来源选择器

先从真实首页复现，分别取得窗口焦点和首次布局后的动作数、adapter 数量、实际 View 类型、子节点数、尺寸、可见性与当前焦点，区分数据、inflate、测量与初始焦点时序。只修已证实的责任点，不预设固定高度或无限 `post/retry`。

完整验收必须是纯 DPAD：主页触发 → 九种来源可见且可遍历 → 进入编辑页 → BACK → 恢复触发控件焦点 → 再打开／取消。MENU 快捷入口另测。测试不能直接给目标 `requestFocus()` 绕过入口，也不能用直接启动编辑页的通过结果替代。

## 5. 最小改动与验收边界

按职责增加必要的小型局部状态、条件查询和测试注入点；不创建跨全部功能的 Repository／UseCase／事件总线，不全仓替换 LiveData，不重建导航框架。新接口必须消除本文对应的旧责任或覆盖真实测试边界。

每项逻辑修复先建立可失败的行为回归，再修改并运行相关测试。最终覆盖实际接线、返回栈、设置持久化、错误恢复和纯 DPAD 主路径；协议夹具、AVD 和物理电视证据分别记录。

前一轮只读审查运行的 `verifyModuleDependencies verifyPlaybackBoundaries --offline --console=plain` 为 `BUILD SUCCESSFUL in 18s`，日志 `.tmp/ui-readiness-governance.log`。这是未改业务代码的边界基线；实施后的 Gradle、仪器测试和纯 DPAD 证据统一记录在执行账本中，不能相互替代。
