## Context
本变更聚焦于两类“架构治理一致性”问题：

1) `RouteTable` 中存在不符合约定的三段式 ARouter 路由（`/stream/storage_file/provider`）。  
2) `player_component` 通过硬编码 `:app` Service 的 fully-qualified 类名字符串来 bind Service，破坏模块边界的可演进性。

目标是以“契约 + 路由/服务化”的现有模式完成统一修复，并尽量不改变运行时行为。

## Goals / Non-Goals

### Goals
- 路由路径统一为两段式 `/module/feature`，减少治理歧义。
- `player_component` 不再直接引用 `:app` 的 Service 类名字符串，改为通过 `:core_contract_component` 提供的契约获取显式 Intent。
- 保持现有运行时行为不变（仍 bind 同一个 `Media3SessionService`，同样的 binder 接口 `Media3SessionClient`）。

### Non-Goals
- 不在本次实现/调整 Media3 后台播放、PIP、通知等功能开关策略。
- 不引入新的复杂框架或跨模块依赖环。

## Decisions

### Decision 1: `StorageFileProvider` 路由改为两段式
- 将 `RouteTable.Stream.StorageFileProvider` 从 `"/stream/storage_file/provider"` 重命名为 `"/stream/storage_file_provider"`。
- 依赖点均通过 `RouteTable` 常量引用，因此改动应是“集中式”且可控。

### Decision 2: 通过 ARouter Provider 提供 Service bind Intent
新增 `Media3SessionServiceProvider` 契约（`core_contract_component`），并在 `:app` 实现。

`player_component` 在 bind Service 前通过 provider 获取显式 `Intent`，避免出现：
- 对 `:app` Service fully-qualified 类名字符串的硬编码
- `player_component` 依赖 `:app` 的编译期引用（保持模块分层不变）

该方案与现有跨模块协作模式一致（例如 `StorageFileProvider`、`Media3CapabilityProvider` 均采用契约接口 + ARouter Provider 实现）。

## Alternatives Considered
- **A) 将 `Media3SessionService` 下沉到 `player_component` 或 `core_system_component`**：可以用类引用直接 bind，但涉及跨模块搬迁与 manifest 位置/包名调整，影响面更大。
- **B) 使用隐式 Intent + intent-filter + resolveService**：实现复杂且容易引入兼容性/解析不确定性；不如 provider 明确。
- **C) 仅将字符串常量移动到 `core_contract_component`**：虽然减少散落，但仍属于“硬编码 app Service 类名”，无法真正解除耦合点。

## Risks / Trade-offs
- **ARouter 初始化顺序**：provider 依赖 ARouter 正常初始化；若初始化时序异常，将导致 bind 失败。现状已有大量 ARouter 注入/导航依赖，风险可接受。
- **路由重命名的兼容性**：如果存在外部/历史数据持久化了旧路由字符串，可能受影响。本次变更仅针对 Provider 路由，且项目内引用均通过常量，风险较低。

## Migration Plan
1) 调整 `RouteTable.Stream.StorageFileProvider` 为两段式并全局确认无散落字符串引用。
2) 增加 `Media3SessionServiceProvider` 契约与 `:app` 实现并注册到 ARouter。
3) 修改 `PlayerActivity`：从 provider 获取 Intent 后 bind；删除硬编码类名字符串。
4) 跑验证集合（依赖治理、单测、lint），并在必要时补充/更新相关测试。

## Open Questions
- `Media3SessionServiceProvider` 的路由归属命名：放在 `RouteTable.Player` 下是否更符合语义，还是应归入 `RouteTable.System`？（默认建议放在 `Player`，因为使用方与语义均与播放器相关。）

