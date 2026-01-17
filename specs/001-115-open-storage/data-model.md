# Phase 1：数据模型（115 Open 存储源）

本文档从 `spec.md` 的实体与需求出发，整理“需要持久化/需要在内存中流转”的核心数据结构、字段约束与状态机。实现时如需对照官方字段与调用顺序，可结合：

- `spec.md`：`spec.md`
- 115 Open API 摘要：`115-open-openapi.md`

## 1. 实体总览

| 实体 | 类型 | 归属层 | 用途 |
|---|---|---|---|
| 115 Open 存储源（Library） | 持久化（Room） | `:data_component` | 在“存储源列表”中展示/选择一个 115 账号入口；作为多账号隔离边界 |
| 115 Open 授权态（AuthState） | 持久化（MMKV/可迁移至 Room） | `:core_storage_component` | 存储 `access_token/refresh_token`、过期时间、账号标识等；支撑自动刷新与失效处理 |
| 115 文件项（FileItem） | 内存态（API 模型 + StorageFile 适配） | `:data_component` + `:core_storage_component` | 列表展示、目录导航、播放入口 |
| 播放直链缓存（DownUrlCache） | 内存态（可选短期持久化） | `:core_storage_component` | 缓存 `downurl` 直链与 UA；失败时可强制刷新并回退 |
| 目录信息缓存（FolderInfoCache，可选） | 内存态 | `:core_storage_component` | 通过 `folder/get_info` 缓存目录树/面包屑，用于搜索结果构造稳定的 `filePath` |
| 播放请求（PlayAttempt） | 运行态（日志/可选埋点） | `:core_log_component` | 关键路径可观测：创建存储源/加载列表/启动播放 成功与失败原因 |

---

## 2. 115 Open 存储源（MediaLibraryEntity 扩展约定）

> `MediaLibraryEntity` 为 Room 实体，且对 `(url, media_type)` 有唯一索引；因此 115 Open 必须设计“每账号唯一 url”，以满足 `FR-008/FR-014` 多账号。

### 2.1 字段映射（建议）

| 字段 | 值 | 约束/说明 |
|---|---|---|
| `mediaType` | `OPEN_115_STORAGE`（名称待定） | 需要新增枚举值与图标资源 |
| `displayName` | 默认 `user_name`（可编辑） | 用于列表展示；编辑时允许用户自定义 |
| `url` | `115open://uid/<uid>` | **必须唯一**（同一 mediaType 下）；`uid` 来自 `GET /open/user/info` 的 `user_id` |
| `account/password/describe` | 不用于存 token | 避免“字段滥用”导致跨存储实现耦合；token 由 AuthState 单独管理 |
| `playerTypeOverride` | 复用现有机制 | 支撑“媒体库覆盖播放器内核选择”，满足 `FR-013` 验收 |

### 2.2 派生键（storageKey）

沿用项目内偏好存储思路，避免依赖自增 `id`：

`storageKey = "${mediaType.value}:${url.trim().removeSuffix("/")}"`  

该 key 用于：

- MMKV namespacing（AuthState、偏好等）
- token 刷新互斥锁的 key（单账号维度）
- 清理（移除存储源时精准清理该账号 token）

---

## 3. 115 Open 授权态（AuthState，持久化）

### 3.1 字段（建议最小集合）

| 字段 | 类型 | 必填 | 来源 | 说明 |
|---|---|---:|---|---|
| `accessToken` | `String` | 否 | `refreshToken` 返回 / 用户输入 | proapi 调用使用 `Authorization: Bearer <access_token>` |
| `expiresAtMs` | `Long` | 否 | `expires_in`（秒） | `System.currentTimeMillis() + expires_in*1000` |
| `refreshToken` | `String` | 是（配置后） | 用户输入/刷新返回 | 刷新会返回新的 refresh_token（旋转）；需更新持久化值 |
| `uid` | `String` | 是（配置后） | `/open/user/info.user_id` | 稳定账号标识，用于 `MediaLibraryEntity.url` 与多账号隔离 |
| `userName` | `String` | 否 | `/open/user/info.user_name` | 默认展示名来源 |
| `avatarUrl` | `String` | 否 | `/open/user/info.user_face_*` | UI 头像/展示 |
| `spaceTotal` | `Long` | 否 | `/open/user/info.rt_space_info` | 展示/诊断用（可选） |
| `spaceUsed` | `Long` | 否 | `/open/user/info.rt_space_info` | 展示/诊断用（可选） |
| `updatedAtMs` | `Long` | 是 | 本地写入 | 最近一次成功登录/刷新时间 |

### 3.2 不变式/校验规则

- `refreshToken` 存在时，`uid` 必须存在（否则无法构造唯一 URL/隔离 key）。
- `expiresAtMs` 到期前可提前刷新（例如到期前 5~10 分钟），避免浏览/播放中途过期。
- **并发刷新互斥**：同一 `storageKey` 只能有一个刷新在进行；其余请求等待结果或复用结果。
- **写入原子性**：只有在刷新成功拿到“新 refresh_token”后，才覆盖持久化值；刷新失败不得覆盖旧值（避免不可恢复）。
- **脱敏**：任何日志/错误提示不得输出完整 token；UI 仅允许遮罩展示（FR-012/FR-016）。

### 3.3 状态机（token）

```text
NoAuth
  └─(用户填 token + user/info 校验成功)→ Authorized(accessToken valid)

Authorized
  ├─(到期/接口 state=false 且 code=99/401xxxx)→ Refreshing
  ├─(用户移除存储源)→ Cleared(NoAuth)
  └─(刷新失败/用户撤销授权)→ NeedReAuth(NoAuth + UI 引导更新 token)

Refreshing
  ├─(刷新成功)→ Authorized(写入新 refresh_token)
  └─(刷新失败)→ NeedReAuth(提示更新 token；不强制清理但禁止继续访问)
```

---

## 4. 115 文件项（OpenAPI 模型 → StorageFile）

### 4.1 来自 `ufile/files` 的关键字段（SDK `GetFilesResp_File`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `fid` | `String` | 文件/文件夹 ID |
| `pid` | `String` | 父目录 ID |
| `fc` | `String` | `0` 目录 / `1` 文件 |
| `fn` | `String` | 名称 |
| `pc` | `String` | pick_code（获取 downurl 直链用） |
| `sha1` | `String` | 文件 sha1（可选） |
| `fs` | `Long` | 文件大小（字节） |
| `upt/uet/uppt` | `Long` | 时间戳（修改/更新时间/上传时间） |
| `isv` | `Long` | 是否视频文件（1/0） |
| `ico` | `String` | 后缀名 |
| `thumb` | `String` | 缩略图（可选） |

### 4.2 来自 `ufile/search` 的关键字段（SDK `SearchFilesResp` item）

| 字段 | 类型 | 说明 |
|---|---|---|
| `file_id` | `String` | 文件/文件夹 ID |
| `parent_id` | `String` | 父目录 ID |
| `file_name` | `String` | 文件名 |
| `file_size` | `String` | 文件大小（字符串） |
| `pick_code` | `String` | pick_code（获取 downurl 直链用） |
| `file_category` | `String` | `1` 文件 / `0` 文件夹 |
| `ico` | `String` | 后缀名 |

### 4.3 StorageFile 适配约定（建议）

为与现有目录导航/播放历史逻辑对齐，建议采用“ID 链路路径”作为 `filePath/storagePath`：

- 根目录：`filePath = "/"`（与 `StorageFileActivity.openDirectory(null)` 默认 route 对齐）
- 目录：`filePath = "<parentPath>/<fid>"`（示例：`/12345/67890`）
- 文件：`filePath = "<parentPath>/<fid>"`（与目录一致的拼接规则）

配套字段建议：

- `fileUrl()`：使用稳定唯一值（建议 `115open://file/<fid>`），用于 `uniqueKey` 计算与历史定位。
- `getFile<T>()` payload：携带原始 API model（至少包含 `fid/pid/fc/fn/pc/fs/isv/ico/thumb`）。
- `isRootFile()`：根目录文件特殊处理（如 `fid=="0"` 或 `filePath=="/"`）。
- `isVideoFile()`：
  - 列表：优先 `isv==1`；否则回退到扩展名判断（与现有 `isVideoFile(fileName)` 一致）
  - 搜索：建议请求时固定 `type=4`（视频）并 `fc=2`（仅文件），再做扩展名兜底

---

## 5. 播放直链缓存（DownUrlCache，内存态）

| 字段 | 类型 | 说明 |
|---|---|---|
| `fid` | `String` | key |
| `pickCode` | `String` | downurl 参数（便于刷新） |
| `url` | `String` | 直链（`downurl` 返回） |
| `userAgent` | `String` | 直链访问需要的 UA |
| `fileSize` | `Long` | 用于 LocalProxy/HttpPlayServer 的 contentLength |
| `updatedAtMs` | `Long` | 最近一次获取时间 |

缓存策略建议：

- `createPlayUrl` 时优先使用未过期缓存；若播放失败（如 403/404/416）可强制刷新并重试一次。
- 对 mpv/VLC：通过 `LocalProxy.wrapIfNeeded(..., upstreamHeaders={User-Agent}, onRangeUnsupported=refreshSupplier)` 支持 Range 不可用时回退刷新。

> 注：115 直链有效期/刷新策略以实际响应为准；实现可先用“短 TTL（例如 1~5 分钟）+ 失败强刷”的保守策略。

---

## 6. 目录信息缓存（FolderInfoCache，可选增强）

若要让“搜索结果”具备更稳定的 `filePath/storagePath`（并让“最近播放目录标记”在后续进入父目录时仍可生效），可在搜索结果构造阶段按 `parent_id` 批量调用：

- `GET /open/folder/get_info?file_id=<folderId>`

并缓存：

- `folderId -> breadcrumbIds`（从根到当前目录的 id 列表）

从而把搜索结果文件的 `filePath` 构造为：

`"/<cid1>/<cid2>/.../<parentId>/<fileId>"`。

