 # Product Context

 ## Why This Exists
 DDPlayTV provides a living-room media workflow for TV users who need local playback, storage browsing, casting/streaming, anime resource discovery, and settings in one Android app.

 ## Problems Solved
 - Remote-controlled media navigation where touch is unavailable or secondary.
 - Unified entry points for local media, storage sources, and player settings.
 - Clear separation between playback capabilities, media sources, and UI shell.
 - Reusable infra for network, DB, system runtime, logging, storage, and contracts.

 ## UX Goals
 - Primary interactions must work with DPAD only.
 - Focus state must be obvious and consistent across lists, grids, dialogs, and settings.
 - Back behavior should close transient UI first, then navigate back, then exit only when stack is empty.
 - TV input should avoid soft-keyboard-dependent critical flows; prefer stepper/toggle/selection controls.
 - Mobile should remain usable, but TV mode drives interaction design.

 ## Product Constraints
 - Runtime TV detection should use shared `Context.isTelevisionUiMode()` from `core_ui_component`.
 - TV-only disablement must be explicitly gated by TV UI mode; global disablement must be a clear product decision.
 - Bilibili functionality, including its danmaku source, is retired globally in favor of standalone PiliPlus (2026-10-28). Dandanplay matching and local danmaku files remain in scope.
