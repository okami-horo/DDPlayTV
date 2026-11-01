# 首页足迹与剧集详情 B 实施记录

2026-11-01：用户选择 B 后明确授权“实施”，并要求继续。现已完成本地实现、构建、模拟器 DPAD 与截图验证。随后按用户“安装到 TV”“版本号 +1”的要求，将 Release `4.2.4` / `71` 于 17:31:33 覆盖安装到实体电视。本任务代码未提交；用户与实体电视运行验收单独保留。

权威设计为[设计说明](../designs/2026-11-01-home-watch-history-design.md)与[固定 B 原型](../../../document/prototypes/tv-ui/series-detail-prototype.html?variant=B)。验证时基线为 `8e931f41d` 加本任务工作区改动；并发的旧番剧发现退役已由其他操作提交，本任务未修改其内容。

## 实现范围

- 首页已识别的剧集／电影足迹卡进入作品详情；顶部继续播放与未识别文件卡保留原真实文件入口。卡片显示季集与文件进度，按作品稳定键聚合，识别完成或排序变化后恢复原卡。
- `MetadataHistory.kt` 共用来源／文件身份投影：核对 `storageId + uniqueKey`，不认领歧义身份、更换代次或 `FILE_CHANGED` 的旧记录；未知时长、特别篇、合并集分别表达。
- 详情重构为 B 的作品信息、主续播、横向集卡。首次默认上次季集；单季是静态标签，多季可切换；无历史从首个实际可播放的常规季集开始。完整简介、管理资料和多来源版本入口均保留。
- 原文件续播与每个版本的进度分离；保存季、集、滚动及焦点。没有缓存资料、没有文件、已知失效和首次加载均有独立状态。
- 播放准备失败时，播放器把可见错误通过既有 ARouter Activity result 返回详情，保留触发位置与原进度。只增加导航结果字段及失败回传，没有修改播放器 BACK、队列或存储策略。
- Room schema、扫描范围、识别规则、播放队列、进度存储及全局播放设置未改变。

## 页面状态覆盖

下列验证均为本地 API 34 AVD，不表示实体电视验收。截图已实际打开查看；原始证据在 `.tmp/watch-history/`。

| 编号 | 设计与实施 | 行为／视觉证据 |
|---|---|---|
| WH01 | Cinema 作品足迹 → B；顶部原文件续播 | Media3/mpv 从非首张剧集卡进入，续播第 3 集再选第 4 集，逐层返回原作品；[首页](../../../.tmp/watch-history/screens-final-normal/home-two-works.png) |
| WH02 | 电影进详情；未识别文件直接播放；未知季集不猜集号 | 电影双版本与返回、未识别真实文件播放通过；未知季集投影测试通过；[电影](../../../.tmp/watch-history/screens-final-normal/movie.png)、[未识别](../../../.tmp/watch-history/screens-final-normal/home-unidentified.png) |
| WH03 | 首次加载与真空态分开；识别完成保留焦点 | 测试在 STARTED 前停住数据生产者，检查真实初始 UI 不出现假空或提前焦点；新实例数据就绪落主按钮。空历史／后台识别与新集进度更新通过；[初始首页](../../../.tmp/watch-history/screens-loading-final/initial-home.png)、[空足迹](../../../.tmp/watch-history/screens-final-normal/home-empty.png) |
| WH04 | B normal／seasons，默认最近季集 | 第 2 季第 3 集定位、切换第 1 季不改变主续播、后台资料更新不抢焦点；[常规](../../../.tmp/watch-history/screens-final-normal/normal.png)、[多季](../../../.tmp/watch-history/screens-final-normal/seasons.png) |
| WH05 | fresh／单季／特别篇 | 无历史显示开始观看；单季无切季焦点；仅特别篇及未知时长路径通过；[无历史](../../../.tmp/watch-history/screens-final-normal/fresh.png)、[特别篇](../../../.tmp/watch-history/screens-final-normal/specials-merged-unknown.png) |
| WH06 | 同集多来源／合并集 | 42% 与另一版本 0% 或未观看保持独立；版本取消回原集；合并集只共享原物理文件进度；[版本选择](../../../.tmp/watch-history/screens-final-normal/versions.png) |
| WH07 | missing／invalid／empty／播放失败 | 失效续播禁用并默认可用集；失败原因持续可见且 8400ms 原记录未清零；空／无缓存资料可返回；[缺失](../../../.tmp/watch-history/screens-final-normal/missing.png)、[错误](../../../.tmp/watch-history/screens-final-normal/playback-failure.png)、[空](../../../.tmp/watch-history/screens-loading-final/empty.png)、[无缓存](../../../.tmp/watch-history/screens-loading-final/invalid.png) |
| WH08 | 未知时长／零位置／片尾 | 投影覆盖未知时长、0、99%、越界；真实 Media3 90%／mpv 95% 恢复点在资料刷新后保留，不触发新归零规则 |
| WH09 | BACK／重建／重排／合并 | 双内核逐层返回；版本取消、Activity 重建、后台更新与首页识别／排序通过。作品键保留到布局完成，避免第二次更新读取旧位置 |
| WH10 | 长标题／滚动／1.3 字体 | 960×540dp 首屏 4 张完整卡，194×109dp 图区；两行长标题、主动作、完整焦点通过；[横向滚动](../../../.tmp/watch-history/screens-final-normal/scrolled.png)、[1.3 字体](../../../.tmp/watch-history/screens-font-13/normal.png)、[放大后焦点](../../../.tmp/watch-history/screens-font-13/scrolled.png) |

## 构建与自动化验证

环境：独立 AVD `DDPlayTVWatchHistory`，私有 ADB 5059、`emulator-5574`，1920×1080 / 320dpi，Debug 包 `com.okamihoro.ddplaytv.debug`。只在任务模拟器安装与操作，测试媒体／图片均为本地夹具，未使用真实云账号。

- 最终全量命令：`:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint :local_component:ktlintCheck :app:ktlintCheck`。`final-verification.log` 尾部 **BUILD SUCCESSFUL in 3m 6s**。中途 Kotlin daemon 失败后 Gradle 自动回退编译，最终结果成功。
- JVM XML：**111 套件，489 项，其中 487 通过、2 个既有外部夹具跳过，0 失败／错误**，含本轮 7 项投影边界测试及 `repository/mpv_native`。`jvm-results-final.json` 是完整统计；先前 `jvm-results.json` 的 110／487 总数遗漏 repository 中的 2 项，不用它宣称通过总数。
- 新观看足迹测试：`suite-final-normal.log` **OK (11 tests)**。首次加载测试随后在 `retained-regressions.log` 中通过，本任务共 12 个新用例。
- 原有电影详情、Media3 90%、mpv 95%、首页顶部续播及媒体库→详情→管理的 5 项回归，与首次加载共 **OK (6 tests)**，见 `retained-regressions.log`。
- 1.3 字体：长标题／四张完整卡／横向滚动、电影版本、多季重建三项均 **OK (3 tests)**，见 `font-tests.log`，之后恢复字体为 1.0。
- 最后加载态视觉修整：禁用按钮透明度、隐藏空季标签及资料失效文案。`:app:assembleDebug :local_component:testDebugUnitTest :local_component:lint :local_component:ktlintCheck` 尾部 **BUILD SUCCESSFUL in 18s**，见 `loading-polish-build.log`。加载态、空／无效作品及设置持久化 **OK (3 tests)**，见 `loading-settings-tests.log`；其他输入未变的通过结果继续有效。
- 合计 **18 个不同 AVD 用例通过**（12 个新增、5 个原有媒体库／播放回归、1 个设置持久化），另有 3 项放大字体重复验证。主要导航路径使用真实 DPAD/BACK 注入。
- 格式检查仅针对本轮 Kotlin 文件，未宣称全仓 ktlint 无历史问题；`git diff --check` 通过。
- 最终 Debug ARM64 APK SHA-256：`f3d63ac15a82d9cdef75d286c9a3e29725e94344faf599e0e2b8ac88e71d8475`。

构建使用 `.tmp/watch-history/gradle.ps1` 包装 `scripts/dev-env/gradlew-local.bat`，沿用离线依赖／Robolectric／短临时目录配置：`--offline -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/watch-history/scoped-format.gradle`。没有修改全局 Java／SDK 设置。

## 视觉对照与修正依据

已查看 B 网页稿及原生普通、电影、多季、版本、无历史、缺失、失败、空、无缓存、初次加载与 1.3 字体画面。网页 1440×810 与原生 1920×1080 均以 960×540dp 等效结构对照，使用相同长标题、海报、前两集剧照、续播集和焦点。

原生的标题／上部信息、主动作、海报和下部横向集卡已按 B 排列；不再沿用旧纵向文件表。修正了初始列表居中裁掉第一张、主按钮首次焦点竞争、背景左缘和短标题信息区定位。1.3 字体保持主动作与焦点边框可见。真实文件没有可靠片长时显示来源，不复制原型“24 分钟”；没有剧照时显示集号。版本与简介复用现有原生弹窗，保留真实文件名和进度。

首次加载使用测试阻止数据订阅以稳定观察初始画面，再另开正常实例验证就绪焦点；这不是对真实慢磁盘时延或全设备性能的测量。最后网页截图脚本返回 `errors: []`。

## 中间失败与修复

- 新集卡最初缺 DataBinding 根节点、测试清理函数返回可空 Unit，均已修正后重建通过。
- 首次模拟器 System UI ANR 弹窗拦截测试，实际查看截图并关闭系统弹窗后原样重跑通过。
- 播放器测试最初在等待详情窗口获得焦点前多发 BACK，误退出详情；现停止于原 PlayerActivity 开始退出，并等待详情接管。未修改生产 BACK 语义。
- 首页真实缺陷：两次紧邻的 Room 更新之间过早清掉作品键，第二次更新按新列表的旧位置恢复到电影。日志确认顺序后，保留稳定键到布局消费完成，双内核主路径通过。
- 准备失败 Toast 在 Activity finish 后被系统阻止显示；补充失败结果回传详情，故障注入验证消息与原进度保持。
- 一次增量编译无法解析仍存在的本地投影函数；只清理 `local_component` 生成产物后同源重建 **BUILD SUCCESSFUL**。失败构建后的旧包测试不计入最终验收。

## 交付边界

本地实现、构建/JVM/lint、AVD 行为和实际截图分别完成。没有新增全库扫描、连续播放或整剧完成率。用户／实体电视体验、真实云端错误矩阵、其他分辨率与设备性能仍未验证；没有把安装或本地通过记作电视验收。任务 AVD 和私有 ADB 在证据收集后关闭，网页预览服务器保留。

## 用户授权的电视 Release 安装（2026-11-01 17:31）

用户要求安装到 TV，并将版本号加一。`Versions.kt` 从 `4.2.3` / `70` 更新为 `4.2.4` / `71`；打包基线为 `8e931f41d` 加本任务工作区改动。此前验证的 17 个源码／布局文件与 `.tmp/watch-history/verified-source-sha256.json` 一致，版本变更没有重复运行未变的行为测试。

- 构建：`.tmp/watch-history/gradle.ps1 :app:assembleRelease --offline`，已读取尾部 **BUILD SUCCESSFUL in 2m 40s**。
- 产物：`app/build/outputs/apk/release/ddplaytv_v4.2.4_arm64-v8a-release.apk`，68,301,329 字节；`apksigner verify` 成功、v2 签名有效，`aapt dump badging` 确认包名 `com.okamihoro.ddplaytv`、版本 `4.2.4` / `71` 与 `arm64-v8a`。
- 安装：本任务私有 ADB 5059 对 VIDAA TV `192.168.31.70:5555`（MT9653）执行 `install -r`，返回 **Success**。设备包信息确认 `versionName=4.2.4`、`versionCode=71`、`lastUpdateTime=2026-11-01 17:31:33`。
- 数据保留：没有卸载或清空数据；安装前后 UID 10111、首次安装时间 `2026-02-12 10:47:55` 相同。
- 本地与已安装 APK SHA-256 一致：`61c31c1652be407a2c793a85734e72dcee4101a1b11c5d91102880caace303ae`。

证据保存在 `.tmp/watch-history/release-delivery/`：`build.log`、`signature.txt`、`badging.txt`、`artifact.txt`、`install.log`、`package-before.txt`、`package-after.txt`、`installed-hash.txt`。完成后已断开电视并关闭私有 ADB 5059。未启动、导航或播放电视应用；本次安装不替代实体电视运行与用户验收。
