# Implementation Plan: 接入 Bilibili 历史记录媒体库（含播放偏好）

**Branch**: `003-add-bilibili-history` | **Date**: 2025-12-28 | **Spec**: `/home/tzw/workspace/DanDanPlayForAndroid/specs/003-add-bilibili-history/spec.md`
**Input**: Feature specification from `/home/tzw/workspace/DanDanPlayForAndroid/specs/003-add-bilibili-history/spec.md`

## Summary

- 在应用“媒体库”中以独立媒体库类型接入 Bilibili：支持账号连接（二维码）、浏览“历史记录”列表、点击条目续播/播放、刷新/加载更多、断开并清除隐私数据。
- 在“媒体库编辑/管理页（StoragePlus 编辑弹窗）”新增 Bilibili 播放偏好配置（取流模式/画质优先/编码/是否允许 4K），持久化到 MMKV，并由后续取流与 mpd 生成逻辑读取生效。
- 弹幕：与其他媒体库“通过弹弹play API 匹配弹幕”不同，Bilibili 的每个 `cid` 都有唯一弹幕池；因此 Bilibili 媒体库播放时默认直接调用 Bilibili 弹幕接口下载并加载弹幕（无需匹配）。

## Technical Context

**Language/Version**: Kotlin 1.9.25（JVM target 1.8）  
**Primary Dependencies**: AndroidX（Lifecycle/ViewModel/Room 等）、Kotlin Coroutines、Retrofit/OkHttp、Moshi、Media3、ARouter、MMKV  
**Storage**: Room（SQLite）+ MMKV（Key-Value）+ 本地缓存文件（临时 mpd/二维码图片等）  
**Testing**: JUnit（JVM 单测 `testDebugUnitTest`）+ AndroidX Test（仪器测试 `connectedDebugAndroidTest`）  
**Target Platform**: Android（手机 + Android TV/遥控器）  
**Project Type**: mobile（多模块 MVVM）  
**Performance Goals**: 历史记录首屏 3s 内可见（见 SC-001）；点击条目后尽快进入播放状态（以可恢复为目标）  
**Constraints**: 隐私（断开必须清除）、三方 API 不稳定需降级提示、TV 可达性/可操作性、避免日志泄露敏感信息  
**Scale/Scope**: 单设备单账号为主；本阶段仅“普通视频”历史条目

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- `/home/tzw/workspace/DanDanPlayForAndroid/.specify/memory/constitution.md` 当前为占位模板，未定义可执行的硬性 Gate。
- 本特性以仓库既有工程规范与模块化约束为准（见 `/home/tzw/workspace/DanDanPlayForAndroid/AGENTS.md` + `spec.md` 的约束与验收场景），无额外复杂度豁免需求。

## Project Structure

### Documentation (this feature)

```text
/home/tzw/workspace/DanDanPlayForAndroid/specs/003-add-bilibili-history/
├── spec.md              # 特性需求与验收
├── plan.md              # 本文件（规划）
├── research.md          # Phase 0：调研与技术决策（含 playurl 参数/偏好设置入口）
├── data-model.md        # Phase 1：数据模型（含播放偏好）
├── quickstart.md        # Phase 1：快速验证路径
├── contracts/           # Phase 1：API contracts（OpenAPI 等）
└── checklists/          # Specify 内置清单
```

### Source Code (repository root)

```text
/home/tzw/workspace/DanDanPlayForAndroid/
├── app/
├── common_component/    # 基础能力与通用逻辑（如 MMKV 存储、Bilibili 偏好模型）
├── data_component/      # entities/enums/resources
├── local_component/     # 媒体库入口与管理页
├── storage_component/   # StoragePlus 媒体库编辑/管理弹窗
├── stream_component/    # 流媒体/网络媒体库能力（路由入口在此）
├── player_component/    # 播放能力（Media3）
├── user_component/      # 账号/登录相关
└── buildSrc/
```

**Structure Decision**: 维持现有“多模块 MVVM + StoragePlus 统一管理媒体库”的架构：Bilibili 作为一种新的 `MediaType` 进入 StoragePlus 管理体系；播放偏好作为“媒体库级别配置”落在 `common_component`（模型+MMKV Store），UI 放在 `storage_component`。

## 关键命名与唯一键规范（强约束）

> 本节用于把“命名/唯一键/路径结构”在实现前固定下来，避免后续在 Storage、播放器、历史记录与清理逻辑之间出现语义不一致。

### 1) MediaType

- 枚举：`MediaType.BILIBILI_STORAGE`
- `value`：`bilibili_storage`
- UI 展示名：`Bilibili媒体库`

### 2) 媒体库隔离 Key（storageKey）

用于隔离 Cookie、登录态、播放偏好等本地数据，建议统一为：

```text
storageKey = "${mediaType.value}:${url.trim().removeSuffix("/")}"
```

说明：

- `url` 对 Bilibili 媒体库默认取 `https://api.bilibili.com/`（实现中可自动补齐）。
- 该 Key 不依赖自增 `libraryId`，可避免“新建媒体库未落库前 id 不可用”的问题。

### 3) StorageFile.uniqueKey()

用于播放器侧“仅凭 uniqueKey 即可解析 cid”，并作为播放历史的稳定 key：

- 多 P/单 P 可播放条目：`bilibili://archive/{bvid}?cid={cid}`
- 多 P 的视频目录：`bilibili://archive/{bvid}`

约束：

- `bvid` 必须为非空字符串
- `cid` 必须为 `> 0` 的 Long

### 4) StorageFile.filePath()/storagePath()（目录结构）

用于列表路由、面包屑、lastPlay 父目录判断等（建议与 `storagePath()` 保持一致）：

```text
/
  history/                  # 历史记录目录
  history/{bvid}/           # 多 P 视频目录（videos > 1）
  history/{bvid}/{cid}      # 分 P / 单 P 的可播放文件项
```

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |

## Danmaku Design（Bilibili 媒体库弹幕）

> 背景：当前项目大多数媒体库的“自动匹配弹幕”依赖弹弹play API（基于文件 hash/标题匹配）。但对 Bilibili 来说，视频分 P 的 `cid` 与弹幕池一一对应，直接调用 B 站弹幕接口能获得“官方弹幕”，无需走匹配链路，且体验更确定。

### 目标与范围

- 目标：当播放来源为 `MediaType.BILIBILI_STORAGE` 的条目时，若未绑定本地弹幕文件，则自动拉取并加载该 `cid` 的 Bilibili 弹幕。
- 范围：仅做“弹幕下载 + 本地缓存 + 自动加载”，不做弹幕发送/点赞/屏蔽同步等高级操作（这些属于另一个独立需求面）。

### 关键标识（从播放源定位到 `cid`）

- BilibiliStorage 的可播放文件项 `uniqueKey()` 必须包含 `cid`（例如：`bilibili://archive/{bvid}?cid={cid}`），以便在播放器侧仅凭 `BaseVideoSource.getUniqueKey()` 即可解析出 `cid` 并拉取弹幕。
- `episodeId`（`LocalDanmuBean.episodeId`）不复用弹弹play 的 episodeId：Bilibili 弹幕仅作为“本地弹幕文件”使用，`episodeId` 建议置空，避免未来启用“发送弹幕到弹弹play”时误用。

### 弹幕获取 API（优先 XML，避免引入 Protobuf 解析）

- 首选（XML，兼容现有 `BiliDanmakuParser`）：`GET https://comment.bilibili.com/{cid}.xml`
  - 现有“手动下载 B 站弹幕”实现已使用该接口并能成功保存为本地弹幕文件（参考：`local_component/.../BilibiliDanmuViewModel.kt`）。
- 备选（同内容，XML）：`GET https://api.bilibili.com/x/v1/dm/list.so?oid={cid}`
- 未来可选（Protobuf 分段，能力更完整但需要解析与合并）：`/x/v2/dm/web/seg.so`、`/x/v2/dm/list/seg.so`（本阶段不做）。

请求建议携带的 Header：

- `User-Agent`：桌面浏览器 UA（与本阶段 Web API 一致）
- `Referer`：`https://www.bilibili.com/`
- `Cookie`：可选；若用户已登录可一并带上（部分内容可能因权限/区域受限需要登录态）

### 本地缓存策略

- 缓存位置：复用现有弹幕目录（`PathHelper.getDanmuDirectory()`）。
- 文件命名：使用稳定且不依赖标题的命名（例如 `bilibili_{cid}.xml`），避免标题包含非法字符导致落盘失败/重复。
- 命中策略：
  - 若本地已存在对应 `cid` 的弹幕文件，则直接加载；
  - 若不存在则下载并保存；保存成功后写入播放历史 `PlayHistoryEntity.danmuPath`（由现有轨道绑定流程自动落库）。
- 清理策略：当用户对该 Bilibili 媒体库执行“断开连接并清除数据”时，除了清理播放历史外，还应删除与该 `storageId` 相关的弹幕文件（可按历史表中记录的 `danmuPath` 批量删除，避免误删其他来源弹幕）。

### 与播放器的集成点（对齐现有架构，不破坏通用逻辑）

- 现状：`PlayerActivity.afterInitPlayer()` 在未绑定弹幕时会走 `danmuViewModel.matchDanmu(source)`，从弹弹play API 进行匹配下载。
- 设计：在 `PlayerDanmuViewModel.matchDanmu(videoSource)` 内按 `videoSource.getMediaType()` 分流：
  - `MediaType.BILIBILI_STORAGE`：解析 `cid` → 下载/缓存 Bilibili 弹幕 → `loadDanmuLiveData.postValue(videoUrl to LocalDanmuBean(...))`
  - 其他类型：保持现有 `DanmuSourceFactory + DanmuFinder.downloadMatched()`（弹弹play 匹配）不变

这样做的好处：

- 播放器侧仍然以“加载一个本地弹幕文件轨道（LocalDanmuBean）”为统一入口；
- Bilibili 与其他媒体库的差异被收敛在“弹幕获取策略”层面，不影响 `DanmuView`、弹幕样式/屏蔽等通用能力；
- 对 TV/遥控器也天然适配（无需额外交互，加载失败时仍走统一提示）。
