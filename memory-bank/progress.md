# Progress

## Latest Recorded Validation

### Playback history repair (2026-10-31)

Confirmed cause: `PlayerActivity.finish()` detached/released the backend before Android delivered `onPause()`, which called `DanDanVideoPlayer.recordPlayInfo()` again. The retained source then wrote position/duration `0/0` over the valid history. The guard in [DanDanVideoPlayer](../player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt) now skips idle players; valid zero positions and error-position capture remain supported. Main fix: `80839fbed`; the instrumented test subsequently received BACK timing and failure-diagnostic refinements.

- Regression evidence: [DanDanVideoPlayerHistoryTest](../player_component/src/test/java/com/xyoye/player/DanDanVideoPlayerHistoryTest.kt) failed 2/4 against the original code (`.tmp/play-history-repro-build.log`, **BUILD FAILED in 31s**), including an extra `(0, 0)` save after `(18000, 60000)`. All four pass after the guard. The original Media3 browser run also saved zero (`.tmp/play-history-device-red.log`).
- Debug/test APK build: `.tmp/play-history-green-build.log`, **BUILD SUCCESSFUL in 36s**. Full lint, scoped three-file ktlint, dependency governance and latest test APK: `.tmp/play-history-lint-device-build2.log`, **BUILD SUCCESSFUL in 4m 9s**.
- Full JVM and final scoped ktlint: `.tmp/play-history-short-temp-check.log`, **BUILD SUCCESSFUL in 20s**; XML readback counted 116 suites, **480 passed, 2 existing external-fixture skips, 0 failures/errors** (`.tmp/play-history-jvm-results.json`). Initial full runs failed only the existing `RoomSchemaMigrationGateTest.migration19RetiresStreamsTorrentsAndSearchWithoutChangingOtherData` with `SQLiteCantOpenDatabaseException`. Changing only that module's test `java.io.tmpdir` to the shorter workspace `.tmp/rt` resolved it; no database business/test code was changed. Local init scripts: `.tmp/ui-repairs-local.gradle`, `.tmp/tmdb-validation.gradle`, `.tmp/play-history-short-temp.gradle`, `.tmp/play-history-style.gradle`.
- Actual local-library playback: [FileBrowserPlaybackReturnInstrumentedTest](../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/FileBrowserPlaybackReturnInstrumentedTest.kt), direct runner on task-owned `emulator-5562`, API 34, **OK (2 tests)** in 21.85s (`.tmp/play-history-device-green2.log`). Both actual kernels seek near 8s, pause, return with BACK, preserve Room position/duration, reselect the same browser item with DPAD and resume within the saved-position range. Browser preview/focus and the global kernel preference are checked. Earlier device runs exposed test BACK timing exceeding the 1500ms exit-confirmation window; the final helper groups those presses before waiting for UI idleness.

Verification covers the local-library source and shared history-recording boundary. Physical-TV/remote-protocol acceptance and complete process-restoration coverage remain open. The task AVD was closed after testing; the TV's earlier Release installation remains the separate delivery below.

### Earlier delivery and UI validation

2026-10-31 Release delivery at user request, source `779ada366` (clean worktree before build): `scripts/dev-env/gradlew-local.bat :app:assembleRelease --offline --console=plain` ended **BUILD SUCCESSFUL in 1m 55s**; log `.tmp/tv-release-build.log`. Installed `app/build/outputs/apk/release/ddplaytv_v4.2.2_arm64-v8a-release.apk` (77,509,705 bytes) using `adb -s 192.168.31.70:5555 install -r`; result **Success**, preserving existing app data. Package readback confirmed `com.okamihoro.ddplaytv`, version `4.2.2`/code `69`, primary ABI `arm64-v8a`, lastUpdateTime `2026-10-31 23:11:12`, and no DEBUGGABLE flag. No application launch, TV navigation/playback, visual acceptance or new test run was performed.

The completed UI work is now committed as `b0985b9b6` (Git checked 2026-10-31). Its implementation-run evidence is in the [R01-R07 ledger](../docs/superpowers/plans/2026-10-31-ui-polish.md): Debug/test APKs, full JVM/lint, 19-file ktlint and dependency governance ended **BUILD SUCCESSFUL in 2m 45s**; 474 JVM passes, two existing external-fixture skips; direct AVD runner **19/19**. Native/prototype screenshots were inspected. Cloud/account data used local fixtures; real Media3/mpv playback preceded injected feedback checks.

This is retained evidence, not a rerun during memory consolidation. Open implementation/acceptance/delivery work has one current summary in [activeContext](activeContext.md).

## Completed-work Archive

[Completed work through 2026-10-31](archive/2026-10-completed-work.md) preserves dated results, failed attempts, superseded decisions and original evidence links. Read only the relevant section:

| Need | Archive section |
| --- | --- |
| UI corrections, C, R01-R07, TMDB, kernel/platform migrations, retirements and documentation cleanup | [Implementation journal](archive/2026-10-completed-work.md#implementation-journal) |
| Unified comment API, Long comment IDs, v2 search, real-service checks and ktlint failure boundaries | [Dandanplay retrieval](archive/2026-10-completed-work.md#dandanplay-retrieval-migration-2026-10-31) |
| Anime4K removal and the subsequently retired VLC failure | [Anime4K evidence](archive/2026-10-completed-work.md#anime4k-removal-verification-2026-10-28) |
| Handoff/transport implementation stages and historical counts | [Playback history](archive/2026-10-completed-work.md#playback-implementation-history) |
| Global-settings migration or minSdk 25 verification | [Additional records](archive/2026-10-completed-work.md#additional-completed-work-records) |
| Old AVD/display/toolchain/network workarounds | [Historical environments](archive/2026-10-completed-work.md#historical-local-test-environments) |

No task is reopened solely because its archived entry says "next" or "deferred". No unchecked real-service/device scenario is closed solely because implementation history was archived. Current technical facts live in the other core memory files and maintained manuals.
