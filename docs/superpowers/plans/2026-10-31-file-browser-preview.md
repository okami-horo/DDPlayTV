# 文件浏览 C 方案实施任务

日期：2026-10-31。任务编号：`UI-FILE-C`。

**状态：C 原生实现与本地验收已完成；物理电视、真实云服务和用户验收未执行。**

用户此前选择 **C：列表与预览** 后暂缓，现已明确恢复。按 C 实施，不再询问 A/B/C。此前暂缓记录保留为历史，不构成当前阻塞；本次不含电视操作、Release 部署或 Git 提交。

## 新会话从这里开始

1. 读项目根 `AGENTS.md` 的 Visual redesign acceptance，以及 `memory-bank/projectBrief.md`、`memory-bank/activeContext.md`；本任务已由用户恢复，不再沿用旧暂缓状态。
2. 读本任务和 [文件浏览设计](../designs/2026-10-31-file-browser-design.md)。启动 [原型](../../../document/prototypes/tv-ui/files-prototype.html)，明确使用 **`?variant=preview`**；实际看图和操作，不能只读 CSS 或凭上一会话描述推断。
3. 对照 [C 方案目录截图](../../../document/prototypes/tv-ui/previews/files-preview.png)，再核对当前原生源码。A/B 和此前全宽文件行均不是实施目标。
4. 查看工作区状态和最新来源能力。其他任务正在修改仓库，旧计划中的模块、存储类型、测试数量不构成当前事实；不回滚、不覆盖、不顺带提交其他任务的改动。
5. 按下面矩阵区分已实现、本地证据与真实来源/设备限制。后续改动重验受影响项，不能把网页设计、构建或安装结果当作原生视觉验收。

可复用启动命令：

```powershell
node document/prototypes/tv-ui/server.cjs
```

入口：`http://127.0.0.1:4173/files-prototype.html?variant=preview`。端口占用时给服务指定其他端口，不停止其他任务的服务。

## 已确认的目标与边界

- 沿用沉浸放映顶部导航、来源标题、路径和右侧工具栏；**主体固定为左侧文件列表、右侧随焦点更新的预览面板**。六个目录、纯视频及混排保持同一布局，分页不得切成 A 网格。
- 根目录的图标、行密度、列宽、间距和预览面板以 C 渲染画面为准。画布 1440 × 810，对应 960 × 540 dp；先量测实际渲染边界，再转换原生尺寸，不直接照抄 A 的行高或把像素当 dp。
- 目录预览显示目录图标、名称、路径和操作提示；视频预览使用已有封面与可用信息。未知数据留空或显示合适占位，不扫描目录拼海报、不推测子文件数量、不增加实时外部元数据请求。
- 预览是信息展示，默认不抢焦点；完整长名称可在文件操作面板读取。预览若出现必须滚动才能访问的信息，应解决可达性，不能依赖鼠标或触摸。
- 焦点从文件主操作移到同一行“更多”时保留该文件预览；目录切换、搜索结果变化、重建和空状态不能遗留上一文件画面。快速移动时，晚到封面不得覆盖新选中文件。
- 保留真实来源/文件身份、返回栈、分页语义、搜索范围、排序选项、播放/续播和字幕/弹幕/音频绑定；不新增存储协议、解码能力或示例媒体到正式应用。
- 只改文件浏览及其必要关联面板，不借此再次重做整套首页、播放器或设置。共享文件行被其他页面复用时，先确认影响范围再决定局部布局或共享调整。
- 本机原型与任务模拟器用于验证。既往电视安装/截图授权不是长期调试授权；恢复本任务也不自动授权操作 `192.168.31.70:5555`，后续按用户实际设备指令执行。

## 先前失败与本次约束

| 已发生的问题 | 本次必须留存的依据 |
| --- | --- |
| 没有完整目录页设计，却把文件行示例当作完整页面 | 页面/状态矩阵；缺图项明确标记，补图后才能声称覆盖 |
| 保留旧布局，只换底色、圆角、图标 | C 原型与原生同场景并排截图，核对两栏结构、密度和信息层级 |
| 构建、行为测试通过后宣布整套 UI 完成 | 分开记录实现、行为、视觉、物理电视和用户验收，不相互替代 |
| 六个真实目录的效果没有充分审视，“没有更多了”获得焦点 | 六目录夹具、到底焦点截图和 DPAD 记录；静态提示不聚焦，真实重试/加载动作可达 |
| 认为缩放能解释差异，或反复占用用户电视调试 | 记录画布、密度和逻辑尺寸；先在本地复现，相同比例仍不同就继续修改 |
| 新会话沿用过度完成的旧记忆 | 本任务作为进度唯一维护位置；记忆只保存 C、当前状态、验收边界和必读链接 |

## 状态覆盖矩阵

下表各项原生逻辑均已实现；“本地通过”仅覆盖所列夹具和路径。原型均为 `files-prototype.html?variant=preview&capture=1`，补充 `scene` 见表；原型图在 `.tmp/file-browser-c/prototype-*.png`，最终原生图在 `.tmp/file-browser-c/native-final2/`。所有截图为 1440×810；原生密度 240 dpi，即 960×540 dp。先前主要使用 A 的网页探索结果不计入本次验收。

| 编号 | 场景 / 原型 | 原生截图与本地行为证据 | 剩余边界 |
| --- | --- | --- | --- |
| C01 | 六目录 `root` | `root.png`；六项一屏、默认首项、向下进入 TV；左右两栏比例已看图 | 物理 TV |
| C02 | 视频 `videos` | `videos.png`、`existing-cover.png`；主项/更多可达，同文件预览；无封面/未知大小不填假值 | 真实来源封面种类、全部历史绑定组合 |
| C03 | 混排 `mixed` | `mixed.png`；文件夹/视频保持两栏、预览类型正确 | 大目录真实网络 |
| C04 | 长名与路径，`videos` 第三项 | `long-name.png`、`long-name-actions.png`、`deep-path.png`；完整长名在操作面板可读，深路径重建及逐级返回通过 | 超长名称/路径极限、电视字体 |
| C05 | `videos` 焦点移动 | `existing-cover.png`；延迟红图不能覆盖新选中蓝图，快速反向后预览匹配焦点 | 持续长按、低性能电视；颜色图是测试所有权夹具 |
| C06 | `empty` / `loading` / 搜索空结果 | `empty.png`、`loading.png`、`search-empty.png`；旧预览清除、加载不显示假计数、清搜索与返回可达 | 真实服务耗时 |
| C07 | `error` / `login` | `error.png`、`login.png`；失败显示重试而非空目录，115 原有编辑/授权页实际可进入并返回；失败不显示“已全部显示” | 实际 115 / 百度授权完成、令牌失效恢复 |
| C08 | `more` / `page-loading` / `page-error` / 加载结束 | `page-idle.png`、`page-loading.png`、`page-error.png`、`page-end.png`；加载只显示分页提示、重试可达、保留文件预览、结束文字不聚焦 | 使用 Pager 合同夹具，非实际 115 分页联网验收 |
| C09 | `refresh-error` | `refresh-error.png`；保留六目录和选中对象，底部重试恢复成功 | 实际协议断线/重连组合 |
| C10 | 搜索/排序/文件操作交互 | `search.png`、`sort.png`、`long-name-actions.png`；搜索范围文案、四种原排序设置、精确 caller 返回、长名完整可读 | 搜索输入用程序注入，未覆盖真实 TV 输入法；绑定/解除沿用原调用链，未重跑全部远端下载 |
| C11 | 目录/返回/重建/播放 | `deep-path.png`；WebDAV 多层路径、本地第二目录/第二文件及更多按钮重建通过；实际 Media3 播放后回同文件/预览，设置导航返回 shell；替换存储夹具会清旧预览 | 多真实来源从媒体库往返全链路、mpv、进程死亡、物理 TV 未验收 |

## 分阶段任务

### T1：重读当前实现并补齐 C 状态设计

- [x] 按上述新会话步骤核对授权、工作区、选定原型和最新来源范围。
- [x] 将 C01–C11 映射到当前页面、资源与状态所有者，记录未覆盖分支。
- [x] 为 C02–C11 渲染/补足必要原型画面，尤其分页失败、刷新失败保留旧数据和登录失效；明确不适用项及原因。
- [x] 保存 C 方案的目录、视频、混排、长名称/操作面板和异常状态图，确保后续不会拿 A 图当目标。

### T2：实现两栏结构与真实预览

已核实的入口（后续必须重新阅读源码，路径存在不代表职责不变）：

- `storage_component/src/main/res/layout/activity_storage_file.xml`
- `storage_component/src/main/res/layout/fragment_storage_file.xml`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_file/StorageFileActivity.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt`
- `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt`
- `core_ui_component/src/main/res/layout/item_storage_folder.xml`
- `core_ui_component/src/main/res/layout/item_storage_video.xml`

- [x] 落地左列表/右预览、顶部路径/工具栏和 C 的信息密度，不沿用全宽旧结构交付。
- [x] 绑定真实当前文件数据；处理默认选择、目录/来源切换、空状态、快速移动和晚到图片所有权。
- [x] 预览展示不新增独立业务状态源；焦点、选中文件与现有目录状态保持一致。

### T3：焦点、操作与状态恢复

- [x] 文件行 → 更多 → 操作面板 → 原调用按钮；返回目录恢复原文件及预览。
- [x] 区分静态 footer 与加载/重试操作；分页、刷新失败和授权恢复遵循现有行为契约。
- [x] 搜索和完整排序能力不缩水；绑定/解除、作品资料入口按当前来源能力显示。
- [x] 保留文件身份、播放/续播位置和返回栈；确认共享布局没有破坏其他页面。

### T4：原生行为与视觉分别验收

- [x] 优先复用 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvDpadRewriteInstrumentedTest.kt` 和 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/smoke/StorageBrowseRecoveryInstrumentedTest.kt`；先读当前用例，针对实质逻辑补回归，不照抄视觉实现写无价值测试。
- [x] 在任务模拟器用 DPAD 执行目录主路径和相关状态动作；部分工具初始焦点与异常数据由程序设置，详见矩阵限制。核实任务实例身份，不停止其他设备/ADB 服务。
- [x] 在相同逻辑画布和可比场景下保存 C 原型/原生图并实际查看；根目录数据一致，视频/分页夹具与网页示例数据差异见下方记录。核对两栏、密度、间距、截断、遮挡和焦点。
- [x] 完成与改动范围匹配的 Debug/测试 APK、受影响测试、lint 和格式检查；模块/播放边界按实际变更执行。读取 Gradle 尾部，记录 `BUILD SUCCESSFUL` 或 `BUILD FAILED`，不沿用旧结果宣称本轮通过。
- [x] UI 改动后的最终截图必须来自最终代码；修复差异后重验受影响项，不重复扩大测试范围。

### T5：交付与状态同步

- [x] 逐项记录 C01–C11 的原型、原生图、设备/密度、命令、结果与限制；真实来源和物理 TV 项仍明确保持未验收。
- [x] 仅按实际证据报告“实现完成 / 本地行为通过 / 本地视觉通过 / 物理电视未验收”等状态，不再笼统声称“整套重构完成”。
- [x] 发布、安装、Git 提交按恢复任务后的用户实际指令执行；本任务文档本身不授权这些动作。
- [x] 更新本账本、设计状态与项目记忆；保留历史失败记录及其适用范围，不把设计确认改写成原生验收通过。

## 实现记录

基线为 `50b6a770c` 加本轮未提交工作区。原有四份已暂存的 UI 复核文档保留在 index；本轮只追加工作树改动，没有暂存或提交。

- `activity_storage_file.xml` / `fragment_storage_file.xml` 实现 C 双栏、顶部导航/来源/路径/工具、底部状态，以及空/错误/加载/授权界面。原型补入分页加载/失败、刷新失败和登录失效；收紧长名称预览间距以保留操作提示。
- 新增独立 `item_file_browser.xml` 和局部图标/背景/样式。没有改共享 `core_ui_component/item_storage_folder.xml`、`item_storage_video.xml`。分页及路径资源经调用方检查只用于此浏览页。
- `StorageFileAdapter` / `StorageFileFragment` 绑定已有封面、大小、时长和历史；Coil 替换/分离时取消旧预览请求。目录主操作、更多、菜单、返回和重建均按选中文件恢复；静态结束不聚焦，加载期间保留分页 caller。
- 本地 `VideoStorageFile` 的目录 `uniqueKey()` 可能为空，导致不同目录混同。新增浏览器局部 `browserKey()`，空 key 回退该来源内的 `filePath()`，用于列表 diff、预览和焦点；**存储/播放/历史身份没有改变**。回归使用两个本地目录并恢复非首项目，不仅验证首行。
- `StorageFileMenus` 保留名称/大小、升降序、目录优先和原持久化；新增居中搜索与准确范围文案。绑定/解除及资料纠正仍走原入口，完整名称放入可操作首项。`MENU` 为文件操作，`SETTINGS` 刷新。
- 首页/设置通过 `RouteTable.App.TvMain` 回到已有 shell；媒体库按钮结束当前浏览页，恢复其调用页面。没有新增模块依赖边。

## 构建与测试证据

任务环境：只读 `Pixel_5_API_34` / `emulator-5558`、SwiftShader、1440×810 / 240dpi。仅安装 Debug 与测试 APK；所有 adb 命令明确指定此设备。原型服务 4182、独立无界面 Edge CDP 9227。

```powershell
# 全量 JVM、lint、模块治理及本轮 Kotlin 格式范围
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/file-browser-c/style.gradle --offline --console=plain

# 收尾改动后的 Debug/test APK、受影响 JVM、全量 lint、格式检查
scripts/dev-env/gradlew-local.bat ktlintFormat :app:assembleDebug :app:assembleDebugAndroidTest :storage_component:testDebugUnitTest lint ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/file-browser-c/style.gradle --offline --console=plain
```

- 已读输出尾部：全量 `verify4.log` 为 **BUILD SUCCESSFUL in 19s**；最终 `verify9.log` 为 **BUILD SUCCESSFUL in 32s**。中间 `verify5`–`verify8` 也通过；最后的变更属于文件页展示/状态，没有修改其他模块测试覆盖的业务逻辑。
- 实际读取 JVM XML：113 个 suite、474 个测试，**472 通过、2 个既有外部夹具跳过、0 失败/错误**。清单及汇总：`.tmp/file-browser-c/jvm-result-files.json`、`jvm-counts.json`。ktlint 只覆盖 `.tmp/file-browser-c/ktlint-files.txt` 的 11 个本轮 Kotlin 文件，不代表全仓历史格式清零。
- 最终 APK：`app/build/outputs/apk/debug/ddplaytv_v4.2.2-debug_universal-debug.apk`、`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。播放夹具为任务 AVD 上自行生成的 20 秒视频，不进入正式资源。

安装最终 APK 后执行：

```powershell
C:/Android/Sdk/platform-tools/adb.exe -s emulator-5558 shell am instrument -w -r -e class 'com.okamihoro.ddplaytv.ui.tv.FileBrowserPreviewInstrumentedTest,com.okamihoro.ddplaytv.ui.tv.FileBrowserPlaybackReturnInstrumentedTest,com.okamihoro.ddplaytv.ui.tv.TvDpadRewriteInstrumentedTest#storageRestoresDirectoryItemAndSupportsTheSecondaryAction,com.okamihoro.ddplaytv.ui.smoke.StorageBrowseRecoveryInstrumentedTest' com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终 `.tmp/file-browser-c/device-final2.log`：**OK (8 tests)**，26.386 秒。5 个 C 状态/交互测试、1 个实际 Media3 播放返回、1 个本地目录重建、1 个既有错误恢复。收尾前 `device-final.log` 同样 8/8；重跑不累计成不同用例。使用 direct runner，未声称 Gradle `connectedDebugAndroidTest` 通过。

目录主路径从首项开始，仅用方向/确认/菜单/BACK 进入、操作并逐级返回；重建由 instrumentation 触发。部分工具用例先程序定位焦点，搜索词及网络异常由夹具注入。WebDAV 是进程内 HTTP 夹具，分页/授权为现有接口合同夹具；这不等于真实 115、百度或其他远端协议验收。播放断言确认 Media3 已播放且位置超过 100ms，再等待窗口焦点后返回；未覆盖 mpv 和完整续播矩阵。

## 实际视觉核对与适配

已实际打开原型及原生截图，修正六目录显示不全、52dp 多余路径缩进、更多按钮纯白底、目录图标过粗、长标题挤掉提示、视频占位图过大、分页 footer 过高、分页叠加旧刷新转圈、失败假计数/成功文案和搜索系统栏显露。最终使用 `native-final2/`，不是修复前的 `native1`–`native3` 或 `native-final/`。

- 根目录左内容 x54、宽约774px，右面板 x860、宽526px；主体 y296–733，右图高180px。原生目录43dp（64.5px）、视频52dp（78px）、条目间6dp（9px）、分页38dp。六目录完整可见。
- 原生/网页字体及焦点外扩绘制存在平台差异，未声称像素相同；两栏、密度、标题/路径/工具信息层级保持 C。底部遥控提示使用原生文字，无网页键帽装饰。
- 原生未知时长/大小不复制网页示例值。名称为 115 的 WebDAV 测试来源会显示资料扫描工具；真实 115 不具备该能力。红/蓝封面用于晚到图片所有权断言，不是正式封面。
- 搜索/排序/文件操作复用已统一的居中弹窗：保留四项完整排序、清除搜索、资料及绑定能力，动作区可滚动且关闭恢复原 caller；没有照抄网页简化功能而删掉现有能力。

## 失败与修复记录

这些失败保留为本轮历史，不能改写为初次全通过：

| 日志 / 阶段 | 发现与处理 |
| --- | --- |
| `build1.log` / `build2.log` | 新 style 隐式 parent 不存在；替换表达式误写 XML 属性。改为显式空 parent 并修正 XML |
| `verify1.log` / `verify2.log` / `verify3.log` | 临时 init script BOM；minSdk25 不支持新 XML 的 horizontal/vertical 简写；测试把 Int 当枚举读取。分别修正，最终构建/lint通过 |
| `legacy-regression.log` | 旧错误恢复测试假设空态在 RecyclerView 内；改为识别独立空态按钮，刷新快捷键更新为 SETTINGS |
| `device2.log` | 硬件 Bitmap 不允许直接读像素，改为软件副本；快速反向可合法到路径栏，测试先确认 caller 归还并从路径回列表再校验预览，未将其当生产崩溃 |
| `device3.log` / `playback-rerun.log` | 任务 AVD 未授予视频读取权限；授权后实际播放通过，但 BACK 发在播放器获得窗口焦点之前。测试等待窗口焦点后再返回，最终实际播放用例通过 |
| 本地目录回归 | 本地文件夹空 key 会混同，加入浏览器局部路径回退和非首目录回归；旧目录重建失败当前已通过，不反推旧提交的全部失败根因 |
| 最终看图 | 发现分页叠加刷新圈、失败状态误报已完成/0项、搜索系统栏。局部修复并重新构建、运行8项、拉取并查看最终截图 |

PowerShell 会把模拟器 GL stderr 标为 NativeCommandError；测试结果以 runner 的逐项状态及 `OK`/`FAILURES` 为准。早期截图包含浏览器审阅外框的一组已用 `capture=1` 重新生成，比较以无外框 1440×810 图为准。

## 交付边界与历史

- 先前用户选择 C 后只登记设计/任务、暂缓实现；本次“文件浏览 C 可以实施了”明确解除暂缓，完成上述原生与本地验证。A/B 保留历史，不再重新选择。
- 物理 TV、真实云授权/网络分页、大目录性能、完整远程绑定下载、mpv/进程死亡及用户观感验收未执行；按具体场景继续，不能笼统声称所有 UI/来源全部通过。
- 本轮不含 Release、电视安装或 Git 提交。原有暂存内容保持；本轮修改保留在工作树。
- 已恢复任务 AVD 的 1080×2340 / 440dpi，撤销本轮视频读取授权、删除自建播放夹具并停止 Debug 应用。模拟器控制台关闭因认证不匹配失败；核对 PID/进程名/完整启动时间后关闭专用模拟器、Edge、4182 服务进程树。首次清理时间解析丢失小数导致校验拒绝，修正解析后再执行；没有关闭其他设备或 ADB 服务。
