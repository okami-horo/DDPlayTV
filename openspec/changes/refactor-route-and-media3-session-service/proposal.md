# Change: 规范化路由路径并消除 Player 对 App Service 的耦合

## Why
- `core_contract_component` 的 `RouteTable.Stream.StorageFileProvider` 当前为三段式路径：`/stream/storage_file/provider`，不符合工程约定的两段式 `/module/feature`，会降低路由一致性与治理可读性。
- `player_component` 目前通过硬编码字符串 `com.okamihoro.ddplaytv.app.service.Media3SessionService` 来 bind Service，形成对 `:app` 的运行时耦合；这与 `Media3SessionClient` 的设计意图（feature 不需要引用 app 模块）不一致，也会增加未来重构/迁移成本。

## What Changes
- 将 `RouteTable.Stream.StorageFileProvider` 调整为两段式路径（提议：`/stream/storage_file_provider`），并确保所有引用统一通过 `RouteTable` 常量复用，不再散落字符串。
- 新增 `Media3SessionServiceProvider`（跨模块契约 + ARouter Provider）：
  - 在 `:core_contract_component` 定义 provider 接口，用于构造 `bindService` 所需的显式 `Intent`。
  - 在 `:app` 提供 provider 实现（`@Route` 注册到 ARouter），内部可直接引用 `Media3SessionService`。
  - `player_component`（`PlayerActivity`）改为通过该 provider 获取 Intent，再执行 bind，不再硬编码 `:app` Service 类名字符串。

## Impact
- Affected specs:
  - `architecture-governance`（新增治理要求：路由两段式、feature 不硬编码 app Service 类名）
- Affected modules/files (expected):
  - `core_contract_component`: `RouteTable` 常量调整；新增 `Media3SessionServiceProvider` 契约
  - `storage_component`: Provider 注解仍引用 `RouteTable`（常量变更自动生效）
  - `player_component`: `PlayerActivity` 绑定 Service 逻辑调整
  - `app`: 增加 provider 实现（不改变现有 Service 行为）

## Out of Scope
- 不在本次恢复/实现后台播放、画中画等能力；`Media3BackgroundCoordinator` 的 TV 禁用策略保持不变。

