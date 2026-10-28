# 项目目录整理追踪

检查与更新日期：2026-10-28。

本记录对应目录结构审计的五项发现。第 1、2 项已处理并提交为 `b545eb3ba`；用户随后授权整理第 3 项文档。第 4、5 项仍待后续实施。文档整理没有调整业务模块、源码包名、构建依赖或既有业务验收状态。

## 状态总览

| 编号 | 事项 | 优先级 | 状态 |
| --- | --- | --- | --- |
| DIR-01 | 停止异常日志增长并清理巨型文件 | 高 | 已完成，本地环境操作 |
| DIR-02 | 清理已跟踪的临时截图和 Python 缓存 | 中 | 已完成，已提交 |
| DIR-03 | 明确文档归属、修正失效路径和归档旧方案 | 中 | 已完成，工作区变更待提交 |
| DIR-04 | 修正按历史包名推断模块归属的问题 | 中 | 待处理 |
| DIR-05 | 明确辅助工具、脚手架和工作流的保留策略 | 低 | 待处理 |

## DIR-01：异常日志

- 文件：`.tmp/handoff-task14-emulator2.stdout.log`；删除前大小为 **72,907,257,796 字节（约 67.9 GiB）**。
- 观察：持续重复创建 AVD `snapshot.lock.lock` 失败，错误码为 `5`；同一 AVD/5558 端口存在两个模拟器进程实例。此处记录观察结果，不把底层锁失败原因视为已经修复。
- 定位：Windows Restart Manager 文件占用查询确认异常日志由进程 `14572` 及其崩溃处理子进程 `28568` 占用。正常实例 `22776` 持有端口 `5558/5559`，日志位于 `handoff-task14-emulator3.stdout.log`。不能仅凭启动时间判断应停止哪个实例。
- 处理：结束上述异常进程，独占打开日志确认占用已释放，保存末尾采样和元数据后删除巨型文件。正常实例及启动器保持运行，`adb devices -l` 确认 `emulator-5558` 为 `device`。
- 本机证据：`.tmp/directory-cleanup-20261028/emulator-log-evidence.json`。该文件受忽略规则保护，不作为跨机器可用的长期文档。
- 后续启动同一 AVD 前，先核对现有设备和进程；若重复报错，停止对应失败实例，避免无界重定向日志。

## DIR-02：已跟踪的临时产物

- 根目录 8 张 `tmp_*.png` 已移至 `.tmp/directory-cleanup-20261028/screenshots/`，逐张核对移动前后 SHA-256 一致；同目录上级保留 `screenshots-manifest.json`。
- 已在 `b545eb3ba` 中删除 `scripts/__pycache__/module_deps_snapshot.cpython-310.pyc`，使其退出版本控制；现有 `__pycache__/`、`*.pyc` 忽略规则继续生效。
- 新增仅针对仓库根目录的 `/tmp_*.png` 忽略规则，防止临时截图再次进入提交。
- 未发现这些截图或缓存被受版本控制的文本文件引用；保留的本机截图不计入仓库内容。

## DIR-03：文档归属与历史内容

处理结果与归属：

| 原问题 | 整理结果 |
| --- | --- |
| 多套说明缺少统一入口 | [文档导航](../README.md) 按任务和职责组织资料，根 README 提供入口；设计／账本有独立导航，OpenSpec 的有效内容按主题整合，见下文 |
| `TODOs/opencc_ass.md` 混合旧路径、未实施方案及无验证的性能数值 | 合并为[简繁转换说明](../player/subtitle-language-conversion.md)，核对初始化、ICU/原生回退；明确当前弹幕转换与待实现字幕转换 |
| `TODOs/架构分析.md` 是工具对话和失效统计 | 删除原始对话；有效主题由[模块治理](module_dependency_governance.md)、[划分评估](over_partitioning_analysis.md)、[参考决策](2026-10-27-playback-refactor-reference-review.md)及 DIR-04 承担 |
| 四份 June 执行模板包含大段过时示例 | 合并为[行为决策与遗留工作](../../docs/superpowers/archive/2026-06-player-focus-decisions.md)，保留五项未完成弹幕修复／补测，原文可从 Git 历史查询 |
| 模块治理把已完成事项当成待办，依赖快照遗漏测试依赖 | 按 buildSrc/Gradle 修正规则、CI 范围、允许列表，保留 DR-0001/2/3，重新生成 21 模块、88 条声明的快照 |
| 开发、隐私、日志、mpv 手册夹有过期或无法证明的内容 | 删除双语重复和无来源操作步骤，按 SDK/NDK、真实 API、HTTP 路由、日志留存及 JNI/打包实现修正说明 |
| 旧设计与 agent 记忆仍写暂停阶段或重构前现状 | 保持重要账本路径和原始证据，修正角色／背景／当前进度，精简重复记忆及独立旧 changelog |

保留有价值的设计理由、验证失败和限制，不因历史材料清理将待办标为完成。传输租约仍为 37/37，交接仍为 62/65；三项用户集成验收没有关闭。首轮整理曾保留 OpenSpec 原记录；随后按用户追加要求完成下述价值复核与整合。

完成条件：

- [x] 明确维护文档、设计/执行计划、规格、历史记录及 agent 记忆各自的归属，并提供统一导航。
- [x] 核对并修正上述失效事实；旧方案保留有价值的决策依据，明确归档状态与替代入口。
- [x] 如移动文件，同步检查 Markdown 链接、工具约定路径、构建门禁和 CI 引用。

### OpenSpec 记录价值复核

复核日期：2026-10-28。五组记录的原始状态与有效信息去向如下；原任务数量仅用于说明迁移前状态，不代表本次重新执行了验证。

后续产品决策：全部 Bilibili 功能已退役，下表中的相关策略与异常播完实现现仅属于历史。原 2.3 现场验收不再适用，不记为通过；对应文档现为[退役与升级说明](../player/bilibili-playback-policy.md)。其余迁移与验收结论保持原口径。

| 原变更 | 原状态 | 有效内容与归属 |
| --- | --- | --- |
| `add-seamless-part-playback` | 15/17 | 顺序、独立资源绑定、过期回调隔离和降级约束并入[交接设计](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md)；旧 Coordinator/StateApplier 编排已被活动会话替代；5.1/5.2 验证保留在[播放验证待办](../player/playback-validation-followup.md) |
| `adjust-bilibili-heartbeat-logic` | 10/10 | 模式迁移、服务端暂停状态、参数映射与诊断整合为[Bilibili 播放策略](../player/bilibili-playback-policy.md) |
| `fix-bilibili-unexpected-completion` | 5/6 | 播完可信度及恢复归入同一播放策略，按源码修正十分钟严格边界和未知时长情况；2.3 现场冒烟仍在播放验证待办中 |
| `fix-libass-subtitle-review-issues` | 11/11 | GPU 恢复条件、实时偏移和有限去重整合为[字幕渲染说明](../player/subtitle-rendering.md)；明确没有实际 CPU 回退，不能承诺所有 GPU 错误均自动恢复 |
| `archive/2026-04-06-improve-test-coverage` | 15/15 | 统一 JaCoCo 口径、风险优先补测、测试分层与渐进门槛归入[测试证据指南](../monitoring/test_evidence_format.md)；旧百分比缺少可复核报告，不作为现行基线 |

原提案的大段背景、被替代的实施步骤、重复的已完成任务模板不再保留另一套副本。`openspec/specs/` 没有已发布规格，`config.yaml` 只有默认 schema 和示例注释；构建、CI 和项目脚本没有依赖这些记录。完成信息迁移后清理整个 `openspec/` 记录目录，保留工作流技能和提示词，其去留仍由 DIR-05 决定。

复核还发现未锚定的 `specs/` 忽略规则隐藏了五份 delta spec。已将这些规格与 proposal/design/tasks 一并审阅，并在清理前备份全部 27 个文件及 SHA-256 清单到本机 `.tmp/openspec-cleanup-20261028/`。受版本控制的原记录仍可从 Git 及本次差异追溯；未跟踪规格保留在该本机备份中。备份只用于回查，现行约束与未完成验证以上表链接为准。

三项原未完成任务逐项迁移，没有因删除原目录或后续账本存在部分相关测试就改为完成。交接账本中用户负责的三项综合验收与这些原任务分别保留，不合并计数。

## DIR-04：包名与模块归属

证据与范围：

- [UserInfoHelper](../../user_component/src/main/java/com/xyoye/common_component/utils/UserInfoHelper.kt) 属于 `user_component`，[QrCodeHelper](../../storage_component/src/main/java/com/xyoye/common_component/utils/QrCodeHelper.kt) 属于 `storage_component`，两者仍使用 `com.xyoye.common_component.utils`。
- [覆盖率统计脚本](../../parse_coverage.py) 把该包统一归入 `core_system_component`，也将分散在不同模块中的 `common_component.network` 按前缀归入网络模块；当前映射无法可靠代表物理模块归属。

完成条件：

- [ ] 覆盖率统计依据实际模块信息归属，验证跨模块同包名的样例不会错误合并到单一模块。
- [ ] 区分有意保留的兼容别名与需要迁移的业务包；按实际维护需要逐步收敛，避免无收益的全量改包名。
- [ ] 涉及包名变化时核对引用、路由、反射/JNI 和对应构建、测试。

## DIR-05：辅助工具与工作流

完成条件：

- [ ] 将根目录 `parse_coverage.py` 归入合适的 `scripts/` 子目录（候选 `scripts/testing/`），同步 [测试证据文档](../monitoring/test_evidence_format.md) 等调用入口。
- [ ] 核实 `other/DanDanPlayTemplate-1.0.0.jar` 和 `other/module-dandan.sh` 的使用方；决定保留并说明用途、迁移或归档。
- [ ] 确认没有实际 Node 工具依赖后，再处理 `package.json`（空对象）与无依赖的 `package-lock.json`。
- [ ] 明确 `.specify`、`.spec-workflow`、Superpowers 及保留的 OpenSpec 技能／提示词是否继续使用。OpenSpec 历史记录已按 DIR-03 整合；未锚定的 `specs/` 忽略规则也会隐藏嵌套规格，需结合后续工作流决定生成目录与忽略策略。
- [ ] 修正 `scripts/dev-env/setup-local-env.ps1` 的参数块位置和首次创建 `local.properties` 的行为；本轮只读 Parser 核对发现参数块位于赋值后（2 项解析错误），脚本未执行或修改。修复后验证参数解析、保留既有凭证及 `sdk.dir` 的首次创建／更新。

## 验证记录与边界

### DIR-01/02 清理阶段（2026-10-28）

- 已执行文件存在性、截图哈希、忽略规则、引用及 Git 差异检查，并复核设备状态。
- 当时目录审计执行 `verifyModuleDependencies --offline --console=plain`，结果为 `BUILD SUCCESSFUL`。
- `change/` 被发布工作流直接引用，`player_component/libs/` 有明确 JNI 构建配置；二者不属于可直接清理的临时目录。

### DIR-03 文档整理阶段（2026-10-28）

- 检查 71 份非 skill 的受版本控制／待新增 Markdown：217 个本地链接、3 个标题锚点均有效，路径大小写匹配；检查 Unicode 中文内容、替换字符、异常问号串与代码围栏。
- 对 7 份保留任务账本逐一比较 HEAD 与工作区：勾选／未勾选数量保持不变，其中租约为 37/37、交接为 62/65。
- 使用现有快照生成脚本重算 Markdown/JSON，与提交候选文件逐字一致：21 个显式模块、88 条直接依赖声明（85 条生产、3 条测试）。6 份 AAR README 的 SHA-256 与实际工件一致。
- 本轮重新执行 `verifyModuleDependencies verifyPlaybackBoundaries --offline --console=plain`，输出尾部为 **BUILD SUCCESSFUL in 6s**；本机输出位于 `.tmp/dir03-gradle.log`。
- `git diff --check` 通过；改动范围仅为文档和自动生成的依赖快照，没有改动源码、工作流、构建规则或业务任务勾选。保留被工具读取的固定治理文档和基线路径。
- 本轮没有重新执行完整 lint、JVM 测试或设备交互验收；先前业务测试记录保留原日期和范围，不作为本轮重新执行。DIR-04/05、五项弹幕修复和用户拥有的三项集成验收仍待后续处理。

### OpenSpec 整合后复核（2026-10-28）

- 对 proposal/design/tasks 及五份被忽略的 delta spec 完成价值审查；Bilibili、顺序续播、libass 和覆盖率迁移内容另经独立只读复查，并核对关键源码和现存测试的真实范围。
- 清理前逐一确认 27 个原文件与备份的 SHA-256 相同；清理后复核备份清单完整，`openspec/` 已不存在。技能和提示词未删除；项目构建、CI 与脚本无旧记录路径依赖。
- 最终 58 份 Markdown 的 236 个本地链接、4 个标题锚点、路径大小写、中文编码和代码围栏检查通过。新文档确实包含中文，未出现替换字符或问号串损坏。
- 原五组任务状态按上表保留出处，原 5.1、5.2、2.3 恰好三项继续未勾选；保留的两份实施账本逐条任务文字与 HEAD 一致，仍为 37/37 和 62/65。
- 用户原暂存区的 `git ls-files --stage -z` 快照与当前逐字节一致；新增整合内容和旧目录清理仅在工作区，未提交。工作区差异只涉及文档和此前的自动依赖快照，无源码、构建或 CI 改动；`git diff --check` 与 `git diff --cached --check` 通过。
- 本次没有重复执行业务测试或 Gradle。上阶段治理检查的输入未变，已复读 `.tmp/dir03-gradle.log` 尾部，复用其 **BUILD SUCCESSFUL in 6s** 结果；不将其算作本次新执行的测试。
