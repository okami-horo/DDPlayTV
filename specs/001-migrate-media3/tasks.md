# Tasks: Media3 Playback Migration

**Input**: Design documents from `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/`  
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Each story lists targeted validation tasks where the spec explicitly calls for regression coverage.

**Organization**: Tasks are grouped by user story so each slice is independently implementable and testable.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Align tooling, dependencies, and rollout toggles with Media3 prerequisites.

- [X] T001 Update compileSdk (≥32), targetSdk, and minSdk (≥21) plus AGP version in `/workspace/DanDanPlayForAndroid/build.gradle.kts` and `/workspace/DanDanPlayForAndroid/app/build.gradle.kts` so Media3 artifacts build without warnings.  
- [X] T002 Declare `media3Version=1.8.0` and the `media3_enabled` gradle fallback flag inside `/workspace/DanDanPlayForAndroid/gradle.properties`, then expose the version in `/workspace/DanDanPlayForAndroid/player_component/build.gradle.kts` dependency blocks.  
- [X] T002a Run `./gradlew dependencyUpdates` from the repo root after T001–T002 and record upgrade decisions inside `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/quickstart.md` to satisfy the Verified Gradle Workflow requirement.  
- [X] T003 Register a Remote Config default for `media3_enabled` and wire it to the application bootstrap in `/workspace/DanDanPlayForAndroid/app/src/main/res/xml/remote_config_defaults.xml` and `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/AppConfig.kt`.  
- [X] T004 Extend the migration runbook with prerequisite commands (`clean build`, `lint`, `testDebugUnitTest`, `connectedDebugAndroidTest`) plus rollout instructions inside `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/quickstart.md`.  

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish shared models, services, and toggles that every story depends on.  
**⚠️ CRITICAL**: Complete this phase before starting any story work.

- [X] T005 Model `PlaybackSession`, `PlayerCapabilityContract`, `TelemetryEvent`, `RolloutToggleSnapshot`, and `DownloadAssetCheck` data classes in `/workspace/DanDanPlayForAndroid/data_component/src/main/java/com/xyoye/data_component/entity/media3/`.  
- [X] T006 Implement the Retrofit interface covering `/v1/media3/sessions`, `/commands`, `/telemetry`, `/rollout`, and `/downloads/validate` in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/network/service/Media3Service.kt`.  
- [X] T007 Create `Media3Repository` that orchestrates the new service, local caches, and entity mappers in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/network/repository/Media3Repository.kt`.  
- [X] T008 Define an ARouter-accessible `Media3CapabilityProvider` contract in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/service/Media3CapabilityProvider.kt` and register a stub implementation in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/service/Media3CapabilityService.kt`.  
- [X] T009 Build `Media3ToggleProvider` that evaluates Remote Config → gradle fallback once per session and surfaces immutable snapshots inside `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/config/Media3ToggleProvider.kt`.  
- [X] T010 [P] Harden legacy capability handling by implementing `LegacyCapabilityMapper` that translates Exo-only renderer/subtitle/DRM options into Media3-compatible parameters or structured error objects within `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/mapper/LegacyCapabilityMapper.kt`.  
- [X] T011 [P] Cover the mapper with unit tests for translation + blocking scenarios in `/workspace/DanDanPlayForAndroid/player_component/src/test/java/com/xyoye/player_component/media3/LegacyCapabilityMapperTest.kt`, ensuring FR-008 is enforceable.  

---

## Phase 3: User Story 1 - Seamless Media Playback (Priority: P1) 🎯 MVP

**Goal**: Ensure every stream launches through the Media3-powered player while meeting first-frame and stability KPIs.  
**Independent Test**: Launch three popular titles on Wi-Fi and cellular, confirm the player reports Media3 usage, and verify first frame renders within 2 seconds with responsive controls.

### Tests for User Story 1 ⚠️

- [X] T012 [P] [US1] Add `Media3PlayerDelegateTest` covering toggle branching, session creation, and error surfacing in `/workspace/DanDanPlayForAndroid/player_component/src/test/java/com/xyoye/player_component/media3/Media3PlayerDelegateTest.kt`.  
- [X] T013 [P] [US1] Add instrumentation smoke test verifying Media3 playback startup latency in `/workspace/DanDanPlayForAndroid/player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3PlaybackSmokeTest.kt`.  
- [X] T014 [P] [US1] Add instrumentation verifying that flipping `media3_enabled` mid-session keeps the active playback on its original stack while new launches adopt the updated toggle in `/workspace/DanDanPlayForAndroid/player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3ToggleSnapshotTest.kt`.  
- [X] T014a [P] [US1] Add instrumentation that forces a codec mismatch and confirms the player surfaces audio-only fallback messaging without crashing in `/workspace/DanDanPlayForAndroid/player_component/src/androidTest/java/com/xyoye/player_component/ui/Media3CodecFallbackTest.kt`.  

### Implementation for User Story 1

- [X] T015 [P] [US1] Implement `Media3PlayerDelegate` that wraps `androidx.media3.exoplayer.ExoPlayer` and maps Media3 events to `PlayerCapabilityContract` in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/Media3PlayerDelegate.kt`.  
- [X] T016 [P] [US1] Create `Media3SessionController` to call `POST/GET /v1/media3/sessions` and hydrate capability metadata in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/session/Media3SessionController.kt`.  
- [X] T017 [US1] Update the player factory selection logic in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/kernel/facoty/PlayerFactory.kt` to prefer the Media3 delegate whenever `media3_enabled` is true while leaving the legacy Exo path as fallback.  
- [X] T018 [US1] Adapt `PlayerViewModel` to bind `PlaybackSession` state (position, bitrate, errors) and dispatch commands via the capability provider inside `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerViewModel.kt`.  
- [X] T019 [US1] Ensure playback launchers pass source metadata and toggle cohorts by updating `/workspace/DanDanPlayForAndroid/anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/anime_episode/AnimeEpisodeFragment.kt` before routing to `RouteTable.Player.Player`.  
- [X] T019a [US1] Update remote streaming launchers (SMB/WebDav/FTP) by wiring `/workspace/DanDanPlayForAndroid/storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt` to the Media3 capability provider so non-anime streams also instantiate the Media3 delegate.  
- [X] T019b [US1] Implement a `CodecFallbackHandler` inside `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/fallback/CodecFallbackHandler.kt` that detects unsupported codecs at session start, forces audio-only playback, and emits a user-visible reason consumed by casting/background surfaces.  
- [X] T020 [US1] Enforce the immutable rollout snapshot by persisting the evaluated toggle on session creation and surfacing user-facing fallback messaging when legacy-only capabilities are blocked inside `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/session/RolloutSnapshotManager.kt`.  

**Checkpoint**: Streaming playback now flows through Media3 under the rollout flag with verified startup telemetry.

---

## Phase 4: User Story 2 - Consistent Controls Across Features (Priority: P2)

**Goal**: Share identical Media3-driven controls across streaming, background, casting, and download/offline entry points.  
**Independent Test**: Start playback, enable background audio/PiP, return via notification controls, cast to a target, and resume a download—all surfaces must reflect the same state without legacy callbacks.

### Tests for User Story 2 ⚠️

- [X] T021 [P] [US2] Add background/notification regression covering MediaSession command sync in `/workspace/DanDanPlayForAndroid/app/src/androidTest/java/com/xyoye/dandanplay/app/Media3BackgroundTest.kt`.  
- [X] T022 [P] [US2] Add offline download resume instrumentation validating `/v1/media3/downloads/validate` responses in `/workspace/DanDanPlayForAndroid/storage_component/src/androidTest/java/com/xyoye/storage_component/download/Media3DownloadValidationTest.kt`.  
- [X] T022a [P] [US2] Add cast instrumentation that injects an unsupported codec stream and verifies the session falls back to audio-only mode with the correct notification/banner in `/workspace/DanDanPlayForAndroid/app/src/androidTest/java/com/xyoye/dandanplay/app/Media3CastFallbackTest.kt`.  

### Implementation for User Story 2

- [X] T023 [P] [US2] Create `Media3SessionService` that binds the player delegate to `MediaSession`, notifications, and widgets in `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/service/Media3SessionService.kt`.  
- [X] T024 [P] [US2] Wire PiP/background state observers to the Media3 session by updating `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt`.  
- [X] T025 [US2] Implement Media3 Cast integration (device discovery, session transfer) in `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/cast/Media3CastManager.kt`.  
- [X] T025a [US2] Surface codec fallback messaging for cast targets by extending `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/cast/Media3CastManager.kt` to consume `CodecFallbackHandler` output and relay audio-only handoffs.  
- [X] T026 [US2] Integrate offline validation + audio-only fallback before enabling Play in `/workspace/DanDanPlayForAndroid/storage_component/src/main/java/com/xyoye/storage_component/download/DownloadValidator.kt`.  
- [X] T027 [US2] Refactor local media resume flow to use the shared `PlayerCapabilityContract` inside `/workspace/DanDanPlayForAndroid/local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt`.  
- [X] T027a [US2] Update magnet/torrent playback prompts in `/workspace/DanDanPlayForAndroid/local_component/src/main/java/com/xyoye/local_component/ui/dialog/MagnetPlayDialog.kt` (and supporting helpers in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/utils/thunder/ThunderManager.kt`) so download-based launches pass Media3 session metadata before routing to the player.  

**Checkpoint**: All playback surfaces now consume the shared Media3 session, keeping controls and metadata synchronized.

---

## Phase 5: User Story 3 - Operational Confidence & Monitoring (Priority: P3)

**Goal**: Deliver telemetry, rollout snapshots, and monitoring hooks that differentiate Media3 sessions from the legacy stack.  
**Independent Test**: Run the playback regression suite, ensure dashboards flag Media3 identifiers, alerting triggers on failures, and no Exo-only metrics remain.

### Tests for User Story 3 ⚠️

- [X] T028 [P] [US3] Add mapper tests that assert Media3 telemetry payloads serialize all required identifiers in `/workspace/DanDanPlayForAndroid/data_component/src/test/java/com/xyoye/data_component/media3/TelemetryEventMapperTest.kt`.  
- [X] T029 [P] [US3] Add repository tests for rollout toggle persistence and `/v1/media3/rollout` overrides in `/workspace/DanDanPlayForAndroid/common_component/src/test/java/com/xyoye/common_component/network/repository/Media3RepositoryTest.kt`.  

### Implementation for User Story 3

- [X] T030 [P] [US3] Implement telemetry submission + batching in `/workspace/DanDanPlayForAndroid/common_component/src/main/java/com/xyoye/common_component/network/repository/Media3TelemetryRepository.kt` targeting `POST /v1/media3/telemetry`.  
- [X] T031 [US3] Emit STARTUP, FIRST_FRAME, ERROR, and CAST_TRANSFER events from the Media3 delegate in `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player_component/media3/Media3PlayerDelegate.kt`.  
- [X] T032 [US3] Persist `RolloutToggleSnapshot` and `DownloadAssetCheck` history via a Room DAO inside `/workspace/DanDanPlayForAndroid/data_component/src/main/java/com/xyoye/data_component/database/dao/Media3Dao.kt`.  
- [X] T033 [US3] Document alert routing, dashboards, and rollback steps for ops inside `/workspace/DanDanPlayForAndroid/document/monitoring/media3-telemetry.md`.  
- [X] T034 [P] [US3] Tag crashes/ANRs with Media3 cohort metadata by updating Bugly/Firebase initialization in `/workspace/DanDanPlayForAndroid/app/src/main/java/com/xyoye/dandanplay/app/App.kt` and adding verification instrumentation in `/workspace/DanDanPlayForAndroid/app/src/androidTest/java/com/xyoye/dandanplay/app/CrashTaggingTest.kt` to keep the ≤0.2% KPI enforceable.  
- [X] T035 [US3] Automate crash/telemetry alert dashboards (Grafana/DataStudio) documenting thresholds and on-call rotation in `/workspace/DanDanPlayForAndroid/document/monitoring/media3-stability.md`.  

**Checkpoint**: Monitoring cleanly differentiates Media3 cohorts and supports rapid rollback/triage.

---

## Final Phase: Polish & Cross-Cutting Concerns

**Purpose**: Wrap-up tasks that improve quality, documentation, and readiness across the stack.

- [ ] T036 [P] Remove unused legacy Exo factories/adapters once telemetry confirms Media3 stability inside `/workspace/DanDanPlayForAndroid/player_component/src/main/java/com/xyoye/player/kernel/impl/exo/`.  
- [ ] T037 [P] Author a migration FAQ + user-facing release notes covering codec fallbacks in `/workspace/DanDanPlayForAndroid/document/release-notes/media3-migration.md`.  
- [ ] T038 Harden CI by adding `/workspace/DanDanPlayForAndroid/scripts/ci/verify-media3.sh` to run `lint`, `testDebugUnitTest`, and `connectedDebugAndroidTest` gates on demand.  
- [ ] T039 Capture rollout verification results and KPI deltas in `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/checklists/requirements.md`.  
- [ ] T040 [P] Publish a support runbook + ticket tags that flag Media3 playback issues and outline troubleshooting (codec fallback, toggle snapshot, telemetry capture) inside `/workspace/DanDanPlayForAndroid/document/support/media3-playback-support.md`, enabling the 30% ticket reduction goal.  
- [ ] T041 [P] Implement `scripts/testing/media3-regression-report.sh` that parses `testDebugUnitTest` and `connectedDebugAndroidTest` results for `@Media3Dependent` suites, confirms ≥90% of cases run on the Media3 delegate, and writes the summary to `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/checklists/requirements.md` for SC-003 evidence.  
- [ ] T042 [P] Add support-ticket analytics by updating `/workspace/DanDanPlayForAndroid/document/support/media3-ticket-dashboard.md` with baseline vs. post-migration queries and instrumenting the helpdesk export pipeline so SC-004’s 30% reduction is measurable.  

---

## Dependencies & Execution Order

- **Setup → Foundational**: Complete T001–T004 before touching shared code; T005–T011 unblock every user story.  
- **User Story Order**: US1 (T012–T019) establishes the Media3 engine; US2 (T021–T027) layers cross-surface controls; US3 (T028–T033) adds telemetry—each can start once Foundational is done, but they should demo in priority order.  
- **Polish Tasks**: T036–T039 rely on all targeted user stories finishing and on telemetry sign-off.  
- **Contract Dependencies**: Media3 service/repository tasks (T006–T008) must land before any story-specific API consumption.  
- **Testing Gates**: Story test tasks (T012–T014a, T021–T022a, T028–T029) run prior to their implementation counterparts to maintain fail-first coverage.

---

## Parallel Execution Examples

- **US1**: Run T012 and T013 concurrently (unit + instrumentation), then parallelize T015 and T016 since delegate wiring and session controller touch different files.  
- **US2**: T021 and T022 can execute in parallel while T023 and T025 proceed separately (service vs cast manager).  
- **US3**: T028 and T029 validate data/repository logic simultaneously, while T030 and T033 happen independently (code vs documentation).

---

## Implementation Strategy

1. **MVP (US1)**: Finish Setup + Foundational phases, deliver T012–T019, and validate playback KPIs before enabling the toggle for a small cohort.  
2. **Incremental Delivery**: After MVP, ship US2 to unify background/cast/download flows, then US3 to expose telemetry and rollback confidence—each story can be demoed and rolled out independently.  
3. **Parallel Teams**: One squad can own US1’s player delegate while another prepares US2’s MediaSession work; a smaller ops-focused group can progress US3 telemetry tasks once repositories exist.  
4. **Rollout & Cleanup**: Use T033 + T039 to monitor adoption, then execute T036–T038 to retire legacy code and automate regression gates.
5. **Edge-Case Hardening**: Execute T019a/T019b before expanding rollout so streaming/remote-file launches inherit codec fallback, then finish T022a/T025a/T027a to keep casting and torrent/magnet flows aligned with the same audio-only degradation rules.
