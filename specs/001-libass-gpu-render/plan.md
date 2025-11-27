# Implementation Plan: Libass GPU Subtitle Pipeline

**Branch**: `001-libass-gpu-render` | **Date**: 2025-11-27 | **Spec**: `/workspace/DanDanPlayForAndroid/specs/001-libass-gpu-render/spec.md`
**Input**: Feature specification from `/specs/001-libass-gpu-render/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Deliver a GPU-backed libass subtitle pipeline running on the native render thread, synchronizing with video playback, handling Surface changes gracefully, and providing automatic fallback plus observability for performance and errors.

## Technical Context

**Language/Version**: Kotlin 1.7.x + Java interop; JNI/C++ for libass 0.17.3 render path  
**Primary Dependencies**: ExoPlayer 2.18.x (Media3 interop), libass 0.17.3, OpenGL ES 3.x FBO/EGLImage pipeline, MMKV for settings, ARouter for navigation glue  
**Storage**: N/A (reuses existing subtitle/font asset loaders)  
**Testing**: Gradle `testDebugUnitTest`, `connectedDebugAndroidTest` (instrumented)  
**Target Platform**: Android 21–33; mobile + TV/remote UX considerations  
**Project Type**: Android multi-module app (app shell + feature components)  
**Performance Goals**: Maintain 60 fps subtitle composition; 95% frames inside vsync window; CPU peak at least 30% lower than legacy path  
**Constraints**: Automatic downgrade when GPU path fails (<1s recovery), no subtitle ghosting on track changes, stable under SurfaceView/TextureView switches  
**Scale/Scope**: Player-facing subtitle pipeline spanning `player_component` + shared `common_component`/`data_component`; no new storage backends  
**API/Interface Notes**: `/subtitle/pipeline/*` is an in-process façade contract (repository/service) for player ↔ subtitle pipeline coordination, not a remote HTTP API

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution template has no ratified principles or governance details; no enforceable gates are defined. Proceeding under provisional PASS while noting governance content must be filled when available.

## Project Structure

### Documentation (this feature)

```text
specs/001-libass-gpu-render/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
app/                      # launcher shell, manifest, shared UI glue
common_component/         # base classes, utilities, shared render helpers
data_component/           # entities, repositories, data sources
player_component/         # playback engine, subtitle/render pipeline target
anime_component/, local_component/, storage_component/, stream_component/, user_component/, download_component/  # other feature slices
buildSrc/                 # build logic, lint/ktlint config
document/, scripts/, repository/, Img/, prompts/  # docs, assets, tooling
```

**Structure Decision**: Android multi-module architecture; libass GPU subtitle pipeline changes concentrate in `player_component` with shared/native helpers in `common_component` and data definitions in `data_component`; no new modules introduced.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitution violations identified; table not required.
