## Why

当前项目的 mpv 内核在 `vo=mediacodec_embed`（Android embed）模式下不会渲染 mpv 自带的 OSD/字幕，导致“内封字幕不可见”。项目已经具备 GPU libass + overlay 的字幕渲染管线（Media3 已接入、外挂 ASS/SSA 也可直接走 GPU 渲染），但 mpv 内核缺少把“mpv 解出的字幕状态”喂给该管线的桥接。

## What Changes

- 在 **不修改 mpv/libmpv.so** 的前提下，扩展 `mpv_bridge.cpp` 监听 mpv property（以 `sub-text/ass-full` 为主，辅以 `sub-ass-extradata`/`sid` 等），把当前字幕的 ASS `Dialogue:` 行转换为 libass streaming 需要的 chunk，并通过 `EmbeddedSubtitleSink` 喂给现有 GPU libass 管线。
- 将 mpv 内核的 GPU 字幕管线启用条件收敛为：仅当 `vo=mediacodec_embed` 时返回可用，避免 `vo=gpu/gpu-next` 与 mpv 自带字幕/OSD 产生双重渲染。
- 在 seek/切换字幕轨道/关闭字幕/偏移量变化等“时间轴跳变”场景下，对 embedded track 做 flush/reset，避免重复事件与残留显示。
- 变更范围集中在 `player_component`（JNI + mpv kernel 对接）；不引入新的模块依赖边。

## Capabilities

### New Capabilities

- （无）

### Modified Capabilities

- `subtitle-libass-gpu-pipeline`: 增加“mpv(vo=mediacodec_embed) 作为 embedded subtitle source”的行为要求与场景。

## Impact

- 影响模块：`player_component`
  - `player_component/src/main/cpp/mpv_bridge.cpp`：新增 mpv property 监听与事件派发
  - `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt`：新增事件类型与映射
  - `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`：接入 `SubtitleKernelBridge` 的 embedded sink，并做 seek/切轨/偏移的 reset
  - （可能新增）mpv ASS 文本解析/去重的纯 Kotlin 工具类与单测
- 风险与代价：
  - mpv property 变化频率可能较高，需要避免主线程重解析与频繁分配
  - `sub-ass-extradata` 在“非原生 ASS 轨道”场景可能不可用，需定义退化行为
  - 与外挂字幕加载/轨道 UI 的交互需要明确边界，避免同一字幕被同时渲染两次

