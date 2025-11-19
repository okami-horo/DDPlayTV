# Media3 硬件解码策略改造说明（现状与优化建议）

## 已实现策略（本项目）

- 自定义解码器选择器（多别名尝试 + 回退 + 排序）
  - 文件：`player_component/src/main/java/com/xyoye/player/kernel/impl/media3/AggressiveMediaCodecSelector.kt`
  - 要点：
    - 多 MIME 别名重试：VC-1（`video/wvc1`/`video/VC1`/`video/vc1`）、Dolby Vision（`video/dvhe`/`video/dvh1`/`video/dav1`）与 AV1（`video/av01`/`video/AV1`）。
    - DRM 灵活降级：当 `requiresSecureDecoder=true` 且无可用解码器时，自动尝试非 secure 变体（需调用方确保内容安全策略适用）。
    - DV→HEVC 回退：若 DV 无解码器，自动尝试 HEVC（同 secure/非 secure 逻辑）。
    - 结果排序：硬件优先、`c2.*` 实现优先，其次名称字典序，尽量选择可靠编解码器。

- 自定义渲染器工厂（开启解码器回退）
  - 文件：`player_component/src/main/java/com/xyoye/player/kernel/impl/media3/AggressiveRenderersFactory.kt`
  - 要点：视频/音频渲染器均启用 `setEnableDecoderFallback(true)`，在首选解码器失败时自动尝试备选。

- 媒体项 MIME 规范化与 DV→HEVC 回退（构建时）
  - 文件：
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3FormatUtil.kt`
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3MediaSourceHelper.kt`
  - 要点：
    - 通过 URI/文件名推断 MIME（H.264/HEVC/AV1/VC‑1/DV）。
    - 结合显示 HDR 能力：若设备/显示不支持 DV 且有 HEVC 解码器，则降级为 HEVC。

- 轻量比特流修补（已接入 Progressive 管线）
  - 文件：
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3BitstreamRewriter.kt`
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/RewritingExtractorsFactory.kt`
    - `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3MediaSourceHelper.kt`
  - 要点：
    - ProgressiveMediaSource 使用包装 Extractor，拦截 TrackOutput.format，对 H.264 avcC→Annex‑B 起始码转换。
    - 仅在非规范 csd 场景触发，正常流保持原样。

- 自适应流层的 HDR/SDR 轨道选择
  - 文件：`Media3VideoPlayer.kt`
  - 要点：在 `onTracksChanged` 时遍历视频轨道，依据显示 HDR 能力按层级选择（DV > HDR10+/HDR10 > HLG > SDR）；若显示不支持 HDR，则强偏好 SDR；一次性应用 `TrackSelectionOverride`，避免频繁覆盖用户选择。

- HDR 感知的 MIME 规范化与播放偏好
  - 通过显示 HDR 能力 + 解码器能力决定 DV→HEVC 降级，并在 TrackSelector 中设置优先视频 MIME 顺序（DV/HEVC/AV1/H.264 按能力排序）。
  - 文件：`Media3FormatUtil.kt`、`Media3VideoPlayer.kt`

- 诊断日志
  - 记录解码器候选/最终选择、格式重写、HDR/SDR 选择结果与显示能力。
  - 文件：`Media3Diagnostics.kt`、选择器/重写器调用处。

- DRM 策略开关
  - 文件：`DrmPolicy.kt`
  - 要点：默认强制 DRM 会话仅使用 secure 解码器，除非业务显式调用 `setRequireSecure(false)` 放宽；当发生降级或被拦截时会记录诊断日志。

- 播放器接入点
  - 文件：`player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt`
  - 要点：
    - 使用 `AggressiveRenderersFactory` 注入选择策略，并保留 `EXTENSION_RENDERER_MODE_PREFER`。

 

## 与 Kodi 思路的对齐程度

- 一致之处：
  - 多 MIME 别名探测、硬件优先排序、DV→HEVC 回退、失败回退链条（与 Kodi 的“尽力而为”策略一致）。
- 尚未覆盖：
  - 更全面的比特流规范化（如 VC‑1 extradata 精简、HEVC hvcC 修补）。
  - 基于显示能力 × 解码器能力的 HDR10+/SDR 进一步回退矩阵。
  - 更精细的 DRM secure/非 secure 策略（结合会话标志与内容权限）。

## 可进一步实现的优化（推荐）

1) HDR 回退进一步细化
   - 现状：已完成自适应流层级的 HDR/SDR 自动选择并区分 DV/HDR10(+)/HLG/SDR 四档；可进一步结合分辨率/比特率/帧率做多维排序或提供用户偏好开关。
2) DRM secure 策略判定
   - 现状：内核层已默认禁止 DRM 场景的非 secure 回退，并输出诊断日志；后续可在业务层接入开关入口，结合内容策略提示用户或按账号/来源动态配置。

## 不建议在本项目实现的复杂策略（性价比低）

- 深度比特流重写与实时重打包
  - 如对 VC‑1/HEVC 的完整流级别转换、跨容器重封装、实时插入/剥离 DV RPU 等。
  - 成本高、维护复杂、风险大；建议尽量在源侧或使用 FFmpeg 工具链处理，而非在移动端实时完成。

- HDR 元数据重写与 Tone‑Mapping 渲染管线
  - 在应用层自行处理 PQ/HLG→SDR 的 tone‑mapping 与色彩空间转换需要专用渲染链与着色器，超出播放器内核改造的合理范围。

- 自研完整 Extractor/Demuxer
  - Media3 自带的 Extractor 体系已覆盖主流容器；完全自研维护成本过高，建议仅做轻量包装/修补。

## 下一步实施建议（优先级）

1) 细化自适应流层的 HDR 优先顺序（HDR10+ / HDR10 / HLG）与多条件排序策略。
2) 完善 DRM secure/非 secure 策略判定与日志（在内容允许前提下降级 non‑secure 并明确提示）。
3) 增强选择/回退的诊断日志（必要时遥测），统一记录解码器选择路径与回退原因。

完成以上工作后，Media3 在“同机硬解命中率与稳定性”将进一步逼近 Kodi 的体验，同时保持实现复杂度在可控范围内。
