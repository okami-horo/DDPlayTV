# Tasks: refactor-route-and-media3-session-service

## 1. Implementation
- [x] 1.1 将 `RouteTable.Stream.StorageFileProvider` 由三段式改为两段式（提议：`/stream/storage_file_provider`），并确认无散落旧字符串引用。
- [x] 1.2 在 `:core_contract_component` 增加 `Media3SessionServiceProvider` 契约（用于生成 bind 所需显式 `Intent`）。
- [x] 1.3 在 `:app` 提供 `Media3SessionServiceProvider` 实现并通过 `@Route` 注册。
- [x] 1.4 修改 `player_component` 的 `PlayerActivity`：通过 provider 获取 Intent 后 bind Service，移除硬编码 `:app` Service 类名字符串。
- [x] 1.5 视需要更新相关测试/文档引用（例如涉及 Service bind 的 instrumentation 测试）。

## 2. Validation
- [x] 2.1 `./gradlew verifyModuleDependencies`
- [x] 2.2 `./gradlew testDebugUnitTest`
- [x] 2.3 `./gradlew lint`
