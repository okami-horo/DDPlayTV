# common_component 拆分设计方案（DanDanPlayForAndroid）

> 目标：将当前“God Module + 依赖伞”的 `common_component` 拆分为职责单一、边界清晰的多个组件（components），让 feature “谁用谁依赖”，并为后续的契约化、可测试化与长期演进打基础。  
> 约束：优先保证架构与逻辑一致性（而不是最小侵入），允许阶段性增加迁移工作量以换取长期结构收益。

---

## 1. 现状分析（基于仓库静态扫描）

### 1.1 模块规模与问题画像

- `common_component` 代码规模（`src/main/java`）约 **314** 个 `.kt/.java` 文件，资源（`src/main/res`）约 **103** 个文件；同时还包含 `libs/*.jar` 与 `libs/*/lib*.so`。
- `common_component/build.gradle.kts` 中 `api(...)` 依赖约 **33** 条：使下游模块获得大量“隐式传递依赖”，导致**边界失效、升级风险放大、构建/编译成本上升**。
- 代码职责高度混杂：UI 基建、网络、数据库、存储协议实现、Bilibili 业务、日志、通知、系统 Receiver/Provider、各种 utils 与 extensions 同处一处，任何一个子域变更都可能波及全局。

### 1.2 包分布（按一级目录统计）

> 统计范围：`common_component/src/main/java/com/xyoye/common_component/*`

| 子域（包） | 文件数(约) | 典型内容 |
| --- | ---: | --- |
| `utils` | 66 | 杂项工具、弹幕/字幕、IO、路径、安全、投屏、压缩等 |
| `storage` | 39 | Storage/StorageFile 抽象 + 多协议实现（SMB/FTP/WebDav/Remote/…） |
| `network` | 37 | Retrofit/OkHttp、拦截器、请求封装、service/repository |
| `bilibili` | 30 | Bilibili 登录/签名/风控/历史/播放等 |
| `extension` | 21 | UI/系统/Media3/通知/文件等扩展函数混合 |
| `log` | 20 | 日志系统、采样、文件管理、上报/telemetry |
| `config` | 17 | RouteTable + 多领域配置表（播放器/字幕/日志/下载/…） |
| `weight` | 15 | 通用控件 + dialog/binding |
| `adapter` | 13 | RecyclerView/Paging 通用适配器体系 |
| `database` | 12 | Room Database/Dao/迁移 + DatabaseManager |
| 其他（`base/source/receiver/services/…`） | 若干 | BaseActivity、VideoSourceManager、通知/receiver/provider 等 |

> 结论：`common_component` 实际上承担了“多个 core + 多个业务域 + 多个跨模块契约”的集合，拆分是必要且高收益的结构性改造。

---

## 2. 拆分目标与原则

### 2.1 拆分目标

1. **恢复模块边界可见性**：让 feature 显式声明依赖，禁止继续依靠 `common_component api` 传递存活。
2. **高内聚、低耦合**：每个 core 只有“一个主要变化原因”，降低联动范围。
3. **契约与实现分离**：跨模块 Provider/路由/协议模型下沉到 contract 层，避免实现渗透。
4. **避免环依赖**：通过分层与“接口在下、实现向上”组织依赖图。
5. **可增量迁移**：每拆一个 core，就能通过关键回归集；支持“薄兼容层”过渡。

### 2.2 拆分原则（强约束）

- **`api` 只用于公共签名必需**：若某依赖不出现在对外 API 的类型签名中，一律用 `implementation`。
- **core 不承载业务 UI**：业务 UI（页面/弹窗）应留在 feature；core 只提供可复用能力。
- **contract 不依赖 UI 资源**：`core_contract` 内不允许引用 `R.*`、布局、drawable；尽量保持“协议/接口/常量”属性。
- **平台初始化去业务化**：`BaseApplication` / initializer 只做基础设施装配（日志、崩溃、路由初始化等），不要直接初始化某个业务域（例如字幕字体、某站点 cookie 之类），业务域应通过自身 initializer/Provider 接入。

---

## 3. 目标拆分：建议 7 个 core components（+1 个可选业务 provider）

> 推荐最终形态：**7 个 core components + 1 个业务 provider component（Bilibili）**。  
> 其中播放强相关（`media3/subtitle/open_cc`）建议归并到 `player_component`（见 4.6）。

### 3.1 `core_contract_component`（新增）

**职责**

- 跨模块契约：ARouter `IProvider` 接口、跨模块数据协议、路由常量（如 `RouteTable`）、跨模块事件协议。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.config.RouteTable`
- `com.xyoye.common_component.services.*`（DeveloperMenuService / Screencast*Service / StorageFileProvider）
- `com.xyoye.common_component.service.Media3CapabilityProvider`（并修正其依赖的 Bundle 类型归属）
- `com.xyoye.common_component.bridge.*`（PlayTaskBridge / ServiceLifecycleBridge / LoginObserver）

**依赖建议**

- `api(project(":data_component"))`（若契约暴露 data types）
- `api(Dependencies.Alibaba.arouter_api)`
- `implementation(AndroidX.lifecycle_livedata)`（若保留 LiveData 协议；也可后续改为 Flow 以降低 AndroidX 依赖）

### 3.2 `core_log_component`（新增）

**职责**

- 统一日志系统（文件、采样、策略、格式化）、异常上报（Bugly）、telemetry 日志入口。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.log.*`
- `com.xyoye.common_component.utils.ErrorReportHelper`
- `com.xyoye.common_component.utils.DDLog`（如存在）
- `com.xyoye.common_component.config.LogConfigTable` / `LogConfigStorage`（按实际归属）

**依赖建议**

- `implementation(Dependencies.Tencent.bugly)`（上报实现）
- 视需要依赖 `core_system_component`（获取路径/Context）

### 3.3 `core_system_component`（新增）

**职责**

- 平台级基础设施：Application/Startup 初始化、权限、全局 Context、路径/IO、安全、通知/Receiver（如不单拆）、通用系统工具。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.base.app.*`（BaseApplication/BaseInitializer）
- `com.xyoye.common_component.application.permission.*`
- `com.xyoye.common_component.receiver.*`（含 NotificationReceiver 等）
- `com.xyoye.common_component.notification.*`（若不单拆 notification 组件）
- `com.xyoye.common_component.utils` 中偏“系统/IO/安全/路径/线程”的部分：  
  `ActivityHelper/AppUtils/ScreenUtils/DiskUtils/IOUtils/PathHelper/SecurityHelper/SecurityHelperConfig/SupervisorScope/...`

**依赖建议**

- `api(project(":core_contract_component"))`
- `implementation(Dependencies.AndroidX.startup)`
- `implementation(Dependencies.AndroidX.core)` / `appcompat`（按实际）
- `implementation(project(":core_log_component"))`（异常上报/日志）

### 3.4 `core_network_component`（新增）

**职责**

- 网络基础设施：Retrofit/OkHttp client、拦截器、请求封装、通用 Header/域名切换、Moshi 基础适配器等。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.network.Retrofit`
- `com.xyoye.common_component.network.helper.*`
- `com.xyoye.common_component.network.request.*`
- `com.xyoye.common_component.network.config.*`
- `com.xyoye.common_component.utils.moshi.*`
- 资源：`common_component/src/main/res/xml/network_security_config.xml`（建议放入该模块，确保 feature 单独跑时也能解析）

**依赖建议**

- Retrofit/OkHttp/Moshi 依赖留在本模块
- `implementation(project(":core_log_component"))`（LoggerInterceptor、错误上报等）
- `implementation(project(":core_system_component"))`（若需要系统配置/证书/设备信息）

> 注意：**不要**把 `AnimeRepository/UserRepository/...` 这类“业务 repository/service”放在 `core_network`；它们应随业务归属到 feature 或 provider（见 4.4/4.5）。

### 3.5 `core_database_component`（新增，或并入 data_component 的备选方案见 7.2）

**职责**

- Room Database/Dao/迁移、DatabaseManager（统一 DB 入口）。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.database.*`

**依赖建议**

- `implementation(Dependencies.AndroidX.room_ktx)` / `kapt(room_compiler)`
- `implementation(project(":data_component"))`（Entity 定义在 data_component）
- `implementation(project(":core_system_component"))`（Context）
- `implementation(project(":core_log_component"))`（可选：DB 异常上报）

### 3.6 `core_storage_component`（新增）

**职责**

- Storage/StorageFile 抽象与协议实现（SMB/FTP/WebDav/Remote/Alist/Torrent/…），本地代理/播放服务器、文件解析、source 管理等。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.storage.*`
- `com.xyoye.common_component.resolver.*`
- `com.xyoye.common_component.source.*`（VideoSourceManager/StorageVideoSourceFactory/…）
- `com.xyoye.common_component.utils` 中偏“存储/媒体扫描/压缩/下载协议”的部分：  
  `meida/*`、`seven_zip/*`、`thunder/*`、`RangeUtils/StreamHeaderUtil/...`

**依赖建议**

- `implementation(project(":core_network_component"))`
- `implementation(project(":core_system_component"))`
- `implementation(project(":core_log_component"))`
- 三方：`smbj/commons-net/sardine/simple-xml/nano-http` 等按协议放入本模块

### 3.7 `core_ui_component`（新增）

**职责**

- UI 基建与通用 UI 资源：BaseActivity/BaseFragment/BaseViewModel、Adapter/Paging、通用控件/对话框、主题与通用 drawable/layout。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.base.Base*`（Activity/Fragment/ViewModel/LoadingDialog 等）
- `com.xyoye.common_component.adapter.*`
- `com.xyoye.common_component.weight.*`（控件、dialog、binding）
- `com.xyoye.common_component.extension` 中 UI 相关（Activity/Context/UI widgets/RecyclerView/Notification Extensions 等按归属拆分）
- 资源：`common_component/src/main/res/*` 中通用 UI 资源（layout/drawable/anim/values 等）

**依赖建议**

- `implementation(project(":core_system_component"))`
- `implementation(project(":core_log_component"))`（UI 层错误上报）
- UI 三方：`material/recyclerview/paging/coil/immersion_bar/...`

### 3.8 `bilibili_component`（可选但强烈建议新增）

**职责**

- Bilibili 业务域的“纯能力模块”：登录态、签名、风控、历史、播放信息获取、弹幕下载等；不包含 UI 页面。

**从 common_component 迁移内容（建议）**

- `com.xyoye.common_component.bilibili.*`
- `com.xyoye.common_component.network.service.BilibiliService`（与 bilibili domain 紧耦合）

**依赖建议**

- `implementation(project(":core_network_component"))`
- `implementation(project(":core_log_component"))`
- `implementation(project(":core_system_component"))`（路径/缓存/设备信息等）
- `implementation(project(":data_component"))`

> 说明：Bilibili 相关 UI（如登录对话框、风控验证页）继续留在 `storage_component/user_component`；它们通过 `bilibili_component` 提供的能力完成调用。

---

## 4. 迁移映射（从 common_component 出发）

### 4.1 一级包到目标模块（总览）

| common_component 一级包/目录 | 目标模块 |
| --- | --- |
| `adapter` / `weight` / `base`（UI 基建） | `core_ui_component` |
| `log` | `core_log_component` |
| `network`（基础设施部分） | `core_network_component` |
| `database` | `core_database_component`（或并入 data_component） |
| `storage` / `resolver` / `source` | `core_storage_component` |
| `application/permission` / `base/app` / `receiver` / `notification` / 系统类 utils | `core_system_component` |
| `services` / `service` / `config/RouteTable` / `bridge` | `core_contract_component` |
| `bilibili` + `network/service/BilibiliService` | `bilibili_component`（建议） |

### 4.2 `config` 的拆分建议

- **路由常量**：`RouteTable` -> `core_contract_component`
- **基础配置与环境**：`AppConfigTable/DefaultConfig/DevelopConfig*` -> `core_system_component`
- **日志/网络/存储等子域配置**：随域迁移到对应 core（例如 `LogConfigTable` -> `core_log_component`）
- **播放器/字幕相关配置**：建议迁移到 `player_component`（或未来的 `player_contract`/`player_core`）

### 4.3 `extension` 的拆分建议

- UI 扩展：`AppCompatActivityExt/RecyclerViewExt/TextViewExt/SeekBarExt/ImageViewExt/...` -> `core_ui_component`
- 文件/Document/磁盘共享：`FileExt/DocumentFileExt/DiskShareExt/...` -> `core_storage_component` 或 `core_system_component`
- 通知扩展：`NotificationExtensions` -> `core_system_component`（或未来拆 `core_notification_component`）
- Media3 扩展：`Media3Extensions` -> `player_component`（避免 core 依赖 player）

### 4.4 `network` 中业务 repository/service 的归位（关键）

将 `core_network_component` 限定为“基础设施”，把业务 repository/service 移动到其所属业务域：

- `AnimeRepository` -> `anime_component`（或 `anime_component:data` 子包）
- `UserRepository` -> `user_component`
- `RemoteRepository/AlistRepository/ScreencastRepository/...` -> 更贴近 `core_storage_component`（它们本质服务于 Storage 协议实现）
- `Media3Repository/Media3TelemetryRepository` -> `player_component`（或 `player_contract`/`player_core`，视后续拆分深度）
- `BilibiliService` -> `bilibili_component`

### 4.5 `storage` 与 provider 的边界

短期（最稳妥）：

- 先把所有 Storage 协议实现仍放在 `core_storage_component`，避免引入“工厂/注册”架构大改。

中期（更干净）：

- 为每个大 provider（如 Bilibili/Alist）引入独立 `*_component`，并在 `core_storage` 中通过“注册/发现”机制接入（避免 `StorageFactory` 引入环依赖）。

### 4.6 播放强相关代码迁移出 common_component

建议将以下内容从 `common_component` 迁移至 `player_component`（或未来的 player 子模块），避免 core/system 反向依赖 player：

- `com.xyoye.common_component.media3.*`
- `com.xyoye.common_component.subtitle.*` 与 `utils/subtitle/*`
- `com.xyoye.common_component.enums.SubtitleRendererBackend` 等播放/字幕枚举
- `com.xyoye.open_cc.*` 与 `libs/*/libopen_cc.so`

配套措施：

- `BaseApplication` 不再直接初始化字幕/播放域；改为 player 域通过 Startup Initializer 或 Provider 在 app 组合根注入。

---

## 5. 建议的依赖分层（避免环依赖）

一个推荐的依赖方向（示意）：

```
data_component
   ^
core_contract_component
   ^
core_log_component        core_network_component
   ^                      ^
core_system_component ----|
   ^
core_storage_component
   ^
core_ui_component
   ^
feature modules (anime/local/storage/user/player)
   ^
app (组合根)
```

关键约束：

- contract 在最底层：只定义协议，不引入实现细节与 UI 资源
- system/log/network/database/storage/ui 逐层向上依赖（允许横向依赖但需避免回环）
- feature 只依赖“它需要的 core + data”，不再依赖“大一统 common”

---

## 6. Gradle 依赖拆分建议（按三方库归属）

| 依赖/工件 | 推荐归属 |
| --- | --- |
| Retrofit/OkHttp/Moshi | `core_network_component` |
| Bugly | `core_log_component` |
| MMKV（含 annotation/processor） | `core_system_component`（或专门的 config/kv 模块） |
| SMBJ / DCERPC / commons-net | `core_storage_component` |
| `sardine-1.0.2.jar` / `simple-xml-2.7.1.jar`（WebDav） | `core_storage_component` |
| Paging / RecyclerView / Material / Coil / immersion_bar | `core_ui_component` |
| `repository:seven_zip` / `repository:thunder` | `core_storage_component`（或未来拆 archive/download 子 core） |
| `libopen_cc.so` / `libsecurity.so` | `player_component`（open_cc）/ `core_system_component`（security）按用途归位 |

规则：

- core 模块**不要**再做“依赖伞”；禁止为了方便把三方库 `api` 到所有下游。
- feature 需要某库，就显式依赖对应 core（或直接依赖库），而不是通过 common 继承。

---

## 7. 迁移策略（建议按阶段落地）

### 7.1 推荐迁移顺序（从低耦合到高耦合）

1. 建 `core_contract_component`：迁移 RouteTable + Provider 接口 + bridge
2. 建 `core_log_component`：迁移 log + ErrorReportHelper
3. 建 `core_system_component`：迁移 BaseApplication/权限/路径/安全/通知等
4. 建 `core_network_component`：迁移 Retrofit/拦截器/request + network_security_config
5. 建 `core_database_component`：迁移 DatabaseInfo/Dao/DatabaseManager
6. 建 `core_storage_component`：迁移 Storage 体系 + source 管理 + 存储相关 utils
7. 建 `core_ui_component`：迁移 BaseActivity/Adapter/控件 + 资源
8. `common_component` 退化为“薄兼容层”并逐步删除：  
   - 旧入口仅保留 `typealias`/转发与 `@Deprecated` 标记  
   - feature 逐个替换为新 core 依赖

> ✅ 当前仓库已完成该步骤：业务模块已移除对 `:common_component` 的依赖，并已从 `settings.gradle.kts` 移除 `include(":common_component")`，仓库不再构建该模块；历史包名 `com.xyoye.common_component.*` 由各 `core_*`/feature 模块继续承载（仅用于兼容命名，边界以 Gradle 依赖为准）。

### 7.2 `core_database_component` 的备选方案

若希望减少模块数量，也可将 `database` 全部迁入 `data_component`（让 data 成为“完整数据层”）。  
但这会使 `data_component` 更偏 Android/Room，需权衡团队对“data 的纯度”诉求。

---

## 8. 风险点与护栏

- **资源与 namespace**：迁移到 `core_ui_component` 后，`R` 引用会变化；需统一处理 import，并避免资源重名冲突。
- **Manifest 合并**：`uses-permission/receiver/provider` 迁移后应确保 app 与 feature 单独跑（`app_manifest`）都能合并通过。
- **BuildConfig 分散**：不要跨模块引用他人 `BuildConfig`；公共常量建议下沉到 `core_system_component`（或 Gradle convention 统一注入）。
- **ARouter/kapt**：拆分后每个含 `@Route/@Autowired` 的模块都需要 kapt 参数与 compiler 依赖；Provider 接口建议统一在 contract。

---

## 9. 验收标准（拆分是否成功）

- `common_component` 明显瘦身：文件数/资源数/`api` 依赖数持续下降。
- feature 不再依赖 `common_component`（或仅依赖过渡期 compat），依赖图更清晰。
- 关键回归链路通过（至少）：任一入口拉起播放、媒体库浏览、投屏接收启停、登录/风控（如启用）。
