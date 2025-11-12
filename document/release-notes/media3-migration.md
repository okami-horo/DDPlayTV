# Media3 Migration Release Notes

## Overview
Media playback, casting, downloads, and background widgets now run exclusively on the AndroidX Media3 stack (1.8.0). The legacy Exo factories have been removed, and all entry points route through the Media3 capability layer guarded by Remote Config + rollout snapshots. This release focuses on stability, codec fallback transparency, and operational confidence before scaling the feature flag to 100%.

## Highlights
- **Unified Player Engine** – `PlayerType.TYPE_EXO_PLAYER` now instantiates the new `Media3VideoPlayer`, which in turn uses Media3 renderers, track selectors, and Remote Config–driven telemetry.
- **Codec Fallback Messaging** – Casting, downloads, and local resumes now consume `CodecFallbackHandler` output so UI surfaces clearly indicate when audio-only fallback is required.
- **Telemetry & Crash Tagging** – All Media3 sessions emit `STARTUP`, `FIRST_FRAME`, `ERROR`, and `CAST_TRANSFER` events plus Bugly/Firebase crash tags for cohort-level rollbacks.
- **CI Entrypoints** – `scripts/ci/verify-media3.sh` runs `lint`, `testDebugUnitTest`, and `connectedDebugAndroidTest` in a single command; `scripts/testing/media3-regression-report.sh` verifies ≥90% of `@Media3Dependent` cases executed on the Media3 delegate.

## Codec Fallback Expectations
- If a device lacks the requested hardware codec (e.g., AV1 + DRM), Media3 automatically triggers audio-only playback. The UI surfaces the fallback reason and links to the support runbook.
- Cast payloads include the fallback decision so Chromecast/remote targets match the phone experience.
- Downloads run `Media3DownloadValidationTest` logic before enabling the Play button; incompatible assets prompt re-download or audio-only resume.

## Rollout Guidance
1. Keep `media3_enabled=false` by default in Remote Config, then stage cohorts at 10% / 25% / 50% / 100% once dashboards stay green for ≥24h.
2. Run `scripts/ci/verify-media3.sh` followed by `scripts/testing/media3-regression-report.sh` before each cohort ramp to capture SC-001–SC-003 evidence in the requirements checklist.
3. If crash or first-frame KPIs breach thresholds (see `document/monitoring/media3-stability.md`), flip `media3_enabled=false` to halt new sessions while in-flight sessions finish.

## Known Issues / Mitigations
- **High CPU on low-end devices** – Media3 FFmpeg renderers may spike CPU when mixing ASS subtitles + PiP. Mitigation: enforce 30 fps cap + audio-only fallback via Remote Config for the lowest tier.
- **RTMP Streams** – Media3 RTMP extension replaces the legacy Exo RTMP module. Streams remain in beta; support team should follow `document/support/media3-playback-support.md` when triaging.
- **Instrumentation Constraints** – `connectedDebugAndroidTest` still requires an emulator with Google APIs + at least 2 GB RAM; the CI script exits with guidance if no device is detected.
