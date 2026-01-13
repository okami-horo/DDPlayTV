# Phase 1：数据模型（百度网盘存储源）

本文档从 `spec.md` 的实体与需求出发，整理“需要持久化/需要在内存中流转”的核心数据结构、字段约束与状态机。实现时如需对照官方字段与调用顺序，可结合：

- `spec.md`：`/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/spec.md`
- Baidu OpenAPI 摘要：`/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/baidu-pan-openapi.md`

## 1. 实体总览

| 实体 | 类型 | 归属层 | 用途 |
|---|---|---|---|
| 百度网盘存储源（Library） | 持久化（Room） | `:data_component` | 在“存储源列表”中展示/选择一个百度账号的入口；作为隔离边界（多账号） |
| 百度网盘授权态（AuthState） | 持久化（MMKV/可迁移至 Room） | `:core_storage_component` | 存储 `access_token/refresh_token`、过期时间、账号标识等；支撑自动刷新与失效处理 |
| 扫码授权会话（DeviceCodeSession） | 内存态（可选短期持久化） | `:core_storage_component` | 一次扫码登录流程的中间状态：二维码、device_code、过期、轮询间隔 |
| 网盘文件项（PanFile） | 内存态（API 模型 + StorageFile 适配） | `:data_component` + `:core_storage_component` | 列表展示、目录导航、播放入口 |
| 播放直链缓存（DlinkCache） | 内存态（可选短期持久化） | `:core_storage_component` | 缓存 `dlink` 与有效期，减少重复请求；失败时可强制刷新 |
| 播放请求（PlayAttempt） | 运行态（日志/可选埋点） | `:core_log_component` | 关键路径可观测：创建存储源/加载列表/启动播放 成功与失败原因（对应 FR-010） |

---

## 2. 百度网盘存储源（MediaLibraryEntity 扩展约定）

> `MediaLibraryEntity` 为 Room 实体，且对 `(url, media_type)` 有唯一索引；因此百度网盘必须设计“每账号唯一 url”，以满足 `FR-008` 多账号。

### 2.1 字段映射（建议）

| 字段 | 值 | 约束/说明 |
|---|---|---|
| `mediaType` | `BAIDU_PAN_STORAGE` | 需要新增枚举值与图标资源 |
| `displayName` | 默认 `netdisk_name`（可编辑） | 用于列表展示；编辑时允许用户自定义 |
| `url` | `baidupan://uk/<uk>` | **必须唯一**（同一 mediaType 下），建议使用 `uinfo.uk` 作为稳定标识 |
| `account/password/describe` | 不用于存 token | 避免“字段滥用”导致跨存储实现耦合；token 由 AuthState 单独管理 |
| `playerTypeOverride` | 复用现有机制 | 支撑“媒体库覆盖播放器内核选择”，满足 `FR-013` 场景验证 |

### 2.2 派生键（storageKey）

沿用项目内偏好存储思路，避免依赖自增 `id`：

`storageKey = "${mediaType.value}:${url.trim().removeSuffix("/")}"`  

该 key 用于：

- MMKV namespacing（AuthState、偏好等）
- token 刷新互斥锁的 key（单账号维度）

---

## 3. 百度网盘授权态（AuthState，持久化）

### 3.1 字段（建议最小集合）

| 字段 | 类型 | 必填 | 来源 | 说明 |
|---|---|---:|---|---|
| `accessToken` | `String` | 否 | `/oauth/2.0/token` | 用于调用 OpenAPI（常作为 query `access_token`） |
| `expiresAtMs` | `Long` | 否 | `expires_in`（秒） | `System.currentTimeMillis() + expires_in*1000` |
| `refreshToken` | `String` | 是（登录后） | `/oauth/2.0/token` | **单次可用且会旋转**：刷新成功后必须写入新值 |
| `scope` | `String` | 否 | token 返回 | 预期包含 `basic netdisk`（展示/诊断用） |
| `uk` | `Long` | 是（登录后） | `uinfo.uk` | 稳定账号标识，用于 url 唯一键与 UI 展示 |
| `netdiskName` | `String` | 否 | `uinfo.netdisk_name` | 存储源默认展示名来源 |
| `avatarUrl` | `String` | 否 | `uinfo.avatar_url` | UI 头像/展示 |
| `updatedAtMs` | `Long` | 是 | 本地写入 | 最近一次成功登录/刷新时间 |

### 3.2 不变式/校验规则

- `refreshToken` 存在时，`uk` 必须存在（否则无法唯一定位账号与存储源 url）。
- `expiresAtMs` 到期前可提前刷新（例如到期前 5~10 分钟），避免播放/浏览中途过期。
- **并发刷新互斥**：同一 `storageKey` 只能有一个刷新在进行；其余请求等待结果或复用结果。
- **写入原子性**：只有在刷新成功拿到“新 refresh_token”后，才覆盖持久化值；避免“刷新失败旧 token 也失效”导致不可恢复。

### 3.3 状态机（token）

```text
NoAuth
  └─(扫码授权成功)→ Authorized(accessToken valid)

Authorized
  ├─(到期/接口报鉴权失败)→ Refreshing
  ├─(用户移除存储源)→ Cleared(NoAuth)
  └─(用户撤销授权/刷新失败)→ NeedReAuth(NoAuth + UI 引导)

Refreshing
  ├─(刷新成功)→ Authorized(写入新 refresh_token)
  └─(刷新失败)→ NeedReAuth(清理 token 并提示重新扫码)
```

---

## 4. 扫码授权会话（DeviceCodeSession，内存态）

对应一次“二维码扫码授权登录”的中间态。

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `deviceCode` | `String` | `/oauth/2.0/device/code` | 轮询换 token 用 |
| `userCode` | `String` | `/oauth/2.0/device/code` | 可用于备用拼接二维码/展示 |
| `qrcodeUrl` | `String` | `/oauth/2.0/device/code` | UI 生成二维码图片的内容（或使用官方拼接规则） |
| `verificationUrl` | `String` | `/oauth/2.0/device/code` | 备用：网页输入 user_code |
| `expiresAtMs` | `Long` | `expires_in` | 会话过期需重新生成二维码 |
| `intervalSec` | `Int` | `interval` | 轮询间隔（官方强调一般不低于 5s） |
| `status` | enum | 本地 | `WaitingScan/WaitingConfirm/Expired/Success/Error`（UI 展示用） |

---

## 5. 网盘文件项（PanFile → StorageFile）

### 5.1 来自 OpenAPI list 的关键字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `fs_id` | `Long` | 文件唯一 ID，播放前获取 dlink 会用到 |
| `path` | `String` | 绝对路径（以 `/` 开头） |
| `server_filename` | `String` | 文件名 |
| `isdir` | `Int(0/1)` | 目录/文件 |
| `size` | `Long` | 文件大小（用于 LocalProxy contentLength 等） |
| `server_mtime/server_ctime` | `Long` | 修改/创建时间（秒） |
| `category` | `Int` | 1 视频 / 2 音频 / ...（用于是否可播放提示） |
| `thumbs` | object? | `web=1` 时可能返回缩略图 URL（用于列表封面） |

### 5.2 StorageFile 适配约定

- `filePath()/storagePath()`：使用 `path`（用于目录导航与历史定位）。
- `getFile<T>()` payload：携带 `PanFile`（至少包含 `fs_id/path/size/isdir/category/thumb`）。
- `isRootFile()`：当 `path == "/"` 为根目录。
- `isVideoFile()`：优先 `category==1`；对没有分类信息或分类不准的情况，可回退到扩展名判断（与项目现有 `isVideoFile(fileName)` 一致）。

---

## 6. 播放直链缓存（DlinkCache，内存态）

| 字段 | 类型 | 说明 |
|---|---|---|
| `fsId` | `Long` | key |
| `dlink` | `String` | 来自 `filemetas`，可能需要 unicode 解码 |
| `expiresAtMs` | `Long` | 官方提示有效期 8 小时（实现可用“获取时间 + 8h”估算） |
| `contentLength` | `Long` | 用于 LocalProxy 判断；优先使用 list 的 size 或 filemetas 的 size |

缓存策略建议：

- `createPlayUrl` 时优先使用未过期缓存；失败时强制刷新并回退到旧值（参考 `AlistStorage` 的“优先新直链，失败回退旧直链”策略）。

