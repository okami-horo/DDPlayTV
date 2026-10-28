# 播放验证遗留项

整理日期：2026-10-28。本文保留尚未完成的顺序续播验证；有效设计约束归入对应维护／设计文档。

综合验收由用户负责，进度见[交接实施账本](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)。关闭以下验证项时需给出对应场景的实际证据，不能以部分构建／设备结果代替完整验收。

## 顺序续播

以下两项继续待验收。现行职责和行为边界见[活动会话设计第 12 节](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md#123-顺序续播的产品边界)。

- [ ] **Media3 集成／冒烟场景**。分别记录以下四类结果：有下一项时在原播放页续播；无下一项时正常完成；手动切换后旧准备结果／旧切换回调到达不覆盖当前来源和绑定；预准备失败时当前项不受影响，完成后按目标可用性降级切源或正常完成。同时核对目标项标题、历史、弹幕、字幕和外挂音轨，避免沿用上一项文件级绑定。
- [ ] **相关 Gradle 验证与 TV 播放主路径**。将适用的构建、单元测试、lint／治理结果与上述场景对应，并以纯 DPAD 完成进入播放器、续播／完成、返回及退出，确认焦点可见、可达、可返回。Gradle 证据必须包含命令、源码版本／工作区差异以及尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

已有 [SequentialPlaybackCoordinatorTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinatorTest.kt)、[Media3SequentialPlaybackAdapterTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapterTest.kt) 和 [ActivePlaybackSessionTest](../../player_component/src/test/java/com/xyoye/player_component/playback/session/ActivePlaybackSessionTest.kt) 可作为策略、队列和会话层证据入口；源码中存在这些用例不等于已经完成上述播放器集成与 TV 冒烟。

交接账本中的字幕／弹幕连续性及完整导航验收与这里存在交集；只有实际覆盖对应场景的记录才能复用，同一证据无需重复执行，尚未覆盖的场景继续保留。
