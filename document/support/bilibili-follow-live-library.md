# Bilibili 关注直播映射为媒体库文件夹（可分页）可行性分析

## 需求描述

在当前工程的 Bilibili 媒体库（`BilibiliStorage`）中，除了已支持的「历史记录」外，希望将 **“已关注用户中正在直播的直播间”** 映射为一个与「历史记录」并列的目录（文件夹），并支持分页加载。

本分析要求 **对齐 PiliPlus 的实现策略**：

- 使用与 PiliPlus 一致的数据源（接口与关键参数）
- 采用分页方式加载
- 仅展示 `live_status == 1`（正在直播）的条目

## 结论（可行性）

结论：**可实现，且改造成本可控**。

理由概述：

1. **数据源可用且已被成熟项目验证**：PiliPlus 使用 `https://api.live.bilibili.com/xlive/web-ucenter/user/following` 拉取关注列表并筛出正在直播条目。
2. **当前工程已具备直播播放链路**：已支持 `bilibili://live/{roomId}` 的唯一键解析、直播间信息查询与取流（HLS/m3u8）逻辑；只需要补齐“关注直播列表”取数与目录映射。
3. **现有媒体库 UI/交互支持目录 + 分页占位**：工程已有 `PagedStorage` + `StoragePagingItem` 的 UI 机制，可复用到新目录。

## 参考依据

> 本分析优先参考以下两个本机项目（遇到 Bilibili 相关困惑时也以它们为准，而非联网搜索）：
> - `PiliPlus`：`/home/tzw/workspace/PiliPlus`
> - `bilibili-API-collect`：`/home/tzw/workspace/bilibili-API-collect`

### 1）PiliPlus 的实现（对齐目标）

PiliPlus 侧使用的接口与参数（核心点）：

- 接口：`GET https://api.live.bilibili.com/xlive/web-ucenter/user/following`
- 参数：
  - `page`: 页码
  - `page_size`: 每页数量（PiliPlus 使用 9）
  - `ignoreRecord`: 1
  - `hit_ab`: true
- 过滤策略：将返回 `list` 中的条目 `live_status == 1` 过滤为最终展示列表
- 计数：使用 `live_count` 作为“正在直播的人数”，用于判断分页终止

对应参考代码：

- `PiliPlus/lib/http/live.dart` 中 `LiveHttp.liveFollow()`
- `PiliPlus/lib/models_new/live/live_follow/data.dart` 中 `where((i) => i['live_status'] == 1)`

### 2）bilibili-API-collect 的接口文档

bilibili-API-collect 明确记录了该接口的请求方式/参数/响应字段：

- 文档：`bilibili-API-collect/docs/live/follow_up_live.md`
- 接口：`/xlive/web-ucenter/user/following`
- 认证：Cookie（SESSDATA）
- 分页：`page` + `page_size`（有效值 1-10）
- 字段：`live_status`、`roomid`、`uname`、`title`、`room_cover`、`text_small` 等

> 说明：同文档中还记录了 `xfetter/GetWebList`（直接返回正在直播列表），但本分析选择“对齐 PiliPlus”，因此以 `user/following` 作为实现基准。

## 当前工程能力现状（DanDanPlayForAndroid）

### 1）Bilibili 媒体库目录结构

当前 `BilibiliStorage`：

- 根目录 `/` 下仅返回一个目录：`/history/`（展示名“历史记录”）
- `/history/` 目录通过 `history/cursor(type=all)` 拉取历史条目并映射为 `StorageFile`

对应文件：

- `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
- `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/BilibiliStorageFile.kt`

### 2）直播播放能力（已具备）

工程已具备直播条目的关键能力：

- 唯一键：`bilibili://live/{roomId}`（`BilibiliKeys.liveRoomKey()`）
- 播放时：
  - 先请求 `room/v1/Room/get_info` 做短号/长号解析
  - 再请求 `room/v1/Room/playUrl` 获取 HLS/m3u8

对应文件：

- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliKeys.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/network/service/BilibiliService.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
- `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`

因此，将“关注直播列表”映射成 `StorageFile` 后，可直接复用既有播放链路。

## 目标形态（目录映射）

### 目录结构建议

在 `BilibiliStorage` 根目录 `/` 下新增一个与 `/history/` 并列的目录：

- `/history/`：历史记录（已有）
- `/follow_live/`：关注直播（新增，显示正在直播的关注 UP）

目录展示名：

- “历史记录”
- “关注直播”

### 条目映射规则（关注直播 → StorageFile）

将 `user/following` 接口返回的 `list` 中（过滤 `live_status == 1`）每个条目映射为一个可播放文件：

- `filePath`: `/follow_live/{roomId}`
- `isDirectory`: `false`
- `uniqueKey`: `bilibili://live/{roomId}`（复用 `BilibiliKeys.liveRoomKey(roomId)`）
- `fileName`: 建议使用 `"{uname} - {title}"`（更利于在文件列表识别主播），标题为空时回退到 `roomId`
- `cover`: 优先 `room_cover`，缺失时可回退 `face`

> 注意：现有 `BilibiliStorageFile.liveRoomFile()` 固定将 `path` 写成 `/history/live/{roomId}`。
> 若要让播放历史 `storagePath`、目录层级与 UI 体验一致，建议新增专用构造函数（例如 `followLiveRoomFile()`），而不是复用历史目录下的路径。

## 分页策略（对齐 PiliPlus）

### 基本策略

- 使用 `page` + `page_size` 的分页方式
- 参数对齐 PiliPlus：`page_size=9`、`ignoreRecord=1`、`hit_ab=true`
- 每次请求后将 `list` 过滤为 `live_status == 1` 的条目，再映射为 `StorageFile`
- 使用返回的 `live_count` 作为“正在直播总数”（对齐 PiliPlus 用法）

### 工程内分页机制复用建议

工程已有 `PagedStorage` 接口与 `StoragePagingItem` UI 组件（当前主要用于 `/history/`）：

- `PagedStorage.hasMore()`
- `PagedStorage.loadMore()`
- `PagedStorage.state`（IDLE/LOADING/ERROR/NO_MORE）

建议复用这一机制，将 `/follow_live/` 也作为可分页目录。

### “过滤导致空页”的处理建议

由于接口返回的列表可能混杂 `live_status=0`（未直播）条目，而我们只展示 `live_status=1`，因此存在：

- 某些分页请求返回的数据在过滤后为空

为避免用户看到“加载更多后没有新增条目”的体验问题，建议与当前历史目录相同，加入“空页跳过”策略：

- 若某一页过滤后为空且还有下一页，则自动继续请求下一页
- 为避免极端情况循环请求，设置最大尝试次数（例如 5 次）

> 这与 `BilibiliStorage` 现有的历史记录拉取逻辑保持一致（历史目录中也存在过滤业务类型导致的空页问题）。

### 刷新策略

直播列表变化频繁，建议：

- 进入 `/follow_live/` 时默认拉取最新（page=1）
- 下拉刷新 / TV 菜单键刷新：重置分页状态（page=1、清空已加载列表、state=IDLE），重新拉取
- 不建议做长时间缓存（最多可做很短的内存缓存/节流，但不是必须）

## 登录与权限

该接口需要 Cookie 登录态（SESSDATA），因此 `/follow_live/` 目录需要与 `/history/` 同级别的登录门槛：

- 未登录：提示扫码登录
- 登录失效（-101）：提示重新登录

工程中目前只有 `/history/` 做了“需要登录才能查看”的硬编码判断，若引入 `/follow_live/`，需要同步扩展判断范围。

涉及位置示例（实现时需调整）：

- `storage_component/.../StorageFileFragmentViewModel.kt` 中 `requiresLogin = target.filePath() == "/history/"`
- `storage_component/.../StorageFileAdapter.kt` 中 empty view 文案 “需要登录才能查看历史记录”

## UI/交互一致性影响点（实施时需要顺带统一）

当前工程对 Bilibili 历史目录 `/history/` 有一些“路径特判”，若新增 `/follow_live/` 并希望体验一致，需要同步扩展：

- TV 刷新键处理：目前仅对 `currentRoute == "/history/"` 生效
- 列表底部分页占位：目前仅在 `storage.directory?.filePath() == "/history/"` 时添加 `StoragePagingItem`
- 空列表/无更多文案：当前写死“暂无历史记录”，关注直播更适合“暂无直播”或“当前无人开播”

> 这些都属于“同一类目录（Bilibili 远端列表型目录）复用同一套 UI 规则”的范畴，建议在实现时抽象为“是否为 Bilibili 分页目录”的判断，而不是继续按路径写死。

## 预计实现改动点（仅列清单，便于后续落地）

### 1）网络层

- `bilibili_component/.../network/service/BilibiliService.kt`
  - 新增 `GET /xlive/web-ucenter/user/following`

- `data_component/.../bilibili/`
  - 新增 `BilibiliLiveFollowModels.kt`（或合并入现有 `BilibiliLiveModels.kt`）
  - 包含 `data.title/pageSize/totalPage/list/live_count` 等字段

- `bilibili_component/.../bilibili/repository/BilibiliRepository.kt`
  - 新增 `liveFollow(page, pageSize, ignoreRecord, hitAb)`

### 2）存储映射层（核心）

- `core_storage_component/.../storage/file/impl/BilibiliStorageFile.kt`
  - 新增：
    - `followLiveDirectory()` → `/follow_live/`
    - `followLiveRoomFile()` → `/follow_live/{roomId}`

- `core_storage_component/.../storage/impl/BilibiliStorage.kt`
  - 根目录返回 `historyDirectory + followLiveDirectory`
  - `listFilesInternal` 增加对 `/follow_live/` 的分发
  - 引入分页状态：`followLivePage`、`followLiveTotalPage`、`followLiveHasMore`
  - 让 `hasMore()/loadMore()/reset()` 在 `/follow_live/` 下也工作

### 3）UI 与交互

- `storage_component/.../StorageFileFragmentViewModel.kt`
  - 登录门槛：`/follow_live/` 也需要登录
  - 分页占位：对 `/follow_live/` 也展示 `StoragePagingItem`

- `storage_component/.../StorageFileAdapter.kt`
  - 空列表文案与动作：根据目录类型区分“历史记录/关注直播”

- `storage_component/.../StorageFileActivity.kt`
  - TV 刷新键：对 `/follow_live/` 生效

## 手动验证建议（实施后）

1. 新增并登录 Bilibili 媒体库
2. 打开 `Bilibili` 媒体库根目录，确认出现两个目录：`历史记录` 与 `关注直播`
3. 进入 `关注直播`：
   - 未登录：提示扫码登录
   - 已登录：展示正在直播的关注 UP（若无人开播则提示“暂无直播”）
4. 点击任意直播条目：能正常取流并播放
5. 下拉刷新 / TV 菜单键刷新：列表更新，分页状态重置
6. 加载更多：能继续翻页直到 `live_count` 覆盖或无更多

---

## 附：为什么不选 `xfetter/GetWebList`

`xfetter/GetWebList` 可以直接拿到“正在直播的房间列表”，但文档指出其字段会受到 `hit_ab` 影响，且与 PiliPlus 的实现不一致。

本分析遵循“对齐 PiliPlus、可分页”的约束，因此选择 `user/following` 作为统一数据源；后续若需要更少请求/更快首屏，可再评估是否引入 `GetWebList` 作为首屏加速或兜底数据源。
