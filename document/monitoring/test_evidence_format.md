# 测试与覆盖率证据格式

核对日期：2026-10-28。测试/覆盖率变更按本文报告；通用要求见 [贡献指南](../Contributing.md)。

## 变更说明

记录完整命令、影响模块、代码状态、环境、实际结果与报告路径。覆盖率比较报告 Before/After 的 LINE、BRANCH；Gradle 必须报告尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。说明失败和未执行范围。TV 变更另记录纯 DPAD 路径、焦点与返回恢复。

```text
命令：./gradlew :player_component:testDebugUnitTest
结果：BUILD SUCCESSFUL，<实际测试数> tests
覆盖率：LINE <before>% → <after>%，BRANCH <before>% → <after>%
报告：build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
TV：进入 → DPAD 操作 → BACK，<实际观察>
未覆盖：<设备/协议/场景或无>
```

没有报告时标记“未测量”，不填推测百分比。历史结果在输入和代码不变时可以引用，注明来源和日期，不当作本轮执行。

## 补测原则

- 复用现有 Gradle / JaCoCo 聚合口径；每轮记录可复现的 LINE、BRANCH、CLASS、METHOD 基线和热点范围，比较时保持源码、排除规则与环境口径一致，不建立第二套覆盖率来源。
- 按当前报告中的覆盖缺口、用户影响和回归代价确定补测顺序，不平均分配测试数量，也不只看全仓库总百分比。旧提案中的百分比缺少可复核报告，不作为当前基线。
- 状态协调、映射、解析、仓储和错误处理优先使用 JVM 单元／契约测试，覆盖成功、失败和边界；生命周期、内核、系统服务和 TV 焦点使用必要的仪器／冒烟验证。固定夹具、数据源与前置状态，保留可重复步骤，不要求所有页面建立完整 UI 自动化。
- 每轮覆盖率改进同时提交测试结果、Before/After 报告及剩余缺口；只为隔离真实测试边界做必要调整，避免为刷覆盖率重写生产代码。门槛按已确认基线和目标范围渐进调整，不对所有模块直接套用统一高阈值。

## 单模块与聚合

单模块测试使用对应任务：

```bash
./gradlew :player_component:testDebugUnitTest
```

全工程报告使用：

```bash
./gradlew jacocoTestReport
```

当前 `jacocoTestReport` 依赖 `jacocoAggregateDebugUnitTest`，要求所有提供 `testDebugUnitTest` 的模块执行任务。追加到单模块命令后不会变成轻量的单模块报告。XML 位于 `build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`，HTML 在同级报告目录。

从根目录解析：

```bash
python parse_coverage.py
# 使用 python3 的环境改为 python3 parse_coverage.py
```

解析器热点默认阈值为 **20 行**，默认前 30 项，通过 `--hotspot-min-lines`、`--hotspot-top` 覆盖。“模块”表按历史包名前缀推断，跨模块同包名可能错误归类；DIR-04 修复前按原始包/类报告与源目录核对，不能直接等同 Gradle 模块覆盖率。

## 覆盖率门禁

```bash
./gradlew verifyCoverageBaseline
```

该任务依赖聚合报告，以 PACKAGE 粒度检查 LINE 的 `COVEREDRATIO`。规则以 [根构建配置](../../build.gradle.kts) 的 `violationRules` 为准，不是为全部物理模块设置门槛。

当前规则包含日志包 50%、Bilibili 包 15%、adapter/preference 包 15%、player/player_component 包 5%、storage 包 5%、data_component 包 5%。历史注释中的 database/network 数字不是实际规则，不能据此宣称受保护。

成功只表示当前输入满足既定规则，不证明测试无缺口。失败时核对范围与回归；门槛变更明确理由和追踪项，不静默降低以绕过失败。

## 设备与 TV

`connectedDebugAndroidTest` 前确认设备为 `device` 且启动完成。DPAD 证据涵盖进入、选择/操作、返回，并检查焦点可见、可达、可恢复；单次 BACK 不是完整主路径验证。环境见 [本地开发说明](../project_local_dev_env.md)。
