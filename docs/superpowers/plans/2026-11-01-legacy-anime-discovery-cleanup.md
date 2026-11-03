# 旧番剧搜索与发现页清理

> 验收归档：本文对应的 activeContext 待验证项已按[2026-11-03 用户确认](../../../memory-bank/archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03)关闭。下文旧“待验收／未实机验证”表述保留为原执行时点记录；未实施需求和已知工程问题不因此完成。

日期：2026-11-01。基线：`2e77f4198`。用户看到旧“搜番剧”截图后，授权确认无用即清理。

## 入口核对与范围

AST 调用核对及源码读取确认：旧 `SearchActivity` 唯一生产调用来自 `HomeFragment`，而旧首页自身已没有生产入口。当前 `TvMainActivity` 的首页／媒体库来自 `local_component`，搜索打开 `RouteTable.Local.MetadataSearch`。此前截图由仪器测试直接启动旧 Activity 生成，不代表当前导航可达。

1. 清理旧搜索与其孤立首页链路，验证路由、Manifest、资源和专用依赖没有残留。
2. 将测试迁移至保留页面，验证 Debug／测试 APK、JVM、lint 和限定文件的格式检查。
3. 使用任务 AVD 验证当前搜索、DPAD／BACK、番剧保留页面和设置持久化。

| 范围 | 实施 |
| --- | --- |
| 旧发现链路 | 删除旧首页、每周列表、搜番剧、季度番剧和空的高级搜索页，以及其 ViewModel、布局、路由与注册 |
| 专用实现 | 删除搜索菜单、年份选择器、轮播适配器、相关图标／文案、Banner 依赖及许可证展示文件 |
| 专用数据请求 | 删除首页 Banner、每周／季度番剧和旧名称搜索接口及专用响应模型；删除旧搜索历史仓库包装 |
| 保留能力与数据 | 保留当前媒体库搜索、番剧详情／推荐／标签、收藏／观看记录、弹幕搜索与匹配；数据库实体、DAO、schema 22 和历史行不变 |
| 测试迁移 | 主路径冒烟改从遥控器导航打开当前搜索；网格更新／顶部退出测试使用保留的推荐布局和番剧适配器；移除已退役页面的专属测试，保留其余焦点裁剪检查 |

没有改动当前 TV 主页面或播放器的生产 UI。首页观看足迹 A/B/C 原型属于其他任务，本轮保留并行修改，未实施其原生设计。

## 验证记录

- 源码检查：退役页面、路由、专用模型／接口和 Banner 库无生产引用残留。
- 限定文件格式整理：`format.log` 尾部 **BUILD SUCCESSFUL in 2m 26s**。
- 完整命令：通过 `scripts/dev-env/gradlew-local.bat --offline` 及既有离线测试／短临时目录 init 配置，执行 `:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies :app:ktlintCheck :core_network_component:ktlintCheck :core_contract_component:ktlintCheck`。ktlint 仅检查本轮修改文件。`build.log` 尾部 **BUILD SUCCESSFUL in 10m**，1119 个任务。lint 仍打印第三方 mpv Kotlin metadata 版本诊断，任务最终通过；未宣称无警告或全仓格式通过。
- JVM XML 汇总（包含 `repository/mpv`）：**110 个套件，480 通过、2 个既有外部夹具跳过、0 失败／错误**。退役页面的三组专属测试不再计入。
- AVD 首轮 12 项：11 通过、收藏页清空筛选用例失败。实际查看 `remaining-ui/collection-0-empty.png` 确认 Android 的 “Process system isn't responding” 弹窗遮挡并接收按键；窗口日志也记录系统界面 ANR。系统弹窗消失后，同包、同断言单独复测 **OK (1 test)**，16.687s。合计 **12 个不同用例通过**，没有为此次失败修改生产代码或断言。
- 通过范围：当前首页／媒体库搜索与筛选、DPAD／BACK 和原调用项恢复、网格更新后的焦点保持与顶部退出、三项保留的焦点裁剪检查、收藏／观看记录重建和空／加载／错误状态、详情子页、绑定页、本地历史／字幕页面、全局设置持久化。实际查看当前搜索弹窗和复测后的收藏空状态截图。
- APK 内容检查：旧页面／年份滚轮／Banner 类及三个旧路由均不在 DEX 中，合并 Manifest 仅保留番剧详情、收藏、历史、标签四个 Activity；Banner 许可证不在 assets。现用搜索与详情类仍在。Debug ARM64 APK SHA-256：`acf42ca98b34c63c773325cc2db58b4dcb271f75c968db0387bfd80fe35b62bc`。
- 本地证据目录：`.tmp/legacy-anime-cleanup/`。任务模拟器使用只读 `Pixel_5_API_34`、私有 ADB 5055、`emulator-5572`、1920×1080 / 320dpi。
- 任务模拟器及私有 ADB 已关闭。`git diff --check` 通过。未提交，未安装或操作实体电视；没有将本地夹具回归算作真实账号／服务或实体电视验收。

## 与历史记录的关系

[焦点裁切记录](2026-11-01-focus-clipping.md) 的 F04／F05 和 [返回按钮清理记录](2026-11-01-remote-back-cleanup.md) 中旧搜索页的测试结果保留为当时证据；页面现已退役，对应测试移除，不算本次回归覆盖。其余返回和裁切修复仍保留。

## 用户授权的电视 Release 安装（2026-11-01 15:58）

用户随后要求“最新 release 安装到 TV”。本节更新上文尚未部署的验证时点；本次安装包含此前 A01–A08、返回按钮／焦点修复以及当前旧番剧发现链路清理。

- 重新执行 `scripts/dev-env/gradlew-local.bat --offline :app:assembleRelease`，日志末尾 **BUILD SUCCESSFUL in 2m 37s**，689 个任务。签名验证通过；APK 包名 `com.okamihoro.ddplaytv`，版本 `4.2.3` / `70`，ARM64，68,279,563 字节。
- 安装文件：`app/build/outputs/apk/release/ddplaytv_v4.2.3_arm64-v8a-release.apk`。Release DEX 中旧搜索／首页／季度页、旧路由及 Banner 类均已移除；现用搜索仍在，旧 Banner 许可证不在 assets。
- 设备：`192.168.31.70:5555`，`VIDAA_TV` / `MT9653`，Android API 30，支持 ARM64。默认 ADB 5037 的连接报 Windows 10013，改用本任务独立 ADB 5055 后正常连接，没有修改系统网络配置。
- `adb -P 5055 -s 192.168.31.70:5555 install -r <APK>` 返回 **Success**。安装后 `lastUpdateTime=2026-11-01 15:58:05`，UID `10111` 和 `firstInstallTime=2026-02-12 10:47:55` 保持不变，没有卸载或清空数据。
- 电视安装包与本地 APK 的 SHA-256 一致：`ff2a118b8820a395da504d13d479cd88b3c80d136d96c87e431b9c131d45e812`。
- 原始构建、签名、APK 内容、安装前后包信息、安装及哈希证据：`.tmp/legacy-anime-cleanup/release-delivery/`。任务 ADB 已关闭，没有操作电视导航／播放，安装成功不代表实体电视运行验收或数据库迁移已被实际触发。未执行提交。
