# Research

## GPU composition API & render-thread integration
- Decision: Use OpenGL ES 3.x with EGLImage/SurfaceTexture targeting the player SurfaceView/TextureView; render libass outputs into FBOs and blit via hardware overlays, with a dedicated native render thread synced to vsync.
- Rationale: GLES3 is universally available on API 21+, supports alpha blending, FBOs, PBOs for async uploads, and integrates cleanly with existing ExoPlayer GL contexts; avoids Vulkan footprint and lowers integration risk. Keeping a native render thread off the main thread matches libass usage and prevents UI jank.
- Alternatives considered: Vulkan (broader API but heavier initialization, weaker device coverage); CPU/Canvas composition (already known bottleneck and fails GPU goal); GLES2-only path (works but limits PBO + modern extensions and risks quality for complex shaders).

## Libass → GPU upload strategy
- Decision: Render ASS bitmaps via libass, pack regions into RGBA textures using double-buffered PBOs, and composite with premultiplied alpha blend (`SRC_ALPHA, ONE_MINUS_SRC_ALPHA`) on the GL thread; reuse textures across frames when glyph atlas unchanged.
- Rationale: PBO staging reduces stalls during glTex(Sub)Image2D, premultiplied alpha prevents fringe artifacts, and atlas reuse minimizes bandwidth. Aligns with common GLES subtitle overlays and documented PBO best practices.
- Alternatives considered: Direct glTexImage2D per frame (simpler but higher stalls); GPU glyph re-rasterization via custom shaders (complex, diverges from libass font/shaping correctness); CPU-side bitmap merge (keeps bottleneck).

## Fallback & surface lifecycle handling
- Decision: Treat EGL errors, Surface destruction/recreation, or GLES context loss as triggers to downgrade to existing CPU/Canvas or software path; rebuild GL resources on `surfaceCreated`/`surfaceChanged` and block rendering until the pipeline is reinitialized, clearing stale frames.
- Rationale: Surface loss is common on rotation/multi-window; explicit teardown avoids ghost subtitles. Degrading keeps playback alive per FR-004 and user story 3. Clearing textures prevents lingering ghosts.
- Alternatives considered: Silent retries on GL errors (risks hangs); keeping old textures after surface swap (causes ghosting/misaligned frames).

## Telemetry hook placement
- Decision: Instrument subtitle render latency and frame drops via: (1) native timestamps around libass render + GL upload/composite, (2) ExoPlayer `AnalyticsListener`/`VideoFrameMetadataListener` for playback timeline correlation, and (3) optional Choreographer/`dumpsys SurfaceFlinger --latency` sampling in debug builds. Emit structured logs (tagged) rather than verbose logs by default.
- Rationale: Combines precise native timing with player timeline and UI smoothness, enabling validation of SC-001/SC-002. Structured logs avoid noisy adb output while supporting filtering.
- Alternatives considered: Relying only on adb logcat scanning (too noisy, per project guidance); GPU driver-specific counters (device-fragmented and overkill for this phase).
