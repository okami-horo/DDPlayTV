 # Product Context

 ## Why This Exists
 DDPlayTV provides a living-room media workflow for TV users who need local playback, storage browsing, casting, anime information discovery, and settings in one Android app.

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
 - TV is the sole maintained UI target (user decision, 2026-10-29). Ordinary Android boxes use the same remote controls regardless of system UI mode.

 ## Product Constraints
 - Product UI behavior must not depend on system television detection. Keep actual input-mode handling where needed for keyboard/mouse or DPAD transitions.
 - Preserve default focus resources and optional Leanback installation support for boxes without standard TV features.
 - Bilibili functionality, including its danmaku source, is retired globally in favor of standalone PiliPlus (2026-10-28). Dandanplay matching and local danmaku files remain in scope.
