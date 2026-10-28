# 播放验证遗留项

整理日期：2026-10-28。本文接收旧变更记录中尚未关闭的验证任务；有效设计约束归入对应维护／设计文档，历史代码模板与逐步实施说明不继续作为当前指令。

当前[交接实施账本](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md) 保持 62/65，Task 14 的三项综合验收由用户负责。本文保留旧任务的具体范围并与该账本关联，不新增一份重复的交接进度，也不因后续已有部分构建／设备结果而自动判定所有原场景完成。关闭旧任务时需给出逐项证据映射。

## 顺序续播

来源：`add-seamless-part-playback` 的原任务 5.1、5.2；原勾选记录为 15/17。本轮未执行这些验证，以下两项继续待验收。现行职责和行为边界见[活动会话设计第 12 节](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md#123-顺序续播的产品边界)。

- [ ] **原 5.1：Media3 集成／冒烟场景**。分别记录以下四类结果：有下一项时在原播放页续播；无下一项时正常完成；手动切换后旧准备结果／旧切换回调到达不覆盖当前来源和绑定；预准备失败时当前项不受影响，完成后按目标可用性降级切源或正常完成。同时核对目标项标题、历史、弹幕、字幕和外挂音轨，避免沿用上一项文件级绑定。
- [ ] **原 5.2：相关 Gradle 验证与 TV 播放主路径**。将适用的构建、单元测试、lint／治理结果与上述场景对应，并以纯 DPAD 完成进入播放器、续播／完成、返回及退出，确认焦点可见、可达、可返回。Gradle 证据必须包含命令、源码版本／工作区差异以及尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

已有 [SequentialPlaybackCoordinatorTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinatorTest.kt)、[Media3SequentialPlaybackAdapterTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapterTest.kt) 和 [ActivePlaybackSessionTest](../../player_component/src/test/java/com/xyoye/player_component/playback/session/ActivePlaybackSessionTest.kt) 可作为策略、队列和会话层证据入口；源码中存在这些用例不等于已经完成上述播放器集成与 TV 冒烟。

交接 Task 14 的字幕／弹幕连续性及完整导航验收与这里存在交集；只有实际覆盖对应场景的记录才能复用，同一证据无需重复执行，尚未覆盖的场景继续保留。

## Bilibili 异常播完（已退役）

来源为原 fix-bilibili-unexpected-completion 的任务 2.3，历史记录为 5/6。2026-10-28 用户确认移除全部 Bilibili 功能，直播与长视频断流、URL 过期、风控及心跳的现场验收不再适用；这不表示原场景曾经验证通过。

交接 Task 14 中 B 站相关验收同样由该产品决策替代，其余 FTP、BT、字幕／弹幕连续性、进程恢复与移动端／实体 TV 导航仍待用户验收。见[Bilibili 退役说明](bilibili-playback-policy.md)。
