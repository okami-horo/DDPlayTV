# 2026 年 6 月播放器与焦点决策整理

整理与源码核对日期：2026-10-28。本文合并四份旧执行计划，保留仍有效的行为约束和未完成工作。旧计划中的逐步代码模板、固定行号、工具调用和未更新的复选框已移除；原文仍可通过 Git 历史查询。

| 原计划 | 当前归属 |
| --- | --- |
| `2026-06-06-player-subtitle-quick-toggle.md` | 本文“字幕快捷开关” |
| `2026-06-13-danmu-match-optimization-plan.md` | 本文“弹幕匹配” |
| `2026-06-13-danmu-match-fixes.md` | 本文“尚未完成的弹幕修复” |
| `2026-06-24-tv-dpad-focus-architecture.md` | 本文“TV 焦点” |

本文是历史决策与遗留工作记录；当前播放交接的实施账本仍为 [交接计划](../plans/2026-10-27-playback-handoff-session.md)，不要据本次文档合并改变其 62/65 验收状态。

## 字幕快捷开关

已经能从现有源码确认的行为：

- [InterSubtitleController](../../../player_component/src/main/java/com/xyoye/player/wrapper/InterSubtitleController.kt) 提供用户可见状态的设置、读取与切换接口；[SubtitleController](../../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt) 在隐藏时清空文字／位图视图，并保存最后一份字幕供恢复显示。
- [PlayerBottomView](../../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt) 的字幕按钮根据已选择且未禁用的字幕轨道决定是否可操作；不可用时变暗并从 DPAD 横向焦点链跳过，必要时把焦点交回播放键。
- 快捷开关的语义是切换字幕可见性，不修改字幕轨道选择；按钮通过 `PlayerAction.ToggleSubtitle` 路由到控制器。

这说明旧计划中的 API、资源和按钮接线已经存在，不需要按旧模板重新创建。源码核对只覆盖上述控制器／按钮路径，不能单独证明所有内核、自绘 libass 图层与真机行为均已验收。

## 弹幕匹配

保留的业务规则与当前入口：

- [DanmuQueryImpl](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/query/DanmuQueryImpl.kt) 在有文件名时使用 `hashAndFileName`，没有文件名时使用 `hashOnly`；[ResourceRepository](../../../core_storage_component/src/main/java/com/xyoye/common_component/network/repository/ResourceRepository.kt) 接收 hash、文件名、大小、时长和匹配模式。旧计划把该 Repository 放在网络模块的路径已失效。
- [DanmuMatchCoordinator](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt) 区分精确命中、模糊候选与未找到；[PlayerDanmuViewModel](../../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModel.kt) 仅自动加载成功结果，不自动下载模糊候选。
- [DanmuMatchCache](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/cache/DanmuMatchCache.kt) 保存精确映射和失败标记，以 hash 与文件大小区分；源码中的有效期分别为 30 天和 1 天。
- 网络 hash 使用 30 秒超时。解析出的 `shift` 随匹配结果传递，但当前自动加载调用没有消费该值，不能描述为已经自动校准弹幕时间。

## 尚未完成的弹幕修复

旧修复计划没有实施结果记录，本次核对也发现以下问题仍存在，因此保留为待办，不标记完成。本轮仅整理文档，不修复生产代码。

- [ ] **手动绑定状态清理**：[BindDanmuSourceFragmentViewModel](../../../local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt) 的 `matchDanmu()` 在没有可用匹配结果时，没有清除先前的匹配／选择状态。应补充空结果及重复匹配回归，并保留独立搜索结果。
- [ ] **网络文件名解析**：[NetworkDanmuSource](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt) 的回退分支直接调用 `URI(url)`；需要验证并处理无效 URI，保持匹配降级流程可继续。
- [ ] **协程取消传播**：同一类的重试方法包含泛化 `catch (Exception)`，没有单独重新抛出 `CancellationException`。应验证上层取消不会被转换成普通失败。
- [ ] **非法缓存 ID**：`DanmuMatchCoordinator.toCachedMatch()` 仍把无法解析为 `Long` 的 episodeId 转为 `0`。应跳过不安全的缓存写入，同时保留此次精确匹配下载结果。
- [ ] **回归覆盖**：[DanmuMatchCoordinatorTest](../../../core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt) 当前包含来源支持判断与模糊候选不下载用例，未包含旧计划要求的精确下载、缓存命中、失败标记、空结果和非法 ID 分支。补测时应使用平台无关临时目录，避免现有硬编码 `/tmp`。

## TV 焦点

旧计划引入的职责划分已能在源码中找到：

| 职责 | 当前入口 |
| --- | --- |
| 页面／弹窗焦点所有者协议 | [DpadFocusOwner](../../../core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusOwner.kt) |
| 焦点保存、恢复与默认目标 | [DpadFocusScope](../../../core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusScope.kt) |
| 列表／网格导航与恢复 | [DpadRecyclerFocusDelegate](../../../core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadRecyclerFocusDelegate.kt) |
| 弹窗显示与关闭后的焦点处理 | [DpadDialogFocusController](../../../core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadDialogFocusController.kt) |
| TV 导航栏与内容区边界 | [TvShellFocusCoordinator](../../../app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvShellFocusCoordinator.kt) |

保留局部焦点所有权，不通过全局监听器持续抢焦点；触摸模式不强行请求 DPAD 焦点。旧 `RecyclerViewFocusDelegate` 仍在迁移期并存，不能据上述类型已存在推断全仓列表／弹窗已全部迁移。

[TvMainFocusSmokeTest](../../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvMainFocusSmokeTest.kt) 与 [DialogDpadFocusSmokeTest](../../../app/src/androidTest/java/com/xyoye/app/quality/DialogDpadFocusSmokeTest.kt) 提供 TV 分支的仪器检查入口。实际测试已采用 `Instrumentation.sendKeyDownUpSync`，不能复用旧计划中的 Activity 直接派发代码来声称覆盖真实按键路由。

历史设备证据及物理 TV、完整返回栈／播放矩阵等限制见 [租约计划 Task 9](../plans/2026-07-19-playback-transport-leases.md#task-9-enforce-boundaries-and-finish-implementation-verification) 与 [交接计划 Task 14](../plans/2026-10-27-playback-handoff-session.md#task-14-complete-integrated-verification-and-record-actual-evidence)。本次未重跑这些测试，也未把源码已存在、历史测试记录和完整产品验收混为一项结论。
