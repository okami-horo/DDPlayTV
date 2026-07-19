# 播放传输资源租约设计

> 日期：2026-07-19  
> 状态：设计已确认，等待书面规格复核  
> 范围：HTTP 代理、SMB、FTP、Thunder 与 Bilibili 播放 Session 的并存、代次和释放所有权  
> 后续依赖：`2026-07-18-playback-handoff-design.md`

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

## 5. 领域术语

### 5.1 传输租约（Playback Transport Lease）

一个已经准备完成的播放传输资源。它向调用方提供稳定播放 URL，并拥有幂等释放能力。

### 5.2 租约 ID（Lease ID）

不可预测的随机标识，用于区分并存来源。HTTP 路由和日志只使用租约 ID，不使用完整 URL、Header 或凭据。

### 5.3 传输上下文（Transport Context）

某个租约内部使用的协议状态，例如 HTTP upstream、SMB Storage/File、FTP Storage/File 或 Thunder taskId。

### 5.4 Direct Lease

不需要本地代理或独立任务的轻量租约。它提供远端 URL 和幂等 no-op 释放，保持调用方 Interface 一致。

### 5.5 Bilibili Session Token

绑定某个具体 Bilibili 播放 Session 代次的随机 token。Addon 和释放动作必须使用 token，而不能只按媒体身份操作“当前 Session”。

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
Storage/File 已解析
  -> acquire transport lease
  -> lease 提供 playUrl
  -> StorageVideoSource 创建成功
  -> 候选 PreparedPlayback 持有租约
```

任一步失败：

- 释放已创建租约；
- 关闭候选 StorageFile；
- 关闭候选 Storage；
- 不影响活动来源。

### 7.2 激活阶段

```text
Active(A lease)
  + Prepared(B lease)
  -> 播放器应用 B
  -> 活动所有权转移到 B lease
  -> 释放 A lease
```

若 B 在应用前被更新请求 C 取代：

- 释放 B lease；
- A 继续；
- 准备 C。

### 7.3 同一活动会话内

后续播放交接项目将最多同时持有：

- 一个活动租约；
- 一个候选租约。

租约层自身不编码全局数量限制，避免和未来顺序播放预加载形成隐藏耦合。

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
- 当前 `SmbStorageFile`；
- 独立 `SmbPlayServer`；
- 独立随机端口；
- 独立输入流状态。

`SmbPlayServer` 不再是单例，不再在 `startSync()` 中释放其他实例。

租约释放顺序：

1. 关闭当前输入流；
2. 停止该 Server；
3. 关闭该 StorageFile；
4. 关闭该 SmbStorage。

每步独立 `runCatching`，保证后续清理继续执行。

## 10. FTP 每租约独立 Server

每个 FTP 租约创建：

- 新的 `FtpStorage`；
- 当前 `FtpStorageFile`；
- 独立 `FtpPlayServer`；
- 独立随机端口。

`FtpPlayServer` 不再是单例。请求流只操作自己的 Storage 和 File。

租约释放顺序：

1. `completePending()`；
2. 停止该 Server；
3. 关闭该 StorageFile；
4. 关闭该 FtpStorage。

候选 FTP 来源必须由新的 Storage 实例解析，不能和活动来源共享协议连接。

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
- 偏好切换时更新自己的 Session；
- `onRelease()` 只删除自己的 token；
- 旧 Addon 释放不能删除新候选 Session。

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

新增传输获取行为，逻辑语义为：

```text
acquirePlaybackTransport(file, profile) -> PlaybackTransportLease?
```

默认实现可把现有直接 URL包装为 Direct Lease。SMB、FTP、HTTP 代理、Torrent 和 Bilibili 提供专用实现。

旧 `createPlayUrl()` 在迁移阶段保留，但标记为兼容入口；所有调用迁移完成后，不允许 feature 或新 resolver 直接获取裸 URL。

### 14.2 StorageVideoSourceFactory

Factory 流程改为：

1. 解析 PlaybackProfile；
2. 获取 Transport Lease；
3. 获取弹幕、字幕、音轨；
4. 创建 `StorageVideoSource`；
5. 将租约所有权交给 Source/PreparedPlayback；
6. 后续失败则释放租约。

`StorageVideoSource` 的播放 URL来自租约。它必须允许活动会话取得并转移租约所有权，但不向普通调用方暴露协议上下文。

### 14.3 队列内切源

当前 `StorageVideoSource.indexSource()` 直接使用共享 Storage 和目录文件创建下一来源。为避免新旧 SMB/FTP 会话共享，后续播放交接项目会将跨身份队列切换交给活动会话 resolver。

租约项目阶段可保留该兼容路径，但必须保证旧 Source 释放只释放旧租约。最终播放交接完成后，跨身份 `indexSource()` 不再成为外部切换入口。

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

### 17.4 Thunder

- 创建 B 不停止 A task；
- 同一 torrent 不同 index 可并存；
- `stopTask(A)` 不删除 B；
- candidate superseded 只清理 candidate task；
- 显式全局清理仍可停止全部任务。

### 17.5 Bilibili

- 同 identity 新旧 token 可并存；
- 旧 Addon 释放不删除新 token；
- 候选失败只删除候选 token；
- Addon 不按 identity 获取最新 Session；
- token 不进入错误文案和日志。

## 18. 迁移顺序

1. 建立通用 Transport Lease 与资源释放测试；
2. HTTP Server 多租户化；
3. SMB Server 实例化；
4. FTP Server 实例化；
5. Thunder task lease；
6. Bilibili Session token；
7. Storage 获取租约的统一入口；
8. StorageVideoSourceFactory/StorageVideoSource 接入；
9. 原有播放路径回归；
10. 增加静态门禁，禁止新代码直接依赖旧单例或裸 URL路径。

每个协议 Adapter 单独提交和验证，避免一次修改全部传输协议。

## 19. 构建与验证

实现阶段至少执行：

```text
./gradlew :core_storage_component:testDebugUnitTest
./gradlew :bilibili_component:testDebugUnitTest
./gradlew testDebugUnitTest
./gradlew verifyModuleDependencies
./gradlew assembleDebug
```

网络 Server 和实际协议资源需要补充仪器或集成验证。所有 Gradle 命令必须读取输出尾部，明确确认 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

## 20. 完成标准

满足以下全部条件才视为完成：

- `StorageVideoSource` 不再只持有无所有权的裸代理 URL；
- HTTP 两个租约可以同时服务不同 upstream；
- SMB/FTP 候选 Server 不停止活动 Server；
- Thunder 候选 task 不停止活动 task；
- Bilibili 同身份新旧 Session 按 token 隔离；
- 所有租约释放幂等；
- 释放旧来源不影响新来源，释放候选不影响活动来源；
- 没有 Header、Cookie、voucher、磁链或完整路径泄漏到日志；
- 现有普通播放和内部切源行为没有回归；
- 单元测试、依赖治理和 Debug 构建明确成功。

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
