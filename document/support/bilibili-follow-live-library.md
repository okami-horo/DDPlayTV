# Bilibili 关注直播映射为媒体库文件夹（可分页）设计与实现说明

> 状态：已在当前仓库实现。目录为 `/follow_live/`，与 `/history/` 同属于 Bilibili 分页目录。

## 需求描述

在当前工程的 Bilibili 媒体库（`BilibiliStorage`）中，除了已支持的「历史记录」外，希望将 **“已关注用户中正在直播的直播间”** 映射为一个与「历史记录」并列的目录（文件夹），并支持分页加载。

实现约束（对齐 PiliPlus 的策略）：

- 使用与 PiliPlus 一致的数据源（接口与关键参数）
- 采用分页方式加载
- 仅展示 `live_status == 1`（正在直播）的条目

## 结论（可行性）

结论：**可实现，且已落地**。

## 当前工程能力现状（DDPlayTV）

### 1）Bilibili 媒体库目录结构

当前 `BilibiliStorage`：

- 根目录 `/` 下返回两个目录：`/history/`（历史记录）与 `/follow_live/`（关注直播）
- `/history/` 目录通过 `history/cursor(type=all)` 拉取历史条目并映射为 `StorageFile`
- `/follow_live/` 目录通过 `xlive/web-ucenter/user/following` 拉取关注列表并过滤 `live_status == 1`

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

### 目录结构

- `/history/`：历史记录（已有）
- `/follow_live/`：关注直播（新增，显示正在直播的关注 UP）

### 条目映射规则（关注直播 → StorageFile）

将 `user/following` 接口返回的 `list` 中（过滤 `live_status == 1`）每个条目映射为一个可播放文件：

- `filePath`: `/follow_live/{roomId}`
- `isDirectory`: `false`
- `uniqueKey`: `bilibili://live/{roomId}`（复用 `BilibiliKeys.liveRoomKey(roomId)`）
- `fileName`: 建议使用 `"{uname} - {title}"`（更利于识别主播），标题为空时回退到 `roomId`
- `cover`: 优先 `room_cover`，缺失时可回退 `face`

## 分页策略（对齐 PiliPlus）

- 使用 `page` + `page_size` 的分页方式
- 参数对齐 PiliPlus：`page_size=9`、`ignoreRecord=1`、`hit_ab=true`
- 每次请求后将 `list` 过滤为 `live_status == 1` 的条目，再映射为 `StorageFile`
- 使用返回的 `live_count` 作为“正在直播总数”（对齐 PiliPlus 用法）

### “过滤导致空页”的处理

由于接口返回的列表会包含 `live_status=0`（未直播）条目，而我们只展示 `live_status=1`，因此存在某些分页过滤后为空的情况。

为避免用户看到“加载更多后没有新增条目”，当前实现与历史目录保持一致，加入了“空页跳过”策略：

- 若某一页过滤后为空且还有下一页，则自动继续请求下一页
- 设置最大尝试次数（默认 5 次）避免极端情况下循环请求

## 登录与权限

该接口需要 Cookie 登录态（SESSDATA），因此 `/follow_live/` 目录需要与 `/history/` 同级别的登录门槛。

当前实现通过 `AuthStorage` 抽象统一处理：

- `BilibiliStorage.requiresLogin(directory)`：对 `/history/` 与 `/follow_live/` 均返回 `true`
- UI 侧通过 `authStorage.requiresLogin(directory) && !authStorage.isConnected()` 判断是否需要登录提示

对应实现：

- `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragmentViewModel.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt`

## UI/交互一致性

当前仓库已把“是否为 Bilibili 分页目录”的判断收敛到存储层：

- `BilibiliStorage.isBilibiliPagedDirectoryPath(path)`：统一识别 `/history/` 与 `/follow_live/`
- `BilibiliStorage.shouldShowPagingItem(directory)`：统一决定是否展示 `StoragePagingItem`

因此 UI 侧无需对路径做硬编码分支；如需新增同类目录，优先扩展上述判定，而不是继续写死路径。

## 实现改动点（已落地）

### 1）网络层

- `bilibili_component/src/main/java/com/xyoye/common_component/network/service/BilibiliService.kt`
  - 新增 `GET /xlive/web-ucenter/user/following`

- `data_component/src/main/java/com/xyoye/data_component/data/bilibili/`
  - 新增 `BilibiliLiveFollowModels.kt`

- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
  - 新增 `liveFollow(page, pageSize, ignoreRecord, hitAb)`

### 2）存储映射层（核心）

- `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/BilibiliStorageFile.kt`
  - 新增：
    - `followLiveDirectory()` → `/follow_live/`
    - `followLiveRoomFile()` → `/follow_live/{roomId}`

- `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
  - 根目录返回 `historyDirectory + followLiveDirectory`
  - `listFilesInternal` 增加对 `/follow_live/` 的分发
  - 引入分页状态：`followLivePage`、`followLiveLoadedCount`、`followLiveTotalLiveCount`、`followLiveHasMore`
  - 让 `hasMore()/loadMore()/reset()` 在 `/follow_live/` 下也工作

## 手动验证建议（回归）

1. 新增并登录 Bilibili 媒体库
2. 打开 `Bilibili` 媒体库根目录，确认出现两个目录：`历史记录` 与 `关注直播`
3. 进入 `关注直播`：
   - 未登录：提示扫码登录
   - 已登录：展示正在直播的关注 UP（若无人开播则提示“暂无直播”）
4. 点击任意直播条目：能正常取流并播放
5. 下拉刷新 / TV 菜单键刷新：列表更新，分页状态重置
6. 加载更多：能继续翻页直到 `live_count` 覆盖或无更多
