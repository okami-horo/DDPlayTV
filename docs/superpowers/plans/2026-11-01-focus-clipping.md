# TV 焦点裁切修复与验证

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-11-01。基线：`18b09d5b1`，开始修复时工作区干净。用户先要求仅排查，随后授权修复全部五处确认问题；作品标题重复仍留待下一轮。本轮不包含实体 TV 安装或操作。

## 范围与实现

后续范围变更：用户于同日授权[清理闲置番剧发现链路](2026-11-01-legacy-anime-discovery-cleanup.md)，F04／F05 对应的季度／搜索页及专属测试已退役。下文保留当时的修复和验证证据；F01–F03 继续维护，不把已移除用例计入当前覆盖。

| 编号 | 问题与根因 | 修复 | 回归要求 |
|---|---|---|---|
| F01 | 最近添加两侧卡片负边距进入父布局 padding，边缘被裁去 8dp | `fragment_media.xml` 仅关闭该父布局的 padding 裁剪；保留网格原来的边距和列表视口裁剪 | 第一／后续行左右列焦点框完整，DPAD 横纵移动后仍完整 |
| F02 | 通用文件选择器单个目录名可宽于整个路径栏 | `item_file_manager_path.xml` 限宽 300dp、单行、中间省略；与主文件浏览器长路径策略一致 | 超长目录名保持完整焦点框，多个目录之间左右可达 |
| F03 | 播放器路径项可宽于 264dp 侧栏 | `item_file_manager_folder.xml` 限宽 220dp，预留侧栏 padding 和路径分隔符空间，单行、中间省略 | 展开的字幕／弹幕／音轨文件选择侧栏中，长路径焦点框完整 |
| F04 | 季度番剧短筛选网格使用 DpadRecyclerView，wrap_content 测成接近整屏 | 年份／季节／排序改用原生 RecyclerView + 现有 `grid(4)`，按内容自动测量；结果区保留 DpadRecyclerView | 三组筛选和结果区同屏可见，DPAD 可进入结果区并返回排序 |
| F05 | 番剧搜索类型／排序列表具有同一高度问题 | 两组短筛选改用按内容测量的 RecyclerView；原适配器、筛选状态和结果列表保留 | 展开增加行高，收起恢复；DPAD 穿过筛选区进入结果区 |

本轮只改五个生产布局文件，没有新增生产 Kotlin 逻辑，没有改变网络、排序、数据身份或播放器行为。长目录名的省略仅影响展示，点击使用的完整路径保持不变。

## 自动化回归

新增 `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/FocusClippingInstrumentedTest.kt`。五个测试使用生产布局与确定性列表数据，检查焦点项经过所有祖先裁剪后仍完整，并用 DPAD 验证导航；结果不依赖账号或在线数据。

修复前的五个用例均失败：媒体库为左右缺口、通用路径为宽度超界、季度筛选为完全出屏、搜索展开不改变异常高度。播放器测试最初遗漏了真实侧栏的短常用目录和滑入位置，修正测试场景后另行验证旧包失败，避免把隐藏侧栏本身当成产品问题。原始排查证据保留在 `.tmp/focus-clipping-audit/`。

## 本轮验证记录

- 独立 API 34 AVD：`emulator-5570`，私有 ADB 5053，1920×1080 / 320dpi。仅使用任务模拟器。
- 构建与完整检查：`:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint :app:ktlintCheck`，`verification-build.log` 尾部 **BUILD SUCCESSFUL in 4m 55s**。ktlint 仅检查本轮新增测试文件，没有宣称全仓格式通过。
- JVM XML 结果：113 个测试套件，**487 通过，2 个既有外部 FTP／SMB 夹具跳过，0 失败／错误**。
- 主页／导航／返回三个既有用例和设置持久化用例通过。双内核实际播放、错误消息可见及返回用例在补齐任务模拟器的媒体权限后通过，**OK (1 test)**；初次失败是夹具文件 `EACCES`，没有修改生产代码或放宽断言。
- 最终五项焦点回归：常规字体 **OK (5 tests)**，1.3 倍字体 **OK (5 tests)**。播放器在修正后的相同展开场景里，旧包仍失败（完整宽 1021px，可见 528px），新包通过；缺陷与隐藏动画分开验证。
- 搜索展开进一步按实际 10 项补齐第三行，常规字体和 1.3 倍字体分别 **OK (1 test)**，均能从第三行进入排序、结果区并收起。其余四项代码和输入未改变，复用前面的通过结果。
- 最终应用／测试构建和测试文件检查均再次确认 **BUILD SUCCESSFUL in 30s**。Debug ARM64 APK SHA-256：`d3851bc3a566dc901530ae1c8677e45f62f4b0640535a39a830444482390a915`。
- 已实际查看媒体库左右边缘、通用长路径、播放器长路径、季度筛选以及搜索展开／放大字体截图。截图与原始日志在 `.tmp/focus-clipping-fix/`，证据索引如下。

| 状态 | 截图 |
|---|---|
| 媒体库左／右边框 | [左侧](../../../.tmp/focus-clipping-fix/final-screens/media-left.png)、[右侧](../../../.tmp/focus-clipping-fix/final-screens/media-right.png) |
| 通用长路径 | [完整焦点框与中间省略](../../../.tmp/focus-clipping-fix/final-screens/file-dialog-long-path.png) |
| 播放器长路径 | [展开侧栏](../../../.tmp/focus-clipping-fix/final-screens/player-long-path.png) |
| 季度番剧 | [三组筛选与结果区](../../../.tmp/focus-clipping-fix/final-screens/season-filters.png) |
| 搜索展开 | [10 项](../../../.tmp/focus-clipping-fix/final-screens/search-expanded.png)、[1.3 倍字体](../../../.tmp/focus-clipping-fix/font-screens/search-expanded.png) |

执行方式沿用 `scripts/dev-env/gradlew-local.bat`，离线依赖／Robolectric／数据库临时路径分别由已有 `.tmp/ui-repairs-local.gradle`、`.tmp/tmdb-validation.gradle`、`.tmp/play-history-short-temp.gradle` 提供。当前 Windows JDK 启动曾因 Unix-domain socket 临时路径导致 `Unable to establish loopback connection`，本轮只在命令进程内将 `TEMP`、`TMP`、`java.io.tmpdir`、`jdk.net.unixdomain.tmpdir` 指向 `.tmp/focus-jvm` 后构建成功，未修改机器全局设置或项目构建配置。

## 验证边界

本次原生夹具验证、既有业务冒烟和截图分别记录。不是所有分辨率、账号、网络错误状态或实体电视的全面验收；未运行额外 Release lint，也未安装到实体 TV。作品标题重复未处理。任务模拟器用完后关闭；本轮修改未提交。
