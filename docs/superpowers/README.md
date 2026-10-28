# 设计、执行账本与历史决策

当前功能与开发说明从 [项目文档导航](../../document/README.md) 查阅。本目录保留需要理解“为什么这样设计”和“实际执行到哪里”的资料；文件名中的早期日期不代表实现状态停留在当时。

## 当前设计与执行证据

| 主题 | 设计依据 | 执行状态与证据 |
| --- | --- | --- |
| Bilibili 全部功能退役 | 用户确认由 PiliPlus 接手，包括弹幕来源 | [移除执行记录](plans/2026-10-28-remove-bilibili.md) |
| TV 遥控器与焦点重写 | [DpadRecyclerView 重写设计](designs/2026-10-28-tv-dpad-library-rewrite-design.md) | 待用户确认；仅完成源码与选型核查，尚未接入依赖或实施 |
| 播放交接、活动会话与恢复 | [交接设计](designs/2026-07-18-playback-handoff-design.md) | [交接账本](plans/2026-10-27-playback-handoff-session.md)：历史计数 62/65，保留功能的集成验收仍由用户负责 |
| 传输资源所有权与租约 | [传输租约设计](designs/2026-07-19-playback-transport-leases-design.md) | [租约账本](plans/2026-07-19-playback-transport-leases.md)：历史计数 37/37；当前协议、设备及验证范围以账本为准 |

两份播放设计的早期背景描述的是重构前系统；后续决策和实际源码已经替换部分旧入口。不要重新实施账本中已经完成的迁移，也不要因自动检查通过就将用户验收改为完成。完整后台／画中画能力仍不能由当前元数据服务适配推断。

## 历史决策与保留待办

[2026 年 6 月播放器与焦点决策整理](archive/2026-06-player-focus-decisions.md) 合并了四份旧执行模板，保留字幕开关、弹幕匹配和 DPAD 的有效约束，以及五项尚未完成的弹幕修复／补测。旧模板中的大段示例代码和过时路径已清理，原文可从 Git 历史查询。

字幕简繁转换的现状与待实现需求见 [播放器说明](../../document/player/subtitle-language-conversion.md)。历史架构原始对话不再作为现行方案，模块边界以 [治理文档](../../document/architecture/module_dependency_governance.md) 为准。

顺序续播约束见交接设计，集成与 TV 场景的剩余验收见[播放验证待办](../../document/player/playback-validation-followup.md)。字幕渲染与其他现行指南从[项目文档导航](../../document/README.md)查阅。

## 维护方式

- `designs/` 保存仍有约束价值的设计决策；实现变化时修正状态与失效假设，避免变成另一套实现说明。
- `plans/` 保存正在执行或仍承担原始验证证据的账本，明确未完成项；不删除有价值的失败记录和验收限制。
- `archive/` 保存精简后的历史决策与有出处的遗留事项，不保存原始聊天、工具输出或可从 Git 恢复的大段旧代码。
- 文档整理不改变任务验收状态。后续测试或用户验收发生后，再更新相应账本与导航。
