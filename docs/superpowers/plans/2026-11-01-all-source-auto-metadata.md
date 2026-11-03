# 全媒体库自动识别与 PC 端来源退役

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

用户确认：所有仍支持的媒体库来源都必须适配播放触发自动识别；随后明确移除弹弹play PC 端媒体库。此决定替代此前“仅四类来源、其余延期”的范围。

## 范围与验收

- 覆盖本地、SAF、SMB、WebDAV、FTP、Alist、百度网盘、115 Open、115 Cloud 共九类媒体库。投屏为临时播放入口，播放历史为聚合入口，不作为独立可扫描的媒体库。
- 播放成功后识别当前文件，同目录发现同剧视频；云盘必须遍历分页，读取失败不得当作空目录或完整扫描。身份、绑定、观看进度和独立播放连接保持兼容。
- 移除 PC 来源新增/编辑入口、实现和专用设置。旧来源枚举/数据库字段仅作兼容，旧记录保留在数据库并从可用来源/历史列表隐藏；旧待恢复播放请求不再启动该来源。不删除其他来源或绑定/观看记录。

## 实施顺序

1. 补充来源/分页/失败/身份回归测试，确认原实现无法覆盖新来源。
2. 接通九类来源的独立元数据读取、分页和根目录处理，保留真实文件名与历史身份。
3. 退役 PC 端入口、实现及设置，补充兼容检查；不恢复已退役能力。
4. 执行针对性测试、完整 JVM、构建、lint 和架构门禁；在任务 AVD 验证来源菜单、设置与播放返回路径。实际账户/服务结果与本地夹具证据分开记录。

## 当前状态：已验收归档（2026-11-01）

九类来源适配、PC 来源退役及后续排障已完成。正式版同版本升级的路由缓存、资料 API 连接和省略副标题匹配问题修复后，Release `4.2.3`/`70` 于 `12:59:24` 覆盖安装电视。用户随后明确反馈“刮削成功了，实际验证通过，可以归档收尾了”，据此关闭本次自动刮削任务及电视验收待办。

验收对应本次 VIDAA TV、115 Cloud 来源和 `乱世千金倪亚·利斯顿 - S01E01.mp4` 场景；本地真实 API 测试另已核验 TMDB `312850` / `S01E01` 入库。用户确认属于实际设备验收证据，不表述为 agent 重新操作电视或采集了成功后的新日志。其他来源的完整真实账户/错误矩阵、样本总体准确率、既有 M115Crypto 随机测试及 Release lint 通知权限问题继续单独记录，不阻止本次已确认问题归档。源码仍为 `cb0c27dff` 加未提交变更；本次收尾只更新文档，保留原始证据与暂存状态。

## 来源实现与局部验证

| 来源 | 读取策略与证据 |
| --- | --- |
| 本地、SAF、SMB、WebDAV | 保留已有实现；九类来源统一识别测试覆盖其编排入口，本地不递归目录和已有独立连接测试继续通过 |
| FTP | 使用真实 MLST/LIST 文件属性，拒绝不存在文件的浏览占位数据；独立读取连接关闭测试通过 |
| Alist | 显式分页读取，兼容旧版 total 和新版 filtered_total/has_more；根目录文件身份、跨页与后续页失败测试通过 |
| 百度、115 Open、115 Cloud | 独立读取实例遍历 PagedStorage；每类 201 文件跨页、真实文件名/身份、失败和取消测试通过 |

局部编排测试使用电视反馈中的普通文件名模式 `乱世千金倪亚·利斯顿 - S01E01.mp4`，九类来源均完成当前集和同剧文件关联；TMDB 为测试服务，不能视为真实标题命中。云盘分页测试使用真实存储类配合注入页加载器和免账户根目录，不请求实际用户账户；FTP 为协议客户端夹具，Alist 为服务替身。

Alist 参数与兼容字段已核对[官方文件系统 API](https://alistgo.com/guide/api/fs.html)。九来源适配阶段未修改 TMDB 唯一标题/季集核验规则；随后针对真实文件省略副标题的修复见本文后续记录。

## 验证记录

- `.tmp/all-source-metadata/red.log`：针对来源限制/分页的回归，**BUILD FAILED in 52s**，6 个测试中 3 个按预期失败。
- `core-green.log` / `core-green2.log`：最初协议回归失败。先修正测试代理对 Kotlin Result 方法名的处理，再定位 Alist 根路径重建及 Windows JVM 上使用宿主文件分隔符的问题；未放宽身份断言。`alist-path.log` 保存路径差异的复现。
- `core-green3.log`：存储、元数据、数据库、网络和播放器 JVM 测试 **BUILD SUCCESSFUL in 1m 4s**。九类普通名称识别、云盘分页失败/取消、FTP 文件存在性、Alist 根文件身份、PC 旧数据保留和恢复请求拒绝均通过。
- `format.log`：39 个本轮 Kotlin 文件的定向格式化 **BUILD SUCCESSFUL in 24s**；未全仓改写格式。
- `full-gates.log`：Debug/test APK、完整 JVM、lint、模块依赖/播放边界检查及 39 文件 scoped ktlint **BUILD SUCCESSFUL in 9m 13s**。按当前 `settings.gradle.kts` 收集 118 份 JVM XML：**497 通过、2 个既有外部协议跳过、0 失败/错误**，详见 `jvm-results.json`。使用既有离线依赖、Robolectric 和 Windows 短临时目录 init 脚本；不是全仓格式检查通过。
- 本轮源文件状态保存在 `source-manifest.json`：基线完整提交号、41 个修改/新增源文件的 SHA-256 和 15 个删除文件。测试后未改变产品代码。
- 任务 AVD 为 `emulator-5566`（API 34，1440×810/240dpi，只读实例），已确认 `device` 和启动完成。直接 AndroidJUnitRunner 的 `device.log` 中，6 个来源/设置用例通过，6 个播放用例因缺少视频夹具而失败。覆盖 PC 来源隐藏但旧行保留、分组设置消失、保留设置 DPAD 持久化、来源表单焦点/返回和 WebDAV/SAF 入口。
- `device-playback.log` 在补齐 20 秒 H264/AAC 视频后暴露 `READ_MEDIA_VIDEO` 未授权。`device-playback-ready.log` 两项 mpv 用例通过后，执行中的权限调整触发系统杀进程；日志明确为 `MANAGE_EXTERNAL_STORAGE changed`，崩溃缓冲区无应用崩溃。随后固定权限，不改变源码或断言。
- `device-playback-final.log`：**OK (6 tests)**，51.279 秒。Media3/mpv 实际播放后自动识别、同目录发现、首页更新、识别网络失败后重试，以及返回/进度恢复全部通过。合并各轮覆盖 **12 个不同设备用例**，不表述为单轮 12/12，也不表述为 Gradle `connectedDebugAndroidTest` 通过。
- 已实际查看 `native/settings.png`、`native/source-FTP_SERVER.png`、`playback-native/TYPE_EXO_PLAYER-home.png` 和 `playback-native/TYPE_MPV_PLAYER-recognized.png`：分组设置消失、保留控件/返回焦点可见、首页作品及识别操作存在。AVD 使用本地真实视频与 TMDB 替身/明确 ID；不能据此宣称真实网盘标题匹配或完整海报状态已验收。

完整门禁命令：

```powershell
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/all-source-metadata/scoped-format.gradle --offline --console=plain
```

真实账户、完整服务/错误矩阵及物理电视播放验收仍需独立记录。保留日志和截图，不用安装成功替代这些验收。

## Release 交付

- `scripts/dev-env/gradlew-local.bat :app:assembleRelease --offline --console=plain` 的 `release.log` 以 **BUILD SUCCESSFUL in 1m 36s** 结束。版本仍为 `4.2.3`/code `70`，本轮没有修改版本号。
- arm64 APK：`app/build/outputs/apk/release/ddplaytv_v4.2.3_arm64-v8a-release.apk`，77,520,663 字节；SHA-256 `7f4b3591ead56b367486a9424b0915c0ad1861c9f0c1b755e7511b34a94d0c8a`。`aapt` 确认生产包名、minSdk 25、targetSdk 35、arm64 且无 debuggable 标记，见 `release-artifact.json` / `release-badging.log`。
- 使用独立 ADB 5047，`adb install -r` 到 VIDAA TV `192.168.31.70:5555` 返回 **Success**。回读版本、ABI 和更新时间 `2026-11-01 12:17:32`；首次安装时间保持 `2026-02-12 10:47:55`，应用数据保留。电视 `base.apk` SHA-256 与上述产物完全一致，见 `tv-install.log` / `tv-readback.log` / `tv-device.sha256`。
- 本轮没有启动电视应用、导航或播放。任务 AVD `emulator-5566` 和独立 ADB 5047 已关闭，用户默认 ADB 服务未重启。日志、截图及来源状态清单保留在 `.tmp/all-source-metadata/`。

## 正式版升级后没有识别任务（2026-11-01）

用户在新包中再次播放后仍无反应，并确认“资料与任务”没有任何任务。direct-http 采集成功得到分段索引、最新分段及归档，无失败端点，位置 `.tmp/metadata-no-response/20261101-122143/`。日志确认 `12:19:43` 与 `12:20:12` 两次 `CLOUD_115_STORAGE` 播放。旧实现没有识别提交日志，因此日志本身不能证明任务已经启动。

本地 ARouter 1.5.2 实际字节码显示：Release 仅按 versionName/versionCode 判断是否重建服务清单，Debug 则强制重新扫描。自动识别新增了 `core_metadata_component` 的服务注册类，而连续交付均为 `4.2.3`/`70`；沿用旧清单时，`PlaybackMetadataService` 查找返回 null，安全调用直接跳过提交。该路径解释了“视频能播，但完全没有任务”，此前全新 Debug 夹具没有覆盖升级条件。电视私有缓存尚未直接读取，证据为用户状态、交付历史与同条件正式包复现。

- `RouterUpgradeInstrumentedTest` 只在显式 `routerUpgradeProbe=true` 时运行；两个方法必须分进程执行。先构造同版本旧服务清单，再冷启动检查新服务。未修复 Release 的 `device-red.log` 明确失败于 provider 为 null；覆盖安装修复包后 `device-green.log` **OK (1 test)**，独立偏好哨兵保留，且 ARouter 仍处于正常 Release 模式。
- 修复仅按应用 `lastUpdateTime` 在初始化前失效 ARouter 的服务清单与版本缓存，账号、媒体库、绑定和观看数据不清理；首次迁移也会刷新旧清单。播放器提交点补充无敏感路径/凭据的成功与服务缺失日志，避免继续静默跳过。
- 修复包构建 `fixed-release-build.log` **BUILD SUCCESSFUL in 1m 6s**。同一 Release 安装上的 `release-playback.log` **OK (6 tests)**，41.363 秒：实际 Media3/mpv 播放后创建任务、同剧发现、首页更新、网络失败重试及返回/续播均通过，TMDB 仍为受控替身。定向三文件格式化 `format.log` **BUILD SUCCESSFUL in 8s**；没有语义变化。
- 电脑端对两部标题的官方 TMDB 只读核对均连接超时（`tmdb-title-check.json`）；这不能证明电视连接失败或标题不存在。真实标题结果仍待电视新任务确认。

上述日志均位于 `.tmp/router-upgrade/`（另行注明的采集目录除外）。最终检查与交付：

- `device-cached-restart.log` 再次冷启动 **OK (1 test)**，确认修复后的缓存仍能解析服务。该结果复用同一个用例，不增加不同用例计数。
- 开启 Release 仪器测试的额外全量命令在 `:app:lintRelease` **BUILD FAILED in 4m 19s**：既有 `ScreencastReceiverNotifier` 的 `NotificationPermission` 错误，Release manifest 缺少 `POST_NOTIFICATIONS`。该通知实现和 app manifest 在本轮及九来源改动中均未修改；未抑制该错误、未声称 Release lint 通过。
- 恢复仓库常规构建配置后，Release APK、完整 JVM、默认 lint、依赖/播放边界及三文件 scoped ktlint 的 `standard-gates.log` **BUILD SUCCESSFUL in 19s**。118 份 JVM XML 共 497 通过、2 个既有外部协议跳过、0 失败/错误。额外 Release lint 缺口独立保留。
- 修复 APK 仍为 `4.2.3`/`70`，77,520,663 字节；SHA-256 `e557c4844e2eab6227b96722fc16bf24d65335fca88cd7eb9e76f8672e9e73dd`。三份本轮 Kotlin 源文件及 APK 指纹保存于 `artifacts.json`；此前九来源的 56 项源文件清单仍匹配。
- 电视 `adb install -r` 返回 **Success**；回读更新时间 `2026-11-01 12:35:27`，首次安装时间不变，且电视 APK 哈希与修复产物一致。见 `tv-install.log`、`tv-readback.log`、`tv-device.sha256`。未代替用户打开或播放电视应用；已请求用户正常重播并检查任务结果。

## 真实资料连接与省略副标题（2026-11-01）

用户随后反馈“资料服务暂时不可访问”。direct-http 采集的 `.tmp/router-upgrade/tv-after/20261101-123727/latest-segment.ndjson` 确认 `12:36:46` 路由缓存刷新，`12:37:07` 当前 `CLOUD_115_STORAGE` 文件已提交识别（library=5）。因此“没有提交任务”的路由缺陷已得到电视运行证据，不能继续将资料访问失败归因于未触发。

当前文件为 `乱世千金倪亚·利斯顿 - S01E01.mp4`，明确包含季号。先前将另一文件的缺季号情况混入说明是不正确的；本轮不修改季集解析算法，也不为另一部作品增加缺季号推断。

连接检查中，原 `api.themoviedb.org` 在电脑解析到 `66.220.149.32` 并 HTTPS 超时；电视解析为 `88.191.249.182`，ping 不通。`api.tmdb.org` 的实际 API 请求返回 HTTP 200，电视解析 `52.85.151.110` 可 ping 通。DNS/ping 本身不能替代电视内 HTTPS 验证。客户端固定域名及 Authorization host guard 一并改为 `https://api.tmdb.org/3/`，仍禁用重定向，没有代理、TLS 降级或系统 DNS 改动。该域名的根地址跳转至 [TMDB 官方文档](https://api.tmdb.org)，[tinyMediaManager 官方变更记录](https://www.tinymediamanager.org/changelog/)也记录该 API 域名。

真实搜索只返回作品 `312850`，中文正式标题为“乱世千金倪亚·利斯顿转生为娇弱千金的弑神武人华丽无双录”，第一季第一集存在。官方别名列表没有该中文简称，不能称为别名精确匹配。规则 v3 增加有限的长剧名前缀匹配，条件与拒绝范围见[命名与重试](../../../document/media-library/tmdb-metadata-followup.md#命名与重试)。有效旧绑定不变，旧失败记录可随新规则重新尝试；季集解析算法不变。

证据位于 `.tmp/tmdb-connectivity/`：

- `title-check.json`、`details.json`：真实搜索、正式标题、别名及季度结果，不包含访问令牌。
- `title-red.log`：新增真实片名回归在旧匹配规则下 **BUILD FAILED in 15s**，7 项中 1 项失败。修改后 `title-green.log` 的元数据/网络模块 JVM **BUILD SUCCESSFUL in 36s**；覆盖当前季集保留、多个候选、短片段、非开头片段、错误年份、电影及数字后缀拒绝。
- 最初测试直接引用网络模块导致 `build.log` 编译失败；改为已有 `MetadataCoordinator.repository`，未增加模块依赖。`build2.log` **BUILD SUCCESSFUL in 19s**，早期 `live-service.log` 查询用例通过。
- 扩展后的 `live-binding-build.log`：Release 与 Release test APK **BUILD SUCCESSFUL in 40s**。`TmdbConnectivityInstrumentedTest` 仅在显式 `tmdbLiveCheck=true` 时运行，使用生产 repository 与真实 API，不注入替身。测试建立当前文件名快照，执行自动识别，断言状态 `MATCHED`、作品 ID `312850` 和已保存的 `(season=1, episode=1)`，finally 删除测试来源及绑定。
- 初次 `live-binding.log` 命中之前的 HTTP 缓存；原始状态确认实际通过、不是跳过。随后仅清空任务模拟器的应用数据，`live-binding-uncached.log` **OK (1 test)**、2.407 秒，原始状态码 0，证明无缓存的真实请求和识别入库通过。多次执行同一用例只计一个不同用例；当前文件快照仍是测试输入，不是实际读取用户 115 文件或播放电视视频。
- 常规完整门禁 `standard-gates.log` **BUILD FAILED in 54s**：既有 `M115CryptoTest` 随机生成空 payload，触发 `Invalid payload size=16`。失败 XML 保存为 `crypto-failure.xml`，该云盘加密实现与测试未修改。此前额外 Release lint 的通知权限错误也保持开放。
- 不改变源码或断言的后续完整命令 `standard-gates2.log` **BUILD SUCCESSFUL in 2m 22s**：Release、完整 JVM、默认 Debug lint、模块依赖/播放边界和六文件 scoped ktlint 通过。118 份 XML 为 **498 通过、2 个既有跳过、0 失败/错误**；随机测试缺陷没有因重跑通过而关闭。`evidence.json` 保存六份 Kotlin 源文件、两个 APK 的 SHA-256 和 JVM 结果；三份路由修复源码及原九来源 56 项清单仍一致。

当前 Release 与测试构建命令：

```powershell
scripts/dev-env/gradlew-local.bat :app:assembleRelease :app:assembleReleaseAndroidTest -I .tmp/router-upgrade/release-tests.gradle --offline --console=plain
scripts/dev-env/gradlew-local.bat :app:assembleRelease testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck -I .tmp/tmdb-connectivity/scoped-format.gradle -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle --offline --console=plain
```

正式包交付：`adb install -r` 返回 **Success**；版本保持 `4.2.3`/`70`，arm64 APK 77,520,661 字节，SHA-256 `0a7eaf58c99baa1a688b1ab0c9555270918b34c8c4535782a1b298f56f0addc6`。电视回读确认 `lastUpdateTime=2026-11-01 12:59:24`、首次安装时间不变、无 DEBUGGABLE 标记，设备 APK 哈希与产物相同（`tv-install.log`、`tv-readback.log`、`tv-device.sha256`）。账号、媒体库和历史数据保留。agent 未操作电视启动/导航/播放；用户随后确认刮削成功、实际验证通过，验收范围见本文归档状态。

`13:00:59` 再次 direct-http 采集无法连接电视 `17010` 日志服务，只有采集元信息写入 `tv-after/20261101-130059/collection-info.txt`，未取得新分段或归档。此结果是日志入口不可达，不能当作新包的 TMDB 请求失败。任务模拟器已确认退出，独立 ADB 5047 已停止；默认 ADB 未重启。用户暂存内容未修改，未提交 Git。
