# Media3 迁移说明（以本仓库实现为准）

> 背景：本项目已将旧的 `com.google.android.exoplayer2`（ExoPlayer 2.x）迁移到 **AndroidX Media3**；但仍保留 VLC / MPV 等其他播放内核，未做“单内核统一”。

## 1. 范围与当前状态

- `PlayerType.TYPE_EXO_PLAYER`：已使用 `androidx.media3`（见 `player_component/.../Media3VideoPlayer.kt`）
- `PlayerType.TYPE_VLC_PLAYER` / `PlayerType.TYPE_MPV_PLAYER`：仍走各自内核
- “Media3 网关/远程能力”（会话创建、遥测上报、下载兼容性验证）：当前仓库在 `Media3Repository` 中默认禁用（避免公网环境触发 DNS/网络异常）

## 2. 开关与版本

- 开关：构建期 Gradle 属性 `media3_enabled=true|false` → `BuildConfig.MEDIA3_ENABLED_FALLBACK`（见 `core_system_component/.../Media3ToggleProvider.kt`）
- Media3 版本：Gradle 属性 `media3Version`（默认 `1.9.0`，见 `core_system_component/build.gradle.kts` 与 `player_component/build.gradle.kts`）

## 3. 观测与排查（本仓库能力）

- 崩溃标记：`Media3CrashTagger` 会把会话/开关信息写入 Bugly 的 user data（若构建时配置了 Bugly）
- 本地日志：建议开启「开发者设置 → 日志」并从 `Download/DDPlayTV/logs/` 拉取（见 `document/monitoring/logging-system.md`）

## 4. 常见误区

- 本仓库不存在远程 Remote Config 灰度；`media3_enabled` 仅由打包/渠道控制。
- `Media3TelemetryRepository` 仍会生成事件，但默认不会向远端上报（见 `core_network_component/.../Media3Repository.kt`）。
