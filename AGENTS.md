<<<<<<< HEAD
# Repository Guidelines

## Project Structure & Module Organization
The app follows a modular MVVM layout: `app/` hosts the launcher shell, shared UI glue, and manifest; feature slices live in sibling directories (`anime_component/`, `player_component/`, `local_component/`, `storage_component/`, `stream_component/`, `user_component/`, `download_component/`). Foundation logic is centralized in `common_component/` (base classes, utilities) and `data_component/` (entities, repositories). Build tooling resides in `buildSrc/` and custom assets/scripts live under `document/`, `scripts/`, and `repository/`. Keep media or prompts within `Img/` or `prompts/` instead of polluting module folders.

## Build, Test, and Development Commands
Use Gradle from repo root:
- `./gradlew assembleDebug` – fast developer build with logging enabled.
- `./gradlew assembleRelease` – optimized, signed release artifacts.
- `./gradlew clean build` – full rebuild to validate cross-module wiring.
- `./gradlew dependencyUpdates` – report outdated libraries defined in `build.gradle.kts`.
- `./gradlew testDebugUnitTest` and `./gradlew connectedDebugAndroidTest` – run JVM unit tests and device/emulator instrumentation respectively.

### Build Verification Requirement
- Always read the tail of Gradle output and confirm whether it ends with `BUILD SUCCESSFUL` or `BUILD FAILED` before reporting status to the user. Do **not** assume success just because tasks ran; explicitly mention failures when they occur.

## Coding Style & Naming Conventions
Stick to Kotlin 1.7.x with 4-space indentation, explicit visibility, and trailing commas disabled. View models live under `.../presentation` or `.../viewmodel` packages, fragments/activities use DataBinding layouts named `fragment_<feature>.xml` or `activity_<feature>.xml`. ARouter paths follow `/module/Feature`. Prefer extension functions for shared logic (place them in `common_component`). Lint via `./gradlew lint` before sending patches and let ktlint/Detekt settings inside `buildSrc` drive formatting rather than ad-hoc style tweaks.

## Testing Guidelines
Place JVM tests in `*/src/test/java` and instrumentation suites in `*/src/androidTest/java`; name files `<Class>Test.kt` or `<Feature>InstrumentedTest.kt` so Gradle discovers them. Cover parsing, player helpers, and data-layer conversions with unit tests, and reserve playback/integration flows for instrumentation backed by an emulator with media files in `storage_component`. Failing tests should block the PR, so run `testDebugUnitTest` locally and attach emulator logs when `connectedDebugAndroidTest` fails.

## Commit & Pull Request Guidelines
Recent history uses the `<type>: <summary>` pattern (`fix: ...`, `refactor: ...`); keep summaries under ~60 characters and describe scope (e.g., `player_component`). Squash noisy WIP commits before pushing. PRs must include: purpose, affected modules, test evidence (command + result), and UI screenshots when touching layouts. Link GitHub issues and note any required configuration toggles (`IS_APPLICATION_RUN`, `IS_DEBUG_MODE`).

## Security & Configuration Tips
Sensitive tokens belong in `local.properties` or Gradle properties; never hard-code keys. Toggle `IS_DEBUG_MODE` and `IS_APPLICATION_RUN` in `gradle.properties` when enabling verbose logs or single-module runs, then rebuild so the flags propagate. Follow `BUGLY_CONFIG.md` for crash reporting credentials, and remember the `user_component` ships with remote APIs disabled—avoid re-enabling interfaces without coordinator approval to keep builds distributable.

## Active Technologies
- Kotlin 1.7.x with Java interoperability for existing Exo helper classes (001-migrate-media3)
- Existing download cache plus `storage_component` test assets; no new external storage vendors planned (001-migrate-media3)
- Kotlin 1.7.21, AGP 7.3.1; JNI bindings to libass 0.17.3 (prebuilt) + ExoPlayer 2.18.x; libass 0.17.3; Android SDK 21–33; MMKV (settings); ARouter (navigation) (001-add-libass-backend)
- N/A (settings via MMKV; no schema changes) (001-add-libass-backend)
- Kotlin 1.7.21 + JNI/C for libass 0.17.3 + ExoPlayer 2.18.x pipeline, Media3 interop helpers, libass 0.17.3 prebuilt, MMKV (settings), ARouter (navigation); GPU composition via OpenGL ES 3.x FBO/EGLImage with dedicated native render thread (001-libass-gpu-render)
- N/A (uses existing subtitle/font asset loaders) (001-libass-gpu-render)
- Kotlin 1.7.x + Java interop; JNI/C++ for libass 0.17.3 render path + ExoPlayer 2.18.x (Media3 interop), libass 0.17.3, OpenGL ES 3.x FBO/EGLImage pipeline, MMKV for settings, ARouter for navigation glue (001-libass-gpu-render)
- N/A (reuses existing subtitle/font asset loaders) (001-libass-gpu-render)
- Kotlin 1.9.25 + Java 8（Android） + Android `Log` / logcat 管道、`common_component` 中的 `DDLog` 与重构后的日志门面 / 写入实现、MMKV 配置存储；Phase 1 不再引入新的第三方日志库，相关实践仅作为设计参考 (001-logging-redesign)
- 应用内部存储目录下的日志文件（仅 `debug.log` / `debug_old.log`，双文件轮转，每个文件默认上限约 5MB，总体约 10MB），以及 MMKV 中持久化的日志策略配置 (001-logging-redesign)

## Recent Changes
- 001-migrate-media3: Added Kotlin 1.7.x with Java interoperability for existing Exo helper classes

## TV/Remote UX
- When adjusting UI element logic (focus/order/visibility), always consider Android TV remote navigation: ensure DPAD can reach/give feedback for all controls and verify focus loops are reachable on TV.

## 测试脚本执行说明（远程模拟器）

本节用于约定在远程模拟器上执行当前项目测试（尤其是 001-logging-redesign 日志相关测试）的统一做法，后续默认都在同一台远程设备上回归。

### 远程设备约定

- 默认远程设备：`192.168.0.188:5555`  
- 建议在当前 shell 中显式指定：

  ```bash
  export REMOTE_EMULATOR=192.168.0.188:5555
  adb devices   # 确认 REMOTE_EMULATOR 处于 device 状态
  ```

后续命令如未特殊说明，均假定使用：

```bash
adb -s $REMOTE_EMULATOR ...
```

### 构建调试 APK 与测试 APK

在仓库根目录执行（WSL 中）：

```bash
./gradlew \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :common_component:assembleDebugAndroidTest
```

要求：
- 检查 Gradle 输出结尾是否为 `BUILD SUCCESSFUL`，否则不得认为构建通过。

### 安装 APK 到远程模拟器

在构建完成后，将主 APK 和各模块的 androidTest APK 安装到远程设备：

```bash
# app 主 APK（调试版）
adb -s $REMOTE_EMULATOR install -r \
  app/build/outputs/apk/debug/dandanplay_v4.1.4_universal-debug.apk

# app 的 androidTest APK（包含 DeveloperLoggingPreferenceTest）
adb -s $REMOTE_EMULATOR install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# common_component 的 androidTest APK（包含日志文件相关测试）
adb -s $REMOTE_EMULATOR install -r \
  common_component/build/outputs/apk/androidTest/debug/common_component-debug-androidTest.apk
```

可选：验证包是否存在：

```bash
adb -s $REMOTE_EMULATOR shell pm list packages | grep dandanplay
adb -s $REMOTE_EMULATOR shell pm list packages | grep dandanplay.test
adb -s $REMOTE_EMULATOR shell pm list packages | grep common_component.test
```

### 按类运行已实现的 Instrumentation 测试

#### 1. 开发者设置日志 UI 测试（app）

文件：`app/src/androidTest/java/com/xyoye/dandanplay/ui/setting/DeveloperLoggingPreferenceTest.kt`  
测试类：`com.xyoye.dandanplay.ui.setting.DeveloperLoggingPreferenceTest`  
Runner：`com.xyoye.dandanplay.test/androidx.test.runner.AndroidJUnitRunner`

执行命令：

```bash
adb -s $REMOTE_EMULATOR shell am instrument -w -r \
  -e class com.xyoye.dandanplay.ui.setting.DeveloperLoggingPreferenceTest \
  com.xyoye.dandanplay.test/androidx.test.runner.AndroidJUnitRunner
```

期望：
- `Tests run: 1`
- `OK (1 test)`

#### 2. 日志文件轮转测试（common_component）

文件：`common_component/src/androidTest/java/com/xyoye/common_component/log/LogFileRotationInstrumentedTest.kt`  
测试类：`com.xyoye.common_component.log.LogFileRotationInstrumentedTest`  
Runner：`com.xyoye.common_component.test/androidx.test.runner.AndroidJUnitRunner`

执行命令：

```bash
adb -s $REMOTE_EMULATOR shell am instrument -w -r \
  -e class com.xyoye.common_component.log.LogFileRotationInstrumentedTest \
  com.xyoye.common_component.test/androidx.test.runner.AndroidJUnitRunner
```

期望：
- `Tests run: 1`
- `OK (1 test)`

#### 3. 磁盘错误熔断测试（common_component）

文件：`common_component/src/androidTest/java/com/xyoye/common_component/log/LogDiskErrorInstrumentedTest.kt`  
测试类：`com.xyoye.common_component.log.LogDiskErrorInstrumentedTest`  
Runner：`com.xyoye.common_component.test/androidx.test.runner.AndroidJUnitRunner`

执行命令：

```bash
adb -s $REMOTE_EMULATOR shell am instrument -w -r \
  -e class com.xyoye.common_component.log.LogDiskErrorInstrumentedTest \
  com.xyoye.common_component.test/androidx.test.runner.AndroidJUnitRunner
```

当前行为（记录现状，后续修复实现或用例后需更新本节）：
- 测试可在远程设备上正常启动，但断言失败：`expected:<DISABLED_DUE_TO_ERROR> but was:<ON_CURRENT_SESSION>`  
- 表示写入失败后的熔断状态更新逻辑与用例预期不一致，需要在调试实现时重点关注。

### 日志查看与过滤建议

由于 adb logcat 噪声较大，不要直接全量阅读。推荐使用包名或关键字过滤：

```bash
# 只看当前应用及日志系统关键输出
adb -s $REMOTE_EMULATOR logcat | grep -E 'com.xyoye.dandanplay|LogSystem|DDLog'
```

其他注意事项：
- 仅在需要定位问题时短时间打开无过滤 logcat，避免长时间输出导致信息泛滥。
- 与日志文件 `debug.log` / `debug_old.log` 联合排查时，优先按照 `quickstart.md` 中的字段说明（time/level/module/ctx_*）进行搜索和过滤。
