# 播放重构：参考来源与决策记录

审查日期：2026-10-27；状态整理日期：2026-10-28。原始审查基线为 f8d4c54c41eb1401107849417afcac68dc88869a。本文件保留参考项目启示、已采用的职责决策及其理由；修订前的错误方案、已被替换源码的行号和失效的“尚未实施”步骤已清理。

当前设计、执行状态与逐项验证分别由以下文件维护，本文不重复维护完整任务账本：

- [播放交接设计](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md)：稳定请求、唯一会话、恢复及平台适配。
- [传输租约设计](../../docs/superpowers/designs/2026-07-19-playback-transport-leases-design.md)：协议连接与资源所有权。
- [租约实施记录](../../docs/superpowers/plans/2026-07-19-playback-transport-leases.md)：9 个任务、37/37 检查项及协议/设备证据。
- [交接实施与验收记录](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)：任务 1–13 及任务 14 自动验证已记录，当前合计 62/65；剩余 3 项综合场景由用户验收。

## 已采用的决策及理由

| 原发现 | 采用的决策 | 维护性收益与行为边界 |
| --- | --- | --- |
| F1：SMB/FTP 只隔离 server、不隔离底层连接 | acquisition 从媒体库配置创建独立播放连接，并在新连接重新解析文件；浏览连接不转交给播放租约 | 准备或释放候选 B 不应关闭 A 或浏览页面的连接；验证从真实工厂装配入口执行 |
| F2：多个入口同时写播放身份和状态 | player 内 ActivePlaybackSession 统一业务写入权；Activity/平台适配读取状态或提交命令 | 新增来源或恢复路径减少并行维护点；不是新增会话层后继续保留旧写入口 |
| F3：上层和租约重复释放同一资源 | 会话 → PreparedPlayback → Source → Addon/lease；lease 释放它独占的协议资源，Bilibili token 也有唯一所有者 | 取消、切换、退出的重复到达由明确所有权和幂等释放处理，不建立第二套全局生命周期总管 |
| F4：播放恢复策略继续下沉 storage | contract 放稳定请求与跨模块接口；player 管请求/队列/恢复；core_storage 管来源解析与租约 | 协议修改和播放交互修改分别留在所属层；无需新增 Gradle 模块 |
| F5：磁盘候选没有恢复消费方 | 原生切换候选只驻留运行时，真实切换后保存快照；主动受控切换仍先满足持久化门槛 | 删除不会被恢复使用的磁盘状态；原生切换保存失败后进程恢复可能回到上一次成功保存的目标 |
| F6：禁用远端网关被当成本地播放核心 | 本地事实和能力来自统一运行时与内核；平台使用只读投影；删除被替代的网关/缓存/转发路径 | 保留网关禁用语义，不依赖远端成功响应维持本地播放，也不把元数据 service 宣称为完整后台宿主 |

这些决策已进入设计和实现记录；详细覆盖与失败证据以链接计划为准。F1–F6 是原始审查的历史编号，不是六个当前未修复缺陷。

## 当前职责边界

| 所在层 | 拥有的职责 | 避免重复拥有 |
| --- | --- | --- |
| core_contract_component | 稳定身份、请求、必要入口和共享契约 | 协议连接、Activity、运行时单例实现 |
| core_storage_component / bilibili_component | 来源解析、协议 I/O、独立租约；Bilibili 解释自身协议语义 | UI 返回策略、全局进度、用户请求调度 |
| player_component | 活动会话、请求替换、候选/队列、来源代次、恢复策略和内核适配 | 浏览页连接、另一个独立播放事实来源 |
| app | 装配、Android service 宿主与平台入口 | 第二套播放业务状态机 |
| Activity / ViewModel | Surface、焦点/返回、展示和提交命令 | 独立改写来源身份或关闭其他所有者的连接 |

可从 [ActivePlaybackSession](../../player_component/src/main/java/com/xyoye/player_component/playback/session/ActivePlaybackSession.kt)、[PreparedPlayback](../../player_component/src/main/java/com/xyoye/player_component/playback/session/PreparedPlayback.kt) 和 [PlaybackTransportFactory](../../core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/PlaybackTransportFactory.kt) 定位当前实现。包名或类名相似不代表它们拥有同一职责。

运行状态与持久恢复目标可能短暂不同：主动提交保存成功后尚未应用内核，或原生切换已经发生但新快照尚未保存。该差异必须显式表达，不能靠多个 store 互相推测。完整后台播放宿主迁移仍是单独需求，不能因本轮职责统一而默认已经完成。

## 参考项目及适用边界

下表保留 2026-10-27 审查时读取的来源与适配判断；外部链接的分支内容可能随后变化，本次整理未重新核验这些外部项目。它们提供行为和职责参考，不是本仓库的依赖版本或可直接复制的模板。

| 参考 | 实读源码 | 对 DDPlayTV 的启示 | 不能直接照搬 |
| --- | --- | --- | --- |
| Next Player | [PlayerService.kt](https://github.com/anilbeesetti/nextplayer/blob/main/feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/PlayerService.kt)、[PlayerViewModel.kt](https://github.com/anilbeesetti/nextplayer/blob/main/feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/PlayerViewModel.kt) | service 拥有 MediaSession/播放器生命周期，ViewModel 面向 UI；媒体切换和位置事件驱动保存。支持把播放运行时与页面拆开 | 它的 Media3 路线不能替代本项目多内核；未取得其网络 DataSource 实现，不据此评价其 SMB 连接隔离 |
| VLC Android | [PlaybackService.kt](https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/PlaybackService.kt)、[PlaylistManager.kt](https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/media/PlaylistManager.kt) | 队列操作和播放位置保存围绕播放运行时组织，页面不必为每个动作各维护一套来源生命周期 | 单一 VLC 内核、较大的 service/manager 以及历史 API 不适合作为逐类复制的模板 |
| Jellyfin Android TV | [PlaybackManager.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackManager.kt)、[PlaybackController.java](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java)、[VideoQueueManager.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoQueueManager.kt) | 将媒体身份/播放选项解析为 StreamInfo，隔离直接播放、直接流和转码差异；可借鉴准备结果与 TV 控制的分工 | `changeVideoStream` 会先停止旧转码再获取新流，不满足本项目“准备失败旧播放继续”；其 controller/共享队列也不是理想单所有者样板 |
| AndroidX Media3 官方示例 | [DemoPlaybackService.kt](https://github.com/androidx/media/blob/release/demos/session_service/src/main/java/androidx/media3/demo/session/DemoPlaybackService.kt)、[DemoMediaLibrarySessionCallback.kt](https://github.com/androidx/media/blob/release/demos/session_service/src/main/java/androidx/media3/demo/session/DemoMediaLibrarySessionCallback.kt) | 展示 service 与 Player/MediaSession 生命周期、mediaId 解析，以及以媒体列表、索引和位置恢复播放 | 是 API 用法示例，不是覆盖本项目加密、租约、多协议与全部 TV 返回栈的生产设计 |
| 本地 PiliPlus | `E:/Project/PiliPlus/lib/pages/video/controller.dart`、`E:/Project/PiliPlus/lib/plugin/pl_player/playback_source.dart`；HEAD `8b2dd5385cb2cfec4957dea8ae083a21c3d81c98` | `PlaybackSource.selectDash` 把画质、编码、音轨和 CDN 选择做成不持有页面状态的逻辑，可参考到 `bilibili_component` 的播放解析 | 本地工作树可能含未提交修改；Flutter/GetX controller 和单例不照搬。DDPlayTV 中由 Bilibili 层解析协议、core_storage 包装来源/租约、player 管会话，按多内核与 TV 约束适配 |


从这些来源得到的共同启示是集中状态写入权、分离协议和页面职责；并未证明某一种目录结构、统一内核算法或“全部放进 service”适合 DDPlayTV。参考 PiliPlus 的播放选择逻辑后，本地按多内核、TV 交互和现有模块边界做了适配。

## 保留的验收边界

- 主动切换前的加密/保存失败取消候选并保留旧播放；初始播放保存失败不能继续激活。
- 原生切换已经发生后，快照保存失败不停止真实播放；重试成功前，进程恢复可能回到上一份快照。
- 每份连接、句柄、任务和 token 必须指出唯一释放所有者；较旧请求、解析结果和内核事件不能覆盖新来源。
- 用户已确认维持多内核、字幕/弹幕、历史、Bilibili 画质/恢复、投屏及 TV DPAD 行为，不以大规模目录搬迁替代验证。
- 原生内核自动切换方式、双播放器首帧无缝接管、框架/DI/数据库替换，以及完整后台/PIP 宿主均没有因参考项目而自动扩大到本次重构范围。

## 验证记录的读法

2026-10-28 实施记录包含最终 478 项 JVM 测试、模块/播放边界、完整 lint、限定范围 ktlint、APK 构建通过（BUILD SUCCESSFUL）及 5 项最终设备回归通过。该证据不是完整聚合门禁、所有协议现场场景或物理 TV 验收通过的证明：

- 全仓旧 ktlint/Pager 基线仍有失败；CI 当前运行的具体任务见 [治理检查](architecture_governance_guardrails.md)。
- FTP 曾出现间歇 EOF/非法范围错误，最终及重复回归通过，但间歇原因未被隔离，重复 seek/替换仍是验收关注点。
- 真实 Bilibili 登录/画质/URL 刷新/风控成功、完整有种 BT 播放、字幕/弹幕连续性、非零进度的进程恢复以及完整移动端/实体 TV 导航仍以任务 14 的用户验收为准。
- 手机 AVD 的临时 TV 分支测试不能等同于物理 TV/SystemUI 兼容性；实际 process-restoration 已有证据，非零位置尚未独立观察。
- 既有 popup 显示/关闭 no-op 和元数据型 app service 不提供完整后台/PIP 托管。
- 仪器测试中曾有 BUILD FAILED，最终通过的显式安装/adb instrumentation 结果不能覆盖或改写这些失败；详情保留在实施记录。
- 原始审查时的 Gradle 下载受限是历史环境限制，后续本地 JDK 17/Gradle 8.9 验证已有成功证据。不得继续把该限制当作当前没有验证的理由。

本次工作是文档维护，没有重跑上述业务测试，也没有核验云端运行结果。剩余验收维持 62/65，不因整理文档而关闭。
