# 项目文档导航

这里是 DDPlayTV 的文档总入口。当前维护说明以源码、Gradle 配置和实际验证结果为依据；设计、提案和历史测试记录分别标注，不能直接当作当前实现或本次测试通过的证明。

## 按任务查阅

| 需要了解 | 入口 |
| --- | --- |
| 产品功能与构建入口 | [项目 README](../README.md) |
| TMDB 统一影视/番剧元数据与 UI 重设计 | [范围与待办](media-library/tmdb-metadata-followup.md)、[刮削与模块设计](../docs/superpowers/designs/2026-10-31-tmdb-media-library-design.md)、[实施与验证](../docs/superpowers/plans/2026-10-31-tmdb-media-library.md)、[网页原型](prototypes/tv-ui/README.md) |
| 纯 TV 收敛范围、执行与验证 | [实施记录](../docs/superpowers/plans/2026-10-29-tv-only.md) |
| 开发、提交与验证要求 | [贡献指南](Contributing.md)、[本地开发环境](project_local_dev_env.md) |
| 模块职责与依赖限制 | [模块治理](architecture/module_dependency_governance.md)、[构建门禁](architecture/architecture_governance_guardrails.md) |
| 播放会话、资源所有权与恢复设计 | [设计与执行账本导航](../docs/superpowers/README.md) |
| Media3/mpv 内核选择、VLC 退役与旧配置回退 | [播放内核说明](player/player-kernels.md) |
| Media3 默认解码选择、初始化回退与实际运行日志 | [Media3 解码说明](player/media3-decoding.md) |
| ASS/SSA 字幕渲染、偏移与 GPU 恢复 | [字幕渲染](player/subtitle-rendering.md) |
| libass 接入缺口、同类项目比较与修复验收 | [libass 接入审查](player/libass-integration-audit.md) |
| Media3 应用 libass 时间、字体、帧请求与资源边界 | [设计契约](../docs/superpowers/designs/2026-10-29-media3-libass-integration-design.md)、[实施与验证](../docs/superpowers/plans/2026-10-30-media3-libass.md)；精确呈现与性能优化保留测量门槛，mpv 保持原生字幕 |
| 简繁转换现状和字幕待实现需求 | [简繁转换](player/subtitle-language-conversion.md) |
| 顺序续播的集成与 TV 验收 | [播放验证待办](player/playback-validation-followup.md) |
| mpv 原生库与运行参数 | [产物来源与更新](mpv-build-notes.md)、[配置项](mpv_conf_supported_options.md) |
| 日志收集与问题复现证据 | [日志指南](monitoring/logging-system.md)、[测试证据](monitoring/test_evidence_format.md) |
| 网络、隐私和依赖说明 | [TLS 策略](architecture/network_tls_policy.md)、[日志脱敏](architecture/log_redaction_policy.md)、[隐私说明](<Privacy policy.md>)、[第三方库](Third_Party_Libraries.md) |
| 模块归属、辅助工具与工作流待办 | [目录维护待办](architecture/directory-hygiene-followup.md) |
| 旧计划保留下来的行为决策和未完成修复 | [播放器与焦点决策整理](../docs/superpowers/archive/2026-06-player-focus-decisions.md) |

## 目录职责

| 位置 | 保存什么 | 使用边界 |
| --- | --- | --- |
| `README.md` | 产品简介、功能和文档入口 | 不堆放实现步骤与执行日志 |
| `document/` | 当前维护的开发、架构、播放器与运行说明 | 同一主题保留一个主要维护位置 |
| `docs/superpowers/` | 设计决策、执行账本与精简历史记录 | [导航](../docs/superpowers/README.md) 区分设计、结果与剩余验收 |
| `repository/*/README.md` | 对应本地 AAR 的来源、校验和、授权线索与升级步骤 | 随该工件维护，入口见下表 |
| `change/` | 面向发布版本的更新说明 | 发布工作流直接读取，移动时必须同步 CI |
| `memory-bank/` | agent 使用的项目上下文、检索图和历史验证摘要 | 英文；重要事实仍须回到源码和原始证据核对 |
| `.specify/`、`.spec-workflow/`、`.github/prompts/` | 工作流规则、模板和提示词 | 属于工具输入，非产品功能说明；是否继续使用由 DIR-05 跟踪 |
| `AGENTS.md` | agent 工作规则 | 不与产品说明、历史对话或执行结果混放 |

## 架构资料与维护状态

| 资料 | 职责与状态 |
| --- | --- |
| [模块依赖治理](architecture/module_dependency_governance.md) | 当前规则与保留的关键决策 |
| [直接依赖快照](architecture/module_dependencies_snapshot.md)、[JSON](architecture/module_dependencies_snapshot.json) | 从 Gradle 声明生成的文档工件，不手工维护依赖边 |
| [旧 Pager 基线](architecture/legacy_pager_api_baseline.tsv) | 构建门禁读取的存量基线，非迁移完成证明 |

## 本地 AAR 资料

[弹幕](../repository/danmaku/README.md) · [沉浸栏](../repository/immersion_bar/README.md) · [面板切换](../repository/panel_switch/README.md) · [7-Zip](../repository/seven_zip/README.md) · [迅雷](../repository/thunder/README.md) · [视频缓存](../repository/video_cache/README.md)

## 维护约定

- 当前实现以 [settings.gradle.kts](../settings.gradle.kts)、[版本配置](../buildSrc/src/main/java/Versions.kt)、模块构建文件和源码为准。记录“已验证”时给出日期、命令和范围；旧测试结果不能包装成本次验证。
- 修改 `project(...)` 依赖后，通过 [快照脚本](../scripts/module_deps_snapshot.py) 更新快照，并执行模块治理检查；具体命令见治理文档。
- 改变 Pager 存量、日志安全、SDK/NDK 或 CI 入口时，同步对应说明及基线。
- 设计文档保存理由与约束；执行账本保存完成状态和证据；操作指南只保留当前可用步骤。未完成需求明确列为待办。
- 移动或合并文档时同步相对链接、agent 检索入口、脚本和 CI 中的固定路径。模板、自动生成文件及历史发布说明不按普通手册随意改写。
