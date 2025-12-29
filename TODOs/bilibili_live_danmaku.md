# Bilibili 直播实时弹幕流（WebSocket）处理与渲染 TODO

## 目标

在当前工程已完成的「Bilibili 直播播放适配」基础上，为 `bilibili://live/{roomId}` 的播放场景补齐 **实时弹幕流的接收、解析与渲染**，让直播间弹幕能够在播放器中实时展示。

## 现状

- 播放侧弹幕渲染已具备：`player_component/.../DanmuView.kt` 基于 DanmakuFlameMaster（DFM）渲染弹幕。
- Bilibili 点播弹幕已具备：通过 `cid` 下载 `comment.bilibili.com/{cid}.xml` / `x/v1/dm/list.so` 落盘后加载。
- **缺失**：直播弹幕为 WebSocket/TCP 信息流（非 XML 文件），工程中目前没有连接、解包、心跳、重连与实时推送到 `DanmuView` 的链路。

## 范围界定（建议分阶段）

### MVP（先做能用）

- 仅支持「直播间实时弹幕（DANMU_MSG）」展示
- 连接方式：WSS
- 压缩协议：优先使用 `protover=2`（zlib），避免引入 brotli 依赖
- 重连：断线自动重连（指数退避 + host 轮询）
- 仅支持“观看端显示”，不做“发送弹幕”

## 对齐现有架构的模块划分（建议）

- `data_component`
  - 放数据模型：`getDanmuInfo` 返回结构、以及“直播弹幕业务事件”（`LiveDanmakuEvent` 等）
- `common_component`
  - 放 Bilibili 直播弹幕链路的 **领域实现**：获取 token/host、WebSocket 客户端、二进制封包/解包、解压、重连策略
  - 建议放在 `common_component/.../bilibili/live/danmaku/` 之下，避免把 B 站特有协议散落在通用网络层
- `player_component`
  - 放播放器侧适配：与现有 `VideoTrackBean`/`DanmuController`/`DanmuView` 的轨道与渲染逻辑对齐（把事件投递到 DFM）

## 与当前播放器弹幕轨道逻辑的耦合点（需要在方案中明确）

当前工程的弹幕展示链路并不是“直接往 DanmuView 推事件”，而是：

- 播放页通过 `VideoController.addExtendTrack(VideoTrackBean.danmu(LocalDanmuBean))` 添加弹幕轨道（见 `PlayerActivity.afterInitPlayer()`）
- `TrackType.DANMU` 当前只接受 `LocalDanmuBean`，`DanmuView.addTrack()` 会读取本地文件并调用 `prepare(parser, context)` 初始化 DFM
- 只有 **prepare 完成** 后，`DanmuView.addDanmuToView()` 才能稳定地把弹幕塞进 DFM 的调度时间轴

因此直播弹幕要“低耦合 + 跟现有逻辑一致”，推荐把“直播实时弹幕”也做成 **一个 Danmu 轨道资源**，并补齐以下设计点：

1) **轨道资源类型扩展（推荐用统一模型，而不是另起一套 UI）**
   - 保持 `TrackType.DANMU` 不变（复用现有弹幕开关/透明度/速度/屏蔽/语言转换等设置）
   - 将 `trackResource` 从“仅 `LocalDanmuBean`”扩展为“本地文件 / 实时流”二选一，例如：
     - `DanmuTrackResource.LocalFile(LocalDanmuBean)`
     - `DanmuTrackResource.BilibiliLive(storageKey, roomId)`
   - 这样弹幕轨道 UI 不需要分叉，耦合点集中在 `DanmuController/DanmuView` 的轨道适配层

2) **DFM 初始化策略（直播场景必须有一个“空 Parser”）**
   - 直播没有 XML 文件，但仍需调用 `prepare()` 让 DFM 进入可接收状态
   - 建议提供一个 `EmptyDanmakuParser`（或等价实现）专用于直播轨道初始化

3) **多轨道并存规则**
   - 方案里提到“与本地弹幕轨道并存”（见下文 UI TODO），但当前 `DanmuController.getTracks()` 只返回单一轨道
   - 若要并存，需要明确：轨道列表如何展示、默认选中谁、切换时是否释放连接/清屏、以及“禁用”行为对连接的影响（建议：禁用弹幕时同时断开直播弹幕连接，降低后台耗电与重连噪音）

4) **播放器生命周期绑定**
   - 连接建立/关闭应跟随“轨道选中状态 + 播放源切换 + 播放器释放”
   - 建议把 LiveSocket 的 start/stop 放在弹幕控制器层（或独立的 `LiveDanmakuController`），并在 `danmuRelease()`/切源时确保关闭，避免多连接与内存泄漏

## 功能 TODO（详细）

### 1）获取信息流连接参数（token + host）

新增接口（直播侧）：

- `GET https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id={roomId}`
  - 关键返回：`data.token`、`data.host_list[]`
  - 备注：近期策略变动要求 **WBI 签名**，并且 Cookie 中 `buvid3` 不能为空（建议在登录/进入直播前预热 `https://www.bilibili.com/` 获取必要 cookie）

TODO：

- [ ] `BilibiliService`/`BilibiliRepository` 增加 `getDanmuInfo()` 封装（走 `BASE_LIVE`）
- [ ] 复用现有 `BilibiliWbiSigner` 对参数进行签名（注意 baseUrl 不同，但签名规则一致）
- [ ] 明确 `roomId` 的长/短号处理：必要时先用 `get_info(room_id=xxx)` 得到真实长号再请求 `getDanmuInfo`
- [ ] 未登录/游客态限制处理：若昵称/uid 被打码，UI 给出提示（不作为错误）

### 2）WebSocket 连接、认证与心跳

连接地址：

- `wss://{host}:{wss_port}/sub`

认证包（op=7）正文为 JSON，关键字段：

- `uid`：游客为 0；登录态可填 mid（需与获取 token 的账号一致）
- `roomid`：直播间真实 id
- `protover`：建议 `2`（zlib）；可选 `3`（brotli）
- `platform`：`web`
- `type`：`2`
- `key`：`getDanmuInfo.data.token`

心跳：

- 每 ~30s 发送心跳包（op=2），接收 op=3 返回人气值（可用于 UI 显示）

TODO：

- [ ] 基于 OkHttp `WebSocket` 实现 `LiveDanmakuSocketClient`
- [ ] **复用 Bilibili 的 OkHttpClient 配置**（CookieJar/User-Agent/Referer 等），避免出现“HTTP 取流能用但 WS 因 cookie/UA 不一致被限流”的耦合问题
- [ ] 实现二进制封包：16 字节 header + body，支持 op=7/op=2
- [ ] 处理认证回复（op=8，JSON `{"code":0}`），失败时切 host 或提示
- [ ] 心跳调度与超时：若连续 N 次心跳无回复或连接关闭，触发重连
- [ ] 重连策略：指数退避 + host_list 轮询 + 最大重试窗口（避免后台疯狂重连）

### 3）数据包解包、解压与命令分发

包头字段（16B）：

- packetLen / headerLen / protocolVer / operation / sequence

需要支持：

- `protocolVer=0/1`：正文为 JSON（未压缩）
- `protocolVer=2`：正文为 zlib 压缩（需要先 inflate）
- （可选）`protocolVer=3`：brotli（且可能包含多个“带头部的普通包”）

op=5（普通包/命令）可能一次携带多条命令，需要“循环按 packetLen 逐包拆解”。

TODO：

- [ ] 实现 `LiveDanmakuPacketCodec`：encode/decode + 多包拆分
- [ ] zlib 解压：`Inflater`（注意流式/完整 buffer 的处理）
- [ ] 命令解析：从 JSON 中读取 `cmd` 字段并路由到对应 handler（注意 `cmd` 可能为 `DANMU_MSG:xxx`，推荐 `startsWith("DANMU_MSG")`）
- [ ] 最小命令集：`DANMU_MSG`（弹幕文本）
- [ ] 数据脱敏日志：禁止在日志中输出 Cookie、token、完整昵称/uid 等敏感信息

### 4）事件模型与播放器侧渲染桥接

建议将 WebSocket 命令先映射为“业务事件”，再由播放器层决定渲染策略：

- `LiveDanmakuEvent.Danmaku(text, color, isTop/isBottom/isScroll, timestamp, userLevel...)`
- （后续）`Gift/Guard/SuperChat` 等

渲染侧（DFM）关键点：

- 直播弹幕没有可回放的时间轴，推荐以「加入直播时刻」作为 0 点：
  - `base = playerCurrentPositionMs`（或单独维护 `joinAtMs`）
  - 每条弹幕 `time = currentTime + smallDelay`（例如 200~500ms）以确保能被 DFM 正常调度显示
- 注意线程：网络接收在 IO 线程，投递到 `DanmuView` 应使用 `post { ... }`
- 弹幕过滤/简繁转换：复用现有 `DanmuView` 的 filter（关键字、正则、OpenCC）

TODO：

- [ ] 新增 `LiveDanmakuRendererBridge`：将 `LiveDanmakuEvent` 转为 `BaseDanmaku` 并投递
- [ ] **复用现有 `SendDanmuBean`/`DanmuController.addDanmuToView()`** 作为投递入口（可天然复用过滤器/简繁转换/最大显示数等配置），避免在直播分支里重复实现一套渲染参数
- [ ] 明确“直播轨道 prepare”与“开始接收 WS”顺序：推荐先 `prepare(emptyParser)`，再开始接收并投递
- [ ] 处理播放器状态：暂停/后台时是否继续接收（建议继续接收但不渲染；或直接断开，回到前台重连）
- [ ] 限流与丢弃策略：高峰期（例如大主播）需要丢弃部分弹幕，避免 UI 卡顿

### 5）UI 与交互（播放页）

建议的交互行为：

- 当播放源为 `bilibili://live/{roomId}`：
  - 弹幕面板显示一个“直播实时弹幕”轨道（不依赖本地文件）
  - 支持开关、透明度、速度、字号、屏蔽等已存在能力
  - 显示连接状态：连接中/已连接/断开重连/受限（未登录昵称打码）

TODO：

- [ ] 播放页识别 LiveKey 并自动启用实时弹幕轨道（默认可配置）
- [ ] 增加状态提示（可复用 Toast 或在弹幕面板显示小字）
- [ ] 与现有“本地弹幕轨道”并存时的优先级规则（直播默认只显示实时流）

### 6）弹幕屏蔽：按等级（智能云屏蔽）接入方案 + 配置入口

> 需求来源：Bilibili 官方客户端提供“按等级屏蔽弹幕”（常见表现为“智能云屏蔽”等级 1-10），本项目直播弹幕也应提供同等能力。

#### 6.1 “等级”的语义（避免与用户 UL 等级混淆）

- 这里的“等级”指 **弹幕权重/推荐分**（范围通常为 0-10），用于“智能屏蔽”（AI 语义识别/长度等综合因素得出）
- **不是** 用户 UL 等级（直播消息中 `info[4]` 的等级信息），两者可同时存在但应分开配置

#### 6.2 数据来源（直播/点播）

- 直播（`DANMU_MSG`）：
  - `info[0][15].extra` JSON 中存在 `recommend_score` 字段（示例中为 `3`），可作为“智能屏蔽权重”的候选来源
  - 若该字段缺失/解析失败：按 `0` 处理（即“最低权重”），避免误判为高权重
- 点播（已存在能力，后续可统一）：
  - XML 弹幕 `p[8]` 为“弹幕的屏蔽等级（0-10）”，低于用户设定等级则屏蔽
  - protobuf 弹幕 `weight` 为“权重（0-10）”

#### 6.3 本项目内的过滤规则（本地生效，默认不改动用户 B 站账号配置）

- 配置项：
  - `aiSwitch`：是否开启智能云屏蔽
  - `aiLevel`：屏蔽等级阈值（0-10，**0 表示“默认等级（3）”**，与 B 站 web 配置语义对齐）
- 生效规则：
  - 当 `aiSwitch=false`：不做权重过滤
  - 当 `aiSwitch=true`：设定 `effectiveLevel = (aiLevel==0 ? 3 : aiLevel)`，丢弃 `score < effectiveLevel` 的弹幕
- 落点位置：
  - 建议在 `LiveDanmakuEvent` → `SendDanmuBean` 之前过滤（减少 DFM 压力、降低 UI 卡顿风险）
  - 过滤后仍继续走 `DanmuView` 现有过滤链（关键字/正则/简繁转换/最大行数等）

#### 6.4 配置入口：放在 Bilibili 媒体库配置界面（StoragePlus）

对应当前工程的入口与文件：

- 入口：新增/编辑媒体库 → `Bilibili媒体库`（`StoragePlusActivity` → `BilibiliStorageEditDialog`）
- UI 文件：`storage_component/.../BilibiliStorageEditDialog.kt` + `dialog_bilibili_storage.xml`

推荐新增一个“弹幕屏蔽”分组（与现有“取流模式/画质/编码”并列，保持一致的交互样式）：

- `智能云屏蔽`：开关（On/Off）
- `屏蔽等级`：选择器（0-10，0 显示为“默认（3）”）

存储建议：

- 新增 `BilibiliDanmakuBlockPreferencesStore`（MMKV，按 `storageKey` 隔离，与 `BilibiliPlaybackPreferencesStore` 一致）
- 默认值：`aiSwitch=false`、`aiLevel=0`（即“关掉智能屏蔽”，并保留默认等级语义）

#### 6.5 （可选）与 B 站账号配置同步

若希望与官方客户端保持一致，可提供一个“同步到账号”的显式开关（默认关闭，避免用户不知情地修改账号全局设置）：

- `POST https://api.bilibili.com/x/v2/dm/web/config`
  - `ai_switch` / `ai_level`（需要登录 Cookie + csrf）

## 验收标准（建议）

- 进入任意直播间后 3 秒内开始出现实时弹幕
- 切到后台/锁屏后不会导致无限重连刷日志；回到前台能恢复（重连成功）
- 弹幕开关/屏蔽/透明度等设置对直播弹幕同样生效
- 高弹幕量房间不会明显掉帧（至少有基本限流/丢弃）
- 启用“智能云屏蔽”后，低权重弹幕能被稳定过滤（不影响关键字/正则等已有屏蔽能力）

## 测试建议

- 单元测试：封包/解包、zlib 解压、多包拆分、`cmd` 路由（使用录制的“脱敏二进制样本”）
- 联调测试：至少验证 3 类房间
  - 普通房间（低弹幕）
  - 高弹幕房间（压测限流）
  - 未登录限制房间（昵称/uid 打码提示）

## 参考资料

- bilibili-API-collect：直播信息流（getDanmuInfo + WebSocket 数据包格式）
  - `docs/live/message_stream.md`
