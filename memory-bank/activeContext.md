 # Active Context

## Current Focus
Memory Bank has been initialized/refreshed for the current session.

 ## Active Decisions
 - Communication is in caveman mode until user requests normal mode.
 - Final responses to user must be Simplified Chinese.
 - Memory Bank must be read at start of every task.
 - Documentation files here are agent-facing and written in English.

## Recent Events
1. 2026-06-06: Initialized Memory Bank core files from repository/user rules.
2. 2026-06-06: Added a player bottom-bar subtitle quick toggle design/plan and implemented UI/controller/resource changes; unrelated staged playback completion changes were committed separately as `0daecc89a`.
3. 2026-06-13: Re-read all Memory Bank core files and confirmed the core structure is present for session initialization.
4. 2026-06-13: Implemented Danmu match optimization plan with TDD: Dandanplay full match params, source metadata, exact/fail cache, coordinator, caller adaptation, hash concurrency fix, and TV subtitle action compile fix.

## Next Steps
- For next task, read all Memory Bank core files first.
- If task touches Bilibili media/library logic, inspect `E:\Project\PiliPlus` before editing DDPlayTV.
 - Run Gradle verification suited to changed scope and inspect output tail before reporting; current danmu match optimization work used `assembleDebug`, `testDebugUnitTest`, and `verifyModuleDependencies` with parsed `BUILD SUCCESSFUL` confirmation.

 ## Open Considerations
 - Danmu match optimization remains uncommitted on `master` per user approval to work in-place.
 - No known failing build/test status after the 2026-06-13 danmu match optimization verification.
