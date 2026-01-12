# Change: 架构一致性与性能治理基线

## Why（为什么要做）

当前工程模块数量多、跨域协作链路长（播放 / 存储 / 网络 / 用户 / 番剧等），若缺少“可执行的架构约束 + 可验证的质量门禁 + 可持续的性能治理清单”，后续迭代容易出现：

- 模块边界被逐步侵蚀（依赖随意增加、契约层失效、隐式耦合扩大）
- UI/线程模型不一致（同类问题用多套写法，定位与维护成本上升）
- 性能问题难以提前发现（主线程阻塞、旧组件堆积、关键链路缺少回归保障）

项目目前已有较好的依赖治理基础（`document/architecture/module_dependency_governance.md` 与 `./gradlew verifyModuleDependencies`），但仍需要把“治理目标、门禁规则、以及第一批改造落地项”固化成 OpenSpec 变更，形成后续持续演进的基线。

## What Changes（变更内容）

- 新增 OpenSpec 能力：`architecture-governance`
  - 明确模块依赖治理、UI 组件一致性、线程/性能守则等可验证要求。
- 形成第一批落地改造任务清单（在 apply 阶段实现）：
  - 将工程内仍在使用的 `FragmentPagerAdapter`/`ViewPager` 迁移到 `ViewPager2`/`FragmentStateAdapter`，并建立“禁止新增旧 API”的静态门禁。
  - 梳理并治理播放代理/本地播放服务器相关阻塞点（`runBlocking` 等），保证不阻塞主线程且线程模型可解释、可回归。
  - 在 Debug 构建引入主线程阻塞检测（如 StrictMode 策略），把性能退化尽量前置到开发阶段暴露。

## Impact（影响范围）

- 受影响模块（预期）：`anime_component`、`user_component`、`core_storage_component`、`core_ui_component`（可能含少量 `core_system_component` 配置）。
- 验证与门禁：Gradle 校验任务（`verifyModuleDependencies`、`ktlintCheck`、`lint`、单元测试/必要的仪表测试）。
- 风险：UI Pager 迁移与播放代理链路涉及用户核心路径；需按任务拆分、逐步落地并配套回归验证。

