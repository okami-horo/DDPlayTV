# document/ 文档索引与维护状态

本目录用于存放 **DDPlayTV** 的项目文档。由于项目来自上游 fork 且经历过多轮重构，历史上曾残留部分“上游文档/模板文档”，内容可能与当前仓库实现不一致。

本文用于：
- 给出 `document/` 内文档的用途与入口
- 标注维护状态（维护中 / 需更新 / 已归档）
- 约定文档更新时需要同步的动作（例如依赖快照、门禁基线）

## 快速入口

- 通用说明
  - `document/Contributing.md`：贡献指南（中英文）
  - `document/project_local_dev_env.md`：项目私有 JDK / Android SDK 本地环境说明
  - `document/Privacy policy.md`：隐私政策（权限 / 日志 / 第三方服务）
  - `document/Third_Party_Libraries.md`：主要第三方依赖清单（非穷举）
- 架构治理
  - [项目目录整理追踪](architecture/directory-hygiene-followup.md)：临时产物清理结果及文档、包名、工具归属的后续任务
  - `document/architecture/architecture_governance_guardrails.md`：推荐门禁集合入口
  - `document/architecture/module_dependency_governance.md`：模块依赖治理规范（v2）
  - `document/architecture/module_dependencies_snapshot.md`：直接依赖快照（自动生成）
  - `document/architecture/module_dependencies_snapshot.json`：直接依赖快照（机器可读）
  - `document/architecture/legacy_pager_api_baseline.tsv`：旧版 Pager API 存量基线
- 运行监控
  - `document/monitoring/logging-system.md`：日志系统维护指南（本仓库现状）
  - `document/monitoring/test_evidence_format.md`：覆盖率改进类变更的测试证据格式
- MPV
  - `document/mpv-build-notes.md`：`libmpv.so` 本地编译记录
  - `document/mpv_conf_supported_options.md`：项目实际用到/暴露的 mpv 配置项清单

> 当前 `document/` 目录中**不包含** `support/` 或 `release-notes/` 子目录；如后续新增，请同步更新本文索引与维护状态表。

## 维护状态（建议以此为准）

| 文档 | 状态 | 备注 |
| --- | --- | --- |
| `document/README.md` | 维护中 | 仅列出当前仓库实际存在的文档入口 |
| `document/Contributing.md` | 维护中 | 通用贡献指南（含中英文） |
| `document/project_local_dev_env.md` | 维护中 | 与 `.dev-env` 脚本 / 本地 SDK 约定强关联 |
| `document/Privacy policy.md` | 维护中 | 需随权限/SDK 变化同步更新 |
| `document/Third_Party_Libraries.md` | 维护中 | 主要依赖清单（非穷举） |
| `document/architecture/*.md` | 维护中 | 与构建门禁强关联，变更需同步快照/基线 |
| `document/architecture/module_dependencies_snapshot.{md,json}` | 自动生成 | 修改 `project(...)` 依赖后需重新生成 |
| `document/architecture/legacy_pager_api_baseline.tsv` | 基线文件 | 更新旧版 Pager API 存量后需同步维护 |
| `document/monitoring/logging-system.md` | 维护中 | 与日志导出方式 / 安全约束强关联 |
| `document/monitoring/test_evidence_format.md` | 维护中 | 与覆盖率门禁 / PR 证据格式强关联 |
| `document/mpv-*.md` | 维护中 | 与 mpv 产物/选项强关联 |

## 文档联动（需要同步的动作）

- 变更模块 `project(...)` 依赖后：运行 `python scripts/module_deps_snapshot.py --write`（Windows）或 `python3 scripts/module_deps_snapshot.py --write`（Linux/macOS）更新 `document/architecture/module_dependencies_snapshot.*`。
- 更新 `ViewPager`/`FragmentPagerAdapter` 存量后：同步维护 `document/architecture/legacy_pager_api_baseline.tsv`，保证 `./gradlew verifyLegacyPagerApis` 门禁可用。
- 调整日志导出、脱敏规则或 HTTP 日志服务安全约束后：同步检查 `document/monitoring/logging-system.md`、`document/architecture/log_redaction_policy.md`、`document/Privacy policy.md`。
- 调整 `.dev-env` 脚本、JDK / SDK 版本约定或 `local.properties` 写入策略后：同步更新 `document/project_local_dev_env.md`。
- 调整覆盖率门禁、JaCoCo 证据格式或 PR 交付规范后：同步更新 `document/monitoring/test_evidence_format.md`。
