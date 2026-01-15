---
description: "Task list for 115 Open 存储库在线播放"
---

# Tasks: 115 Open 存储库在线播放

**Input**: 设计文档来自 `specs/001-115-open-storage/`（`plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`）  
**Tests**: 本次不新增自动化测试任务（`spec.md` 未要求）；以 `specs/001-115-open-storage/quickstart.md` 的手动用例为准。  
**组织方式**: 任务按用户故事拆分（P1 → P2 → P3 → P4），确保每个故事都可独立验收；共享基础设施放入 Setup/Foundational，避免跨故事“隐形依赖”。

## Checklist 格式（强制）

每条任务必须严格使用：

```text
- [ ] TaskID [P] [US1] 在 path/to/file 做某事
```

- `[P]`：可并行（不同文件/无未完成依赖）
- `[US1]/[US2]/[US3]/[US4]`：仅用户故事阶段必须标注；Setup/Foundational/Polish 阶段不标注
- 描述必须包含**明确文件路径**（允许 `A.kt + B.xml` 这种多路径写法）

---

## Phase 1: Setup（共享基础配置）

**Purpose**: 为 115 Open 接入准备“baseUrl/枚举资源/日志脱敏”等通用能力（后续所有用户故事都依赖）

- [X] T001 [P] 在 `core_network_component/src/main/java/com/xyoye/common_component/network/config/Api.kt` 增加 115 Open baseUrl 常量（`https://proapi.115.com/`、`https://passportapi.115.com/`）
- [X] T002 [P] 在 `data_component/src/main/java/com/xyoye/data_component/enums/MediaType.kt` 新增 `OPEN_115_STORAGE`（value 建议 `open_115_storage`）并添加图标 `data_component/src/main/res/drawable/ic_open_115_storage.xml`（同时补齐 `fromValue` 映射）
- [X] T003 [P] 在 `data_component/src/main/java/com/xyoye/data_component/entity/MediaLibraryEntity.kt` 增加 `MediaType.OPEN_115_STORAGE` 的 `disPlayDescribe` 显示（从 `url=115open://uid/<uid>` 提取 uid，不涉及 token）
- [X] T004 [P] 在 `core_network_component/src/main/java/com/xyoye/common_component/network/helper/LoggerInterceptor.kt` 增强 `sanitizeBody()`：对 `"access_token"`（JSON）与 `access_token=`（query/form）做脱敏（与现有 refresh_token 规则一致，满足 FR-012/FR-016）

---

## Phase 2: Foundational（阻塞性前置能力）

**Purpose**: 115 Open API/鉴权/授权态持久化的通用基础设施；在完成前不应开始任何用户故事 UI/浏览/播放开发

- [X] T005 [P] 新增 115 Open API Moshi 模型 `data_component/src/main/java/com/xyoye/data_component/data/open115/Open115Models.kt`（按 `specs/001-115-open-storage/contracts/115-open-openapi.yaml` 覆盖 ProApiEnvelope/PassportEnvelope/UserInfo/ListFiles/Search/DownUrl/RefreshToken）
- [X] T006 [P] 新增 Retrofit Service `core_network_component/src/main/java/com/xyoye/common_component/network/service/Open115Service.kt` 并在 `core_network_component/src/main/java/com/xyoye/common_component/network/Retrofit.kt` 注册 `open115Service`（使用 `HeaderKey.BASE_URL` 动态切域；proapi Bearer；refreshToken/downurl 用 form-url-encoded）
- [X] T007 [P] 新增 115 Open Header 约定 `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/net/Open115Headers.kt`（OpenList 风格 UA 常量、`Authorization: Bearer` 拼装、token 脱敏工具）
- [X] T008 [P] 新增授权态持久化 `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/auth/Open115AuthStore.kt`（storageKey 规则按 `data-model.md`：`${mediaType.value}:${url.trim().removeSuffix("/")}`；读写 access/refresh/expiresAt/uid/userName/avatar；清理）
- [X] T009 [P] 新增可识别异常 `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/auth/Open115AuthExceptions.kt`（`Open115ReAuthRequiredException`/`Open115NotConfiguredException` 实现 `PassThroughException`，错误文案不含 token）
- [X] T010 实现 token 管理器 `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/auth/Open115TokenManager.kt`（按 storageKey 互斥刷新、refresh_token 旋转原子写入、到期前刷新阈值；proapi `code==99|401xxxx` 触发刷新并重试一次；刷新失败抛 `Open115ReAuthRequiredException`）
- [X] T011 实现 115 Open 仓库层 `core_storage_component/src/main/java/com/xyoye/common_component/network/repository/Open115Repository.kt`（统一封装：鉴权注入、ProApiEnvelope state/code 错误映射、自动刷新 + 重试一次语义；提供 `userInfo/listFiles/search/downUrl/refreshToken/folderGetInfo` 方法）

**Checkpoint**: Foundation ready（可开始 US1/US2/US3/US4 的并行开发）

---

## Phase 3: User Story 1 - 挂载 115 Open 并浏览文件 (Priority: P1) 🎯 MVP

**Goal**: 新增“115 Open”存储源，用户手动填写 token 后可从根目录开始浏览目录/文件，并可进入/返回目录继续浏览

**Independent Test**: 使用一个包含至少 1 个目录与 1 个视频文件的 115 账号：新增存储源 → 看到根目录列表 → 进入子目录并返回，验证列表内容正确且可持续操作（参考 `specs/001-115-open-storage/quickstart.md` 3.1）

- [X] T012 [P] [US1] 新增文件项适配 `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/Open115StorageFile.kt`（根目录 `filePath="/"`；目录/文件判定；`isv==1` 视频判定 + 扩展名兜底；`fileUrl=115open://file/<fid>`；payload 挂载）
- [X] T013 [US1] 新增基础 Storage 实现 `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/Open115Storage.kt`（extends `AbstractStorage` + implements `AuthStorage`）：实现 `getRootFile/openDirectory/listFiles/pathFile/historyFile/test/getNetworkHeaders`（先完成“浏览闭环”，播放相关留到 US2；依赖 T008/T010/T011/T012）
- [X] T014 [US1] 在 `core_storage_component/src/main/java/com/xyoye/common_component/storage/StorageFactory.kt` 注册 `MediaType.OPEN_115_STORAGE -> Open115Storage`
- [X] T015 [US1] 在 `local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt` 的 `launchMediaStorage()` 增加 `MediaType.OPEN_115_STORAGE` 分支（打开 `RouteTable.Stream.StorageFile`）
- [X] T016 [P] [US1] 新增存储源编辑对话框 `storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Open115StorageEditDialog.kt` + `storage_component/src/main/res/layout/dialog_open115_storage.xml`（token 输入项、默认脱敏/可切换可见、提示“无需账号密码”、集成 `PlayerTypeOverrideBinder`、提供“测试连接”状态位）
- [X] T017 [US1] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_plus/StoragePlusActivity.kt` 接入 `MediaType.OPEN_115_STORAGE -> Open115StorageEditDialog`
- [X] T018 [US1] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Open115StorageEditDialog.kt` 实现“测试连接/保存”逻辑：校验 access_token；若鉴权失效则先 `refreshToken` 再重试；成功后构造 `url=115open://uid/<uid>` 并默认 displayName=user_name（可编辑）；用 `Open115AuthStore` 按 storageKey 写入 tokens/profile（不写入 `MediaLibraryEntity.account/password/describe`）；保存前用 `DatabaseManager.getMediaLibraryDao().getByUrl(...)` 防止同 uid 重复添加/覆盖 token
- [X] T019 [US1] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_plus/StoragePlusViewModel.kt` 增加 `MediaType.OPEN_115_STORAGE` 保存前校验（url 必须匹配 `^115open://uid/\\d+$`；失败提示“请先测试连接/保存”），避免产生无效库记录

**Checkpoint**: 此时 US1 应可独立完成并验收（新增/编辑 → 根目录列表 → 目录进出）

---

## Phase 4: User Story 2 - 从 115 Open 选择视频并播放 (Priority: P2)

**Goal**: 用户在 115 Open 文件列表中点击视频后可进入播放器并开始播放；兼容 Media3/Exo、mpv、VLC，且切换内核不改变“能否播放”的结论

**Independent Test**: 在已成功挂载 115 Open 的前提下：从列表点击一个视频文件 → 进入播放器 → 播放开始（出现画面或听到声音）；再切换 Media3/mpv/VLC 重复验证（参考 `specs/001-115-open-storage/quickstart.md` 3.2）

- [X] T020 [P] [US2] 新增 downurl 缓存 `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/play/Open115DownUrlCache.kt`（按 fid 缓存 url/userAgent/fileSize/updatedAt；短 TTL + forceRefresh 回退旧值，参考 `BaiduPanDlinkCache`）
- [X] T021 [US2] 在 `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/Open115Storage.kt` 实现播放链路：`createPlayUrl(...)` 调用 `Open115Repository.downUrl(pick_code)` 获取直链并注入 `User-Agent`；Media3 直接返回直链 + `getNetworkHeaders(file)`；mpv/VLC 使用 `LocalProxy.wrapIfNeeded(...)` + `HttpPlayServer`，并提供 Range 不支持时的“强制刷新 downurl” supplier，同时补齐 `openFile(file)` 用于字幕/弹幕下载（依赖 T020，满足 FR-005/FR-013）

**Checkpoint**: 此时 US2 可独立验收（点击视频即可播放，多内核一致可播）

---

## Phase 5: User Story 3 - 在 115 Open 中快速定位内容 (Priority: P3)

**Goal**: 支持刷新/排序/搜索等定位能力，交互与百度网盘存储源保持一致；面对大目录时仍可用

**Independent Test**: 使用包含多级目录和多文件类型的 115：验证能正常浏览层级、刷新列表、排序和搜索，并能稳定返回正确结果（参考 `specs/001-115-open-storage/quickstart.md` 3.3）

- [X] T022 [US3] 在 `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/Open115Storage.kt` 增加 `PagedStorage` 支持：目录列表按 `limit/offset` 逐页加载（默认 200），实现 `state/hasMore/reset/loadMore`，并在 `openDirectory(refresh=true)` 时重置 paging（对齐 FR-015 与 SC-002）
- [X] T023 [US3] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragmentViewModel.kt` 的 `loadMore()` 合并逻辑中，将 `MediaType.OPEN_115_STORAGE` 纳入与 `MediaType.BAIDU_PAN_STORAGE` 同等的排序处理（`merged.sortedWith(StorageSortOption.comparator())`），确保加载更多后排序/目录优先仍稳定
- [X] T024 [US3] 在 `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/Open115Storage.kt` 实现 `supportSearch=true` 与 `search(keyword)`：调用 `Open115Repository.searchFiles(cid=<currentCid>, type=4, fc=2, limit=..., offset=0)`，仅返回可播放视频；关键词 trim/空值/长度上限处理与 `specs/001-115-open-storage/spec.md` Edge Cases 对齐

**Checkpoint**: 此时 US3 可独立验收（刷新/排序/搜索可用且不破坏浏览上下文）

---

## Phase 6: User Story 4 - Token 失效后的可恢复体验 (Priority: P4)

**Goal**: token 失效时给出明确提示，并引导用户通过“编辑 token / 重试 / 移除存储源”等方式恢复；在可行时自动用 refresh_token 续期以减少打断

**Independent Test**: 模拟鉴权失效：access_token 过期但 refresh_token 可用时应自动恢复；refresh_token 也失效时应提示并引导更新 token；移除存储源后授权信息被清理（参考 `specs/001-115-open-storage/quickstart.md` 3.4）

- [X] T025 [US4] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragmentViewModel.kt` 的 `handleLoginRequiredIfNeeded()` 增加 `Open115ReAuthRequiredException` 分支：提示“授权失效/需要更新 token”，并触发通用 loginRequiredLiveData（不在日志/Toast 输出完整 token）
- [X] T026 [US4] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt` 的 `showLoginDialog()` 增加 `MediaType.OPEN_115_STORAGE`：导航到 `RouteTable.Stream.StoragePlus`（携带 `mediaType` + `editData`），保存成功后触发 `triggerTvRefresh()` 重新加载当前目录
- [X] T027 [US4] 在 `storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Open115StorageEditDialog.kt` 增加“断开连接/清除授权”入口（包含二次确认）：调用 `Open115AuthStore.clear(storageKey)`，并刷新 UI 状态/允许重新填写 token（满足 FR-007/FR-016）
- [X] T028 [US4] 在 `local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaViewModel.kt` 删除媒体库时清理 115 Open 授权数据（调用 `Open115AuthStore.clear(Open115AuthStore.storageKey(data))`，满足 FR-007/FR-016）

**Checkpoint**: 此时 US4 可独立验收（自动刷新可用、失败可恢复、移除可清理）

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: 跨用户故事的稳定性、可观测性与发布前门禁

- [X] T029 为关键链路补充日志与错误上下文（且必须脱敏 token）：`core_storage_component/src/main/java/com/xyoye/common_component/network/repository/Open115Repository.kt` + `core_storage_component/src/main/java/com/xyoye/common_component/storage/open115/auth/Open115TokenManager.kt` + `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/Open115Storage.kt` + `storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Open115StorageEditDialog.kt`（使用 `LogFacade`/`ErrorReportHelper`，并用 `Open115Headers` 的脱敏工具）
- [X] T030 在仓库根目录运行 `./gradlew verifyModuleDependencies` 并确认输出末尾为 `BUILD SUCCESSFUL`（`./gradlew`）
- [X] T031 在仓库根目录运行 `./gradlew lint` 并确认输出末尾为 `BUILD SUCCESSFUL`（`./gradlew`）
- [X] T032 在仓库根目录运行 `./gradlew assembleRelease` 并确认输出末尾为 `BUILD SUCCESSFUL`（`./gradlew`）
- [ ] T033 最终对照 `specs/001-115-open-storage/quickstart.md` 走通 P1-P4 用例并在 `specs/001-115-open-storage/checklists/acceptance.md` 留存验收记录（文字/截图/关键日志均可）

---

## Dependencies & Execution Order

### User Story 依赖图（建议）

```text
Setup (Phase 1)
  ↓
Foundational (Phase 2)
  ↓
US1 (P1, MVP)  →  US2 (P2)  →  US3 (P3)  →  US4 (P4)
```

- 在“人力充足”的情况下：Phase 2 完成后，US1/US2/US3/US4 可并行推进（但验收仍建议按 P1→P4 顺序）
- US2/US3/US4 在体验上依赖 US1 的“可挂载 + 可浏览”闭环，但可在 Foundation ready 后分工并行（不同文件/不同关注点）
- 依赖治理提醒：禁止通过新增 feature ↔ feature 依赖来打通；如需共享类型优先下沉到 `:data_component`/`core_*`（见 `document/architecture/module_dependency_governance.md`）

### Parallel Opportunities（示例）

- Setup：`T001/T002/T003/T004` 可并行
- Foundational：`T005/T006/T007/T008/T009` 可并行（`T010/T011` 依赖其结果）
- US1：`T012`（存储侧）与 `T016`（UI 侧）可并行
- US4：`T025`（ViewModel）与 `T028`（删除清理）可并行

---

## Parallel Example: User Story 1

```text
并行组 A（存储侧）：
- T012 [US1] `core_storage_component/.../Open115StorageFile.kt`
- T013 [US1] `core_storage_component/.../Open115Storage.kt`

并行组 B（UI 侧）：
- T016 [US1] `storage_component/.../Open115StorageEditDialog.kt` + layout
```

---

## Parallel Example: User Story 2

```text
并行组 A（缓存/基础能力）：
- T020 [US2] `core_storage_component/.../Open115DownUrlCache.kt`

并行组 B（播放链路）：
- T021 [US2] `core_storage_component/.../Open115Storage.kt`
```

---

## Parallel Example: User Story 3

```text
并行组 A（分页能力）：
- T022 [US3] `core_storage_component/.../Open115Storage.kt`

并行组 B（UI 合并/排序）：
- T023 [US3] `storage_component/.../StorageFileFragmentViewModel.kt`
```

---

## Parallel Example: User Story 4

```text
并行组 A（登录失效引导）：
- T025 [US4] `storage_component/.../StorageFileFragmentViewModel.kt`
- T026 [US4] `storage_component/.../StorageFileFragment.kt`

并行组 B（清理链路）：
- T028 [US4] `local_component/.../MediaViewModel.kt`
```

---

## Implementation Strategy

### MVP First（只做 US1）

1. Phase 1 → Phase 2 → Phase 3（US1）
2. 以 `specs/001-115-open-storage/quickstart.md` 3.1 验收 P1
3. 构建门禁至少通过：`./gradlew assembleDebug`

### Incremental Delivery

1. US1：可挂载 + 可浏览（MVP）
2. US2：可播放 + 多内核一致可播
3. US3：大目录定位能力（刷新/排序/搜索 + 分页稳定）
4. US4：鉴权失效可恢复 + 可清理/重连
