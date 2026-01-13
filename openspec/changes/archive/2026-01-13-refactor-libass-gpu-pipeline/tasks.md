## 1. Implementation
- [x] 1.1 迁移 subtitle pipeline 契约：将 `SubtitlePipelineApi` 与相关 DTO 移动到 `core_contract_component`，并调整包结构与依赖声明
- [x] 1.2 重构 GPU 管线包结构：将 `player_component/subtitle/gpu` 相关类收敛到与播放器字幕体系一致的包层级，并消除跨模块“伪归属”代码
- [x] 1.3 引入单一渲染线程：在 `LibassGpuSubtitleSession` 内创建渲染线程并串行化所有 native bridge 调用
- [x] 1.4 改造 JNI 指标回传：移除每帧 `jlongArray` 分配，改为复用缓冲/按需查询；Telemetry 关闭时走快速路径
- [x] 1.5 优化 native GL 热路径：减少循环内状态抖动与重复设置（低风险项优先）
- [x] 1.6 统一生命周期与并发：移除各组件自建的 `CoroutineScope`，改为 session 注入；确保 release 顺序与取消一致
- [x] 1.7 回退/恢复链路收敛：将 init/render/surface 错误统一映射为 fallback reason，并对接 `SubtitleFallbackDispatcher`

## 2. Validation
- [x] 2.1 JVM 单测：覆盖 `SubtitleLoadSheddingPolicy`、`SubtitleTelemetryRepository`（rolling snapshot）、以及关键状态转换逻辑（如可测）
- [x] 2.2 构建验证：`./gradlew assembleDebug`（至少覆盖 `player_component` 相关变更）
- [x] 2.3 静态检查：`./gradlew lint`（或工程约定的 Debug lint 任务）
- [x] 2.4 依赖治理：`./gradlew verifyModuleDependencies`
- [x] 2.5 手动回归：Exo + ASS/SSA（外挂/内嵌），覆盖播放/暂停/seek/旋转/进入后台/返回前台/切换后端/透明度调整

## 3. Performance Evidence
- [x] 3.1 关键路径确认：渲染热路径不产生每帧 JNI 分配（对比改造前后）
- [x] 3.2 稳定性验证：复杂 ASS 场景连续播放 ≥ 20 分钟，无明显卡顿/ANR，load-shedding 生效且可回退恢复
