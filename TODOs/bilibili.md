# 将 Bilibili 作为网络媒体库接入的可行性评估（面向 DanDanPlayForAndroid）

本文基于当前仓库实现（`Storage/StorageFile` 抽象、播放器内核、已接入的 Alist/FTP/WebDav/SMB 等）与 `.tmp/bilibili-API-collect` 项目整理的 API 文档，对“把 Bilibili 作为一个网络媒体库接入（类似 Alist/FTP）”进行可行性与工作量评估，并给出推荐的最小可行方案（MVP）与演进路径。

---

## 1. 一句话结论

- **可以接入**：从架构上，当前项目的 `Storage` 抽象足以承载一个“虚拟媒体库”（并不要求真实文件系统），可以把“收藏夹/稍后再看/历史/搜索结果”等映射为目录结构，把“视频分 P（cid）”映射为文件项，实现浏览与播放闭环。
- **但复杂度明显高于 Alist/FTP**：Bilibili 的播放链路涉及 **Cookie 登录态**、**WBI 签名**、**播放链接时效（约 120min）**、以及 **DASH（音视频分离）**。要做到“稳定可用 + 清晰度可控 + 可恢复”，需要额外的鉴权与播放编排工作。
- **建议按阶段推进**：MVP 先做“收藏夹/稍后再看 + 播放（优先 DASH）+ 弹幕（cid）+ 二维码登录 + 基于 refresh_token 的 Cookie 刷新”；搜索后置。

---

## 2. 当前项目的媒体库接入点（需要对齐的抽象）

### 2.1 `Storage` / `StorageFile` 的语义

当前项目把各种“媒体来源”统一抽象为 `Storage`（媒体库）与 `StorageFile`（文件/目录项）：

- `Storage`（接口）：`getRootFile/openDirectory/listFiles/createPlayUrl/getNetworkHeaders/supportSearch/search/test/historyFile` 等
- `StorageFile`（接口）：`filePath/fileUrl/fileCover/fileName/fileLength/isDirectory/uniqueKey/storagePath/clone` 等

这些接口并不强制要求“真实文件系统”，只要能：
1) 构造可浏览的目录树（`openDirectory/listFiles/pathFile`）  
2) 为某个“文件项”生成可播放 URL（`createPlayUrl`）  
3) 必要时提供 HTTP Header（`getNetworkHeaders`）  

即可与上层浏览页与播放器打通。

### 2.2 播放侧对 Header 的支持（关键能力）

当前项目的播放链路支持将网络请求 Header 传入播放器（对 B 站极其重要）：

- Media3：会把 Header 设置为 `DefaultRequestProperties`（对清单与分片请求生效）
- VLC：支持 `:http-user-agent / :http-referrer / :http-cookie / :http-authorization`
- mpv：支持 `user-agent` 与 `http-header-fields`

因此，只要我们能拿到播放 URL，并能提供 `Referer/Cookie/User-Agent` 等必要头，三套内核都有可能完成播放。

### 2.3 项目里已存在的“类似形态”参考实现

以下实现可作为 BilibiliStorage 的对照：

- `AlistStorage`：带鉴权、会为 mpv 使用本地 `HttpPlayServer` 做代理（解决 Range/seek 等兼容问题）
- `RemoteStorage`：不是文件协议，而是“服务端返回结构化列表 + 搜索”，很接近“虚拟目录”的思路
- `LinkStorage`：演示了把 Header 作为播放层参数传递并记录到历史里

---

## 3. Bilibili 能提供哪些“媒体库能力”（基于 bilibili-API-collect）

下面仅列出与“媒体库浏览/播放”直接相关的能力（并标注主要风险点）。

### 3.1 目录/列表数据源（用于构建“虚拟目录树”）

可用的、天然适合做目录的入口：

- **收藏夹**
  - 获取用户创建的收藏夹列表：`/x/v3/fav/folder/created/list-all`
  - 获取收藏夹内容：`/x/v3/fav/resource/list`
  - 备注：私密/权限收藏夹需要对应账号登录

- **稍后再看**
  - 获取列表：`/x/v2/history/toview`
  - 备注：需要 Cookie（SESSDATA）

- **历史记录**
  - 获取历史列表：`/x/v2/history`
  - 备注：需要 Cookie；分页参数类似无限滚动

可选入口（看产品定位决定要不要做）：

- UP 主空间/投稿列表、视频合集（collection/ugc season）
- 追番/订阅列表（番剧/影视是另一套业务模型）
- 直播（播放链路与视频不同）

### 3.2 取流/播放能力（播放闭环核心）

- 取流接口（web）：`/x/player/wbi/playurl`
  - 返回 **DASH 或 MP4** 流地址
  - URL 有效期约 **120 分钟**，超时失效需重新取流
  - 高清/大会员清晰度需要登录与会员鉴权

### 3.3 鉴权/风控能力（决定“能不能用”和“能不能稳定用”）

- **WBI 签名**：大量 web 接口需要 `w_rid/wts`
  - `img_key/sub_key` 可从 `/x/web-interface/nav` 的 `wbi_img` 获取（即使未登录也存在）
  - mixin key 需要按固定表重排后取前 32 位，再对参数排序+URL 编码+MD5 得到签名
  - key 观测为**每日更替**，建议做缓存与刷新

- **搜索风控（-412）**
  - 综合搜索接口 `/x/web-interface/wbi/search/all/v2` 在 Cookies 不足时可能返回 `-412`（被拦截）
  - 文档建议：搜索前先 GET `https://bilibili.com` 以获取必要 cookie（如 `buvid3`）
  - 这意味着“站内搜索”是一个**高风控、高变动**功能，适合后置

---

## 4. 将 Bilibili 映射为 `Storage`：推荐的模型与行为

### 4.1 核心设计：把 B 站当成“内容平台”而非“文件系统”

与 FTP/WebDav 不同，Bilibili 没有“任意路径下列目录”的能力；因此必须采用“虚拟目录”映射：

建议的根目录结构示例（可按产品需要裁剪）：

```
/
  我的收藏夹/
    收藏夹 A/
      视频 1/（如多P，作为目录）
        P1（cid=...）
        P2（cid=...）
      视频 2（单P，直接作为文件）
    收藏夹 B/
  稍后再看/
    视频...
  历史记录/（可选）
    视频...
  搜索/（可选：作为“虚拟入口”，真正搜索走 supportSearch/search）
```

### 4.2 `StorageFile` 的关键字段如何定义

需要特别关注 `uniqueKey` 与 `filePath/storagePath`，因为它们与播放历史、列表 diff、上次播放定位相关：

- `uniqueKey()`：建议使用稳定、可重建的键，例如：
  - `bilibili://video/{bvid}?cid={cid}`（或 `aid+cid`）
  - 对番剧/影视可使用 `epid/cid` 组合（后续再扩展）
  - 番剧/影视（PGC）历史播放适配设计：`TODOs/bilibili_pgc_playback.md`

- `filePath()`：用于“路径栏/路由”，不必是真实路径，但应具备层级与可解析性，例如：
  - `/fav/{mlid}/`、`/toview/`、`/video/{bvid}/`、`/video/{bvid}/{cid}`

- `fileUrl()`：用于“默认播放地址/打开方式”
  - 对于 BilibiliStorage，通常不直接返回最终播放直链，而是返回一个可解析的标识（如 `bilibili://...`），最终播放 URL 由 `createPlayUrl()` 生成

- `fileCover()`：直接使用接口返回的 `cover/pic` URL 即可

- `fileLength()`：多数情况下无法稳定拿到文件大小，可返回 `0` 或 `-1`（看 UI 是否能接受）

### 4.3 播放 URL 生成策略（`createPlayUrl`）

这是接入成败关键，建议按以下优先级：

#### 策略 A：DASH（推荐，覆盖主流高画质）

1) 调用 `/x/player/wbi/playurl` 获取 DASH 清单数据（视频/音频轨 `base_url` 与备选地址）
2) **在本地生成一个 `.mpd` 文件**（存放在应用缓存目录），清单内引用上述音视频分片 URL
3) 将本地 `.mpd` 的路径作为 `playUrl` 返回

为什么生成 `.mpd`：
- DASH 常见返回是“音视频分离”的 `m4s`，仅给出分片 URL 列表；如果直接把 `video.base_url` 交给播放器，通常会**只有画面没有声音**（或反过来）。
- Media3 对 `.mpd` 支持成熟；mpv/VLC 也普遍支持 DASH（取决于编译能力与设备情况）。

注意事项：
- 确保 `getNetworkHeaders()` 返回的 Header 能被播放器用于“清单与分片”请求（Media3 可行；VLC/mpv 需要实际验证）。
- 处理 URL 时效：超过有效期需要重新取流与重写 `.mpd`（可以在播放失败时兜底重试；完整的“播放中续期”属于后续优化）。

#### 策略 B：MP4（作为降级/兼容手段）

当用户不需要高画质、或设备/内核对 DASH 兼容性不佳时，可请求 MP4：
- 通过 playurl 参数选择 MP4（部分新视频高画质可能拿不到 MP4，只能 DASH）

MP4 的优势：
- 不需要生成 `.mpd`
- 链路更接近普通直链播放

劣势：
- 可用清晰度受限
- 仍然受 Cookie/Referer/时效影响

#### 是否需要本地代理（类似 `HttpPlayServer`）？

`HttpPlayServer` 当前是“单上游 URL 的 Range/seek 兼容代理”，非常适合 MP4 或单文件场景；但 **DASH 是“清单 + 多分片 URL”**，需要多路代理与重写路由，现有实现无法直接复用。

因此：
- **MVP 阶段不建议引入复杂代理**：优先走“本地生成 mpd + 直接请求分片”
- 若后续遇到大量 Range/seek/302/403 兼容问题，再考虑扩展一个“DASH 代理服务器”（工作量显著）

### 4.4 搜索（`supportSearch/search`）建议后置

从 API 文档看，搜索存在明显风控：
- 返回 `-412` 的概率高
- 需要补齐 `buvid3` 等 cookie
- 需要 WBI 签名

建议：
- MVP 不做搜索，仅做“收藏夹/稍后再看”
- 如果要做搜索，先做“预热请求（访问 bilibili.com 获取 cookies）+ 持久 CookieJar + 风控失败提示”

---

## 5. 登录与 Cookie 管理（实现成本与安全点）

### 5.1 登录形态（MVP）：二维码扫码登录（推荐）

#### 5.1.1 Web 端二维码扫码登录（推荐）

`bilibili-API-collect` 已整理了 Web 端扫码登录接口（`.tmp/bilibili-API-collect/docs/login/login_action/QR.md`），**可在纯 HTTP 场景下获取 web 端 Cookie 登录态**，满足后续 `/x/player/wbi/playurl`、收藏夹、稍后再看等接口需求。

- 申请二维码：`GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate`
  - 返回 `data.url`（用于生成二维码）与 `data.qrcode_key`（32 字符，约 180s 过期）
- 轮询扫码状态：`GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=...`
  - `data.code`：`86101` 未扫码、`86090` 已扫码未确认、`86038` 已失效、`0` 成功
  - 成功时响应头 `Set-Cookie` 会写入：`SESSDATA`、`bili_jct`、`DedeUserID`、`DedeUserID__ckMd5`、`sid` 等
  - 同时返回 `data.refresh_token`（后续 cookie 刷新必需）

实现建议（Android）：
- UI：弹窗/页展示二维码 + 状态文案；二维码失效（`86038`）时自动重新申请
- 网络：使用 OkHttp `CookieJar` 自动接收 `Set-Cookie`，并把关键 cookie + `refresh_token` 持久化
- 刷新：将 `refresh_token` 视为登录态的一部分，按 5.3 的流程做“检查/刷新/失效兜底”
- 备注：TV 端扫码登录接口会返回 `access_key`，且依赖 `appkey/sign`，与本评估“走 web 接口 + cookie”的路径不同，不建议作为首选

#### 5.1.2 Cookie 管理建议（CookieJar + 导出 Cookie Header）

- 业务请求：所有 API 请求走同一个 OkHttpClient，依赖 CookieJar 自动收发 cookie
- 播放请求：播放器侧需要显式 Header；因此需要提供“从 CookieJar 组装 `Cookie` 字符串”的能力（至少 `SESSDATA`，通常还需要 `bili_jct/buvid3`）
- 预热：为降低 `-412` 风控概率，首次使用或每次启动可先 GET `https://www.bilibili.com/`，让服务器下发 `buvid3/buvid4/b_nut` 等基础 cookie（并随 CookieJar 持久化）

### 5.2 WBI key 缓存策略

`img_key/sub_key` 可从 `/x/web-interface/nav` 取到（未登录也可）：
- 建议缓存到本地（带时间戳）
- 发现签名失败或跨天时刷新

### 5.3 Cookie 刷新（refresh_token）与失效处理（稳定性必做）

`bilibili-API-collect` 提供了 Web 端 Cookie 刷新机制（`.tmp/bilibili-API-collect/docs/login/cookie_refresh.md`）。社区反馈 Web 端 Cookie 在访问部分敏感接口后可能被判定“需要刷新”，如果不实现刷新链路，最终通常只能靠“让用户重新扫码登录”来恢复，稳定性难以保证。

核心结论：**想要长期稳定，必须把 `refresh_token` 当作登录态的一部分持久化，并实现刷新流程**（二维码扫码登录 `poll` 会返回 `refresh_token`）。

#### 5.3.1 刷新触发点（建议）

- 每日首次访问前：先检查是否需要刷新
- 业务失败兜底：接口 `-101`、取流 403、播放失败等场景触发“检查/刷新/重登”

#### 5.3.2 刷新流程（接口与关键参数）

1) 检查是否需要刷新：`GET https://passport.bilibili.com/x/passport-login/web/cookie/info`（Cookie 鉴权，可带 `csrf=bili_jct`）
   - 读取 `data.refresh`（是否需要刷新）与 `data.timestamp`（毫秒时间戳）

2) 生成 `correspondPath`：对字符串 `refresh_{timestamp}` 做 `RSA-OAEP(SHA-256)` 加密，并将密文转为小写 hex（详见文档“生成CorrespondPath算法”）

3) 获取 `refresh_csrf`：`GET https://www.bilibili.com/correspond/1/{correspondPath}`（Cookie 鉴权）
   - 解析 HTML 中 `div#1-name` 的文本值作为 `refresh_csrf`

4) 刷新 Cookie：`POST https://passport.bilibili.com/x/passport-login/web/cookie/refresh`
   - 表单参数：`csrf=<旧 bili_jct>`、`refresh_csrf=<上一步>`、`source=main_web`、`refresh_token=<旧 refresh_token>`
   - 成功时响应头会 `Set-Cookie` 下发新的 `SESSDATA/bili_jct/...`；JSON `data.refresh_token` 返回新的 `refresh_token`（用于替换持久化值）

5) 确认更新：`POST https://passport.bilibili.com/x/passport-login/web/confirm/refresh`
   - 表单参数：`csrf=<新 bili_jct>`、`refresh_token=<旧 refresh_token>`（注意这里必须是“刷新前的旧值”，否则会导致旧 token 未被作废）

6)（可选）SSO 站点跨域登录：cookie_refresh.md 的伪代码提到该步骤；如后续发现部分站点域名 cookie 缺失，再按抓包补齐即可

#### 5.3.3 失败与兜底策略

- `cookie/info` / `cookie/refresh` 返回 `-101`：视为未登录，触发重新扫码登录
- `cookie/refresh` 返回 `86095`：通常是 `refresh_csrf` 或 `refresh_token` 与当前 Cookie 不匹配，触发“清理旧登录态 + 重新扫码登录”
- 刷新链路避免高频重试：失败后应退避并提示用户主动重登，避免触发风控

### 5.4 安全与合规

- Cookie / refresh_token 属于敏感凭证：建议避免进入日志、Crash 上报、分享导出等路径
- 分发/使用边界需要先明确：B 站接口与风控策略经常变化，且 ToS 风险需要评估

---

## 6. 需要修改的模块范围（粗粒度拆分）

以下是“做成一个可用的 Bilibili 媒体库”通常涉及的改动点（不等于都要在 MVP 做完）。

### 6.1 `data_component`

- 新增 `MediaType.BILIBILI_STORAGE`（用于区分媒体库类型与图标/名称）
- 可能需要为 `MediaLibraryEntity` 增加字段以保存：
  - cookie/SESSDATA（或结构化字段；建议交由 CookieJar 持久化）
  - `refresh_token`（二维码登录返回；Cookie 刷新必需）
  - 可选：上次刷新时间戳、用户 mid、默认清晰度偏好、是否启用 DASH 等
- 如果新增字段，需同步 Room 数据库迁移

### 6.2 `common_component`

- 新增网络层：
  - Bilibili API Service（Retrofit 接口声明）
  - Repository：封装 WBI 签名、参数、错误码处理、重试策略
- 新增 WBI 签名工具（含 URL 编码一致性要求）
- 新增 `BilibiliStorage` + `BilibiliStorageFile`：
  - `listFiles/openDirectory/pathFile/historyFile/createPlayUrl/getNetworkHeaders`
  - DASH：生成本地 `.mpd` 文件

### 6.3 `storage_component`

- 新增/复用媒体库编辑 UI（类似 FTP/WebDav/Alist 的编辑弹窗）：
  - 登录状态展示（未登录/已登录、可选昵称/uid）
  - 二维码登录入口（二维码展示 + 轮询状态 + 失效自动重试）
  - 退出登录（清理 CookieJar + refresh_token）
  - 连接测试（调用 nav/收藏夹列表等）
- 在媒体库列表中展示新类型入口与图标

### 6.4 `player_component`（尽量不动）

理想情况下无需改播放器：
- 播放 URL 是 `.mpd` 本地文件路径或普通 https URL
- Header 由现有机制注入

如果遇到“分片不带 Header”等兼容问题，再评估是否需要补强（例如对 VLC/mpv 的 Header 注入覆盖面做实测与调整）。

---

## 7. MVP 建议与工作量预估（以 1 名开发为参考）

### 7.1 MVP（推荐优先做）

目标：能“像一个媒体库一样浏览并播放 B 站内容”，优先保证可用与稳定。

- 浏览：
  - 我的收藏夹（列表 + 内容分页）
  - 稍后再看列表
- 播放：
  - playurl 取流
  - DASH：本地生成 `.mpd` 并播放（优先）
  - MP4：作为降级
- 弹幕（可选但性价比高）：
  - 利用 cid 拉取弹幕 xml 并缓存（项目已有相关实现基础）
- 登录：
  - 二维码扫码登录（web，推荐）+ CookieJar 持久化
  - 基于 `refresh_token` 的 Cookie 刷新（每日检查 + 失败兜底重登）
- 搜索：
  - MVP 不做

预估：约 2~3 周（取决于 DASH 适配、Cookie 刷新链路落地与 UI 交互完整度）。

### 7.2 第二阶段（增强可用性）

- 增加历史记录入口
- 增加搜索（并做好 -412 风控提示）
- cookie 预热（访问 bilibili.com 获取必要 cookie）+ 持久 CookieJar
- 播放失败自动重取流（处理 url 过期/403）

### 7.3 第三阶段（体验与长期稳定）

- Cookie 刷新策略完善（失败重试退避、后台定时、异常场景补齐）（需要额外的合规评估）
- 清晰度/编码偏好、音轨/画质选择 UI
- DASH 代理（仅在大量机型/内核兼容问题出现时再考虑）

---

## 8. 验收与测试建议

建议至少覆盖：

- **浏览正确性**：收藏夹分页、视频多 P 展开、空/权限不足提示
- **播放正确性**：
  - DASH：画面+声音同时存在
  - seek/暂停/继续
  - cookie 失效时的提示与恢复路径
- **Header 覆盖**：Media3/VLC/mpv 三内核分别验证 `Referer/Cookie/User-Agent` 是否对分片请求生效
- **时效场景**：播放前后超过 120 分钟的取流失效处理（至少能重新进入播放）
