# mpv 原生库来源与打包记录

核对日期：2026-10-29。当前接入 Plezy 使用的原版 Android AAR；迁移验证进度见[执行记录](../docs/superpowers/plans/2026-10-29-plezy-mpv.md)。

## 锁定的上游产物

- 仓库：[edde746/mpv-build](https://github.com/edde746/mpv-build/tree/b15bf59c043b45f9ad7fa227b2cc7d3164daf58f)。
- 提交：`b15bf59c043b45f9ad7fa227b2cc7d3164daf58f`。
- AAR：`mpv-android-127aea1231b8.aar`，保存在 `repository/mpv_native/`。
- SHA-256：`60aa28e89ca288d7a653a6a7c50c9dacaa91c4d992868b257f305b909bb26d59`。依赖构建前的 `verifyMpvArtifact` 会检查此值。
- 下载：[固定内容键的 GitHub Release 产物](https://github.com/edde746/mpv-build/releases/download/binaries-android/mpv-android-127aea1231b8.aar)。
- 二进制版本标识：`mpv v0.41.0-dirty`；补丁来源以上述提交及其 patch series 为准，不能把 dirty 标识当作完整版本锁。
- 上游使用 NDK `29.0.14206865`；AAR 要求 minSdk 25 / compileSdk 36，Java 字节码 17、Kotlin 元数据 2.4。源码和编译器版本不要求消费端使用相同 AGP。
- 原始 Plezy 锁、依赖版本、工具链及许可证材料保存在同目录的 `mpv-build.lock.json`、`versions.json`、`toolchain-android.txt`、`LICENSE`、`NOTICE`。上游整体为 GPL-3.0；NOTICE 内的 MIT 声明适用于其派生 JNI 部分，不能代表整个 AAR。

## 模块与编译边界

`player_component → repository:mpv → repository:mpv_native`

`repository:mpv_native` 发布完整 AAR，包含 Kotlin API、`libplayer.so`、`libmpv.so`、FFmpeg 与 `libc++_shared.so`。上游带四种 ABI，应用继续只打包 ARM64/ARMv7。

`repository:mpv` 是 Java 包装模块，通过 `compileOnly` 和 `runtimeOnly` 引用 AAR，其公开 API 不出现上游 Kotlin 类型。这样项目 Kotlin 2.2.10 的编译路径不读取不兼容的 Kotlin 2.4 元数据，运行时仍使用未经改写的上游类和 native 代码。禁止用跳过元数据检查、修改 AAR 或复制上游源码来掩盖兼容性问题；升级时重新执行编译与设备测试。

应用的 `MpvNativeBridge` 负责串行命令、主线程事件分发、片源修订过滤和业务映射。原 `mpv_bridge.cpp`、mpv C 头文件及旧 mpv/FFmpeg 二进制已移除。初始化、加载、普通控制和关闭进入后台线程，进度/时长/轨道读缓存；必须同步返回的轨道操作及 Surface 销毁仍等待 native 结果。

## 字幕与视频输出

视频固定使用上游补丁的 `vo=mediacodec` 和 `hwdec=mediacodec`，直接向 SurfaceView 输出；不启用 GPU 视频合成或软件回退。有效 Surface 到来后才加载片源。Surface 销毁时先交接到应用持有的占位 Surface，之后才能释放旧窗口；会话关闭后释放占位 Surface。暂停且可 seek 时保存位置，重绑后精确 seek 重绘；同步交接超时会报错，但不保证取消底层操作。细节见[参数与生命周期说明](mpv_conf_supported_options.md)。

应用层将本地 `file:/`、`file:///` URI 转为解码后的绝对路径，HTTP/content URI 保持原值。HTTP 头通过列表 append 命令逐项传入，并在每次加载前清空，保留逗号、反斜杠等值中的字符。

应用自有的 `libass.so`、`libopen_cc.so` 及 `libass_bridge` 保留。上游 mpv 内置 libass，其 ELF 使用 SYMBOLIC 绑定；独立字幕叠加层仍链接应用的 `libass.so`，没有迁移到上游 OSD。现有字幕时钟、弹幕及 Media3 后端保持各自职责。

播放器 CMake 只编译 libass 桥接。Debug 从 `player_component/libs/` 打包保留的库；Release/Beta 的 strip 任务只处理这些本地库。mpv/FFmpeg 的原版 AAR 由 AGP 正常打包，不再与旧同名库混用。

## 升级步骤

1. 选择并固定上游提交和 AAR 内容键，核验下载 SHA-256，保存对应锁与许可材料。
2. 更新 AAR 文件名与校验值，检查公开 API、Kotlin/Java 边界和 minSdk/compileSdk。
3. 检查 APK 内每个 ABI 的库及动态依赖，确认没有旧 `libmpv_bridge.so` 或重复 FFmpeg；比较体积时使用同一构建类型。
4. 执行 JVM、lint、Debug/Release/test APK 及模块边界检查，再验证真实加载、播放、暂停/seek、字幕、Surface 重建、换源、退出和重入。
5. 物理电视的画质/PQ/HDR、音频直通和性能须单独验收，不能由 AVD 或 AAR 版本号推断。

## 历史来源

迁移前的 ARM64/ARMv7 二进制实际标识为 `mpv v0.40.0-493-gf7be2ee3e`；旧构建记录所称 `0.40.0-UNKNOWN` 缺少提交和校验值，仅作历史线索。新的锁定 AAR 取代这套不可复现的 native 来源。
