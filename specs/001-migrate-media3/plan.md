# Implementation Plan: Media3 Playback Migration

**Branch**: `[001-migrate-media3]` | **Date**: 2025-11-10 | **Spec**: `/workspace/DanDanPlayForAndroid/specs/001-migrate-media3/spec.md`
**Input**: Feature specification from `/specs/001-migrate-media3/spec.md`

## Summary

Replace the legacy Exo-based playback stack with AndroidX Media3 across every entry point (streaming, casting, downloads, previews, background widgets) while exposing a unified player capability contract, rollout toggle, and telemetry updates so playback KPIs (first-frame <2s, <0.2% crash/ANR) remain stable during migration.

## Technical Context

**Language/Version**: Kotlin 1.7.x with Java interoperability for existing Exo helper classes  
**Primary Dependencies**: AndroidX Media3 1.8.0 stack (`media3-exoplayer`, `media3-session`, `media3-ui`, HLS/DASH, Cast, WorkManager, datasource-okhttp, test-utils), Jetpack Lifecycle/ViewModel, ARouter, WorkManager for downloads  
**Storage**: Existing download cache plus `storage_component` test assets; no new external storage vendors planned  
**Testing**: `./gradlew testDebugUnitTest` for helpers/adapters, `./gradlew connectedDebugAndroidTest` for playback/casting flows, plus module-specific regression suites; the new `scripts/testing/media3-regression-report.sh` (T041) parses these outputs to prove ≥90% of tagged cases run on Media3 per SC-003  
**Target Platform**: Android devices with minSdk ≥21 (Lollipop) and compileSdk/targetSdk ≥32 aligned with AGP ≥7.1; focus on mid-tier + low-bandwidth phones per spec KPIs  
**Project Type**: Multi-module Android app (app + feature components)  
**Performance Goals**: 95% sessions reach first frame <2s, crash/ANR ≤0.2%, maintain seamless adaptive bitrate under 1 Mbps, telemetry coverage for 100% of player sessions  
**Constraints**: Remote-config driven `media3_enabled` rollout flag (gradle fallback) evaluated per session, immutable for in-flight sessions; offline validation via `DownloadAssetCheck`; codec fallback messaging when Media3 lacks codecs; support tooling must surface Media3 troubleshooting steps aligned with SC-004; any Gradle/build-script change (T001–T002) must be followed by `./gradlew dependencyUpdates` (T002a) so dependency drift stays visible  
**Scale/Scope**: Migration order: (1) `player_component` engine/capability layer, (2) app-level MediaSession + notifications, (3) feature modules—streaming flows inside `anime_component`, download/torrent flows in `storage_component` + `local_component`—plus casting entry points, (4) telemetry/data updates, (5) cleanup legacy Exo APIs

## Backend & Observability Alignment

- **API Contract Sources**: `/specs/001-migrate-media3/contracts/` includes schema files for `/v1/media3/sessions`, `/commands`, `/telemetry`, `/rollout`, `/downloads/validate`; all client and repository changes must align with these documents before coding.
- **Legacy Capability Mapping**: Documented adapters translate Exo-only renderers/subtitle hooks into Media3 equivalents or emit structured errors consumed by feature modules; this work blocks FR-008.
- **Crash/ANR Monitoring**: Bugly/Firebase dashboards require Media3 cohort tags emitted from player, telemetry, and crash reporters to enforce the 0.2% cap; alert routing is defined alongside telemetry tasks.
- **Support Feedback Loop**: Release notes, FAQs, and support-ticket tags must highlight Media3 cohorts to achieve the 30% ticket reduction KPI, with `document/support/media3-ticket-dashboard.md` tracking baseline vs. rollout volumes (T042).

## Constitution Check

- **Module-First MVVM Ownership**: Work spans `player_component` (core engine + capability layer), streaming entry points currently housed in `anime_component` (covering the historical `stream_component` scope), download/torrent launchers in `storage_component` + `local_component` (standing in for `download_component`), `app` shell wiring, and shared contracts in `common_component` + `data_component`. Each module will consume the new capability interface through ARouter/service locators so no feature module directly references another; any shared code (Media3 adapters, telemetry mappers) must live in `player_component` or be promoted into `common_component` only if multiple modules need it.
- **Verified Gradle Workflow**: During implementation we must run `./gradlew assembleDebug` for iterative validation, `./gradlew lint` after structural/editorial changes, `./gradlew testDebugUnitTest` whenever adapters/entities update, `./gradlew connectedDebugAndroidTest` before merging playback/casting changes, and `./gradlew clean build` before release branch cut. Dependency bumps for Media3 require `./gradlew dependencyUpdates`.
- **Kotlin Style Contract**: All new viewmodels remain under `*/presentation` packages, fragments/activities follow `fragment_<feature>.xml` / `activity_<feature>.xml`, ARouter routes respect `/module/Feature`. Shared helpers become extension functions inside `common_component`, and new Media3 services stick to explicit visibility + no trailing commas per buildSrc tooling.
- **Evidence-Driven Testing Gates**: Player adapters and telemetry mappers get unit tests in their respective `src/test/java` directories; playback, background, casting, and download flows rely on instrumentation via `connectedDebugAndroidTest` plus storage_component assets; integration tests ensure remote-config toggles and API models align with Media3 schemas. Test commands/logs must accompany the eventual PR.
- **Secure Configuration & Release Hygiene**: Media3 rollout toggle likely resides in `gradle.properties` or remote config; document any `IS_APPLICATION_RUN`/`IS_DEBUG_MODE` usage and keep secrets in `local.properties`. Telemetry endpoints and DRM keys remain untouched; ensure no Media3 credential data is committed.

### Constitution Check — Post-Design (2025-11-10)

- **Module-First MVVM Ownership**: `data-model.md` keeps Media3 adapters + capability contracts inside `player_component`, exposing them through ARouter so the streaming features in `anime_component` (standing in for `stream_component`) and download/torrent features in `storage_component` + `local_component` (standing in for `download_component`) remain decoupled; telemetry/entity changes land inside `data_component`.
- **Verified Gradle Workflow**: `quickstart.md` now prescribes `clean build`, `lint`, `testDebugUnitTest`, and `connectedDebugAndroidTest` prior to rollout plus `dependencyUpdates` whenever Media3 versions change.
- **Kotlin Style Contract**: No new UI shells were introduced in Phase 1, but the design reiterates that any upcoming fragments/viewmodels must remain under the existing naming/packaging conventions.
- **Evidence-Driven Testing Gates**: Documentation outlines unit coverage (toggle mappers, capability adapters) and instrumentation coverage (playback, casting, download resume) with explicit Gradle commands, satisfying the testing gate.
- **Secure Configuration & Release Hygiene**: The rollout toggle is immutable per session (`RolloutToggleSnapshot`) and sourced from Remote Config or a gradle fallback; secrets stay external to source control.

## Project Structure

### Documentation (this feature)

```text
specs/001-migrate-media3/
├── plan.md         # Implementation plan (this file)
├── research.md     # Phase 0 research output
├── data-model.md   # Phase 1 entity definitions
├── quickstart.md   # Phase 1 integration steps
├── contracts/      # Phase 1 API contract artifacts
└── tasks.md        # Phase 2 execution tracker (created later)
```

### Source Code (repository root)

```text
.
├── app/
├── anime_component/
├── player_component/
├── local_component/
├── storage_component/
├── user_component/
├── common_component/
├── data_component/
├── buildSrc/
└── document/ | scripts/ | repository/
```

*Note*: The historical `stream_component` / `download_component` directories referenced in the constitution are represented in this workspace by `anime_component` (streaming flows) plus `storage_component` & `local_component` (download/torrent flows). Tasks reference these concrete modules while keeping the same ownership boundaries.

**Structure Decision**: The migration stays inside the existing multi-module Android layout—`player_component` owns Media3 integration, feature modules invoke it via ARouter/services, shared utilities live in `common_component`, and repositories/entities update inside `data_component`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _None_    | -          | -                                   |
