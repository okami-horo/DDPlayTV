# DanDanPlayForAndroid mpv/libmpv 本地编译记录

本记录用于复现当前在 `.tmp/mpv-android` 下编译出的 `libmpv.so`，并说明关键参数与产物位置，方便后续排查或重新打包。

## 环境与前置
- OS：Ubuntu 22.04
- JDK：javac 17.0.15
- NDK：r29（`buildscripts/sdk/android-ndk-r29`，toolchain 位于 `.../toolchains/llvm/prebuilt/linux-x86_64`）
- Android SDK 平台：platform 35 / build-tools 35.0.0（由 `buildscripts/download.sh` 自动安装到 `buildscripts/sdk/android-sdk-linux`）
- 构建仓库：`mpv-android`（官方项目），clone 路径：`.tmp/mpv-android`

## 编译步骤（已执行）
```bash
# 进入 buildscripts 目录
cd .tmp/mpv-android/buildscripts

# 1) 下载 SDK/NDK 以及源码依赖（ffmpeg/libass/libplacebo 等）
./download.sh

# 2) 构建 libmpv（含依赖）arm64
./buildall.sh --arch arm64 mpv

# 3) 构建 libmpv（含依赖）armeabi-v7a
./buildall.sh mpv   # 默认 arch=armv7l

# 4) 仅对 libmpv.so 做 strip 减小体积
../sdk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded prefix/arm64/usr/local/lib/libmpv.so
../sdk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded prefix/armv7l/usr/local/lib/libmpv.so
```

## 产物路径
- arm64-v8a: `.tmp/mpv-android/buildscripts/prefix/arm64/usr/local/lib/libmpv.so`（约 5.6MB，已 stripped）
- armeabi-v7a: `.tmp/mpv-android/buildscripts/prefix/armv7l/usr/local/lib/libmpv.so`（约 5.0MB，已 stripped）
- 对应头文件：`.../prefix/<arch>/usr/local/include/mpv/{client.h,render.h,render_gl.h,stream_cb.h}`
- 构建时同时生成的依赖 `.so`（FFmpeg、swresample、swscale 等）也在 `.../usr/local/lib`，如需随包分发请一并放入 `player_component/libs/<abi>/`

## 编译选项与版本要点
- mpv 版本：0.40.0-UNKNOWN（来源 `mpv-android` 当前 master，未手动指定 tag）
- Meson 关键参数（见 `buildscripts/scripts/mpv.sh` 默认配置）：
  - `--default-library shared`
  - `-Diconv=disabled`
  - `-Dlua=enabled`（Lua 5.2.4）
  - `-Dlibmpv=true`
  - `-Dcplayer=false`
  - `-Dmanpage-build=disabled`
- 构建特性检测输出：启用了 `gpl`、`libass`、`ffmpeg`、`opengl`、`android`、`lua`；未启用 `vulkan/wayland/x11/egl-angle` 等。
- 依赖版本（来自 `depinfo.sh`）：NDK r29、FFmpeg default (CI pin n8.0 only used in CI)、libass master、libplacebo master、dav1d master、harfbuzz 12.2.0、fribidi 1.0.16、freetype 2.14.1、libunibreak 6.1、mbedtls 3.6.5、Lua 5.2.4。
- ABI：已编译 `arm64` 与 `armv7l`；未构建 x86/x86_64。
- Strip 状态：仅对 `libmpv.so` 执行 strip；其他依赖 `.so` 未 strip（如需减重可按需再 strip）。

## 后续打包提醒
- 将对应 ABI 的 `libmpv.so`（及所需 ffmpeg 等依赖）放入 `player_component/libs/arm64-v8a/` 与 `player_component/libs/armeabi-v7a/`，保持与 CMake `PREBUILT_LIBS_DIR` 路径一致。
- 构建日志显示 `gpl` 特性启用，发布前务必完成 GPL/LGPL 合规确认。
