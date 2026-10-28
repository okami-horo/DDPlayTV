# mpv 参数与设置入口

核对日期：2026-10-28。本文列出本项目实际设置的选项、属性和命令，不替代特定 libmpv 版本手册，也不承诺产物支持任意参数。

## 配置入口

[mpv_bridge.cpp](../player_component/src/main/cpp/mpv_bridge.cpp) 设置 `config=no`，当前不自动加载 `mpv.conf`、`input.conf` 或外部脚本目录。通过应用已有设置配置播放器。

参数由 [MpvNativeBridge](../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt)、[MpvVideoPlayer](../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt) 注入；输出枚举见 [MpvOptions](../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvOptions.kt)。旧文档所称 `MpvOptionController.setMpvOption()` 通用入口在当前源码不存在。

## native 初始化默认值

部分值随后被应用偏好覆盖：

| 参数 | 初始值 |
| --- | --- |
| `config` / `terminal` | `no` / `no` |
| `pause` / `idle` | `yes` / `once` |
| `profile` | `fast` |
| `gpu-context` / `opengl-es` | `android` / `yes` |
| `hwdec` | `mediacodec,mediacodec-copy` |
| `ao` | `audiotrack,opensles` |
| `vo` / `force-window` | `null` / `no`，等待 Surface |

## 应用设置与运行时参数

| 参数/属性 | 当前来源 |
| --- | --- |
| `hwdec` | 普通/copy 硬解优先级 |
| `ao` | 默认、AudioTrack 优先或 OpenSL ES 优先 |
| `video-sync` | 同步设置；默认值时不覆盖 |
| `vo` | `gpu`、`gpu-next`、`mediacodec_embed`，默认 `gpu` |
| `wid` / `android-surface-size` | 当前 Surface 及尺寸 |
| `user-agent` / `http-header-fields` | 当前播放源 HTTP 请求头 |
| `force-seekable` | 播放源与本地代理识别结果 |
| `msg-level` / `demuxer-lavf-o` | 日志级别为 DEBUG 时增加网络/解封装日志，并非仅限 Debug 构建 |
| `sub-fonts-dir` / `sub-font` | 应用准备的字幕字体目录、默认字体 |
| `sub-font-provider` | `none` |
| `loop-file` / `speed` / `volume` | 循环、速度和音量 |
| `sub-delay` | 字幕延迟；毫秒在 native 转为秒 |
| `aid` / `vid` / `sid` | 音频、视频和字幕轨道 |

`deinterlace`、`sigmoid-upscaling`、`sigmoid-upscaling-strength` 当前没有源码设置入口，已从实际暴露能力清单移除。参数注入成功不能单独证明设备上的实际效果。

## 命令与验证

bridge 使用 `loadfile`、`seek`、`audio-add`、`sub-add` 命令，不能将它们当作配置行。新增参数时核对内置 libmpv 支持、设置时机、错误返回和实际播放。原生库来源见 [打包记录](mpv-build-notes.md)。

## 已移除能力

2026-10-28 按产品决策全端移除 Anime4K 视频后处理：TV GPU 的性能成本不适合本项目。播放器设置、内置 shader、Media3 特效管线及 mpv 自定义 shader 桥接已删除；普通视频输出与字幕渲染继续保留。
