# Bilibili 媒体库风控优化方案（对比 PiliPlus）

## 0. 背景与现状

当前项目在 B 站媒体库场景下（UGC/PGC 点播）风控命中率很高，用户侧表现为：

- 直播（Live）基本可播放
- 非直播（UGC/PGC）**大面积播放失败**（常见为 `-412/-351/-509`、或取流/分片 403 等）

本方案目标：在不破坏现有模块化架构的前提下，参考 `.tmp/PiliPlus` 的可用实践，系统化补齐“设备指纹/GAIA 链路/必要参数/请求头策略/失败兜底”，降低风控概率并提升可恢复性。

> 说明：B 站风控策略变化频繁。本方案强调“可观测 + 可配置 + 可降级”，避免把某一种绕过方式写死为唯一通路。

---

## 1. 当前项目实现梳理（与风控相关）

### 1.1 取流链路

- 入口：`common_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`
  - `createPlayUrl()` 调用 `BilibiliRepository.playurl()/pgcPlayurl()`
  - DASH 时写本地 MPD：`BilibiliMpdGenerator.writeDashMpd()`
  - 播放器侧请求头：`getNetworkHeaders()` -> `BilibiliHeaders.withCookie(...)`
- 仓库：`common_component/src/main/java/com/xyoye/common_component/bilibili/repository/BilibiliRepository.kt`
  - UGC：`/x/player/wbi/playurl`（Web）或 `/x/player/playurl`（TV/app sign）
  - PGC：`/pgc/player/web/playurl`（Web）或 `/pgc/player/api/playurl`（TV/app sign）
- Service：`common_component/src/main/java/com/xyoye/common_component/network/service/BilibiliService.kt`

### 1.2 已有的“风控降低/稳定性”措施

- WBI 签名：`common_component/src/main/java/com/xyoye/common_component/bilibili/wbi/BilibiliWbiSigner.kt`
- Cookie 持久化与回放：`common_component/src/main/java/com/xyoye/common_component/bilibili/auth/BilibiliCookieJarStore.kt`
- Web Cookie 刷新：`BilibiliRepository.refreshCookieIfNeeded()`（含 correspondPath/RSA-OAEP）
- `bili_ticket`（WebTicket）刷新：`BilibiliRepository.refreshBiliTicketIfNeeded()` + `BilibiliTicketSigner`
  - 条件触发：在 `requestBilibiliAuthed()` 内部每次业务请求前检查
- 预热（仅在缺少 `buvid3` 时）：`service.preheat(BASE_WWW)`（主要用于直播弹幕、bili_ticket 刷新前）
- 风控重试：仅对 PGC playurl 使用 `retryBilibiliRiskControl()`（UGC playurl 未使用）

### 1.3 当前实现的关键缺口（与“直播能播、点播难播”高度相关）

1) **playurl 参数缺失**

- 当前 UGC Web 取流参数主要是 `bvid/cid/fnver/fnval/platform/qn/fourk/codecid`（由 `BilibiliPlayurlPreferencesMapper` 生成）
- 但 `bilibili-API-collect` 的 Web playurl 文档明确出现 `gaia_source / isGaiaAvoided / try_look / session` 等字段：
  - `.tmp/bilibili-API-collect/docs/video/videostream_url.md`

2) **缺少 GAIA（设备指纹）激活链路**

- 当前项目主要靠 `bili_ticket + buvid3` 降低风控，但这在近期策略下可能不足。

3) **预热策略不完整**

- 当前只检查 `buvid3`；但实际风控经常依赖一组基础 cookie（常见如 `buvid3/buvid4/b_nut/...`），且可能需要“周期性”补齐。

4) **请求头策略没有按“API 类型/播放上下文”分层**

- `BilibiliOkHttpClientFactory` 全局补齐 `User-Agent/Referer/Accept-Encoding`，但：
  - TV/API 签名请求仍使用桌面浏览器 UA/Referer（不一定匹配官方端行为）
  - 播放器侧 `Referer` 固定为 `https://www.bilibili.com/`，可能不足以通过部分视频的 referer 校验（更稳妥是 `.../video/{bvid}` 或 `.../bangumi/play/ep{epId}`）

5) **UGC playurl 风控未做退避重试/补救动作**

- PGC 有 `retryBilibiliRiskControl()`，UGC 没有；遇到 `-412/-509` 只能失败返回。

---

## 2. PiliPlus 做法梳理（可复用的“抗风控”要点）

参考代码：

- WBI：`.tmp/PiliPlus/lib/utils/wbi_sign.dart`
- 取流参数：`.tmp/PiliPlus/lib/http/video.dart`（`VideoHttp.videoUrl()`）
- GAIA 激活：`.tmp/PiliPlus/lib/http/init.dart`（`Request.buvidActive()`），调用：
  - `POST /x/internal/gaia-gateway/ExClimbWuzhi`（payload 为一段 JSON 字符串）
- 激活触发：`.tmp/PiliPlus/lib/utils/accounts.dart`（对各账号类型启动时触发一次激活）

### 2.1 playurl 参数更贴近官方行为

PiliPlus 的取流请求中，除常规参数外，额外携带（示例，非完整）：

- `gaia_source: pre-load`
- `isGaiaAvoided: true`
- `web_location: 1315873`
- （可选）`try_look: 1`（未登录拉 720/1080）

这与 `bilibili-API-collect` playurl 文档中提到的 GAIA 字段相吻合。

### 2.2 主动执行 GAIA “buvid 激活”

PiliPlus 在账号初始化/切换时会调用 GAIA 网关激活一次（并用 `account.activated` 做幂等标记），属于“先做设备指纹链路，再做高风险接口”的思路。

---

## 3. 差异对比（结论表）

| 维度 | 当前项目 | PiliPlus | 影响 | 建议 |
|---|---|---|---|---|
| Web playurl 参数 | 偏“最小参数集合” | 包含 GAIA 相关字段 | 无登录/弱 cookie 场景更易触发风控 | 补齐 `gaia_source/isGaiaAvoided/web_location/try_look/session(可选)` |
| GAIA 激活 | 无 | `ExClimbWuzhi` 激活一次 | 设备指纹链路缺失可能导致 `-412` 概率飙升 | 增加“激活/修复风控”流程，并做幂等与失败降级 |
| 预热 | 仅缺 `buvid3` 时预热 `www` | 本地生成 buvid3 + 激活 | cookie 不足时仍会被拦截 | 预热条件扩展为“缺关键 cookie 组合” + 周期性刷新 |
| headers 策略 | Web/TV 共用桌面 UA/Referer | web/app 请求头分流，带 `env/app-key/x-bili-*` | 端侧特征不一致易触发风控 | 将 headers 策略按 `apiType` 分层，并为“播放器侧”按视频生成 referer |
| 风控重试 | PGC 有退避重试，UGC 无 | 有全局重试（网络）+ 更完善参数 | `-509/-412` 时可恢复性差 | UGC 也增加“退避 + 补救动作 + 降级” |

---

## 4. 优化方案（按落地优先级拆分）

### 4.1 P0：补齐 Web playurl 的 GAIA 相关参数（立竿见影）

基于 `.tmp/bilibili-API-collect/docs/video/videostream_url.md`：

- 对 UGC Web：`/x/player/wbi/playurl`
  - 默认追加：`gaia_source=pre-load`、`isGaiaAvoided=true`、`web_location`（选一个固定值即可，后续可调）
  - 未登录场景可选追加：`try_look=1`
  - 可选追加：`session`（按文档规则生成：`md5(buvid3 + 当前毫秒时间戳)`）
- 对 PGC Web：保持现状 + 评估是否也需要 `gaia_source/isGaiaAvoided`（看实际返回/失败率）

落地点建议：

- 不要把“风控参数”混到 `BilibiliPlaybackPreferences`（这是用户偏好）
- 新建一个“取流参数装配层”，将 `PlaybackPreferencesMapper` 的输出与“风控参数”合并。

### 4.2 P0：UGC playurl 增加风控退避重试 + 失败补救动作

建议将 UGC Web `playurl()` 与 `playurlFallbackOrNull()` 都改为：

1) 首次请求前：确保“预热/票据/激活”已完成（详见 4.3/4.4）
2) 命中 `-351/-412/-509`：
   - 触发一次“补救动作”（预热 + GAIA 激活 + bili_ticket 刷新）
   - 然后走 `retryBilibiliRiskControl()` 的指数退避重试
3) 仍失败：进入降级（见 4.6）

### 4.3 P1：引入 GAIA buvid 激活（对齐 PiliPlus）

新增一个“幂等激活”流程：

- API：`POST https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi`（PiliPlus 使用）
- 请求体：`{"payload":"<json string>"}`（PiliPlus 示例可复用）
- 触发时机：
  - 每个 `storageKey` 第一次进行“高风险请求”前（playurl / 搜索 / space wbi 等）
  - 或在连续命中风控后作为补救动作
- 结果缓存：
  - `MMKV` 持久化 `activated_at`（带 TTL，例如 24h/7d）
  - 并配合互斥锁避免并发触发

注意：

- 激活属于“软依赖”：失败不能阻塞核心功能，应记录并继续走其他降级路径。

### 4.4 P1：预热策略升级（从“buvid3”升级到“关键 cookie 组合”）

建议将当前零散的 `preheat(BASE_WWW)` 收敛为一个统一方法，例如：

- `ensureWebCookies()`：
  - 若缺少关键 cookie（至少 `buvid3`，建议扩展 `buvid4/b_nut` 等）则 `GET https://www.bilibili.com/`
  - 记录 `preheated_at`，避免每次请求都预热（例如 6h/12h TTL）

同时把预热纳入“风控补救动作”里（当命中 `-412` 时再触发一次）。

### 4.5 P1：请求头策略分层（Web vs TV vs 播放器侧）

**(1) OkHttp 业务请求头（Repository 层）**

- Web：维持桌面 UA 也可以，但建议补齐：
  - `Origin: https://www.bilibili.com`（部分接口更稳）
  - `Accept-Language`（非必需，但更像真实浏览器）
- TV/API：不要强制注入桌面 UA/Referer，建议为 TV client 单独一套 UA/headers（可借鉴 PiliPlus 的 BiliDroid UA 结构）

**(2) 播放器侧 Header（分片请求）**

建议把 `Referer` 从固定 `https://www.bilibili.com/` 升级为“按视频生成”：

- UGC：`https://www.bilibili.com/video/{bvid}`
- PGC：`https://www.bilibili.com/bangumi/play/ep{epId}`

落地方式：

- 方案 A（推荐）：让 `VideoSource.getHttpHeader()` 具备“按 file 生成 header”的能力
  - 例如在 `StorageVideoSource` 里识别 `BilibiliKeys.parse(file.uniqueKey())`，动态生成 referer
- 方案 B：扩展 `Storage` 接口新增 `getNetworkHeaders(file: StorageFile)`（改动更大，但更架构化）

### 4.6 P2：降级与兜底（让“能播”优先于“高画质”）

建议按顺序尝试：

1) DASH（现有主链路）
2) MP4 回退（现有 `playurlFallbackOrNull()`）
3) **HTML5 MP4 回退**（重点）
   - 对 Web playurl：`platform=html5` + `fnval=1`（文档说明“无 referer 鉴权，video 标签可播”）
   - 可结合 `high_quality=1` 提升清晰度
4) 自动降清晰度（降低 `qn`，减少会员/高码率触发）
5) 最终失败：给用户明确提示（“风控拦截/请求频繁/需登录/稍后重试”）并提供“一键修复风控”（触发 4.3/4.4）

### 4.7 P2：限流与缓存（降低“触发风控”的概率）

- 对高风险接口（playurl / 搜索 / space wbi / history）做协程层限流：
  - 每个 `storageKey` 最大并发数（例如 2）
  - 最小间隔（例如 200~500ms）
- playurl 结果短缓存（例如 30~60s），避免播放器重试时立刻再次命中风控

### 4.8 P2：可观测与排障（减少“看不见的问题”）

- 对所有 Bilibili 请求统一打印：
  - `path + code + message + apiType + 是否登录 + 是否有 buvid/bili_ticket`
  - Cookie 需脱敏（可复用 `BilibiliHeaders.redactHeaders()`）
- 重点：**播放失败时区分是“取流失败”还是“分片 403/412”**
  - 建议在播放器网络层抓取错误码，并仅输出关键信息（避免 adb log 淹没）

### 4.9 P3：`w_webid` 兼容（GAIA JWT，适用于部分 WBI 接口）

近期社区反馈部分 WBI 接口开始引入新的风控参数 `w_webid`（本质是 GAIA 签发的 JWT，通常带 `ttl=86400`），在 cookie/指纹不足时可能显著提升成功率。

建议将其作为“可选增强项”，优先用于 **搜索/空间/动态** 等非播放接口的 `-412` 修复：

- 获取方式（偏 Web 方案）：请求对应页面 HTML（如 `space.bilibili.com`），从页面的渲染数据中提取 `w_webid`
- 缓存策略：按天缓存（与 WBI key 类似），失败不阻塞主流程
- 接入方式：在需要 WBI 签名的接口参数中追加 `w_webid`

> 注：该能力属于“高变动项”，建议放在 `BilibiliRiskControlManager` 内做独立开关与灰度，不要散落在业务代码里。

---

## 5. 推荐的代码落地点（保持架构一致性）

建议新增包：`common_component/src/main/java/com/xyoye/common_component/bilibili/risk/`

核心职责（示意）：

- `BilibiliRiskControlManager`
  - `suspend fun prepare(reason: String, level: Level)`：预热、刷新 ticket、必要时 GAIA 激活
  - `suspend fun onRiskControlHit(reason: String)`：记录命中次数，触发补救
- `BilibiliRiskStateStore`（MMKV）：记录 `preheated_at/activated_at/last_risk_at`
- `BilibiliPlayurlParamsAssembler`
  - 合并 `PlaybackPreferencesMapper` + GAIA 参数 + session/try_look 等

然后在 `BilibiliRepository.requestBilibiliAuthed()` / `playurl()` / `pgcPlayurl()` 里统一接入（避免在各业务函数里散落“补救逻辑”）。

---

## 6. 验证清单（建议按用例跑一遍）

1) 未登录：UGC（普通视频）DASH -> MP4 -> HTML5 MP4 是否可播
2) 已登录：UGC 720/1080 是否更稳定，是否还容易 `-412`
3) PGC：ep 播放是否受影响（含重试链路）
4) 分片请求：是否出现大量 403（验证 Referer 动态化的收益）
5) 频繁切换视频：限流/缓存是否降低 `-509`
