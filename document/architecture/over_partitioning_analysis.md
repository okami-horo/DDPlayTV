# 功能逻辑过度划分分析

> 分析日期：2026-04-17  
> 范围：主工程源码模块（`app`、功能模块、`core_*`、`data_component`、`bilibili_component`），不包含 `repository/*` AAR 包装模块的内部实现。

## 结论摘要

当前工程的模块级拆分总体符合“TV 壳层 + 业务功能 + core 基础能力 + data 模型”的方向，没有发现需要立即合并 Gradle 模块的强信号。真正带来复杂度的主要不是模块数量，而是若干模块内部的“类/包/接口”划分偏细：

- 高置信度：大量无状态页面仍被迫创建空 `ViewModel`，这是由 `BaseActivity<VM, V>` 强制绑定 `BaseViewModel` 造成的样板层。
- 中高置信度：播放器内核和渲染 Surface 的工厂层存在多个“只有一行 create 方法”的子类，抽象收益低于跳转和维护成本。
- 中置信度：部分 ARouter `IProvider` 服务只有单实现、单调用或极薄包装，属于“为模块边界服务”的合理抽象与“设计超前”的混合状态，需要逐个保留或收敛。
- 中置信度：`SubtitlePipelineApi` 当前是进程内单实现接口，但放在 `core_contract_component`，接口命名和位置更像远程/跨进程 API，实际需求暂未达到该抽象级别。
- 低置信度：若干 1-2 个文件的深层包、占位 Marker 类、纯模型文件拆分，会增加搜索和导航成本，但对运行逻辑影响较小。

建议优先治理“空 ViewModel”和“一行工厂类”，这两类修改风险较低、复杂度收益直接；ARouter Provider 和 subtitle pipeline 合同需要结合模块依赖目标逐步评估，不建议一次性大规模合并。

## 分析方法与准确性

### ast-grep 结果

- `lang`: Kotlin
- `scope`: `app`、`anime_component`、`local_component`、`player_component`、`storage_component`、`user_component`、`bilibili_component`、`core_*`、`data_component` 的 `src/main/java`
- `pattern`: `interface $NAME`
- `match count`: 82
- `key files`: `core_contract_component/src/main/java/com/xyoye/common_component/services/*`、`core_contract_component/src/main/java/com/xyoye/common_component/storage/Storage.kt`、`player_component/src/main/java/com/xyoye/player/subtitle/backend/*`
- `accuracy`: AST 准确

- `lang`: Kotlin
- `scope`: 同上
- `pattern`: `class $NAME`
- `match count`: 447
- `key files`: `player_component`、`core_storage_component`、`storage_component`、`data_component`
- `accuracy`: AST 准确

- `lang`: Kotlin
- `scope`: 同上
- `pattern`: `object $NAME`
- `match count`: 198
- `key files`: `core_storage_component`、`player_component`、`core_system_component`、`bilibili_component`
- `accuracy`: AST 准确

### 近似统计

继承/调用链关系曾尝试用 ast-grep 匹配 Kotlin 继承和 ARouter 注入调用，但通配模式对当前 Kotlin 写法匹配不稳定。因此“单实现/调用点”部分采用字面量搜索和抽样源码阅读验证，属于近似信号，不作为机械重构依据。

目录颗粒度统计来自文件系统盘点：

| 模块 | 含 Kotlin 文件目录数 | 单文件目录占比 | 1-2 文件目录占比 |
| --- | ---: | ---: | ---: |
| `anime_component` | 21 | 23.8% | 95.2% |
| `user_component` | 22 | 4.5% | 86.4% |
| `core_contract_component` | 9 | 66.7% | 77.8% |
| `local_component` | 11 | 0.0% | 72.7% |
| `bilibili_component` | 18 | 38.9% | 66.7% |
| `storage_component` | 17 | 23.5% | 64.7% |
| `core_log_component` | 16 | 56.2% | 62.5% |
| `core_system_component` | 16 | 43.8% | 62.5% |
| `player_component` | 52 | 40.4% | 59.6% |
| `core_storage_component` | 42 | 38.1% | 57.1% |

该统计只能说明“颗粒度偏细”，不能直接等价于过度设计；是否过度需要结合职责、依赖方向和变更频率判断。

## 发现 1：空 ViewModel 层级偏多

### 证据

典型文件：

- `user_component/src/main/java/com/xyoye/user_component/ui/activities/about_us/AboutUsViewModel.kt`
- `anime_component/src/main/java/com/xyoye/anime_component/ui/activities/search_advanced/SearchAdvancedViewModel.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/main/MainViewModel.kt`
- `user_component/src/main/java/com/xyoye/user_component/ui/activities/setting_player/SettingPlayerViewModel.kt`

这些 `ViewModel` 通常只有如下结构：

```kotlin
class AboutUsViewModel : BaseViewModel()
```

根因在 `core_ui_component/src/main/java/com/xyoye/common_component/base/BaseActivity.kt`：`BaseActivity<VM : BaseViewModel, V : ViewDataBinding>` 强制每个页面提供 `initViewModel()`，并在 `onCreate()` 中绑定 `viewModel` 到 DataBinding。即使页面只有静态文案或简单点击事件，也必须创建一个空类。

抽样统计显示，主源码中 25 行以内的 `*ViewModel.kt` 至少有 17 个，其中不少是 6-10 行空壳。

### 判断

这是“框架设计大于实际需求”的高置信度问题。它不是业务复杂度，而是基础类约束把所有页面都推入 MVVM 模板，导致：

- 新增简单页面也必须新增目录 + Activity + ViewModel + Binding 变量。
- 搜索页面逻辑时会频繁跳转到无内容类。
- `ViewModel` 的存在不再表达“页面有状态/异步任务”，削弱命名语义。

### 建议

短期不必改现有页面，但新增页面建议允许无 ViewModel 基类：

- 增加 `BaseBindingActivity<V : ViewDataBinding>` 或 `BaseSimpleActivity<V : ViewDataBinding>`，只负责 Binding、状态栏、通用生命周期。
- 让 `BaseActivity<VM, V>` 继承这个无 VM 基类，只保留真正需要 `loadingObserver`、协程状态、页面数据的场景。
- 对已存在的空 ViewModel 页面分批迁移，优先迁移 `AboutUs`、`SearchAdvanced`、设置类页面、纯壳层 main 页面。

## 发现 2：播放器工厂层抽象收益偏低

### 证据

典型文件：

- `player_component/src/main/java/com/xyoye/player/kernel/facoty/PlayerFactory.kt`
- `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvPlayerFactory.kt`
- `player_component/src/main/java/com/xyoye/player/kernel/impl/media3/Media3PlayerFactory.kt`
- `player_component/src/main/java/com/xyoye/player/kernel/impl/vlc/VlcPlayerFactory.kt`
- `player_component/src/main/java/com/xyoye/player/surface/SurfaceFactory.kt`
- `player_component/src/main/java/com/xyoye/player/surface/MpvSurfaceViewFactory.kt`
- `player_component/src/main/java/com/xyoye/player/surface/TextureViewFactory.kt`
- `player_component/src/main/java/com/xyoye/player/surface/VLCViewFactory.kt`

`PlayerFactory.getFactory()` 已经在 `when (playerType)` 中直接知道所有实现类型，但返回后仍要进入一组只有一行 `createPlayer()` 的子类：

```kotlin
class MpvPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer = MpvVideoPlayer(context)
}
```

Surface 工厂也类似，多数子类仅包装 `Render*View(context)`。

### 判断

这是中高置信度的过度分层。当前设计表达了“可替换内核/可替换 Surface”的扩展点，但扩展实际仍集中在 `when` 分支里，子类没有保存策略、生命周期、依赖注入或复用状态，导致抽象层没有承担足够职责。

保留抽象的理由是播放器内核确实有多个实现，且未来可能引入更复杂初始化。但以当前代码看，子类粒度过细。

### 建议

可以分两步收敛：

- 短期：保留 `PlayerFactory`/`SurfaceFactory` 入口，但把一行子类替换为私有函数或函数引用映射，例如 `PlayerType -> (Context) -> AbstractVideoPlayer`。
- 中期：如果某个内核需要复杂构建，再为该内核单独引入专用 builder，不要为所有内核强制同样层级。
- 顺手修正包名拼写：`facoty` 应为 `factory`。如果改包影响较大，可单独做迁移。

## 发现 3：部分 ARouter Provider 是必要解耦，部分偏薄

### 证据

`core_contract_component/src/main/java/com/xyoye/common_component/services` 中的 IProvider 合同包括：

- `UserSessionService`
- `StorageFileProvider`
- `ScreencastReceiveService`
- `Media3SessionServiceProvider`
- `Media3CapabilityProvider`
- `DeveloperMenuService`
- `CloudDanmuBlockService`

抽样：

- `UserSessionService`：`app` 壳层通过 ARouter 调用 `user_component` 的 token 刷新逻辑，避免 `app -> user/network` 细节耦合，有明确模块解耦价值。
- `Media3SessionServiceProvider`：`player_component` 不能直接硬编码 `:app` 的 `Media3SessionService` 类名，该 Provider 合理。
- `CloudDanmuBlockService`：壳层启动时触发播放器/弹幕域的云屏蔽词同步，避免壳层依赖 player/db/network 细节，基本合理。
- `DeveloperMenuService`：`app/MainActivity` 只调用 `developerMenuService.create(this, menu)`，实现只是转发到 `DeveloperMenus.inflater(activity, menu)`，抽象偏薄。
- `StorageFileProvider`：`local_component` 通过 Provider 取 `StorageFileActivity.shareStorageFile`，实现通过 `ActivityHelper` 查找当前 Activity。这是为了跨模块拿运行时对象，但本质是全局状态/Activity 查找，接口本身很薄，且隐式依赖较强。

### 判断

这里不应简单按“单实现接口”判定过度设计，因为 ARouter Provider 在当前模块依赖规则下承担了“跨 feature/module 边界调用”的职责。问题在于部分 Provider 的业务含义太薄，接口只是绕过依赖限制，而不是稳定能力边界。

### 建议

逐个保留或收敛：

- 保留：`UserSessionService`、`Media3SessionServiceProvider`、`Media3CapabilityProvider`、`CloudDanmuBlockService`。
- 观察：`DeveloperMenuService`。如果开发者菜单只服务 `MainActivity`，可考虑把菜单能力移动到 `app` 或通过路由打开 developer 页面，而不是让 user 模块提供菜单构建器。
- 重点复查：`StorageFileProvider`。更稳妥的方式是传递可序列化的 `StorageFilePayload` / `PlaybackIdentity` / `mediaId`，由目标模块重新解析，而不是跨模块暴露活动实例上的对象。

## 发现 4：`SubtitlePipelineApi` 的合同位置偏超前

### 证据

相关文件：

- `core_contract_component/src/main/java/com/xyoye/common_component/subtitle/pipeline/SubtitlePipelineApi.kt`
- `player_component/src/main/java/com/xyoye/player/subtitle/gpu/LocalSubtitlePipelineApi.kt`
- `player_component/src/main/java/com/xyoye/player/subtitle/gpu/SubtitlePipelineController.kt`

`SubtitlePipelineApi` 位于 `core_contract_component`，名称是 `Api`，但当前实现是 `LocalSubtitlePipelineApi`，注释也明确是 in-process。调用方也在播放器字幕管线内部。

### 判断

这是中置信度的“抽象位置大于实际需求”。如果未来确实计划做跨进程、插件化、可替换字幕渲染服务，放在 contract 层合理；但当前只有本地实现时，接口放在全局 contract 会扩大公共 API 面，并让数据模型、telemetry、pipeline 状态更早固化。

### 建议

- 如果 1-2 个版本内没有跨模块替换计划，把 `SubtitlePipelineApi` 下沉到 `player_component` 内部包，`core_contract_component` 只保留跨模块真正需要共享的数据/枚举。
- 如果保留在 contract 层，建议补充注释说明它是“稳定跨模块合同”，并列出预期第二实现或外部调用方，否则后续维护者会误判它已经是公共 API。
- `PipelineInitRequest`、`PipelineStatusResponse`、`FallbackCommand` 可继续与接口同包，但不要让纯播放器内部策略类进入 contract。

## 发现 5：占位 Marker 类没有实际业务价值

### 证据

`core_log_component/src/main/java/com/xyoye/common_component/log/model/LogModelPackageMarker.kt`：

```kotlin
class LogModelPackageMarker
```

注释说明它只是“日志相关数据模型集中放在该包下”的占位标记。

### 判断

这是低风险、低收益但很明确的过度类。包结构不需要通过空类证明存在；如果只是为了文档表达，维护成本大于收益。

### 建议

- 删除 Marker 类。
- 如果需要 IDE/package 可见性或文档提示，把说明放入 `package-info.java` 不一定适合 Kotlin；更简单的是在 `document/monitoring/logging-system.md` 或包内真实模型文件注释中说明。

## 发现 6：页面包和模型包颗粒度偏细

### 证据

目录统计显示：

- `anime_component` 的 1-2 文件目录占比达到 95.2%，大量页面目录是 `Activity + ViewModel` 或 `Fragment + ViewModel`。
- `user_component` 的 1-2 文件目录占比达到 86.4%，大量设置/信息页单独成目录。
- `bilibili_component`、`core_log_component`、`core_system_component` 也存在较多单文件子包，例如 `cdn`、`mpd`、`ticket`、`wbi`、`http/auth`、`http/json`、`http/page`。

### 判断

这属于低到中置信度问题。按页面建目录在 Android 项目中常见，优点是资源、Activity、ViewModel 容易一一对应；缺点是当页面逻辑很少时，目录层级会放大复杂度。

`bilibili_component` 的细包有一定合理性，因为签名、MPD、风控、直播弹幕等能力边界明确。`core_log_component/log/http/*` 的若干单文件包则更像按技术概念过早细分，长期可能增加导航成本。

### 建议

- 页面层：保留“复杂页面单目录”，但简单静态页可以并入上级 `activities` 或使用无 ViewModel 基类减少文件数量。
- 业务能力层：只有当目录内至少有稳定的一组模型/策略/测试，或存在清晰外部依赖边界时，再单独建包。
- 模型层：纯 DTO/data class 不必强制每个协议/响应都建多级目录，除非同一领域已经有明显规模。

## 不建议立即合并的区域

以下区域虽然也有接口/抽象，但当前不建议按“过度设计”处理：

- `core_storage_component` 的 `Storage` / `StorageFile` / `AbstractStorage` / 多种 `*Storage` 实现：媒体库协议多、差异大，抽象有实际多态收益。
- `player_component` 的 `AbstractVideoPlayer` 和多内核实现：Media3、VLC、mpv 的能力差异明显，抽象必要。
- `core_contract_component` 的路由表和跨模块服务合同：只要模块依赖治理继续要求 feature 之间不直接依赖，这层仍有存在价值。
- `repository/*` AAR 包装模块：它们是构建与第三方包隔离层，不属于本次“功能逻辑过度划分”的重点。

## 优先级建议

### P1：降低空 ViewModel 样板

收益最大，风险较低。先新增无 ViewModel 的 Activity/Fragment 基类，再迁移空壳页面。迁移标准：

- 页面不需要 `viewModelScope`。
- 页面没有可观察状态。
- 页面只做静态文案、点击跳转、简单 UI 初始化。

### P2：收敛一行工厂子类

优先处理 `PlayerFactory` 和 `SurfaceFactory` 的一行实现类。保留统一入口，不保留无状态子类。

### P3：复核薄 Provider

按“是否真实跨模块能力边界”逐个判断：

- `StorageFileProvider` 优先考虑改为参数/ID/payload 传递。
- `DeveloperMenuService` 评估是否应该回收到 `app` 或改为页面路由。

### P4：调整 contract 层暴露面

复核 `SubtitlePipelineApi` 是否真的需要放在 `core_contract_component`。如果暂时只有播放器内部使用，建议下沉，避免 contract 层继续膨胀。

## 后续治理规则建议

新增抽象前建议满足至少一项：

- 已有两个以上真实实现。
- 调用方和实现方位于不能直接依赖的模块，且接口表达的是稳定业务能力。
- 抽象承载了策略、生命周期、缓存、错误处理或资源管理，而不是只转发一行构造/调用。
- 该抽象有明确测试替身需求，并且测试替身能显著降低测试成本。

新增包/目录前建议满足至少一项：

- 目录内预计会有 3 个以上同职责文件。
- 该职责有独立测试、独立演进或独立依赖边界。
- 包名能表达领域概念，而不是只表达技术动作。

对简单页面建议默认不创建空 ViewModel；只有当页面确实持有状态、异步任务或跨配置变更数据时才引入 ViewModel。
