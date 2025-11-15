# Implementation Plan: Add Pluggable Subtitle Rendering Backend (libass)

**Branch**: `001-add-libass-backend` | **Date**: 2025-11-15 | **Spec**: /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/spec.md
**Input**: Feature specification from `/specs/001-add-libass-backend/spec.md`

## Summary

Introduce a pluggable subtitle rendering backend in `player_component` and integrate libass 0.17.3 (prebuilt .so already placed under `player_component/libs`). Keep the existing Canvas/TextPaint renderer and add a user-facing toggle in Settings to switch between backends. Scope limits support to ExoPlayer, using TextureView overlay (custom View drawing libass-produced bitmaps) and SurfaceView dual-surface overlay for transparent subtitles. Default remains the legacy renderer; failures trigger a blocking dialog offering one-click fallback without interrupting media playback.

## Technical Context

**Language/Version**: Kotlin 1.7.21, AGP 7.3.1; JNI bindings to libass 0.17.3 (prebuilt)  
**Primary Dependencies**: ExoPlayer 2.18.x; libass 0.17.3; Android SDK 21–33; MMKV (settings); ARouter (navigation)  
**Storage**: N/A (settings via MMKV; no schema changes)  
**Testing**: Gradle `testDebugUnitTest`, `connectedDebugAndroidTest`; visual baselines on sample ASS/SSA sets; instrumentation asserts for toggle + fallback UI  
**Target Platform**: Android API 21+ (minSdk 21, target 29, compile 33)  
**Project Type**: mobile (modular MVVM; feature touches `player_component`, `user_component`)  
**Performance Goals**: Maintain 60 fps rendering; first-subtitle latency within ±10% of legacy; avoid redundant re-render unless libass `changed` flag true or event boundary reached  
**Constraints**: Transparent overlay correctness on TextureView/SurfaceView; ASS styles preserved; memory bounded (subtitle bitmaps ≤ 2 frames of RGBA at view resolution)  
**Scale/Scope**: Single feature; no IJK/VLC integration in this version; no remote toggle; SRT handled by legacy path; ASS/SSA routed by user setting only

## Constitution Check

GATE 1 — Documentation artifacts present (plan, research, data-model, contracts, quickstart): PASS  
GATE 2 — Modularity preserved (no cross-module leaks; interfaces in `player_component` only): PASS  
GATE 3 — Testability: unit/instrumentation entry points identified; non-visual parts covered; visual validated by procedure: PASS  
Rationale: The repository constitution file is a placeholder with no enforceable rules; we adopt standard gates above to avoid ambiguity and ensure quality.

## Project Structure

### Documentation (this feature)

```text
specs/001-add-libass-backend/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/
```

### Source Code (repository root)

```text
android/
└── player_component/
    └── src/main/java/.../player/subtitle/
        ├── backend/
        │   ├── SubtitleRendererBackend.kt         # interface
        │   ├── CanvasTextRendererBackend.kt       # existing impl (migrate here)
        │   └── LibassRendererBackend.kt           # new impl (JNI + bitmap)
        ├── libass/
        │   ├── LibassBridge.kt                    # JNI wrapper
        │   └── native/                            # headers if needed
        └── ui/
            ├── SubtitleOverlayView.kt             # TextureView overlay path
            └── SubtitleSurfaceOverlay.kt          # SurfaceView overlay helper

user_component/
└── src/main/java/.../settings/subtitle/
    └── SubtitleBackendSettingFragment.kt          # toggle UI
```

**Structure Decision**: Android modular project; add a backend interface and libass implementation under `player_component`. UI toggle resides in `user_component`. No new modules introduced.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| JNI wrapper presence | Required to call libass C API | Pure Kotlin/Canvas cannot provide ASS/SSA parity |

