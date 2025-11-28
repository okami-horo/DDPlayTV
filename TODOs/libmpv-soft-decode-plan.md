# libmpv 纯软解接入计划

## 背景
- `player_component/src/main/java/com/xyoye/player/kernel/facoty/PlayerFactory.kt` 目前只注入 Media3、VLC 两种内核。Media3 解码失败时只会触发 `player_component/src/main/java/com/xyoye/player_component/media3/fallback/CodecFallbackHandler.kt` 的音频兜底，已无旧版 IJK 回落路径。
- 旧 IJK 内核代码已移除（包含自带 FFmpeg 的实现），后续软解/兼容性方案需依赖 mpv 或 VLC。
- 项目已经有 libass JNI（`player_component/src/main/cpp`）和 Media3 远程开关/运营文档（`document/support/media3-playback-support.md`），可沿用相同模板接入“只复用 libmpv 解码+渲染、禁用 mpv UI 的纯软解后端”。

## 目标
1. 新增一个 libmpv 软解播放器，完全复用现有 UI / 控制层，并强制禁用 `hwdec`，保证所有设备行为一致。
2. Media3 仍默认启用，libmpv 既能被用户在设置中手动选中（新 `PlayerType`），也能在 `CodecFallbackHandler` 判定 `UNSUPPORTED_CODEC` 时作为自动重试路径。
3. 交付可重复构建的原生产物（按 ABI 打包 mpv/FFmpeg/dav1d）、遥测与 QA 工具链，确保上线流程与 Media3 迁移一样可审计。

## 范围与假设
- 支持 ABI：`arm64-v8a`、`armeabi-v7a`，x86 家族暂缓。
- mpv 版本建议 ≥0.36，组合 FFmpeg LTS + dav1d；构建时去掉 `hwdec` 及无用滤镜以控制体积。
- 首选 LGPL（动态链接）配置，并更新 `document/Third_Party_Libraries.md` 与 NOTICE 文件。
- 新播放器受远程开关/隐藏设置保护，上线前不改动 ARouter/存储模块。

## 工作流与任务

### WS1 – 原生依赖与构建工具
| ID | 任务 | 产出 / 备注 |
| --- | --- | --- |
| W1.1 | 确认 NDK、CMake、mpv、FFmpeg、dav1d 版本，并记录在 `scripts/README.md` + 本文。 | 与 Gradle `android.ndkVersion` 保持一致，便于 CI。 |
| W1.2 | 编写 `scripts/build/libmpv/build.sh`，为 `arm64-v8a`/`armeabi-v7a` 构建禁用硬解的 mpv，打包依赖 `.so` 并输出 manifest（ABI、git SHA、codec 开关）。 | 复用现有 libass 脚本结构，方便在 CI/本地运行。 |
| W1.3 | 修改 `player_component/build.gradle.kts`，将新产物放入 `src/main/jniLibs/<abi>` 或 prefab 路径，并在构建阶段校验缺失时失败。 | 防止漏拷贝导致运行时崩溃。 |
| W1.4 | 新增 JNI 桥（参考 `player_component/src/main/cpp/mpv_bridge.cpp`），暴露 Surface 绑定、播放命令、遥测回调。 | Kotlin 与 libmpv 的唯一接口，保持 API 精简便于测试桩。 |
| W1.5 | 更新 `document/Third_Party_Libraries.md` 及 License 资源，列出 mpv / FFmpeg / dav1d，并检查导出符号满足 LGPL。 | 发行审核必需。 |

### WS2 – 播放器内核与 Surface 集成
| ID | 任务 | 产出 / 备注 |
| --- | --- | --- |
| W2.1 | 在 `data_component/src/main/java/com/xyoye/data_component/enums/PlayerType.kt` 添加 `TYPE_MPV_SOFT`，并在 `PlayerFactory` 注册 `MpvPlayerFactory`。 | 让播放器选择器识别新类型。 |
| W2.2 | 实现 `com.xyoye.player.kernel.impl.mpv.MpvVideoPlayer` + `MpvPlayerFactory`，遵循 `AbstractVideoPlayer` 接口。 | 支持 `setDataSource`、`prepareAsync`、Surface 绑定、轨道查询、外挂字幕注入，确保控制层无感。 |
| W2.3 | 创建 mpv 事件循环管理器（如 `MpvEventLoop`），通过 `ControlWrapper` 生命周期回调驱动，防止后台/销毁时漏释放。 | 保证 Activity/TV 模式切后台不会残留线程。 |
| W2.4 | 在 `player_component/src/main/java/com/xyoye/player/surface/SurfaceFactory.kt` 等处挂上 mpv 分支，让 TextureView/SurfaceView 工厂可复用。 | 避免 UI 代码复制，保持手势/遥控器焦点策略。 |
| W2.5 | 与 `player_component/src/main/java/com/xyoye/player/subtitle/backend/SubtitleRendererRegistry.kt` 对接，确认 mpv 输出的时间戳/位图能沿用现有 libass GPU/字幕叠加逻辑。 | 防止字幕/弹幕渲染回退。 |

### WS3 – 功能开关、设置与回退流程
| ID | 任务 | 产出 / 备注 |
| --- | --- | --- |
| W3.1 | 在 `PlayerSettingView`（player_component 控制器）新增播放器选项，当检测到 mpv so 存在时展示。 | 便于内测/资深用户主动启用。 |
| W3.2 | 扩展 `CodecFallbackHandler` 触发链路：出现 `AudioOnly`/`BlockPlayback` 时提供“一键用 mpv 重试”入口，自动实例化新 `PlayerType`。 | 与 Media3 现有错误提示保持一致。 |
| W3.3 | 复用 `document/support/media3-playback-support.md` 所述的远程配置/快照机制，为 mpv 提供按设备/Codec 的灰度开关，并记录在 `RolloutSnapshotManager`。 | 方便支持同学定位问题 Cohort。 |
| W3.4 | 经 `PlayerInitializer`/MMKV 持久化最近一次播放器选择，遥测可区分“人工切换”与“自动兜底”。 | KPI 统计所需。 |

### WS4 – 遥测、测试与上线
| ID | 任务 | 产出 / 备注 |
| --- | --- | --- |
| W4.1 | 在 `data_component/src/main/java/com/xyoye/data_component/entity/media3`（或邻近包）新增 mpv 会话事件：记录 sessionId、codec、平均解码耗时、回退原因。 | 与 `document/monitoring/media3-telemetry.md` 的仪表盘兼容。 |
| W4.2 | 编写 `scripts/testing/mpv-softdecode-validation.sh`，使用 adb 播放 HEVC Main10 / 4K H.264 / AV1 / ASS 压测样本，并过滤 `adb logcat | grep mpv`。 | 与现有 Media3 回归脚本并行，便于 CI/本地快速验证。 |
| W4.3 | 在 `player_component/src/androidTest` 添加仪器化用例，覆盖 Activity 重建、字幕切换、TV DPAD 焦点循环等场景。 | 确保 mpv 软解与其他内核在生命周期/遥控器体验上一致。 |
| W4.4 | 更新支持文档（可在 `document/support/media3-playback-support.md` 增设 mpv 章节或新建 runbook），指导值班接入 mpv 日志和排障步骤。 | 让客服/运营知道何时建议用户切换 mpv。 |
| W4.5 | 在 `document/monitoring/media3-stability.md` 等监控清单中补充 mpv 指标（例如 Codec 兜底成功率、CPU 峰值变化），作为灰度/正式发布的准入条件。 | 保证上线前有量化指标。 |

## 里程碑
1. **M1 – 原生产物**：完成 W1.1–W1.4，`./gradlew assembleDebug` 能随 APK 打出 libmpv。
2. **M2 – 播放器联通**：W2.x 完成，用户可手动切换 mpv 播放本地/远程媒体。
3. **M3 – 回退闭环**：W3.x 完成，Media3 遇到 `UNSUPPORTED_CODEC` 时可自动拉起 mpv 并记录遥测。
4. **M4 – 验证与交付**：W4.x 与许可证更新合入，具备灰度发布与支持/运营交接条件。

## 风险与缓解
- **许可证失误**：静态链接到 GPL 版本的 FFmpeg。→ 在 `build.sh` 中用 `readelf -d` 检查 `NEEDED`，若缺少动态依赖直接 fail。
- **APK 体积增长**：mpv + FFmpeg 可能增加 30MB。→ 精简协议/滤镜、剥离调试符号、必要时使用 ABI 拆分。
- **遥测缺口**：mpv 会话未进入 Media3 仪表盘。→ 复用 `PlaybackSession` ID，扩展 `TelemetryEventMapper`，统一上报。
- **TV 导航回归**：mpv Surface 切换破坏 DPAD 焦点。→ 复用 `player_component/src/main/java/com/xyoye/player/controller/video` 现有焦点逻辑，并在 W4.3 覆盖 TV 用例。

## 待决问题
1. mpv 是否直接加载 libass（内置字幕）还是继续沿用现有 GPU 渲染？决定 W2.5 接入复杂度。
2. mpv 是否需要立即支持离线/下载播放，或初期仍由 Media3/VLC 负责？
3. 回退触发条件是否仅限 `UNSUPPORTED_CODEC`，还是需要参考温控/CPU Telemetry 阈值？
