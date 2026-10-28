# Bilibili 历史同步与异常播完策略

源码核对日期：2026-10-28。本文整合原 `adjust-bilibili-heartbeat-logic` 与 `fix-bilibili-unexpected-completion` 的有效决策，说明当前实现及维护边界。历史任务勾选不代表本次重新验证了远端接口、设备播放或 TV 交互。

## 职责与入口

| 职责 | 当前维护入口 |
| --- | --- |
| 本地历史同步模式、旧配置迁移 | [BilibiliHistorySyncPreferencesStore](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliHistorySyncPreferencesStore.kt) |
| 连接标识与播放偏好 | [BilibiliPlaybackPreferencesStore](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliPlaybackPreferencesStore.kt) |
| 服务端状态缓存 | [BilibiliHistoryStatusStore](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/history/BilibiliHistoryStatusStore.kt) |
| 历史状态刷新、请求参数映射与兜底校验 | [BilibiliRepositoryCore](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepositoryCore.kt) |
| 心跳策略与调度 | [BilibiliHeartbeatPolicy](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/playback/BilibiliHeartbeatPolicy.kt)、[BilibiliPlaybackHeartbeat](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/playback/BilibiliPlaybackHeartbeat.kt) |
| TV 配置、只读服务端状态 | [BilibiliStorageEditDialog](../../storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/BilibiliStorageEditDialog.kt) |
| 播完可信度与状态转换 | [BilibiliCompletionTrust](../../player_component/src/main/java/com/xyoye/player/BilibiliCompletionTrust.kt)、[DanDanVideoPlayer](../../player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt) |
| URL 恢复与播放资源替换编排 | [ActivePlaybackSession](../../player_component/src/main/java/com/xyoye/player_component/playback/session/ActivePlaybackSession.kt) |

`bilibili_component` 管理 Bilibili 请求、认证相关状态及心跳，`storage_component` 提供连接配置，`player_component` 判断播放状态并编排恢复。修改时沿这些职责维护，避免在 Activity 中另建恢复状态机。

## 本地模式与服务端状态

“历史同步”提供 `AUTO`（自动）和 `DISABLED`（关闭）两种模式，配置修改自动保存。`AUTO` 继续检查服务端状态、登录和内容类型；`DISABLED` 停止本客户端对应连接的同步，不调用服务端暂停设置接口，也不改变其他客户端的全局历史开关。

本地模式、认证和状态缓存按 `storageKey` 隔离。该键由媒体类型和规范化连接 URL 组成：`${library.mediaType.value}:${library.url.trim().removeSuffix("/")}`。它不是 Bilibili `mid` 账号标识；相同键的记录会共享配置，不能把它描述为所有数据库记录彼此完全独立。

模式与画质等播放偏好分开存储。首次读取新模式时若没有有效值，按旧 `heartbeat_report` 迁移：旧值为 `false` 时使用 `DISABLED`，旧值为 `true` 或旧键不存在时使用 `AUTO`。写入新模式会清理旧键。[连接清理](../../bilibili_component/src/main/java/com/xyoye/common_component/bilibili/cleanup/BilibiliCleanup.kt)统一清除模式、播放偏好、服务端状态缓存及认证等数据；该入口对 `library.id <= 0` 直接返回。

服务端历史暂停状态通过 `GET /x/v2/history/shadow?jsonp=jsonp` 读取，配置页只读展示。当前缓存和刷新策略为：

- 缓存有效期为 5 分钟；非强制刷新至少间隔 60 秒重试。
- 刷新失败但存在旧结果时，保留最近已知状态，即使该结果已过期；已知暂停仍会阻止心跳。
- 没有旧结果且刷新失败时，状态保持未知，心跳继续按本地模式、登录和内容类型判定，并保留失败诊断。
- 未登录或缺少 CSRF 时不发状态请求，返回已有缓存；心跳策略仍单独检查登录与 CSRF。

服务端状态读取失败不应被描述为“已经确认允许同步”或“自动关闭同步”。

## 心跳决策、参数与生命周期

每次发送前集中执行策略，按顺序返回第一个跳过原因：

1. 本地关闭：`local_disabled`。
2. 已知服务端暂停：`server_paused`。
3. 未登录：`unauthenticated`。
4. CSRF 缺失：`missing_csrf`。
5. 内容不可上报：投稿缺少 `cid` 为 `missing_archive_cid`，直播为 `live_not_supported`，仅有整季标识为 `pgc_season_not_supported`。

请求通过 `POST /x/click-interface/web/heartbeat` 发送；最终参数映射与缺参兜底仍属于 repository：

| 内容标识 | 请求参数与行为 |
| --- | --- |
| `ArchiveKey` | `bvid`、`cid`、`type=3` |
| `PgcEpisodeKey` | `epid`、`cid`、`type=4`；`seasonId > 0` 时附加 `sid` |
| `LiveKey`、`PgcSeasonKey` | 不发送播放心跳 |

`played_time` 为正数时表示秒进度，`-1` 表示播完，`0` 直接忽略。缺少 CSRF 或投稿 `cid` 时 repository 返回失败。当前模型没有提供额外的 PUGV 课程类型支持。

调度仅处理 `BILIBILI_STORAGE`：播放中且位置至少 1 秒时调度普通进度，并按实际时间间隔 5 秒节流；暂停且位置至少 1 秒时强制发送当前位置；播完强制发送 `-1`。这里“强制”仅绕过时间节流，仍遵守心跳策略。空闲、启动中止或会话清理会取消对应调度任务。

调度以 `storageId / uniqueKey / token` 区分会话，清理时取消该会话的任务。相同跳过原因和发送失败日志约每 60 秒限频，跳过原因变化可立即记录。不要通过全局调度器混合不同播放会话的进度或取消信号。

## 播完可信度与恢复

以下守卫只用于 `BILIBILI_STORAGE`。位置和时长的负值先归零；长视频判断使用严格大于 10 分钟的边界：

| 场景 | 是否视为异常播完 |
| --- | --- |
| 直播 | 是 |
| 时长未知或为零，位置超过 600000ms | 是 |
| 时长未知或为零，位置恰好 600000ms 或更小 | 否 |
| 已知时长不超过 600000ms | 否 |
| 已知时长超过 600000ms，剩余时间超过 30000ms | 是 |
| 已知时长超过 600000ms，剩余时间恰好 30000ms 或更小 | 否 |

剩余时间取 `max(duration-position, 0)`。旧规格中的 `duration >= 600000ms` 与当前源码和测试不一致，维护时应使用上表的严格边界。

收到异常播完时，播放器先记录当前位置与时长，再合成错误并进入 `STATE_ERROR`，提前结束完成回调；因此不会进入 `STATE_COMPLETED`、发送播完心跳或触发自动下一项。位置优先使用内核返回的正值，否则使用最近已知位置。可信播完仍记录 `position=0` 并保留实际时长，保持再次打开从头播放的语义；已经处于错误态时不会再改成播完态。

错误观察由 [PlayerActivity](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 交给 ViewModel，再由活动播放会话执行恢复和内部替换。会话检查预期版本，通过 `PlaybackUrlRecoverableAddon.recover` 刷新可恢复的播放源；当前恢复尝试上限为 3 次。成功后提示“已自动恢复播放”，失败则展示错误。旧提案中的 Activity 恢复调用链仅说明当时背景，不构成新增编排层的依据。

## 参考实现与保留的验证责任

本策略参考了 `E:\Project\PiliPlus` 的以下行为语义：

- `lib/http/video.dart`：播放心跳的内容标识与进度参数。
- `lib/http/user.dart`、`lib/pages/history/controller.dart`：服务端历史暂停状态的查询、修改与展示边界。
- `lib/plugin/pl_player/controller.dart`：登录与暂停状态门禁、直播跳过、约 5 秒进度心跳和播完 `-1`。

本仓库将这些行为映射到上述 Kotlin 模块，按 `storageKey`、本地连接配置和 TV 操作入口适配；没有照搬 Flutter 的账号状态或页面组织方式。

原心跳调整任务记录为 **10/10**，有效决策已转入本页；原异常播完任务记录为 **5/6**，其中 **2.3 现场冒烟尚未完成**：需确认 Bilibili 直播及长视频异常断流／URL 过期时不会误跳下一项，且能自动恢复或给出可理解的错误提示。该项统一由[播放验证追踪](playback-validation-followup.md)维护，不因删除旧提案或整理文档而关闭。

现有 [BilibiliHeartbeatPolicyTest](../../bilibili_component/src/test/java/com/xyoye/common_component/bilibili/playback/BilibiliHeartbeatPolicyTest.kt) 和 [BilibiliCompletionTrustTest](../../player_component/src/test/java/com/xyoye/player/BilibiliCompletionTrustTest.kt)提供策略与边界用例。此次仅核对源码及测试内容，未重跑业务测试、远端请求或现场播放；后续变更需按受影响路径补充验证，Gradle 结果以输出尾部 `BUILD SUCCESSFUL`／`BUILD FAILED` 为准。
