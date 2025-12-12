# Phase 1 数据模型：mpv 播放引擎集成

本特性不新增持久化表/数据库，仅对现有配置与会话状态做最小扩展。

## 实体与字段

### 1. 播放引擎类型（`PlayerType`）

位置：`data_component/src/main/java/com/xyoye/data_component/enums/PlayerType.kt`

- **新增枚举值**：`TYPE_MPV_PLAYER(4)`  
  - 取值需与现有 `TYPE_EXO_PLAYER(2)`、`TYPE_VLC_PLAYER(3)` 不冲突，保持向后兼容。
- **回读规则**：`valueOf()` 支持 4 → `TYPE_MPV_PLAYER`，未知值继续回落到 Exo(Media3)。

### 2. 播放引擎配置（MMKV `PlayerConfig`）

位置：  
- `common_component/src/main/java/com/xyoye/common_component/config/PlayerConfigTable.kt`  
- `player_component/src/main/java/com/xyoye/player/info/PlayerInitializer.kt`

字段（现有 + 扩展）：
- `usePlayerType: Int`：允许持久化 mpv 值；默认值维持现状（VLC），用户可在设置中切换。
- 其他现有字段（倍速、循环、硬解、音频输出、背景播放等）**继续复用**，在 mpv 内核中做等价映射。
- 预留（Phase 2+）：mpv 专有配置（如 `hwdec`、`vo`、日志等级、字幕样式映射等），不在 Phase 1 落地。

### 3. 播放会话（运行态）

位置：`player_component`（现有运行态对象，如 `DanDanVideoPlayer`/控制器）。

会话状态（现有，mpv 需对齐）：
- 当前视频资源：`path`、`headers`、清晰度/分段信息
- 播放进度：`currentPosition`、`duration`
- 控制状态：`isPlaying`、`speed`、`volume`、`isLooping`
- 覆盖层状态：`danmakuEnabled`（应用侧）、`subtitleEnabled/selectedTrack/subDelay`（mpv 内核侧）
- 视图状态：`videoSize`、`rotation`、`screenScale`

## 关系

- `PlayerConfig.usePlayerType` → 决定 `PlayerFactory` 选择哪个 `AbstractVideoPlayer` 实现，以及 `SurfaceFactory` 返回的渲染视图。
- `PlaybackSession` 持有当前内核实例与渲染视图，向上通过统一接口驱动 UI/播放列表/历史记录。

## 校验与状态转换

- **内核可用性**：若 `TYPE_MPV_PLAYER` 被选中但 libmpv 加载失败，需触发错误提示并允许回退（FR-007）。
- **字幕避免重复**：当 mpv 内核生效时，应用侧字幕渲染链路不得启动或应被禁用（FR-005）。
- **向后兼容**：未知/旧版本存储的 `usePlayerType` 必须安全回落到 `TYPE_EXO_PLAYER`（FR-008）。

