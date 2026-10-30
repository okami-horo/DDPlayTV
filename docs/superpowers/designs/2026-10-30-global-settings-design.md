# 新 UI 的全局配置收敛设计

日期：2026-10-30。状态：**已实现并完成本地验证**。实际结果、真实服务／物理电视边界和额外发现的新增来源选择器异常见[实施记录](../plans/2026-10-30-global-settings.md)。

## 1. 目标与边界

新 UI 使用统一的播放与浏览偏好，用户在全局设置中配置一次，所有存储来源遵循相同规则。存储来源只负责连接、鉴权和访问文件。

用户已确认的边界是：移除按来源保存的播放器内核和展示偏好；服务器地址、账号、密钥、根目录及协议参数仍按来源保存。

本次实施配置收敛。原生新 UI、TMDB 接入与元数据管理仍按[媒体库待办](../../../document/media-library/tmdb-metadata-followup.md)单独推进。

## 2. 实施前的源码依据

以下为 2026-10-30 实施前对工作区源码的核对，基线提交为 `ee00ffe084c243ba90ae9dbf07e3cde423b4d0fd`。表中描述的是收敛前行为，当前源码已按后续方案调整。

| 实施前行为 | 证据与含义 |
| --- | --- |
| 连接参数和两个单库偏好存于同一实体 | `MediaLibraryEntity` 原包含 `playerTypeOverride`、`remoteAnimeGrouping`，以及连接和协议字段；当前实体已移除两个偏好字段 |
| 九种可编辑来源提供内核覆盖入口 | 基线中的 `PlayerTypeOverrideBinder` 供外部目录、PC、FTP、SMB、WebDAV、Alist、百度、115 Open、115 Cloud 的编辑页使用；绑定器随本次收敛删除 |
| 播放入口先解析单库覆盖，再回到全局 | 基线中的 `PlaybackProfileResolver` 和[StorageVideoSourceFactory](../../../core_storage_component/src/main/java/com/xyoye/common_component/source/factory/StorageVideoSourceFactory.kt)；直链来源已忽略单库覆盖；旧解析器随本次收敛删除 |
| PC 媒体分组也是单库展示偏好 | `RemoteStorageEditDialog` 原提供“按文件／按番剧”，`RemoteStorage` 据此构建目录树；当前改读全局配置 |
| 其余主要偏好已经全局保存 | `PlayerConfig`、`SubtitleConfig`、`DanmuConfig` 由[播放器初始化](../../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt)读取；[StorageSortOption](../../../core_storage_component/src/main/java/com/xyoye/common_component/storage/StorageSortOption.kt) 使用全局 `AppConfig` |
| 来源内核支持声明没有实际差异 | `Storage` 原默认支持全部现存内核；百度、115 Open、115 Cloud 的显式声明也均为 Media3、mpv，并首选 Media3；这些声明已随覆盖模型删除 |
| 实施前数据库为版本 17 | 内核覆盖列由历史 14→15 迁移加入；当前 [DatabaseInfo](../../../core_database_component/src/main/java/com/xyoye/common_component/database/DatabaseInfo.kt) 为版本 18，历史迁移保留在 [DatabaseManager](../../../core_database_component/src/main/java/com/xyoye/common_component/database/DatabaseManager.kt) |

## 3. 配置归属

| 配置 | 目标归属 | 用户入口 |
| --- | --- | --- |
| 默认播放器、倍速、自动下一集、按内核区分的代理和 mpv 参数 | 全局 `PlayerConfig` | 设置 → 播放器 |
| 字幕样式、位置、自动加载与匹配 | 全局 `SubtitleConfig` | 设置 → 字幕 |
| 弹幕样式、速度、透明度、屏蔽等 | 全局 `DanmuConfig` | 设置 → 弹幕 |
| 排序、隐藏文件等浏览偏好 | 全局 `AppConfig` | 设置或现有文件页菜单，共用同一配置 |
| PC 端媒体按文件／按番剧分组 | 移至全局 `AppConfig` | 设置 → 应用 → PC 端按番剧分组 |
| 名称、服务器地址、账号、凭据、根目录 | 每个存储来源 | 设置 → 存储源 → 添加／编辑 |
| FTP 模式与编码、SMB 共享目录、WebDAV 解析及 TLS 策略等 | 每个存储来源 | 对应来源的连接参数 |
| 当前实际内核、临时内核重试、当前轨道和播放进度 | 当前播放会话 | 播放器现有操作及故障恢复入口 |

“单一全局配置”指每项用户偏好只有一个持久化来源。继续使用现有配置表按职责组织，不合并成巨型配置对象，不新增配置管理框架。凭据继续由现有加密存储管理。

## 4. 播放行为

### 4.1 内核选择

新建播放源时统一读取 `PlayerConfig.usePlayerType`。现有编号保持 Media3=`2`、mpv=`4`，默认 Media3；旧全局值和未知值继续遵循现行归一化规则。

所有来源，包括历史播放、直链、本地目录和网络存储，都采用同一全局选择。删除 `LIBRARY_OVERRIDE` 分支和仅为该分支存在的来源内核选择矩阵。

`PlaybackProfile` 继续表达当前实际内核及 `GLOBAL`／`FALLBACK` 来源，供传输准备和播放器使用。代理、请求头、连接获取及租约依然按实际内核和协议执行。

### 4.2 修改与生效时机

全局设置修改后立即保存。内核变更由下一次新建播放源采用；正在播放或已经准备完成的源保持当前实际内核，不因设置返回、Activity 重建而重启。现有字幕、弹幕等设置保留各自的即时生效和持久化语义。

mpv 播放失败后保留当前会话使用 Media3 重试的能力，按钮文案明确为“使用 Media3 重试”。该操作不改写全局默认值。后续新建播放源仍读取全局设置，已有活动会话重建则恢复其实际内核。

### 4.3 恢复与历史

现有[播放快照](../../../player_component/src/main/java/com/xyoye/player_component/playback/handoff/PlaybackHandoffState.kt)不持久化 `PlaybackProfile`；进程恢复按来源描述重新解析资源。因此不修改快照格式，恢复时自然采用当前全局内核。

保留现有活动会话、传输租约、队列和进度保存机制。配置收敛不改变资源所有权。

## 5. PC 端分组收敛

删除 PC 来源编辑页的分组选择，将其改为全局 `AppConfig.remoteAnimeGrouping`，默认 `false`（按文件）。该设置只对 PC 端来源有意义，所有 PC 来源统一遵循。

每个 `RemoteStorage` 实例创建时读取一次该值，同一实例的首次浏览、刷新和播放引用解析均使用这一值，避免缓存目录树与后续解析采用不同规则。全局开关的说明为“适用于所有 PC 端媒体库，重新进入后生效”。

旧库的分组值不参与全局值推导，升级后统一按文件展示；用户可在全局设置中启用按番剧分组。由此产生的展示变化属于本次产品变更，媒体文件、播放记录和凭据继续保留。

分组切换后新进入的浏览页从根目录开始，旧虚拟分组目录与焦点不跨模式恢复。历史播放和进程恢复继续优先使用 PC 视频 ID 定位，必须验证原有记录在两种模式下均可播放。

兼容边界需要覆盖无 Hash 的 PC 视频：[RemoteStorageFile](../../../core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/RemoteStorageFile.kt) 会退回用 `absolutePath` 计算身份，实施前的文件／番剧分组转换对该路径的赋值不同。旧记录即使找到同一个视频 ID，也可能在[恢复身份校验](../../../core_storage_component/src/main/java/com/xyoye/common_component/source/handoff/PlaybackSourceResolver.kt)被拒绝。

本次对这类旧记录增加有边界的兼容：来源 ID、类型和已保存的视频 ID 必须一致；当前视频没有 Hash，且旧身份可由保存的旧路径或已知旧空路径规则重建时，才允许归一化为当前身份，并沿用现有历史回填和描述更新机制。相同规则适用于快照及队列中的旧 PC 视频引用；缺少可靠视频 ID 或身份无法验证时保留现有失败处理。带 Hash 的媒体继续严格校验，不批量重建所有媒体身份。

实现补充：两种分组均为新 PC 视频叶子设置规范化文件路径，使无 Hash 身份不再依赖分组模式。旧无 Hash 引用仍按上述边界兼容；队列按已验证的视频 ID 替换选中引用，避免旧／新身份导致重复追加。

按番剧分组继续沿用当前只展示 `AnimeId` 有效资源的语义；全局化不改变其筛选范围。当前没有可用 PC 测试服务时，以固定响应数据覆盖转换和恢复，再将真实服务联调列为未验收项。

## 6. 数据升级方案

本次收敛同步移除实体中的 `playerTypeOverride` 和 `remoteAnimeGrouping`，新增 Room **17→18** 迁移，并由 KSP 导出[版本 18 schema](../../../core_database_component/schemas/com.xyoye.common_component.database.DatabaseInfo/18.json)。

采用显式重建 `media_library` 表的方式移除 `player_type_override`、`remote_anime_grouping` 两列，保持兼容项目当前最低 Android 版本。迁移应满足：

1. 逐列复制其余数据，保留媒体库主键和 `url + media_type` 唯一索引。
2. 保留自增序列高水位，避免重新分配曾使用过的媒体库 ID；凭据及历史均可能按该 ID 关联。
3. 保留播放历史、协议字段、WebDAV TLS 值及现有加密凭据引用；不进行破坏性重建兜底。
4. 不把任何一个库的旧内核覆盖写入全局配置，也不根据库的数量、排列或最后使用时间推断全局值。用户已有全局配置继续有效。
5. PC 分组使用新增全局默认值，旧列仅在升级时退出 schema。
6. 历史迁移及历史 schema 保持原样，通过现有迁移链升级到 18。

选择同步迁移是为了让实体和运行逻辑都退出单库偏好模型，避免后续新 UI 继续绑定失效字段。仅保留旧列并停止读取可减少迁移工作，但会留下兼容字段，本稿不采用该方案。

## 7. 代码与 UI 调整范围

| 区域 | 本次实施的变更 |
| --- | --- |
| `storage_component` | 删除 `PlayerTypeOverrideBinder`、内核覆盖公共布局、九个来源编辑页的绑定和 include；清理关联资源；PC 编辑页移除分组控件；重新接好外部目录页约束与各编辑页 DPAD 路径 |
| `data_component` | 移除实体的两个偏好字段与相关输出；移除 `PlaybackProfileSource.LIBRARY_OVERRIDE`，保留运行时 profile |
| `core_contract_component` | 删除无实际差异且随覆盖选择退出的 `supportedPlayerTypes`、`preferredPlayerType` 接口 |
| `core_storage_component` | 删除覆盖解析器及三处重复能力声明；全局 profile 构建不再依赖媒体库；PC 分组读取全局实例快照；兼容无 Hash 旧 PC 视频在分组变化后的身份校验与历史回填 |
| `core_system_component`、`user_component` | 增加 PC 分组全局开关及设置绑定；播放器、字幕、弹幕沿用现有全局配置 |
| `core_database_component` | 增加 17→18 迁移、版本 18 schema 和数据保留验证 |
| `player_component` | 保留实际内核和临时回退状态；修正重试文案，移除失效的覆盖来源引用 |
| 测试与维护文档 | 替换单库内核选择用例；同步现行内核说明、配置归属及验收记录 |

源配置页继续提供连接测试和自动保存。新 UI 的存储源编辑流程只展示连接必需信息；不会再提供“跟随全局／单库内核”选项。现有分组能力先移至全局，后续元数据 UI 是否继续展示该选项由其设计另定。

## 8. 实施顺序与验收

以下为本次验收要求。源码实现、构建及本地回归已完成；逐项结果、失败记录和未覆盖范围以[实施账本](../plans/2026-10-30-global-settings.md)为准，真实服务及物理电视未验收项保留。

1. **数据与解析收敛** → 验证新安装及 15／16／17→18 升级链，字段移除、其余列数据、索引、自增高水位与凭据 ID 关联正确；不同旧覆盖值均不影响全局选择。
2. **编辑页与全局入口调整** → 验证九类编辑页能保存连接参数、测试连接和返回；仅用 DPAD 可达所有保留控件；外部目录选择按钮约束有效。
3. **全局生效与会话回归** → 验证不同来源分别在全局 Media3、mpv 下播放；旧全局值归一化；设置保存／重新进入；当前播放重建；临时 Media3 重试不改全局；历史与进程恢复。
4. **PC 分组回归** → 至少两个 PC 来源统一跟随全局；实例内刷新模式一致；切换模式后新页面从根目录开始；分别覆盖有 Hash，以及无 Hash 但视频 ID 有效的旧历史、快照和队列，验证旧番剧模式升级到文件模式及反向切换；ID 缺失或不匹配、不可验证身份必须拒绝，按番剧模式的既有筛选范围保持一致。
5. **工程门禁** → Debug 构建、JVM 测试、lint、相关格式检查、模块及播放边界门禁；设备可用时执行相关仪器用例与纯 DPAD 冒烟，保留日志及布局截图。Gradle 必须核对输出尾部的 `BUILD SUCCESSFUL`／`BUILD FAILED`。

本设计不要求先完成新 UI 或 TMDB 接入；配置收敛可作为其前置变更独立验收。

## 9. 已确认范围

用户已于 2026-10-30 授权实施：**播放与浏览偏好全局化、连接参数按源保留、PC 分组改全局、同步 Room 数据迁移、保留当前会话的临时故障恢复**。

本稿已获用户授权实施；代码、验证结果与剩余边界统一记录在实施账本，设计中的验收要求不代表测试已经通过。
