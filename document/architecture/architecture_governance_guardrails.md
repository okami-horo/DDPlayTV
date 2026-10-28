# 架构治理检查

核对日期：2026-10-28。本文说明构建任务的职责及验证边界；模块规则见 [模块依赖治理](module_dependency_governance.md)，具体依赖见 [自动快照](module_dependencies_snapshot.md)。

## 本地入口

完整集合：./gradlew verifyArchitectureGovernance --console=plain。

| 任务 | 检查内容与边界 |
| --- | --- |
| verifyModuleDependencies | 已知模块、直接 project 依赖允许矩阵、测试例外与 API 暴露列表；不展开外部或传递依赖 |
| verifyPlaybackBoundaries | 已知旧播放写入口、来源构造/释放和内核来源写入位置；Kotlin 使用编译器 PSI，Java/XML 使用补充标识符扫描，不是完整类型解析或任意新写入口的自动发现 |
| verifyLegacyPagerApis | 禁止增加旧 ViewPager / FragmentPagerAdapter 使用，基线为 [legacy_pager_api_baseline.tsv](legacy_pager_api_baseline.tsv) |
| ktlintCheck | Kotlin 风格；聚合各项目中存在的任务 |
| testDebugUnitTest | 各项目中存在的 Debug JVM 单元测试 |
| lint / lintDebug | 各项目优先选择 lint，没有时选择 lintDebug |

任务装配位于 [根 build.gradle.kts](../../build.gradle.kts)。单独检查可直接执行相应任务；聚合门禁不自动执行 APK 构建、仪器测试、物理 TV 验收或覆盖率基线任务。播放边界固定允许位置表达职责边界，不是迁移期兼容白名单。

## CI 的实际覆盖

[SonarCloud workflow](../../.github/workflows/sonarcloud.yml) 显式调用：

    verifyModuleDependencies
    verifyPlaybackBoundaries
    testDebugUnitTest
    lint
    jacocoTestReport
    assembleDebug

该 workflow 没有调用 verifyArchitectureGovernance、verifyLegacyPagerApis 或 ktlintCheck。CI 配置存在不证明某次云端运行成功，结果需要查看对应运行记录。

## 结果解释

- 每次汇报查看 Gradle 输出尾部，明确记录 BUILD SUCCESSFUL 或 BUILD FAILED。
- 某个单项通过只能支持它的检查范围；编译、静态门禁、JVM 测试和设备行为不能互相替代。
- 2026-10-28 的播放重构已有模块/播放门禁、测试和构建通过的历史证据，但全仓旧 ktlint/Pager 基线仍失败。详见 [交接实施与验收记录](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)，不得把局部通过写成完整聚合门禁通过。
- 修改依赖、旧 Pager 存量或播放所有权边界时，同步对应规则/快照/基线；不通过扩大基线隐藏新增违例。
