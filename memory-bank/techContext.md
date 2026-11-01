 # Tech Context

 ## Platform
 - OS/workspace: Windows, repo at `E:\Project\DDPlayTV`.
 - Android project, Kotlin `2.2.10`, JVM target `1.8` (Media3 upgrade, 2026-10-28).
 - Android Gradle Plugin `8.10.1`, Gradle `8.11.1`, JDK 17; compileSdk 36, targetSdk 35, minSdk 25 (Android 7.1; raised on 2026-10-29 in `buildSrc/src/main/java/Versions.kt`).

 ## Main Libraries
 - AndroidX Lifecycle/ViewModel, Room, Preference, RecyclerView, Startup.
 - TV navigation: DpadRecyclerView `1.4.3`, exposed by core UI. The original rewrite's Kotlin 1.9.25 / stdlib 2.0.0 and isolated R8 checks are historical; the Media3 upgrade now resolves stdlib 2.2.10. Main-app Release still has minify disabled and falls back to debug signing without release credentials.
 - Kotlin Coroutines.
 - ARouter.
 - Retrofit, OkHttp, Moshi `1.15.1`, Room `2.6.1`. Moshi and Room codegen use KSP `2.2.10-2.0.2` with `ksp.useKSP2=false` because Moshi 1.x custom qualifiers fail with KSP2. ARouter/MMKV/DataBinding retain KAPT. Current database schema is version 22 after the 2026-11-01 TV cleanup; see persistence details below. The Media3 upgrade ledger retains the processor migration's failure evidence and compatibility tests.
 - MMKV.
 - Media3 `1.11.1` (single version source: `gradle.properties`, default player), mpv and danmaku/subtitle rendering. VLC/libVLC was removed on all devices on 2026-10-29. The libass bridge retains raw ASS decoding with a method-scoped Media3 `ExperimentalApi` opt-in. ExoPlayer 2 dependency constants remain but are not referenced by current module build declarations.
 - mpv now comes from Plezy's pinned original Android AAR via `repository:mpv_native`, with a Java-only `repository:mpv` ABI wrapper keeping upstream Kotlin 2.4 metadata off the Kotlin 2.2.10 compile classpath. Debug/release/beta variants are supported; no compiler metadata bypass or AAR rewriting. Source lock, SHA-256, native versions, license and upgrade procedure have one authoritative home in `document/mpv-build-notes.md`; migration validation is tracked in `docs/superpowers/plans/2026-10-29-plezy-mpv.md`. The app retains its independent libass/OpenCC libraries; old mpv JNI/headers/FFmpeg binaries are removed.
 - NanoHTTPD/local proxy-related storage/playback components.
 - mpv subtitles now use the pinned AAR's native OSD (`MPV_NATIVE` runtime backend); no new dependency/version is introduced. App libass remains for Media3. Source mapping, option limits and validation are in `document/player/subtitle-rendering.md` and `docs/superpowers/plans/2026-10-29-mpv-native-subtitles.md` (2026-10-29).
 - Robolectric `4.16.1`, JUnit4, AndroidX Test.

 ## Configuration and Persistence
 - Playback, subtitle, danmaku and browsing preferences use existing global MMKV-backed `PlayerConfig`, `SubtitleConfig`, `DanmuConfig` and `AppConfig` tables. Per-library player preferences are removed (2026-10-30). PC integration and its global grouping setting were retired on 2026-11-01; the old grouping key and PC credentials are cleared, while legacy Room rows/fields remain compatible and hidden from active lists. That PC retirement needed no schema change; the subsequent Media3 cleanup below uses schema 22. Current-session actual kernel and temporary Media3 retry remain runtime state.
 - `DatabaseInfo` is version 22. `MIGRATION_21_22` drops only `media3_rollout_snapshot` and `media3_download_asset_check`; the other 15 entity definitions match schema 21. RoomSchemaMigrationGateTest covers empty/populated upgrades, all retained rows/table/index SQL/sequences, legacy PC rows and a fresh database. Current schema: `core_database_component/schemas/com.xyoye.common_component.database.DatabaseInfo/22.json`; cleanup evidence: `docs/superpowers/plans/2026-11-01-tv-scope-cleanup.md`.
 - Earlier migrations remain intact: `MIGRATION_20_21` additively stores binding attemptedAt and playback task triggerKey/triggerPath/triggerName; `MetadataMigration` adds metadata in 18->19; `MIGRATION_19_20` removes stream/magnet libraries and their history, two search tables and two torrent history columns while preserving retained identities/indexes/sequence high-watermarks. Their historical schema JSON files remain. Metadata implementation: `docs/superpowers/plans/2026-10-31-tmdb-media-library.md` and `docs/superpowers/plans/2026-11-01-playback-auto-metadata.md`; stream/magnet retirement evidence: `docs/superpowers/plans/2026-10-31-remove-stream-magnet.md`.
 - A01–A08 removes the direct Media3 WorkManager/session/Cast, Jsoup and legacy MultiDex-install dependencies and the isolated `panel_switch` wrapper (21 included modules remain). `multiDexEnabled=true`, Media3 UI, subtitle encoding libraries, protocol parsers, casting reception and ordinary caching remain. No library version upgrade is part of this cleanup.

 - TMDB API token is injected into core_system BuildConfig from environment, Gradle property or local.properties (`TMDB_READ_ACCESS_TOKEN`); missing configuration leaves file playback available. The independent API client fixes HTTPS api.tmdb.org (changed 2026-11-01 after original-host connectivity failures), has two request permits and a 16 MiB private HTTP cache. Authorization remains restricted to that exact HTTPS host, with redirects disabled. Language is fixed zh-CN with missing-text fallback; no runtime language switch/revision is exposed. Live-service evidence and delivery status are in the [all-source ledger](../docs/superpowers/plans/2026-11-01-all-source-auto-metadata.md).

 ## Build Commands
 - `./gradlew assembleDebug` for fast developer build.
 - `./gradlew assembleRelease` for release artifacts; current app minify/signing behavior is described above.
 - `./gradlew clean build` for full validation.
 - `./gradlew dependencyUpdates` for dependency report.
 - `./gradlew verifyModuleDependencies` for dependency governance.
 - `./gradlew testDebugUnitTest` for JVM tests.
 - `./gradlew connectedDebugAndroidTest` for instrumentation.

 ## Local Tooling and Device Verification

 - Maintained setup/SDK/NDK commands: [project-local environment](../document/project_local_dev_env.md). Recent recorded UI checks used `scripts/dev-env/gradlew-local.bat` and offline Robolectric artifacts; the exact init scripts and task scope are in the UI execution ledger.
 - Recorded local SDK/AVD: `C:\Android\Sdk`, `Pixel_5_API_34`. Latest UI work used task-owned read-only `emulator-5558`, API 34, SwiftShader/ARM64 translation at 1440x810 / 240dpi. On 2026-10-31 it was restored to 1080x2340 / 440dpi, night auto, and closed. This is a dated result, not current device inventory.
 - The latest direct instrumentation runner passed; prior Gradle connected runs could not resolve an offline UTP dependency. Keep runner/build evidence distinct and require a device in `device` state before runtime claims.
 - Cleanup validation (2026-11-01) used task-owned API 34 `DDPlayTVCleanup` / `emulator-5568`, private ADB 5051 at 1440x810 / 240dpi. Sixteen cases passed after confirming the first-fullscreen system guide; no application/assertion change was needed. The task AVD/private ADB were closed. Commands, host-stderr behavior and scope are in the [cleanup ledger](../docs/superpowers/plans/2026-11-01-tv-scope-cleanup.md), not a reusable device inventory.
 - ARouter 1.5.2 caches generated service classes by versionName/code. Same-version Release replacement can omit a newly added provider even though Debug playback tests pass. `BaseApplication` now invalidates only ARouter discovery/version keys when the installed package's lastUpdateTime changes, including the first migration of old caches. Validate updates using the explicit Release `RouterUpgradeInstrumentedTest` two-process probe; commands/evidence belong to the [all-source ledger](../docs/superpowers/plans/2026-11-01-all-source-auto-metadata.md).
 - Older TV-feature injection, display restoration and portproxy recovery are archived under [historical environments](archive/2026-10-completed-work.md#historical-local-test-environments). They are not setup steps to repeat automatically.

 ## Tooling Notes
 - In the 2026-10-28 CLI environment, `rg` and a registered Python runtime were unavailable on PATH; `node`, Git and targeted PowerShell reads worked. A task-local Python existed at `.tmp/lease-fixtures/python-test/python.exe`; this ignored temporary path is not a portable project prerequisite.
 - Windows console pipelines can replace Chinese with literal ASCII question marks while leaving valid UTF-8. Use direct Unicode patch writes for documentation and inspect actual CJK content, not only decoding success.
 - Current developer environment commands and SDK/NDK requirements are maintained in `document/project_local_dev_env.md`; do not duplicate a stale setup recipe here.
