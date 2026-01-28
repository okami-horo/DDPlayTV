## ADDED Requirements

### Requirement: ARouter 路由路径必须遵循两段式规范
工程中用于 ARouter 的 `path` 字符串 MUST 遵循两段式 `/module/feature`（即：去掉前导 `/` 后恰好包含 1 个 `/`），并集中定义在 `RouteTable`（或同等路由表）中供复用，避免散落字符串造成治理与迁移成本。

#### Scenario: 新增路由 path 满足两段式
- **WHEN** 开发者为页面或 `IProvider` 新增 ARouter `@Route(path = ...)` 定义
- **THEN** path 去掉前导 `/` 后仅包含一个 `/`（两段式），且不出现额外层级

#### Scenario: 修复现存三段式路径
- **WHEN** 工程中发现三段或更深层级的 ARouter path（例如 `/stream/storage_file/provider`）
- **THEN** 该 path 应被重命名为两段式，并通过路由表常量引用以避免后续再引入散落字符串

### Requirement: Feature 模块不得直接硬编码 :app Service 类名用于 bind/start
`player_component` 等 library/feature 模块 MUST NOT 通过硬编码的 fully-qualified 类名字符串（例如 `com.xxx.app.service.FooService`）来 bind/start 仅存在于 `:app` 的 Android `Service`。

跨模块绑定/启动 Service 时，模块间交互 MUST 通过 `:core_contract_component` 提供的契约完成（例如由 `:app` 通过 ARouter Provider 提供用于 bind/start 的显式 `Intent`）。

#### Scenario: Player 绑定 Media3SessionService 不引用 app 类名
- **WHEN** Player 需要绑定 Media3 session service
- **THEN** 通过 `:core_contract_component` 定义的契约（例如 ARouter Provider）获取显式 Intent，再执行 bind/start；并且 `player_component` 代码中不出现对 `:app` service fully-qualified 类名的硬编码字符串

