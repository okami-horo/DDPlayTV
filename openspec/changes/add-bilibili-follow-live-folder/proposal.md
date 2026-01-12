# Change: 将 Bilibili 关注直播映射为媒体库文件夹（分页）

## Why
当前 `BilibiliStorage` 仅提供“历史记录”目录。用户希望在同一媒体库中直接浏览“已关注用户中正在直播的直播间”，并与历史记录并列展示，同时支持分页加载，以提升发现与播放直播的效率。

## What Changes
- 在 `BilibiliStorage` 根目录增加一个与 `/history/` 并列的目录：`/follow_live/`（展示名“关注直播”）。
- `/follow_live/` 目录通过 B 站关注直播接口分页拉取关注列表，并过滤仅展示 `live_status == 1`（正在直播）的条目。
- 将每个正在直播条目映射为可播放 `StorageFile`（`uniqueKey=bilibili://live/{roomId}`），复用现有直播取流与播放链路。
- 统一 Bilibili 远端分页目录在 UI 层的判断逻辑：登录门槛、分页占位、TV 刷新键、空态文案不再仅对 `/history/` 路径写死。

## Impact
- Affected specs: `bilibili-follow-live-library`（新增能力）。
- Affected code (implementation stage):
  - 网络层：`bilibili_component`（新增接口）、`data_component`（新增 JSON models）、`bilibili_component` repository（新增请求封装）。
  - 存储层：`core_storage_component`（`BilibiliStorage` 目录与分页支持、`BilibiliStorageFile` 新增构造）。
  - UI 层：`storage_component`（登录判断、分页占位、TV 刷新、空态文案）。

## Non-Goals
- 不引入 `xfetter/GetWebList` 作为数据源或首屏加速（保持与 PiliPlus 对齐）。
- 不新增长期缓存策略（最多允许短期内存节流，非必须）。
- 不改变现有 `/history/` 行为与数据源。
