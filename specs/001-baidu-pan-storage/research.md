# Phase 0：调研与关键决策（百度网盘存储源）

本文档用于把实现所需的关键事实、约束与技术决策一次讲清，并尽量标注百度网盘开放平台的原文链接，便于后续实现时直接对照官方说明。

- Feature spec：`/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/spec.md`
- Baidu OpenAPI 摘要整理（已由项目内先行整理）：`/home/tzw/workspace/DanDanPlayForAndroid/specs/001-baidu-pan-storage/baidu-pan-openapi.md`

## 关键结论（TL;DR）

1. **授权**：使用 OAuth2 **设备码模式（device code）**生成二维码并轮询换取 token；`scope` 在官方文档中固定为 `basic,netdisk`；成功后**持久化 `refresh_token`** 并自动刷新 `access_token`。
2. **播放**：MVP 优先走 `filemetas(dlink=1)` 获取 `dlink` 直链，拼接 `access_token` 后作为可播放 URL；并且必须带 `User-Agent: pan.baidu.com`；mpv/VLC 走 `LocalProxy/HttpPlayServer` 注入 headers、稳定 Range。
3. **多账号**：`MediaLibraryEntity` 对 `(url, media_type)` 有唯一约束；因此百度网盘存储源的 `url` 必须按账号区分。用 `uinfo.uk` 作为稳定账号标识，写入 `url`（例如 `baidupan://uk/<uk>`），从而支持多个存储源并天然隔离 MMKV key。
4. **refresh_token 旋转与并发**：`refresh_token` **只支持使用一次**，且刷新失败旧 token 也会失效；必须做**串行刷新（互斥）+ 原子持久化（成功后再覆盖）**，避免并发刷新导致自毁式登出。
5. **模块分层**：UI 在 `:storage_component`；存储实现与百度业务仓库在 `:core_storage_component`；Retrofit Service 在 `:core_network_component`；数据模型与 `MediaType` 在 `:data_component`；密钥注入在 `:core_system_component`。不新增 feature↔feature 依赖。

---

## 决策 1：OAuth 授权采用设备码模式（二维码扫码确认）

- **Decision**：仅实现百度开放平台 OAuth2 **设备码模式**（二维码扫码确认），不实现账号密码登录、不模拟网页登录（Cookie/BDUSS）。
- **Rationale**：
  - 与 `spec.md` 的产品要求一致（官方扫码授权、无需用户输入账号密码）。
  - 能将登录态规范化为 `access_token/refresh_token`，便于在存储层自动刷新与失效处理。
- **Alternatives considered**：
  - 授权码模式（`response_type=code` + `redirect_uri`）：需要回调承接与更多 Web 交互；首期不选。
  - 模拟网页登录（cookie/BDUSS）：不符合“仅用开放平台”的约束，且风险高/易失效。
- **Implementation notes（落地要点）**：
  - 获取二维码：`GET https://openapi.baidu.com/oauth/2.0/device/code`
  - 轮询换取 token：`GET https://openapi.baidu.com/oauth/2.0/token?grant_type=device_token&code=<device_code>`
  - 轮询间隔：遵循返回 `interval`（官方文档提示一般不低于 5 秒），UI 需要展示“等待扫码/等待确认/过期/失败”等状态。
- **Sources（官方原文）**：
  - 设备码模式授权：`https://pan.baidu.com/union/doc/fl1x114ti`
  - 授权码模式（备用参考）：`https://pan.baidu.com/union/doc/al0rwqzzl`

## 决策 2：scope 与最小权限策略

- **Decision**：按官方文档使用固定 `scope=basic,netdisk`；产品层面的“最小权限”体现为：仅实现“浏览 + 读取/播放”，不实现写入/管理类能力，并在 UI/文案中明确授权用途。
- **Rationale**：
  - 当前官方文档对该授权模式的 `scope` 给出固定值；在无法进一步细分的前提下，能力范围应通过“功能不实现”来体现最小化。
- **Alternatives considered**：
  - 试图申请更少 scope：与文档不一致，存在不可用风险。
  - 申请更大 scope：与 `spec.md` 的最小权限约束冲突。
- **Sources（官方原文）**：
  - 设备码模式授权：`https://pan.baidu.com/union/doc/fl1x114ti`
  - 授权码模式授权：`https://pan.baidu.com/union/doc/al0rwqzzl`

## 决策 3：token 生命周期与 refresh_token 旋转处理

- **Decision**：实现“可恢复”的 token 管理器：
  - 持久化字段：`access_token`、`expires_in`/过期时间、`refresh_token`、`updated_at`、`uk`、`netdisk_name/avatar_url`（用于展示与多账号区分）。
  - 刷新策略：在 `access_token` 过期或接口返回鉴权失败时，使用 `refresh_token` 刷新并持久化新的 `refresh_token`。
  - 并发策略：按 storageKey（单账号维度）加互斥锁，确保同一账号不会出现并发刷新（避免 refresh_token 被重复使用）。
- **Rationale**：
  - 百度明确：`refresh_token` 单次可用且失败会让旧 token 失效；这要求实现层必须把“刷新”当作一次不可并发的关键区段。
- **Alternatives considered**：
  - 仅在失败时让用户重新扫码：体验差，违背 `FR-012`。
  - 允许并发刷新：会触发 refresh_token 复用导致失效，稳定性不可控。
- **Sources（官方原文）**：
  - 设备码模式授权（`expires_in`、refresh 规则）：`https://pan.baidu.com/union/doc/fl1x114ti`
  - 授权码模式授权（同样的 refresh 规则）：`https://pan.baidu.com/union/doc/al0rwqzzl`

## 决策 4：文件浏览 API 选型（list + 分页；可选 search）

- **Decision**：目录浏览使用 `xpan/file?method=list`，支持 `dir=/` 起步与 `start/limit` 分页；搜索使用 `xpan/file?method=search`（注意官方限制固定返回量）。
- **Rationale**：
  - `list` 与“目录层级浏览”直接匹配；分页参数明确，适合大目录渐进加载。
  - `search` 用于 P2 场景的快速定位，但受官方固定 `num` 限制，需要在 UI 上说明“结果可能非全量”或做二次过滤/提示。
- **Alternatives considered**：
  - `listall` 递归：更容易触发频控且返回规模大，不适合作为默认浏览方式（可作为后续增强的“全盘索引/媒体库扫描”能力）。
- **Sources（官方原文）**：
  - 目录列表 list：`https://pan.baidu.com/union/doc/nksg0sat9`
  - 搜索 search：`https://pan.baidu.com/union/doc/zksg0sb9z`
  - 递归 listall：`https://pan.baidu.com/union/doc/Zksg0sb73`

## 决策 5：播放 URL 选型（dlink 直链 + Range；不做 streaming(m3u8) MVP）

- **Decision**：MVP 播放链路统一使用：
  1. `filemetas(method=filemetas, fsids=[...], dlink=1)` 获取 `dlink`
  2. 访问 `dlink` 时拼接 `access_token`，并带 `User-Agent: pan.baidu.com`
  3. 对 mpv/VLC：返回 `LocalProxy.wrapIfNeeded(...)` 生成的本地代理 URL；对 Exo/Media3：返回直链 URL 并通过 `Storage.getNetworkHeaders(file)` 提供 headers
- **Rationale**：
  - `dlink` 路径对“原画/通用播放器 + Range seek”更友好，且可统一适配多播放内核。
  - `streaming`（m3u8）涉及广告 token/等待时间、转码状态轮询、特定 UA/Host 约束与更多错误分支；首期会显著扩大复杂度与耦合面。
- **Alternatives considered**：
  - `streaming` m3u8：后续可作为“弱设备/带宽自适应”的增强路径，在播放器/内核兼容明确后再引入。
- **Sources（官方原文）**：
  - filemetas（返回 dlink）：`https://pan.baidu.com/union/doc/Fksg0sbcm`
  - dlink 下载/播放（UA、Range、有效期、302）：`https://pan.baidu.com/union/doc/pkuo3snyp`
  - streaming（m3u8，复杂流程）：`https://pan.baidu.com/union/doc/aksk0bacn`

## 决策 6：多账号与存储源唯一键（MediaLibraryEntity.url 设计）

- **Decision**：将每个百度网盘账号作为一个独立 `MediaLibraryEntity`：
  - `mediaType = BAIDU_PAN_STORAGE`
  - `url = baidupan://uk/<uk>`（uk 来自 uinfo，用于保证 `(url, media_type)` 唯一）
  - `displayName` 默认使用 `netdisk_name`（可允许用户手动改名）
  - `account/password/describe` 不承载 token（避免“字段滥用”造成跨存储实现耦合）
- **Rationale**：
  - Room 表 `media_library` 对 `(url, media_type)` 有唯一索引；若 url 固定为同一个 host，将无法添加多个账号（违反 `FR-008`）。
  - 使用 `uk` 作为稳定标识，既满足唯一约束，又便于后续做“账号切换/清理/隔离”。
- **Alternatives considered**：
  - 用自增 `id` 做 key：新增存储源时还未落库，难以在扫码流程中稳定引用（项目内 Bilibili 偏好存储也因此选择了 url 作为 key）。
  - 把 refresh_token 放进 `password` 字段：实现上看似简单，但会把“百度 OAuth”耦合进通用字段语义，长期维护成本高。
- **Sources**：
  - uinfo（uk 字段）：`https://pan.baidu.com/union/doc/pksg0s9ns`
  - 项目内约束：`MediaLibraryEntity` 的唯一索引（见 `data_component/.../MediaLibraryEntity.kt`）

## 决策 7：错误码与稳定性（鉴权失效、频控、直链失效）

- **Decision**：实现统一错误映射策略：
  - token/授权错误（如 `-6`、`31045`）：触发“刷新 token”，刷新失败则标记为“需要重新授权”
  - 直链/防盗链错误（如 `31326`、`31360`）：优先检查 headers/有效期；必要时重新获取 dlink 并重试
  - 频控（如 `20012`、`31034`）：在 Repository 层做退避/节流，并在 UI 侧给出可理解提示
- **Rationale**：百度很多失败以“业务错误码”表达（并非纯 HTTP 状态）；必须在数据层统一识别与上抛，否则 UI/播放器只能看到“未知错误”。
- **Sources（官方原文）**：
  - 错误码总表：`https://pan.baidu.com/union/doc/okumlx17r`
  - listall 频控提示：`https://pan.baidu.com/union/doc/Zksg0sb73`

## 决策 8：模块归属与解耦策略（遵守依赖治理）

- **Decision**：遵循“UI/基础设施/数据模型”分层：
  - `:core_network_component`：只新增 Retrofit Service 声明与必要的 header/Query 约束（不写业务逻辑）
  - `:core_storage_component`：实现 BaiduPanStorage + Repository + token/dlink 管理（业务逻辑集中，不外泄实现细节）
  - `:storage_component`：实现新增/编辑 UI 与扫码授权交互；通过 Storage/Repository 暴露的最小接口驱动，不直接拼接 OpenAPI URL
  - `:core_system_component`：统一注入与读取 `client_id/client_secret`
  - `:data_component`：承载 OpenAPI 模型与 `MediaType`（跨模块共享类型）
- **Rationale**：把“对外契约（Storage 接口）”与“对内实现（Baidu OpenAPI）”隔离，避免 UI/播放器直接依赖 Baidu 细节，从而降低耦合与未来替换成本。
- **Alternatives considered**：
  - 新建独立模块 `:baidu_component`：分层更清晰但会引入新的模块治理与依赖矩阵调整；首期不做，但保留演进空间。

