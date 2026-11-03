# 文件浏览菜单精简任务

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-10-31。任务编号：`UI-FILE-MENU`。

**状态：2026-11-01 按用户“开始实施”授权完成代码、原型及本地验证；构建、JVM、lint、仪器用例和截图检查结果如下。** 本次未部署到用户电视，未执行 Git 提交。真实来源、物理电视和用户验收单独保留。

前置工作：[UX02](../../../document/player/playback-validation-followup.md#ux02常规遥控器操作说明过多) 已独立清理文件浏览底栏与预览中的常规按键教学。本任务进一步删除文件菜单/行末省略号，统一 MENU/SETTINGS 刷新，并实现长名阅读承接；FM01–FM10 使用本次证据，不继承 UX02 的通过结论。自动刮削仍未实施。

## 1. 目标与推进顺序

文件浏览以“选中文件 → 打开目录或播放视频”为主路径，移除重复操作和逐文件手工绑定入口，减少遥控器操作。沿用已实现的 [C：列表与预览](2026-10-31-file-browser-preview.md)，不重新设计整个媒体库。

按以下顺序推进：

1. **本任务：精简文件浏览菜单。** 移除目录仅有“打开”的菜单、视频重复“播放”和资源绑定/解绑菜单、视频行末“更多”按钮，修复焦点、长名称阅读与按键提示。
2. **后续独立任务：实现自动刮削。** 按[播放触发与同目录剧集入库设计](../designs/2026-10-31-playback-auto-metadata-design.md)实现自动识别、首页更新和同目录同剧发现。
3. **自动识别主链路可用后：退役旧人工匹配界面。** 用重新识别、刷新资料和问题说明承接原候选/季集选择。保留有效旧绑定及历史，不删除用户数据。

本任务可独立实施，不依赖自动刮削先完成；但必须保留第 3 节的元数据过渡路径，不能提前删掉仍有用途的识别页面。

## 2. 产品取舍与范围

| 能力 | 本任务决定 | 说明 |
| --- | --- | --- |
| 目录菜单“打开”、视频菜单“播放” | 删除 | 确定键已有同一主操作 |
| 文件行长按菜单、视频行末“更多” | 删除 | 不为已删除操作保留空弹窗或无效焦点 |
| 文件菜单手动查找/绑定/解除弹幕、字幕、外部音频 | 删除入口 | 不迁移到另一个文件操作菜单 |
| 文件菜单“识别 / 更正资料” | 随文件菜单移除 | 旧识别页面通过现有媒体库路径继续可达，见第 3 节 |
| 播放器字幕/音轨选择、关闭、弹幕搜索或重试 | 保留 | 自动匹配不能替代观看时的轨道选择、关闭和故障恢复 |
| 同目录 XML 弹幕、字幕发现及播放器自动弹幕匹配 | 保留现有实现 | 本任务不调整算法、缓存和匹配优先级 |
| 已保存弹幕/字幕/音频绑定、元数据关联、观看历史 | 原样兼容 | 不清列、不清库、不因移除入口而忽略旧路径 |
| 搜索、排序、刷新、目录资料扫描 | 保留 | 顶部 `browser_tools` 是扫描入口，与视频行末省略号不是同一个控件 |
| C 两栏结构、预览、分页、来源和返回栈 | 保留 | 只调整本任务涉及的控件与信息可达性 |

文件名规范有助于自动识别，但不能保证字幕语言、外部音轨或弹幕匹配永远正确。当前外部音频来自已保存的路径，没有可承诺的自动匹配替代能力；本任务接受移除文件浏览中的新增绑定入口，同时保留旧绑定读取及播放器内已有控制。不要在清理菜单时顺带开发新的音频匹配功能。

不包含：文件重命名/删除/移动、全盘扫描、新增协议、TMDB 凭据调整、数据库迁移、播放器布局重做、共享绑定能力的整体退役。

## 3. 元数据识别的过渡安排

当前 `StorageFileAdapter` 的文件菜单承载直接识别入口。移除后，在现有支持的本地、SAF、SMB、WebDAV 来源中保留以下路径：

1. 在文件浏览顶部执行现有“扫描此目录资料”。扫描负责发现文件，浏览刷新本身不等于资料扫描。
2. 通过“媒体库 → 全部作品 → 待识别文件”进入既有 `MetadataMatch` 页面，处理扫描后未匹配的文件。
3. 已关联作品继续使用详情页现有资料更正入口。

保留 `MetadataLibraryActivity`、`MetadataMatch` 及其路由，直到自动刮削后续任务提供替代流程。无需新增文件行菜单、逐行识别按钮或新识别页面。未支持资料扫描的来源保持当前能力边界，仍可浏览和播放。

实施时必须验证“新文件 → 目录扫描 → 待识别列表 → 识别页”完整路径，不能只验证路由类仍存在。若实际路径不能承接原入口，应先修复该路径再交付菜单移除，不得用删除测试掩盖缺口。

## 4. 按键、焦点和长名称约定

以下为文件浏览页面的目标行为；已有对话框打开时仍由对话框处理按键，不穿透刷新底层目录。

| 操作/状态 | 目标行为 |
| --- | --- |
| 目录行按确定 | 直接进入目录 |
| 视频行按确定 | 按现有播放/续播逻辑打开一次 |
| 长按确定 | 不定义独立文件操作，不弹菜单，不重复进入或播放 |
| `MENU` / `SETTINGS` | 均刷新当前目录；一次按压仅处理首次 `ACTION_DOWN`，忽略重复事件，不触发资料扫描 |
| 上下移动 | 文件行之间移动，预览跟随所选文件；分页加载/重试沿用现有语义 |
| 行内左右移动 | 无“更多”焦点或占位；右侧预览仅在文字溢出时提供阅读入口，见下文 |
| 返回 | 先退出预览阅读/已有弹窗，再按原逻辑返回上一级目录或调用页 |
| 页面重建或从播放器返回 | 恢复同来源、同文件及预览，不跳到首项；旧保存状态指向“更多”时映射回该文件行 |
| 刷新后选中文件消失 | 使用当前列表的有效回退位置；不恢复到隐藏控件或另一来源的同名文件 |

按用户后续反馈 [UX02](../../../document/player/playback-validation-followup.md#ux02常规遥控器操作说明过多)，移除底部“方向键 / 确定 / 菜单 / 返回”常驻说明，以及预览中“按确定打开/播放”等常规教学，不再把“菜单 更多”改写成“菜单 刷新”。保留真实状态信息、按钮名称与无障碍描述，移除说明后不留空白占位。顶部刷新按钮仍可达；刷新失败沿用保留旧列表、显示错误与重试的行为，不能变成空目录。

**完整文件名的阅读能力必须承接。** 原 C 方案借助操作面板展示完整长名，删除菜单后不能只留下截断标题。本任务选择在右侧已有预览内承接：普通预览保持展示用途；名称/路径超出可见区域时，显示简短阅读提示，允许从当前文件按右进入可滚动文本区域，上下阅读，左键或返回键回到原文件。正文不截断，不新增资源绑定或操作菜单。切换文件时重置阅读位置，空/加载/错误状态不留下可聚焦的旧预览。

该溢出状态属于 C 的局部交互补充，实施前需补充原型并实际查看；保持两栏比例、行密度及正常状态默认焦点不变，不需要重新选择 A/B/C。

## 5. 已核实的实现位置

下列职责为实施前核对的位置；2026-11-01 已重读源码与调用点，实际删除/保留结果见第 9 节。

| 文件/组件 | 当前职责与本任务关注点 |
| --- | --- |
| [StorageFileAdapter.kt](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt) | `ManageAction`、`showMoreAction`、绑定跳转、长按与行末按钮；保留主点击和真实预览数据 |
| [StorageFileFragment.kt](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt) | `savedMore` / `focused_more`、`showFocusedActions`、文件焦点与预览；兼容旧焦点状态 |
| [StorageFileActivity.kt](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_file/StorageFileActivity.kt) | `dispatchKeyEvent`、刷新、工具栏扫描入口与目录栈 |
| [item_file_browser.xml](../../../storage_component/src/main/res/layout/item_file_browser.xml) | 文件主行、`more_action_iv`、时长布局与左右焦点；移除按钮后修复约束，目录箭头可保留为非交互指示 |
| [activity_storage_file.xml](../../../storage_component/src/main/res/layout/activity_storage_file.xml)、[fragment_storage_file.xml](../../../storage_component/src/main/res/layout/fragment_storage_file.xml) | 底部提示、顶部工具和 C 预览；完整名称及溢出阅读 |
| [StorageFileFragmentViewModel.kt](../../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragmentViewModel.kt) | 旧绑定写入/解除辅助方法；仅在确认无其他调用后删除本次产生的孤立方法 |
| [MetadataLibraryActivity.kt](../../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataLibraryActivity.kt) | 待识别筛选及进入匹配页面；作为过渡路径验收，不在本任务退役 |
| [StorageVideoSourceFactory.kt](../../../core_storage_component/src/main/java/com/xyoye/common_component/source/factory/StorageVideoSourceFactory.kt)、[SubtitleFinder.kt](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/subtitle/SubtitleFinder.kt) | 旧附加资源读取与字幕匹配，作为保留行为检查，不因菜单移除重写 |
| [DanmuMatchCoordinator.kt](../../../core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt) | 弹幕匹配和缓存；改名不保证强制重新匹配，本任务保持现有行为 |

共享 `BottomActionDialog`、文件选择器、其他页面的 `more_action_iv`、绑定路由不得全局删除。只移除本任务造成且经调用检查确认的孤立代码、导入和专用资源。

## 6. 实施任务

### T1：确认现状并补齐受影响的 C 原型

- [x] 阅读项目规则、当前记忆和 C 任务，核对最新源码与未提交改动。
- [x] 启动并查看 [C 原型](../../../document/prototypes/tv-ui/files-prototype.html?variant=preview)，只调整行末按钮、按键提示和长名阅读状态。
- [x] 将第 7 节场景映射到原型/原生状态；确认正常列表、长名溢出、空/错误状态的焦点规则。

验证：已实际查看修改前后 C 原型、同数据长名阅读状态，并以脚本验证进入、滚动、返回和 MENU 行为；证据见第 9 节。

### T2：移除文件操作入口并保留兼容能力

- [x] 修改 Adapter 和局部行布局，移除视频省略号、长按菜单及相关 `ManageAction`；目录/视频保留原主点击。
- [x] 按调用关系清理本次产生的孤立代码；保留既有历史字段、资源读取、共享能力和播放器控制。
- [x] 保留顶部目录扫描与媒体库识别路由，验证第 3 节过渡链路。

验证：无空弹窗、无隐藏“更多”焦点、无多余尾部按钮占位；已有资源绑定仍进入播放输入，元数据识别仍可达。

### T3：统一按键与焦点恢复，承接长名阅读

- [x] 将 `MENU` / `SETTINGS` 统一到当前目录刷新，移除常规按键说明，处理按键重复及弹窗作用域。
- [x] 去掉“主行/更多”的新状态分支，旧 `focused_more` 恢复到同一文件行，保持来源/文件身份。
- [x] 在现有预览实现第 4 节的溢出阅读与返回，修复目录、搜索、刷新、播放返回和重建后的焦点。

验证：纯 DPAD 完成目录进入、播放返回、长名阅读、刷新恢复；不触发意外扫描，不丢选中文件，不出现焦点陷阱。

### T4：更新回归用例与验证记录

- [x] 修改 [FileBrowserPreviewInstrumentedTest.kt](../../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/FileBrowserPreviewInstrumentedTest.kt)：将旧“更多 caller / 菜单读长名”断言改为行焦点恢复、长名阅读、MENU 刷新及重复键行为，保留原有目录/预览异步检查。
- [x] 复用 [FileBrowserPlaybackReturnInstrumentedTest.kt](../../../app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/FileBrowserPlaybackReturnInstrumentedTest.kt) 覆盖实际播放返回；按受影响行为补充现有测试，不全局移除同名控件断言。
- [x] 检查其他文件浏览 DPAD、元数据入口测试；对旧绑定读取使用真实保存状态或现有测试夹具验证，不只检查数据库列存在。
- [x] 执行第 8 节验证，回填第 7 节证据，记录失败与未覆盖项。
- [x] 同步 C 账本、文档导航和 `activeContext.md`，自动刮削状态仍按其独立证据维护。

依赖：T1 → T2 → T3 → T4。实现过程中可同步更新受影响测试，不为每个步骤创建独立提交。

## 7. 验收矩阵

下表记录 2026-11-01 的本地实现和验收证据。构建、仪器测试、DPAD、视觉和真实来源/物理电视分开记录；旧 C 验收不自动算本任务通过。浏览主路径使用 DPAD；部分工具/播放器面板测试先用 `requestFocus` 定位入口，再注入遥控器事件，不将其描述为所有入口的端到端纯遥控器验收。

| 编号 | 场景 | 通过条件 | 当前状态 |
| --- | --- | --- | --- |
| FM01 | 目录/视频确定及长按 | 主操作正常；无文件操作弹窗；一次按压不重复进入/播放 | 已实现；目录长按/重复键用例通过，Media3/mpv 实际播放/返回通过。物理遥控器视频长按仍待验收 |
| FM02 | 视频、目录、混排列表 | 视频无省略号或隐藏焦点；目录箭头不聚焦；两栏密度与预览保持 C | 已实现；普通预览右键留在主行，已查看 `root/videos/mixed` 截图；C 原型同步 |
| FM03 | MENU / SETTINGS、重复键、弹窗 | 两键仅刷新当前目录一次；不扫描、不穿透弹窗；无常驻常规按键说明，刷新按钮仍可达 | 已实现；请求次数断言、重复事件和排序弹窗测试通过；顶部刷新截图已查看 |
| FM04 | 第二目录/非首个视频重建，旧 more 保存状态 | 恢复同文件主行及预览；不跳到首项，不跨来源复用焦点 | 已实现；注入旧 `focused_more=true` 后重建恢复同一视频主行；深层返回测试通过。跨真实来源未新增设备验收 |
| FM05 | 超长名称和路径 | 纯 DPAD 可读完整内容并回原行；普通预览不抢焦点，切换文件无旧文本 | 已实现；长名/深路径纯 DPAD 滚动到底、左/BACK 回行及切换重置通过；已查看 `reading/reading-end` 与同数据原型 |
| FM06 | 播放/续播后返回 | 文件身份、进度、选中位置、目录栈保留；播放器现有轨道/弹幕操作可用 | 双内核实际播放、8 秒定位、退出持久化、同文件续播通过；弹幕轨道→搜索页及本地文件入口可达。未测试真实网络搜索结果或新绑定写入 |
| FM07 | 同目录资源和已保存的外部资源绑定 | 原自动发现与旧弹幕/字幕/音频绑定仍按既有优先级读取；无静默清理 | 真实 Room 历史＋本地文件＋`StorageVideoSourceFactory.create` 验证通过：旧绑定优先，无绑定时同名 XML/SRT 自动发现；未改资源算法 |
| FM08 | 未识别新文件与已关联作品 | 扫描后可经待识别列表进入匹配；详情更正仍可达；不支持来源不冒充支持 | 本地新文件→顶部扫描→待识别→匹配页的真实路由通过；已关联作品详情更正通过。SAF/SMB/真实 WebDAV 扫描未验收 |
| FM09 | 搜索、排序、分页及返回 | 原筛选/持久化/分页语义保留；加载/重试可达，结束文字不聚焦 | 现有搜索/排序持久化、异步预览、分页加载/失败/结束测试通过；相关截图已保留 |
| FM10 | 空/加载/错误、刷新失败、文件消失 | 清除无效预览；失败保留旧列表并可重试；焦点有合法回退和返回路径 | 空/加载/错误与刷新恢复测试通过；删除已选文件后回退邻近有效行；已查看空、加载、错误、刷新失败及搜索无结果截图 |

## 8. 验证与交付

实施后在仓库根使用已存在的本机 Gradle 包装脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-env/gradlew-local.ps1 :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint --console=plain
```

按实际修改范围执行格式、模块依赖或播放边界检查。读取日志尾部确认 `BUILD SUCCESSFUL` 或 `BUILD FAILED`，分别记录本次结果和已知基线失败，不能因部分任务执行就报告通过。

仪器与冒烟使用任务自有 AVD，先确认设备为 `device`；测试选择覆盖上表的现有/更新用例。纯 DPAD 完成“启动 → 文件浏览 → 目录/播放/阅读/刷新 → 返回”，补测排序持久化、错误恢复和返回栈。实际查看相同数据、焦点及逻辑尺寸的原型/原生截图，特别检查移除按钮后的对齐和长名区域。物理电视与真实来源验收单列，历史安装授权不作为导航/播放测试授权。

## 9. 实施与验证记录（2026-11-01）

基线 `e187285a8`，开始时工作区干净。修改限于文件浏览业务/资源、三个相关仪器测试、C 原型及任务文档。

- `StorageFileAdapter` 删除专用 `ManageAction`、长按/更多/绑定跳转；ViewModel 仅删除由这些入口造成的孤立写入/解除辅助方法。保留主点击、共享文件提供者、其他绑定页面、历史读取、扫描、匹配路由及播放器控制。
- Activity 统一 MENU/SETTINGS，处理首次按下并尊重弹窗窗口焦点。Fragment 忽略旧 more 标记、保留文件身份，文件消失时按有效位置回退；预览仅实际溢出时开放阅读焦点，右进入、上下滚动、左/BACK 回原行。
- 原型仅改变 C 的菜单/阅读交互；A/B 保留为历史方案。新增 `scene=long-path` 对齐原生夹具数据。截图检查发现阅读提示未正确占位，调整布局后更新时机，并新增提示可见且有高度的断言。

### 测试与失败记录

- 有效红测：`.tmp/file-menu/red-device-permission.log` 在原实现按 MENU 后等待刷新失败，旧行为仍打开文件菜单；改后同用例 `.tmp/file-menu/probe-device.log` 为 **OK (1 test)**。此前两次启动被通知权限弹窗阻挡，不算行为红测。
- `.tmp/file-menu/device-final1.log`：**12/13 通过**，失败为扫描确认焦点同步；原测试单跑 `.tmp/file-menu/scan-probe.log` **OK (1 test)**，随后明确等待确认窗口焦点再操作。
- 最新 `.tmp/file-menu/device-final2.log`：三个文件浏览类的 **12 个实际用例全部通过**（Preview 8、PlaybackReturn 2、Compatibility 2）；同命令的元数据类包名填写错误，另有 1 个初始化错误。因此不记为 13/13 批次通过。改正 runner 参数后 `.tmp/file-menu/metadata-final.log` 的详情更正用例 **OK (1 test)**，最终覆盖共 13 个实际用例。
- JVM XML 汇总：116 suites，**480 通过、2 个既有跳过、0 失败/错误**；`.tmp/file-menu/jvm-results.json` 与 `jvm-result-files.json` 记录统计和文件列表。
- `.tmp/file-menu/validation.log` 最终 **BUILD FAILED in 4m 36s**：新增扫描测试有 ktlint 换行问题；作用域格式化后 `.tmp/file-menu/format-final.log` **BUILD SUCCESSFUL in 8s**。最终 `.tmp/file-menu/validation-final.log` 尾部确认 **BUILD SUCCESSFUL in 4m 29s**（1112 tasks，84 executed），涵盖下列检查。
- 最终检查命令包含 Debug/test APK、`testDebugUnitTest`、`lint`、`verifyModuleDependencies`、storage/app 作用域 `ktlintCheck`。本机继续使用 `.tmp/ui-repairs-local.gradle`、`.tmp/tmdb-validation.gradle` 的离线依赖和 `.tmp/play-history-short-temp.gradle` 的 Windows 短临时目录；`.tmp/file-menu/style.gradle` 只检查七个修改 Kotlin 文件，并保持仓库无 trailing comma 约定。这不是全库 ktlint 通过声明。

### 视觉、设备与范围

- 使用任务自有 API 34 AVD `emulator-5564`（1440×810，240 dpi）；未操作用户电视。浏览列表测试使用本地 WebDAV 服务模拟真实响应、慢响应和错误；扫描/播放/历史兼容测试使用本地文件与 Room 数据。
- 实际查看修改前后原型、`prototype-reading-final.png` 和同状态原生 `native-latest/reading.png`；两栏边界、行密度、预览占位及阅读焦点吻合。原生保留实际顶部扫描工具，原型未绘制此历史入口；字体换行、路径面包屑和占位图标存在已有平台差异，未扩展为整体视觉重做。
- 已查看 `native-final/` 的目录/视频/混排、空、加载、错误、阅读终点图，以及最新 `native-latest/` 的阅读、刷新失败、搜索无结果图；截图根目录 `.tmp/file-menu/`。`check-prototype.js` 实际验证 C 无行菜单、阅读进入/滚动/左右返回、MENU 刷新和重复 Enter 抑制，输出 `prototype-checked.png`。
- 播放器已有手动恢复入口保留：弹幕轨道面板→添加弹幕轨→搜索网络弹幕；搜索页含选择本地文件。未新增强制绕过缓存的“一键重新自动匹配”，未改变自动弹幕匹配优先级，也未实现自动元数据识别。
- 仍待独立验收：物理遥控器长按/快速反向、真实云/SAF/SMB 来源、真实网络弹幕结果和绑定、用户视觉接受度。构建、模拟器和截图结果不替代这些验收。
- 收尾检查：修改后的 3 个 XML 解析、原型脚本语法、7 份文档的 171 个本地文件链接及 `git diff --check` 通过。任务模拟器、独立预览浏览器和本地服务已关闭，日志与截图保留在 `.tmp/file-menu/`。
