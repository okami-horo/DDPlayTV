## ADDED Requirements

### Requirement: MPV `mediacodec_embed` 模式下可用 GPU libass 渲染内封字幕
系统 SHALL 在 mpv 内核选择 `vo=mediacodec_embed` 时启用 GPU libass + overlay 的字幕渲染路径，以保证 mpv 自身无法渲染 OSD/字幕的场景下，内封字幕仍可见。

#### Scenario: `mediacodec_embed` 下内封 ASS/SSA 字幕可见
- **WHEN** 使用 mpv 内核播放包含内封 ASS/SSA 字幕的媒体，且 `vo=mediacodec_embed`
- **THEN** 字幕通过 overlay Surface 正常渲染并随播放进度更新
- **AND** 不依赖 mpv 自带 OSD/字幕合成能力

#### Scenario: 非 `mediacodec_embed` 不自动启用以避免双重渲染
- **WHEN** 使用 mpv 内核播放媒体，且 `vo=gpu` 或 `vo=gpu-next`
- **THEN** 系统不自动启用 mpv→GPU libass 的 embedded 字幕桥接
- **AND** 避免与 mpv 自带字幕/OSD 产生叠加重复

### Requirement: MPV `sub-text/ass-full` 桥接为 libass streaming chunk 并具备去重与复位
系统 SHALL 监听 mpv property `sub-text/ass-full`，并将其返回的 ASS `Dialogue:` 行转换为 libass streaming API 需要的 chunk（timecode/duration + Matroska event fields），通过 `EmbeddedSubtitleSink` 投递到既有 GPU libass 管线。

系统 SHALL 对 `Dialogue:` 事件做去重，避免 property 重复上报时产生重复字幕叠加。

系统 SHALL 在 seek、切换字幕轨道（含 `sid=no`）、以及字幕偏移量改变等“时间轴跳变”场景下 flush/reset embedded 事件，避免旧事件残留或重复显示。

#### Scenario: property 重复上报不产生重复字幕
- **WHEN** mpv 在同一字幕展示区间内多次上报相同的 `sub-text/ass-full` 内容
- **THEN** GPU libass 管线只保留一份等价事件，不出现“重影/重复叠字”

#### Scenario: seek 后字幕不残留且不重复
- **WHEN** 播放过程中发生 seek（前进或后退）
- **THEN** 系统对 embedded 事件做 flush/reset
- **AND** seek 落点处的字幕能在合理时间内恢复显示
- **AND** 不出现 seek 前后事件叠加导致的重复字幕

#### Scenario: 关闭字幕立即清屏
- **WHEN** 用户将 mpv 字幕轨道切换为 `sid=no`（或等价的“关闭字幕”操作）
- **THEN** overlay 中的 embedded 字幕立即清空

### Requirement: 优先使用 `sub-ass-extradata` 初始化 embedded 样式，缺失时可退化渲染
系统 SHALL 在 mpv 提供 `sub-ass-extradata` 时，将其作为 embedded track 的 codec private 初始化输入，以尽量保持 ASS 样式/PlayRes 信息一致。

当 `sub-ass-extradata` 不可用时，系统 SHALL 允许以空 codec private 启动 embedded track，并以退化样式渲染，保证“有字幕可看”。

#### Scenario: extradata 可用时样式尽量一致
- **WHEN** mpv 选中的字幕轨道为原生 ASS/SSA，且可获取 `sub-ass-extradata`
- **THEN** GPU libass 使用该 extradata 初始化并渲染字幕样式（字体/位置/缩放等）尽量与预期一致

#### Scenario: extradata 不可用时仍可显示字幕
- **WHEN** mpv 无法提供 `sub-ass-extradata`（例如非原生 ASS 轨道或兼容性限制）
- **THEN** GPU libass 仍可渲染字幕文本（允许样式退化）

### Requirement: 字幕偏移“提前/延迟”语义一致且支持提前不缺字幕
系统 SHALL 保证字幕偏移的语义与播放器 UI 一致：offset > 0 表示“提前”，offset < 0 表示“延迟”。

在 mpv embedded 字幕桥接路径下，系统 SHALL 采取必要的策略确保 offset > 0 时不会因“未来事件尚未被投递”而导致字幕缺失（例如通过 mpv `sub-delay` 的反向映射实现事件前置/lookahead）。

#### Scenario: offset 为正时字幕可提前显示且不断档
- **WHEN** 用户将字幕偏移设置为正值（提前）
- **THEN** 字幕能按偏移提前显示
- **AND** 不因事件投递滞后出现明显缺行/断档

