# Feature Specification: Media3 Playback Migration

**Feature Branch**: `[001-migrate-media3]`  
**Created**: 2025-11-10  
**Status**: Draft  
**Input**: User description: "当前项目集成的exo player已经停止维护了，替换为media3.use exa 可以获取到详细信息、media3的文档。项目中集成的api也需要同步调整，以适配media3."

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.
  
  Assign priorities (P1, P2, P3, etc.) to each story, where P1 is the most critical.
  Think of each story as a standalone slice of functionality that can be:
  - Developed independently
  - Tested independently
  - Deployed independently
  - Demonstrated to users independently
-->

### User Story 1 - Seamless Media Playback (Priority: P1)

Anime viewers need every stream to open through the Media3-powered player so they can keep watching without noticing the underlying migration.

**Why this priority**: Playback is the core value of the app; any regression directly blocks daily viewing and spikes churn.

**Independent Test**: Launch three popular titles over Wi-Fi and cellular, verify the player stack reports Media3 usage, and confirm controls remain responsive for the entire episode.

**Acceptance Scenarios**:

1. **Given** a user selects any playable title, **When** playback begins, **Then** the session initializes via the Media3 stack and reaches the first frame within 2 seconds on supported devices.
2. **Given** network throughput fluctuates during playback, **When** bandwidth drops below 1 Mbps, **Then** the Media3-based adaptive streaming continues without force-closing or reverting to the deprecated player.

---

### User Story 2 - Consistent Controls Across Features (Priority: P2)

Users who switch between streaming, casting, downloads, and background modes expect identical controls and metadata because those flows now share the Media3 APIs.

**Why this priority**: Feature parity prevents user confusion and reduces support load when people move between modules.

**Independent Test**: Start playback, trigger background audio, return via notification controls, and confirm casting/download entry points expose the same state without referencing old Exo player callbacks.

**Acceptance Scenarios**:

1. **Given** playback is active, **When** the user enables background or PiP mode, **Then** controls, playback position, and artwork remain synchronized via Media3 session data across notifications, widgets, and casting targets.
2. **Given** a download finishes and the user resumes offline, **When** they press play, **Then** the offline item streams through the Media3 capability layer without requiring fallback APIs.

---

### User Story 3 - Operational Confidence & Monitoring (Priority: P3)

Support and ops teams need telemetry that distinguishes Media3 sessions from the retired stack so they can isolate regressions quickly.

**Why this priority**: Clean monitoring shortens incident response time during rollout spikes.

**Independent Test**: Run the playback regression suite and verify dashboards show Media3 identifiers, degraded sessions raise alerts, and no Exo-only metrics remain.

**Acceptance Scenarios**:

1. **Given** the regression suite runs nightly, **When** a Media3 playback failure occurs, **Then** the alert payload flags the session as Media3-based with the affected module so on-call staff can triage without inspecting device logs manually.

---

### Edge Cases

- Playback must degrade gracefully on devices lacking specific codecs supported by Exo but missing from Media3 defaults, providing a user-facing message and fallback audio-only mode.
- When API consumers still send legacy player capability flags, the adapter layer should translate them or reject with a clear error so downstream modules know migration status.
- If the migration switch flips mid-session (e.g., remote config rollout), the active session should finish on the original stack while new sessions start on Media3 to avoid user-visible interruption.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All playback entry points (streaming, downloads, previews, casting) MUST instantiate and control sessions via the Media3 APIs instead of the deprecated Exo-specific wrappers.
- **FR-002**: The player capability layer MUST expose a unified contract for play/pause/seek/speed/subtitle controls so feature modules can consume it without referencing legacy interfaces.
- **FR-003**: Data and repository modules MUST update any player-related API models (e.g., track info, DRM flags, playback telemetry) to the schemas expected by Media3 while keeping field naming consistent for clients.
- **FR-004**: The app MUST provide a rollout toggle that can enable/disable Media3 globally, allowing safe canary releases and instant rollback without app reinstallation.
- **FR-005**: Playback telemetry MUST log Media3-specific identifiers (player type, version, error codes) so monitoring distinguishes it from past Exo sessions.
- **FR-006**: Offline downloads and local playback MUST verify that downloaded assets remain compatible with the Media3 pipeline before exposing the “Play” action, re-downloading or warning the user otherwise.
- **FR-007**: Background playback, notification controls, widgets, and PiP MUST read session state from the Media3 session manager to remain in sync across UI surfaces.
- **FR-008**: API consumers that previously injected Exo-only configuration (e.g., custom renderers) MUST either be translated into Media3-compatible options or blocked with actionable error messaging documented for integrators.

### Non-Functional Requirements

- **NFR-001 (Startup Performance)**: Maintain first-frame render within 2 seconds for 95% of reference devices across Wi-Fi and cellular, matching SC-001 while documenting the measurement methodology in telemetry.
- **NFR-002 (Stability & Crash Budget)**: Playback-related crashes or ANRs must stay at/under 0.2% of daily sessions; instrumentation must tag Media3 sessions so Bugly/Firebase dashboards can segment the data automatically.
- **NFR-003 (Observability & Alerting)**: Using the identifiers mandated by **FR-005**, telemetry, crash, and alert payloads must include the Media3 rollout cohort, player type, and session IDs so ops can differentiate Media3 regressions from legacy Exo traffic without manual log pulls, and dashboards must surface first-frame, fallback, and crash rates per module.
- **NFR-004 (Support Readiness)**: Customer support tooling (ticket templates, FAQ, release notes) must expose Media3-specific troubleshooting steps and tag tickets with the rollout cohort so we can measure (via `document/support/media3-ticket-dashboard.md`) at least a 30% ticket reduction (SC-004) within one release cycle.
- **NFR-005 (Rollout Safety)**: The `media3_enabled` flag must be immutable for an active session (toggle snapshot) while allowing remote-config changes to affect only newly created sessions; if a user loses codec parity, the stack must surface audio-only fallback messaging without crashing.

### Key Entities *(include if feature involves data)*

- **Playback Session**: Represents an end-to-end viewing instance with attributes such as media identifier, codec selection, bitrate ladder, session state, and error surface, ensuring every module reads consistent data.
- **Player Capability Contract**: Describes the command set (play/pause/seek/speed/subtitle/toggle background) exposed by the player adapter and referenced by feature modules instead of raw player instances.
- **Telemetry Event**: Captures Media3-specific metrics (startup time, buffering ratio, failure category, rollout cohort) and links to device/user context for observability dashboards.
- **Legacy Capability Mapping**: Defines how Exo-only injected options (custom renderers, DRM overrides, timed text hooks) translate into Media3 parameters or actionable errors if unsupported, ensuring FR-008 is testable.

### API Contracts Overview *(reference `specs/001-migrate-media3/contracts/`)*

- **`/v1/media3/sessions`**: POST creates a session with playback metadata, DRM flags, and legacy capability hints; GET returns active session state aligned to `PlaybackSession`.
- **`/v1/media3/commands`**: POST relays capability commands (play, pause, seek, subtitle, audio-only fallback) using the unified contract payload.
- **`/v1/media3/telemetry`**: POST batches startup, first-frame, error, and cast-transfer events, including rollout cohort IDs for SC-001/SC-002 tracking.
- **`/v1/media3/rollout`**: GET returns the remote-config toggle plus segmentation bucketing; POST (admin only) overrides cohorts for canary testing.
- **`/v1/media3/downloads/validate`**: POST validates offline assets (codec, license freshness) and returns remediation instructions (re-download, warn, block).

Detailed schema examples live in `specs/001-migrate-media3/contracts/media3-*.*` and must stay consistent with the entities above.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of playback sessions reach first frame within 2 seconds on reference devices after the migration completes.
- **SC-002**: Playback-related crashes or ANRs remain at or below 0.2% of daily sessions during the first month post-launch.
- **SC-003**: At least 90% of regression test cases across player-dependent modules (streaming, downloads, casting, background) pass without referencing the legacy player as verified by `scripts/testing/media3-regression-report.sh`, which parses tagged `@Media3Dependent` suites and fails if the legacy delegate runs more than 10% of cases.
- **SC-004**: Playback-related customer support tickets decrease by 30% within one release cycle due to improved stability and monitoring, measured through the tagged cohorts and dashboards documented in `document/support/media3-ticket-dashboard.md`.

## Assumptions

- The migration replaces the legacy Exo integration entirely once the rollout toggle is switched on; dual-running both stacks is only for canary purposes.
- Media3 library versions will follow the latest stable AndroidX release supported by the app’s minSdk, ensuring security patches are available.
- Existing DRM licenses, subtitle formats, and content delivery endpoints remain unchanged; any incompatibility will be handled by re-encoding assets outside this scope.
