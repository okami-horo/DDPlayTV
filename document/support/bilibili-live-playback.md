# Bilibili 直播播放适配设计说明

## 背景与问题

当前工程的 `BilibiliStorage` 通过 `https://api.bilibili.com/x/web-interface/history/cursor` 获取 B 站历史记录，并将条目映射为可播放的 `StorageFile`。

历史记录接口的默认 `type=all` 会同时返回：

- `archive`：稿件（普通视频）
- `live`：直播间
- 其他：`pgc`、`article` 等

但工程原实现仅按 `archive` 取数并依赖 `playurl`（点播取流）播放，导致当历史记录中出现 `live` 条目时：

- 条目无法被正确映射为可播放文件，或
- 即使展示出来也无法通过点播 `playurl` 完成取流，从而无法播放。

## 目标

为 `BilibiliStorage` 增加对 **直播间历史条目** 的播放支持，使 Bilibili 媒体库的「历史记录」中出现的 `live` 内容能够正确播放。

## 方案概述

### 1）统一唯一键（UniqueKey）表达

工程内对 Bilibili 播放对象使用 `bilibili://...` 的唯一键进行标识（用于播放记录、去重、跨页面恢复等）。

新增直播间唯一键：

- `bilibili://live/{roomId}`

并保留原有稿件唯一键：

- `bilibili://archive/{bvid}?cid={cid}`

对应实现：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliKeys.kt`

### 2）扩展历史记录数据模型（History Models）

历史记录接口在 `history` 对象中通过 `business` 区分业务类型，并使用 `oid` 表示目标 id：

- `business=archive`：`oid` 为 avid，同时会提供 `bvid/cid`
- `business=live`：`oid` 为直播间号（roomId）

因此需要在模型中补充 `oid`（以及少量直播相关字段，便于未来扩展）。

对应实现：`data_component/src/main/java/com/xyoye/data_component/data/bilibili/BilibiliHistoryModels.kt`

### 3）历史目录取数策略

由于接口不支持「只取 archive + live」的组合筛选（仅提供 `type=all/archive/live/article`），因此历史目录改为：

- 请求 `type=all`
- 仅映射 `archive` 与 `live`

并加入「空页跳过」逻辑：当某一页全部是暂不支持的业务类型（例如 `article/pgc`）导致映射结果为空时，会自动再取下一页（最多 5 次），避免首屏/翻页出现“加载成功但没有新增条目”的体验问题。

对应实现：`core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`

### 4）直播取流（Live playUrl）

直播取流与点播 `playurl` 不同，使用直播侧接口：

1. 房间信息（用于短号转长号）
   - `https://api.live.bilibili.com/room/v1/Room/get_info?room_id={roomId}`
   - 入参 `room_id` 支持短号，返回 `room_id`（长号）

2. 直播流地址
   - `https://api.live.bilibili.com/room/v1/Room/playUrl?cid={roomId}&platform=h5`
   - `platform=h5` 返回 HLS（m3u8），更利于播放器兼容

对应实现：

- Retrofit 接口：`bilibili_component/src/main/java/com/xyoye/common_component/network/service/BilibiliService.kt`
- Repository：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
- 数据模型：`data_component/src/main/java/com/xyoye/data_component/data/bilibili/BilibiliLiveModels.kt`

### 5）弹幕处理

当前工程的 Bilibili 弹幕下载基于稿件 `cid`（点播弹幕），直播弹幕为 WS/WSS 实时流，协议不同。

本次适配仅保证直播 **可播放**，播放页弹幕逻辑在检测到 `bilibili://live/{roomId}` 时不会尝试按 `cid` 下载点播弹幕。

对应实现：`player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModel.kt`

## 数据流与关键路径

1. `BilibiliStorage` 打开 `/history/`：
   - 调用 `history/cursor(type=all)` 获取列表
   - `business=archive` → 映射为 `archive` 文件或目录
   - `business=live` → 映射为 `live` 可播放文件（`bilibili://live/{roomId}`）

2. 用户点击直播条目播放：
   - `createPlayUrl()` 解析唯一键为 `LiveKey(roomId)`
   - `get_info(room_id=roomId)` 得到长号 `room_id`
   - `playUrl(cid=room_id, platform=h5)` 得到 m3u8 URL
   - 返回给播放器开始播放

## 已知限制与后续可扩展点

- 直播弹幕（WS/WSS）暂未接入，仅播放视频流。
- 直播流 URL 具有时效性；当 URL 过期或线路不可用导致播放失败时，会触发播放页「自动恢复」：重取流并在必要时切换线路（直播不 seek）。
- 历史记录 `type=all` 仍会包含 `pgc/article` 等业务类型，目前选择忽略；如需支持，可在 `mapHistoryItem()` 中扩展映射与取流策略。

## 手动验证步骤（建议）

1. 在 App 中新增并登录 Bilibili 媒体库
2. 打开「Bilibili → 历史记录」，确认能看到直播条目
3. 点击直播条目，确认：
   - 能正常开始播放
   - 网络请求不出现 403/404
   - 长时间播放（约 1h+）后如遇过期/断流，能自动恢复
4. 对普通稿件（archive）回归验证：
   - 单 P 与多 P 播放/分页列表正常
