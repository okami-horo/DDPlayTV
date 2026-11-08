# 防御性编码问题修复与验证

日期：2026-11-08。审计基线：`92c2a8d1f5900b2695dbcfd7e3490566255a422a`；修复保留在当前工作区，未提交。范围是用户确认的六项问题及使修复可用的直接调用链，不包含播放器菜单的并行任务。

## 修复行为

| 问题 | 修复后的行为 | 主要位置 |
| --- | --- | --- |
| 百度刷新令牌异常清空授权 | 网络、解析、取消和一般 OAuth 错误保留授权；仅 `invalid_grant` / `expired_token` 清除失效授权；取消直接传播 | `BaiduPanTokenManager` |
| 扫描失败被视为空快照 | 缺失/不可读目录、枚举失败和 MediaStore 空游标使本次刷新失败，在数据库清理前停止；成功空目录仍允许清理缺失索引 | `VideoScan`、`MediaResolver` → `VideoStorage` |
| 凭据失败删除旧值 | 解密失败保留密文并报告不可用；加密/持久化成功后才替换旧值；数据库旧密码仅在迁移成功后清除；编辑保存失败保留原记录 | `MediaLibraryCredentialStore/Resolver/Migration`、`StoragePlusViewModel` |
| 弹幕重试未执行 | 保留 `Result` 中的失败原因，识别包装后的连接/读取超时，最多尝试三次，退避 1/2 秒；取消不重试 | `NetworkDanmuSource` |
| 三种直链缓存吞取消 | 只有可恢复网络故障允许回退旧缓存；取消、授权、证书及编程错误向上传递；公共请求层也传播取消 | 百度、115 Open、115 Cloud 的直链缓存及 `Request` |
| 父目录失败缓存成根目录 | 查询失败/缺失父节点不再伪造根目录；删除派生目录链缓存，从真实父关系生成路径；父关系更新立即生效；搜索显示失败而非错误路径 | `Open115FolderInfoCache`、`Open115Storage.search` |

凭据调用链还修复了两个直接相关的入口：Keystore 读取/加载失败不会触发覆盖旧密钥的 128 位生成回退；浏览、编辑和测试连接接住凭据不可用错误。浏览页初始化失败时跳过视图及历史 Fragment 恢复，避免访问未初始化的存储对象。256 位新密钥创建不受支持时仍保留 128 位兼容回退。

百度错误码分类参考[百度 OAuth 错误码说明](https://openauth.baidu.com/doc/appendix.html)。本地扫描保护的是视频索引及该行的过滤属性，不涉及删除媒体文件或清空独立播放历史表。显式清空密码仍然是允许的用户操作；不新增明文凭据保存回退。

## 回归证据

新增 34 个 JVM 用例覆盖取消传播、缓存降级边界、实际三次重试、授权保留与恢复、成功/失败扫描的真实 Room 数据变更、凭据读取/加密/写入失败、旧密码迁移、Keystore 故障及恢复、编辑保存失败、父目录重试和更新。另新增模拟器凭据恢复测试，覆盖浏览/编辑失败退出、密文保留和恢复编辑。

- 修复前红灯：`red-network.log`、`red-state-compiled.log`、`red-keystore-save.log`。状态类基线 12 个测试中 11 个断言失败；Keystore 两个故障断言失败；编辑保存失败复现数据库密码被清空。
- 测试夹具修正：Retrofit suspend 假服务通过 Continuation 返回异常，避免 Java Proxy 包装 checked exception；取消断言验证类型/消息，允许协程栈恢复复制异常；MMKV 内存实现清空已有容器，避免单例引用在测试间残留。
- 全仓 `testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest lint verifyModuleDependencies`：日志 `full-validation.log`，实际尾部为 **BUILD SUCCESSFUL in 8m 27s**。JUnit XML 合计 **570 个测试：568 通过、2 个既有跳过、0 失败/错误**；包含本次新增 34 个用例。两个跳过来自 `PlaybackTransportProtocolIntegrationTest`。
- 浏览页生命周期保护补齐后，重跑 `:storage_component:testDebugUnitTest`（24 个全部通过）、Debug 应用/测试 APK 构建、该模块 `lintDebug` 和本任务文件范围 ktlint：`final-validation.log`，**BUILD SUCCESSFUL in 1m 42s**。全仓 lint 本次运行的是 Debug 检查，不代表额外 Release 检查。
- 模拟器 `Pixel_5_API_34` / Android 14、任务端口 5582、独立 adb 5097、1440×810 / 240dpi：**8 个不同仪器用例最终均有通过结果**。其中凭据恢复 1 个、存储故障恢复 1 个、全局设置 5 个在 `device-final.log` 通过；主导航 1 个在 `device-main-navigation.log` 重跑通过。
- 设备红灯没有隐藏：生命周期保护前的安装包在浏览凭据不可用时出现 `UninitializedPropertyAccessException`（`device-credential-before-guard.log`），最终包上对应测试通过。首次设备批次为 7/8 通过，剩余主导航测试仍断言已退休的 `PersonalFragment` / `tag_fragment_tv_personal`。确认 HEAD 原本就使用 `CinemaSettingsFragment` / `tag_fragment_tv_settings` 后，仅修正该断言及失效 import；测试 APK 重建 **BUILD SUCCESSFUL in 52s**，该用例 **OK (1 test)**，追加范围 ktlint **BUILD SUCCESSFUL in 27s**。没有为了旧断言修改产品导航。
- 最终生产 APK 另做启动 → 媒体库 → 添加来源弹窗 → BACK 返回 → 退出的 DPAD 冒烟，实际查看首页、媒体库和弹窗截图，确认焦点可见；退出后 `topResumedActivity` 为系统 Launcher。首次直接启动非 exported 的 TvMainActivity 被系统拒绝，改用 manifest 中 exported 的 TvSplashActivity；首次本地入口出现权限框，按 BACK 退出，没有把该次操作当作本地目录读取成功。文件浏览的空结果、失败保留内容、刷新与恢复由上述 WebDAV 仪器用例验证。

验证使用项目 `scripts/dev-env/gradlew-local.bat`，进程级 JVM temp/Unix-domain 路径指向 `.tmp/wh-jvm`，Robolectric 使用现有离线依赖初始化脚本；未改动构建环境配置。初始增量编译缓存错误通过禁用该次运行的 Kotlin 增量编译恢复。所有成功声明均已核对 Gradle 日志尾部及测试 XML/仪器测试输出。

任务模拟器与独立 adb 已关闭；2026-11-08 12:57:30 +08:00 核验任务进程数及 5582/5583/5097 监听数均为 0（`cleanup.json`）。

日志、红灯 XML、任务源码哈希（27 个 Kotlin 文件）及设备证据目录：`.tmp/defensive-fixes-2026-11-08/`。这些是本机验证产物，不作为版本控制中的测试来源；正式回归测试位于对应模块 `src/test` 和 `app/src/androidTest`。

## 验证边界

云盘错误通过假服务注入，未使用真实百度/115 账户进行线上故障测试。Keystore 加载/读取失败通过 JVM 测试提供者注入，设备测试使用真实 Android Keystore 和不可解密密文。模拟器验证不等同于物理电视验收。
