## ADDED Requirements

### Requirement: 模块依赖必须可治理且可自动校验
工程 MUST 提供自动化机制来校验 Gradle 模块级直接依赖是否符合允许矩阵，并在违反规则时阻止合入。

#### Scenario: CI 中依赖治理校验成功
- **WHEN** 在 CI 或本地执行 `./gradlew verifyModuleDependencies`
- **THEN** 构建通过且输出 `BUILD SUCCESSFUL`

#### Scenario: 引入禁止依赖时应被阻止
- **WHEN** 某模块新增一条不在允许矩阵内的 `project(...)` 依赖
- **THEN** 依赖治理校验失败并指出违规边（模块与依赖）

### Requirement: 不得新增旧版 ViewPager/FragmentPagerAdapter 用法
工程 MUST 阻止新增对已过时分页组件（`ViewPager` / `FragmentPagerAdapter`）的使用，并逐步迁移现存用法到 `ViewPager2`/`FragmentStateAdapter`，以降低内存与生命周期风险。

#### Scenario: 新增旧分页组件时 CI 检查失败
- **WHEN** 提交中新增对旧分页组件的引用或实现
- **THEN** CI 静态检查失败并标注具体文件位置

### Requirement: 主线程不得执行阻塞式 IO
应用 MUST 不在主线程执行可能阻塞的 IO（网络/磁盘/跨进程等待等），并在 Debug 构建中提供可观测的检测手段以便尽早发现性能退化。

#### Scenario: Debug 构建可暴露主线程阻塞风险
- **WHEN** 在 Debug 构建中出现主线程阻塞式 IO
- **THEN** 系统输出可定位的告警信息（包含线程与调用点）以支持快速修复

### Requirement: 播放代理链路的线程模型必须明确且不阻塞 UI
涉及本地播放代理/播放服务器等关键链路时，工程 MUST 保证其线程模型可解释、并发可控，且不会阻塞 UI 线程，避免引入播放卡顿、ANR 或吞吐退化。

#### Scenario: 本地代理播放不影响 UI 响应
- **WHEN** 用户通过本地播放代理链路开始播放并产生连续 Range 请求
- **THEN** UI 仍保持可交互（返回/暂停/拖动等操作可及时响应），且无明显卡顿或 ANR
