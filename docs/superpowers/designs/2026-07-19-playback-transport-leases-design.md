# 播放传输资源租约设计

> 日期：2026-07-19  
> 修订：2026-10-27  
> 状态：租约实施计划的 9 个任务、37 项检查已完成；验证范围与限制见计划，后续交接会话重构正在实施  
> 范围：HTTP 代理、SMB、FTP、Thunder 与 Bilibili 播放 Session 的并存、代次和释放所有权  
> 后续依赖：`2026-07-18-playback-handoff-design.md`

> 进度与验证以[租约实施计划](../plans/2026-07-19-playback-transport-leases.md)为准；基础测试通过不代表 HTTP/SMB/FTP/Thunder/Bilibili 的实际并存已经验收。

> 修订依据：[播放重构方案与参考项目源码复核](../../../document/architecture/2026-10-27-playback-refactor-reference-review.md) F1、F3。租约 factory 负责独立播放连接的获取，来源统一释放 Addon/lease，PreparedPlayback 仅委托来源释放；测试必须经过实际 acquisition/factory 路径。

## 1. 背景

播放交接设计要求：新来源完成稳定描述解析、凭据读取、播放 URL 创建、下载校验和播放配置装配后，才替换当前活动来源。准备失败时，旧播放必须继续。

当前传输实现无法满足该要求：

- `HttpPlayServer` 是全局单例，只保存一份 upstream URL、Header、TLS 和 Range 状态；
- `SmbPlayServer.startSync()` 在已启动时会释放旧 Server，且只保存一份 Storage/File；
- `FtpPlayServer` 只保存一份 Storage/File；
- `ThunderManager.generatePlayUrl()` 会先执行 `stopAllTask()`；
- Bilibili 播放 Session Store 只按 `(storageId, uniqueKey)` 保存一份 Session。

因此在准备候选来源 B 时，可能已经改写或停止活动来源 A。即使随后释放 B，也无法恢复 A 的传输状态。

本设计先建立独立的传输资源所有权，使旧活动来源和新候选来源可以短暂并存。完成该前置项目后，播放请求与活动会话才能实现可靠的 PreparedPlayback 切换。

## 2. 设计目标

1. 每个已准备播放来源拥有独立、可释放的传输租约。
2. 准备候选来源不得修改、停止或删除活动来源使用的传输资源。
3. 候选失败、请求被替换、来源退出时，只释放对应租约。
4. 释放动作必须幂等。
5. 旧、新来源短暂并存时，URL、连接、流、Thunder task 和 Bilibili Session 均不得串线。
6. 保持现有 Storage 与播放器内核行为，不在本项目中修改播放请求路由和 Activity 返回栈。
7. 为后续播放交接提供真实的多 Adapter Seam，而不是继续传递裸播放 URL。

## 3. 非目标

本项目不同时实现：

- 播放请求加密 Store；
- PlayerActivity `singleTask`；
- 进程恢复；
- feature 播放入口迁移；
- 双播放器或首帧后切换；
- Media3 session/capability 的整体统一；
- 播放队列恢复；
- Storage 浏览状态重构；
- 多笔用户播放请求排队。

本设计只保证候选 `PreparedPlayback` 形成前，旧传输资源继续有效。开始播放器切换后，不承诺旧画面持续到新来源首帧。

## 4. 备选方案与决策

### 4.1 全部共享多租户 Server

HTTP、SMB、FTP 各保留一个 Server，所有请求通过 `leaseId` 路由到独立上下文。

优点：端口和 Server 数量稳定。

缺点：SMB/FTP 的连接、流、Range 和清理状态会集中到一个复杂共享 Server，容易再次形成宽而浅的状态容器。

结论：拒绝。

### 4.2 全部每租约独立 Server

HTTP、SMB、FTP 每次准备都创建独立 Server。

优点：隔离直观。

缺点：HTTP 代理本身适合多租户映射，频繁创建 Server 和随机端口没有必要；线程、端口和清理开销更高。

结论：拒绝。

### 4.3 混合租约模型

- HTTP：单 Server、多租约路由；
- SMB：每租约独立 Server 与独立 Storage 会话；
- FTP：每租约独立 Server 与独立 Storage 会话；
- Thunder：每租约独立 taskId；
- Bilibili：身份 + sessionToken，多代 Session 短暂并存；
- 其他协议：使用轻量 direct lease。

优点：按现有协议真实差异设计，隔离明确，避免无意义的统一实现。

结论：采用。

参考成熟播放器对运行时与来源解析的职责分离，资源获取留在 core_storage，播放请求/恢复策略由后续 player 会话持有。具体代理仍按本项目多内核和协议差异选择，不照搬只适用于 Media3 DataSource 的方案；参见[交接设计 §4.4](2026-07-18-playback-handoff-design.md)。

## 5. 领域术语

### 5.1 传输租约（Playback Transport Lease）

一个已经准备完成的播放传输资源。它向调用方提供本次租约有效期内的播放 URL，并拥有幂等释放能力；该 URL 不等于可持久化的稳定媒体身份。

### 5.2 租约 ID（Lease ID）

不可预测的随机标识，用于区分并存来源。HTTP 路由和日志只使用租约 ID，不使用完整 URL、Header 或凭据。

### 5.3 传输上下文（Transport Context）

某个租约内部使用的协议状态，例如 HTTP upstream、SMB Storage/File、FTP Storage/File 或 Thunder taskId。

### 5.4 Direct Lease

不需要本地代理或独立任务的轻量租约。它提供远端 URL 和幂等 no-op 释放，保持调用方 Interface 一致。

### 5.5 Bilibili Session Token

绑定某个具体 Bilibili 播放 Session 代次的随机 token。Addon 读取和最终释放动作必须使用 token，而不能只按媒体身份操作“当前 Session”。Addon 借用 token，lease 是 token 的唯一销毁所有者。

## 6. 总体 Interface

逻辑 Interface：

```text
PlaybackTransportLease
  leaseId
  playUrl
  release()
```

可选诊断字段只允许包含：

- 传输类型；
- 租约 ID；
- 脱敏来源指纹；
- 是否已释放。

禁止暴露：

- Header/Cookie；
- 完整 URL query；
- 明文密码；
- 磁链正文；
- 本地完整路径；
- SMB/FTP 客户端实例；
- Thunder task 实现对象；
- Bilibili Session 对象。

`release()` 使用原子状态保证幂等。一个租约释放失败不能阻止其他资源继续释放。

## 7. 资源所有权

### 7.1 准备阶段

```text
借用入口文件，提取协议身份与队列元数据
  -> factory 创建独立播放 Storage（SMB/FTP）并重新解析文件
  -> provider 创建 Server/任务/token，形成 lease
  -> 返回 AcquiredPlaybackTransport(file, lease)
  -> StorageVideoSource 接管 Addon 与 lease
  -> 后续 PreparedPlayback 接管 Source，只委托 Source 释放
```

`AcquiredPlaybackTransport` 只是装配结果，file 是租约资源的借用视图，不是另一个关闭所有者。成功交给来源前，调用方负责失败时释放 lease；交接后解除该清理责任。

任一步异常或取消按当前所有者清理：lease 尚未形成时，准备作用域关闭已创建的 Server/File/Storage；形成后只调用 lease.release；Source 形成后只调用来源释放入口。不能在 finally 中既释放 lease 又直接关闭同一 Storage。取消清理后重新抛出取消异常。

factory 的准备作用域拥有独立 Storage/File；provider 在成功返回 lease 前仅借用它们，拥有自己新建的 Server/task/token。provider 失败先清理自己新增的资源，再由 factory 清理 Storage/File；成功时二者一并转入 lease。包括切换协程上下文时结果未能交付的取消窗口，也必须有明确的当前所有者负责释放，不能只在正常返回路径转移标志。

入口的浏览 Storage/File 始终是借用，不能加入播放关闭清单。只有真正独占的连接/句柄/任务才进入 lease；纯配置/元数据可复制，具有关闭行为的共享对象不得冒充独占资源。

### 7.2 激活阶段

```text
Active(A lease)
  + Prepared(B lease)
  -> 播放器应用 B
  -> 活动所有权转移到 B lease
  -> backend 确认不再使用 A 后，释放 A Source（Addon -> lease）
```

若 B 尚未进入不可取消的提交/应用阶段，就被更新请求 C 取代：

- 经 B Source 释放入口清理 Addon/lease（尚未形成 Source 才直接释放 lease）；
- A 继续；
- 准备 C。

同身份更换 URL/画质也按来源实例/租约所有权退役，不能只比较媒体 identity 决定是否释放。只换内核而继续使用同一来源时保留 lease。Addon 停止自己的工作，token/协议资源由 lease 统一关闭；一项清理失败不得跳过后续清理。

### 7.3 同一活动会话内

后续播放交接项目最多同时持有以下可用于新播放的逻辑对象：

- 一个活动租约；
- 一个候选租约。

取消后尚未关闭完成的资源需跟踪退役，不算新的可用候选。租约层自身不编码全局数量限制；会话负责预加载与用户请求优先级。

## 8. HTTP 多租户租约

### 8.1 Server 生命周期

保留一个进程内 `HttpPlayServer`。Server 启动后可以服务多个租约路径：

```text
http://127.0.0.1:<port>/lease/<leaseId>/<encodedName>
```

Server 内部持有：

```text
leaseId -> HttpUpstreamContext
```

每个上下文独立包含：

- upstream URL；
- upstream Header；
- content type/length；
- TLS policy；
- seek/range 状态；
- range retry supplier；
- 请求节流和诊断状态。

当前 `HttpPlayServer` 中所有与 upstream 相关的全局字段必须移动到上下文。Server 只负责：

- 路径解析；
- 上下文查找；
- 转发到对应上下文的 Range 处理；
- 注册和删除租约。

### 8.2 注册与释放

```text
register(source)
  -> 生成 leaseId
  -> 写入 context map
  -> 确认 Server 已启动
  -> 返回 HttpTransportLease

release(leaseId)
  -> 从 map 删除对应 context
```

释放一个 HTTP 租约不得停止 Server，也不得清理其他上下文。过期 URL 访问已释放 leaseId 时返回 404。

### 8.3 并发

- context map 使用线程安全结构；
- 同一租约的 Range retry 状态独立加锁；
- 不使用一个全局 `upstreamRangeLock` 串行所有租约；
- 日志只记录 leaseId 和 URL 指纹。

## 9. SMB 每租约独立 Server

每个 SMB 租约创建：

- 新的 `SmbStorage`；
- 在新 Storage 上按完整共享路径重新解析的 `SmbStorageFile`；
- 独立 `SmbPlayServer`；
- 独立随机端口；
- 独立输入流状态。

`SmbPlayServer` 不再是单例，不再在 `startSync()` 中释放其他实例。

由 `PlaybackTransportFactory` 创建新 Storage，复用 `StorageFactory.createStorage()` 的凭据解析能力；以 `storagePath()`（含 share）调用新连接的 `pathFile(..., false)`，校验媒体身份，再交给 SMB provider。不得传入浏览 file，也不能用 `file.clone()`，因为当前 clone 仍引用原 Storage。校验/准备使用同一独立连接，不能先在浏览连接验证后把它塞入 lease。

租约释放顺序：

1. 将租约标记为不可服务，阻止新的读请求并与正在建立的响应协调，再关闭当前输入流；
2. 停止该 Server；
3. 关闭该 StorageFile；
4. 关闭该 SmbStorage。

每步独立 `runCatching`，保证后续清理继续执行。

Server.start、文件解析或 URL 装配任一失败，准备作用域按已获取顺序逆向清理；返回 lease 后由 lease 接管。Server.release 不额外关闭 Storage/File，防止与 lease 重复。流由 server 管理，关闭时清空其引用。

## 10. FTP 每租约独立 Server

每个 FTP 租约创建：

- 新的 `FtpStorage`；
- 在新 Storage 上按稳定路径重新解析的 `FtpStorageFile`；
- 独立 `FtpPlayServer`；
- 独立随机端口。

`FtpPlayServer` 不再是单例。请求流只操作自己的 Storage 和 File。

租约释放顺序：

1. 将租约标记为不可服务并阻止新的读请求；
2. 停止该 Server、协调在途请求，然后由 Storage 的唯一流所有者关闭流并调用 `completePending()`；
3. 关闭该 StorageFile；
4. 关闭该 FtpStorage。

factory 与 SMB 使用同一隔离规则，新建 `FtpStorage` 后重新解析文件；不能与活动来源或浏览页共享 FTPClient。流关闭、pending 完成与 disconnect 在同一 Storage 生命周期内协调，清空流引用，重复关闭不会重复提交 pending。当前 `pathFile()` 存在元数据失败后构造占位文件的行为，不能仅凭非空返回就宣称服务器文件存在；实施时在播放 acquisition 验证可访问性并返回结构化失败，浏览兼容行为可保留。

## 11. Thunder task 租约

### 11.1 创建

`generatePlayUrl()` 不再调用 `stopAllTask()`。每次创建播放任务后返回：

- taskId；
- local play URL；
- torrent 身份；
- 文件 index。

Thunder 的内部映射不能继续只按 torrent path 保存一个 taskId，因为同一 torrent 的不同 index 也可能短暂并存。应按 taskId 或 leaseId 保存任务记录。

### 11.2 释放

Thunder lease 只调用：

```text
stopTask(itsTaskId)
```

禁止释放其他租约任务。`stopAllTask()` 不再用于普通播放来源准备或切换，只能保留为显式全局清理能力。

### 11.3 兼容性验证

磁链下载兼容校验在创建播放 task 之前完成；被阻止的候选不创建 transport lease。

`AUDIO_ONLY_FALLBACK` 维持当前行为：允许播放并返回提示。本项目不新增音频强制模式。

## 12. Bilibili Session token

### 12.1 Store

Store 从：

```text
identity -> current session
```

改为：

```text
identity -> token -> session
```

`put(session)` 返回 `sessionToken`。同身份的新旧 Session 可以短暂并存。

### 12.2 Addon

`BilibiliPlaybackAddon` 和直播 Addon 固定持有自己的 token：

- 读取 Session 时按 token；
- 设置/恢复操作只携带自己的 token；租约阶段保留既有操作语义，后续交接阶段由会话创建独立候选后提交替换；
- `onRelease()` 只停止自身协程、监听和事件派发，不删除 session/token；
- 来源释放先清理 Addon，再由 lease 删除自己的 token 及对应 heartbeat/session 资源；
- 旧来源释放不能删除新候选 Session。

### 12.3 StorageVideoSource

创建 Bilibili 来源时，将具体 token 传给 Addon。禁止 Addon 在运行期按身份重新获取“当前最新 Session”。

候选来源失败时，由候选租约释放自己的 token。

## 13. Direct Lease

以下来源不需要本地代理时使用 Direct Lease：

- 可直接由播放器访问的 HTTP/HTTPS；
- Alist/Baidu/115 返回的临时 URL；
- Bilibili 最终播放 URL外层；
- 本地文件/content URI；
- 投屏直接 URL。

Direct Lease 仍然保留：

- 随机 leaseId；
- playUrl；
- 可选 source-specific release action；
- 幂等释放。

如果来源内部还有 Bilibili Session 或其他资源，应把释放动作组合进 Direct Lease，不能使用纯 no-op。

## 14. Storage 与 Source 装配

### 14.1 Storage Interface 演进

保持 core_contract 中的 Storage 接口不反向依赖 core_storage。传输获取在 core_storage 内部 provider/factory 上实现，逻辑语义为：

```text
PlaybackTransportFactory.acquire(storage, file, profile)
  -> AcquiredPlaybackTransport(playbackFile, lease)

PlaybackTransportProvider.acquirePlaybackTransport(ownedOrBorrowedFile, profile)
  -> PlaybackTransportLease
```

factory 只借用调用参数：SMB/FTP 先独立建连和重解析，再调用新 Storage 的 provider；provider 成功后接管这些独占资源。其他协议使用专用 provider，未知或需资源所有权的类型不能失败后回落到裸 URL。仅经检查确实无可关闭资源、无代理副作用的直接来源可使用 Direct Lease。

返回的 playbackFile 用于字幕/弹幕和来源装配；它与 lease 绑定的 Storage 必须一致。对于无需独立连接的直接来源，元数据可以借用或复制，但 lease 不关闭浏览对象。长期持有的 retry supplier/回调不得捕获会被浏览页关闭的连接；需要连接时纳入该 lease 所有权。

旧 `createPlayUrl()` 在迁移阶段保留，但标记为兼容入口；所有调用迁移完成后，不允许 feature 或新 resolver 直接获取裸 URL。

### 14.2 StorageVideoSourceFactory

Factory 流程改为：

1. 提取身份、历史/队列元数据快照并解析 PlaybackProfile；不将原浏览连接交给租约。
2. 通过统一 factory 获取 `AcquiredPlaybackTransport`，取得独立播放 file 和 lease。
3. 在播放 file/Storage 上准备弹幕、字幕、音轨；不再对浏览 Storage 执行播放准备 I/O。
4. 用播放 file、固定队列身份和 lease 构造 `StorageVideoSource`，不混入原浏览 file；必要历史字段只复制数据。
5. Source 成功创建即接管 Addon/lease；任一步失败时按当前所有者清理，Addon 失败不能阻止 lease 清理。

`StorageVideoSource` 的 URL 来自 lease，通过幂等 `releasePlaybackResources()` 释放 Addon/lease。后续 PreparedPlayback 拥有 Source 并委托该入口；不取出同一 lease 再拥有一次，也不向普通调用方暴露协议上下文。

### 14.3 队列内切源

当前 `StorageVideoSource.indexSource()` 用原列表中的文件重新调用 factory。租约项目阶段即要求该 factory 每次独立获取 SMB/FTP 播放连接，不能把修复共享连接推迟到交接项目。兼容队列提取媒体库/完整路径/uniqueKey 等元数据，不能靠旧播放连接存活才能解析下一项。

最终交接项目由活动会话持有稳定队列并调 resolver，删除跨身份 `indexSource()` 作为业务切换入口。浏览目录变化不影响已捕获的播放队列。

## 15. 错误模型

租约创建使用结构化结果：

```text
TransportAcquisitionFailure
  ServerStartFailed
  PortUnavailable
  UpstreamUnavailable
  ProtocolConnectionFailed
  TaskCreationFailed
  SessionUnavailable
  Superseded
  Unknown
```

规则：

- 不通过错误文案正则分类；
- 用户提示由上层播放准备错误 Mapper 决定；
- 协议异常和完整 URL 不直接进入 UI；
- 失败日志使用 leaseId、协议类型和脱敏指纹。

## 16. 安全

- HTTP leaseId 使用不可预测随机值；
- 已释放或未知 leaseId 返回 404；
- Server 只监听 loopback；
- 不在路径中编码 upstream URL、Cookie 或 Header；
- 日志不记录完整代理 URL query；
- Bilibili sessionToken 不进入 Intent；
- Thunder 日志使用脱敏磁链和路径；
- SMB/FTP 凭据仍由现有 MediaLibraryCredentialStore 管理，不复制到租约模型。

## 17. 测试设计

### 17.1 通用租约测试

- `release()` 幂等；
- A、B 同时存在；
- 释放 A 不影响 B；
- 释放 B 不影响 A；
- candidate superseded 后资源被释放；
- 一项清理失败不阻止其他清理。

### 17.2 HTTP

- 两个 leaseId 返回各自 upstream；
- Header/TLS/Range 状态不串线；
- 释放 A 后 A 返回 404，B 仍可访问；
- 并发 Range 请求互不覆盖 retry 状态；
- URL 和日志不包含上游敏感参数。

### 17.3 SMB/FTP

- A、B 使用不同 Server 实例和端口；
- B 启动不停止 A；
- 释放 A 不关闭 B 的 Storage 会话；
- 流关闭与 Server 停止幂等；
- 启动失败清理候选 Storage。
- 测试从生产 factory/acquisition 入口调用，注入 client/server 创建点，验证 A、B、浏览各自使用不同连接；不能仅手工构造两个 fake lease。
- 分别释放 A/B 后验证另一方可继续读、浏览可继续列目录；同一文件两次 acquisition 也独立。
- 在建连、解析文件、启动 Server、URL 装配及来源构建期间注入异常/取消，检查每个句柄恰好关闭一次。
- 文件 clone 不作为独立连接；FTP 占位元数据不误判为可播放文件；退役与新读请求交错时不再打开已释放资源。

### 17.4 Thunder

- 创建 B 不停止 A task；
- 同一 torrent 不同 index 可并存；
- `stopTask(A)` 不删除 B；
- candidate superseded 只清理 candidate task；
- 显式全局清理仍可停止全部任务。

### 17.5 Bilibili

- 同 identity 新旧 token 可并存；
- Addon 释放不删除任何 session token；旧 lease 释放仅删除自己的 token；
- 候选失败只删除候选 token；
- Addon 不按 identity 获取最新 Session；
- token 不进入错误文案和日志。

## 18. 迁移顺序

1. 建立通用 Transport Lease 与资源释放测试；
2. HTTP Server 多租户化；
3. SMB 独立 Storage/File/Server 获取与失败清理；
4. FTP 独立 Storage/File/Server 获取与失败清理；
5. Thunder task lease；
6. Bilibili Session token；
7. Storage 获取租约的统一入口；
8. StorageVideoSourceFactory/StorageVideoSource 接入；
9. 原有播放路径回归；
10. 增加静态门禁，禁止新代码直接依赖旧单例或裸 URL路径。

每个协议 Adapter 分批验证，最终同时核对 factory 装配；提交仅在用户授权后执行。优先复用已有工厂/配置，不新增通用连接池或生命周期管理器。

## 19. 构建与验证

实现阶段至少执行：

```text
./gradlew :core_storage_component:testDebugUnitTest
./gradlew :bilibili_component:testDebugUnitTest
./gradlew testDebugUnitTest
./gradlew verifyModuleDependencies
./gradlew lint
./gradlew assembleDebug
```

网络 Server 和实际协议资源需要补充仪器或集成验证。所有 Gradle 命令必须读取输出尾部，明确确认 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

## 20. 完成标准

满足以下全部条件才视为完成：

- `StorageVideoSource` 不再只持有无所有权的裸代理 URL；
- HTTP 两个租约可以同时服务不同 upstream；
- SMB/FTP 的浏览、活动和候选连接独立，装配测试证明释放一方不影响另一方；
- Thunder 候选 task 不停止活动 task；
- Bilibili 同身份新旧 Session 按 token 隔离；
- 所有租约释放幂等；
- 准备作用域、Source 和 lease 逐级转移所有权；Addon/PreparedPlayback 不重复关闭底层资源；
- 释放旧来源不影响新来源，释放候选不影响活动来源；
- 没有 Header、Cookie、voucher、磁链或完整路径泄漏到日志；
- 现有普通播放和内部切源行为没有回归；
- 单元测试、依赖治理、lint 和 Debug 构建明确成功；缺失协议设备验证单独记录，不视为通过。

## 21. 与播放交接设计的关系

播放交接设计必须依赖本项目完成后再实施完整迁移：

```text
稳定 PlaybackDescriptor
  -> resolver
  -> acquire Transport Lease
  -> PreparedPlayback
  -> ActivePlaybackSession 激活
  -> 释放旧 Lease
```

任何后续播放入口或内部切源不得绕过 Transport Lease 直接修改全局代理、Server、task 或 Bilibili Session Store。
