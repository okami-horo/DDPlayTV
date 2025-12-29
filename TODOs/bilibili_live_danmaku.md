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

### 可选增强（后续迭代）

- 支持更多命令：礼物、上舰、SC（醒目留言）、进场提示等
- 支持 `protover=3`（brotli）并兼容多包合并
- 直播弹幕录制为本地轨道（回放/导出）
- 弹幕发送（需要 CSRF、鉴权、风控处理）

## 对齐现有架构的模块划分（建议）

- `data_component`
  - 放数据模型：WebSocket 返回的 `getDanmuInfo`、解析后的事件模型（`LiveDanmakuEvent` 等）
- `common_component`
  - 放网络与协议实现：获取 token/host、WebSocket 客户端、二进制封包/解包、解压、重连策略
- `player_component`
  - 放渲染与 UI 编排：把 `LiveDanmakuEvent` 转成 DFM `BaseDanmaku` 并投递到 `DanmuView`

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
- [ ] 命令解析：从 JSON 中读取 `cmd` 字段并路由到对应 handler
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

## 验收标准（建议）

- 进入任意直播间后 3 秒内开始出现实时弹幕
- 切到后台/锁屏后不会导致无限重连刷日志；回到前台能恢复（重连成功）
- 弹幕开关/屏蔽/透明度等设置对直播弹幕同样生效
- 高弹幕量房间不会明显掉帧（至少有基本限流/丢弃）

## 测试建议

- 单元测试：封包/解包、zlib 解压、多包拆分、`cmd` 路由（使用录制的“脱敏二进制样本”）
- 联调测试：至少验证 3 类房间
  - 普通房间（低弹幕）
  - 高弹幕房间（压测限流）
  - 未登录限制房间（昵称/uid 打码提示）

## 参考资料

- bilibili-API-collect：直播信息流（getDanmuInfo + WebSocket 数据包格式）
  - `docs/live/message_stream.md`

