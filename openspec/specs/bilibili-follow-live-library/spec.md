# bilibili-follow-live-library Specification

## Purpose
TBD - created by archiving change add-bilibili-follow-live-folder. Update Purpose after archive.
## Requirements
### Requirement: Bilibili 关注直播目录
系统 SHALL 在 `BilibiliStorage` 根目录中提供“关注直播”目录，以展示当前正在直播的已关注用户。

#### Scenario: 根目录展示关注直播
- **WHEN** 用户打开 Bilibili 媒体库根目录 `/`
- **THEN** 文件列表包含 `历史记录` 目录（`/history/`）与 `关注直播` 目录（`/follow_live/`）

#### Scenario: 未登录时访问关注直播
- **WHEN** 用户打开 `关注直播` 目录（`/follow_live/`）且当前 Cookie 登录态不存在/失效
- **THEN** 系统提示用户扫码登录，并阻止继续加载关注直播列表

### Requirement: 关注直播分页加载
系统 SHALL 使用与 PiliPlus 对齐的数据源与参数分页加载关注直播列表，并仅展示正在直播的条目。

#### Scenario: 分页加载并过滤正在直播
- **WHEN** 用户打开 `关注直播` 目录（`/follow_live/`）
- **THEN** 系统请求 `GET /xlive/web-ucenter/user/following`，携带 `page`、`page_size=9`、`ignoreRecord=1`、`hit_ab=true`
- **AND** 系统仅将响应 `list` 中 `live_status == 1` 的条目映射为可展示的文件项

#### Scenario: 过滤导致空页时自动跳过
- **WHEN** 某次分页请求返回的 `list` 在过滤 `live_status == 1` 后为空
- **THEN** 系统自动尝试继续请求下一页以获取可展示条目
- **AND** 为避免极端情况，系统最多连续跳过 5 页后停止并按“无更多/空列表”处理

#### Scenario: 无更多时停止分页
- **WHEN** 系统已加载的正在直播条目数量达到或超过响应中的 `live_count`
- **THEN** 系统标记为无更多并停止继续分页加载

### Requirement: 分页状态按目录隔离
系统 SHALL 对 Bilibili 的远端分页目录分别维护分页状态，并保证 `PagedStorage.state` 与当前目录一致。

#### Scenario: 从历史切换到关注直播时状态不串台
- **WHEN** 用户从 `历史记录` 目录（`/history/`）切换到 `关注直播` 目录（`/follow_live/`）
- **THEN** 系统的分页状态与 `关注直播` 当前分页进度一致（不继承 `/history/` 的分页 state/cursor）

#### Scenario: 从关注直播返回历史时状态不串台
- **WHEN** 用户从 `关注直播` 目录（`/follow_live/`）返回 `历史记录` 目录（`/history/`）
- **THEN** 系统的分页状态与 `历史记录` 当前分页进度一致（不继承 `/follow_live/` 的分页 state/cursor）

### Requirement: 关注直播路径解析与播放历史兼容
系统 SHALL 能够从 `/follow_live/{roomId}` 路径与播放历史记录正确还原直播条目，并保持对旧 `/history/live/{roomId}` 的兼容。

#### Scenario: 通过路径解析关注直播条目
- **WHEN** 系统需要解析 `关注直播` 条目路径 `/follow_live/{roomId}`
- **THEN** 系统返回的文件项 `uniqueKey` 为 `bilibili://live/{roomId}` 且为可播放视频文件

#### Scenario: 播放历史保存 follow_live 路径时按原路径还原
- **WHEN** 某条直播播放历史记录的 `storagePath` 为 `/follow_live/{roomId}`
- **THEN** 系统还原的 `StorageFile.filePath` 为 `/follow_live/{roomId}`

#### Scenario: 兼容旧历史 live 路径
- **WHEN** 某条直播播放历史记录的 `storagePath` 为空或为 `/history/live/{roomId}`
- **THEN** 系统仍可还原并播放该直播条目（默认回退到 `/history/live/{roomId}`）

### Requirement: 关注直播条目可播放
系统 SHALL 将关注直播条目映射为可播放的 `StorageFile`，并复用既有直播播放链路。

#### Scenario: 关注直播条目映射为可播放文件
- **WHEN** 系统将关注直播接口返回条目映射为文件项
- **THEN** 每个文件项 `uniqueKey` 形如 `bilibili://live/{roomId}`
- **AND** 文件项 `filePath` 形如 `/follow_live/{roomId}`

#### Scenario: 点击关注直播条目可正常播放
- **WHEN** 用户点击任一关注直播文件项
- **THEN** 系统可以解析 `bilibili://live/{roomId}` 并获取可播放的 HLS/m3u8 地址并进入播放

### Requirement: Bilibili 分页目录 UI 一致性
系统 SHALL 对 Bilibili 的远端分页目录提供一致的 UI/交互行为，而非仅对 `/history/` 路径做特判。

#### Scenario: 分页占位在关注直播中可用
- **WHEN** 用户位于 `关注直播` 目录（`/follow_live/`）
- **THEN** 列表底部展示分页占位项，支持“加载更多 / 重试 / 无更多 / 刷新”等状态

#### Scenario: TV 刷新键在关注直播中可用
- **WHEN** 用户在 TV 模式下位于 `关注直播` 目录并按下菜单键/设置键
- **THEN** 系统触发刷新并重置分页状态

#### Scenario: 空列表文案与动作符合目录语义
- **WHEN** `关注直播` 目录当前无正在直播条目
- **THEN** 系统展示与“关注直播”语义匹配的空态文案（例如“暂无直播/当前无人开播”）并提供刷新入口

