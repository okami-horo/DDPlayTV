 # Tech Context

 ## Platform
 - OS/workspace: Windows, repo at `E:\Project\DDPlayTV`.
 - Android project, Kotlin `1.9.25`, JVM target `1.8`.
 - Android Gradle Plugin `8.7.2`, Gradle `8.9`.

 ## Main Libraries
 - AndroidX Lifecycle/ViewModel, Room, Preference, RecyclerView, Startup.
 - Kotlin Coroutines.
 - ARouter.
 - Retrofit, OkHttp, Moshi.
 - MMKV.
 - Media3, VLC, mpv and danmaku/subtitle rendering. ExoPlayer 2 dependency constants remain but are not referenced by current module build declarations.
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
