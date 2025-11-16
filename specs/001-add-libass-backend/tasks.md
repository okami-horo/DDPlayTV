# Tasks: Add Pluggable Subtitle Rendering Backend (libass)

Feature: Add Pluggable Subtitle Rendering Backend (libass)
Spec: /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/spec.md
Plan: /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/plan.md

## Phase 1: Setup

- [X] T001 Add externalNativeBuild(cmake) to /workspace/DanDanPlayForAndroid/player_component/build.gradle.kts
- [X] T002 Create CMakeLists.txt to build libass_bridge and link prebuilt libass in /workspace/DanDanPlayForAndroid/player_component/src/main/cpp/CMakeLists.txt
- [X] T003 Create JNI header for bridge API in /workspace/DanDanPlayForAndroid/player_component/src/main/cpp/libass_bridge.h
- [X] T004 Implement JNI bridge skeleton (init, release, setFrameSize, setFonts, loadTrack, renderFrame) in /workspace/DanDanPlayForAndroid/player_component/src/main/cpp/libass_bridge.cpp
- [X] T005 [P] Add keep rules for native methods/classes in /workspace/DanDanPlayForAndroid/player_component/proguard-rules.pro
- [X] T006 [P] Verify jniLibs packaging config points to libs/ in /workspace/DanDanPlayForAndroid/player_component/build.gradle.kts

## Phase 2: Foundational

- [X] T007 Create enum SubtitleRendererBackend { LEGACY_CANVAS, LIBASS } in /workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/enums/SubtitleRendererBackend.kt
- [X] T008 Add MMKV key subtitleRendererBackend with default LEGACY_CANVAS in /workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/SubtitleConfigTable.kt
- [X] T009 Create backend interface SubtitleRenderer in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/SubtitleRenderer.kt
- [X] T010 Implement CanvasTextRendererBackend adapter around SubtitleTextView in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/CanvasTextRendererBackend.kt
- [X] T011 Implement LibassRendererBackend using LibassBridge in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T012 Create TextureView overlay view SubtitleOverlayView in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T013 Create SurfaceView overlay helper SubtitleSurfaceOverlay in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleSurfaceOverlay.kt
- [X] T045 Configure SurfaceView pixel format and Z-order (PixelFormat.TRANSLUCENT + setZOrderMediaOverlay) per FR-015 in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleSurfaceOverlay.kt
- [X] T014 Wire backend selection into player init (resolve LEGACY_CANVAS vs LIBASS) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T015 [P] Add PlayerInitializer.Subtitle.backend mapping from SubtitleConfig in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T016 [P] Add LibassBridge Kotlin wrapper with native loaders in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/libass/LibassBridge.kt
- [X] T017 Add route that skips legacy MixedSubtitle pipeline when LIBASS is active in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt
- [X] T018 Add subtitle file path handoff to backend (ASS/SSA) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/subtitle/ExternalSubtitleManager.kt
- [X] T041 Enforce Exo-only scope: disable libass path for IJK/VLC kernels in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T042 Implement font search order: subtitle directory → system fonts → fallback; pass to setFonts in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T043 Update libass frame size on visible rect/orientation changes (ass_set_frame_size) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T044 Enforce style precedence for libass: ignore user style overrides, apply only offsets in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt

## Phase 3: User Story 1 (P1)
Goal: 用户可在设置中切换字幕后端；新会话生效；ASS/SSA 走 libass，其他走旧实现；不支持时提示可切回。
Independent Test: 仅修改设置并重新开始一次播放，验证渲染、时序、样式与层级正确；不支持格式出现提示。

- [X] T019 [US1] Add ListPreference("subtitle_renderer_backend") with options LEGACY_CANVAS/LIBASS in /workspace/DanDanPlayForAndroid/user_component/src/main/res/xml/preference_subtitle_setting.xml
- [X] T020 [US1] Persist get/put of subtitle_renderer_backend via SubtitleConfig in /workspace/DanDanPlayForAndroid/user_component/src/main/java/com/xyoye/user_component/ui/fragment/SubtitleSettingFragment.kt
- [X] T021 [US1] Read setting at player init and set PlayerInitializer.Subtitle.backend in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T022 [P] [US1] Detect unsupported format for LIBASS, show prompt to switch in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/subtitle/ExternalSubtitleManager.kt
- [X] T023 [P] [US1] Ensure ASS/SSA tracks are loaded into libass backend in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T024 [US1] Skip legacy MixedSubtitle path when backend=LIBASS in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt
- [X] T025 [US1] Align overlay layout to video visible rect (TextureView) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T026 [P] [US1] Implement SurfaceView dual-surface overlay alignment in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleSurfaceOverlay.kt
- [X] T047 [US1] When LIBASS selected, show note that style settings are not applied (preserve ASS styles) in /workspace/DanDanPlayForAndroid/user_component/src/main/java/com/xyoye/user_component/ui/fragment/SubtitleSettingFragment.kt

## Phase 4: User Story 2 (P2)
Goal: 渲染失败出现阻断式弹窗，提供“一键切回旧后端/继续尝试”，切回立即生效，不中断媒体播放。
Independent Test: 构造初始化/渲染失败，弹窗出现；确认后立刻切回旧后端并恢复字幕；取消则不中断播放。

- [X] T027 [US2] Create blocking dialog SubtitleFallbackDialog with two actions in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleFallbackDialog.kt
- [X] T028 [US2] Trigger dialog on libass init/render first failure in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T029 [US2] Apply fallback change: update SubtitleConfig and re-bind legacy backend in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T030 [P] [US2] Keep playback running while switching backend (no media interruption) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt
- [X] T031 [P] [US2] Log fallback reason via ErrorReportHelper in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt

## Phase 5: User Story 3 (P3)
Goal: 在关于/调试信息中展示当前会话字幕后端与最近一次回退原因；可用于反馈定位。
Independent Test: 切换后端并触发一次回退后，进入调试信息页看到后端与回退记录。

- [X] T032 [US3] Add data class PlaybackSessionStatus provider in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/debug/PlaybackSessionStatusProvider.kt
- [X] T033 [US3] Update session status on init, failures, and fallback in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T034 [US3] Add Debug preference and view to show session backend/fallback in /workspace/DanDanPlayForAndroid/user_component/src/main/java/com/xyoye/user_component/ui/fragment/DeveloperSettingFragment.kt
- [X] T035 [P] [US3] Add optional force-fallback action for testing in /workspace/DanDanPlayForAndroid/user_component/src/main/java/com/xyoye/user_component/ui/fragment/DeveloperSettingFragment.kt

## Final Phase: Polish & Cross-Cutting

- [X] T036 Add vertical/time offset passthrough to libass (FR-014) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T037 Clear canvas between frames; render only when changed==1 (FR-015) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T038 Guard Surface validity (Texture/Surface) before draw in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleSurfaceOverlay.kt
- [X] T039 Document setup & toggle instructions in /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/quickstart.md
- [X] T040 Update README with feature summary and toggle location in /workspace/DanDanPlayForAndroid/README.md
- [X] T046 Create supported-format matrix (ASS/SSA targeted by libass; when backend doesn’t support a format, prompt per FR-008; legacy backend covers other formats) in /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/checklists/support-matrix.md

---

## Dependencies

- US1 → US2 → US3
- Foundational (Phase 2) blocks all US phases.
- Setup (Phase 1) blocks LibassRendererBackend and any native calls.
- T041–T044 must complete before US1 tasks that use libass；T045 亦需在使用 libass 的 US1 相关任务前完成。

## Non-Functional Validation (additions)

- [X] T048 Add performance tracing of subtitle render cadence with "changed" gating in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T049 Measure first-subtitle latency vs legacy and log comparison within ±10% in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T050 Add libass-specific error logging tags to support crash-rate comparison in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T051 Timestamp dialog show and recovery to validate ≤2s targets in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleFallbackDialog.kt
- [X] T052 Implement single reusable ARGB bitmap buffer and assert memory bound (≤2 frames at view resolution) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt
- [X] T053 Verify overlay does not block control/gesture interaction (manual checklist doc) in /workspace/DanDanPlayForAndroid/specs/001-add-libass-backend/checklists/overlay-gesture-validation.md
- [X] T058 Implement adaptive redraw throttle under load (CPU pressure/missed frames): reduce redraw cadence while preserving timing correctness in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt

## Data & Session Semantics (additions)

- [X] T054 Persist RendererPreference.source and updatedAtEpochMs in /workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/SubtitleConfigTable.kt
- [X] T055 Update fallback flow to write RendererPreference with updatedAtEpochMs in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt
- [X] T056 Populate PlaybackSession fields (surfaceType, start/end, reason) in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/debug/PlaybackSessionStatusProvider.kt
- [X] T057 Update session status on orientation/surface changes in /workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/subtitle/ui/SubtitleOverlayView.kt

## Parallel Execution Examples

- [P] T005, T006 can run in parallel with T001–T004 (separate files).
- [P] T015, T016 can run in parallel after T007–T011 (independent wrappers/wiring).
- [P] T022, T023, T026 can run in parallel within US1 once T009–T013 are ready.
- [P] T030, T031 can run in parallel in US2 after T027–T029 are scaffolded.
- [P] T035 can run in parallel in US3 after T032–T034 exist.

## Implementation Strategy

- MVP: Complete US1 end-to-end with LIBASS toggle, ASS/SSA rendering via overlay, and unsupported-format prompt. Defer advanced polish to later phases.
- Incremental delivery: land Phase 1–2 → US1 → US2 → US3 → Polish.
- Testing: prefer manual instrumentation for visual; add logs and debug screen for observability.

## Notes from Design Docs

- Tech stack: Kotlin 1.7.21, AGP 7.3.1, ExoPlayer 2.18.x, libass 0.17.3, MMKV.
- Rendering paths: TextureView with Canvas bitmap overlay; SurfaceView with dual-surface overlay (translucent).
- Style policy: Preserve ASS/SSA styles; only apply general offsets.
