# DanDanPlayForAndroid：项目实际设置/暴露的 `mpv.conf` 配置项清单

## 先说明：当前项目默认不会读取 `mpv.conf`

本项目在初始化 `libmpv` 时显式设置了 `config=no`（等价于命令行 `--no-config`），因此 **mpv 默认配置文件（如 `mpv.conf`、`input.conf`、scripts 等）不会被加载**。

所以本文的“支持/使用”口径是：
- 项目代码里**硬编码设置**的 mpv 选项
- 或者项目通过 UI/JNI 在运行时**会设置/暴露**的 mpv 选项（这些选项本身也属于 `mpv.conf` 的配置项体系）

补充：播放器实现里提供了通用通道 `setMpvOption(name, value)`（`MpvOptionController`），因此**理论上可以设置任意 mpv 选项**；本文仍以“项目实际用到/显式暴露”的选项为主，避免把 mpv 全部选项（数量非常多、且与编译特性强相关）机械罗列。

如果你的目标是“把这些写进 `mpv.conf` 并让它生效”，需要先在项目里移除/改写 `config=no` 的逻辑，或在 `mpv_initialize()` 前设置：
- `config-dir=<path>`：配置根目录（放 `mpv.conf` / `input.conf` / `scripts` 等）
- `config=yes`：启用配置加载

（mpv 官方在 `libmpv` 接口头文件里也明确建议：启用 `config=yes` 时最好同时设置 `config-dir`，避免误读命令行版 mpv 的默认配置目录。）

参考代码位置：
- `player_component/src/main/cpp/mpv_bridge.cpp`（创建/初始化 mpv handle、默认选项）
- `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt`（运行时 setOption 注入）
- `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`（vo 选择、shader 追加、播放控制）

## `mpv.conf` 写法提示

- `mpv.conf` 通常使用 `key=value`（不带 `--` 前缀）。
- 逗号分隔的“优先级列表”在 `mpv.conf` 里通常也写成逗号分隔（例如 `hwdec=mediacodec,mediacodec-copy`）。
- 诸如 `http-header-fields` 这种“字符串列表”选项，建议参考 mpv 官方手册的示例写法；值里包含空格/冒号时建议加引号。

## 配置项清单（按项目使用方式分组）

下面的“含义”均来自 mpv 官方手册（`https://mpv.io/manual/stable/`）的对应选项说明，并结合本项目的实际用法做了补充。

### 1) 初始化阶段固定设置（项目硬编码，优先级最高）

这些选项在 `mpv_initialize()` 之前设置；除非改代码，否则无法被外部 `mpv.conf` 覆盖（而且当前也不会读取 `mpv.conf`）。

| 选项 | 本项目设置值 | 含义 | 备注（项目语义） |
| --- | --- | --- | --- |
| `config` | `no` | 禁用加载默认配置（等价 `--no-config`） | 直接导致 `mpv.conf` 不生效 |
| `terminal` | `no` | 禁用终端/stdin/stdout/stderr，完全静默终端输出 | Android 场景避免终端依赖 |
| `pause` | `yes` | 以暂停状态启动播放器 | 由上层在合适时机调用播放 |
| `idle` | `once` | 无文件可播时保持空闲（`once`：仅在启动时 idle，首个播放列表结束后退出） | 作为嵌入式播放器更友好 |
| `profile` | `fast` | 应用指定 profile（配置组合） | `fast` 为 mpv 内置 profile 之一 |
| `gpu-context` | `android` | 指定 GPU 上下文为 Android/EGL | 需要 `wid` 绑定 `android.view.Surface`（由项目在 `nativeSetSurface` 设置） |
| `opengl-es` | `yes` | 仅允许 GLES 上下文 | Android 设备通常使用 GLES |
| `hwdec` | `mediacodec,mediacodec-copy`（默认）/`mediacodec-copy,mediacodec` | 指定硬解 API 优先级列表；不可用则回退软解 | 由“播放器设置-视频- MPV 硬解优先级”控制；仍作为判断硬解状态的基础（读取 `hwdec-current`） |
| `ao` | `audiotrack,opensles` | 指定音频输出驱动优先级列表 | 优先 `audiotrack`，回退 `opensles` |
| `vo` | `null` | 指定视频输出后端 | 项目启动时先禁用视频输出，等 Surface 可用后再切到 `gpu/gpu-next/mediacodec_embed` |
| `force-window` | `no` | 无视频时是否强制创建窗口 | Surface 绑定后会临时设为 `yes` 以保持渲染启用 |
| `wid` | `android.view.Surface` 指针值（`int64`） | 为 `gpu-context=android` 绑定渲染目标 | 项目在 Surface 变化时动态设置/清空 |

### 2) 运行时会设置/可注入的选项（项目功能需要）

这些选项由项目在播放/调试/渲染过程中动态设置（通过 `mpv_set_property_string` 或 `mpv_set_property` 等）。一般也可以写进 `mpv.conf`（但仍受“当前不读取 mpv.conf”的前提限制）。

| 选项 | 本项目设置值/来源 | 含义 | 备注（项目语义） |
| --- | --- | --- | --- |
| `vo` | `gpu` / `gpu-next` / `mediacodec_embed`（播放器设置 `mpv_video_output`） | 选择视频输出后端 | UI：`gpu（默认）`/`gpu-next（实验）`/`mediacodec_embed（系统硬件渲染）`；当选择 `mediacodec_embed` 时，mpv 自带字幕/OSD 不可用，项目改用自研 libass 管线渲染软字幕 |
| `android-surface-size` | `${width}x${height}` | 设置 Android `gpu-context` 渲染 surface 的尺寸 | 旋转/尺寸变化时需要更新 |
| `user-agent` | 来自请求头或默认 `"libmpv-android"` | HTTP 流媒体请求使用的 UA | 仅对 `http/https` 注入 |
| `http-header-fields` | 来自 `setDataSource()` 的 header map | 为 HTTP 请求追加自定义 Header 字段 | 用于鉴权/Referer/Cookie 等（按需传入） |
| `force-seekable` | `yes/no`（本地代理 URL 时强制 `yes`） | 强制认为媒体可 seek | 用于规避某些 HTTP 源被判定为不可 seek 的情况 |
| `msg-level` | `all=v,ffmpeg=trace,stream=trace,network=trace`（仅 Debug） | 按模块控制日志详细程度 | 便于抓网络/解封装问题 |
| `demuxer-lavf-o` | `loglevel=trace`（仅 Debug） | 传递 AVOptions 给 FFmpeg demuxer | 主要用于让 FFmpeg 输出更详细日志 |
| `sub-fonts-dir` | 字体目录（应用缓存内） | 指定字幕可用字体目录 | 避免依赖系统字体安装情况 |
| `sub-font` | 默认字体族名 | 为不指定字体的字幕指定默认字体 | 对 ASS 字幕可能被忽略（取决于 `sub-ass` 等） |
| `sub-font-provider` | `none` | 选择 libass 的字体提供后端 | `none` 基本禁用系统字体，仅用内置/目录字体 |
| `deinterlace` | `yes/no`（默认 `yes`） | 启用/禁用去隔行（可减少运动时梳齿） | 项目在播放控制面板里提供开关 |
| `sigmoid-upscaling` | `yes/no`（默认 `yes`） | 上采样时使用 sigmoid 变换以减少 ringing | 仅对 `vo=gpu/gpu-next` 的缩放链路有意义 |
| `sigmoid-upscaling-strength` | `0.00~1.00`（默认 `0.75`） | Sigmoid 放大“强度” | 注意：该选项名称不在 mpv 官方 stable 手册中；是否生效取决于内置 `libmpv` 版本，若不支持会被 mpv 拒绝并在日志中提示 |

### 3) 播放控制相关（项目通过属性/命令驱动，亦属于 mpv 选项体系）

| 选项 | 本项目用法 | 含义 | 备注（项目语义） |
| --- | --- | --- | --- |
| `loop-file` | `inf` / `no` | 单文件循环次数（`inf` 无限循环） | 对应 App 的“循环播放”开关 |
| `speed` | `0.01~100`（float） | 播放速度倍率 | App 使用 `PlayerInitializer.Player.videoSpeed` |
| `volume` | `0~100`（内部混音器音量） | 启动音量/软件音量 | App 侧传入 `0.0~1.0`，native 会乘 100 |
| `sub-delay` | 秒（可负） | 主字幕延迟（正值延后显示） | App 以毫秒传入，native 转成秒 |
| `aid` | 轨道 id / `no` | 选择音轨（`no` 禁用音频） | 项目用 `track-list` 枚举并设置 |
| `vid` | 轨道 id / `no` | 选择视频轨（`no` 禁用视频） | 同上 |
| `sid` | 轨道 id / `no` | 选择字幕轨（`no` 禁用字幕） | 同上 |
| `glsl-shaders` | 通过 `change-list glsl-shaders append <path>` 追加 | 自定义 GLSL hook shader 列表 | App 支持按 HOOK stage 包装/写入缓存后再追加 |

## 额外说明（非 `mpv.conf`，但项目确实用到了）

项目还会通过 mpv command interface 调用这些命令（它们不是 `mpv.conf` 的配置项，但和“功能支持”有关）：
- `loadfile <path>`：加载播放 URL/文件
- `seek <seconds> absolute+exact`：精确跳转
- `audio-add <path> select`：追加外部音轨并选中
- `sub-add <path> select`：追加外部字幕并选中
