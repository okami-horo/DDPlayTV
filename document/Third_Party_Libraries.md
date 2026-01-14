# 第三方库清单（非穷举）

> 说明：本清单用于快速了解“项目主要依赖”。完整依赖以各模块 `build.gradle.kts` 与 Gradle 依赖树为准（例如 `./gradlew :app:dependencies`）。

## 1、播放器 / 渲染

- AndroidX Media3（`androidx.media3:*`）：TYPE_EXO 播放内核、Session、Cast 等能力
- libVLC（`org.videolan.android:libvlc-all`）：VLC 播放内核
- libmpv（`mpv-android` 编译产物）：MPV 播放内核（见 `document/mpv-build-notes.md`）
- DanmakuFlameMaster（`repository:danmaku`）：弹幕渲染库（AAR 封装）
- VideoCache（`repository:video_cache`）：视频缓存相关库（AAR 封装）
- PanelSwitchHelper（`repository:panel_switch`）：面板/键盘切换库（AAR 封装）

## 2、网络 / 协议 / 解析

- OkHttp：HTTP 客户端
- Retrofit：网络请求封装
- Moshi：JSON 序列化/反序列化
- Kotlin Coroutines：异步与并发
- NanoHTTPD：本地 HTTP 服务/代理（例如播放代理）
- SMBJ + dcerpc：SMB 协议访问
- Apache Commons Net：FTP 等协议访问
- jsoup：HTML/文本解析（部分站点能力）

## 3、数据 / 存储

- Room：SQLite ORM
- MMKV：Key-Value 存储
- SevenZip（`repository:seven_zip`）：7z 解压能力（AAR 封装）
- Thunder（`repository:thunder`）：下载相关能力（AAR 封装）

## 4、UI / 基础设施

- AndroidX（AppCompat/RecyclerView/Paging/Startup/Preference 等）
- Material Components：Material UI 组件
- Coil（含视频帧解码）：图片/封面加载
- ARouter：组件化路由
- ImmersionBar（`repository:immersion_bar`）：沉浸式状态栏（AAR 封装）

## 5、质量 / 诊断

- Bugly：崩溃上报（若构建时配置相关参数）
- LeakCanary：内存泄漏检测（debug）

## 6、已移除 / 不再使用（历史遗留说明）

历史版本曾使用但当前仓库已不再依赖（以代码与 Gradle 为准）：IJKPlayer、RxJava、ButterKnife、Sophix 等。
