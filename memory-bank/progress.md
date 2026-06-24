 # Progress

## Current Status
- Memory Bank core structure exists and was refreshed for the 2026-06-13 session.
- Player subtitle quick toggle implementation is complete in `:player_component` and remains uncommitted.
- Danmu match optimization implementation is complete and uncommitted.
- TV DPAD focus architecture work is implemented on branch `feat/tv-dpad-focus-architecture` and remains uncommitted.

## What Works
- Project rules define module layout, dependency governance, TV UX rules, testing expectations, and Bilibili reference workflow.
- Known build/test commands documented in Memory Bank.
- Player bottom bar now has a subtitle quick toggle design/plan and code changes for UI resources, controller API, visibility state, action routing, and DPAD focus skipping when no selected subtitle track exists.
- Dandanplay danmu matching now passes hash, filename, file size, duration, and match mode via source metadata.
- Player auto-match now uses `DanmuMatchCoordinator`; exact matches download automatically, fuzzy candidates stay manual-only, and Bilibili live switch semantics are preserved.
- Exact match success/fail marker cache exists for auto-match, with tests covering model parsing, source metadata, hash concurrency, finder semantics, and coordinator fuzzy behavior.
- TV DPAD focus now has additive core skeleton types (`DpadDirection`, `DpadFocusOwner`, `DpadFocusScope`, `DpadRecyclerFocusDelegate`, `DpadDialogFocusController`) plus `TvShellFocusCoordinator` in `:app`.
- TV shell, bottom dialogs, media list, storage file list, anime home grid page, and player setting overlay have first-stage DPAD focus ownership/default/restore integration.
- AndroidTest smoke coverage exists for TV shell nav/content focus and bottom-action dialog show/dismiss focus restore.

## Remaining
- Optional: run a hands-on player DPAD smoke test with a known subtitle-bearing media item.
- Optional: run a hands-on danmu auto-match smoke test with a known local video and Dandanplay match.
- Optional: run `connectedDebugAndroidTest` on a TV-mode device/emulator for the new DPAD smoke tests; current ADB inventory showed one physical `device` and one `emulator-5554 offline`, but no TV-mode instrumentation run was completed.
- Continue P2/P3 migration list for remaining RecyclerViews/dialogs/`clearFocus()` call sites not covered by the first TV DPAD focus pass.

## Known Issues
- Hands-on DPAD smoke remains unproven against an active playback screen.
- TV-mode instrumentation was not run for the new DPAD smoke tests in this session.

## Verification
- IDE diagnostics for changed files reported no issues.
- `assembleDebug` was run and parsed as `BUILD SUCCESSFUL`.
- No Gradle build was run for the 2026-06-13 Memory Bank refresh because only documentation was updated.
- For danmu match optimization, IDE diagnostics reported no new issues.
- For danmu match optimization, `assembleDebug`, `testDebugUnitTest`, and `verifyModuleDependencies` were run and parsed as `BUILD SUCCESSFUL`.
- For TV DPAD focus architecture, `:core_ui_component:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `verifyModuleDependencies`, and `assembleDebug` were run and parsed as `BUILD SUCCESSFUL`.
