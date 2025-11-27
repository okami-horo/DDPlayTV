# Tasks: Libass GPU Subtitle Pipeline

**Input**: Design documents from `/workspace/DanDanPlayForAndroid/specs/001-libass-gpu-render/`  
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Only add tests if explicitly requested by the spec (not requested here).  
**Organization**: Tasks are grouped by user story so each story can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)  
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)  
- Include exact file paths in descriptions (absolute paths below)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare build and native toolchain for GPU subtitle pipeline.

- [X] T001 Align GLES3/libass GPU dependencies and CMake options in `/workspace/DanDanPlayForAndroid/player_component/build.gradle.kts`
- [X] T002 [P] Add GPU subtitle feature flags and default telemetry toggle in `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/AppConfig.kt`
- [X] T003 [P] Prepare native targets and include libass GPU render thread entry points in `/workspace/DanDanPlayForAndroid/player_component/src/main/cpp/CMakeLists.txt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared data models, contracts, and JNI bridge required before user stories.
**Note**: `/subtitle/pipeline/*` contracts are in-process façades, not remote HTTP endpoints; keep implementations local.

**⚠️ CRITICAL**: Complete before any user story work.

- [X] T004 Create data entities `SubtitlePipelineState`, `SubtitleOutputTarget`, `TelemetrySample`, `FallbackEvent` per data-model.md in `/workspace/DanDanPlayForAndroid/data_component/src/main/java/com/xyoye/data_component/bean/subtitle/`
- [X] T005 [P] Define in-process subtitle pipeline façade (init/status/fallback/telemetry) from contracts/openapi.yaml in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/network/subtitle/SubtitlePipelineApi.kt` (no HTTP transport)
- [X] T006 Establish structured subtitle telemetry logger/tags for GPU pipeline in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/SubtitleTelemetryLogger.kt`
- [X] T007 [P] Provide JNI/bridge interface for native libass GPU render thread lifecycle in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/AssGpuNativeBridge.kt`

**Checkpoint**: Foundation ready — user story phases can start.

---

## Phase 3: User Story 1 - 流畅播放复杂字幕 (Priority: P1) 🎯 MVP

**Goal**: GPU-backed libass rendering stays smooth and synced for complex ASS/SSA effects.  
**Independent Test**: Play 1080p/4K complex ASS sample; subtitles stay in sync at 60 fps with no UI jank.

- [X] T008 [US1] Implement GPU render thread orchestrator with libass FBO/EGLImage pipeline in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/AssGpuRenderer.kt`
- [X] T009 [P] [US1] Track SurfaceView/TextureView changes and build `SubtitleOutputTarget` updates feeding pipeline init/status in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleOutputTargetTracker.kt`
- [X] T010 [P] [US1] Implement pipeline controller issuing `/subtitle/pipeline/init` via local façade and maintaining `SubtitlePipelineState` in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitlePipelineController.kt`
- [X] T011 [US1] Support subtitle time offset settings and keep alignment across play/pause/seek in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/render/SubtitleRenderScheduler.kt`, persisting offset in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/SubtitlePreferenceUpdater.kt`
- [X] T012 [US1] Wire render scheduler to ExoPlayer `AnalyticsListener`/`VideoFrameMetadataListener` for timeline sync in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/render/SubtitleRenderScheduler.kt`
- [X] T013 [US1] Clear/flush subtitle textures on seek/track change to prevent ghost frames in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleFrameCleaner.kt`
- [X] T014 [P] [US1] Expose GPU renderer backend toggle using subtitle preferences in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/SubtitlePreferenceUpdater.kt`

**Checkpoint**: User Story 1 independently testable via complex ASS playback.

---

## Phase 4: User Story 2 - 资源占用受控 (Priority: P2)

**Goal**: Keep CPU/GPU usage stable during long playback; maintain interaction responsiveness.  
**Independent Test**: 20-minute complex subtitle playback on mid-tier device; no heat or lag, telemetry shows controlled resource use.

- [X] T015 [US2] Collect native render/upload/composite timings into `TelemetrySample` stream in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleTelemetryCollector.kt`
- [X] T016 [P] [US2] Aggregate telemetry snapshots and serve `/subtitle/pipeline/telemetry` and `/subtitle/pipeline/telemetry/latest` in `/workspace/DanDanPlayForAndroid/data_component/src/main/java/com/xyoye/data_component/repository/subtitle/SubtitleTelemetryRepository.kt`
- [X] T017 [P] [US2] Add on-device performance overlay/structured log filters for CPU/GPU/VSYNC metrics in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/widgets/SubtitleTelemetryOverlay.kt`
- [X] T018 [US2] Implement load-shedding/backpressure policy to throttle subtitle uploads when system is overutilized in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleLoadSheddingPolicy.kt`

**Checkpoint**: User Story 2 independently testable via long-run playback with telemetry.

---

## Phase 5: User Story 3 - 可用性与降级 (Priority: P3)

**Goal**: Detect GPU unavailability and degrade/recover without interrupting playback.  
**Independent Test**: Force surface loss or disable hardware accel; app falls back within ~1s, keeps subtitles visible, and can auto-recover.

- [X] T019 [US3] Implement fallback controller mapping `/subtitle/pipeline/fallback` and mode transitions in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleFallbackController.kt`
- [X] T020 [P] [US3] Handle surface recreate/resize events and rebuild GL resources safely in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleSurfaceLifecycleHandler.kt`
- [X] T021 [US3] Report fallback reasons and recovery attempts via structured logs/prompts in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/log/SubtitleFallbackReporter.kt`
- [X] T022 [P] [US3] Add recovery path to re-init GPU pipeline after fallback when conditions improve in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/SubtitleRecoveryCoordinator.kt`

**Checkpoint**: User Story 3 independently testable via forced fallback/recovery scenarios.

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Hardening and documentation across all stories.

- [X] T023 [P] Update quickstart with GPU pipeline usage, fallback steps, and telemetry tips in `/workspace/DanDanPlayForAndroid/specs/001-libass-gpu-render/quickstart.md`
- [X] T024 Document release notes and edge-case coverage for the feature in `/workspace/DanDanPlayForAndroid/document/release-notes/001-libass-gpu-render.md`
- [X] T025 Optimize render/upload hotspots identified in telemetry in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/subtitle/gpu/AssGpuRenderer.kt`
- [X] T026 [P] Capture validation artifacts (screenshots/log extracts) for review in `/workspace/DanDanPlayForAndroid/specs/001-libass-gpu-render/checklists/validation.md`

---

## Dependencies & Execution Order

- Setup (Phase 1) → Foundational (Phase 2) → User Stories (Phase 3–5) → Polish.  
- User Story order by priority: US1 (P1) → US2 (P2) → US3 (P3); each should stay independently testable after Foundational.  
- Contracts/data models (T004–T007) block all story work; complete them first.

## Parallel Execution Examples

- US1: T009 and T010 in parallel while T008 scaffolds renderer; T014 can proceed once preferences are available.  
- US2: T015 can collect metrics while T016 builds aggregation; T017 runs separately from T018.  
- US3: T020 and T022 can run together once fallback controller (T019) shape is known.

## Implementation Strategy

- MVP first: finish Setup + Foundational, deliver US1 end-to-end, validate against complex ASS samples.  
- Incremental: add US2 telemetry/resource controls next, then US3 fallback/recovery; keep each story demoable.  
- Keep GPU/CPU fallback toggles and telemetry switches exposed to simplify validation and regression checks.
