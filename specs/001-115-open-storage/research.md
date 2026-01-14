# Phase 0：调研与关键决策（115 Open 存储源）

本文档用于把实现所需的关键事实、约束与技术决策一次讲清，并尽量标注“证据来源”（项目内整理文档、OpenList 实现、115-sdk-go 实现）以便后续实现时可快速对照。

- Feature spec：`spec.md`
- 115 Open API 摘要整理（已由项目内先行整理）：`115-open-openapi.md`

## 关键结论（TL;DR）

1. **鉴权**：产品侧仅支持“用户手动填写 `access_token` + `refresh_token`”（与 OpenList 约束一致）；新增/编辑时通过 `GET /open/user/info` 校验 token，并用 `user_id` 作为账号唯一标识（`uid`）。
2. **多账号唯一键**：`MediaLibraryEntity` 对 `(url, media_type)` 有唯一约束；因此 115 Open 存储源的 `url` 设计为 `115open://uid/<uid>`，以支持多账号与天然隔离（`storageKey = "${mediaType.value}:${url}"`）。
3. **token 自动刷新语义**：参考 115-sdk-go：当 proapi 返回 `state=false` 且 `code==99` 或 `code` 以 `401` 开头时，触发 `POST https://passportapi.115.com/open/refreshToken` 刷新 token，并重试一次原请求；刷新会返回新的 `refresh_token`，需按账号维度互斥刷新与原子持久化。
4. **文件浏览**：使用 `GET /open/ufile/files` 做目录分页（`offset/limit`，默认 200，最大 1150）；排序映射到 `o=file_name|file_size|user_utime|file_type` 与 `asc=1|0`，并保持与百度网盘存储源的排序选项一致。
5. **播放**：使用 `POST /open/ufile/downurl` 以 `pick_code` 获取直链；必须统一注入 `User-Agent`。为避免 115 风控（高频随机 Range 触发 403），对 mpv/VLC 建议默认走 `LocalProxy/HttpPlayServer` 代理策略（参考 `document/support/mpv-115-proxy.md`），并支持直链失效/Range 不支持时的刷新回退。

---

## 决策 1：鉴权方式采用“用户手动填 token”（与 OpenList 一致）

- **Decision**：在应用新增/编辑“115 Open”存储源时，仅提供 `access_token` 与 `refresh_token` 两个输入项；不实现账号密码登录、不模拟网页登录（Cookie）。
- **Rationale**：
  - 与 `spec.md` 的产品约束一致（FR-002），且与 OpenList 的 115 Open driver 一致。
  - 可将授权态标准化为 token 管理器（MMKV 持久化 + 自动刷新），避免 UI 引入复杂的网页登录链路。
- **Alternatives considered**：
  - 设备码登录（二维码）内置到 App：可降低用户获取 token 门槛，但会引入 `client_id` 申请、轮询状态机、更多 UI 分支；不在本期范围。
  - 账号密码/短信：违背 115 Open 的集成约束与产品要求。
- **Sources（证据）**：
  - `specs/001-115-open-storage/spec.md`（FR-002）
  - `specs/001-115-open-storage/115-open-openapi.md`（鉴权约束说明）
  - `OpenList/drivers/115_open/meta.go`（Addition 里定义 AccessToken/RefreshToken 配置项）

## 决策 2：用 `user_id` 构造 `MediaLibraryEntity.url` 作为多账号唯一标识

- **Decision**：保存 115 Open 存储源时，先调用 `GET https://proapi.115.com/open/user/info` 获取 `user_id`，并固定：
  - `MediaLibraryEntity.mediaType = MediaType.OPEN_115_STORAGE`（名称待定）
  - `MediaLibraryEntity.url = 115open://uid/<user_id>`
- **Rationale**：
  - `media_library` 表对 `(url, media_type)` 有唯一索引；若 url 不能区分账号，将无法多账号（违反 FR-008/FR-014）。
  - `user_id` 是稳定账号标识，可作为隔离边界（MMKV key、互斥刷新锁、缓存清理）。
- **Alternatives considered**：
  - 仅使用自增 `id`：新增流程未落库前难以稳定引用；且项目内偏好存储普遍以 url 作为 key。
  - 将 token 写入 `MediaLibraryEntity.password` 等通用字段：会污染通用字段语义、增加跨存储实现耦合与泄露风险。
- **Sources（证据）**：
  - `specs/001-115-open-storage/spec.md`（FR-014）
  - `specs/001-115-open-storage/115-open-openapi.md`（3.1 用户信息）
  - `data_component/src/main/java/com/xyoye/data_component/entity/MediaLibraryEntity.kt`（唯一索引）

## 决策 3：token 刷新策略遵循 115-sdk-go：自动刷新 + 重试一次

- **Decision**：在 Repository 层实现统一请求封装：
  - proapi 失败时若 `state=false` 且（`code==99` 或 `code` 以 `401` 开头），触发 RefreshToken 并重试一次
  - RefreshToken 成功后，更新并持久化新的 `access_token/refresh_token/expires_in`
  - 刷新过程按 `storageKey` 加互斥锁，避免并发刷新导致 refresh_token 被重复使用
- **Rationale**：
  - 115 的 proapi 失败主要以“业务响应体”表达（HTTP 200 + `state=false`），不能只依赖 HTTP 状态码。
  - RefreshToken 返回新的 `refresh_token`（旋转）；实现必须做到“串行刷新 + 原子写入”，否则容易出现不可恢复的失效。
- **Alternatives considered**：
  - 完全不自动刷新：会频繁打断用户，违反 FR-010。
  - 仅按时间刷新、不按错误码触发：会在 access_token 提前失效/撤销时无法及时恢复。
- **Sources（证据）**：
  - `specs/001-115-open-storage/115-open-openapi.md`（1.4/1.5/2.4）
  - `OpenList/drivers/115_open/driver.go`（Init 注册 onRefreshToken 回调持久化）
  - `OpenListTeam/115-sdk-go`（通过 `gh` 查阅）：
    - `request.go`（`resp.Code == 99 || Is401Started(resp.Code)` 触发刷新并重试）
    - `auth.go`（RefreshToken 返回新的 access/refresh/expires_in）

## 决策 4：目录浏览采用 `ufile/files` 分页；排序字段映射与现有存储源对齐

- **Decision**：目录浏览使用 `GET /open/ufile/files`，按 `offset/limit` 分页直到无更多；排序映射：
  - 名称：`o=file_name`
  - 大小：`o=file_size`
  - 修改时间：`o=user_utime`
  - 其他（如类型/后缀）：`o=file_type`（仅在现有 UI 有对应入口时启用）
  - 升/降序：`asc=1/0`
  - 固定 `show_dir=1` 显示目录
- **Rationale**：
  - 与 115 Open SDK/OpenList driver 的目录列举方式一致，分页参数明确，适配“大目录渐进加载”。
  - 保持 UI 的排序选项与百度网盘一致，减少用户学习成本（FR-011/FR-015）。
- **Alternatives considered**：
  - 一次性拉全量：大目录会造成首屏延迟与内存压力，不符合 SC-002。
- **Sources（证据）**：
  - `specs/001-115-open-storage/115-open-openapi.md`（4.2 列目录）
  - `OpenList/drivers/115_open/driver.go`（List()：offset/limit 循环直到 count）
  - `OpenListTeam/115-sdk-go`（通过 `gh` 查阅）：`fs.go`（GetFilesReq: `o`/`asc`/`show_dir`/limit max 1150）

## 决策 5：播放采用 `downurl` 直链 + 统一 `User-Agent`；mpv/VLC 优先走本地代理

- **Decision**：播放链路统一为：
  1. `POST /open/ufile/downurl`（pick_code）获取直链
  2. 为直链访问统一注入 `User-Agent`（默认沿用 OpenList 的 browser-like UA；允许后续按需调整）
  3. 对 mpv/VLC：返回 `LocalProxy.wrapIfNeeded(...)` 的本地代理 URL（上游 headers 包含 UA），以控制 Range 行为降低 403 风险；对 Media3/Exo：直接返回直链 URL 并通过 `Storage.getNetworkHeaders(file)` 提供 UA
- **Rationale**：
  - `downurl` 是 OpenList driver 的核心闭环（List → Link → 播放）。
  - 项目已有“115 Range 风控”经验：mpv 会高频随机 Range，易触发 403；复用 `HttpPlayServer` 的 Range 改写/限频/降级策略可以显著提升“能播”概率（FR-013）。
- **Alternatives considered**：
  - 直接把直链喂给 mpv：风险较高且难以在播放器侧控制 Range 行为。
  - 统一全播放器都走代理：会增加本地 IO/延迟；首期仅对高风险内核（mpv/VLC）优先代理。
- **Sources（证据）**：
  - `specs/001-115-open-storage/115-open-openapi.md`（4.3 downurl + UA 提示）
  - `document/support/mpv-115-proxy.md`（Range 风控与 HttpPlayServer 方案）
  - `OpenList/drivers/115_open/driver.go`（Link() 返回 URL + User-Agent）
  - `OpenList/drivers/base/client.go`（UserAgent 常量）

## 决策 6：模块归属与解耦（遵守依赖治理）

- **Decision**：遵循“UI / 存储实现 / 网络声明 / 数据模型”分层：
  - `:storage_component`：新增/编辑 115 Open UI（token 输入、脱敏、测试与保存）
  - `:core_storage_component`：实现 `Open115Storage` + Repository + token 管理/缓存；只暴露 Storage 抽象，不外泄 115 Open 细节
  - `:core_network_component`：声明 Retrofit Service（proapi/passportapi）
  - `:data_component`：承载 API model 与 `MediaType`/图标（跨模块共享类型）
- **Rationale**：保持与“百度网盘存储源”一致的架构落点，避免 UI/播放器直接依赖 115 Open 细节，降低耦合与未来替换成本。
- **Alternatives considered**：
  - 新建独立 `:open115_component`：边界更清晰但会引入新的模块治理与依赖矩阵调整；首期不做。

