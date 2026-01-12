## 1. Implementation
- [ ] 1.1 为 `BilibiliService` 新增关注直播接口 `/xlive/web-ucenter/user/following`
- [ ] 1.2 在 `data_component` 新增/补齐 follow live 响应模型（包含 `live_count`、`list`、`roomid/uname/title/room_cover/live_status` 等字段）
- [ ] 1.3 在 `BilibiliRepository` 增加 `liveFollow(page, pageSize, ignoreRecord, hitAb)` 方法并接入 Cookie 鉴权
- [ ] 1.4 在 `BilibiliStorageFile` 增加 `/follow_live/` 目录与直播条目构造函数（避免复用 `/history/live/` 路径）
- [ ] 1.5 在 `BilibiliStorage`：根目录返回 `history + follow_live`；为 `/follow_live/` 增加分页游标与 `hasMore/loadMore/reset` 支持；实现空页跳过（最多 5 次）；并确保分页状态按目录隔离（`/history/` 与 `/follow_live/` 各自维护分页 cursor/hasMore/state，并在目录切换/返回时同步到 `PagedStorage.state`）
- [ ] 1.6 在 `storage_component`：将“Bilibili 登录门槛/分页占位/TV 刷新键/空态文案”从 `/history/` 特判抽象为“Bilibili 分页目录”判断，并覆盖 `/follow_live/`
- [ ] 1.7 在 `BilibiliStorage`：补齐 `pathFile()`/`historyFile()` 对 `/follow_live/{roomId}` 的解析与播放历史兼容（支持旧 `/history/live/{roomId}`；当历史记录 `storagePath` 指向 `/follow_live/` 时还原同路径的 `StorageFile`）

## 2. Validation
- [ ] 2.1 JVM 单测：为 follow live JSON model 解析补充测试（或在现有 bilibili_component 测试模块中新增针对映射/过滤的测试）
- [ ] 2.2 本地验证：进入 Bilibili 媒体库根目录能看到两个目录；进入“关注直播”能分页加载并可播放
- [ ] 2.3 回归验证：`/history/` 分页、TV 刷新、登录提示行为不变
- [ ] 2.4 切换目录验证：`/history/` 与 `/follow_live/` 间切换时分页 state/cursor 不串台
- [ ] 2.5 播放历史验证：LiveKey 在 `storagePath=/follow_live/{roomId}` 与旧 `/history/live/{roomId}` 场景均可还原并播放

## 3. Tooling
- [ ] 3.1 运行 `./gradlew testDebugUnitTest`（至少覆盖新增单测模块）
- [ ] 3.2 运行 `./gradlew lint`（或 `lintDebug`）确保无新增 lint 问题
- [ ] 3.3 运行 `./gradlew verifyModuleDependencies` 确保依赖治理通过
