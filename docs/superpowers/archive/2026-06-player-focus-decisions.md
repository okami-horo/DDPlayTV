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

2026-11-03 重新核对现用路径并按用户要求清除低收益待办后，保留以下三项底层修复及对应回归覆盖。本轮仅整理待办，不修复生产代码。

- [ ] **网络文件名解析**：[NetworkDanmuSource](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt) 的回退分支直接调用 `URI(url)`；需要验证并处理无效 URI，保持匹配降级流程可继续。
- [ ] **协程取消传播**：同一类的重试方法包含泛化 `catch (Exception)`，没有单独重新抛出 `CancellationException`；还需连同网络请求的 `Result` 包装核对取消语义。当前播放器已有切源取消与请求版本校验，本项属于底层防御性补强，不能据此断言旧结果会绑定到新来源。
- [ ] **非法缓存 ID**：`DanmuMatchCoordinator.toCachedMatch()` 仍把无法解析为 `Long` 的 episodeId 转为 `0`。应跳过不安全的缓存写入，同时保留此次精确匹配下载结果。
- [ ] **回归覆盖**：[DanmuMatchCoordinatorTest](../../../core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt) 当前只有模糊候选不下载的一个用例，需补精确下载、缓存命中、失败标记、空结果及非法 ID 分支。现有用例已使用 `TemporaryFolder`，旧的统一 `/tmp` 迁移要求取消；[播放器测试](../../../player_component/src/test/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModelTest.kt) 已覆盖迟到结果、手动优先和搜索取消，后续补测聚焦实际缺口。

已移出：旧手动绑定页的空匹配状态清理。生产调用核对只找到 `RouteTable.Local.BindExtraSource` 的页面注册，没有当前导航调用；其 Fragment 只在初始化时发起匹配。用户未要求恢复入口，因此取消该遗留修复；播放器内的手动弹幕搜索及其测试继续保留。

## TV 焦点

以下保留旧计划的职责划分。三项旧焦点代理实现已由后续 [DpadRecyclerView 重写](../plans/2026-10-28-tv-dpad-library-rewrite.md)替代，已删除文件不再列为当前源码入口。

| 职责 | 当前入口 |
| --- | --- |
| 页面／弹窗焦点所有者协议 | [DpadFocusOwner](../../../core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusOwner.kt) |
| 焦点保存、恢复与默认目标 | 原 `DpadFocusScope` 已移除，当前归属见重写记录 |
| 列表／网格导航与恢复 | 原 `DpadRecyclerFocusDelegate` 已移除，当前列表由库处理 |
| 弹窗显示与关闭后的焦点处理 | 原 `DpadDialogFocusController` 已移除，当前窗口恢复逻辑见重写记录 |
| TV 导航栏与内容区边界 | [TvShellFocusCoordinator](../../../app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvShellFocusCoordinator.kt) |

保留局部焦点所有权，不通过全局监听器持续抢焦点；触摸模式不强行请求 DPAD 焦点。旧 `RecyclerViewFocusDelegate` 及另一套列表代理已随重写删除，不再保留“迁移期并存”待办；实际迁移范围与验证证据以重写记录为准。

[TvMainFocusSmokeTest](../../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvMainFocusSmokeTest.kt) 与 [DialogDpadFocusSmokeTest](../../../app/src/androidTest/java/com/xyoye/app/quality/DialogDpadFocusSmokeTest.kt) 提供 TV 分支的仪器检查入口。实际测试已采用 `Instrumentation.sendKeyDownUpSync`，不能复用旧计划中的 Activity 直接派发代码来声称覆盖真实按键路由。

历史设备证据及物理 TV、完整返回栈／播放矩阵等限制见 [租约计划 Task 9](../plans/2026-07-19-playback-transport-leases.md#task-9-enforce-boundaries-and-finish-implementation-verification) 与 [交接计划 Task 14](../plans/2026-10-27-playback-handoff-session.md#task-14-complete-integrated-verification-and-record-actual-evidence)。本次未重跑这些测试，也未把源码已存在、历史测试记录和完整产品验收混为一项结论。
