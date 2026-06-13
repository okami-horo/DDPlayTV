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
 - Media3, ExoPlayer 2, VLC, danmaku/subtitle rendering.
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
 - SDK: `C:\Android\Sdk`.
 - AVD: `Pixel_5_API_34`.
 - Known stale state may show `emulator-5554 offline`; reset adb before instrumentation if needed.
 - Emulator path: `C:\Android\Sdk\emulator\emulator.exe -avd Pixel_5_API_34`.

 ## Tooling Notes
 - Prefer specialized file tools over shell for file read/write/search.
 - Use `rg` tool for text search, `Glob` for filename search.
 - For Bilibili source-code structure questions, prefer ast-grep workflow when feasible.
 - Final user responses must be Simplified Chinese; internal docs for agents may be English.
