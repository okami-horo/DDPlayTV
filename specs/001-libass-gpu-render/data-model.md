# Data Model — Libass GPU Subtitle Pipeline

## Entities

### SubtitlePipelineState
- Fields: `mode` (GPU_GL / Fallback_CPU), `status` (Initializing / Active / Degraded / Error), `surfaceId`, `eglContextId`, `lastError`, `lastRecoverAt`, `fallbackReason`, `telemetryEnabled` (bool).
- Relationships: References `SubtitleOutputTarget`; emits `TelemetrySample` per frame.
- Validation: `mode` and `status` required; `fallbackReason` required when `mode=Fallback_CPU` or `status=Degraded/Error`; `surfaceId` updates on surface recreate.
- State transitions: `Initializing` → `Active` on successful GL context + libass warmup; `Active` → `Degraded` on soft GL errors or throttling; `Active/Degraded` → `Fallback_CPU` on context loss/unsupported GPU; any state → `Error` on unrecoverable native failure; `Fallback_CPU/Error` → `Initializing` on retry after surface available.

### SubtitleOutputTarget
- Fields: `viewType` (SurfaceView/TextureView), `width`, `height`, `scale`, `rotation`, `colorFormat`, `supportsHardwareBuffer` (bool), `vsyncId` (last vsync token).
- Relationships: Bound to the current `SubtitlePipelineState`; provides geometry for libass layout and GL viewport.
- Validation: Dimensions >0; recompute on orientation/resolution changes; align libass pixel aspect to `width/height`.
- State transitions: Recreated on `surfaceCreated/surfaceChanged`; invalidated on `surfaceDestroyed` and triggers pipeline re-init.

### TelemetrySample
- Fields: `timestampMs`, `subtitlePtsMs`, `renderLatencyMs` (libass), `uploadLatencyMs` (PBO/glTexSubImage2D), `compositeLatencyMs`, `frameStatus` (Rendered/Dropped/Skipped), `dropReason` (SurfaceLost/QueueFull/Error), `cpuUsagePct` (optional), `gpuOverutilized` (flag), `vsyncMiss` (bool).
- Relationships: Linked to `SubtitlePipelineState` instance; aggregated for SC-001/SC-002 evaluation.
- Validation: `frameStatus` required; latencies non-negative; `dropReason` required when `frameStatus` != Rendered.

### FallbackEvent
- Fields: `timestampMs`, `fromMode`, `toMode`, `reason`, `surfaceId`, `recoverable` (bool).
- Relationships: Emitted by `SubtitlePipelineState` on downgrade/upgrade; stored alongside telemetry.
- Validation: `fromMode`/`toMode` required; `reason` must align to known codes (GL_ERROR, SURFACE_LOST, UNSUPPORTED_GPU, INIT_TIMEOUT).

## Relationships & Derived Views
- `SubtitlePipelineState` 1→1 `SubtitleOutputTarget`; 1→N `TelemetrySample` / `FallbackEvent`.
- Derived metrics: FPS stability = rendered frames / expected frames by vsyncId; CPU delta vs baseline derived from `cpuUsagePct` and system stats.
- Edge handling: On track switch/seek, flush pending `TelemetrySample` queue and clear GL textures to prevent ghost frames.
