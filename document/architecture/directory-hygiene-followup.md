# 覆盖率与环境工具待办

更新日期：2026-11-03。按用户要求清除低价值、有限价值的旧待办后，仅保留覆盖率归属和环境初始化脚本的实际问题；本轮未修复工具实现。模块边界遵循[模块依赖治理](module_dependency_governance.md)，验证范围见[治理检查](architecture_governance_guardrails.md)。

## DIR-04：覆盖率模块归属

[UserInfoHelper](../../user_component/src/main/java/com/xyoye/common_component/utils/UserInfoHelper.kt) 属于 `user_component`，[QrCodeHelper](../../storage_component/src/main/java/com/xyoye/common_component/utils/QrCodeHelper.kt) 属于 `storage_component`，但两者仍使用 `com.xyoye.common_component.utils`。[覆盖率统计脚本](../../parse_coverage.py) 将该包统一归入 `core_system_component`，不能可靠代表实际模块归属。

仅在继续使用该报表时实施，修复统计依据即可；不再为报表启动业务包名迁移。

- [ ] 覆盖率统计依据实际模块信息归属，验证跨模块同包名的样例不会错误合并到单一模块。

## DIR-05：环境初始化脚本

完成条件：

- [ ] 修复 [setup-local-env.ps1](../../scripts/dev-env/setup-local-env.ps1) 的参数块位置和首次创建 `local.properties` 的行为。当前参数块位于赋值后，存在解析错误；修复后验证参数解析、保留既有凭证及 `sdk.dir` 的首次创建／更新。

## 已移出待办（2026-11-03）

以下低收益整理任务按用户要求取消，现有工具文件保持原状，取消不表示已迁移或删除：

- 仅为统一目录或包名而迁移业务包，以及随附的包名迁移检查项。
- 单独移动 `parse_coverage.py`；其实际统计问题仍由 DIR-04 跟踪。
- 清点、迁移或归档 `other/DanDanPlayTemplate-1.0.0.jar` 与 `other/module-dandan.sh`。
- 清理空的 `package.json` 与无依赖的 `package-lock.json`。
- 统一 `.specify`、`.spec-workflow`、Superpowers、OpenSpec 提示词及调整 `specs/` 忽略范围。当前 Sonar 辅助脚本仍使用 `specs/` 下的默认输出位置，不能把取消整理任务理解为可删除这些约定。
