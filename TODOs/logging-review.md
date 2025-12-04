日志体系现状与改进建议
====================

当前实现速览
- 全局入口：`BaseApplication.kt` 在启动时初始化 `AppLogger`，并通过 `DDLog` 打印启动信息后尝试触发历史日志上传。
- 入口封装：`DDLog` 仅提供 info/warn/error，固定 Logcat tag 为 `DDLog`，模块 tag 拼进 message，再转发到 `AppLogger` 持久化。
- 持久化与上传：`AppLogger` 单线程写入文件，2MB 轮转保留 5 份；文件超 512KB 且距离上次检查超 30 分钟时，会在上传开关开启且配置齐全时触发 WebDAV 上传。
- 配置入口：开发者设置页提供 DDLog 开关和字幕遥测开关；上传配置在旧版本已移除，仅保留日志总开关与字幕遥测开关。
- 其他出口：播放器仍使用独立的 `VideoLog`（受 `PlayerInitializer.isPrintLog` 控制），OkHttp 日志拦截器在 Debug 下默认 BODY 级别输出，少量直接使用 `Log` 的代码未受 DDLog 控制。

主要问题
- Tag 与级别不可控：DDLog 统一 tag，模块 tag 混在 message，Logcat 难以按模块过滤；缺少 debug/verbose，导致要么过静要么过吵。
- 开关分裂：DDLog 开关无法管控 `VideoLog`、OkHttp 拦截器以及直接 `Log` 的输出；想静默或收敛日志时仍有噪音。
- 采集与上传耦合：关闭 DDLog 会直接停止本地采集和上传链路，配置项也没有提示依赖关系。
- 噪声过多：大量 UI/导航类日志使用 info/warn（如存储文件列表焦点/键盘事件），占据主要信道且缺少筛选维度。
- I/O 频繁：`AppLogger` 每条日志都打开/写入/关闭文件，无缓冲或批量 flush，高频场景易产生额外 I/O 压力。
- 结构化缺失：纯文本拼接缺少键值和 trace/session id，上传后难以检索、关联播放/网络/字幕流水线的问题。

改进建议
1) 统一入口与开关：提供带 debug/info/warn/error/telemetry 的 Logger 门面，`VideoLog`、网络拦截器、字幕遥测全部走同一开关与级别策略，可按域单独控制。
2) Tag/级别规范：真实模块 tag 直接写入 Logcat；默认最低输出为 warn，debug/verbose 仅在开发者模式或临时诊断时开启；为网络日志提供独立开关与级别。
3) 配置解耦：区分“采集开关”与“上传开关”，在 UI 中提示依赖关系；允许临时开启采集并自动超时回落。
4) 噪声治理：将 UI 频繁日志改为 debug 或采样输出，关键路径（播放、字幕、网络错误）保留结构化字段。
5) 写入优化：为 `AppLogger` 增加缓冲/批量 flush 或内存队列 + 定时落盘，必要时增加压缩与打包后上传。
6) 诊断友好：在日志行内添加会话/播放/请求 ID，上传后在开发者设置中显示最近一次上传结果和剩余本地文件数。
