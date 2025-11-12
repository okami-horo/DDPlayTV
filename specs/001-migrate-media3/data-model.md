# Phase 1 Data Model — Media3 Playback Migration

## Overview
The Media3 migration introduces a unified player capability surface consumed by multiple feature modules. The model must capture playback session lifecycle, capability negotiation, telemetry, rollout toggles, and offline asset validation so FR-001 through FR-008 remain testable.

## Entities

### PlaybackSession
Represents every user-visible playback attempt regardless of source (stream, download, preview, cast, background).

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | UUID | Stable identifier shared across modules, notifications, and telemetry payloads. |
| `mediaId` | String | Anime/episode identifier or download item id. Required for resuming and analytics. |
| `sourceType` | Enum (`STREAM`, `DOWNLOAD`, `CAST`, `PREVIEW`) | Determines which module supplied the media item. |
| `playerEngine` | Enum (`EXO_LEGACY`, `MEDIA3`) | Records which player created the session (for rollout cohorts). |
| `toggleCohort` | Enum (`CONTROL`, `TREATMENT`, `ROLLBACK`) | Flagging remote-config bucket for post-launch analysis. |
| `codecSelection` | Object | Video/audio codecs negotiated (profile, level, drm keys). |
| `bitrateProfile` | Object | Adaptive bitrate ladder currently selected (min/max/target). |
| `playbackState` | Enum (`INITIALIZING`, `READY`, `PLAYING`, `BUFFERING`, `PAUSED`, `COMPLETED`, `FAILED`) | High-level state mirrored to widgets and telemetry. |
| `positionMs` | Long | Last confirmed playback position. Required for background + download resume. |
| `bufferedMs` | Long | Buffered duration surfaced to background widgets for UI parity. |
| `networkStats` | Object | Rolling average throughput, packet loss. Mandatory for adaptive behavior at <1 Mbps. |
| `subtitleTrack` | Object | Currently selected subtitle/danmaku metadata (language, format, offset). |
| `availableTracks` | Array | Audio/subtitle variants exposed through the capability contract. |
| `errors` | Array | Previous fatal/non-fatal errors (code, message, fatal flag). |
| `startedAt` | Instant | UTC timestamp when playback command was issued. |
| `firstFrameAt` | Instant | Timestamp when first frame rendered; used to validate SC-001. |
| `isOfflineCapable` | Boolean | Indicates if the source may fall back to offline audio-only when codec unsupported. |

**Relationships & Rules**
- `PlaybackSession` owns the authoritative state consumed by widgets, casting, downloads, and telemetry.
- `TelemetryEvent.sessionId` references `PlaybackSession.sessionId`.
- `PlayerCapabilityContract.sessionId` references `PlaybackSession` to scope available commands.
- Validation: `firstFrameAt - startedAt <= 2000 ms` for ≥95% of P1 sessions; fallback message triggered otherwise.

**State Transitions**
```
INITIALIZING -> READY -> PLAYING -> (BUFFERING <-> PLAYING)* -> (PAUSED|COMPLETED|FAILED)
```
- Transition to `FAILED` requires populating `errors[-1]`.
- Rollout toggle changes only apply on transitions from `INITIALIZING` to `READY` (ensures mid-session stability).

### PlayerCapabilityContract
Defines the command surface consumed by feature modules so no caller touches raw Media3 APIs.

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | UUID | Links contract to a `PlaybackSession`. |
| `capabilities` | Array<Enum> | List of supported commands (PLAY, PAUSE, SEEK, SPEED, SUBTITLE, AUDIO_TRACK, BACKGROUND, PIP, CAST, DOWNLOAD_VALIDATE). |
| `seekIncrementMs` | Int | Default seek stride for UI surfaces. |
| `speedOptions` | Array<Float> | Supported playback speeds (e.g., 0.5x–3x). |
| `subtitleLanguages` | Array | Available subtitle language codes + formats. |
| `backgroundModes` | Array<Enum> | Modes allowed (`AUDIO_ONLY`, `PIP`, `NOTIFICATION`). |
| `castTargets` | Array | Available cast devices, including metadata needed for Media3 `MediaRouter`. |
| `offlineSupport` | Object | Flags for `DOWNLOAD_RESUME`, `VERIFY_BEFORE_PLAY`, `AUDIO_FALLBACK`. |
| `sessionCommands` | Array | Custom MediaSession commands (e.g., `SWITCH_PLAYER_ENGINE`) for remote config toggles. |

**Rules**
- If `playerEngine == MEDIA3`, `capabilities` must include `SESSION_COMMANDS` so notifications/widgets can route MediaSession custom commands.
- Modules must treat missing capabilities as unsupported operations (no fallback to legacy shortcuts).

### TelemetryEvent
Captures Media3-specific observability for playback, downloads, and toggles.

| Field | Type | Description |
| --- | --- | --- |
| `eventId` | UUID | Unique log id. |
| `sessionId` | UUID | Foreign key to `PlaybackSession`. |
| `eventType` | Enum (`STARTUP`, `FIRST_FRAME`, `BUFFERING`, `ERROR`, `TOGGLE_CHANGE`, `DOWNLOAD_VALIDATION`, `CAST_TRANSFER`) | Categorizes the metric. |
| `timestamp` | Instant | Event creation time. |
| `playerEngine` | Enum | Copied from session for analyzer filters. |
| `media3Version` | String | `androidx.media3` semantic version used. |
| `metrics` | Map | Arbitrary numeric measurements (startupMs, bufferedRatio, bitrate, network, errorCode). |
| `deviceInfo` | Object | OS/build/device class used for distinguishing low-end cohorts. |
| `isForeground` | Boolean | Distinguishes background playback instrumentation. |

**Rules**
- STARTUP and FIRST_FRAME events are mandatory for every session.  
- ERROR events must include `metrics.errorCode` from Media3’s `PlaybackException`.  
- Telemetry must be emitted for both `EXO_LEGACY` and `MEDIA3` to compare cohorts during rollout.

### RolloutToggleSnapshot
Represents the evaluated toggle state when a session is created.

| Field | Type | Description |
| --- | --- | --- |
| `snapshotId` | UUID | Unique evaluation id. |
| `flagName` | String | Usually `media3_enabled`. |
| `value` | Boolean | Result applied to the session. |
| `source` | Enum (`REMOTE_CONFIG`, `GRADLE_FALLBACK`, `MANUAL_OVERRIDE`) | Where the value originated. |
| `evaluatedAt` | Instant | Timestamp of evaluation. |
| `appliesToSession` | UUID | Session that consumed the value. |

**Rules**
- Once recorded, `value` remains immutable for the session lifetime; mid-session flips spawn new sessions only.
- Stored in `data_component` for audit + ops dashboards.

### DownloadAssetCheck
Encapsulates offline verification required before exposing Play on downloaded items.

| Field | Type | Description |
| --- | --- | --- |
| `downloadId` | UUID | Download job id referencing `download_component`. |
| `mediaId` | String | Same as `PlaybackSession.mediaId`. |
| `lastVerifiedAt` | Instant | Timestamp of last Media3 compatibility check. |
| `isCompatible` | Boolean | Result of running Media3 extractor/probe. |
| `requiredAction` | Enum (`NONE`, `REVALIDATE`, `REDOWNLOAD`, `AUDIO_ONLY_FALLBACK`) | Downstream handling. |
| `verificationLogs` | Array | Errors/warnings captured during check. |

**Rules**
- Must run when a download completes and again before playback if `lastVerifiedAt` > 7 days or Media3 version changed.
- `requiredAction != NONE` blocks the UI Play button until resolved.

## Relationships Summary
- `PlaybackSession 1 - * TelemetryEvent`
- `PlaybackSession 1 - 1 PlayerCapabilityContract`
- `PlaybackSession 1 - 1 RolloutToggleSnapshot`
- `DownloadAssetCheck` links to `PlaybackSession` when offline playback begins.

## Validation & Testing Notes
- Unit tests should cover entity mappers between legacy Exo models and Media3 equivalents, ensuring `playerEngine` and `toggleCohort` propagate correctly.
- Integration tests assert that offline checks fail fast when `isCompatible == false` and that telemetry emits the `media3Version` for every session.

