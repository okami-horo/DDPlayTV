# Release Notes — 001-libass-gpu-render

## Feature Summary
- GPU-backed libass rendering path now streams telemetry and supports controlled fallback/recovery when surfaces or GPU contexts misbehave.
- On-device overlay exposes vsync hit rate, dropped frames, and CPU peaks without overwhelming adb logs.
- Load-shedding policy throttles uploads during overutilization to keep playback responsive.

## Key Changes
- Added telemetry collector with load-shedding guardrails and repository snapshot aggregation.
- Introduced fallback/recovery coordinators plus surface lifecycle handler to rebuild GL resources safely.
- New validation overlay for CPU/GPU/VSYNC metrics and updated quickstart guidance.

## Risks & Mitigations
- GPU context churn during rotation/split-screen may still drop frames; lifecycle handler rebinds surfaces and emits fallback events.
- Aggressive load-shedding can temporarily skip subtitle uploads; telemetry flags `LOAD_SHED` to aid tuning.
- Fallback loops: recovery coordinator gates retries to current surface IDs to avoid thrashing.

## Validation
- Complex ASS samples on SurfaceView/TextureView with telemetry overlay enabled; watch `SUB-GPU` and `SUB-FALLBACK` tags.
- Soak test ≥20 minutes to ensure load-shedding stabilizes CPU/GPU usage and no subtitle ghosting.
