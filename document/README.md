# document/ 文档索引与维护状态

本目录用于存放 **DDPlayTV** 的项目文档。由于项目来自上游 fork 且经历过多轮重构，历史上曾残留部分“上游文档/模板文档”，内容可能与当前仓库实现不一致。

本文用于：
- 给出 `document/` 内文档的用途与入口
- 标注维护状态（维护中 / 需更新 / 已归档）
- 约定文档更新时需要同步的动作（例如依赖快照、门禁基线）

## 快速入口

- 架构治理
  - `document/architecture/architecture_governance_guardrails.md`：推荐门禁集合入口
  - `document/architecture/module_dependency_governance.md`：模块依赖治理规范（v2）
  - `document/architecture/module_dependencies_snapshot.md`：直接依赖快照（自动生成）
- 运行监控
  - `document/monitoring/logging-system.md`：日志系统维护指南（本仓库现状）
- Bilibili
  - `document/support/bilibili-live-playback.md`：B 站历史记录含直播条目播放适配说明
  - `document/support/bilibili-follow-live-library.md`：关注直播目录（`/follow_live/`）设计/实现说明
- MPV
  - `document/support/mpv-115-proxy.md`：115 风控场景的本地 HTTP 代理方案
  - `document/mpv-build-notes.md`：`libmpv.so` 本地编译记录
  - `document/mpv_conf_supported_options.md`：项目实际用到/暴露的 mpv 配置项清单
- 发行说明
  - `document/release-notes/media3-migration.md`：TYPE_EXO 播放内核迁移到 Media3 的说明（以本仓库为准）

## 维护状态（建议以此为准）

| 文档 | 状态 | 备注 |
| --- | --- | --- |
| `document/Contributing.md` | 维护中 | 通用贡献指南（含中英文） |
| `document/Privacy policy.md` | 维护中 | 需随权限/SDK 变化同步更新 |
| `document/Third_Party_Libraries.md` | 维护中 | 主要依赖清单（非穷举） |
| `document/architecture/*` | 维护中 | 与构建门禁强关联，变更需同步快照/基线 |
| `document/monitoring/logging-system.md` | 维护中 | 与日志路径/导出方式强关联 |
| `document/support/bilibili-*.md` | 维护中 | 与 BilibiliStorage 目录/取流策略强关联 |
| `document/support/mpv-115-proxy.md` | 维护中 | 与本地代理 + MPV 行为强关联 |
| `document/mpv-*.md` | 维护中 | 与 mpv 产物/选项强关联 |
| `document/release-notes/media3-migration.md` | 维护中 | 随 Media3 版本与开关策略演进 |

## 文档联动（需要同步的动作）

- 变更模块 `project(...)` 依赖后：运行 `python3 scripts/module_deps_snapshot.py --write` 更新 `document/architecture/module_dependencies_snapshot.*`。
- 更新 `ViewPager`/`FragmentPagerAdapter` 存量后：同步维护 `document/architecture/legacy_pager_api_baseline.tsv`，保证 `./gradlew verifyLegacyPagerApis` 门禁可用。
