 # Progress

## Current Status
- Memory Bank core structure exists and was refreshed for the 2026-06-13 session.
- Player subtitle quick toggle implementation is complete in `:player_component` and remains uncommitted.
- Danmu match optimization implementation is complete and uncommitted.

## What Works
- Project rules define module layout, dependency governance, TV UX rules, testing expectations, and Bilibili reference workflow.
- Known build/test commands documented in Memory Bank.
- Player bottom bar now has a subtitle quick toggle design/plan and code changes for UI resources, controller API, visibility state, action routing, and DPAD focus skipping when no selected subtitle track exists.
- Dandanplay danmu matching now passes hash, filename, file size, duration, and match mode via source metadata.
- Player auto-match now uses `DanmuMatchCoordinator`; exact matches download automatically, fuzzy candidates stay manual-only, and Bilibili live switch semantics are preserved.
- Exact match success/fail marker cache exists for auto-match, with tests covering model parsing, source metadata, hash concurrency, finder semantics, and coordinator fuzzy behavior.

## Remaining
- Optional: run a hands-on player DPAD smoke test with a known subtitle-bearing media item.
- Optional: run a hands-on danmu auto-match smoke test with a known local video and Dandanplay match.

## Known Issues
- DPAD smoke was not proven against an active playback screen in this session.

## Verification
- IDE diagnostics for changed files reported no issues.
- `assembleDebug` was run and parsed as `BUILD SUCCESSFUL`.
- No Gradle build was run for the 2026-06-13 Memory Bank refresh because only documentation was updated.
- For danmu match optimization, IDE diagnostics reported no new issues.
- `assembleDebug`, `testDebugUnitTest`, and `verifyModuleDependencies` were run and parsed as `BUILD SUCCESSFUL`.
