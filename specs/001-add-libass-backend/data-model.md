# Data Model: Subtitle Backend Selection & Session State

## Entities

Entity: RendererPreference
- id: string = "subtitle.renderer.backend"
- selectedBackend: enum [LEGACY_CANVAS, LIBASS]
- source: enum [DEFAULT, LOCAL_SETTINGS]
- updatedAtEpochMs: long

Validation
- selectedBackend must be one of defined enum values
- updates allowed only via Settings UI or internal fallback handler

Entity: PlaybackSession
- sessionId: string (UUID)
- resolvedBackend: enum [LEGACY_CANVAS, LIBASS]
- videoSizePx: { width: int, height: int }
- surfaceType: enum [TEXTURE_VIEW, SURFACE_VIEW]
- fallbackTriggered: boolean
- fallbackReasonCode: enum [INIT_FAIL, RENDER_FAIL, UNSUPPORTED_FORMAT, USER_REQUEST]
- lastErrorMessage: string?
- startedAtEpochMs: long
- endedAtEpochMs: long?

Validation
- resolvedBackend equals RendererPreference at session start unless explicit user override in-session
- if fallbackTriggered == true then fallbackReasonCode is required

Relationships
- RendererPreference (1) — (N) PlaybackSession by time; preference change affects future sessions only

State Transitions
- CREATED → RUNNING → (FALLBACK_PENDING | COMPLETED)
- FALLBACK_PENDING → RUNNING (after user confirms) | COMPLETED

