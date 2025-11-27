# Research: libass backend integration on Android

## Scope & Questions

- How to render ASS/SSA with libass on Android efficiently (TextureView vs SurfaceView)?
- How to manage fonts on Android (search path, fallback)?
- How to schedule rendering and avoid redundant frames (use `changed`)?
- How to combine video and subtitles (overlay) without disturbing controls/gestures?
- Error handling and user fallback UX per spec (blocking dialog, no auto rollback)?
- Performance guardrails and memory footprint boundaries.

## Findings

Decision: Use libass 0.17.3 via JNI with a per-session `ASS_Library` + `ASS_Renderer` + `ASS_Track` lifecycle.  
Rationale: Standard libass API: `ass_library_init` → `ass_renderer_init` → `ass_set_frame_size`/`ass_set_fonts` → `ass_new_track`/`ass_process_data` → `ass_render_frame` returning linked `ASS_Image` nodes. This is the most common integration shape and is widely battle-tested.
Alternatives considered: Reimplement Canvas/TextPaint ASS features (high cost, poor parity), burn-in via FFmpeg filter chain (breaks interactive overlays and increases pipeline complexity on-device).

Decision: Overlay composition model.  
Rationale: 
- TextureView path: Add a dedicated `SubtitleOverlayView` that composites the `ASS_Image` chain into a single ARGB bitmap per frame and draws onto Canvas; aligns with video bounds and respects rotation/resize.
- SurfaceView path: Use a transparent subtitle SurfaceView with `PixelFormat.TRANSLUCENT` and `setZOrderMediaOverlay(true)`; clear canvas each frame to avoid trails. Control layout to match video visible rect.
Alternatives considered: GPU shaders for direct glyph composition (complex and not required initially); burning subtitles into video frames (removes user control and degrades UX).

Decision: Render size equals the current visible video area in pixels.  
Rationale: Matches spec; avoids scaling artifacts and keeps text crisp. Use `ass_set_frame_size(renderer, width, height)` on layout changes.
Alternatives considered: Fixed logical resolution with post-scale (simpler but risks blur and style misplacement).

Decision: Font resolution and selection.  
Rationale: `ass_set_fonts(renderer, default_font, default_family, use_fontconfig=false, fonts_dir, false)` with:  
1) subtitle directory fonts (if present), 2) system fonts (`/system/fonts/`, `/system/font/`), 3) app-bundled fallback (optional). Preserve ASS-declared styles first; only apply global offsets (time/vertical) as allowed by spec.
Alternatives considered: Enforce a single app font (violates FR-014; loses stylistic fidelity).

Decision: Re-render only when needed.  
Rationale: `ass_render_frame(renderer, track, now_ms, &changed)` supports skipping if `changed==0`. Throttle updates to video clock ticks and subtitle event boundaries to minimize CPU.
Alternatives considered: Constant 60 fps ticker (wastes CPU on static lines).

Decision: Error handling and fallback UX.  
Rationale: On init or first render failure, show blocking dialog with actions: “Switch back to legacy” / “Keep trying”. Do not auto-switch; apply setting change immediately on confirm; playback continues regardless, per spec.
Alternatives considered: Silent auto-rollback (hurts diagnosability and control per spec).

Decision: Keep ExoPlayer-only scope for v1.  
Rationale: Limits complexity; IJK/VLC require separate surface plumbing. The backend abstraction makes future extension straightforward.
Alternatives considered: Multi-kernel support in v1 (increases risk and test matrix size).

## Integration Notes

- JNI bridge exposes minimal functions: init/shutdown, loadTrack(from memory/string), setFrameSize, setFonts, renderFrame(nowMs) → returns bitmap (composited) or raw ASS_Image list to be blended in Kotlin.
- If the shipped libass lacks HarfBuzz, complex shaping quality may vary; treat HB as optional and document. No runtime dependency assumed beyond prebuilt .so.
- Memory: reuse a single ARGB bitmap buffer sized to the current view; clear between frames; avoid per-frame allocations.
- Surface validity: follow existing PixelCopy guidance (see `player_component/utils/PlayRecorder.kt:169`) to guard against invalid surfaces on rotation/resume.

## References

- libass project and wiki (ASS file format, integration notes): https://github.com/libass/libass/wiki/ASS-File-Format-Guide
- FFmpeg subtitles filter using libass (integration reference): https://ffmpeg.org/doxygen/trunk/vf__subtitles_8c.html
- SSA/ASS handling examples in FFmpeg: https://ffmpeg.org/ass_8c.html

## Open Points Resolved

- Remote toggle: out-of-scope for this version; local setting only.
- Default selection: legacy renderer for both new installs and upgrades.
- Unsupported format under libass: show prompt to switch; default no change.
- SurfaceView support: dual-surface overlay confirmed; transparent subtitle surface aligned to video rect.
- Style priority: preserve ASS/SSA styles; apply only generic offsets per spec.

