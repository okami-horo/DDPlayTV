# 架构治理：规格与门禁（Guardrails）

本文用于把“架构一致性与性能治理”落地为可执行的门禁集合，并在本地与 CI 中保持一致。

## 推荐验证集合（本地/CI）

统一入口（推荐）：

- `./gradlew verifyArchitectureGovernance`

该入口任务会执行（至少）：

- `verifyModuleDependencies`：模块依赖治理（允许矩阵/白名单）
- `verifyPlaybackBoundaries`：禁止恢复旧播放写入口，约束来源构造、释放及内核来源写入位置；Kotlin 使用现有版本编译器的 PSI 语法树，Java/XML 使用补充标识符扫描。检查覆盖已知 API，不代表完整类型解析或任意新写入口的自动发现。固定允许位置是职责边界，没有迁移期兼容白名单。
- `verifyLegacyPagerApis`：静态门禁，禁止新增旧版 `ViewPager`/`FragmentPagerAdapter`（基线：`document/architecture/legacy_pager_api_baseline.tsv`）
- `ktlintCheck`：Kotlin 代码风格一致性
- `testDebugUnitTest`：JVM 单元测试（Debug）
- `lint`/`lintDebug`：Android Lint（按工程实际任务存在性选择）

如仅需验证某一项，可单独执行对应 Gradle 任务。
