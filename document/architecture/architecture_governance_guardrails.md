# 架构治理：规格与门禁（Guardrails）

本文用于把“架构一致性与性能治理”落地为可执行的门禁集合，并在本地与 CI 中保持一致。

## 推荐验证集合（本地/CI）

统一入口（推荐）：

- `./gradlew verifyArchitectureGovernance`

该入口任务会执行（至少）：

- `verifyModuleDependencies`：模块依赖治理（允许矩阵/白名单）
- `verifyLegacyPagerApis`：静态门禁，禁止新增旧版 `ViewPager`/`FragmentPagerAdapter`（基线：`document/architecture/legacy_pager_api_baseline.tsv`）
- `ktlintCheck`：Kotlin 代码风格一致性
- `testDebugUnitTest`：JVM 单元测试（Debug）
- `lint`/`lintDebug`：Android Lint（按工程实际任务存在性选择）

如仅需验证某一项，可单独执行对应 Gradle 任务。
