# 类与包划分评估：保留的结论和待评估项

原始分析日期：2026-04-17；源码复核日期：2026-10-28。本文保留仍有依据的维护性问题和判断标准，不作为已批准的重构计划。原统计数量和目录占比没有随源码持续生成，已移除，避免作为当前规模指标使用。

## 已确认的边界

21 个构建模块中包含 6 个 AAR 包装模块。模块数量、单实现接口、单文件包或文件行数本身不足以证明过度设计。多协议存储、多播放器内核、TV 与移动端交互差异，以及 feature 不能直接相互依赖，都是目前真实的设计约束。

依赖规则以 [模块依赖治理](module_dependency_governance.md) 为准；播放状态与传输资源所有权以 [播放重构决策复核](2026-10-27-playback-refactor-reference-review.md) 及其链接的设计/实施记录为准。此前关于播放状态转交的建议已经被更具体的会话与租约设计替代。

## 仍值得评估的事项

### 简单页面的空 ViewModel

[AboutUsViewModel](../../user_component/src/main/java/com/xyoye/user_component/ui/activities/about_us/AboutUsViewModel.kt) 仍是空类。[BaseActivity](../../core_ui_component/src/main/java/com/xyoye/common_component/base/BaseActivity.kt) 会创建 VM、绑定 DataBinding 并观察 loading，因此使用该基类的静态页面会保留相关样板。

现有 [BaseAppCompatActivity](../../core_ui_component/src/main/java/com/xyoye/common_component/base/BaseAppCompatActivity.kt) 已提供不要求 VM 的 Binding 页面基础能力。旧建议“先新增另一套无 VM 基类”会重复现有职责，已撤回。后续若迁移简单页面，应先评估复用此基类及补齐页面需要的状态栏/加载行为，验证现有生命周期和 TV 焦点，而不是仅删除空类。

### 内核和 Surface 工厂

[PlayerFactory](../../player_component/src/main/java/com/xyoye/player/kernel/facoty/PlayerFactory.kt) 仍集中选择 Media3、VLC、mpv 工厂；部分工厂只包装构造函数，可以在相关变更时评估是否减少跳转。

不能把所有 Surface 工厂视为无状态同形包装：[SurfaceFactory](../../player_component/src/main/java/com/xyoye/player/surface/SurfaceFactory.kt) 按内核和 Surface 类型分流，[VLCViewFactory](../../player_component/src/main/java/com/xyoye/player/surface/VLCViewFactory.kt) 持有 useTextureView 策略。收敛时必须保留这类实际差异及现有路由测试。facoty 的拼写属于历史包名，改名需要单独核对引用，不是本次文档整理的代码任务。

### 跨模块 Provider

ARouter Provider 用于禁止直接依赖的模块之间协作，单实现并不自动意味着多余。

[StorageFileProvider](../../core_contract_component/src/main/java/com/xyoye/common_component/services/StorageFileProvider.kt) 及其 [实现](../../storage_component/src/main/java/com/xyoye/storage_component/providers/StorageFileProviderImpl.kt) 仍从活动页面取得共享文件。后续可以核对调用者是否仍需这种活动实例耦合，但不得把它与已经完成的稳定播放请求迁移混为一谈，更不能仅因接口薄就重新引入 feature 直接依赖。

### 字幕管线契约

[SubtitlePipelineApi](../../core_contract_component/src/main/java/com/xyoye/common_component/subtitle/pipeline/SubtitlePipelineApi.kt) 明确是 in-process façade，[LocalSubtitlePipelineApi](../../player_component/src/main/java/com/xyoye/player/subtitle/gpu/LocalSubtitlePipelineApi.kt) 是其本地实现。契约的所在模块不表示已有跨进程服务，也不能从类名推断未来一定需要第二实现。

只有在查清所有消费者、数据模型归属、测试替身与真实替换需求后，才能决定保留共享合同还是下沉播放器。旧文档中“未来 1–2 个版本”的时间假设没有需求依据，已删除。

### 占位文件和细包

[LogModelPackageMarker](../../core_log_component/src/main/java/com/xyoye/common_component/log/model/LogModelPackageMarker.kt) 仍只承担包说明。包并不需要空类维持存在；如后续清理，应先确认没有生成工具、反射或外部消费者依赖它。

按页面组织 Activity/ViewModel、按签名/MPD/认证等能力组织代码均可能合理，不采用“目录内必须至少 3 个文件”一类数量门槛。只在实际变更需要跨多个无职责边界的包反复跳转时，再做局部整合。

## 后续变更的判断标准

引入或保留一个抽象，应能说明至少一项实际收益：

- 存在多个真实实现，或者外部 I/O、时钟、生命周期等边界需要测试隔离。
- 跨越不能直接依赖的模块，合同表达稳定能力。
- 集中原本分散的策略、状态或资源所有权，减少真实维护点。
- 有已确认的近期需求，而不是仅为可能出现的扩展预留层级。

评估时同时比较现有直接实现、复用已有基础能力和新增抽象的总成本。目录、类或接口调整必须保留既有行为，并验证受影响的 TV 导航及生命周期；本文不授予修改这些实现的任务范围。
