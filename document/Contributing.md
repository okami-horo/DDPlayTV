# 贡献指南

项目以 Android TV 遥控器操作为先，使用 Kotlin、XML View 和 Gradle 多模块结构。功能见 [项目首页](../README.md)，资料见 [文档索引](README.md)，修改前阅读 [仓库约定](../AGENTS.md)。

## 问题与需求

问题报告提供应用版本、设备型号、Android 版本、内核、媒体来源、复现步骤、预期与实际结果。TV 问题记录按键顺序；播放问题说明发生在启动、切集、Seek 还是退出阶段。无法在另一台设备复现不代表问题无效，应直接说明验证范围。

日志与截图只保留定位所需内容，检查账号、Cookie、Token、远端地址等敏感信息。采集方法见 [日志维护指南](monitoring/logging-system.md)。需求应描述实际场景和期望结果；跨模块或大幅交互调整先明确设计与范围。

## 修改与提交

1. 使用主题分支，使一次变更围绕一个明确目的。
2. 遵守模块边界、现有风格；跨业务模块通过契约与路由协作。
3. 保留可复现的行为证据，避免混入无关格式化、搬移与功能修改。
4. PR 说明解决的问题、影响模块、验证命令与结果；布局变化附界面证据，关联问题记录。

提交信息沿用 `<type>: <summary>`，例如 `fix: player_component 修复切集恢复`。重构说明保留的行为；缺陷修复明确前后差异，不要求保留错误行为。

## 验证

在根目录运行与改动匹配的命令：

```bash
./gradlew :<module>:testDebugUnitTest
./gradlew assembleDebug
./gradlew lint
```

模块依赖变化时运行 `verifyModuleDependencies`；播放边界变化时运行 `verifyPlaybackBoundaries`。完整门禁见 [架构治理](architecture/architecture_governance_guardrails.md)，Windows 环境见 [开发环境](project_local_dev_env.md)。

必须查看 Gradle 尾部，报告 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。成功命令不能代替运行行为验证，说明未覆盖的设备、媒体或交互。

TV 页面至少通过纯 DPAD 完成进入、选择、操作和返回，检查默认焦点、可见反馈、可达性与恢复。仪器测试先确认设备为 `device`，不能把 `offline`/`unauthorized` 视为可用设备。覆盖率变更见 [证据格式](monitoring/test_evidence_format.md)。

## 文档

面向使用者和开发者的维护文档使用中文 Markdown；agent 记忆保存在 `memory-bank/`。按文档索引放置内容，复用权威文档，避免复制规则。引用源码或配置，区分当前实现、历史决策、方案与验证结果。移动后同步链接、脚本和 CI；整理文档不会把历史测试变成本轮验证。
