# TV 焦点库重写执行记录

用户于 2026-10-28 授权实施[既有设计](../designs/2026-10-28-tv-dpad-library-rewrite-design.md)。范围覆盖 Bilibili 退役后保留的 TV 页面、列表、分类和播放器面板；移动端保持原生触摸路径。完成条件包括删除旧导航算法、验证具体焦点目标及交付构建，不以增加依赖代替完成。

开始时工作区已有暂存的文档精简改动；保留这些改动，不操作 Git index，不提交。当前依赖固定为 DpadRecyclerView 1.4.3，工具链为 Kotlin 1.9.25 / AGP 8.7.2 / Gradle 8.9。若兼容性验证证明必须升级工具链，按设计提供实际证据和最小调整方案，未解决前不铺开页面迁移。

## 实施顺序与验收

- [x] 1. 兼容性：固定依赖，验证实际 Kotlin 调用、KAPT/DataBinding、Debug、Release/R8；记录解析版本。核查 TV 页面与旧代理调用清单。
- [x] 2. 主路径实现：主壳、媒体库、文件列表／路径栏、番剧网格改用库；默认入口、业务 key、目录与条目恢复落地。设备已验证侧栏与首列／非首列边界、插入／删除及目录重建；完整网络与实体 TV 矩阵不计入此项。
- [x] 3. 分类与设置实现：Preference 列表接入库；播放器设置、扫描管理、搜索、详情、主页分类和源绑定改用显式分类与单个活动 Fragment 容器，手机保留 Pager。设备已验证分类确认、内容方向不切页、开关单次执行、持久化与重建。
- [x] 4. 弹窗与其余列表实现：底部动作、文件选择、下载／字幕、历史、缓存／许可证、筛选等列表迁移。窗口负责弹窗关闭后的原目标恢复，删除延迟抢焦点回调；底部动作弹窗连续开关 30 次通过。其他数据依赖场景的验证范围见下表。
- [x] 5. 播放器实现：动态设置／选集／轨道／来源列表交库；静态控件使用原生方向导航与模态边界，输入归于 dispatchKeyEvent 单一入口。设备已验证菜单箭头不快进、不暂停，浏览不改变比例，返回恢复菜单调用项与画面，确认播放／暂停各一次。
- [x] 6. 清理与本地整体验证：删除两套旧列表代理、索引重试／中心线算法、焦点反向切页及无调用协议。单元测试、lint、相关样式检查、依赖治理、Debug/Release 与 AVD 的 TV／手机回归通过；具体覆盖与限制见下文。
- [ ] 实体 TV 验收：使用 Release APK 验证完整播放主路径、至少 10 秒方向键长按、快速反向、真实网络等待及主观跟手性。本次没有连接实体电视，此项仍未验证，不以 AVD 结果代替。

## 迁移清单

源码扫描发现 36 份含 RecyclerView 的布局，另有 Preference 动态列表；分类宿主包括 SettingPlayerActivity、ScanManagerActivity、SearchActivity、AnimeDetailActivity、HomeFragment、BindExtraSourceActivity。列表主焦点统一使用现有条目标签／可操作根 View，实际方向搜索和滚动由库处理。实施时按上述分组逐一确认，不为静态按钮增加列表或全局焦点引擎。

| 区域 | 迁移结果与验证范围 |
| --- | --- |
| 主壳／媒体库 | 独立 TV 侧栏直接使用库；默认当前栏目、右进内容、左边界和 BACK 回当前栏目。已复现旧版启动错误并验证新版两项回归。隐藏 Fragment 限制生命周期，不再主动抢焦点。 |
| 文件与路径 | 两类列表交库；按目录区分 DpadStateRegistry 状态，重建保存路径栈与文件业务 key。目录进入／返回、重建与同一行“更多操作”通过设备回归；原协议访问与分页业务保持。 |
| 番剧列表 | 季度／追番／历史／标签／介绍／推荐／集数／搜索／主页与磁力筛选全部标记 TV 列表；AnimeAdapter 使用 animeId 身份。60 项网格的插入／删除、首列与非首列左键通过；联网数据完整性未在此任务重测。 |
| 分类页 | 六个宿主接入 TvCategoryPages；只显示当前 Fragment，分类浏览与确认分离。搜索与扫描页结构冒烟通过，播放器设置的重建／持久化通过；源绑定与详情的联网操作不计为已验证。 |
| 本地／用户列表 | 历史、弹幕绑定／下载、字幕搜索／文件弹窗、扫描扩展／过滤、缓存、许可证、头像列表全部迁移；保留现有业务。主要页面加载通过，字幕搜索取消弹窗后的空态另加具体工具栏目标回归。 |
| 公共弹窗 | 底部动作、文件选择及路径条使用库；输入／确认／取消保留原生控件，TV 禁用拖拽。底部动作循环 30 次、文件更多操作返回通过。 |
| 播放器面板 | 设置网格、选集、轨道、比例、Anime4K、弹幕搜索／屏蔽、来源与路径列表交库；数值／滑块保留静态控件，删除手写索引与行列路由。菜单隔离、子面板返回、浏览不执行和播放／暂停通过。 |
| 保留原生控件 | 个人页按钮、工具栏、登录／存储配置／投屏、确认与文本输入弹窗不改成列表。播放器数值按键只修改对应数值；`item_storage_video` 的嵌套 RecyclerView 仅展示标签、无独立动作，继续普通 RecyclerView。 |

主导航不再进行全局逐键搜索；TvContentContainer 只在子列表允许退出时提供左侧栏目目标。共享布局由 `TvViewInflater` 根据 `tvDpadList` 与 TV mode 创建库控件，避免复制整页；`vertical()/horizontal()/grid()` 保留库 LayoutManager。Preference 使用原 Adapter／持久化与原无障碍代理，针对 AndroidX 的库组限制做局部标注，无新设置渲染器。

## 验证与决策

- 2026-10-28：已读取工作区设计、现行约束及实际构建配置。开始依赖兼容性验证；尚未宣称接入或焦点修复通过。
- 依赖下载：最初两次构建因旧 Gradle 守护进程的网络权限失败；独立进程 `--no-daemon` 已正常下载公开依赖。原配置构建随后以 `BUILD FAILED in 5m 30s` 结束，唯一编译错误为 `BaseSplashActivity.dispatchKeyEvent` 的可空参数不再覆盖新版 AndroidX 的非空签名，已按实际签名修正。
- 严格兼容性：发现现有 `setupKotlinOptions` 全局启用了 `-Xskip-metadata-version-check`，Release 关闭了 minify。临时 init 脚本移除跳过检查，再构建 Debug、Release 和仪器测试；另准备独立的 AGP 8.7.2 / Kotlin 1.9.25 / JVM 1.8 / minSdk 21 的 R8 样例验证库，不把原 Release 构建当作 R8 验证。
- 设备基线：`emulator-5558` 为 `device`。原模式 `0x11`、1080×2340、440 dpi；原来已禁用 SystemUI，保留该状态。添加本任务的 `/system/etc/permissions/ddplaytv-dpad-rewrite-tv.xml` 后重启，确认 TV 模式 `0x14`，测试显示设为 1920×1080、160 dpi。结束时移除该文件并恢复本次显示覆盖及非 root adbd。
- 已观察旧版问题：非触摸输入下重新启动 TV 主页，UiAutomator 显示焦点落在媒体库第一行 `item_layout`，当前侧栏项未聚焦（`.tmp/tv-dpad-baseline.xml`）。已加强 `TvMainFocusSmokeTest`，断言启动侧栏的具体位置及右进内容、左回当前侧栏；等待新测试运行形成可重复失败证据。
- 严格构建已完成：`:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest -I .tmp/tv-dpad-compat.gradle -I .tmp/tv-dpad-strict.gradle --offline --console=plain`，`BUILD SUCCESSFUL in 3m 50s`（`.tmp/tv-dpad-strict-build.log`）。未升级 Kotlin 插件或 JVM target；已从正式配置删除跳过 metadata 检查的参数。Release 因缺少正式签名材料使用既有 debug 签名回退，不能称为正式签名产物。
- R8 检查：`.tmp/tv-dpad-r8` 独立样例使用同版本 AGP/Kotlin、JVM 1.8、minSdk 21 和 DpadRecyclerView 1.4.3，实际执行 `minifyReleaseWithR8`，`BUILD SUCCESSFUL in 27s`（`.tmp/tv-dpad-r8-build.log`）。这验证库的严格编译和 R8 兼容；主应用原有 Release 仍未启用 minify，不能将样例结果表述为整应用混淆验证。
- 旧实现回归基线已运行：真实 TV 模式下运行加强的 `TvMainFocusSmokeTest`，`Tests run: 2, Failures: 2`，均为启动后焦点不在侧栏（`.tmp/tv-dpad-main-baseline-tests.log`）。
- 布局接入采用小型 AppCompat inflater，只有标记 `tvDpadList` 的共享列表在 TV 模式创建库控件，手机保持原 RecyclerView；独立 TV 主页直接声明库控件。该接线仅选择容器类型，不参与焦点搜索或重试。每个标记与对应旧代理删除同批落地，避免复制整套 DataBinding 页面。
- 实际解析结果：DpadRecyclerView 1.4.3、RecyclerView 1.4.0、Fragment 1.8.2、Collection 1.4.3、Kotlin stdlib 2.0.0；Kotlin 插件仍为 1.9.25、JVM target 仍为 1.8。结果来自 `.tmp/tv-dpad-final-validation.log` 的 `printDpadDependencies`，没有强制降级。
- 迁移后第一轮完整设备回归：明确安装 Debug 与测试 APK 后，真实 TV UI mode 下执行 `TvMainFocusSmokeTest`、`TvDpadRewriteInstrumentedTest`、`PlayerSettingDpadNavigationTest`、`DialogDpadFocusSmokeTest`、`AppFeaturePageSmokeTest`，**OK (13 tests)**（`.tmp/tv-dpad-device-regression.log`）。不含后续新增的字幕空态用例与返回分发调整。
- 验证发现并修正：网格测试在滚动未结束时发送跨区按键，改为等待真实焦点位置及滚动空闲；测试常量改用 RecyclerView 声明的 `SCROLL_STATE_IDLE`。默认 Robolectric 下载受限，复用 `.tmp/handoff14-local.gradle` 与项目离线运行库。lint 报 Preference 无障碍代理的 `RestrictedApi` 及新版 AndroidX 的 `MissingSuperCall`，前者限定在替换 Preference 列表的方法中保留原代理，后者将详情与播放器原有返回语义接到 OnBackPressedDispatcher。未禁用全局 lint。
- 静态清理：生产源码无两套旧列表代理、requestIndexChildFocus、焦点反向切页／旧 Tab 协调器、DpadFocusScope 调用；相关文件与 CenterLayoutManager、过时方向／重试辅助已删除。DpadFocusOwner 仅保留进入操作；移除已无调用的延迟 resetDescendantFocus 接线。
- 截图与纯 DPAD 路径：主页内容焦点边框、设置分类和设置内容截图见 `.tmp/tv-dpad-main.png`、`.tmp/tv-dpad-settings.png`、`.tmp/tv-dpad-settings-content.png`；从个人页进入设置、切到弹幕分类、进入内容并 BACK 后，UI 树确认焦点回到 `player_setting_ll`（`.tmp/tv-dpad-settings-return.xml`）。
- 字幕空态的附加回归最初失败，诊断确认按钮存在、可聚焦且可见，但其 Toolbar／AppBarLayout 祖先的 `touchscreenBlocksFocus=true` 导致请求失败（`.tmp/tv-dpad-empty-parent-probe.log`、对应定向 logcat）。基类只在 TV 模式关闭这两个原生限制；删除尝试中的页面抢焦点处理与诊断日志，保留 Android 自然恢复到工具栏返回按钮。回归同时检查工具栏内的真实可操作焦点及确认后退出，避免把空列表容器算作成功。
- 最终构建命令：`scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest testDebugUnitTest lint verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck -I .tmp/handoff14-local.gradle -I .tmp/tv-dpad-style.gradle --offline --console=plain`，**BUILD SUCCESSFUL in 1m 36s**（`.tmp/tv-dpad-delivery-checks.log`）。ktlint 限于本次改动文件；全仓历史格式问题不计为通过。JVM 共 378 项，376 通过、0 失败、2 个外部 FTP／SMB 服务用例跳过，计数见 `.tmp/tv-dpad-unit-counts.json`。
- 工具栏修复后的相邻回归：分类页原先在布局前请求焦点，会被新可达的返回按钮覆盖；播放器设置／扫描管理改为首次窗口可用时请求分类焦点，已在内容区的焦点保持不变。字幕退出断言等待 Activity 生命周期实际销毁。定向三项回归通过（`.tmp/tv-dpad-entry-fixed.log`），不增加延迟重试。
- 最终 TV 回归：安装本次最终 Debug 与测试 APK 后，上述五个测试类加播放器加密重入／重建／返回退出用例，**OK (15 tests)**、无跳过（`.tmp/tv-dpad-delivery-tv.log`，47.476 秒）。包含新增字幕空态、分类入口修正及原生返回分发调整。测试环境仍为 API 34 手机 AVD 注入 TV mode，不等同实体电视测试。
- 手机回归：恢复手机 UI mode 后执行 `adb -s emulator-5558 shell am instrument -w -e class com.okamihoro.ddplaytv.ui.smoke.AppFeaturePageSmokeTest com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`，**OK (5 tests)**、无跳过（`.tmp/tv-dpad-mobile-tests.log`，62.714 秒）。覆盖主栏目与设置、搜索、本地和存储页面结构；不把页面加载冒烟表述为全部触摸流程通过。
- 手机触摸补查：用 `adb shell input tap` 完成个人页 → 播放器设置 → 弹幕分类 → 切换“弹幕调试工具” → 工具栏返回 → 重进设置。UI 树确认一次点击从 false 变为 true，退出重进后仍为 true，随后恢复原值 false；证据为 `.tmp/tv-dpad-mobile-main.xml`、`tv-dpad-mobile-personal.xml`、`tv-dpad-mobile-toggle.xml`、`tv-dpad-mobile-return.xml`、`tv-dpad-mobile-persisted.xml` 和 `tv-dpad-mobile-preference-restored.xml`。首次自动选择 launcher 进入了 LeakCanary，随后明确使用应用的 SplashActivity 启动；上述成功路径均来自应用本身。
- AVD 已恢复：移除本任务的 TV feature 文件，重启并恢复非 root adbd。最终读回为 `device`、`sys.boot_completed=1`、`mCurUiMode=0x11`、1080×2340／440 dpi、`uid=2000(shell)`，临时 feature 文件不存在（`.tmp/tv-dpad-avd-restored.log`）。保留测试前已经禁用的 SystemUI 与锁屏配置。

## 交付状态

代码迁移及上述本地验证已完成，未执行 Git 提交。Debug 通用包为 `app/build/outputs/apk/debug/ddplaytv_v4.2.2-debug_universal-debug.apk`；Release 构建也已通过，但沿用项目既有的未混淆／缺少正式密钥时 debug 签名回退配置。实体 TV 的完整交互验收，以及迁移清单中明确未测的真实联网场景仍待验证。
