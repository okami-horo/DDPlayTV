# 目录与工具待办

核对日期：2026-10-28。以下两项仍待处理；模块边界遵循[模块依赖治理](module_dependency_governance.md)，验证范围见[治理检查](architecture_governance_guardrails.md)。

## DIR-04：包名与模块归属

[UserInfoHelper](../../user_component/src/main/java/com/xyoye/common_component/utils/UserInfoHelper.kt) 属于 `user_component`，[QrCodeHelper](../../storage_component/src/main/java/com/xyoye/common_component/utils/QrCodeHelper.kt) 属于 `storage_component`，但两者仍使用 `com.xyoye.common_component.utils`。[覆盖率统计脚本](../../parse_coverage.py) 将该包统一归入 `core_system_component`，不能可靠代表实际模块归属。

完成条件：

- [ ] 覆盖率统计依据实际模块信息归属，验证跨模块同包名的样例不会错误合并到单一模块。
- [ ] 区分有意保留的兼容别名与需要迁移的业务包；按实际维护需要逐步收敛，避免无收益的全量改包名。
- [ ] 涉及包名变化时核对引用、路由、反射/JNI 和对应构建、测试。

## DIR-05：辅助工具与工作流

完成条件：

- [ ] 将根目录 `parse_coverage.py` 归入合适的 `scripts/` 子目录（候选 `scripts/testing/`），同步[测试证据文档](../monitoring/test_evidence_format.md)等调用入口。
- [ ] 核实 `other/DanDanPlayTemplate-1.0.0.jar` 和 `other/module-dandan.sh` 的使用方；决定保留并说明用途、迁移或归档。
- [ ] 确认没有实际 Node 工具依赖后，再处理 `package.json`（空对象）与无依赖的 `package-lock.json`。
- [ ] 明确 `.specify`、`.spec-workflow`、Superpowers 及保留的 OpenSpec 技能／提示词是否继续使用；一并确定规格文件的生成位置和 `specs/` 忽略范围，避免隐藏有效规格。
- [ ] 修复 [setup-local-env.ps1](../../scripts/dev-env/setup-local-env.ps1) 的参数块位置和首次创建 `local.properties` 的行为。当前参数块位于赋值后，存在解析错误；修复后验证参数解析、保留既有凭证及 `sdk.dir` 的首次创建／更新。
