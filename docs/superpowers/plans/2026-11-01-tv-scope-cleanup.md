# TV 冗余清理 A01–A08（2026-11-01）

用户已授权清理[审查报告](../../../document/architecture/tv-feature-scope-audit-2026-11-01.md)中的全部八类候选。实施基线为 `d0ee40e29`，开始时工作区干净。本任务不包含 B01–B03 或账户、横幅等产品设计取舍。

## 范围与验证

保留真实播放会话、Media3/mpv、投屏接收、九类存储、字幕/弹幕播放和普通缓存；保留历史数据兼容字段、旧迁移、启动 alias 及字幕编码依赖。数据库升级到 22，仅通过 21→22 迁移删除两张退役 Media3 表，不清除用户库、历史和元数据。

| 项目 | 实施结果 | 验证 |
| --- | --- | --- |
| A01 无消费者 Media3 下载/Cast/session 依赖及辅助代码 | 已删除三个依赖、Cast 管理器和孤立映射/回退实现及专属测试 | 三份 Release APK/Manifest 核查通过；最终依赖树与综合门禁见下文 |
| A02 无消费者绑定服务及专属契约/转发 | 已删除绑定生命周期、服务、路由和专属契约；测试直接调用现有 PlaybackRequestService | 实际双内核播放、状态/命令、投屏接收与版本隔离回归通过 |
| A03 迁移期灰度/遥测/下载校验与两张表 | 已删除旧模型/DAO/灰度/启动标签/占位 API，新增显式迁移和 schema 22 | 14 项迁移门禁通过，含 PC 旧行/凭据、历史、绑定和元数据保护 |
| A04 无入口的弹幕发送链路 | 已删除上传、XML 追加、发送模型/控件接口/专属图标；加载状态使用准确命名的 getter 供原有解析回归读取 | 双内核弹幕切换/重建回归通过，下载/播放/屏蔽保留 |
| A05 孤立 panel_switch 模块 | 已删除模块注册、包装 AAR、许可和治理例外，更新自动快照；清除旧遥测测试留下的 network→contract 测试依赖 | 显式模块 22→21；该模块原先未进入 APK，治理结果见下文 |
| A06 扫描/电量/下载与旧配置残留 | 已删除扫描控件、电量链路、专属属性/资源、DownloadConfigTable、FTP 提示与 Jsoup UA 配置 | 生产残余引用为零，退役类型未进入 Release DEX |
| A07 停用的模块入口和备用 Manifest | 已删除四套入口/ViewModel/layout、五份备用 Manifest，以及独占的 launcher 图标和未使用 app_name；保留云盘登录实际使用的字符串 | 主 shell 冷启动、两种 launcher 目标及 DPAD 回归通过；旧组件 alias 保留 |
| A08 Jsoup 与旧 MultiDex 安装兼容层 | 已删除 storage/local 两处 Jsoup 依赖、常量/许可与 MultiDex.install 兼容层；保留 multiDexEnabled=true | Release 保留 4 个 DEX，退役库类型与许可不再打包；启动回归通过 |

执行顺序：先清理相互依赖的 A01–A03，并完成显式数据库迁移；再清理 A04–A08 的专属实现、资源和治理记录；最后执行 Debug/Release 构建、JVM、lint、播放边界、依赖/Manifest 核查以及任务模拟器上的 DPAD 冒烟。无需操作用户实体电视，不提交代码。

## 执行与验证记录

八类清理、数据库升级保护、构建/静态检查及本地模拟器回归均已完成。历史审查结果不能代替本次清理后的证据；当前结果不代表实体电视、真实云账号或全部异常场景的验收。

最终综合命令（仓库根目录）：

```powershell
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/play-history-short-temp.gradle -I .tmp/tv-scope-cleanup/scoped-format.gradle --offline --console=plain --continue --no-daemon --max-workers=2
```

- `verification-final2.log`：尾部明确为 **`BUILD SUCCESSFUL in 20m 48s`**，1823 个任务（1583 executed、240 up-to-date）。Debug/测试 APK、Release、全部 JVM、标准 lint、模块依赖治理、播放边界及限定范围的 ktlint 均完成。app Debug lint 报告为 0 error、24 warning；第三方 mpv 的 lint 分析仍输出 Kotlin 2.4 元数据兼容诊断，但任务及最终构建未失败，没有修改第三方 AAR/编译器兼容策略。
- `release-dependencies.log`：`scripts/dev-env/gradlew-local.bat :app:dependencies --configuration releaseRuntimeClasspath -I .tmp/ui-repairs-local.gradle --offline --console=plain --no-daemon --max-workers=2`，尾部 **`BUILD SUCCESSFUL in 30s`**。无解析 FAILED，也无 WorkManager、Media3 Cast/session、Play services Cast、Jsoup 或 MultiDex 兼容库；Media3 UI、字幕编码和保留的包装依赖仍在，见 `dependency-results.json`。

- `database-gate.log`：首轮 14 项 Room 迁移测试通过，尾部 `BUILD SUCCESSFUL in 58s`。21→22 使用空库/有数据两组，比较全部保留表的行、表/索引 SQL 和自增序列；新建库也不再包含退役表。之后补入 PC 旧库行/历史和凭据夹具，最终 JVM XML 中这 14 项再次通过。
- 生成 schema 22 与 schema 21 对比：仅删除两张退役表，其他 15 个 entity 定义逐项相同。全部历史 schema 和旧迁移链保留。
- `verification.log`：首轮综合检查最终 `BUILD FAILED in 10m 8s`，三个失败任务为仪器测试编译和两处 ktlint 任务。测试此前通过发送接口间接读取弹幕加载状态，改用加载状态 getter 修正；本次新增迁移测试的三处多行表达式格式已修正。该轮全部 JVM XML（含 mpv 包装模块）汇总为 487 通过、2 跳过、0 失败；这不是最终状态验证。
- 首轮 ktlint 的另一处是 `ResourceRepository.kt:19:8` 既有 `matchDanmu` 表达式格式，相关方法与 `d0ee40e29` 完全相同，见 `baseline-format-evidence.txt`。保留旧格式，不顺带改写。最终 scoped ktlint 使用 28 项路径白名单并排除此文件；buildSrc 本身未配置 ktlint 任务，其两处纯删除变更由编译与差异核对验证。其余列入检查的项目变更通过，不宣称全仓 ktlint 通过。
- `verification-final.log`：复验在 Gradle 守护进程意外消失时中断，未给出崩溃原因，没有最终成功结果。改为 `--no-daemon --max-workers=2` 的单次进程继续验证，未修改工程运行参数。
- `jvm-final.json`：最终单元测试 XML 汇总 113 个 suite、487 通过、2 项既有外部协议夹具跳过、0 失败/错误。覆盖补入 PC 数据后的迁移门禁。
- `device-final.log`：首次有效仪器批次为 16 项、9 项失败。通过有界复现、7 秒/12 秒窗口转储和实际查看 `window-7000.png`，确认首次全屏的系统 `ImmersiveModeConfirmation` 引导占用焦点；播放器已实际渲染并进入 PLAYING。只在本任务模拟器设置 `secure immersive_mode_confirmations=confirmed`，没有修改应用代码、测试断言或等待时限。
- `device-confirmed.log`：环境修正后同一批次 `OK (16 tests)`，77.295 秒，每项终态均为 0，见 `device-results.json`。覆盖真实 Media3/mpv 播放、状态/命令、空闲及播放中投屏接收、弹幕切换/重建、存储/历史/动漫入口、返回同文件续播、设置持久化和 DPAD 面板。PowerShell 宿主因 `s_glBindAttribLocation` 渲染器 stderr 标成 `NativeCommandError` 并返回 1；已逐项核对 runner 完整输出，不把宿主退出码当成测试结果。网络/云账号覆盖不能由这些本地夹具推断。
- 已拉取并实际查看 Media3 libass 字幕/弹幕、mpv 原生字幕和设置截图。额外从 `TvSplashActivity` 执行冷启动，`am start -W` 返回 `Status: ok` 并进入有窗口焦点的 `TvMainActivity`；执行 DPAD_DOWN/BACK 后仍正常，见 `launcher-smoke.txt/.png`。
- `release-artifacts.json`：三份 Release APK 的 DEX 哈希一致，保留 4 个 DEX；核查删除源码中的 60 个类型及 WorkManager/Cast/session/Jsoup/MultiDex 代表类型均不存在。三份合并 Manifest 无退役初始化器/服务/Activity，保留投屏接收、TV 入口和旧 alias；保留字幕 UI、真实活动会话、弹幕 View、arm64 mpv/libass 与普通缓存。许可清单同步移除两份退役依赖。
- 生产符号扫描未发现本次退役服务、灰度/遥测、发送、电量、扫描或配置的残余引用。实际播放会话/请求服务、投屏接收、耳机/关屏广播与 DPAD 预览实现与基线一致。共享播放枚举及其兼容值保留；旧表名只用于历史 schema 和显式升级迁移/回归。
- `final-source-manifest.json` 标识验证源码：39 个现存实现/配置/资源与 125 项删除。最终哈希与删除状态复核一致，后续只同步文档；`git diff --check` 通过。模块快照由原脚本生成，当前说明和 memory 已同步。原审查证据中的已删除路径属于历史记录。
- 验证证据存放于本机 `.tmp/tv-scope-cleanup/`。模拟器为独立 `DDPlayTVCleanup` / `emulator-5568` / ADB 5051，只操作该实例；120 秒 H.264/AAC 播放夹具和文件浏览夹具已推送。回归后通过定向 `emu kill` 关闭该实例，并关闭专用 ADB 5051；未操作其他实例、默认 ADB 或实体电视。未提交代码。

## 产物与剩余边界

本地产物版本仍为 `4.2.3` / code `70`。arm64：`app/build/outputs/apk/release/ddplaytv_v4.2.3_arm64-v8a-release.apk`，68,380,642 字节，SHA-256 为 `2c848c2ce195c637abf1318613999eb55357ae640fafd54c7fb616e9b554ca86`；另外两份 ABI/universal 身份见 `release-artifacts.json`。本次未安装到实体电视，先前 12:59:24 交付仍是电视上的最后已记录版本；相同版本号不表示同一工件。

B01–B03、账户/横幅设计取舍、真实协议/账号与物理电视验收均未扩入任务。既有全仓格式问题、额外 Release lint 的 NotificationPermission 历史问题与其他工程待办未在本次修复；标准 lint 通过不表示额外 Release lint 已重新验证。
