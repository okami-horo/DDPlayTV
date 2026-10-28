# mpv 原生库来源与打包记录

核对日期：2026-10-28。本文区分当前可验证的打包方式与旧构建记录，避免把缺少源版本的历史步骤当作可复现说明。

## 当前事实

- mpv 及配套 FFmpeg、字幕/字体预编译 `.so` 位于 `player_component/libs/arm64-v8a/` 和 `player_component/libs/armeabi-v7a/`，当前没有 x86/x86_64 目录。
- [CMake](../player_component/src/main/cpp/CMakeLists.txt) 导入其中的 `libmpv.so`、`libass.so` 并编译 JNI。缺少 mpv 时可编译不链接它的 bridge，但这不提供可用的 mpv 内核。
- [Gradle 配置](../player_component/build.gradle.kts) 的 Debug 使用 `libs/`；Release/Beta 使用 `build/strippedJniLibs/`，由 `stripReleaseJniLibs` 复制并对**全部预编译 `.so`**执行 `llvm-strip --strip-unneeded`。
- Gradle/CMake 使用工程 NDK `25.2.9519653`，与历史重编上游 libmpv 所用 NDK 是不同阶段。
- JNI 头文件位于 `player_component/src/main/cpp/include/`；替换库需核对 API、ABI、依赖及头文件兼容性。

## 保留的历史线索

旧记录描述一次 Ubuntu 22.04、JDK 17、NDK r29 和 `mpv-android` 构建，称输出为 `0.40.0-UNKNOWN`，构建了 arm64/armv7l，启用了 libass、FFmpeg、OpenGL、Android、Lua、GPL 特性。

记录没有保存上游提交、全部依赖提交、完整配置、日志与校验值，使用会变化的 `master`；临时检出 `.tmp/mpv-android` 当前不存在。因此只能作为来源追查线索，不能证明当前二进制版本、全部编译特性或许可证结论。

旧的一键下载/编译命令、临时体积和“仅 strip libmpv”描述已移除：前两者缺少可复现输入，后者与当前构建流程不符。

## 后续替换/重建时记录

1. 固定上游提交/tag 和依赖版本，保存真实构建命令、配置及日志。
2. 记录主机、SDK/NDK、ABI、API 级别、编译特性与许可证材料。
3. 为每个产物记录 SHA-256，确认动态依赖齐全，不能只替换 `libmpv.so`。
4. 检查 Debug、Release/Beta 包内库，再在目标 ABI 设备验证加载、播放、Seek、字幕、渲染和退出。

本轮没有重编原生依赖，也没有将历史运行结果标为新验证。实际使用参数见 [配置项](mpv_conf_supported_options.md)。
