# 项目目录整理追踪

检查与更新日期：2026-10-28。

本记录对应目录结构审计的五项发现。用户已授权处理第 1、2 项；第 3–5 项仅登记，留待后续实施。本次没有调整业务模块、源码包名或构建依赖，也没有创建 Git 提交。

## 状态总览

| 编号 | 事项 | 优先级 | 状态 |
| --- | --- | --- | --- |
| DIR-01 | 停止异常日志增长并清理巨型文件 | 高 | 已完成，本地环境操作 |
| DIR-02 | 清理已跟踪的临时截图和 Python 缓存 | 中 | 已完成，工作区变更待提交 |
| DIR-03 | 明确文档归属、修正失效路径和归档旧方案 | 中 | 待处理 |
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
- 已删除 `scripts/__pycache__/module_deps_snapshot.cpython-310.pyc`，使其在本次工作区差异中退出版本控制；现有 `__pycache__/`、`*.pyc` 忽略规则继续生效。
- 新增仅针对仓库根目录的 `/tmp_*.png` 忽略规则，防止临时截图再次进入提交。
- 未发现这些截图或缓存被受版本控制的文本文件引用；保留的本机截图不计入仓库内容。

## DIR-03：文档归属与历史内容

证据与范围：

- 项目说明分布于 `document/`、`docs/superpowers/`、`TODOs/`、`openspec/`，现有 [文档索引](../README.md) 尚未统一说明这些目录的职责。
- [字幕方案](../../TODOs/opencc_ass.md) 仍引用已不存在的 `common_component/`；OpenCC 源码、资源及原生库目前位于 `player_component/`。
- [模块依赖治理规范](module_dependency_governance.md) 的“不在范围”段落仍声称存在 `core_notification_component/`、`core_service_component/`、`remote_component/`、`Han1meViewer/`，本次检查均不存在。

完成条件：

- [ ] 明确维护文档、设计/执行计划、规格、历史记录及 agent 记忆各自的归属，并提供统一导航。
- [ ] 核对并修正上述失效事实；旧方案保留有价值的决策依据，明确归档状态与替代入口。
- [ ] 如移动文件，同步检查 Markdown 链接、工具约定路径、构建门禁和 CI 引用。

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
- [ ] 明确 `.specify`、`.spec-workflow`、OpenSpec 和 Superpowers 各自是否继续使用；解决 `.specify` 生成 `specs/` 而该目录被整体忽略的策略不一致。

## 验证边界

- 本轮执行文件存在性、截图哈希、忽略规则、引用及 Git 差异检查，并复核设备状态。
- 同会话目录审计已执行 `verifyModuleDependencies --offline --console=plain`，结果为 `BUILD SUCCESSFUL`；本次未更改模块依赖，复用该结果。
- 本轮未修改业务代码，未重跑完整 lint、单元测试或设备交互测试。
- `change/` 被发布工作流直接引用，`player_component/libs/` 有明确 JNI 构建配置；二者不属于可直接清理的临时目录。
