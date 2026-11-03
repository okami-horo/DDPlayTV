# 统一剧集队列实施与验证

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-11-01。设计依据：[统一剧集播放队列](../designs/2026-11-01-series-playback-queue-design.md)。

用户已授权完善设计并落地能力，确认整剧跨季、当前来源优先且缺少时提示选择其他版本。实施基线 `aa7bfade8`，本任务改动未提交；工作区并行存在其他圆角/图片修复，保留其内容，不纳入本任务完成范围。

| 阶段 | 结果与验证 | 状态 |
| --- | --- | --- |
| SQ-A | 固化领域关系、队列/版本/恢复契约及 Jellyfin/Plezy/Kodi 映射；旧详情设计链接到后续范围 | 已完成 |
| SQ-B | 真实详情用例先失败（预期 12、实际 1），同一用例实施后通过；新增 20 项契约、构建器、解析和会话/恢复单测 | 已完成 |
| SQ-C | `MetadataPlaybackRequests` 统一事务读取 Room；详情保留季集锚点，详情/首页/历史页统一转换 | 已实施并本地验证 |
| SQ-D | series 描述、合并文件投影、版本选择、会话修订检查与完整加密快照；复用活动会话和双内核 | 已实施并本地验证 |
| SQ-E | Debug/test 构建、全 JVM、lint、依赖治理、20 个改动 Kotlin 文件的格式检查 | 已通过 |
| SQ-F | API 34 AVD 上 20 项详情/队列检查、7 项兼容回归；查看截图并同步文档和 memory | 已通过；真实账号/物理电视验收另计 |

## 证据与边界

### 已执行

- `.tmp/series-queue/red-build.log`：基线加回归断言的 Debug/test 构建，`BUILD SUCCESSFUL in 9s`。
- `.tmp/series-queue/red-test.log`：`WatchHistoryInstrumentedTest#media3HomepageDetailPlaybackAndLayeredBack`，断言失败，预期 12、实际 1，证明原问题。
- 首轮实现编译暴露跨模块 `Long?` 智能转换问题；改为显式非空检查后重建。
- `.tmp/series-queue/implementation-tests.log`：Debug/test APK、contract/metadata/storage JVM 测试，`BUILD SUCCESSFUL in 2m 7s`。
- `.tmp/series-queue/player-tests.log`：播放器全模块 JVM，包括版本选择取消、内部选择不消费外部 pending、陈旧选择、快照写入失败、整剧进程恢复和合并文件投影，`BUILD SUCCESSFUL in 57s`。
- `.tmp/series-queue/green-original.log`：原失败 Media3 详情主路径已通过，`OK (1 test)`。

### 最终构建与 JVM

使用项目本地 Gradle/JDK 包装器和既有离线测试依赖/Windows Room 短临时路径配置：

```powershell
& .tmp/watch-history/gradle.ps1 :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies ktlintCheck --offline -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/series-queue/scoped-format.gradle
```

- `.tmp/series-queue/validation-final.log` 尾部确认 **BUILD SUCCESSFUL in 3m 55s**，1164 个任务；Debug APK、测试 APK、Debug lint、模块依赖治理、全 JVM 和限定改动文件的 ktlint 均通过。没有将全仓库存量格式问题包装为已修复。
- 读取当前 included 模块的 113 份 JUnit XML：507 项，**505 通过、0 失败、2 既有跳过**。跳过来自需要真实 SMB/FTP 夹具的 `PlaybackTransportProtocolIntegrationTest`。
- 新增 20 项行为单测：剧集契约 6、metadata 构建器 5、真实 SMB 解析器回归 5、活动会话/加密恢复 4。覆盖跨目录/跨季、版本、特殊集、合并文件、210/260 集、来源隔离、旧代次、文件变化、准备中失效、快照写入失败、陈旧选择与恢复。
- 最终 Kotlin 输入 SHA-256 存于 `.tmp/series-queue/validated-sources.json`，包含新增文件；单一 Git 基线不代表此未提交工作区。
- 收尾核对：新增的本地文档和截图链接有效，`git diff --check` 通过；20 个 Kotlin 文件与最终验证时的 SHA-256 一致。

### AVD、失败修正与截图

使用独立 API 34 AVD `emulator-5580`、私有 ADB 5061，1920×1080 / 320dpi。仅安装 Debug 及测试包。

验证结束后只关闭本任务的 AVD 5580 和私有 ADB 5061；并行任务的 AVD 5574 保留。

- 首轮完整详情/队列回归：20 项中 18 项通过，双内核自动跨季测试超时。`ffprobe` 确认夹具真实时长 **120 秒**，原测试误按 20 秒从第 12 秒等待结束。改为 `MediaMetadataRetriever` 测量真实时长，并从结束前 8 秒进入；没有修改生产播放逻辑以迁就夹具。
- `.tmp/series-queue/avd-final.log`：`WatchHistoryInstrumentedTest`，**OK (20 tests)**，147.984 秒。包含双内核原详情路径、跨季 DPAD 切集/重建、版本取消/重建/确认并沿用新来源、自然播放自动跨季、特别篇合并文件、电影/未识别文件、缺失/错误提示、返回与焦点。
- 兼容批次首次 7 项因缺少其既有 `/sdcard/Download/ddplaytv-lease-playback-long.mp4` 和 `file-browser-c.mp4` 夹具失败；补齐同一已验证 120 秒视频后，未修改这些兼容测试或生产代码。
- `.tmp/series-queue/avd-compatibility-final.log`：**OK (7 tests)**，42.799 秒。覆盖目录手动/原生续播、双内核历史/原有播放入口、双内核文件浏览返回与续播、投屏发送端队列，以及内核重试不改变全局偏好。
- 合计 **27 项不同 AVD 用例通过**；重复执行不重复计数。未执行完整 `connectedDebugAndroidTest`；使用 AndroidJUnitRunner 直接运行上述明确用例。

实际拉取并查看了以下最终截图，核对队列数量/当前项、可见焦点、季边界、合并文件仅占一项、来源/文件名和取消动作。复用既有选集面板与底部操作弹窗，没有重新设计 A/B/C 页面结构。

| 场景 | 截图 | 观察 |
| --- | --- | --- |
| 常规季跨季队列 | [当前第 12 / 14 项](../../../document/player/series-queue-2026-11-01/cross-season.png) | 第一季末集与第二季首集相邻，当前项和 DPAD 焦点明确 |
| 特别篇合并文件 | [特别篇 2 项](../../../document/player/series-queue-2026-11-01/specials-merged.png) | 前两集共享文件，只占一个条目；下一项为特别篇第 3 集，常规季未混入 |
| mpv 重建后的版本选择 | [选择来源与实际文件](../../../document/player/series-queue-2026-11-01/version-choice.png) | 仅有一个其他来源也要求确认；重建后焦点在候选行，BACK/取消保留原播放 |

### SQ01–SQ09 验收对应

| 编号 | 本地证据 | 状态/限制 |
| --- | --- | --- |
| SQ01 | 原失败用例、双内核详情续播和队列大小/索引断言 | 通过 |
| SQ02 | 双内核 DPAD 跨季；按真实结束时间自动跨季；关闭自动后保留手动选集 | 通过 |
| SQ03 | 解析器同来源优先/歧义/无来源测试；双内核选择、取消、重建及后续沿用来源 | 通过 |
| SQ04 | 契约、会话版本变化投影与特别篇合并文件 AVD | 通过；无分段时间轴，不虚构文件内分集进度 |
| SQ05 | 统一构建器/调用源码、详情续播、历史入口兼容、电影/未识别及目录/投屏回归 | 通过对应本地范围；首页已识别 hero 和识别历史页的每个 UI 组合未逐一重跑 |
| SQ06 | 失效/旧代次/准备中变化、保存失败及旧来源租约断言；详情错误与返回 AVD | 通过对应本地范围；真实云账号断连/权限矩阵未覆盖 |
| SQ07 | 加密描述/快照往返、模拟新进程恢复、旧描述回归；实际 Activity/版本弹窗重建 | 通过；未在 AVD 做系统杀进程后恢复 |
| SQ08 | 常规季/特别篇分组、无绑定/失效过滤、210/260 集未截断测试 | 通过 |
| SQ09 | 双内核 DPAD 主路径、查看过的三张最终截图、文件浏览与分层返回 | AVD 通过；物理电视/用户验收未执行 |

本次实现阶段未安装或操作物理电视，未修改扫描范围、Room schema、全局自动下一集偏好、文件历史归属及已选 A/B/C 页面结构。用户随后要求安装到 TV，当前队列实现与 F01–F07 修复已于 2026-11-01 20:23:29 随 Release 4.2.4 / 71 覆盖安装，源码哈希与已验证状态一致；[交付记录](../../../document/architecture/ui-rounding-audit-2026-11-01.md#修复后的电视-release-安装2026-11-01-2023)保存构建、签名及安装后 APK 哈希。物理电视运行和用户验收仍未执行。
