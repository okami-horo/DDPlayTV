# Media3 Migration Release Notes

## Overview
> 说明：云端 Remote Config 已下线，Media3 开关仅由打包时的本地配置控制，下文涉及远程灰度的旧描述已调整。

媒体播放、投屏、下载与后台挂件现全部运行在 AndroidX Media3 (1.8.0)，旧的 Exo 工厂已移除，入口统一走 Media3 能力层，由打包的 `media3_enabled` 本地开关及本地快照记录控制。此版本聚焦稳定性、编解码回退透明度与观测性，为提升开关到 100% 做准备。

## Highlights
- **Unified Player Engine** – `PlayerType.TYPE_EXO_PLAYER` now instantiates the new `Media3VideoPlayer`, which in turn uses Media3 renderers, track selectors, and 本地开关驱动的 telemetry（不再依赖远程配置）。
- **Codec Fallback Messaging** – Casting, downloads, and local resumes now consume `CodecFallbackHandler` output so UI surfaces clearly indicate when audio-only fallback is required.
- **Telemetry & Crash Tagging** – All Media3 sessions emit `STARTUP`, `FIRST_FRAME`, `ERROR`, and `CAST_TRANSFER` events plus Bugly/Firebase crash tags for cohort-level rollbacks.
- **CI Entrypoints** – `scripts/ci/verify-media3.sh` runs `lint`, `testDebugUnitTest`, and `connectedDebugAndroidTest` in a single command; `scripts/testing/media3-regression-report.sh` verifies ≥90% of `@Media3Dependent` cases executed on the Media3 delegate.

## Codec Fallback Expectations
- If a device lacks the requested hardware codec (e.g., AV1 + DRM), Media3 automatically triggers audio-only playback. The UI surfaces the fallback reason and links to the support runbook.
- Cast payloads include the fallback decision so Chromecast/remote targets match the phone experience.
- Downloads run `Media3DownloadValidationTest` logic before enabling the Play button; incompatible assets prompt re-download or audio-only resume.

## Rollout Guidance
1. 默认使用打包属性 `media3_enabled` 控制启停（无远程灰度百分比），需要分阶段发布请通过渠道/版本节奏控制。
2. Run `scripts/ci/verify-media3.sh` followed by `scripts/testing/media3-regression-report.sh` before each cohort ramp to capture SC-001–SC-003 evidence in the requirements checklist.
3. If crash or first-frame KPIs breach thresholds (see `document/monitoring/media3-stability.md`), 改为通过发布管道/热修订版关闭 `media3_enabled`（无远程切换能力）。

## Known Issues / Mitigations
- **High CPU on low-end devices** – Media3 FFmpeg renderers may spike CPU when mixing ASS subtitles + PiP. Mitigation: enforce 30 fps cap + audio-only fallback via 本地开关/发布渠道控制（不再使用 Remote Config）。
- **RTMP Streams** – Media3 RTMP extension replaces the legacy Exo RTMP module. Streams remain in beta; support team should follow `document/support/media3-playback-support.md` when triaging.
- **Instrumentation Constraints** – `connectedDebugAndroidTest` still requires an emulator with Google APIs + at least 2 GB RAM; the CI script exits with guidance if no device is detected.
