# A03 容器与边框减层执行账本

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-11-02。基线：`5324dd7f3`。用户已确认 [设计与 C01–C09](../designs/2026-11-02-a03-container-hierarchy-design.md)，授权实施五个工具页和番剧详情三个子页。

## 当前进度

| 任务 | 状态 | 验证要求 |
| --- | --- | --- |
| T1 工具页减层 | 已实现 | 五页撤局部大底板；缓存普通行透明，目录轻分组，许可同层阅读；主题保存、完整许可／归属及真实操作保留 |
| T2 番剧子页减层 | 已实现 | 撤公共底板，分集普通行／图标透明；补齐行内左右焦点，保留共享作品卡与现有业务 |
| T3 视觉与行为回归 | 本地完成 | 最终普通字体 9＋7 项、大字体 9＋2 项分批通过；已查看八页前后与状态图、目录路径、独立图标和两个网站的完整焦点 |
| T4 工程与证据收口 | 本地完成 | 最新工程检查 BUILD SUCCESSFUL in 40s；92 张原生图／16 组对比、安装包身份、浏览器图片／链接检查完成；2026-11-03 用户确认实机验收通过 |

## 边界与现场

- 当前设计稿与记忆索引已由用户暂存，保留原暂存状态；原生实施阶段未改版本／打 Release。用户随后追加的版本与打包授权见文末，仍不暂存、不提交。
- 使用独立端口 `emulator-5588`／ADB `5087` 的只读任务模拟器；不操作实体电视及默认 ADB 服务。
- 先构建并安装当前源码的 Debug／test APK，保存改前截图；实现后用相同数据与测试脚本采集改后。网页参考是设计重建，不能代替 APK 结果。
- 现有 A02 焦点、A06 面板、首页／详情 B／文件 C／播放 A、真实缓存／目录／许可／声明和业务数据均保留。

## 页面／状态跟踪

| 编号 | 范围 | 实现 | 原生／DPAD／视觉证据 |
| --- | --- | --- | --- |
| C01 | 缓存管理 | 仅撤右侧底板与普通行底色；左侧系统缓存入口保留 | 系统缓存确认取消、延时确认取消、清理任务截图夹具后统计归零，返回原焦点；真实缓存统计因截图产物变化 |
| C02 | 常用目录 | 撤右侧底板、两组间轻分隔；快捷目录最小高度 64dp，随大字体增高 | 未设置／长路径、选择器返回、重建持久化、开关与长按清除；路径高度断言通过 |
| C03 | 深色模式 | 撤右侧底板，保留手动选项与跟随系统、工具栏保存 | 模式显隐、保存后重新进入持久化、取消重启不提交、焦点与勾选分离 |
| C04 | 许可长文 | 撤列表底板与正文黑底；16dp 条目留白、细线与单层 2dp 阅读焦点 | 许可条数与实际 assets 一致；Plezy-mpv 超三万字正文可内部 DPAD 滚动，到达列表末条；普通主题正文／链接对比已适配 |
| C05 | 关于／归属 | 撤右侧底板；把原 24dp 留白移至滚动内容内部，避免裁切焦点 | 完整真实说明／TMDB 声明保留；两个网站依次 DPAD 可达、滚动后全可见；新增回归先失败，再于 1.0／1.3 两字体通过并查看截图 |
| C06 | 番剧简介 | 仅撤公共 viewpager 底板 | 阅读、展开／收起长资料、长简介、无简介、切页与返回；不复制网页夹具进生产 |
| C07 | 番剧分集 | 普通行／图标透明，状态委托现有 cinema_button；显式行内左右焦点链 | 排序、单独标记图标焦点与未登录反馈、全选／反选／退出、列表滚动与空态回到页签；真实账号标记成功另计 |
| C08 | 相关作品 | 仅撤公共底板；三列共享作品卡不变 | 相关／更多分组、跨行滚动、打开子详情后 BACK 恢复、回到页签。无推荐沿用原生隐藏推荐页签，不新增网页示意空页 |
| C09 | 字体／主题／焦点交叉覆盖 | 新资源限于两模块局部；A02／A06 和其他已选结构不改 | 最终八页 1.0／1.3 原生对比及五工具页普通主题已查看；A02／A06、真实双内核播放、侧栏返回／遮罩回收与设置持久化回归通过 |

## 运行记录

1. 基线构建首轮及 IPv4／单次 daemon 重试均失败，原因为 Windows Unix-domain socket 临时目录。使用已有的 `jdk.net.unixdomain.tmpdir` 与 `java.io.tmpdir` 配置后，`baseline-fixed.log` 结束为 **BUILD SUCCESSFUL in 1m 7s**。
2. 改前普通字体两项既有测试通过；1.3 字体的工具页断言在滚动结束前过早检查焦点可见性而失败。仅补充测试等待后，改前工具页和超长许可阅读两项通过；生产导航无需修复。额外四项工具／普通主题检查均通过。
3. 新测试初次编译因配置 getter 可空、setter 不可空而失败；修正测试恢复值后 `debug-build-fixed.log` 为 **BUILD SUCCESSFUL in 46s**。一次在构建尚未结束时取得旧 runner 的运行被主动终止，不作为有效证据；重新确认 APK 身份后重跑。
4. 局部资源实现后，普通字体工具／番剧／新增五项检查共 **OK (7 tests)**。已查看八页原生画面，减层符合设计方向；许可条目留白和目录分组线为设计预期变化。真实缓存统计随本任务截图产物变化，不能声称前后数字完全相同。
5. `testDebugUnitTest lint verifyModuleDependencies` 结束为 **BUILD SUCCESSFUL in 4m 40s**。仍保留既有 mpv AAR Kotlin 元数据诊断；该轮无失败任务。最终用例和图片身份继续收口。
6. 放大字体后，新增路径断言与分集图标检查暴露两处真实问题：固定 64dp 快捷目录裁切路径，以及分集右键跳排序。分别改为内容高度加最小高度、局部显式焦点链。空推荐检查则错误假定存在第三页签，核对原实现后改为保留两页签；不改变产品行为。
7. 推荐网格返回页签的测试未等滚动结束，第一次仍失败；补充每次移动的等待后，原八项两字体均 **OK (8 tests)**。A06／Cinema 兼容测试各有一次缺既有图片夹具的准备失败，补齐历史背景、四张封面和视频后相关用例通过，未为夹具问题修改生产代码。
8. 手工补查关于页第二网站时发现，滚动容器的 24dp 内边距会裁切已获焦按钮。新增 `aboutActionsScrollFullyIntoViewWithRemote` 在 1.3 字体下先失败（`about-focus-red.log`）；把同样的留白移至内部内容后通过（`about-focus-green.log`，3.442s）。只改局部 XML，未增生产 Kotlin。
9. 最新完整生产资源／测试构建、全量 JVM、app Debug lint、依赖治理与三份测试的限定格式检查为 **BUILD SUCCESSFUL in 40s**（`about-focus-final-gate.log`）。全量 JVM 报告为 516 项：**514 通过、2 项既有跳过、0 失败／错误**。原全量标准 lint 的通过记录保留。
10. 最终原生包：普通字体主检查 **OK (9 tests)**／98.140s、兼容回归 **OK (7 tests)**／54.747s；1.3 字体主检查 **OK (9 tests)**／98.329s、焦点／双内核回归 **OK (2 tests)**／10.363s。共 16 项不同场景有通过证据，其中 11 项在大字体复测；不是一轮 27 项不同测试。
11. 读取模拟器内 Debug／test APK 的 SHA-256，与保存的 `final-main.apk`／`final-test.apk` 分别一致。恢复字体并读回 **1.0**，核对只读任务 PID／端口后关闭 `emulator-5588` 与私有 ADB `5087`；预览 `18106` 返回 HTTP 200，默认 ADB 未操作。
12. 图库实际解码 **92 张** 1440 × 810 原生图片，检查 **95 个链接**、页面／字体／前后筛选、原图弹出及 760px 视口；脚本错误为 0。源码、原图、日志和 APK 哈希一致；`git diff --check` 通过，原暂存内容身份保持不变。

## 最终验证与可复查资料

| 检查 | 记录 | 结果 |
| --- | --- | --- |
| 全量标准 lint／JVM／依赖 | `evidence/gates-first.log` | BUILD SUCCESSFUL in 4m 40s |
| 最终资源构建／JVM／app lint／依赖／限定格式 | `evidence/about-focus-final-gate.log` | BUILD SUCCESSFUL in 40s；514 项 JVM 通过／2 项既有跳过 |
| 最终主检查，1.0／1.3 | `evidence/native-complete-normal.log`、`evidence/native-complete-font130.log` | 各 9 项通过：六项工具行为／内容检查、五页 DPAD、详情操作及空详情 |
| 最终兼容检查，1.0／1.3 | `evidence/regression-complete-normal.log`、`evidence/regression-complete-font130.log` | 7／2 项通过；Cinema 搜索与设置返回、A02 状态／字幕、播放器选集与侧栏、双内核与偏好持久化 |
| 身份与图库 | [verification.json](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/verification.json)、[浏览器验证](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/gallery-verification.json) | 17 份生产／测试源码，六个阶段性 APK 身份与最终安装读回；92 张原生图，16 组对比 |
| 清理 | `evidence/cleanup.log` | 字体 1.0；任务 AVD／私有 ADB 已关闭，预览保留 |

上表 `evidence/` 位于原生图库目录中，同时保留首轮构建失败、测试等待／夹具修正及关于页先失败后通过的日志。JUnit XML 汇总为 `evidence/jvm-report-summary.json`；仅两项既有测试跳过，没有将跳过计为通过。

本机最终工程命令（Gradle/JDK 17；实际 Kotlin 2.2.10、AGP 8.10.1、Gradle 8.11.1）为：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=E:/Project/DDPlayTV/.tmp/wh-jvm -Djava.io.tmpdir=E:/Project/DDPlayTV/.tmp/wh-jvm'
.\scripts\dev-env\gradlew-local.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug testDebugUnitTest verifyModuleDependencies :app:ktlintAndroidTestSourceSetFormat :app:ktlintAndroidTestSourceSetCheck -I .tmp\a03-containers\scoped-format.gradle --offline
```

限定格式脚本副本位于 `evidence/scoped-format.gradle`，仅作用于本轮三份测试源码。原生采用设备处于 `device` 状态时的直接 AndroidJUnitRunner，包为 `com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`；不是 Gradle UTP 连接测试的成功声明。

## 视觉依据与边界

- [原生前后／状态图库](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/index.html) 包含最终 APK 的八页／两字体对比和操作状态；[网页确认稿](../../../document/prototypes/tv-ui/container-hierarchy-prototype.html) 仍是设计重建。两者不混作证据。
- 使用 1440 × 810／240dpi、同数据夹具和相同焦点／滚动脚本。目录分组、许可留白及大字体目录行增高是有意差异；真实缓存统计随任务输出变化，未修图统一数字。原生标题、标签／页签、占位封面与浏览器字体仍按真实实现保留。
- 普通主题的既有 Cinema 静态白标题／品牌对比问题未扩展为全局配色重做；本轮新增许可正文、链接和焦点底色分别适配两主题。
- 工具页未新增不存在的加载／错误页面。服务加载／失败处理、完整在线账号标记／作品数据、外部网站内容与实际多音轨组合不从本地夹具推断验收。
- 实现、工程检查、原生视觉与 DPAD、实体电视／用户验收分别记录。初始 UI 阶段未操作／安装物理电视；后续 Release 打包和用户授权安装见下两节。用户视觉验收待完成，未暂存／提交。

## 用户追加：4.2.5 Release 打包（2026-11-02 18:45）

用户要求“版本号＋1，打包 Release”。`buildSrc/src/main/java/Versions.kt` 已由 **4.2.4／71** 更新为 **4.2.5／72**；本次只增加这两处版本值，A03 已验证的 17 份源码／测试输入哈希保持一致，沿用前述 JVM／DPAD 证据，未另行宣称新一轮运行验收。

使用相同 JDK／临时目录配置运行 `scripts/dev-env/gradlew-local.bat :app:assembleRelease --offline`，输出尾部为 **BUILD SUCCESSFUL in 3m 24s**，含 `lintVitalRelease` 通过。产物如下：

| 架构 | APK | 大小 |
| --- | --- | --- |
| ARM64 | [ddplaytv_v4.2.5_arm64-v8a-release.apk](../../../app/build/outputs/apk/release/ddplaytv_v4.2.5_arm64-v8a-release.apk) | 68,326,338 字节 |
| ARM32 | [ddplaytv_v4.2.5_armeabi-v7a-release.apk](../../../app/build/outputs/apk/release/ddplaytv_v4.2.5_armeabi-v7a-release.apk) | 64,562,678 字节 |
| 通用双架构 | [ddplaytv_v4.2.5_universal-release.apk](../../../app/build/outputs/apk/release/ddplaytv_v4.2.5_universal-release.apk) | 102,396,148 字节 |

已用 `aapt2` 读回三包均为 `com.okamihoro.ddplaytv`／`4.2.5`／`72`，无 debuggable 标志；ZIP 内原生库架构匹配文件名，`apksigner` 的 v2 签名校验全部通过、三包证书一致。沿用既有 Release 配置，在缺正式凭证时使用其 debug 签名回退。

各包 SHA-256、证书、源码及日志身份见 [Release 验证记录](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/evidence/release-4.2.5-verification.json)，原始构建输出见 [Release 日志](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/evidence/release-4.2.5-build.log)。版本已递增、Release 已生成；打包阶段未安装设备，后续安装见下节。原暂存内容保持不变，未提交。

## 用户追加：安装到电视（2026-11-02 21:49）

用户要求“安装到 tv”。通过本任务独立 ADB 端口 **5089** 连接 `192.168.31.70:5555`，现场确认处于 `device` 状态，设备为 Hisense／VIDAA_TV／MT9653，Android 11／API 30，支持 `arm64-v8a`。

对上述已核验的 ARM64 Release 执行 `adb install -r`，返回 **Success**。安装前后包信息读回如下：

| 项目 | 安装前 | 安装后 |
| --- | --- | --- |
| 包名 | `com.okamihoro.ddplaytv` | 相同 |
| 版本 | 4.2.4／71 | **4.2.5／72** |
| 最近更新时间（电视读回） | 2026-11-01 21:21:47 | **2026-11-02 21:49:17** |
| UID | 10111 | 10111 |
| 首次安装时间 | 2026-02-12 10:47:55 | 相同 |

电视当前 `base.apk` 与本地 ARM64 产物 SHA-256 均为 `356a00281cde0e0d7defed663b905aa978c43c2e0e44464f90460eb7e0517472`。此次为保留数据的覆盖安装，没有卸载或清除数据；未启动应用、导航、播放或截图，物理电视视觉／运行及用户验收仍待完成。没有重跑构建或修改源码。

[安装核验记录](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/evidence/tv-install-4.2.5-verification.json) 保存设备身份、安装前后信息、双端 APK 哈希及日志身份；原始 [安装输出](../../../document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/evidence/tv-install-4.2.5-install.log) 和前后包信息位于同目录。已断开电视并关闭私有 ADB 5089；默认 5037 进程 PID 57396 保持原样，原暂存 blob 身份未变，未提交。
