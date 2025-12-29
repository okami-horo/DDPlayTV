# Bilibili 历史记录 PGC（番剧/影视）播放适配设计（TODO）

> 目标：在当前已接入的「Bilibili → 历史记录」媒体库中，让 `business=pgc`（番剧/影视）条目能够像普通稿件/直播一样被映射为可播放项，并完成取流播放闭环。  
> 非目标：不新增“追番/收藏/索引页”等浏览入口，仅覆盖历史记录流中的 PGC 条目。

---

## 1. 背景

当前项目的 Bilibili 媒体库实现基于 `history/cursor(type=all)`：

- 已支持：
  - `archive`：普通视频（稿件）
  - `live`：直播
- 尚未支持：
  - `pgc`：剧集（番剧 / 影视）

历史记录接口会返回 `pgc` 条目，但现实现会直接忽略，导致番剧/影视无法从历史记录直接播放。

相关实现与说明：

- 历史列表映射：`common_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
- 唯一键（UniqueKey）仅支持 `archive/live`：`common_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliKeys.kt`
- 播放接口仅接入点播 `playurl`（`/x/player/wbi/playurl`）与直播 `playUrl`：`common_component/src/main/java/com/xyoye/common_component/network/service/BilibiliService.kt`
- 直播播放适配说明（已落地）：`document/support/bilibili-live-playback.md`

---

## 2. 目标与范围

### 2.1 目标

- 在 Bilibili 媒体库的「历史记录」列表中：
  - `business=pgc` 条目被正确映射为可播放的 `StorageFile`（文件项）
  - 点击后可成功生成可播放 URL（优先 DASH），并由现有播放器链路正常播放
  - 播放记录（PlayHistory）可稳定恢复（UniqueKey 稳定、可解析）
  - 弹幕逻辑与点播保持一致：PGC 仍使用 `cid` 下载点播弹幕（不涉及直播 WS）

### 2.2 非目标

- 不支持“追番/收藏列表/番剧索引页”等新的目录入口。
- 不实现 PGC 分集列表浏览（season → episodes）。
- 不在本次设计中处理付费/大会员内容的“降级播放”（仅保证合理报错与提示）。

---

## 3. 现状与问题分析

### 3.1 历史记录 API 提供了 PGC 必要字段

`.tmp/bilibili-API-collect` 文档表明：历史记录 `business=pgc` 会给出 `epid/cid/oid(kid)` 等关键字段，可直接用于取流。

- 历史记录接口（web）：`/x/web-interface/history/cursor`
  - 文档：`.tmp/bilibili-API-collect/docs/historytoview/history.md`
  - `history.epid`：剧集 episode id（ep_id）
  - `history.cid`：视频 cid
  - `history.oid`：avid（PGC 对应稿件 avid）
  - `kid`：剧集 ssid（season id），用于关联 season（可选）

### 3.2 播放接口与现有点播接口不同

PGC 取流不走 `/x/player/wbi/playurl`，而是：

- `GET https://api.bilibili.com/pgc/player/web/playurl`
  - 文档：`.tmp/bilibili-API-collect/docs/bangumi/videostream_url.md`
  - 入参核心：`ep_id` 与 `cid`（任选其一即可），可同时带 `avid`
  - 返回结构根字段为 `result`（而非 `data`），需要单独的响应模型或适配层

### 3.3 当前实现忽略 PGC

`BilibiliStorage.mapHistoryItem()` 当前仅映射 `archive/live`，对 `pgc` 会直接 `return null`，因此 UI 不展示也无法播放。

---

## 4. 总体方案

核心思路：沿用当前工程“Storage → Repository → Retrofit Service → Data Models”的分层结构，将 PGC 作为第三种可播放对象纳入 **同一条链路**，通过扩展：

- UniqueKey 表达（新增 `pgc` Key）
- History Models（补充 `kid/epid` 等字段）
- Storage 映射（`mapHistoryItem` 支持 `pgc`）
- Repository + Service（新增 PGC playurl 方法）
- 播放 URL 生成（复用 DASH 选择与 MPD 生成）

并保持模块职责清晰、避免在 player/UI 层直接耦合 API 细节。

---

## 5. 关键设计

### 5.1 UniqueKey 设计（BilibiliKeys）

#### 5.1.1 新增 Key 类型

在 `BilibiliKeys` 中新增 `PgcKey`（或更明确的 `PgcEpisodeKey`）以表达 PGC 播放目标。

建议最小字段集合：

- `epId: Long`（必须）
- `cid: Long`（必须；用于点播弹幕与更稳定的取流）
- `seasonId: Long?`（可选；来自历史记录 `kid`，便于构造路径/展示）
- `avid: Long?`（可选；来自 `history.oid`，便于补参/日志排障）

#### 5.1.2 推荐的 UniqueKey 格式（稳定且可扩展）

为避免未来扩展（例如 season 目录）时出现歧义，建议显式区分 `ep/season`：

- PGC 单集（可播放）：
  - `bilibili://pgc/ep/{epId}?cid={cid}&sid={seasonId}&aid={avid}`
- （预留）PGC Season 目录（本次不实现）：
  - `bilibili://pgc/season/{seasonId}`

解析策略：

- `authority=pgc`
- `pathSegments[0]` 为 `ep`/`season`
- `ep` 场景下要求 `cid>0`，否则视为无效 Key（避免播放阶段才失败）

与现有 `archive/live` 的一致性：

- 同样使用 `bilibili://{type}/...` 作为跨模块唯一标识
- 解析逻辑集中在 `common_component`，player 侧只依赖解析结果，不直接拼装 URL

---

### 5.2 Storage 路径映射（BilibiliStorage / BilibiliStorageFile）

#### 5.2.1 历史记录目录下的路径规范

PGC 条目建议映射为文件项（不可展开目录）：

- `path = /history/pgc/{seasonId}/{epId}`
  - `seasonId` 缺失时可降级为 `0` 或直接省略一段：`/history/pgc/{epId}`

目的：

- 与现有 `/history/live/{roomId}`、`/history/{bvid}/{cid}` 风格一致
- 便于后续（可选）增加 “/history/pgc/{seasonId}/” 的分集目录扩展，而不破坏现有路径

#### 5.2.2 BilibiliStorageFile 增加工厂方法

在 `BilibiliStorageFile` companion 中新增：

- `pgcEpisodeFile(storage, seasonId, epId, cid, title, coverUrl, durationMs, payload)`

并确保：

- `playable = true`
- `uniqueKey = BilibiliKeys.pgcEpisodeKey(...)`

---

### 5.3 History Models 扩展（data_component）

为了从历史记录直接得到 PGC 取流必要参数，建议扩展：

#### 5.3.1 `BilibiliHistoryItem` 补齐字段

按 `.tmp/bilibili-API-collect/docs/historytoview/history.md`：

- `kid: Long`（PGC 时为 ssid / season id）
- `long_title: String`（副标题，通常是当前集标题）
- `show_title: String`（展示用“第 X 话 …”）
- `badge: String`（国创/番剧/电影等角标）
- `total: Int`（总集数）

这些字段用于更友好的展示标题（但播放链路只依赖 `kid/epid/cid`）。

#### 5.3.2 `BilibiliHistoryItemHistory` 补齐 `epid`

新增：

- `epid: Long`（仅 PGC 有效；用于 `pgc/player/web/playurl` 的 `ep_id`）

---

### 5.4 PGC 取流链路（Repository / Service）

#### 5.4.1 Retrofit Service 增加接口

在 `BilibiliService` 增加：

- `@GET("/pgc/player/web/playurl")`

注意返回根字段为 `result`（而非 `data`），建议新增一个通用包装模型（见 5.4.2）。

#### 5.4.2 新增 `BilibiliResultJsonModel<T>`（data_component）

为了不让 repository 层“手写 JSON 解析”，建议新增：

```kotlin
@JsonClass(generateAdapter = true)
data class BilibiliResultJsonModel<T>(
    val code: Int = 0,
    val message: String = "",
    val result: T? = null,
)
```

并在 repository 内提供与 `requestBilibili()` 对齐的 `requestBilibiliResult()`：

- 成功：`code==0 && result!=null`
- 失败：用 `BilibiliException.from(code, message)` 转换为统一异常

#### 5.4.3 Repository 增加 `pgcPlayurl(...)`

建议签名（保持与 archive playurl 的调用方式接近）：

- 入参：`epId/cid/avid?` + `BilibiliPlaybackPreferences`
- 出参：`Result<BilibiliPlayurlData>`

参数映射策略：

- 复用 `BilibiliPlayurlPreferencesMapper` 的能力（fnval/fnver/fourk/qn）
  - 但避免把 archive 专用参数（如 `platform`、`codecid`）硬塞给 PGC
  - 推荐将 mapper 拆成“共享参数生成 + endpoint 特定参数生成”，由 repository 选择

---

### 5.5 播放 URL 生成策略（BilibiliStorage.createPlayUrl）

PGC 的 `createPlayUrl()` 分支建议与 archive 保持一致的策略：

1. 解析 UniqueKey → `PgcKey(epId,cid,...)`
2. 读取播放偏好（同一个 `BilibiliPlaybackPreferencesStore`）
3. 调用 `repository.pgcPlayurl(...)`
4. 优先使用 DASH：
   - 选择 video：复用 `selectVideo()`（按 preferred codec + preferred qn）
   - 选择 audio：带宽最大
   - 生成本地 MPD：复用 `BilibiliMpdGenerator`，文件名仍使用 `uniqueKey.md5`
5. 若无 DASH，则回退到 `durl`（保底）
   - 需在文档中明确：若返回分段且播放器不支持无缝拼接，可能仍存在兼容性问题；后续可考虑代理/本地 m3u8 生成。

---

### 5.6 Header 策略

PGC 取流同样要求：

- `Referer: https://www.bilibili.com/`
- 适配的 `User-Agent`
- 登录态 Cookie（用于 480P+、会员内容等）

因此播放器侧 Header 仍由 `BilibiliStorage.getNetworkHeaders()` 统一提供（复用 `BilibiliHeaders.withCookie()`），不新增“PGC 特例”。

---

### 5.7 弹幕策略（player_component）

PGC 属于点播弹幕，仍由 `cid` 决定：

- 复用 `BilibiliDanmakuDownloader.getOrDownload(storageKey, cid)`
- 在 `PlayerDanmuViewModel.matchDanmu()` 中，除了 `ArchiveKey` 外，也应支持从 `PgcKey` 读取 `cid`
- 直播弹幕（WS/WSS）逻辑保持不变（仅 `LiveKey` 分支使用 `DanmuTrackResource.BilibiliLive`）

---

## 6. 模块改动清单（实现时参考）

### 6.1 `data_component`

- `BilibiliHistoryModels.kt`
  - 新增/补齐：`kid/long_title/show_title/badge/total`、`history.epid`
- 新增：`BilibiliResultJsonModel.kt`（或放在现有 `BilibiliJsonModel.kt` 同目录）

### 6.2 `common_component`

- `bilibili/BilibiliKeys.kt`
  - 新增 `pgc` Key + builder + parse
- `storage/file/impl/BilibiliStorageFile.kt`
  - 新增 `pgcEpisodeFile(...)`
- `storage/impl/BilibiliStorage.kt`
  - `mapHistoryItem()`：支持 `business=pgc`
  - `createPlayUrl()`：新增 PGC 分支调用 `repository.pgcPlayurl`
- `network/service/BilibiliService.kt`
  - 新增 `/pgc/player/web/playurl` 接口
- `bilibili/repository/BilibiliRepository.kt`
  - 新增 `pgcPlayurl` + `requestBilibiliResult`（或抽象成通用 response adapter）

### 6.3 `player_component`

- `PlayerDanmuViewModel.kt`
  - 解析 `PgcKey` 并按 `cid` 加载点播弹幕

---

## 7. 兼容性与迁移

- UniqueKey 新增 `pgc` 类型不会影响已有 `archive/live` 历史记录。
- 若未来修改 UniqueKey 格式，需要考虑 PlayHistory 的兼容（建议一次性定稿并保持稳定）。
- PGC 条目展示标题变化属于 UI 文案层面的软兼容，不影响播放数据结构。

---

## 8. 风险与对策

- **付费/大会员内容**：可能返回权限不足（如 `-403/-10403` 等）。
  - 对策：统一使用 `BilibiliException` 提示（保留 code/message），不做“自动降清晰度”隐式行为。
- **返回分段 durl**：部分 PGC 内容可能没有 DASH 或 DASH 不完整，导致拼接问题。
  - 对策：优先 DASH；若 durl 多段且播放失败，后续再评估是否需要引入本地代理/清单生成。
- **字段缺失/空值**：历史记录可能返回失效条目或字段为 0。
  - 对策：映射阶段严格校验 `epid/cid`，缺失则忽略该条目并依赖“空页跳过”逻辑取下一页。

---

## 9. 验收标准（建议）

- 登录 Bilibili 媒体库后，打开「历史记录」：
  - 能看到 `pgc` 条目（番剧/影视）
  - 点击后能正常开始播放（至少 480P/720P 取决于账号态与内容权限）
- 回归：
  - `archive` 单 P/多 P 播放与分页不受影响
  - `live` 播放与直播弹幕逻辑不受影响
- 弹幕：
  - PGC 条目可按 `cid` 下载并显示点播弹幕（如内容支持）

---

## 10. 手动验证步骤（建议）

1. App 中新增并登录 Bilibili 媒体库
2. 打开「Bilibili → 历史记录」，确认列表中同时存在：
   - 普通视频（archive）
   - 直播（live）
   - 番剧/影视（pgc）
3. 点击任意 `pgc` 条目：
   - 播放器能开始播放
   - 网络请求不出现 403/404（若出现，提示文案正确且包含 code）
4. 打开弹幕面板：
   - 若该 PGC 条目存在弹幕，确认可加载显示

---

## 11. 参考资料

- 历史记录（web, cursor）：`.tmp/bilibili-API-collect/docs/historytoview/history.md`
- 番剧取流（web playurl）：`.tmp/bilibili-API-collect/docs/bangumi/videostream_url.md`

