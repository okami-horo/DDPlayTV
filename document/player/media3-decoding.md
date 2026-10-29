# Media3 解码器选择与运行日志

适用于 Media3 1.11.1；2026-10-29 按用户确认的方向收敛。mpv 的解码策略不在本次修改范围内；VLC 随后的全端移除与旧配置兼容见[播放内核说明](player-kernels.md)。

## 当前行为

- 视频、音频使用 `DefaultRenderersFactory` 的默认 MediaCodec 选择。`LibassAwareRenderersFactory` 只保留原始 ASS 字幕桥接，并开启 `setEnableDecoderFallback(true)`；已有扩展渲染器优先配置继续保留。
- 初始化首选解码器失败时，由 Media3 尝试其余可用候选。这个开关只处理初始化失败，不承诺运行中的解码错误恢复，也不保证最终存在可用的软件解码器。
- 视频轨道选择交给 `DefaultTrackSelector`。不再按厂商排名、固定视频 MIME 偏好或自定义 HDR 分数强制锁定轨道；原音频和字幕语言偏好保留。
- 移除应用自行维护的解码器黑名单、音视频重试配额、DV/HEVC 能力猜测和码流改写包装。容器解析交给官方 extractor，libass 所需的字幕转码开关保留。
- `onPlayerError` 将原始错误交给现有上层处理流程，不再将普通网络或媒体源错误推断为解码器失败并重启播放。既有用户重试、切换内核和会话恢复仍由上层负责。

这不会增加电视芯片、系统 MediaCodec 或显示链路的能力。只有能够在具体设备与样片上复现、默认策略确实无法解决的兼容问题，才值得添加范围明确的设备规避逻辑。

实现入口：[渲染器工厂](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/LibassAwareRenderersFactory.kt)、[播放器](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3VideoPlayer.kt)、[媒体源](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3MediaSourceHelper.kt)。

## 日志含义

`Media3Diagnostics` 在每次播放器构建时注册 `AnalyticsListener`，包括字幕后端变化导致的播放器重建。日志经现有 `LogFacade` 输出，标签为 `Media3Diag`；同时保留结构化遥测回调。

| 事件 | 依据与字段 |
| --- | --- |
| `decoder_initialized` | 实际音频/视频初始化成功回调；轨道类型、真实 decoder 名称、初始化耗时及时间戳。时间戳来自 Media3 的单调时钟，不是日历时间。 |
| `decoder_released` | 实际释放回调；轨道类型、decoder 名称。 |
| `decoder_format` | 实际输入格式变化；MIME、codec 字符串。文本日志还含视频尺寸、帧率、颜色传递函数，或音频采样率、声道数；未知值原样保留。 |
| `decoder_error` | Media3 codec 异常回调；轨道类型、异常类型和脱敏后的原因。它与整个播放失败事件不同，后续仍可能初始化成功。 |

候选列表不再标成“已选中”。日志也不再仅凭 decoder 名称宣称硬解、软解或 HDR 输出成功；这些结论需要设备能力与实际播放证据。初始化成功本身不能证明首帧已显示或播放全程正常。

实现入口：[Media3Diagnostics](../../player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3Diagnostics.kt)。HTTP 诊断及 URL 脱敏沿用原逻辑。

## 参考依据

- Media3 官方 [DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java) 与 [MediaCodecRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java)：默认选择与初始化候选回退由播放器维护。
- Jellyfin Android TV 的 [VideoManager](https://github.com/jellyfin/jellyfin-androidtv/blob/1267753bc6c5cf4e58a00c5b52e53c40b0ab8674/app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java#L219)：采用官方渲染器工厂、开启初始化回退，同时接入 libass。这里参考的是这条具体路径，不代表其整个项目没有设备适配。
- Jellyfin 的[运行时回退提案](https://github.com/jellyfin/jellyfin-androidtv/pull/5827)与 [Media3 上游讨论](https://github.com/androidx/media/issues/3421)表明，运行时恢复需要单独处理，不能把初始化回退开关解释为完整恢复机制。

## 本次验证（2026-10-29）

最终构建与检查命令：

```powershell
& '.\scripts\dev-env\gradlew-local.bat' `
  :player_component:assembleDebugAndroidTest `
  :app:assembleDebug :app:assembleDebugAndroidTest `
  testDebugUnitTest lint :player_component:ktlintCheck `
  verifyModuleDependencies verifyPlaybackBoundaries `
  -I .tmp/handoff14-local.gradle `
  -I .tmp/media3-upgrade/resolver-cache.gradle `
  -I .tmp/media3-decoder-style.gradle `
  --offline --max-workers=4 --continue --console=plain
```

结果：`BUILD SUCCESSFUL in 7m 18s`。按当前构建模块的 JUnit XML 汇总，385 项通过、2 项外部协议夹具测试跳过，0 失败。lint 为全量；ktlint 仅覆盖此次修改的 Kotlin 文件，按仓库规范关闭尾逗号规则。前两个本地 init script 用于离线测试依赖解析。

设备为只读冷启动的 `Pixel_5_API_34` / Android 14 / x86_64 模拟器，使用 SwiftShader；本轮未切换 TV UI mode。

| 验证 | 结果 |
| --- | --- |
| 新增 `Media3DecoderLifecycleTest` 两项 | 旧实现均失败，修改后均通过：真实音视频初始化、释放事件与诊断数据一致，字幕后端重建后继续记录；注入网络错误时原错误交给上层且不重启解码器。 |
| `Media3SubtitleCompatibilityTest` 三项 | 全部通过：内嵌 ASS 前后 seek、关闭再开启字幕，以及官方提取器生成字幕 cue。 |
| 应用级播放三项 | 全部通过：Media3 seek 立即持久化、请求重入和 Activity 重建保持资源所有权、内核重试保持来源/位置/全局设置。重入测试包含 DPAD_CENTER 恢复播放、可见焦点及 BACK 退出。 |
| 实际日志 | 观察到 `c2.goldfish.h264.decoder` 与 `c2.android.aac.decoder` 的初始化、输入格式和释放记录；本轮 crash buffer 为空。 |

新增样片 `player_component/src/androidTest/assets/media3/decoder-lifecycle.mkv` 为 5 秒 H.264 + ASS + AAC，用已有字幕样片添加静音音轨生成。应用级测试使用 `/sdcard/Download/ddplaytv-lease-playback-long.mp4`。两轮初次应用测试均因缺少读取权限失败（`EACCES`）；给临时模拟器中的 Debug 包授予 `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` 后三项通过，未修改业务代码来绕过失败。

本地原始证据：`.tmp/media3-decoder-validation.log`、`.tmp/media3-decoder-unit-counts.log`、`.tmp/media3-decoder-red-device.log`、`.tmp/media3-decoder-green-device.log`、`.tmp/media3-decoder-playback-device-final.log`、`.tmp/media3-decoder-diagnostics.log`。初次权限失败保留在 `.tmp/media3-decoder-playback-device.log`、`.tmp/media3-decoder-playback-device-granted.log` 和 `.tmp/media3-decoder-playback-permission.log`。

这些结果验证应用行为及日志来源，不是硬解性能对比。尚未验证物理 TV、HDR/Dolby Vision、DRM、厂商解码器故障或真实“首选初始化失败后备用成功”的设备场景；不据此宣称硬件兼容率提高。
