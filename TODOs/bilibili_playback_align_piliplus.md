# Bilibili 媒体库/播放链路对齐 PiliPlus 的设计分析（面向 DDPlay-bilibili）

本文目标：对比当前项目（DDPlay-bilibili）在 **Bilibili 媒体库（Storage）接入 + 播放取流/选流/回退** 的实现，与稳定第三方客户端 **PiliPlus** 的播放链路设计差异，归纳哪些功能点/架构值得对齐，并给出一套可落地的分阶段设计方案。

> 说明：本文重点讨论“**播放稳定性与可控性**”（CDN、DASH、清晰度/编码选择、取流回退、播放进度上报），并尽量保持与本项目既有 `Storage/VideoSource/Player` 架构一致，而不是做零散的最小侵入补丁。

---

## 0. 一句话结论

- 当前项目 B 站链路已具备 **风控/签名/TV 兜底/本地 MPD 生成** 等关键能力，但播放侧仍偏“**生成单一路径**”——遇到 CDN 抖动、URL 失效、编码不兼容、清晰度切换等场景时，恢复手段不足。
- PiliPlus 的稳定性来自：**全格式 playurl 拉取（fnval=4048）→本地选择 video/audio →CDN 重写/回退→播放中可切换→心跳上报闭环**。
- 建议 DDPlay-bilibili 优先对齐的方向（按收益排序）：
  1) **CDN 策略与回退**（利用 `backup_url`/host 重写/失败重试）  
  2) **清晰度/编码/音质的“可切换”能力**（统一为“重取流+重建清单+无缝续播”或“多 Representation MPD + 轨道选择”）  
  3) **播放进度/心跳上报**（让服务端历史与本地播放闭环一致，提升“跨端续播”体验）

---

## 1. 当前项目现状梳理（DDPlay-bilibili）

### 1.1 模块职责（与本议题相关）

- **bilibili_component**
  - `BilibiliRepository`：取流（WEB/TV）、WBI 签名、风控补救、Cookie 管理、历史接口等  
    - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
  - `BilibiliPlayurlPreferencesMapper`：把播放偏好映射到 playurl 参数（fnval/qn/codecid/fourk…）  
    - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliPlayurlPreferencesMapper.kt`
  - `BilibiliMpdGenerator`：把 DASH（SegmentBase）写成本地 MPD  
    - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/mpd/BilibiliMpdGenerator.kt`
  - `BilibiliHeaders`：统一 UA/Referer/Cookie，并提供脱敏  
    - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/net/BilibiliHeaders.kt`

- **core_storage_component**
  - `BilibiliStorage`：把 B 站“历史记录”映射成虚拟目录，并在 `createPlayUrl` 中完成取流与 MPD 生成  
    - `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
  - `BilibiliStorageFile`：`/history/`、`/history/{bvid}/`、`/history/live/{roomId}`、`/history/pgc/...` 等虚拟项封装  
    - `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/BilibiliStorageFile.kt`

- **player_component**
  - 多内核（Media3/mpv/vlc）+ 轨道管理（音轨/字幕/弹幕）  
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt`
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`
  - B 站异常上报（已做 URL 脱敏）  
    - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/error/BilibiliPlaybackErrorReporter.kt`

### 1.2 当前播放链路（从“媒体库条目”到“播放器”）

以 BilibiliStorage 历史条目为例：

1. 上层页面选择某个 `StorageFile`  
2. 通过 `StorageVideoSourceFactory` 创建 `StorageVideoSource`（内部保存 `StorageFile`）  
   - `core_storage_component/src/main/java/com/xyoye/common_component/source/factory/StorageVideoSourceFactory.kt`
3. 播放时调用 `Storage.createPlayUrl(file)` 生成可播地址  
4. 对 BilibiliStorage：
   - UGC/PGC：请求 playurl → 若返回 DASH → 选一个 video + 一个 audio → 写本地 MPD 文件 → 播放器播放本地 MPD  
   - Live：请求 livePlayUrl → 取 `durl.first.url` 直链播放  

当前关键代码：

- `createPlayUrl`：`core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
  - Live：`liveRoomInfo + livePlayUrl + durl.first.url`
  - UGC/PGC：`repository.playurl/pgcPlayurl` → `dash.video/audio` → `BilibiliMpdGenerator.writeDashMpd(...)` → 返回 `mpdFile.absolutePath`

### 1.3 当前“选流策略/回退策略”

#### 1) 选流

- Video：按 `preferredVideoCodec` 过滤候选，再按 `preferredQualityQn` 选一个；若未指定 quality 则按带宽最大  
  - `BilibiliStorage.selectVideo(...)`：`core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
- Audio：直接选 `dash.audio.maxByOrNull { bandwidth }`

#### 2) 回退

- playurl 主请求失败时，`BilibiliRepository` 具备多层兜底：
  - WEB：风控重试（含 Remedy）+ 可选 html5 兜底（针对 MP4/风控场景）  
  - TV：可走 `playurlOld` 以及 TV client sign  
  - `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
- 若 DASH 不可用，则尝试 `durl.first.url`（MP4/FLV 类）  
  - `BilibiliStorage.createPlayUrl(...)` 内部逻辑

#### 3) 仍存在的结构性短板

- **MPD 只有单个 Representation**：一旦该 CDN/该编码不可用，播放器侧几乎没有可选余地，只能“退出/重试/换内核”。  
  - `BilibiliMpdGenerator` 当前只写 `media.baseUrl`，忽略 `backupUrl`：`bilibili_component/.../BilibiliMpdGenerator.kt`
- **缺少明确的 CDN 策略**：没有类似 PiliPlus 的 host 重写/备用 URL 选择规则。
- **缺少“播放中切换清晰度/编码/音质”的统一机制**：目前偏“进入前选一次”，播放中恢复能力弱。
- **缺少播放进度上报闭环**：当前媒体库来源是 `history/cursor`，但播放器播放并不会反向上报到 B 站（至少在本仓库未看到相关接口封装），导致“播放→历史”一致性无法保证。

---

## 2. PiliPlus 播放逻辑关键点（稳定性来源）

> PiliPlus 为 Flutter 项目，但其播放链路设计可抽象复用。

### 2.1 playurl 获取策略：一次拉全（为本地决策留空间）

- playurl 请求固定带：`fnval=4048`、`fourk=1`、`fnver=0`，并且支持 `try_look`（免登录试看）  
  - `/home/tzw/workspace/PiliPlus/lib/http/video.dart` 的 `VideoHttp.videoUrl(...)`

这意味着：服务端返回尽可能多的 DASH 格式/编码组合，**客户端再做选择**，而不是把决策完全压在请求参数上。

### 2.2 本地选流：画质/编码/音质均可控且支持播放中切换

- `queryVideoUrl` 会根据：
  - 可用最高画质
  - 用户画质偏好（WiFi/蜂窝区分）
  - 编码偏好（AV1/HEVC/AVC 等优先级）
  - 音质偏好  
  计算出 `videoUrl` 与 `audioUrl`  
  - `/home/tzw/workspace/PiliPlus/lib/pages/video/controller.dart` 的 `queryVideoUrl(...)`
- 切换清晰度/音质：重算 URL 并 `playerInit()`，从当前播放进度继续播放  
  - `/home/tzw/workspace/PiliPlus/lib/pages/video/controller.dart` 的 `updatePlayer()`

### 2.3 CDN 策略：对 base/backup 做重写与回退

- `VideoUtils.getCdnUrl(urls)` 会基于 URL 的 host/path 形态做：
  - host 替换到用户指定 CDN（阿里/腾讯/自建…）
  - 对音频可单独禁用 CDN
  - 对 `backupUrl` 进行回退选择  
  - `/home/tzw/workspace/PiliPlus/lib/utils/video_utils.dart`

这块是“稳定播放”的核心：即使某个 `upos-*` 节点异常，也能快速切换到可用节点。

### 2.4 播放器数据源模型：显式 video/audio 分离 + 统一 headers

- 数据结构：`DataSource(videoSource, audioSource, httpHeaders)`  
  - `/home/tzw/workspace/PiliPlus/lib/plugin/pl_player/models/data_source.dart`
- mpv 注入外置音轨：`pp.setProperty('audio-files', audioUri)`  
  - `/home/tzw/workspace/PiliPlus/lib/plugin/pl_player/controller.dart`

### 2.5 播放进度上报：心跳闭环（提升“历史/续播一致性”）

- 每 5 秒上报一次；状态变化（暂停/完成）也会上报  
  - `/home/tzw/workspace/PiliPlus/lib/plugin/pl_player/controller.dart` 的 `makeHeartBeat(...)`
  - `/home/tzw/workspace/PiliPlus/lib/http/video.dart` 的 `VideoHttp.heartBeat(...)`

---

## 3. 差异对照表（DDPlay vs PiliPlus）

| 维度 | DDPlay-bilibili 当前实现 | PiliPlus 实现 | 建议对齐方向 |
|---|---|---|---|
| playurl 拉取 | 偏“按偏好请求 + 返回后再选一个 video/audio” | 倾向“fnval=4048 拉全格式，再本地决策” | **拉全 + 本地选择**，为回退/切换留空间 |
| 清晰度/编码切换 | 偏“进入前选一次” | 播放中可切换（重建 dataSource） | 提供统一“**切换=重取流+续播**”机制或多 Representation MPD |
| CDN 选择/回退 | 目前无明确策略；MPD 仅 baseUrl | `base+backup` + host 重写/回退 | 引入 `BilibiliCdnStrategy`，利用 `backupUrl` |
| Manifest（DASH） | 本地 MPD，单 Representation | 直链 video/audio；mpv 注入音轨 | 将 MPD 升级为**多 Representation**或可重写回退 |
| 播放进度上报 | 未见 B 站心跳上报封装 | 每 5 秒心跳 + 状态上报 | 增加 B 站心跳（可开关），形成历史闭环 |
| Live | 取 `durl.first.url` 直连 | 可选清晰度 + CDN 拼接 | 解析直播多档清晰度并支持切换/回退 |

---

## 4. 对齐设计提案（面向 DDPlay 的“统一播放编排”）

### 4.1 设计目标（优先级）

P0（稳定性）：
- CDN 抖动/节点失效时 **自动回退**（无需用户切内核/反复重进）
- playurl 过期（常见 120min）时 **自动重取流并续播**

P1（可控性）：
- 播放中可切换：清晰度、编码偏好（AV1/HEVC/AVC）、音质

P2（体验闭环）：
- 可选的播放进度/完成上报，使服务端历史、跨端续播更可信

### 4.2 关键思想：把 B 站播放从“生成单一路径”升级为“可恢复的播放会话（Playback Session）”

建议引入一层 **Bilibili 播放会话编排**（放在 `bilibili_component`，避免污染 `core_storage_component`）：

- `BilibiliPlayurlFetcher`：负责按策略拉取 playurl（可配置：优先拉全 `fnval=4048`）
- `BilibiliStreamSelector`：在一个 playurl 响应内，按偏好选择 video/audio（并输出“备选列表”）
- `BilibiliCdnStrategy`：对 `baseUrl/backupUrl` 做 host 重写、优先级排序、黑名单（失败降级）
- `BilibiliManifestBuilder`：
  - 方案 A：生成 **多 Representation MPD**（让播放器侧具备选择空间）
  - 方案 B：生成“当前选择的 MPD”但保留“可重建能力”（失败时重建并续播）

`BilibiliStorage.createPlayUrl(...)` 仅负责：
- 解析 uniqueKey（UGC/PGC/Live）
- 调用上述编排得到“当前可播 Manifest”
- 交给播放器播放（本地 MPD/直链）

这样能保证：
- Storage 仍保持“媒体库抽象”，不把复杂播放策略硬塞进 Storage
- 播放策略可复用到：播放页、投屏、后台播放、未来的收藏/稍后再看等入口

### 4.3 MPD 生成策略升级（核心）

#### 方案 A：多 Representation MPD（推荐作为中长期目标）

目标：让 DASH 清单包含“多个 video rep + 多个 audio rep”，播放器可自动选择可解码/可达的流，并允许手动切换。

实现要点：
- `BilibiliMpdGenerator` 从“`writeDashMpd(dash, video, audio)`”升级为支持：
  - `video: List<BilibiliDashMediaData>`
  - `audio: List<BilibiliDashMediaData>`
  - 每个 media 的 `baseUrl + backupUrl` 参与构建（见下节 CDN 策略）
- 在 `BilibiliStorage.createPlayUrl(...)` 中：
  - 不再只写“选中的一个 video/audio”，而是把“可用候选集”写入 MPD

优点：
- 播放器侧有更强的自适应空间（尤其是 Media3）
- 未来做“清晰度切换”可以更自然（轨道/representation 选择）

风险/注意：
- 多内核一致性：Media3 对多 rep 支持最好；mpv/vlc 是否能像 Media3 一样暴露“可选画质”需要验证。若不可，则仍需方案 B 做兜底（重建续播）。

#### 方案 B：可重建 MPD + 无缝续播（短期更容易落地）

目标：保留“单 rep MPD”的播放形式，但在错误时具备“自动换 CDN / 换编码 / 换清晰度”的恢复能力。

实现要点：
- 维护一个 `BilibiliPlaybackSession`（内含：当前选中的 rep、备选 rep 列表、当前 CDN 策略状态、上次失败原因）
- 播放失败事件触发：
  1) 判断错误类型（超时/403/404/解码失败/风控）
  2) 选择下一条备选策略：换 `backupUrl` → 换 host → 降级编码/清晰度 → 重新 playurl
  3) 重写 MPD 并从 `currentPosition` 续播

优点：
- 不依赖播放器暴露多 rep 轨道能力，三内核一致性更强
- 可逐步演进到方案 A（把“备选列表”最终写入 MPD）

### 4.4 CDN 策略（对齐 PiliPlus 的关键）

建议新增 `BilibiliCdnStrategy`（参考 PiliPlus `VideoUtils.getCdnUrl` 的思想）：

- 输入：`baseUrl` + `backupUrl[]`（来自 `dash.media` 或 `durl`）
- 输出：按优先级排序的一组 URL（或 host 重写后的 URL）
- 支持：
  - 用户级/媒体库级的“偏好 CDN host”（可选）
  - 音频与视频可使用不同策略（可选）
  - 失败 URL 进入短期黑名单（例如 10 分钟）

在 MPD 生成上可选两条路线：
- 多 `<BaseURL>`（理论上 DASH 支持，但客户端是否会切换需验证）
- 将同一份资源的不同 CDN 作为“不同 Representation”（需要避免 UI 上出现大量重复选项，可在 label 上隐藏/折叠）

### 4.5 清晰度/编码/音质切换（与本项目 UI/抽象对齐）

当前项目的轨道选择 UI（`SettingTracksView`）仅覆盖：音轨/字幕/弹幕，不含“视频轨道”。因此建议先选一个与现有架构兼容的统一方案：

**建议优先实现：切换=重取流 + 重建清单 + 续播（跨内核一致）**

- Player 侧提供一个“B 站清晰度/编码”设置入口：
  - 读取 `BilibiliPlaybackPreferences`（媒体库级默认）
  - 读取 playurl 返回的可用清晰度/编码列表（会话级）
  - 用户选择后触发 `BilibiliPlaybackSession` 重建
- 对 Media3：可以进一步提供“轨道级切换”（中长期）

### 4.6 播放进度/心跳上报（可选，但很值得）

对齐 PiliPlus 的“历史闭环”：

- 新增 `BilibiliService` 对应心跳接口（UGC/PGC 可能参数不同，需要进一步确认与抓包验证）
- 新增 `BilibiliPlaybackHeartbeat`：
  - 触发点：`PlayRecorder.recordProgress(...)` 或播放器位置回调
  - 节流：每 5 秒一次；暂停/完成额外上报
  - 开关：用户可关闭（隐私/账号安全考虑）
  - 错误处理：失败不影响本地播放，仅记录/降级

### 4.7 统一的错误分类与自动恢复策略

建议把“播放失败恢复”明确成可测试的决策树：

1) **URL 过期/403/410**：重取 playurl（保持偏好）→ 重建 MPD → 续播  
2) **CDN 超时/连接失败**：切换 `backupUrl`/host → 重建 MPD → 续播  
3) **解码失败/不支持编码**：切换 codec（AV1→HEVC→AVC）或降级清晰度 → 重建 MPD → 续播  
4) **风控（v_voucher / -352 等）**：触发风控验证 UI（本项目已有相关页面/逻辑）  

配合现有：
- `BilibiliRepository` 的风控重试与 `BilibiliPlaybackErrorReporter` 的上报/脱敏

---

## 5. 分阶段落地路线（建议）

### Phase 1：CDN 策略 + backupUrl 利用（P0）

- 增加 `BilibiliCdnStrategy`（仅用于排序/host 重写）
- `BilibiliMpdGenerator` 至少能够在生成 MPD 前选择“更可靠的 URL”（例如：优先可用 host）
- 播放失败时，能够在 `backupUrl` 间轮询重试（方案 B 的最小版本）

> 落地状态（DDPlay-bilibili）：已实现（2026-01）
> - CDN 策略：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/cdn/BilibiliCdnStrategy.kt`
> - 媒体库偏好：新增 `CDN节点` 选择（`BilibiliPlaybackPreferences.cdnService`），入口在 `storage_component` 的 Bilibili 媒体库编辑弹窗
> - MPD BaseURL：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/mpd/BilibiliMpdGenerator.kt`（写入 `BaseURL + backupUrl`，并输出 `dvb:priority/weight + serviceLocation`，以支持 Media3 的 failover）
> - 非 DASH durl：`core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`（选择 `url + backup_url` 的优先候选）

### Phase 2：会话化重建与无缝续播（P0/P1）

- 引入 `BilibiliPlaybackSession`：保存候选流、当前选择、失败黑名单、上次播放位置
- 统一“切换清晰度/编码/音质”的动作入口（同一套重建逻辑）

### Phase 3：多 Representation MPD（P1）

- 升级 `BilibiliMpdGenerator` 写入多 video/audio rep
- Media3 上提供更自然的“轨道/清晰度切换”

> 落地状态（DDPlay-bilibili）：已实现（2026-01）
> - 多 Representation MPD：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/mpd/BilibiliMpdGenerator.kt`（支持写入多 video/audio rep，并保证 Representation id 唯一）
> - 会话写清单：`bilibili_component/src/main/java/com/xyoye/common_component/bilibili/playback/BilibiliPlaybackSession.kt`（按“选中清晰度/音质”为上限写入可降级的多 rep，避免默认选到超出偏好的更高档）
> - Media3 视频轨切换：`player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt` + `data_component/src/main/java/com/xyoye/data_component/enums/TrackType.kt`（新增 `TrackType.VIDEO` 并支持选择）
> - 播放器入口：`player_component/src/main/java/com/xyoye/player/info/SettingAction.kt` + `player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt`（新增「视频轨」设置项，进入后可切换画质/轨道）

### Phase 4：心跳上报闭环（P2）

- 增加 B 站心跳接口封装与节流上报
- 与本地 `PlayHistory` 同步策略协调（避免互相覆盖）

---

## 6. 测试与验证建议

- 单元测试：
  - CDN 重写规则（输入一组 `base+backup`，输出排序是否符合预期）
  - MPD 生成内容（包含 SegmentBase、BaseURL、Representation 数量与字段）
  - 选流策略（quality/codec/audio 选择）
- 真机/模拟器验证：
  - Media3 播放本地 MPD：清晰度/编码组合覆盖（AVC/HEVC/AV1）
  - 断网/弱网/特定 CDN 阻断场景：是否能自动切换并续播
  - 播放 2 小时后 URL 过期：是否能自动重取流恢复

---

## 7. 关键代码索引（便于落地时快速定位）

DDPlay-bilibili：

- `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/BilibiliPlayurlPreferencesMapper.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/mpd/BilibiliMpdGenerator.kt`
- `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/net/BilibiliHeaders.kt`

PiliPlus（参考实现）：

- `/home/tzw/workspace/PiliPlus/lib/http/video.dart`
- `/home/tzw/workspace/PiliPlus/lib/pages/video/controller.dart`
- `/home/tzw/workspace/PiliPlus/lib/utils/video_utils.dart`
- `/home/tzw/workspace/PiliPlus/lib/plugin/pl_player/controller.dart`
