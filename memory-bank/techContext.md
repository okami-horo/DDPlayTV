 # Tech Context

 ## Platform
 - OS/workspace: Windows, repo at `E:\Project\DDPlayTV`.
 - Android project, Kotlin `2.2.10`, JVM target `1.8` (Media3 upgrade, 2026-10-28).
 - Android Gradle Plugin `8.10.1`, Gradle `8.11.1`, JDK 17; compileSdk 36, targetSdk 35, minSdk 23.

 ## Main Libraries
 - AndroidX Lifecycle/ViewModel, Room, Preference, RecyclerView, Startup.
 - TV navigation: DpadRecyclerView `1.4.3`, exposed by core UI. The original rewrite's Kotlin 1.9.25 / stdlib 2.0.0 and isolated R8 checks are historical; the Media3 upgrade now resolves stdlib 2.2.10. Main-app Release still has minify disabled and falls back to debug signing without release credentials.
 - Kotlin Coroutines.
 - ARouter.
 - Retrofit, OkHttp, Moshi `1.15.1`, Room `2.6.1`. Moshi and Room codegen use KSP `2.2.10-2.0.2` with `ksp.useKSP2=false` because Moshi 1.x custom qualifiers fail with KSP2. ARouter/MMKV/DataBinding retain KAPT. Database schema remains version 17 with no exported changes. See the Media3 upgrade ledger for failure evidence and compatibility tests.
 - MMKV.
 - Media3 `1.11.1` (single version source: `gradle.properties`), VLC, mpv and danmaku/subtitle rendering. The libass bridge retains raw ASS decoding with a method-scoped Media3 `ExperimentalApi` opt-in. ExoPlayer 2 dependency constants remain but are not referenced by current module build declarations.
 - NanoHTTPD/local proxy-related storage/playback components.
 - Robolectric `4.16.1`, JUnit4, AndroidX Test.

 ## Build Commands
 - `./gradlew assembleDebug` for fast developer build.
 - `./gradlew assembleRelease` for optimized release artifacts.
 - `./gradlew clean build` for full validation.
 - `./gradlew dependencyUpdates` for dependency report.
 - `./gradlew verifyModuleDependencies` for dependency governance.
 - `./gradlew testDebugUnitTest` for JVM tests.
 - `./gradlew connectedDebugAndroidTest` for instrumentation.

 ## Local AVD Baseline
 - Latest TV-rewrite verification (2026-10-28): `scripts/dev-env/gradlew-local.bat` works with the project-private toolchain and offline Robolectric cache. Device `emulator-5558` was restored to mobile UI mode `0x11`, physical 1080x2340 / 440 dpi and non-root adbd after temporary TV tests; the task-created TV feature file is absent. Its captured pre-test baseline already had SystemUI and lockscreen disabled; those values were preserved. Exact readback is `.tmp/tv-dpad-avd-restored.log`; test scope is in `docs/superpowers/plans/2026-10-28-tv-dpad-library-rewrite.md`. Query current device state rather than assuming this historical readback remains current.
 - Current verified workflow (2026-10-27): project-local `.dev-env/jdk/ms-17.0.15`, `.dev-env/gradle-home` and `.dev-env/android-sdk`; set JAVA_HOME, GRADLE_USER_HOME, ANDROID_HOME and ANDROID_SDK_ROOT, then run gradlew.bat directly. The PowerShell wrapper is blocked by local execution policy.
 - Historical device verification used API 34 `emulator-5558`, including temporary TV UI/feature injection. Those AVD modifications were reverted after final 2026-10-28 testing. DIR-01 cleanup preserved the working emulator and removed a separate runaway emulator writer; query current device/UI state instead of assuming TV mode remains enabled.
 - User-authorized deletion of the self-loop portproxy `*:5555 -> 127.0.0.1:5555` restored ADB/Gradle connectivity. Backup/restore records remain under `.tmp/portproxy-*`; do not repeat UAC or alter unrelated system networking.
 - SDK: `C:\Android\Sdk`.
 - AVD: `Pixel_5_API_34`.
 - Known stale state may show `emulator-5554 offline`; reset adb before instrumentation if needed.
 - Emulator path: `C:\Android\Sdk\emulator\emulator.exe -avd Pixel_5_API_34`.

 ## Tooling Notes
 - In the 2026-10-28 CLI environment, `rg` and a registered Python runtime were unavailable on PATH; `node`, Git and targeted PowerShell reads worked. A task-local Python existed at `.tmp/lease-fixtures/python-test/python.exe`; this ignored temporary path is not a portable project prerequisite.
 - Windows console pipelines can replace Chinese with literal ASCII question marks while leaving valid UTF-8. Use direct Unicode patch writes for documentation and inspect actual CJK content, not only decoding success.
 - Current developer environment commands and SDK/NDK requirements are maintained in `document/project_local_dev_env.md`; do not duplicate a stale setup recipe here.
