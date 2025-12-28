# Phase 1：数据模型（Bilibili 历史记录媒体库接入）

本文从需求（`/home/tzw/workspace/DanDanPlayForAndroid/specs/003-add-bilibili-history/spec.md`）抽取实体与状态，明确哪些数据需要持久化、哪些仅为运行态/缓存，并给出必要的校验规则与状态流转。

---

## 1. 媒体库类型（MediaType）

### 1.1 新增枚举

- 实体：`MediaType.BILIBILI_STORAGE`
- 作用：作为“媒体库来源类型”注册到入口页、媒体库管理页、`StorageFactory`，用于区分图标/名称/路由与播放行为。

### 1.2 约束

- 本阶段仅支持“历史记录/普通视频（稿件）”（Spec: FR-010）。
- 多账号同时登录与账号切换不在本阶段范围内（Spec: Out of Scope）。

---

## 2. Bilibili 账号连接信息（持久化）

> 目标：满足“已连接/未连接判断”“Cookie 刷新/失效兜底”“断开清除”，同时避免把敏感 Cookie 直接写进日志或暴露给无关模块。

### 2.1 连接信息（建议持久化字段）

- 实体：`BilibiliAuthState`（逻辑实体，可落地为 Room 字段或 MMKV）
- 字段：
  - `libraryId: Int`：关联到 `MediaLibraryEntity.id`
  - `refreshToken: String?`：二维码登录成功返回（用于 Cookie 刷新链路）
  - `mid: Long?`：`DedeUserID` / `author_mid`（用于展示与调试）
  - `csrf: String?`：`bili_jct`（用于部分 POST，如 cookie refresh/confirm）
  - `updatedAt: Long`：最后一次成功登录/刷新时间（毫秒）

### 2.2 Cookie（建议独立于 Room）

- 实体：`BilibiliCookieJarStore`（逻辑实体）
- 字段：
  - `cookies: List<Cookie>`（按域名划分的集合：`api.bilibili.com`、`www.bilibili.com`、`passport.bilibili.com` 等）
  - `updatedAt: Long`
- 存储策略：
  - 采用可清理的持久化 CookieJar（落地为文件或 MMKV），并提供“导出 Cookie Header 字符串”能力供播放器使用。
  - 断开清除时必须彻底删除该存储（Spec: FR-007/FR-008）。

### 2.3 校验规则

- 认为“已连接”的最小条件：
  - Cookie 存在且包含 `SESSDATA`（或服务端校验通过），并且能成功访问需要鉴权的接口（如历史记录接口返回非 `-101`）。
- `refreshToken` 缺失时仍可短期使用，但稳定性不足（后续会更依赖“刷新/重登”兜底）。

### 2.4 Bilibili 播放偏好（持久化）

> 目标：把 `playurl` 的关键参数（画质/格式/编码/4K）做成用户可配置项，便于不同设备与网络环境下调整“清晰度 vs 兼容性”。

- 实体：`BilibiliPlaybackPreferences`（逻辑实体）
- 字段（建议）：
  - `playMode: AUTO|DASH|MP4`
  - `preferredQualityQn: Int`（例如 64=720P，80=1080P）
  - `preferredVideoCodec: AUTO|AVC|HEVC|AV1`（映射 `codecid`：7/12/13）
  - `allow4k: Boolean`（映射 `fourk=1` 与 `fnval` 的 4K 位）
- 存储策略：
  - 使用 MMKV 持久化，并按“媒体库唯一键”隔离：`storageKey = "${mediaType.value}:${url}"`（避免新建媒体库时无法拿到自增 id 导致无法落库）。
  - 断开连接并清除数据时，同时清理该媒体库对应的播放偏好（避免“换号后沿用旧偏好”）。
- 校验规则：
  - `preferredQualityQn <= 0` 视为“自动”；否则必须在允许的 qn 集合内（实现阶段兜底到默认值）。
  - `allow4k=false` 时，即使 qn=120 也不保证能取到 4K（实现阶段按参数约束兜底）。

---

## 3. 历史记录条目（运行态/缓存）

### 3.1 Bilibili 历史条目（API 映射）

- 实体：`BilibiliHistoryItem`
- 来源：`GET https://api.bilibili.com/x/web-interface/history/cursor`
- 字段（本阶段最小集）：
  - `bvid: String`（来源：`history.bvid`）
  - `cid: Long`（来源：`history.cid`）
  - `title: String`
  - `cover: String?`
  - `authorName: String?`
  - `viewAt: Long`（秒级时间戳）
  - `progressSec: Long`（秒，可能为 0/无效）
  - `durationSec: Long`（秒）
  - `videos: Int`（分 P 数；用于决定是否为目录）
  - `pageTitle: String?`（如 `show_title`/`history.part`，用于多 P 展示）

### 3.2 分页游标（运行态，可选持久化缓存）

- 实体：`BilibiliHistoryCursor`
- 字段：
  - `max: Long`
  - `viewAt: Long`
  - `business: String`
  - `ps: Int`
  - `hasMore: Boolean`（本地推导：当接口返回 list 为空或 cursor 不再变化时判定）

### 3.3 校验与过滤规则（满足 FR-010）

- 仅展示“普通视频（稿件）”：
  - 请求侧：`type=archive`
  - 结果侧：若仍出现非稿件条目，按 `history.business != "archive"` 过滤
- `bvid` 为空或 `cid <= 0`：视为不可播放，过滤或作为“不可播放”占位（本阶段推荐过滤）
- 进度修正：
  - `progressSec <= 0` → 视为从头播放
  - `durationSec > 0` 且 `progressSec >= durationSec` → 视为从头播放（避免直接 seek 到结尾）

---

## 4. StorageFile 映射（关键：uniqueKey / storagePath）

### 4.1 虚拟目录结构（对齐 Spec）

```text
/
  历史记录/
    [视频条目…]            # 单 P：文件；多 P：目录
```

### 4.2 `StorageFile` 关键字段定义（建议）

- `uniqueKey()`（稳定且可重建）：
  - 单 P/分 P 可播放条目：`bilibili://archive/{bvid}?cid={cid}`
  - 多 P 的“视频目录”：`bilibili://archive/{bvid}`
- `filePath()`（用于路径栏/路由，需层级清晰）：
  - 根：`/`
  - 历史记录目录：`/history/`
  - 视频目录（多 P）：`/history/{bvid}/`
  - 分 P 文件：`/history/{bvid}/{cid}`
- `storagePath()`：
  - 建议与 `filePath()` 同步（便于写入 `PlayHistoryEntity.storagePath`）
- `fileUrl()`：
  - 返回可解析标识（如 `bilibili://...`），真实播放链接由 `Storage.createPlayUrl()` 生成

---

## 5. 播放历史/进度（PlayHistoryEntity）

### 5.1 远端进度与本地进度

- 本地表：`PlayHistoryEntity`
- 关键字段：
  - `uniqueKey`：使用上文 `bilibili://...`
  - `storageId`：绑定到 Bilibili 媒体库实例（`MediaLibraryEntity.id`）
  - `videoPosition/videoDuration`：毫秒
  - `playTime`：可使用 `viewAt` 映射为日期（用于 UI 标签与排序时参考）

### 5.2 优先级规则

- 若本地存在 `uniqueKey + storageId` 记录：优先本地（应用内播放更及时）
- 否则：用远端 `progressSec/durationSec/viewAt` 构造“临时播放记录”用于展示与初次续播（是否落库由实现阶段决定）

---

## 5.3 Bilibili 弹幕缓存（cid -> danmuPath）

> 目标：在 Bilibili 媒体库播放时，自动加载“该 cid 对应的官方弹幕”。对播放器而言仍然是“加载一个本地弹幕文件轨道”，保持与现有弹幕渲染/开关/屏蔽逻辑一致。

### 5.3.1 弹幕文件（本地缓存）

- 实体：`BilibiliDanmakuCache`（逻辑实体，不强制落库）
- 关键字段：
  - `storageId: Int`：关联到 `MediaLibraryEntity.id`（用于清理）
  - `cid: Long`：Bilibili 分 P 的 cid
  - `danmuPath: String`：本地缓存文件路径（建议：`${PathHelper.getDanmuDirectory()}/bilibili_{cid}.xml`）
  - `updatedAt: Long`：最后一次成功下载时间（用于后续可能的过期策略）

### 5.3.2 与播放器/历史记录的衔接

- 轨道模型：复用 `LocalDanmuBean(danmuPath, episodeId)`；对 Bilibili 弹幕：
  - `danmuPath`：必填（本地 XML 路径）
  - `episodeId`：建议置空（避免与弹弹play 的 episodeId 语义混淆；本阶段也不做“发送弹幕”）
- 播放历史落库：下载/选择弹幕后，写入 `PlayHistoryEntity.danmuPath`（`episodeId` 为空即可），以便下次播放直接命中并加载。
- `cid` 的来源：由 `StorageFile.uniqueKey()` 解析得到（`bilibili://archive/{bvid}?cid={cid}`），避免依赖标题或网络再查询。

### 5.3.3 清理规则（满足 FR-008）

- 当用户对某个 Bilibili 媒体库执行“断开连接并清除数据”时：
  1) 清理该 `storageId` 下的 `PlayHistoryEntity`（既有需求）；  
  2) 额外删除这些历史记录中引用的 `danmuPath` 文件（仅删除确认为 Bilibili 弹幕缓存命名/路径下的文件，避免误删其他来源弹幕）；  
  3) 清理完成后，重新进入 Bilibili 媒体库不得残留可直接加载个人内容的弹幕缓存入口（SC-004）。

---

## 6. 状态流转（State Transitions）

### 6.1 账号连接状态

```text
未连接
  └─(发起扫码)→ 扫码中（二维码有效期内）
        ├─(确认登录成功)→ 已连接
        ├─(二维码失效/超时)→ 未连接（提示重试）
        └─(用户取消)→ 未连接

已连接
  ├─(Cookie 失效/-101)→ 连接失效（提示重新扫码）
  └─(断开并清除)→ 未连接（同时清理本地数据）
```

### 6.2 历史列表加载状态

```text
空闲
  ├─(首次进入/刷新)→ 加载中 → 成功(可继续加载更多/无更多)
  └─(失败)→ 失败（可重试）
```
