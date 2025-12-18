# Phase 1 Quickstart：本地验证 mpv 播放引擎

本 Quickstart 面向开发者，用于在工程内快速接入并验证 mpv 内核的 Phase 1 能力。

## 1. 准备 libmpv 依赖

1. 获取 **Android 版 libmpv**（需包含 `armeabi-v7a` 与 `arm64-v8a` 两种 ABI）。  
2. 将 `.so` / AAR 依赖放入工程：
   - 方案 A：把 `libmpv.so` 与其依赖（如 ffmpeg 相关 `.so`）放入 `player_component/libs/armeabi-v7a/` 与 `player_component/libs/arm64-v8a/`。
   - 方案 B：在 `player_component/build.gradle.kts` 引入对应 AAR/Maven 依赖（确保 NDK/AGP 兼容）。
3. 确认 native 冲突处理：
   - 工程已 `pickFirst("lib/**/libc++_shared.so")`，如 mpv 包内携带同名库，需要确认版本一致或去重。

> 注意：发布前必须完成 GPL/LGPL 许可证评估。工程侧优先选择 LGPL-only 构建或插件化发布策略。

**内置默认参数（Phase 1，无需手动配置）**
- 渲染：`vo=gpu-next` + `gpu-context=android`，默认启用 `opengl-early-flush=yes`
- 解码：`hwdec=auto-safe`，可与设备硬解能力对齐
- 日志：调试模式会请求 `msg-level=info`，发布态降级为 `warn`，日志关键字 `mpv_bridge`/`MpvVideoPlayer`

## 2. 构建与安装

在仓库根目录：

```bash
./gradlew :app:assembleDebug
```

安装到设备/模拟器后启动应用。

## 3. 切换到 mpv 播放

1. 打开应用设置 → 播放器设置 → 播放引擎选择。  
2. 选择 **mpv**。  
3. 播放任意本地/网络视频源，验证：
   - 首帧展示、播放/暂停、seek、倍速、音量、全屏/旋转
   - 弹幕覆盖层显示/开关正常
   - 内嵌/外挂字幕可见、轨道切换、时间偏移生效，且无重复字幕

如 mpv 播放失败，应能看到明确错误提示，并可一键回退默认内核重试。

## 4. 推荐的日志过滤

由于 logcat 噪声较大，建议按关键字过滤：

```bash
adb logcat | grep -E 'com.xyoye.dandanplay|MpvVideoPlayer|LogSystem|DDLog'
```

## 5. Phase 1 回归测试建议

- 参照 `player_component` 现有 smoke tests（如 `Media3PlaybackSmokeTest.kt`），新增 mpv 对应测试类：  
  - 播放成功/首帧  
  - seek/暂停恢复  
  - 轨道切换（音频/字幕）  
  - 外挂字幕加载/偏移

## 6. 常见故障排查

- **初始化失败 / 找不到 mpv**：提示信息如 “libmpv.so missing or failed to link”/“Failed to initialize mpv native session”，检查 ABI 目录下是否存在对应 `libmpv.so`，以及 `player_component/src/main/cpp/CMakeLists.txt` 是否能找到该路径。
- **渲染初始化失败**：错误包含 `render context` 或 `egl*` 关键词时，优先确认 `RenderMpvView` 已持有 `TextureView` Surface，必要时重建页面或切换回默认内核重试。
- **加载数据源被拒绝**：错误包含 `mpv bridge rejected data source` 或 mpv 错误码时，确认 URL/headers 是否完整（referer/token），并用 logcat 关键字 `mpv_bridge|MpvVideoPlayer` 过滤详细原因。
- **播放中报错**：错误会携带 mpv `code=` 与 `reason=`（如 `reason=redirect`），UI 将提供“切换默认内核重试”按钮，可快速落回 Exo/VLC。
