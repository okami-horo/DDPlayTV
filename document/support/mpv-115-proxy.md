# MPV 播放 115（Alist 挂载）触发风控：本地 HTTP 代理方案记录

## 背景
在 `mpv/libmpv` 播放 Alist 存储库挂载的 115 网盘视频时，播放器在真正开始播放前会进行高频、随机的 `Range` 读取（尤其是 mkv 容器为了读取 cues/索引会跳到文件尾部或大偏移）。该行为容易触发 115 风控，上游直接返回 `403 Forbidden`，导致 `mpv` 打开/探测阶段失败，表现为“无法播放/秒退/无音视频数据播放”。

为兼容此类上游，项目引入了本地轻量 HTTP 代理：`common_component/src/main/java/com/xyoye/common_component/storage/file/helper/HttpPlayServer.kt`，由 `AlistStorage` 在 MPV 播放时生成本地播放 URL：`common_component/src/main/java/com/xyoye/common_component/storage/impl/AlistStorage.kt`。

## 问题现象
常见日志关键词（仅示例，实际以 `.tmp/debug.log` 为准）：
- `ffmpeg[warn] http: HTTP error 403 Forbidden`
- `ffmpeg[error] Seek failed ...`
- `cplayer[error] no audio or video data played`
- `demux[v] Stream is not seekable` / `Cannot seek in this stream`
- `ffmpeg[error] Cannot seek backward in linear streams!`（会导致拖动进度很慢）

## 目标
1. **首要目标：保证能播**（避免探测阶段直接触发 403 导致播放失败）。
2. **次要目标：尽可能支持 seek**（拖动进度条可跳转，且尽量快）。
3. **可调参数：在“风控风险”和“seek 速度”之间可配置权衡**（仅 MPV 显示）。

## 解决方案概述
整体方案分为两部分：

### A. 本地 HTTP 代理（HttpPlayServer）对 Range 行为“降风险改写”
代理的核心思想：让 `mpv` 看到一个“更可控”的 HTTP 源，把对上游的高风险访问变成低风险访问。

1) **串行化 Range 转发**
- Range 请求在转发上游前会进入 `synchronized(upstreamRangeLock)`，避免并发 Range 轰炸上游。

2) **播放前的 Range 频率限速（可配置）**
- 在 `seekEnabled == false`（通常为首帧渲染前）时，代理在每次 Range 转发前做最小间隔控制，用于降低短时间内连续随机读触发风控的概率。
- 该间隔由设置项提供（见“配置项”）。

3) **Range 幅度裁剪（固定窗口）**
- 风控并不只和“频率”有关：我们已经验证过“即使把间隔设置到 2000ms，第一次大 Range 也可能直接 403”。
- 因此代理会把客户端请求的 Range 改写为“从请求起点开始、最多只读取固定窗口大小”的 Range 再转发上游：
  - 播放前窗口更小（用于探测阶段，降低风险）
  - 播放后窗口更大（提升 seek 性能）

4) **探测阶段 Range 被上游 403 时的降级**
- 播放前若上游对 Range 直接返回 `403`，代理会对 `mpv` 返回 `416 Range Not Satisfiable`，迫使 `mpv/ffmpeg` 回退到线性读取路径继续打开/播放，避免“直接失败”。
- 注意：该降级会带来一个副作用：`mpv` 可能把该源判定为“线性流/不可 seek”，导致后续 seek 变慢，因此需要播放器侧配合（见下文）。

### B. 播放器侧（libmpv）配合：确保“用户 seek”不会被 mpv 直接拒绝
由于某些场景下（尤其发生过探测阶段降级后）`mpv` 会认为输入不可 seek，从而出现 `Cannot seek in this stream`，项目在 MPV 侧做了两点处理：

1) **对本地代理 URL 强制开启 mpv 的 seekable**
- 在 `prepareAsync()` 设置数据源前，若数据源是 `http://127.0.0.1:<port>/...`，设置 `force-seekable=yes`，避免 `mpv` 直接拒绝用户 seek。
- 代码位置：
  - `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt`
  - `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`

2) **seekEnable 与“延迟 seek”**
- 代理在创建播放 URL 时会重置 `seekEnabled=false`；播放器在渲染开始后再切换为 `true`，并对用户在此之前触发的 seek 做一次延迟执行，减少“开播阶段大量随机 Range”带来的风险。

## 配置项
### MPV Range 限速间隔（ms）
- 入口：`设置 → 播放器设置 → 视频`
- 仅当播放器类型为 `mpv Player` 时显示
- Key：`mpv_proxy_range_interval_ms`
- 范围：0–2000ms（默认 200ms）
- 语义：**播放前**（`seekEnabled=false`）Range 转发到上游的最小间隔；数值越大越保守（更不易触发风控），但可能降低探测/seek 速度。

实现位置：
- 配置字段：`common_component/src/main/java/com/xyoye/common_component/config/PlayerConfigTable.kt`
- 设置 UI：`user_component/src/main/res/xml/preference_player_setting.xml`
- 仅 MPV 显示与读写：`user_component/src/main/java/com/xyoye/user_component/ui/fragment/PlayerSettingFragment.kt`
- 代理读取：`common_component/src/main/java/com/xyoye/common_component/storage/file/helper/HttpPlayServer.kt`

## 已知限制与权衡
1) **“能播”与“快 seek”不可同时极致**
- 为避免 403，我们在探测阶段会裁剪 Range、限频甚至降级（403→416）。一旦 mpv 走了线性路径，seek 可能会退化（例如出现 `Cannot seek backward in linear streams!`，导致拖动很慢）。

2) **115 风控策略会变化**
- 115 对随机 Range、频率、Header、Referer/Cookie 等的判定可能随时间变化；本方案以“尽量减少高风险请求”为核心，但无法保证完全不触发。

3) **窗口裁剪并非万能**
- Range 裁剪窗口能降低风险，但若上游对“任何非线性 Range”都敏感，仍可能触发 403；此时只能进一步降级为线性播放或引入更复杂的缓存/预取策略。

## 排查建议
1) 先看是否是 403 导致的“打开失败”
- 关键词：`HTTP error 403` + `no audio or video data played`

2) 如果“能播但 seek 很慢”
- 关键词：`Cannot seek backward in linear streams!`
- 说明：mpv 仍在以线性流方式处理输入，seek 需要顺序跳读/重建索引，耗时明显。
- 处理方向：降低探测阶段被迫降级的概率（例如更合理的 Range 窗口/限速策略），或在可接受风险范围内提高 Range 能力。

3) 调整配置项进行 A/B 验证
- `MPV Range 限速间隔（ms）`：从 200ms 开始逐步上调；若上调后“直接不能播”，说明是“首个大 Range 触发 403”而不是频率问题，需要依赖 Range 裁剪/降级逻辑。

