# Quickstart — Libass GPU Subtitle Pipeline

> `/subtitle/pipeline/*` contracts are local façade calls inside the app (no external server); wire them via the pipeline controller/repository interfaces.

1) Checkout branch: `git checkout 001-libass-gpu-render`.
2) Build/debug: `./gradlew assembleDebug` (watch tail for BUILD SUCCESSFUL).
3) Run unit tests: `./gradlew testDebugUnitTest`; optional device playback: `./gradlew connectedDebugAndroidTest`.
4) Prepare assets: load complex ASS samples (1080p/4K) into test media; ensure fonts available as in current subtitle pipeline.
5) Enable GPU pipeline: configure player to initialize via `/subtitle/pipeline/init` contract with current SurfaceView/TextureView dimensions; keep telemetryEnabled=true.
6) Validate playback: play sample video, toggle controls/seek/rotation; verify GPU mode stays Active and telemetry shows ≥95% vsync hit with reduced CPU.
7) Fallback test: force surface recreate or set `/subtitle/pipeline/fallback` to Fallback_CPU; confirm playback continues and stale subtitles are cleared.
8) Observe telemetry overlay: turn on `SubtitleTelemetryOverlay` in debug builds to view vsync hit rate, dropped frames, and CPU peak without spamming adb logcat.
9) Long-run soak: play ≥20 min with complex subtitles; check `SubtitleTelemetryRepository.latestSnapshot()` or log tag `SUB-GPU` for load-shedding and drop bursts.
10) Recovery path: after forcing fallback, trigger surface recreation and verify `SubtitleRecoveryCoordinator` rebinds GPU mode within ~1s.
