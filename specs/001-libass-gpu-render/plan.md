# Implementation Plan: Libass GPU Subtitle Pipeline

**Branch**: `001-libass-gpu-render` | **Date**: 2025-11-27 | **Spec**: `specs/001-libass-gpu-render/spec.md`
**Input**: Feature specification from `/specs/001-libass-gpu-render/spec.md`

## Summary

Implement a GPU-backed libass subtitle rendering pipeline that runs on a native render thread using OpenGL ES 3.x FBO/EGLImage composition, with automatic fallback when GPU is unavailable, while preserving smooth playback and emitting structured telemetry for validation.

## Technical Context
**Language/Version**: Kotlin 1.7.21 + JNI/C for libass 0.17.3  
**Primary Dependencies**: ExoPlayer 2.18.x pipeline, Media3 interop helpers, libass 0.17.3 prebuilt, MMKV (settings), ARouter (navigation); GPU composition via OpenGL ES 3.x FBO/EGLImage with dedicated native render thread  
**Storage**: N/A (uses existing subtitle/font asset loaders)  
**Testing**: Gradle `./gradlew testDebugUnitTest` and `./gradlew connectedDebugAndroidTest`; playback validation via ASS samples on device/emulator  
**Target Platform**: Android 21–33 mobile/TV devices  
**Project Type**: Multi-module Android app (launcher + feature components)  
**Performance Goals**: 60 fps subtitle compositing with ≥95% frames within vsync window on 1080p/4K ASS; CPU peak at least 30% lower than legacy path under complex effects  
**Constraints**: Must degrade gracefully within 1s on surface loss or GPU unavailability; handle SurfaceView/TextureView swaps without flicker; avoid subtitle ghosts on seek/track change; telemetry via native render/upload timers + ExoPlayer AnalyticsListener/VideoFrameMetadataListener and optional Choreographer sampling  
**Scale/Scope**: Player subtitle pipeline across `player_component` with shared utilities in `common_component`/`data_component`; supports controller overlays and TV remote focus flows

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- Constitution status: `.specify/memory/constitution.md` is a placeholder with unnamed principles and no enforceable rules.  
- Gate assessment: No explicit constraints to violate; proceed with NOTICE. Future constitution updates must be re-evaluated.
- Post-design check: unchanged; no additional gates detected after Phase 1 outputs.

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
specs/001-libass-gpu-render/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/

app/                     # launcher shell, manifest, shared UI glue
common_component/        # base classes, utilities, extension helpers
data_component/          # entities, repositories
player_component/        # playback UI, render pipeline, subtitle handling
storage_component/       # storage helpers and media assets
anime_component/, user_component/, stream_component/, download_component/ ...
buildSrc/                # Gradle conventions and lint/ktlint rules
document/, scripts/, repository/  # supplemental assets/tools
```

**Structure Decision**: Multi-module Android app; this feature primarily extends `player_component` native/JNI subtitle pipeline with support utilities in `common_component` and configuration/logging in `data_component`/`app`.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
