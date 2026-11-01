# 播放触发自动刮削实施账本

日期：2026-11-01。状态：**实现与本地验证完成；真实来源、准确率与物理电视验收仍开放。**

依据：[已确认设计与 AM01–AM13](../designs/2026-10-31-playback-auto-metadata-design.md)。延续 Cinema 与文件浏览 C。菜单精简已由前序工作提交为 `3ddb8efa7`，本轮为其上的未提交改动。本任务不提交、不部署用户电视。

## 实施顺序与完成条件

1. **解析和共享自动识别**：补普通电影、无年份唯一标题、季集等价写法和诊断；有界完整候选查询，歧义不绑定。验证：从真实文件名解析到 Room 绑定的回归及错误/旧绑定测试。
2. **播放触发契约**：在来源解析时捕获版本与稳定文件/父目录引用，在首次实际播放成功后只提交异步任务；覆盖双内核、历史和换集。验证：播放所有者去重、迟到/失效保护及断网播放。
3. **同目录同剧发现**：独立读取一层完整目录，本地发现未索引文件，SAF 不猜父目录；同作品/季复用、60 秒目录缓存、受控重试，不运行缺失清理。验证：第 3/12 集、混排、多季/副本、部分失败与新增集。
4. **问题/任务与兼容**：用原路由承接识别状态、重新识别和刷新资料，移除常规候选/季集人工流程；保留旧人工/忽略数据与历史。验证：页面覆盖矩阵、DPAD、焦点、截图、完整构建/JVM/lint/依赖与播放边界检查。

不增加协议、媒体流读取、全盘自动扫描、后台常驻服务或跨目录收集。先核对现有字段是否足以保存规则/尝试状态；仅在确需新增字段时迁移数据库。

## 覆盖与证据

AM01–AM13 的实现与本次证据见下表，旧 FM/C/TMDB 证据不自动继承。实现、自动化、AVD、视觉与真实来源/物理电视分列记录；真实网络匹配准确率不由夹具测试替代。

## 当前进展

- 已核对现有解析/匹配、Room 事务、独立目录读取和播放所有权；确认普通电影类型被拒绝、无年份被拒绝、扫描忽略候选截断、命名变化不失效、本地仅读旧索引等缺口。
- 已实现共享识别器、播放成功后的窄提交契约、独立一层同剧发现、本地未索引文件读取、来源版本保护、60 秒目录缓存和失败抑制。普通网络错误保存尝试时间，5 分钟后可随播放自动重试；明确重新识别可绕过。
- Room 20→21 只增加绑定尝试时间及任务触发文件 key/path/name。schema 20 迁移与全量 JVM 门禁通过，人工/忽略绑定和 8123ms 历史保留。
- 原 `MetadataMatch` 路由改为“资料与识别问题”，只读订阅状态；仅按重新识别/刷新时提交修改。任务重试保留原播放文件与同剧范围；目录主动扫描保留递归选项和旧人工绑定保护。
- 初始解析红测 `parser-red.log` 为 6 项中 2 失败；修复后 `parser-green.log`、核心初次编译 `core-build2.log` 均 BUILD SUCCESSFUL。后续发现并修复跨模块智能转换、测试 MMKV shadow、DpadRecyclerView 方法调用和 scoped ktlint 问题。
- 已修复问题页空记录/长名的 ScrollView 抢焦点、禁用刷新按钮的横向导航、取消操作后 busy 不复位。任务行使用自适应高度及 ItemDecoration 间距：DpadRecyclerView 1.4.3 的选中行尺寸未计入外边距，原 4dp 上下 margin 使本机高度少 12px，最后一行被裁切；尺寸回归同时检查 TextView 自身与父行边界。

## 页面与状态覆盖

沿用 Cinema；本次先实际查看更新后的 `document/prototypes/tv-ui/deep-pages.html?page=match` 与 `page=tasks`，再改原生界面。原型截图位于 `.tmp/auto-metadata/prototype-*.png`，普通/未识别/加载/网络失败/长文件名及任务样例均已渲染并查看；原生记录见下表。

| 页面/状态 | 设计与实现 | 当前验证 |
| --- | --- | --- |
| 问题页：已识别、未识别、旧忽略、网络错误 | match 状态面板；文件名、原因、建议格式、三个动作；不提供候选/季集向导 | 已识别/长名歧义/网络失败原生截图已看；旧忽略的数据兼容通过 JVM，专门截图未补 |
| 问题页：正在重试/刷新、空记录 | 后台任务可退出；禁用重复提交，焦点转返回；空记录引导播放/扫描 | 重试加载与空记录截图已看；纯 DPAD 跳过禁用刷新、返回及取消回调通过 |
| 任务：运行/完成/部分完成/失败/中断 | 明确播放触发与主动扫描范围；显示已入库、未识别、失败；优先任务/返回焦点 | 完成→部分完成的增量显示、行边界断言、截图通过；底部缓存按钮、返回通过；运行/中断状态为代码与任务测试证据，未补所有状态截图 |
| 首页文件卡变作品卡、同剧合并 | 保留真实 history ID；聚合后按作品映射原焦点 | 双内核播放→同剧 12 集→Cinema 已通过；首页图片使用夹具，不代表真实封面服务 |
| 未识别列表变作品、详情增量选集 | 按 asset/work/episode key 恢复焦点；保留旧忽略入口 | 长名问题页返回→文件变作品仍聚焦该作品的 AVD 用例通过；详情增量期间快速操作仍需真机验收 |

## 验收映射

| 编号 | 本次证据入口 | 当前边界 |
| --- | --- | --- |
| AM01–02 | MediaNameParserTest、PlaybackMetadataIndexerTest：真实文件名到 Room | 夹具候选，不代表真实识别准确率 |
| AM03–04 | PlaybackMetadataIndexerTest：第 3/12 集、多季、混排、历史保持；双内核实际播放→12 集入库→Cinema 更新 | 本地文件及夹具资料已通过；真实作品来源另验 |
| AM05 | PlaybackMetadataIndexerTest：S00、合并集、连续集数、缺集 | 源实际季集对应需用户内容验收 |
| AM06 | ActivePlaybackSessionTest、PlaybackBackendLifecycleTest：成功触发、重复事件/重建去重、实际切集；双内核 AVD 播放不被资料请求阻塞 | 真机快速连续切集/长时间播放仍待验 |
| AM07 | CatalogSourceReaderTest、PlaybackMetadataIndexerTest、既有来源/网络测试 | 真实大目录与四类来源联调仍开放 |
| AM08 | CatalogSourceReaderTest：本地未索引同级；Indexer：SAF 未知父目录；AVD 仅第 3 集有旧索引仍发现 12 集，未索引第 1 集可解析 | 真 SAF 提供方仍待验 |
| AM09 | Resolver/Repository/Indexer：捕获来源版本、改名、旧结果拒绝 | 无稳定 ID 的路径改名不迁移历史 |
| AM10 | RoomSchemaMigrationGateTest 20→21、旧人工/忽略测试 | 迁移与全量 JVM 通过，保留原进度 |
| AM11 | 网络/限流错误分类、持久冷却、重试；双内核实际视频在资料请求阻塞/抛网络错误时正常播放，问题页重试恢复 | 真实错误与图片服务覆盖率另验 |
| AM12 | 新问题页/任务页的纯 DPAD、文件→作品焦点、动态任务文字尺寸及实际截图 | 物理电视及完整状态矩阵仍单列开放 |
| AM13 | Indexer：60 秒后新增 13 集；中断任务下一次触发 | 无退出进程后继续工作的承诺 |

## 运行记录与边界

日志根目录：`.tmp/auto-metadata/`。使用任务专用 API 34 AVD `emulator-5564`，已核验 `device`/启动完成；1440×810、240dpi。视频实际由 Media3/mpv 播放，TMDB 返回值由测试替身控制，不新增网络模块测试依赖。设备测试使用直接 AndroidJUnitRunner，不能表述为 Gradle `connectedDebugAndroidTest` 通过。

- `device-final.log`：自动播放集成四项通过；问题页空状态和任务导航失败。`focus-device2.log` 的四项通过包含取消回调、空状态、长名→作品焦点和任务缓存按钮。
- `ui-regression.log`：9 项中 7 通过、1 失败、1 跳过。通过项包括双内核浏览返回/约 8 秒续播、原文件高进度恢复、Cinema/详情/问题页纯 DPAD；失败为任务缓存按钮，跳过为未启用的真实 TMDB 图片用例。
- 任务按钮旧测试以最高速度连续发 DOWN，未等待平滑滚动完成。诊断截图确认焦点仍在列表；补充等待实际滚动空闲后通过，没有通过重复发键/增加超时掩盖失败。
- `task-layout-device2.log`：3/4 通过；新增尺寸检查准确失败于 `summary.bottom=162 > row.height(168)-padding(18)`。修复将行外边距换为同尺寸列表装饰，最终结果见下方。
- `device-final2.log`：17 项中 14 通过、2 失败、1 真实 API 用例跳过。问题页四项及任务行尺寸全部通过；两项失败为 mpv 播放初始恢复进度断言。日志记录了 `seekTo(8000/19000)` 的异步恢复，旧测试只等待 `isPlaying`；高进度夹具仅 20 秒长，95% 后剩约 1 秒。测试改为等待实际位置达到原断言阈值，高进度校验在达到位置后暂停，避免检查过程中自然播放结束；未改变生产播放逻辑或放宽位置阈值。
- `device-resume-final.log`：调整同步后，四项播放自动入库/网络失败重试、双内核高进度恢复和 Cinema 历史恢复共 **7/7 通过**，44.058 秒。与前批未受改动影响的通过项合并，本轮 **16 个不同设备用例通过，1 个真实 API 用例跳过**，不是一次 17/17 的全绿批次。最终正常/错误/加载/空记录/长名/任务/作品焦点截图汇集在 `native-verified/`，已实际查看相关页面。
- `final-gates2.log` 曾完整通过。后续 `final-gates3.log` **BUILD FAILED** 于未改动的 `M115CryptoTest.decode_payload_roundTrips_withSyntheticDecryptedBlob`：`SecureRandom.nextInt(1024)` 生成零长度明文，与生产 `decodePayload` 拒绝只有 16 字节密钥的输入冲突。`task-layout-build.log` 单独复跑该类（4 项）及 APK 构建 **BUILD SUCCESSFUL in 13s**。本任务未修改云盘代码或该测试，随机边界问题保留为独立基线问题。

最终门禁命令（使用本机离线依赖与 Windows 短临时目录配置）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-env/gradlew-local.ps1 `
  testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest lint `
  verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck `
  -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle `
  -I .tmp/play-history-short-temp.gradle -I .tmp/auto-metadata/scoped-format.gradle `
  --offline --console=plain
```

ktlint 限于本轮 Kotlin/Gradle 文件；不把它称为全仓格式化基线已清零。测试恢复原全局内核设置、删除自己的来源/历史/文件夹具。真实本地收藏、SAF 提供方、SMB/WebDAV、大目录时延、实际命名识别率、完整图片/鉴权错误矩阵及物理电视仍需分别验收，不承诺准确率。

### 最终构建与静态结果

- `final-gates4.log`：上述完整门禁 **BUILD SUCCESSFUL in 2m 27s**。JVM XML 汇总为 **116 个套件、493 通过、2 个既有外部协议夹具跳过、0 失败/错误**，详见 `jvm-results.json`。
- 只调整仪器测试同步后，`test-sync-build.log` 的 test APK、app lint 与 scoped ktlint **BUILD SUCCESSFUL in 14s**；生产代码未再修改，完整 JVM/构建结果继续有效。
- 最新任务完成/部分完成、空记录、长名及作品焦点截图位于 `native-final/`。已实际查看，选中任务最后一行完整显示。问题页按原型将动作行调至 38dp（本机 57px，对照原型约 56px），间距约 15px；原生长文件名使用中间省略以保留末尾季集/扩展名，字体基线仍有约 7px 的平台差异。结构、信息顺序与状态动作保持一致，不以颜色替代原型结构。
- XML、原型 JavaScript 语法、172 个本地文档链接及 `git diff --check` 通过。任务 AVD、独立原型浏览器和本地原型服务器已关闭，日志与截图保留；未提交代码、未部署或操作用户电视。
