# 第三方组件清单

核对日期：2026-10-28。本文用于定位主要依赖职责，不作为完整依赖树或许可证清单。版本及使用范围以 [依赖常量](../buildSrc/src/main/java/Dependencies.kt)、各模块构建脚本及 `./gradlew :app:dependencies` 为准；定义常量不代表已经使用。

## 播放与渲染

| 组件 | 当前用途与位置 |
| --- | --- |
| AndroidX Media3 | `player_component` 的 Exo 内核、Session、UI、数据源和扩展；Cast 依赖不代表完整投屏已实现 |
| Plezy mpv AAR / FFmpeg | `repository:mpv_native` 保存原版 AAR，`repository:mpv` 提供 Java 编译边界，见 [原生库记录](mpv-build-notes.md) |
| libass / 字体依赖 | ASS/SSA 与字体支持，位于播放器原生库目录 |
| OpenCC | 简繁转换包装、词典与 `libopen_cc.so`，位于 `player_component` |
| DanmakuFlameMaster | `repository:danmaku` AAR 封装，弹幕渲染 |
| VideoCache | `repository:video_cache` AAR 封装，缓存 |

当前播放器构建使用 Media3。`Dependencies.Google` 保留了 ExoPlayer 2 常量，但当前模块构建没有引用，不作为独立现用内核列出。

## 网络、数据与系统

- OkHttp、Retrofit、Moshi：HTTP 与数据转换。
- Kotlin Coroutines：异步与并发。
- NanoHTTPD：播放代理、日志等本地服务。
- SMBJ / dcerpc、Apache Commons Net：SMB、FTP 访问。
- jsoup、juniversalchardet：网页与文本解析、编码识别。
- Room、MMKV：数据库与键值存储。
- SevenZip（`repository:seven_zip`）、Thunder（`repository:thunder`）：解压与下载封装。
- 华为 Scan Kit：二维码生成/相关组件调用；依赖存在不代表当前声明相机权限。

## UI、诊断与测试

- AndroidX AppCompat、Lifecycle、RecyclerView、Paging、Startup、Preference 等。
- DpadRecyclerView `1.4.3`：`core_ui_component` 提供 TV 列表、网格和动态菜单的方向导航与滚动；移动端共享布局继续使用普通 RecyclerView。
- Material Components、Coil（含视频帧）、Banner：UI 与封面加载。
- ARouter：模块路由和服务。
- ImmersionBar、PanelSwitchHelper：`repository:immersion_bar`、`repository:panel_switch` 封装。
- Bugly：有效 App ID 配置下的诊断上报，见 [数据处理说明](<Privacy policy.md>)。
- LeakCanary：`app` Debug 依赖。
- JUnit、AndroidX Test、Robolectric、Coroutines Test：测试。

## 分发材料

应用内许可证文本在 `user_component/src/main/assets/license/`，该目录与本文均不是完整合规审查结论。更新 AAR、预编译 `.so` 或 Maven 依赖时，核对来源、编译选项与许可证材料，不用旧文本代表新产物。
