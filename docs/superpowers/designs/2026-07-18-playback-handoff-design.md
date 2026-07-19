# 播放启动交接与活动会话深化设计

> 日期：2026-07-18  
> 修订：2026-07-19  
> 状态：修订设计已确认，等待书面规格复核  
> 范围：播放请求提交、来源恢复、播放器路由、前台切换、进程恢复与敏感临时数据  
> 不包含：完整播放器 source transition 重构、Media3 会话统一、Bilibili 账号会话重构、存储浏览状态重构  
> 前置依赖：`2026-07-19-playback-transport-leases-design.md`

## 1. 背景

当前所有播放入口通过 `VideoSourceManager` 交接一个进程内 `BaseVideoSource`。部分入口还会单独写入 `Media3LaunchParams`：

```text
feature ViewModel
  -> 构建 BaseVideoSource
  -> setSource(source)
  -> attachMedia3LaunchParams(params)
  -> 发送无内容导航事件
  -> PlayerInterceptorActivity
  -> PlayerActivity
  -> getSource()
  -> consumeMedia3LaunchParams()
```

该 Interface 暴露了调用顺序、全局状态和 Activity 生命周期等实现细节。调用方必须记住：

- 播放源和 Media3 参数必须属于同一次用户操作；
- 所有校验必须在导航前完成；
- 失败时必须清理已经写入的全局状态；
- PlayerActivity 必须在另一个入口覆盖状态前完成读取；
- 进程死亡后 `BaseVideoSource` 无法恢复；
- 投屏、外部 URL 和磁链各自还携带不同的临时信息。

现有代码已经存在可复现的状态串线窗口：

1. `StorageFileViewModel` 创建播放源并写入 Media3 参数；
2. 磁链离线校验随后阻止播放；
3. 待消费参数没有清理；
4. `PlayHistoryViewModel` 只覆盖播放源；
5. `PlayerActivity` 将上一笔失败请求的 Media3 参数应用到新播放源。

本设计不以增加一次 `clear()` 为目标，而是重新建立播放请求和活动播放会话的所有权。

2026-07-19 的源码级复核进一步确认：HTTP、SMB、FTP、Thunder 和 Bilibili 播放 Session 当前使用共享可变资源，在准备候选来源时可能破坏旧播放。因此本设计拆为两个独立子项目：先完成传输资源租约，再实施播放请求与活动会话。本文只描述后者，且假设所有 `PreparedPlayback` 已获得独立、可释放的 `PlaybackTransportLease`。

## 2. 设计目标

### 2.1 功能目标

1. 一次播放请求必须原子包含来源身份、启动选项和敏感临时数据。
2. feature 不再构建或持有可跨页面传递的 `BaseVideoSource`。
3. 待消费请求采用 latest-wins；新请求替换旧请求并清理旧临时资源。
4. 播放器消费请求后，运行时所有权转移到活动播放会话。
5. PlayerActivity 被系统恢复时，可以按稳定描述重建来源并续播。
6. 用户正常冷启动应用时仍进入首页，不自动恢复陈旧播放会话。
7. 播放器前台收到新请求时复用当前 PlayerActivity，不增加播放器返回栈。
8. 新来源解析或校验失败时保留旧播放；新来源准备成功后才切换。
9. URL Header、Cookie、投屏 Header 等敏感数据不以明文进入 Intent、SavedState、MMKV 或日志。
10. 删除透明的 `PlayerInterceptorActivity` 和旧 `VideoSourceManager`。
11. 所有跨身份内部切源必须更新活动描述、租约和会话快照。
12. 同身份画质或 URL 恢复必须替换自己的传输/Bilibili token，不能覆盖全局当前资源。

### 2.2 架构目标

- 建立“播放请求”Module：隐藏稳定描述、加密存储、TTL、latest-wins 和提交规则。
- 建立“活动播放会话”Module：隐藏来源解析、恢复、并发代次和运行时所有权。
- 建立真实的来源解析 Seam；不同来源类型由多个 Adapter 解析。
- 让调用方和测试穿过相同 Interface，不再测试或替换全局 Locator。
- 保持现有 Gradle Module 依赖方向，不增加 feature 之间的直接依赖。

## 3. 非目标

本次不同时解决以下问题：

- 不重构 Media3 session/capability 的全部状态所有权；
- 不重写 `PlayerActivity.applyPlaySource()` 的全部 source transition 生命周期；
- 不拆分 `PlayerInitializer`；
- 不拆分 `MediaLibraryEntity` 数据库结构；
- 不实现“桌面冷启动自动继续播放”；
- 不实现多笔播放请求排队；
- 不把完整 `BaseVideoSource`、`StorageFile` 或 `MediaLibraryEntity` 序列化；
- 不新增跨 feature 的 Gradle 依赖；
- 不改变 TV DPAD focus 架构和播放器控制条交互。
- 不保证旧画面持续到新来源首帧；切换边界是 `PreparedPlayback` 与传输租约全部就绪。

## 4. 备选方案与决策

### 4.1 方案 A：修补现有全局单例

做法：增加 `clear()`，调整 `StorageFileViewModel` 的校验顺序，并在每个入口覆盖 Media3 参数。

优点：改动最小。

缺点：

- 仍然依赖进程内 `BaseVideoSource`；
- 仍然不能恢复进程死亡；
- 所有入口仍需了解清理和写入顺序；
- 后续新增播放参数会再次扩大串线风险；
- `PlayerInterceptorActivity` 和无内容导航事件仍然存在。

结论：拒绝。该方案只修复当前症状，不增加 Depth。

### 4.2 方案 B：原子进程内请求对象

做法：把 `BaseVideoSource` 和 `Media3LaunchParams` 合并成一个进程内请求对象，一次提交、一次消费。

优点：

- 能消除参数分离导致的串线；
- 改动范围低于完整恢复设计。

缺点：

- 请求仍不能跨进程恢复；
- feature 仍负责构建运行时来源；
- 投屏、外部 URL 和媒体库恢复仍需旁路；
- 后续要支持恢复时仍需第二次迁移。

结论：拒绝。可作为临时过渡，但不符合已确认的根治目标。

### 4.3 方案 C：稳定播放描述 + 待消费请求 + 活动播放会话

做法：feature 提交稳定播放描述；播放请求 Module 加密持久化；PlayerActivity 加载请求，由活动播放会话统一解析 `BaseVideoSource` 并管理恢复和替换。

优点：

- 原子解决播放源、Media3 参数和敏感数据的归属；
- 支持进程恢复；
- feature 不再了解来源构建顺序；
- 可以通过请求 ID 和会话 ID建立明确状态机；
- 为投屏、Bilibili、磁链和外部 URL 提供统一入口。

缺点：

- 需要为不同来源建立稳定引用和解析 Adapter；
- 需要迁移全部播放入口；
- 需要新增加密临时存储与恢复测试。

结论：采用。该方案提供最大的 Leverage 和 Locality，且不需要改变 Gradle Module 边界。

## 5. 领域术语

### 5.1 播放描述（Playback Descriptor）

描述“用户要播放什么”的稳定、可持久化数据。它不包含 `BaseVideoSource`、`Storage`、`StorageFile`、Activity 或 Repository 实例。

### 5.2 播放请求（Playback Request）

一次尚未被播放器激活的用户操作。包含请求 ID、播放描述、Media3 启动选项、来源入口、创建时间和过期时间。

### 5.3 待消费请求（Pending Request）

当前最新的一笔播放请求。系统只保存一笔；新请求原子替换旧请求。

### 5.4 已准备播放（Prepared Playback）

播放描述成功解析后的运行时结果。包含 `BaseVideoSource`、Media3 启动参数、稳定描述、释放动作和可用于恢复的初始进度。

### 5.5 活动播放会话（Active Playback Session）

播放器已经激活的一次播放。由 player 模块拥有运行时状态，由加密会话快照提供进程恢复数据。

### 5.6 播放会话快照（Playback Session Snapshot）

活动播放会话的可持久化投影。包含会话 ID、稳定播放描述、最近播放位置、更新时间和过期时间，不包含播放器内核、Surface 或 Controller 对象。

## 6. Module 与职责

### 6.1 `core_storage_component`：播放请求 Module

建议放置于 `com.xyoye.common_component.playback.request` 或等价领域包。

职责：

- 根据 feature 输入创建稳定播放描述；
- 原子提交待消费请求；
- 执行 latest-wins 替换；
- 加密持久化请求和敏感参数；
- 管理 pending TTL；
- 加载指定请求，但在解析成功前不破坏性删除；
- 在激活、失败、替换和过期时清理；
- 提供请求是否仍为最新的代次检查。

该 Module 不负责：

- 展示 Toast/Dialog；
- 启动 Activity；
- 操作播放器 Controller；
- 保存运行时 `BaseVideoSource`；
- 选择前台页面行为。

### 6.2 `core_storage_component`：播放来源解析 Module

职责：

- 选择能处理播放描述的解析 Adapter；
- 查询媒体库、历史和协议状态；
- 创建 `Storage` 和 `StorageFile`；
- 执行磁链/下载兼容校验；
- 调用 `StorageVideoSourceFactory`；
- 生成 `PreparedPlayback`；
- 获取前置项目定义的独立 `PlaybackTransportLease`；
- 将异常转换为结构化准备失败；
- 在结果过期或被替换时释放已经创建的资源。

解析 Seam 具有多个真实 Adapter：

- 媒体库文件 Adapter；
- 播放历史 Adapter；
- 磁链 Adapter；
- 外部 URL Adapter；
- 投屏 Adapter。

Bilibili 媒体库项目由媒体库 Adapter 进入，但其协议身份和重新取流逻辑仍由 Bilibili Storage/播放实现负责。

来源解析不得直接修改全局代理、Server、Thunder task 或 Bilibili 当前 Session；所有传输资源必须通过租约 Adapter 获得。

### 6.3 `player_component`：活动播放会话 Module

职责：

- 从 Intent/SavedState 取得请求 ID 或会话 ID；
- 加载待消费请求或活动会话快照；
- 管理来源解析协程和请求代次；
- 新来源准备期间保持旧播放；
- 确认请求仍为最新后原子激活；
- 更新活动会话快照；
- PlayerActivity 系统恢复时重建来源；
- 显式退出时清理活动会话；
- 失败时选择“保留旧播放”或“退出空播放器”。

该 Module 不负责协议专属解析，也不直接访问各 feature ViewModel。

### 6.4 `PlayerActivity`

职责收敛为：

- 接收路由；
- 绑定活动播放会话状态；
- 展示初始加载、替换加载和结构化错误；
- 将已经准备完成的播放交给现有 source transition；
- 处理 `onNewIntent()`；
- 维护 TV 返回行为和 Controller UI。

PlayerActivity 不再读取或写入全局播放源槽位。

## 7. 数据设计

### 7.1 播放请求信封

逻辑字段如下：

```text
PlaybackRequest
  schemaVersion: Int
  requestId: String
  descriptor: PlaybackDescriptor
  launchOptions: Media3LaunchParams?
  origin: PlaybackRequestOrigin
  createdAtMs: Long
  expiresAtMs: Long
```

`origin` 只用于日志、统计和错误展示，建议枚举包括：

- STORAGE_FILE
- PLAY_HISTORY
- ANIME_EPISODE
- EXTERNAL_INTENT
- SCREENCAST

请求 ID 使用不可预测的随机 UUID。路由仅传请求 ID。

请求和活动快照使用 Moshi 序列化。`schemaVersion` 初始值为 1；由于 pending TTL 为 15 分钟、活动快照 TTL 为 24 小时，不为临时记录设计长期迁移链。遇到不支持的版本时清理记录并返回 `InvalidDescriptor`。

活动会话快照逻辑字段如下：

```text
PlaybackSessionSnapshot
  schemaVersion: Int
  sessionId: String
  descriptor: PlaybackDescriptor
  launchOptions: Media3LaunchParams?
  queueContext: PlaybackQueueContext?
  positionMs: Long
  updatedAtMs: Long
  expiresAtMs: Long
```

快照必须保存激活时的 Media3 启动选项。否则 pending 请求在激活后删除，进程恢复会丢失 `mediaId`、`sourceType`、能力请求和 autoplay 语义。

### 7.2 播放描述分类

#### 媒体库文件描述

```text
StoredLibraryFileDescriptor
  libraryId
  mediaType
  uniqueKey
  storagePath
  filePathHint
  playHistoryId?
  protocolReference?
  queueContext?
```

`protocolReference` 由 `core_storage_component` 内部的引用 Adapter 生成和解析，feature 不解释其内容。

#### 播放历史描述

```text
PlayHistoryDescriptor
  historyId
  expectedLibraryId?
  expectedUniqueKey
```

恢复时必须重新查询数据库实体，不持久化整条 `PlayHistoryEntity` 快照。

#### 磁链描述

```text
TorrentDescriptor
  magnetOrTorrentIdentity
  fileIndex
  downloadId
  expectedUniqueKey
```

磁链初始化、torrent 文件恢复和下载兼容校验都在解析期间完成。

#### 外部 URL 描述

```text
ExternalUriDescriptor
  uri
  displayTitle?
  headers?
  sourceType
```

Header/Cookie 等敏感字段直接放入整体加密的请求正文，不再增加第二套 secret payload 存储；日志不得输出完整 URI query 或 Header。

#### 投屏描述

```text
ScreencastDescriptor
  senderAddress
  port
  relatedVideos
  playUniqueKey
  httpHeaders?
```

当前 `ScreencastData.ip` 使用 `@IgnoredOnParcel`，新描述必须把发送端地址作为正式恢复字段。

### 7.3 队列上下文

当前 `StorageVideoSourceFactory` 从 `storage.directoryFiles` 构建同目录视频队列。该字段属于 UI 浏览状态，不能作为恢复事实源。

播放描述可选保存：

- 目录稳定身份；
- 当前排序选项；
- 当前隐藏文件过滤策略；
- 当前选中项身份；
- 有界的文件身份快照。

解析优先重新加载目录并应用相同排序/过滤。协议不支持稳定目录恢复时，可使用有界身份快照。身份快照最多保存 200 项，始终包含当前项，并围绕当前项截取前后窗口。快照只保存身份，不保存 `StorageFile` 对象或短期播放 URL。

## 8. 持久化与安全

### 8.1 存储 Adapter

使用独立 MMKV 文件保存一个完整的加密 `PlaybackHandoffState` blob，其中同时包含 pending 请求、验证状态和活动会话快照。所有更新在进程内 Mutex 中执行 read-modify-write，并通过单个 MMKV value 替换，避免多 key 写入产生部分状态。MMKV 不提供 ACID transaction，因此本文只承诺单进程串行更新和单值替换。

整个 state 正文使用 `KeystoreAesGcmCrypto` 加密，不只加密已知敏感字段，以避免未来新增字段被遗漏。

使用独立 KeyStore alias，例如：

```text
dandanplay.playback.handoff.aes
```

不得复用开发者凭据或媒体库凭据 alias。

### 8.2 安全约束

- Release 不允许明文 fallback；
- 加密失败则拒绝提交包含敏感数据的请求；
- 解密失败则删除记录并返回结构化错误；
- Intent 和 SavedState 只包含请求 ID或会话 ID；
- 日志只记录 ID、来源枚举、MediaType 和脱敏指纹；
- 不记录 Header、Cookie、完整 URL query、磁链或本地完整路径；
- 记录被替换、激活、失败或过期后立即删除不再需要的密文。

此处“删除”指逻辑记录删除。MMKV mmap 文件不保证旧页面在物理介质上立即安全擦除；安全性依赖整体 AES-GCM 加密和 KeyStore 密钥保护，文档与日志不得宣称物理 secure erase。

### 8.3 TTL

- 待消费请求 TTL：15 分钟，加载解析时刷新最后访问时间；
- 活动会话快照 TTL：24 小时，播放进度写入时刷新；
- 显式退出播放器立即删除活动会话快照；
- 普通冷启动不读取活动快照；只有系统恢复且 SavedState/Intent 携带会话 ID 时才能恢复；
- 应用启动时可异步清理过期记录，不阻塞首帧。

TTL 作为内部常量，不增加用户设置。

## 9. 状态机与并发规则

### 9.1 待消费请求

```text
Created
  -> Pending
  -> Replaced | Expired | Resolving

Resolving
  -> Prepared
  -> AwaitingInteractiveVerification
  -> Failed
  -> Superseded

AwaitingInteractiveVerification
  -> Resolving
  -> Replaced
  -> Expired
  -> Failed

Prepared
  -> Active
  -> Superseded
```

规则：

1. `submit()` 在同一个 Mutex 临界区内通过单加密 blob 替换当前 pending 请求；
2. 替换后立即清理旧请求的密文和临时资源引用；
3. PlayerActivity 加载请求时不立即删除记录；
4. 解析完成后再次检查当前 pending request ID；
5. ID 不匹配说明请求已被更新请求取代，必须释放解析结果；
6. 激活成功时原子写入活动快照并删除 pending 请求；
7. 失败时删除对应 pending 请求；
8. 重复路由同一 request ID 不得重复激活；
9. 交互验证不是普通失败；请求进入 `AwaitingInteractiveVerification` 并保留加密 descriptor；
10. 验证成功后重试同一 request ID；取消、替换或过期后才删除请求和验证数据。

### 9.2 前台替换

PlayerActivity 已有活动播放时收到新请求：

```text
Active(old)
  -> Resolve(new), old continues playing
  -> Prepared(new)
  -> Verify request generation
  -> Activate(new)
  -> Release(old)
```

如果新来源失败或被更新请求取代：

```text
Active(old)
  -> Resolve(new) failed/superseded
  -> Release prepared new resources if any
  -> Keep Active(old)
```

“准备成功”在本设计中指：

- 稳定描述可解析；
- 媒体库和文件仍然存在；
- 凭据可读取；
- 协议可以创建有效播放 URL；
- 磁链兼容校验允许播放；
- 独立 Transport Lease 已成功创建；
- 播放配置和 Media3 启动参数已经生成。

它不保证已经渲染首帧。首帧失败继续使用现有播放器错误恢复机制；完整 source transition 的事务化属于后续深化项。

### 9.3 活动会话恢复

PlayerActivity 系统重建时：

1. 从 SavedState 读取活动会话 ID；
2. 读取并解密有效快照；
3. 重新解析稳定播放描述；
4. 以快照中的最近位置作为恢复位置；
5. 数据库中存在更新时间更晚的播放历史时，可采用更晚记录；
6. 解析成功后恢复活动会话；
7. 解析失败则展示明确错误并退出 PlayerActivity。

正常桌面冷启动不执行以上流程。

## 10. 路由与 Activity 行为

### 10.1 路由

保留稳定外部路由语义 `RouteTable.Player.Player`，但直接映射到 `PlayerActivity`。

删除：

- `PlayerInterceptorActivity`；
- `PlayerInterceptorViewModel`；
- `activity_player_interceptor.xml`；
- `RouteTable.Player.PlayerCenter`；
- 二段 ARouter 导航。

路由参数只携带：

```text
requestId=<UUID>
```

### 10.2 Activity 实例

PlayerActivity 使用 `android:launchMode="singleTask"`，确保 task 内已有播放器时接收 `onNewIntent()`，而不是压入第二个播放器页面。该选择会移除 PlayerActivity 之上的临时页面，因此任何必须保留的播放器内交互应使用现有 overlay/dialog，而不是在播放器上方长期堆叠 Activity。实施时需用仪器测试验证：

- 从文件列表首次进入播放器；
- 播放器前台收到投屏请求；
- 播放器后台时从通知或外部 Intent 再次进入；
- BACK 返回不会出现旧播放器页面；
- TV 控制条和焦点恢复不受影响。

### 10.3 初始与替换加载

- 没有活动来源：显示全屏准备状态；失败后展示错误并退出；
- 已有活动来源：保持播放，只显示非阻断替换提示；失败后提示并继续旧播放；
- 新请求到达时取消旧解析协程，但仍依赖 request ID 代次检查防止不可取消操作晚返回。

## 11. 错误模型

来源解析返回结构化失败，不向 UI 泄漏协议异常字符串：

```text
PlaybackPreparationFailure
  RequestMissing
  RequestExpired
  RequestSuperseded
  SessionSnapshotMissing
  SensitivePayloadUnavailable
  LibraryMissing
  FileMissing
  PermissionDenied
  CredentialUnavailable
  OfflineValidationBlocked(reason)
  AuthenticationRequired
  InteractiveVerificationRequired(payload)
  NetworkUnavailable
  UnsupportedSource
  InvalidDescriptor
  Unknown(causeFingerprint)
```

UI 根据“初始播放”或“替换播放”选择行为：

- 初始播放失败：错误对话框 + 退出；
- 替换播放失败：提示 + 保留旧播放；
- 需要风控验证：请求进入 `AwaitingInteractiveVerification`，验证页只接收不透明 `verificationId`，成功后重试同一 request ID；
- 失败信息不得依赖正则解析错误文案。

Bilibili `v_voucher` 必须由 Bilibili 层结构化提供，不能进入异常文案或 Intent。验证 payload 与 requestId 一起保存在整体加密记录中。Bilibili 播放 Session 必须使用身份 + sessionToken 的代次所有权；旧 Addon 释放只能删除自己的 token。

## 12. 资源所有权

`BaseVideoSource` 当前不能完整表达所持有 Storage、StorageFile 和 PlaybackAddon 的释放责任。来源解析应返回 `PreparedPlayback`，由其统一提供幂等释放动作。

释放时机：

- 请求被 latest-wins 替换；
- 解析结果晚返回且 request ID 已过期；
- 新来源准备失败；
- 活动会话切到其他身份；
- PlayerActivity 显式退出；
- 进程恢复后旧运行时对象自然不存在，只恢复稳定描述。

释放动作至少覆盖：

- `PlaybackReleasableAddon.onRelease()`；
- `StorageFile.close()`；
- `Storage.close()`；
- 来源解析期间创建的临时资源；
- `PlaybackTransportLease.release()`。

调用必须幂等，防止切源和 Activity 销毁重复释放。

### 12.1 内部切源所有权

所有播放器内部来源变化必须接入活动播放会话：

- 手动下一集和传统自动下一集：按新身份生成稳定描述、PreparedPlayback 和租约，激活后更新快照；
- Media3 seamless transition：成功后 adoption 新描述、Media3 启动选项和队列位置；
- Bilibili 画质/编码切换和 URL 恢复：身份保持不变，但替换具体 transport/Bilibili sessionToken；
- 播放内核回退：复用当前租约，只更新运行时 playback profile，不误改全局设置。

任何内部切源不得继续通过 `VideoSourceManager` 或裸 `StorageVideoSourceFactory.create()` 绕过活动会话。

## 13. feature 调用方式

迁移后，各 feature 只执行两步：

1. 调用播放请求 Module 提交领域输入；
2. 使用返回的 request ID 导航到播放器。

示意流程：

```text
requestId = playbackRequests.submitStorageFile(file, launchOptions)
openPlayer(requestId)
```

`submitStorageFile()` 内部负责从 `StorageFile` 生成稳定描述。调用返回成功后，feature 不再持有跨页面播放状态。

以下 LiveData/Flow 应从“无内容播放事件”改为携带 request ID 的一次性导航结果，或由统一导航 helper 在提交成功后直接执行：

- `StorageFileViewModel.playLiveData`；
- `PlayHistoryViewModel.playLiveData`；
- `AnimeEpisodeFragmentViewModel.playVideoFlow`；
- `PlayerIntentViewModel.playLiveData`。

不允许继续使用 `Any()` 表达播放请求。

## 14. 迁移顺序

### 阶段 1：特征测试与安全基线

- 固定当前 Media3 参数串线复现；
- 固定请求只能消费一次；
- 固定新来源失败时旧播放继续；
- 验证 Intent/SavedState 不包含敏感字段；
- 验证日志脱敏。

### 前置阶段：传输租约

必须先完成 `2026-07-19-playback-transport-leases-design.md`，验证 HTTP、SMB、FTP、Thunder 和 Bilibili Session 新旧资源可以短暂并存。未完成该前置项目时，不得宣称 replacement 失败能普遍保留旧播放。

### 阶段 2：播放描述、加密存储和解析 Adapter

- 建立请求模型与 store；
- 建立 stable reference 生成和恢复；
- 建立 `PreparedPlayback` 和释放动作；
- 将 `DownloadValidator` 的核心兼容校验下沉到 `core_storage_component`；
- 覆盖媒体库、历史、磁链、外部 URL、投屏和 Bilibili 媒体库文件。
- 建立 `AwaitingInteractiveVerification` 加密状态和 opaque verification ID。

### 阶段 3：PlayerActivity 接入

- 直接路由；
- 请求 ID 和会话 ID；
- 活动播放会话；
- `onNewIntent()`；
- 系统恢复；
- 新来源准备完成后再调用现有 source transition。

阶段 3 可暂时保留旧 `VideoSourceManager` 兼容入口，但必须增加静态门禁或测试，禁止新增调用点。

### 阶段 4：入口迁移

建议按以下顺序逐个迁移并验证：

1. `StorageFileViewModel`；
2. `PlayHistoryViewModel`；
3. `AnimeEpisodeFragmentViewModel`；
4. `PlayerIntentViewModel`；
5. `ScreencastReceiveService`。

### 阶段 4.1：内部切源迁移

- 手动/传统下一集；
- Media3 seamless transition；
- Bilibili 画质和 URL 恢复；
- 播放内核回退；
- 每次成功变化后更新 canonical descriptor、launch options、队列位置和活动快照。

### 阶段 5：删除旧 Implementation

删除：

- `VideoSourceManager`；
- `PlayerInterceptorActivity`；
- `PlayerInterceptorViewModel`；
- 透明布局；
- `PlayerCenter` 路由；
- feature 中直接调用 `StorageVideoSourceFactory` 的播放入口；
- 分离的 Media3 attach/consume 流程；
- `Any()` 类型播放事件。

## 15. 测试设计

### 15.1 JVM 单元测试

#### 请求 store

- submit 后可按 ID读取；
- 新请求替换旧请求；
- 旧请求密文和临时数据被删除；
- 过期请求不可读取；
- 激活后 pending 被删除；
- 相同请求不能激活两次；
- 加密失败不落明文；
- 解密失败清理记录。

#### 来源解析

- 媒体库和文件存在时成功；
- 媒体库已删除；
- 文件已移动或删除；
- 历史记录旧身份回填后恢复；
- 磁链校验阻止时不产生活动来源；
- 外部 URL Header 恢复；
- 投屏发送端地址恢复；
- Bilibili 短期 URL 不进入持久描述；
- 解析完成后发现请求已被替换，结果被释放。

#### 活动会话

- 初始请求成功激活；
- 新请求准备期间旧来源继续；
- 新请求失败保留旧来源；
- A 慢、B 快时 A 结果不能覆盖 B；
- 快照恢复使用正确播放位置；
- 显式退出删除活动快照；
- 普通冷启动不读取活动快照。
- 手动下一集后恢复到新集；
- seamless transition 后恢复到新集；
- 同身份画质/URL 恢复只替换对应 token；
- 内核回退复用当前 transport lease。

### 15.2 Robolectric 或 Activity 测试

- `PlayerActivity` 首次接收 request ID；
- `onNewIntent()` 复用同一 Activity；
- SavedState 恢复 session ID；
- 缺失、过期和解密失败的错误行为；
- 返回栈中不残留拦截 Activity 或旧播放器 Activity。

### 15.3 仪器与 TV 冒烟测试

至少覆盖：

1. 文件列表播放；
2. 播放历史恢复；
3. 动漫本地剧集播放；
4. 外部 URL + Header；
5. 磁链允许和阻止；
6. 投屏到空闲应用；
7. 投屏替换前台播放；
8. 系统回收后恢复 PlayerActivity；
9. 全程 DPAD 进入、加载、失败返回和退出；
10. 返回栈不存在双播放器页面。

## 16. 构建与治理验证

实现阶段至少执行：

```text
./gradlew testDebugUnitTest
./gradlew verifyModuleDependencies
./gradlew assembleDebug
```

涉及 Activity 路由和进程恢复后，还应执行：

```text
./gradlew connectedDebugAndroidTest
```

设备必须处于 `device` 状态。所有 Gradle 命令都必须读取输出尾部并明确确认 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

## 17. 完成标准

满足以下全部条件才视为完成：

- 所有播放入口提交稳定播放描述；
- feature 不再负责构建跨页面 `BaseVideoSource`；
- 播放源和 Media3 参数不可分离提交；
- 同时只有一个 pending 请求；
- latest-wins 和资源清理有自动化测试；
- PlayerActivity 前台接收新请求时不增加返回栈；
- 新来源失败时旧播放继续；
- PlayerActivity 系统恢复后可重建来源和续播；
- 正常冷启动不自动进入播放器；
- 敏感参数不以明文进入 Intent、SavedState、MMKV 或日志；
- `VideoSourceManager` 无生产调用并被删除；
- `PlayerInterceptorActivity`、空 ViewModel 和二段路由被删除；
- TV DPAD 主路径可达、可见、可返回；
- 单元测试、依赖治理和 Debug 构建全部明确成功。

## 18. 后续深化关系

本设计完成后，为以下架构工作建立前置条件：

- 集中 source transition 生命周期；
- 统一 Media3 会话状态所有者；
- 将弹幕异步操作绑定活动播放身份；
- 分离投屏传输与 UI 请求生命周期；
- 收口 `PlayerInitializer` 中的 source-scoped 状态；
- 让存储浏览路由拥有独立目录和队列状态。

这些后续工作不得重新绕过播放请求和活动播放会话 Interface。
