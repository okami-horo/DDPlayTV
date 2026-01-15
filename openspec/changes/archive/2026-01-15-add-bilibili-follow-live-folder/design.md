## Context
项目已在 `BilibiliStorage` 中支持 `/history/` 目录，并具备 `PagedStorage` + `StoragePagingItem` 的分页 UI 机制；同时已有 `bilibili://live/{roomId}` 的解析、直播间信息查询与 HLS/m3u8 取流链路。

`document/support/bilibili-follow-live-library.md` 提出对齐 PiliPlus：使用 `/xlive/web-ucenter/user/following` 接口分页获取关注列表，并过滤 `live_status == 1`。

若遇到 Bilibili 接口/字段/边界行为的困惑，优先参考本机的 `PiliPlus`（`/home/tzw/workspace/PiliPlus`）与 `bilibili-API-collect`（`/home/tzw/workspace/bilibili-API-collect`），避免依赖联网搜索。

## Goals / Non-Goals
- Goals
  - 在 Bilibili 媒体库根目录新增 `/follow_live/` 目录并支持分页。
  - 仅展示正在直播条目，并可直接播放。
  - UI/交互对 Bilibili 的“远端分页目录”保持一致，减少基于路径的特判。

- Non-Goals
  - 不新增长期缓存或离线能力。
  - 不调整直播播放链路（只复用）。

## Key Decisions
1. **数据源对齐 PiliPlus**
   - 使用 `GET https://api.live.bilibili.com/xlive/web-ucenter/user/following`。
   - 参数对齐：`page`、`page_size=9`、`ignoreRecord=1`、`hit_ab=true`。
   - 仅展示 `live_status == 1`。

2. **分页终止条件对齐**
   - 以响应中的 `live_count` 作为“正在直播总数”用于判断是否还有更多。
   - 考虑过滤导致的“空页”，采用与历史列表相同的“空页跳过”策略（最多尝试 5 页），避免用户看到“加载更多但无新增”。

3. **目录与路径设计**
   - `/follow_live/` 与 `/history/` 并列。
   - 直播条目 `filePath` 固定为 `/follow_live/{roomId}`，而不是复用历史目录下 `/history/live/{roomId}`，以保证目录层级与播放历史 `storagePath` 更一致。

4. **分页状态按目录隔离并映射到 `PagedStorage.state`**
   - `PagedStorage.state` 是单值，但本变更需要同时支持 `/history/` 与 `/follow_live/` 两个可分页目录。
   - 方案要求按目录分别维护 cursor/hasMore/state，并在目录切换/返回时，保证 `PagedStorage.state` 与当前目录一致，避免状态串台。

5. **UI 特判收敛为“Bilibili 分页目录”判断**
   - 当前 UI 层对 `/history/` 有硬编码：登录门槛、分页占位、TV 刷新键、空态文案。
   - 本变更将这些逻辑抽象为“是否为 Bilibili 的远端分页目录”（至少包含 `/history/` 与 `/follow_live/`），避免继续按路径扩散特判。

6. **直播播放历史路径兼容策略（明确回退规则）**
   - 直播的 `uniqueKey` 统一使用 `bilibili://live/{roomId}`。
   - 若播放历史记录中 `storagePath` 存在且以 `/follow_live/` 开头，则还原为 `/follow_live/{roomId}` 的 `StorageFile`。
   - 若 `storagePath` 为空或为旧路径 `/history/live/{roomId}`，默认回退到 `/history/live/{roomId}`（保持向后兼容）。

## Risks / Trade-offs
- **接口返回混杂未开播条目**：过滤后可能出现空页，需要跳页策略；但跳页过多会增加请求次数，设置最大尝试次数以止损。
- **登录态依赖**：该接口需要 Cookie（SESSDATA）。需与 `/history/` 同级别的登录提示与失效处理。
- **状态同步复杂度**：分页状态需要按目录隔离，并在目录切换/返回时同步到 `PagedStorage.state`；若处理不当可能出现“加载更多按钮状态错误/被 LOADING 阻塞”等问题。

## Migration Plan
- 不涉及数据迁移。
- 仅新增目录与接口；旧版本播放历史仍可通过 `uniqueKey=bilibili://live/{roomId}` 回放。
- 对于直播历史：优先尊重已有 `storagePath`；当缺失时按上述回退规则生成路径，保证旧记录可用。

## Open Questions
- `/follow_live/` 的空列表文案希望统一为“暂无直播”还是“当前无人开播”。
