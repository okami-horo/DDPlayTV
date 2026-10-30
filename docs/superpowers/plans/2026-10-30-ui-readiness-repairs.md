# 新 UI 改造前的逻辑修复任务

规划日期：2026-10-30；完成本地验证：2026-10-31。状态：**R1–R10 已实施并完成本地验证；物理电视、真实外部服务及完整杀进程恢复验收仍保留原有边界**。

设计与行为契约见[修复设计](../designs/2026-10-30-ui-readiness-repairs-design.md)。源码核对基线为 `9944e9eaf8a89da5ec15da9196ae68bc5e18ec70`。以下执行记录对应本次未提交工作区，不自动提交。

## 执行顺序

| 任务 | 优先级／依赖 | 可验证结果 |
| --- | --- | --- |
| R1 历史来源隔离 | P1，独立 | 浏览另一来源不搬走已有历史，旧记录仅安全认领 |
| R2 浏览失败传播 | P1，独立 | 真空目录、失败、登录失效可区分，刷新失败保留数据 |
| R3 查询与目录状态 | P2，依赖 R2；浏览集成在 R1 后 | 搜索可逆、最新请求生效、旧目录结果不污染新目录 |
| R4 权限路由 | P2，独立 | 拒绝本地权限仍可进入网络库和历史 |
| R5 待提交快进归属 | P1，独立 | 换源／换后端后不执行旧快进 |
| R6 弹幕请求与消费 | P1，播放器接线在 R5 后 | 晚到弹幕不绑错集，重建不重复消费，失败可重试 |
| R7 续播位置应用 | P1，播放器接线在 R6 后 | 高进度仍恢复，显式快照／当前进度不被旧历史覆盖 |
| R8 生命周期与弹窗 | P2，磁链改动在 R3 后 | View 重建不增加动作，缓存不自行打开弹窗 |
| R9 新增来源完整入口 | P1 诊断，完整回归在 R4 后 | 真实主页到选择器／编辑页／返回的纯 DPAD 路径通过 |
| R10 集成验证与记录 | 依赖 R1–R9 | 构建、测试、边界、焦点及恢复证据相互对应 |

存储、播放器、页面三条调查可并行；共同修改 `StorageFileFragmentViewModel`、`PlayerActivity`、`SearchMagnetFragmentViewModel` 时串行整合。R9 可提前复现；若未复现，只记录版本、场景与证据，不能将未知根因写成已修复。

## R1：安全认领旧历史

- [x] 在 `core_database_component/.../database/dao/PlayHistoryDao.kt` 与 `repository/PlayHistoryRepository.kt` 替换危险的按键＋类型兼容查询；`storage_component/.../storage_file/StorageFileFragmentViewModel.kt` 调用安全入口。
- [x] 按设计实现事务内精确记录优先、唯一可信的无来源候选、条件 UPDATE 与重查；不使用 REPLACE，不修改已归属其他来源的记录。来源无法证明时保留旧记录而不自动关联。
- [x] 新增 `core_database_component/src/test/java/com/xyoye/common_component/database/dao/PlayHistoryLegacyClaimTest.kt`，覆盖两个库同 Hash、已归属他库、可信旧记录、多候选、来源证据不足、并发认领、目标记录已存在；断言 ID、进度、时间与轨道绑定没有被替换。
- [x] 回归现有 `RoomSchemaMigrationGateTest` 和 `core_storage_component/.../source/handoff/PlaybackSourceResolverTest.kt`，保留旧 PC 无 Hash／分组切换的兼容与身份拒绝用例。

验收：只浏览 B 库不能改变 A 库任何历史；不新增数据库版本或批量身份迁移。

## R2：让浏览失败到达页面

- [x] 核对 `core_storage_component/.../storage/impl/` 中 WebDAV、Remote、FTP、SMB、Alist 的列目录／根目录失败分支，让失败向上传播，取消重新抛出；保留成功空列表及现有播放严格模式边界。
- [x] 在 `StorageFileFragmentViewModel`／`StorageFileAdapter` 中表达首次失败、需登录、成功空目录、内容刷新失败；使用 R3 将接续的页面局部状态，移除被其替代的重复错误字段。
- [x] 核对百度／115 分页刷新失败的游标和旧列表一致性；失败不得留下旧内容＋错误新游标。
- [x] 新增 `core_storage_component/src/test/java/com/xyoye/common_component/storage/StorageBrowsingFailureTest.kt`，复用现有可注入协议客户端或本地 HTTP 夹具，验证真实空目录、连接失败、鉴权失败、取消与恢复；避免依赖真实账号。
- [x] 新增 `storage_component/src/test/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragmentViewModelTest.kt`，覆盖首次失败、有旧内容的刷新失败、重试成功、登录失效和分页失败。只增加此类测试需要的 `coroutines_test`／Robolectric 依赖及最小注入点。
- [x] 回归 `FtpPlaybackTransportTest`、`SmbPlaybackTransportTest`、`PlaybackTransportLeaseTest` 与 `PlaybackSourceResolverTest`，确认浏览错误修复未改变传输释放或播放失败分类。

验收：断网不会显示为成功空目录；失败后可重试并保持返回与焦点入口。

## R3：可逆搜索与最新请求归属

- [x] 存储浏览保留原始完整目录数据，搜索／排序／历史标记只派生显示列表；清空查询使旧请求失效并恢复当前目录。
- [x] 请求捕获来源、目录、完整查询与版本；旧结果、旧错误及旧 `finally` 均不修改当前状态。同一 Storage 的可变目录／分页操作在浏览所有权内串行。
- [x] 调整 `StorageFileFragment.kt` → `StorageFileActivity.onDirectoryOpened()` 的局部接线，让成功结果携带请求目录与原始快照，不再从共享当前目录反推。旧目录响应不得覆盖新路由快照或播放队列输入。
- [x] 在 `SearchAnimeFragmentViewModel`、`SearchMagnetFragmentViewModel` 和 `AnimeSeasonViewModel` 分别冻结查询参数并保留当前 Job／版本；使用页面局部加载／结果／错误状态，不改造全局 `BaseViewModel`。
- [x] 扩展存储 VM 测试：`abc→a`、清空、A/B 逆序、清空后旧响应、切目录后旧响应、搜索中排序／历史更新、取消不报错、旧请求结束不关闭新 loading。
- [x] 在上述三个番剧／磁链 ViewModel 的同包 `src/test/java` 新增对应 `*Test.kt`，通过受控挂起响应检验参数快照与逆序结果，使用函数依赖／已有接口作为测试边界。

验收：输入、当前目录、结果、错误和 loading 始终属于同一请求；目录原始列表与搜索结果不能互相覆盖。

## R4：按实际访问请求权限

- [x] 调整 `local_component/.../ui/fragment/media/MediaFragment.kt` 的来源点击分发：本地 MediaStore、SAF 与网络来源采用各自访问能力，不统一请求媒体权限。
- [x] 网络库和 Room 历史直接导航；检查本地历史播放、字幕／弹幕文件选择等实际本地操作的现有拒权恢复路径，避免移走入口检查后遗漏能力检查。
- [x] 新增 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/smoke/MediaSourcePermissionNavigationTest.kt`，覆盖拒绝本地权限后进入网络库／历史，以及本地访问授权恢复；纯 DPAD 返回并恢复入口焦点。
- [x] 权限撤销在宿主启动测试 runner 前准备，结束后恢复；不在测试进程内撤销自身权限并把进程被杀当成业务失败。

验收：拒绝本地权限不影响远程导航，SAF 不被普通媒体权限替代。

## R5：取消旧后端的待提交快进

- [x] 修改 `player_component/.../controller/base/TvVideoController.kt`、`controller/VideoController.kt` 和 `ui/activities/player/PlayerActivity.kt` 的生命周期接线。
- [x] 快进开始绑定当前来源和后端生命周期；apply／detach、手动切集、原生续播、同源内核重试、释放／销毁均清理 Runnable、累计位置和预览。提交时再次验证归属，不只检查 `sourceRevision`。
- [x] 新增 `player_component/src/test/java/com/xyoye/player/controller/base/TvVideoControllerTest.kt`，使用 Robolectric 时钟与假播放器验证同源累计、799ms 未提交／800ms 提交、换源、同源换后端、释放及晚到回调；不启动真实内核。

验收：原有合并快进行为保留，切换后旧预览不 seek 新后端。

## R6：弹幕结果只绑定目标来源并消费一次

- [x] 修改 `PlayerDanmuViewModel.kt`、`PlayerActivity.kt` 与 `SearchDanmuView.kt`。激活时显式传入准确的 `PlaybackSessionVersion`，统一自动／手动请求序号；手动选择使旧自动匹配失效。
- [x] 结果携带来源版本与消费身份；主线程校验、取走后才添加轨道，并核对历史保存的目标来源。真正切源清旧结果，同版本 View 重建保留未消费结果；销毁旧 Activity 不清新 Activity 的保留 VM。
- [x] 在 `core_storage_component/.../utils/danmu/` 的现有搜索接口局部保留 `Result`，维持旧列表 API 的兼容回退。面板区分失败／空结果，下载失败可重试，成功后关闭；取消必须传播。
- [x] 新增 `player_component/src/test/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModelTest.kt` 与 `player_component/src/test/java/com/xyoye/player/controller/setting/SearchDanmuViewTest.kt`。
- [x] 覆盖 A 集下载→切 B→A 晚到、取消不合作响应、自动晚于手动、同来源多次选择、STOPPED 期间完成、重建前后单次消费、失败重试、搜索逆序／清空／失败与空结果区分。
- [x] 在现有 `app/.../playback/PlaybackEntriesInstrumentedTest.kt` 或同目录新增用例验证实际轨道添加和历史绑定；只测 VM 丢弃结果不足以证明接线安全。

验收：错误来源结果既不进入渲染，也不写入当前集历史；不因重建重复加载。

## R7：统一应用续播位置

- [x] 沿用 `PlaybackProgress` 与 `PlaybackPositionConstraints`。在 `PlayerActivity` 的后端／`preparingProgress` 现有接线处，准备就绪后一次性应用位置，移除 `VideoController` 的独立历史恢复责任和 90% 否决；倍速逻辑保持原归属。
- [x] 保留历史、进程快照、配置挂起进度、当前后端重试进度的既有输入优先级。显式 `0` 不回退旧历史；未知时长保留位置，旧后端回调不能完成当前恢复。
- [x] 新增 `PendingPlaybackResumeTest.kt`、`Media3PreparedCallbackTest.kt`，覆盖高进度、显式零值、未知时长、越界、不可 seek、来源失效及首帧／READY 顺序；全量回归现有 `PlaybackRecoveryTest.kt`、`PlaybackBackendLifecycleTest.kt`。重建／内核重试由实际后端仪器测试补充。
- [x] 在控制器／实际 adapter 接线测试中断言最终 seek，不能仅验证纯归一化函数。
- [x] 复用 `PlaybackHandoffInstrumentedTest.kt`、`PlaybackEntriesInstrumentedTest.kt` 的长视频夹具，分别验证 Media3/mpv 的 90%／95% 恢复、重建和临时内核重试，并确认全局内核设置未被改写。

验收：有效高进度按已确认规则恢复；不新增完成阈值、数据库字段或自动重播政策。

## R8：修正 View 订阅与弹窗事件

- [x] `AnimeEpisodeFragment.kt` 与 `PersonalFragment.kt` 中操作 View 的订阅改用 `viewLifecycleOwner`；渲染不额外触发业务动作。
- [x] `AnimeEpisodeFragmentViewModel.episodeHistoryFlow` 按最新剧集 ID 集合切换持续查询，取消旧 Room Flow；复用已有 `stateIn`，不再新增共享层。
- [x] `SearchMagnetFragmentViewModel.kt`／`SearchMagnetFragment.kt` 保留分类缓存，单独管理一次性打开动作；关闭后不因重建重开，配置节点后不重放旧错误。与 R3 的当前请求状态整合。
- [x] 新增同包 `AnimeEpisodeFragmentViewModelTest.kt` 与 `SearchMagnetFragmentViewModelTest.kt`（后者与 R3 共用），覆盖 ID 集合切换／取消、已消费动作不回放、缓存复用和节点修复。
- [x] 新增 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/anime/AnimeViewLifecycleInstrumentedTest.kt` 与 `SearchDialogLifecycleInstrumentedTest.kt`，覆盖同一 Fragment 的 detach／attach 或 back-stack View 重建；不能只测 Activity recreate。

验收：一次用户动作只有一次刷新／导航／弹窗；旧 View 不再更新，刷新剧集后历史来自当前 ID 集合。

## R9：复现并修复新增来源完整入口

- [x] 从真实 `TvMainActivity` 用 DPAD 进入“添加媒体库”，保存截图、UI 树以及窗口焦点／首次布局的诊断数据，核对九项数据是否进入可见树。
- [x] 在 `BottomActionDialog.kt`、`BaseBottomDialog.kt` 或相应布局中只修证据支持的责任点；若是测量问题先解决尺寸，若是初始焦点时机则使用现有布局／选择完成回调，避免无限重试。
- [x] 扩展 `app/src/androidTest/java/com/xyoye/app/quality/DialogDpadFocusSmokeTest.kt`：九项动作、长列表滚动、重复打开／取消与 BACK 恢复焦点。
- [x] 新增 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/MediaSourceEntryInstrumentedTest.kt`，覆盖真实主页 → 选择器 → FTP/WebDAV 编辑页 → 返回 → 再次打开／取消，以及 MENU 入口。
- [x] 不通过测试直接 `requestFocus()` 或直接启动编辑页隐藏入口缺陷；同时回归共享弹窗的默认焦点、长内容和取消可达性。

验收：完整新增路径纯 DPAD 可完成；未复现时记录证据与未闭环状态，不凭静态假设标记修复。

## R10：集成、门禁与交付证据

- [x] 运行新增行为回归，保留代表性真实红绿与故障注入证据，分别说明范围；不把故障注入称为原始版本复现，不靠重复实现逻辑的测试代替行为检查。
- [x] 使用[项目本地环境](../../../document/project_local_dev_env.md)运行 Debug／Release／测试 APK、全量 JVM、lint、变更范围格式检查与模块／播放边界门禁。格式检查沿用已有变更范围做法，全仓历史基线问题单列，不能扩基线隐藏新增问题。

```bat
scripts\dev-env\gradlew-local.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries --console=plain
```

仅在依赖已缓存时添加 `--offline`；需要本机已验证 init 时按既有环境说明记录实际使用参数，不把临时环境问题解释为测试通过。

- [x] 检查 `adb devices -l` 和启动完成状态，再执行相关设备测试；离线直接 runner 与 `connectedDebugAndroidTest` 结果分别记录。
- [x] 纯 DPAD 验证启动、拒权后的来源／历史导航、新增来源完整入口、浏览失败重试、快进、设置往返与持久化、页面返回／退出；搜索逆序／清空由受控 ViewModel 回归，双内核恢复、切集及弹幕晚到由实际 Activity／后端仪器测试验证。网络夹具与真实服务覆盖分开记录。
- [x] 回归 Media3 原生续播与 mpv 手动切集、轨道绑定、临时重试、加密恢复快照写入及重入；复用真实覆盖到的[既有验收项](../../../document/player/playback-validation-followup.md)。Activity 重建、重入和显式零值输入不等同于杀进程恢复，未覆盖的完整场景继续保留。
- [x] 每条 Gradle 证据读取尾部并记录 `BUILD SUCCESSFUL`／`BUILD FAILED`；设备结果记录内核、AVD／真机、用例范围及截图。物理电视与外部服务未测项明确保留。
- [x] 独立复核后更新本账本的实际结果、设计状态、维护文档与 memory-bank；不自动提交。

## 实际实现与复核

- R1：事务内精确来源优先，仅认领唯一、无来源、同类型且 URL 完全相同的旧记录。条件 UPDATE 保留记录身份和轨道绑定，不搬走其他库历史，不变更 schema。
- R2/R3：协议失败／取消传播；分页失败回滚游标；Activity 共享 Mutex 串行访问可变 Storage。请求捕获原实例、目录和版本，完整目录与搜索结果分离。首次加载期间保留最新查询，首次失败后输入／清空不能抹掉错误。FTP 负回复不再伪装空目录；WebDAV 排除等价根路径／末尾斜杠对应的目录自身。
- R4：本地库入口申请媒体权限；远程库、Room 历史和 SAF 按各自访问能力处理。
- R5/R6：保留 800ms 快进合并，来源／后端切换清理旧任务及预览；弹幕按来源版本和请求序号竞争，手动优先，pending 只取走一次。搜索失败与空结果分开，下载失败可重试；绑定立即更新当前来源并串行持久化，避免同源连续选择逆序写入。
- R7：显式进度在真实后端准备后应用，0 和 90%／95% 均有效，不被旧历史覆盖。Media3 首帧／READY 都进入一次性的 prepared 通知；mpv 准备时读取真实时长和 seek 能力。准备期间接受的新 seek 同步更新保留目标，完成 seek 后退出准备进度保护，避免暂停重建被初始 0 覆盖。
- R8：View 订阅归属 `viewLifecycleOwner`，剧集历史切换当前 ID 集合，个人页以用户会话为唯一刷新源；移除被替代的 LoginObserver 接线。磁链分类缓存与一次性打开事件分开，同 Fragment View 重建不重放弹窗。
- R9：确认列表视口为 0 的测量根因，按实际内容测量并限制高度，宽度变化时重测。未采用固定行高或无限重试。
- 三条既有调查的复核已完成。独立复核发现的空态 DiffUtil 单例不重绑、首次错误被查询清除、旧隐藏页面仅 STARTED 而未取消请求均已修复：局部重绑空态、保留失败、在 `onPause` 取消。设备另证实 AppCompat 提前截获 MENU；刷新按键移到 Activity 分发入口，删除列表中无效的重复监听。

## 构建与 JVM 证据

最终命令（项目本地 JDK／SDK／Gradle 缓存）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest testDebugUnitTest lint ktlintCheck verifyModuleDependencies verifyPlaybackBoundaries -I .tmp/handoff14-local.gradle -I .tmp/ui-repairs-local.gradle -I .tmp/ui-repairs-style.gradle --offline --continue --console=plain
```

已读取 `.tmp/ui-repairs-final-validation2.log` 尾部：**BUILD SUCCESSFUL in 2m 1s**，1720 个任务。Debug／Release／测试 APK、全量 JVM、完整 lint、变更范围 ktlint 和两项边界门禁通过。469 项 JVM 用例中 **467 通过、0 失败、0 错误、2 跳过**；跳过项为外部 FTP／SMB 协议服务夹具。汇总见 `.tmp/ui-repairs-final-jvm-summary.json`；lint XML 错误数为 0。此前综合检查 `.tmp/ui-repairs-final-validation1.log` 为 `BUILD SUCCESSFUL in 9m 24s`，之后追加的两处接线修复以上述第二次检查为准。

新增回归共 65 项：历史认领 7、协议浏览 12、存储页面 17、番剧／季度／磁链／历史查询 9、个人会话 1、弹幕查询结果 3、快进 3、弹幕 VM／面板 8、待应用位置 3、Media3 准备顺序 2。全量计数包括 repository 模块的既有用例，同时回归 Room 迁移、身份解析、传输释放、活动会话和恢复规则。

依赖下载曾因 `Permission denied: getsockopt` 失败，普通与升级权限均相同，**并非自动审批拒绝**。协程测试原版本已有缓存，临时 `.tmp/ui-repairs-local.gradle` 仅将对应工件映射到 `.tmp/ui-repairs-maven`；未升级项目依赖。Robolectric 使用既有离线 init，格式检查只针对变更文件并沿用项目不要求 trailing comma 的设置；未扩大全仓基线。

## 设备、DPAD 与截图证据

API 34 `Pixel_5_API_34`、SwiftShader／ARM64 translation、非 root、普通系统 UI mode `0x11`；应用本身采用 TV UI。以下为 **21 个去重后的直接 runner 用例**，不将不同轮次重复累计：

| 范围 | 用例数 | 最终有效证据 |
| --- | --- | --- |
| Media3／mpv 高进度、显式 0、准备期间新 seek、重建、临时内核重试、队列切集、晚到快进／弹幕与实际 Room 绑定 | 6 | `.tmp/ui-repairs-device-final2.log` |
| 加密恢复位置写入、重入／重建及旧内核偏好兼容 | 4 | 同上 |
| WebDAV 首次失败→重试→成功空目录→一文件目录→刷新失败保内容→恢复→返回 | 1 | 同上；本地 NanoHTTPD 夹具，真实 MENU／DPAD 操作 |
| 同一 Fragment detach／attach、旧 View 订阅结束、分类弹窗不回放 | 3 | `.tmp/ui-repairs-device-final1.log` 中三项通过，之后相关代码未变 |
| 九项选择器、30 次开关、30 项滚动；真实主页→FTP／WebDAV 编辑→返回、MENU、取消及焦点恢复 | 4 | 同上中四项通过，之后相关代码未变 |
| 全局分组设置重建／重入持久化 | 1 | 同上中一项通过；另有下述纯 DPAD 手动核对 |
| 拒绝本地权限后的远程／历史导航；授权后恢复本地入口 | 2 | `.tmp/ui-repairs-permission-denied1.log`、`.tmp/ui-repairs-permission-granted.log`，各 `OK (1 test)`；之后权限路由未变 |

第二次最终设备批次完整尾部为 **OK (11 tests)**，76.834 秒。弹幕用例控制 pending 完成边界，实际 Activity 观察、轨道解析、消费和 Room 写入均走生产代码；准备期间的新 seek 用例固定初始恢复状态以稳定复现竞态。显式 0 通过真实 adapter 输入验证，不能称为真实杀进程恢复。

补充纯 DPAD：启动主页→进入列表→MENU 打开选择器→FTP 编辑→BACK 恢复原行焦点；九项与取消按钮可见、首项 focused 描边清晰。截图／UI 树：`.tmp/ui-repairs-final-chooser.{png,xml}`、`.tmp/ui-repairs-final-editor.{png,xml}`、`.tmp/ui-repairs-final-return.xml`。设置路径使用 DPAD 从导航进入应用设置，将分组 false→true→返回→重入，读回 true，再恢复 false；证据为 `.tmp/ui-repairs-final-settings-{on,back,reentry,restored}.xml`。没有保存测试服务器配置。

Gradle 设备入口单独尝试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.okamihoro.ddplaytv.ui.smoke.StorageBrowseRecoveryInstrumentedTest' -I .tmp/handoff14-local.gradle -I .tmp/ui-repairs-local.gradle --offline --console=plain
```

`.tmp/ui-repairs-connected-retry.log` 为 **BUILD FAILED in 5s**：离线缓存缺少 `com.android.tools.utp:android-test-plugin-host-additional-test-output:31.10.1`，没有进入设备用例。首次命令因 PowerShell 未引用含点号的 `-P` 参数而失败（`.tmp/ui-repairs-connected.log`），修正后才得到上述环境错误。直接 ADB runner 的通过结果与 Gradle connected 失败分别保留。

测试 AVD 为任务独立的 `-read-only -no-snapshot -no-window -port 5558` 实例。结束后已恢复 1080×2340／440dpi、原三项媒体拒权及分组设置，删除本次加入的长视频夹具；读回 UI mode `0x11` 与 shell uid 2000，见 `.tmp/ui-repairs-avd-restored.log`。控制台关闭命令不可用，核对 launcher PID 29788 与 qemu PID 3016 的路径、父子关系和只读／5558 参数后，仅关闭任务实例；未清用户数据或修改其他模拟器。

## 失败与修正记录

- R1 真实红绿：`.tmp/ui-repairs-history-red.log` 中 7 项有 5 项行为失败；修复后数据库模块 `.tmp/ui-repairs-history-green.log` 为 `BUILD SUCCESSFUL in 53s`。
- 初轮集成 `.tmp/ui-repairs-integration-local3.log` 为 `BUILD FAILED in 1m 59s`。修正 MMKV 初始化、PC／WebDAV 测试夹具；协议 12 项随后在 `.tmp/ui-repairs-probe-build.log` 通过（`BUILD SUCCESSFUL in 1m 11s`），存储 VM 最终 17 项通过综合检查。
- R9 真实入口与九项测试复现视口高度 0：`.tmp/ui-repairs-current-chooser-red.{xml,png}`、`.tmp/ui-repairs-dialog-nine-red.log`。长列表初测没有等待滚动完成，改为逐项等待选中及滚动空闲；所有断言保留，后续四项通过。
- 首次实际播放 6 项全失败（`.tmp/ui-repairs-playback-existing.log`）：激活来源同步发布空 pending，观察者过早读取未初始化播放器来源。调整为空值先返回后，恢复启动。
- `.tmp/ui-repairs-device-round2.log` 为 18 项／6 失败；`.tmp/ui-repairs-resume-probe-device.log` 暴露 Media3 无 prepared 通知和空态文案不重绑。修复真实竞态；弹幕夹具另改为非同名 XML，关闭并恢复自动匹配设置，避免自动加载污染手动选择测试。
- `.tmp/ui-repairs-device-round3.log` 为 11 项／3 失败。两个 Media3 用例位置已正确，但测试未接受 wrapper 的“缓冲结束”状态；改为同时校验实际 Exo `STATE_READY`、位置和终态，未扩大容差／超时。目录用例继续失败，后续按真实按键分发定位。
- `.tmp/ui-repairs-device-final1.log` 为 19 项／2 失败：mpv 准备期间的新 seek 被初始恢复值覆盖，且 MENU 在到达列表前被 AppCompat 截获。日志显示 mpv 重建输入又回到 0，以及 WebDAV 只有两次请求、第二次 MENU 没有发出刷新；修复接线并加强确定性断言后 `.tmp/ui-repairs-device-final2.log` 11 项全通过。
- 故障注入 `.tmp/ui-repairs-player-mutation-red.log` 为 14 项／5 失败、`BUILD FAILED in 30s`，覆盖去除快进归属校验、重复取 pending、恢复 90% 否决、首帧吞 prepared。原始字节在 finally 中恢复，最终检查基于恢复后的代码；这不是原始版本的复现记录。临时生产探针已移除。
- HTTP 日志采集仅得到采集元信息，run-as 回退没有 `files/http_logs`；实际诊断使用 logcat 和已移除的临时探针，不宣称取得 HTTP 日志正文。

## 剩余决策与范围

- 本次本地实现、复核与记录已完成，未提交。构建和设备证据对应当前未提交工作区，已保留初始失败与修正过程。
- 前一轮只读审查的模块／播放门禁为 `BUILD SUCCESSFUL in 18s`，`.tmp/ui-readiness-governance.log`；不能作为 R1–R10 的完成证据。
- 续播规则已获用户确认，不再作为待确认问题。
- R9 已由当前包设备复现并修复；旧记录归属无法证明时按设计保守保留，不增加猜测迁移。
- 物理电视／Android 11 真机、真实 FTP／SMB／PC／网盘账户、复杂多行弹窗矩阵、长期压力和完整杀进程非零恢复未实测；[既有综合播放验收](../../../document/player/playback-validation-followup.md)不因本轮部分覆盖而自动关闭。
- TMDB 数据模型、首页继续观看集合及“已看完”分类仍在独立后续设计中，不能借本次修复扩大实施范围。
