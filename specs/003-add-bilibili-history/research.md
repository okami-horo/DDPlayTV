# Phase 0：调研结论（Bilibili 历史记录媒体库接入）

本文用于在进入设计与实现前，明确“接入 Bilibili 历史记录”的关键技术选型与风险边界，确保后续方案能与项目既有 `Storage/StorageFile`、播放器与媒体库管理 UI 保持一致。

参考资料（本仓库内）：

- `/home/tzw/workspace/DanDanPlayForAndroid/TODOs/bilibili.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/login/login_action/QR.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/misc/sign/wbi.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/danmaku/danmaku_xml.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/historytoview/history.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/video/videostream_url.md`
- `/home/tzw/workspace/DanDanPlayForAndroid/.tmp/bilibili-API-collect/docs/video/info.md`（分 P 列表）

---

## 1. 账号连接（二维码扫码登录）

- Decision: 使用 Web 端二维码登录接口获取 Cookie 登录态：  
  `GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate` +  
  `GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=...`  
  登录成功后使用 OkHttp `CookieJar` 接收 `Set-Cookie`，并持久化关键 Cookie（至少 `SESSDATA`/`bili_jct` 等）与 `refresh_token`。
- Rationale:  
  1) 与本项目“网络媒体库”形态契合（纯 HTTP，无需 WebView）；  
  2) 避免 TV 端 `access_key` + `appkey/sign` 体系的额外复杂度；  
  3) `poll` 返回 `refresh_token`，为后续 Cookie 刷新与长期稳定性预留通道（见 `cookie_refresh.md`）。
- Alternatives considered:  
  - TV 端扫码登录（返回 `access_key`）：鉴权体系不同、落地成本更高；  
  - WebView 登录/手工导入 Cookie：不符合本阶段约束（Spec 已排除）。

---

## 2. WBI 签名（访问 playurl 等接口所需）

- Decision: 按 WBI 文档实现 `wts` + `w_rid` 签名；`img_key/sub_key` 通过 `GET https://api.bilibili.com/x/web-interface/nav` 的 `wbi_img` 获取并缓存（按天刷新/失败刷新）。  
- Rationale: `x/player/wbi/playurl` 等接口需要 WBI 签名；且 `img_key/sub_key` 观测为每日更替，需要缓存策略避免频繁请求与签名失效。
- Alternatives considered:  
  - 依赖网页 JS 里硬编码 key：不稳定、不可控；  
  - 放弃 playurl：无法完成播放闭环。

---

## 3. 历史记录列表（数据源与分页）

- Decision: 使用新接口 `GET https://api.bilibili.com/x/web-interface/history/cursor` 作为历史记录主数据源；分页使用 cursor（`max`/`view_at`/`business`）推进；并通过 `type=archive` 将列表限定为“普通视频（稿件）”。  
  每页 `ps` 取 30（接口最大值），以减少翻页次数。
- Rationale:  
  1) cursor 分页天然适配“加载更多”；  
  2) `type=archive` 可满足 Spec 的 FR-010（仅展示普通视频类型）；  
  3) 返回字段包含 `view_at/progress/duration`，可用于“时间倒序展示 + 续播进度”。
- Alternatives considered:  
  - 旧接口 `GET https://api.bilibili.com/x/v2/history`（pn/ps）：缺少更强的筛选与 cursor 语义；  
  - 仅拉取少量数据不做分页：不满足 P2 用户故事。

---

## 4. 多 P（分 P 列表与目录映射）

- Decision: 当历史条目 `videos > 1` 时，将该条目映射为“目录（视频）”，展开后通过 `GET https://api.bilibili.com/x/player/pagelist?bvid=...` 获取分 P 列表，并将每个 P（`cid`）映射为“文件项”。  
  当 `videos == 1` 时直接映射为“文件项”。
- Rationale:  
  1) 与 Spec 的“稿件/多 P”一致；  
  2) 与项目 Storage 的“目录/文件”交互一致，可在同一套浏览页完成“展开/返回/播放”；  
  3) 为后续扩展（收藏夹/稍后再看）提供统一映射规则。
- Alternatives considered:  
  - 始终把历史条目当成“单文件（最后观看的 cid）”：实现更简单，但对多 P 不完整，且不利于统一后续入口的结构。

---

## 5. 播放链路（取流策略：DASH 优先）

- Decision: 播放链接使用 `GET https://api.bilibili.com/x/player/wbi/playurl`，优先请求 DASH（`fnval=16`）并在本地生成 `.mpd` 清单文件供播放器打开；必要时以 MP4（`fnval=1`）作为降级。  
  播放请求统一通过 `Storage.getNetworkHeaders()` 注入 `Cookie/Referer/User-Agent` 等头部。
- Rationale:  
  1) B 站常见为 DASH（音视频分离），仅使用单一 `base_url` 容易“有画无声”；生成 `.mpd` 可一次性描述音视频轨并与 Media3 直接兼容；  
  2) 项目已有“给播放器注入 Header”的能力（Media3/VLC/mpv），可沿用而尽量不改播放器内核；  
  3) MP4 作为兼容兜底（部分场景 DASH 兼容性/设备能力不可控）。
- Alternatives considered:  
  - MP4-only：实现简单，但清晰度受限且新视频常无法获取高画质 MP4；  
  - 引入本地代理（类似 `HttpPlayServer` 扩展到 DASH）：工作量明显更大，放到后续阶段评估。

---

## 5.1 播放偏好（画质/格式/编码）的配置入口与默认值

- Decision: 在“媒体库编辑/管理页（StoragePlus 对应的 Bilibili 编辑弹窗）”提供播放偏好配置项，并持久化到 MMKV（按媒体库唯一键隔离），由后续取流与 mpd 生成逻辑读取并生效。  
  建议默认值：
  - 取流模式：`AUTO（DASH 优先，失败回退 MP4）`
  - 画质优先：`720P（qn=64）`
  - 视频编码优先：`AVC/H.264（codecid=7）`
  - 允许 4K：关闭（通常需要 `fourk=1` 且 `fnval` 包含 4K 位标识，并可能要求大会员；对带宽/稳定性要求更高）
- Rationale:
  1) B 站 Web API 的 `qn/fnval/fourk/codecid` 等参数会直接影响“可播放性/清晰度/兼容性”；暴露为偏好设置能让用户在不同设备/网络下自助调整；  
  2) 按媒体库隔离存储，避免未来扩展多账号/多实例时互相污染；  
  3) 默认选择更偏“兼容/稳定”的组合，避免因大会员/设备解码能力导致的失败率升高。
- Alternatives considered:
  - 全局设置：实现简单但不利于未来多账号/多实例；  
  - 不提供设置：遇到兼容性问题只能硬编码或频繁迭代，不利于稳定。

---

## 5.2 弹幕（按 cid 直拉 Bilibili 官方弹幕）

> 对比：当前项目“其他媒体库视频弹幕”主要依赖弹弹play API 进行匹配与下载；但 Bilibili 的每个分 P 都有自己的 `cid`，并对应唯一弹幕池，因此 Bilibili 媒体库应直接使用 B 站弹幕 API 获取弹幕。

- Decision: 在 `MediaType.BILIBILI_STORAGE` 的播放场景中，默认使用 Bilibili 弹幕接口按 `cid` 拉取弹幕并缓存为本地 XML 文件，再作为弹幕轨道加载；不走弹弹play 匹配链路。
  - 首选：`GET https://comment.bilibili.com/{cid}.xml`
  - 备选：`GET https://api.bilibili.com/x/v1/dm/list.so?oid={cid}`
- Rationale:
  1) “cid ↔ 弹幕池”是确定映射，避免了 hash/标题匹配的误匹配与失败率；  
  2) 项目现有弹幕渲染链路（`BiliDanmakuParser`）天然支持 B 站 XML；  
  3) 与 Storage 抽象兼容：最终仍落为 `LocalDanmuBean(danmuPath)`，播放器无需感知来源差异。
- Alternatives considered:
  - 继续走弹弹play 匹配：对 B 站内容并非必要，且匹配错误会降低体验；  
  - 使用 Protobuf 分段弹幕（`seg.so`）：能力更完整，但需要 Protobuf 解析与分段合并，MVP 成本更高；  
  - 只提供“手动下载弹幕”入口：不满足“媒体库播放时自动加载官方弹幕”的直觉体验。

---

## 6. 续播进度（远端进度与本地进度的优先级）

- Decision: 列表展示与首次播放时，使用 Bilibili 返回的 `progress/duration/view_at` 作为“远端播放历史”；若本地已存在同一 `uniqueKey + storageId` 的播放记录（应用内播放产生），则优先使用本地记录（更精确）。  
  数据换算：Bilibili `progress/duration` 为秒，本地 `PlayHistoryEntity.videoPosition/videoDuration` 为毫秒。
- Rationale:  
  1) 满足 Spec 的“默认从上次观看进度继续播放”；  
  2) 一旦用户在本应用播放过，本地进度通常比远端更及时（远端同步可能延迟）。
- Alternatives considered:  
  - 永远使用远端进度：会覆盖用户在本应用内的最新进度；  
  - 永远使用本地进度：首次接入时无法满足“续看”预期。

---

## 7. 排序与展示（时间倒序的实现方式）

- Decision: 在“历史记录”目录内强制按 `view_at` 倒序展示，忽略全局的 `StorageSortOption`（名称/大小）设置；其他目录保持现有排序逻辑。  
- Rationale: 项目现有媒体库排序仅支持“名称/大小”，无法表达“观看时间”；历史记录属于语义化列表，必须满足 FR-003 的“按时间倒序”。
- Alternatives considered:  
  - 扩展全局排序类型（增加 TIME）：更通用，但牵涉 UI/配置迁移，属于额外工作量；  
  - 复用 `fileLength()` 作为排序权重：可行但语义不清晰，且可能影响其他依赖 `fileLength` 的逻辑。

---

## 8. 刷新与加载更多（交互与状态）

- Decision: 为“历史记录”引入分页加载状态（加载中/失败/无更多），并提供两种触发方式：  
  1) 触屏：下拉刷新；滚动到底部自动加载更多；  
  2) TV：提供可聚焦的“加载更多/重试”列表项与显式“刷新”入口（菜单或按钮）。
- Rationale: Spec 同时要求手机与 TV 可用性（FR-006/FR-009）；TV 遥控器不适合下拉刷新，需要明确可聚焦入口。
- Alternatives considered:  
  - 仅做滚动到底自动加载更多：TV 不易发现且失败不可控；  
  - 仅做按钮加载更多：触屏体验不佳。

---

## 9. 断开连接与清除（隐私与播放中断）

- Decision: 在 Bilibili 媒体库管理 UI 中提供“断开连接并清除数据”，执行：  
  - 清除本地持久化 Cookie 与 `refresh_token`；  
  - 清除历史列表缓存（若做了缓存）；  
  - 删除本地 `PlayHistoryEntity` 中属于该 `storageId` 且为 Bilibili 的播放历史/进度；  
  - 若当前正在播放 Bilibili 内容：立即停止播放并退出播放页。
- Rationale: 满足 FR-007/FR-008/FR-011，避免共享设备隐私泄露与“清除后仍请求第三方内容”的异常体验。
- Alternatives considered:  
  - 仅清 Cookie 不清本地播放历史：不满足隐私要求；  
  - 延迟到播放结束再清理：不满足“立即停止并退出”。
