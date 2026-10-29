# mpv 参数与设置入口

核对日期：2026-10-29。本文列出本项目实际设置的选项、属性和命令，不替代特定 libmpv 版本手册，也不承诺产物支持任意参数。

## 配置入口

[MpvNativeBridge](../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt) 在创建 Plezy AAR 会话时设置 `config=no`，当前不自动加载 `mpv.conf`、`input.conf` 或外部脚本目录。通过应用已有设置配置播放器。

参数由上述桥接和 [MpvVideoPlayer](../player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt) 注入。纯 TV 产品统一使用原生 SurfaceView 和 Plezy 补丁的 `mediacodec` 直出，已删除视频输出和硬解优先级选择及旧持久化键。

## native 初始化默认值

部分值随后被应用偏好覆盖：

| 参数 | 初始值 |
| --- | --- |
| `config` / `terminal` | `no` / `no` |
| `pause` / `idle` | `yes` / `yes` |
| `profile` | `fast` |
| `hwdec` | `mediacodec` |
| `vd-lavc-software-fallback` | `no` |
| `ao` | `audiotrack,opensles` |
| `vo` / `force-window` | `mediacodec` / `no`；有效 Surface 到来前不加载片源 |
| `stop-playback-on-init-failure` | `yes` |
| `sub-visibility` / `osd-level` | `yes` / `0`；字幕由 mpv 原生 OSD Surface 显示 |
| `sub-auto` / `sub-ass-override` / `embeddedfonts` | `no` / `scale` / `yes`；应用管理外挂发现，mpv 保留样式和附件字体 |

## 应用设置与运行时参数

| 参数/属性 | 当前来源 |
| --- | --- |
| `hwdec` | 固定 `mediacodec`；不使用 copy 或软件解码回退 |
| `ao` | 默认、AudioTrack 优先或 OpenSL ES 优先 |
| `video-sync` | 同步设置；默认值时不覆盖 |
| `vo` | 固定 `mediacodec`；Surface 销毁时通过 AAR 交接到占位窗口，不切换 GPU 视频输出 |
| `android-surface-size` | 当前 Surface 尺寸；窗口绑定通过 AAR 的 `attachSurfaces` |
| `user-agent` / `http-header-fields` | 当前播放源 HTTP 请求头 |
| `force-seekable` | 播放源与本地代理识别结果 |
| 日志订阅 | AAR `setLogLevel`；关闭为 `no`，DEBUG 对应 `v`，不额外开启网络 trace |
| `sub-fonts-dir` / `sub-font` | 应用准备的字幕字体目录、默认字体 |
| `sub-font-provider` | `none` |
| `loop-file` / `speed` / `volume` | 循环、速度和音量 |
| `sub-delay` | 字幕延迟；毫秒在 Kotlin 桥接中转为秒 |
| `sub-visibility` / `sub-scale` / `sub-pos` | 显示、字号偏移与垂直偏移；无全局透明度覆盖 |
| `vo-mediacodec-video-rect` | OSD 与实际视频布局同尺寸，传入 `width,height,0,0,width,height` |
| `aid` / `sid` | 音频和字幕轨道；视频维持硬解直出 |

`deinterlace`、`sigmoid-upscaling`、`sigmoid-upscaling-strength` 当前没有源码设置入口，已从实际暴露能力清单移除。参数注入成功不能单独证明设备上的实际效果。

## 命令与验证

bridge 使用 `loadfile`、`seek`、`stop`、`audio-add`、`sub-add`、`sub-remove` 和 `change-list` 命令，不能将它们当作配置行。HTTP 请求头每次换源先清空，再用 `change-list http-header-fields append` 逐项传入原值，避免逗号和反斜杠被列表解析破坏。新增参数时核对内置 libmpv 支持、设置时机、错误返回和实际播放。原生库来源见 [打包记录](mpv-build-notes.md)。

Surface 交接使用 AAR 的 `attachSurfaces` 与视频/字幕独立生命周期编号；销毁旧视频窗口前等待交接到应用持有的占位 Surface，随后重绑实际窗口。字幕窗口销毁仅解绑 OSD。释放会话后再释放占位 Surface。关键设置失败向上报告错误。设备视频硬解不支持时应切换 Media3；字幕使用原生管线，见[字幕渲染](player/subtitle-rendering.md)。

暂停且可 seek 时，解绑前保存时间，重绑后精确 seek 以重绘暂停帧；播放、停止和换源清除该位置，解绑期间的 seek 更新待恢复位置。不可 seek 的流不承诺精确恢复。同步 Surface/轨道调用最多等待 1.5 秒；超时报告失败，但不代表底层阻塞操作已经取消。

## 已移除能力

2026-10-28 按产品决策全端移除 Anime4K 视频后处理：TV GPU 的性能成本不适合本项目。播放器设置、内置 shader、Media3 特效管线及 mpv 自定义 shader 桥接已删除；普通视频输出与字幕渲染继续保留。
