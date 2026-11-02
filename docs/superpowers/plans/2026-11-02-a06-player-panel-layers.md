# A06 播放侧栏材质实施与验证

日期：2026-11-02。基线：`c047019c3`。用户已确认 [设计方案](../designs/2026-11-02-a06-player-panel-layers-design.md)，本账本记录确认后的原生实施，不改变此前设计截图的证据性质。

## 范围与任务

采用已确认的 [调整后参考](../../../document/prototypes/tv-ui/player-panel-layers-prototype.html?mode=after)：不透明冷灰渐变、右侧渐暗、柔和边缘和播放器设置普通行减轻底色。保持九个右侧布局模板、A02 焦点／选中、播放 A 及既有字号／尺寸／字幕／弹幕位置。共享旧背景的左侧选择器与关键词视图、原有 320dp 弹幕配置不套用新右侧材质。

| 任务 | 状态 | 本轮证据／完成条件 |
| --- | --- | --- |
| T1 右侧专用资源与设置普通行 | 已实施、局部检查通过 | 新背景仅有九个右侧布局消费者；旧背景保留两个消费者，A02 状态资源未改 |
| T2 单一背景层与面板栈 | 已实施、仪器检查通过 | 右侧嵌套不加深；排除左侧／关键词／旧 320dp 面板；逐层返回、连续切换与释放清除通过 |
| T3 原生视觉与 DPAD | 本地通过，实机／用户验收待完成 | 普通字体 11 项、1.3 字体 7 项不同场景通过，含双内核真实播放及倍速调节／复开／重置 |
| T4 工程检查与收口 | 已通过 | Debug／test APK、全量 JVM／lint、依赖及限定格式通过；源文件、安装包和截图身份已记录 |

## 验证安排

- 使用任务自有 `emulator-5586`、私有 ADB `5085`、API 34、1440×810／240dpi。仅此模拟器用于安装与导航，不操作实体电视或默认 ADB 服务。
- 新增仪器用例先记录九类面板的改前状态，并对“忙碌背景不能改变面板阅读底色”及“栈切换和释放清除背景层”建立可失败的行为检查。
- 图像对比使用同一历史背景裁切作为测试覆盖层，保持视口／内容一致；真实双内核播放另行烟测。测试图像不是实体电视截图，也不等同于真实视频性能测试。
- 源文件与原型、构建／APK、失败与重跑、原生截图及 DPAD 结果在执行后填入本账本；未取得证据的状态保留开放。

## 原生实现

- `cinema_player_side_panel.xml` 提供全不透明 `#27272F → #1B1B22` 渐变、20dp 圆角和约 10% 白边，仅替换设计列明的九个布局；面板增加 12dp 静态 elevation，现有标题容器增加细分界。倍速和比例保留原有不同结构。
- `cinema_player_setting_row.xml` 仅用于播放器设置行的普通态；禁用、焦点、按下及选中继续交给原 `cinema_button`。未修改全局按钮或 A02 的填充、描边、勾选资源。
- `PlayerPanelScrimView` 是一个不接收焦点、点击或无障碍导航的静态渐变 View。左 50% 透明，70% 为 `#42000000`，最右 `#70000000`；200ms 显隐，重复通知不重启动画。
- `SettingController` 在栈顶变化时通知 `VideoController`，后者只控制这一层遮罩。`settingRelease()` 清空整个栈，播放器切源／释放／销毁时重置遮罩；普通 BACK 仍逐层返回并恢复原呼出焦点。
- 左侧文件选择器、关键词视图及旧 320dp 弹幕配置不启用新增遮罩。关键词视图原有的全屏 50% 黑色遮罩继续保留。

## 页面与状态覆盖 M01–M11

[原生画廊](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/index.html) 提供九组改前／改后 APK 对比及两种字体的原生状态；共 67 张无损 WebP，转换后逐图核对与 PNG 像素相同。普通／1.3 字体均为 1440×810／240dpi；没有伪造改前 1.3 字体对比。

| 编号 | 实施与实际检查 | 原生证据／边界 |
| --- | --- | --- |
| M01 选集 | 保留当前集勾选、长名换行、三项队列；DPAD 切到末集／上一集并返回入口通过 | [普通字体](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-switch_video_source.webp)；两种字体的切集与返回用例通过 |
| M02 轨道 | 字幕空态、外部 SRT 选中失焦／聚焦、音轨、弹幕空轨及视频轨共用壳已查看 | [字幕选中失焦](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-subtitle-selected-away.webp)；勾选和禁用操作回归通过。视频轨直接打开共用壳，未验证多视频轨入口条件；非空弹幕和多音轨组合仍待真实媒体覆盖 |
| M03 字幕样式 | 保留滑杆、原数值和垂直偏移焦点；两种字体无新增阅读遮挡 | [字幕样式](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-subtitle_style.webp)；未改变字幕定位逻辑 |
| M04 弹幕样式 | 保留滑杆／滚动结构；DPAD 改字号即时更新运行值和配置，关闭再开仍保持；finally 恢复旧值 | [放大字体](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-font130-danmu_style.webp)；即时生效和复开持久化回归通过 |
| M05 播放设置 | 普通行减轻底色，分组、图标、尺寸和焦点填充保留；原 DPAD 用例滚动到字幕／弹幕组 | [APK 前后对比](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/compare-settings.jpg) |
| M06 时间偏移 | 字幕／弹幕两种壳均已查看；字幕 +0.5、重置、禁用重置后回到可操作键通过 | [弹幕偏移](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-danmu-offset.webp)；保留原偏移生效和持久化规则 |
| M07 倍速 | 保留 Material Slider；纯 DPAD 改值写入配置／运行值、关闭复开保持、重置到 1.0 并恢复滑杆焦点 | [调整后](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-speed-adjusted.webp)、[重置后](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-font130-speed-reset.webp)；补充用例在两种字体均通过，finally 恢复原配置 |
| M08 比例 | 仍为无额外标题的列表；默认选中／焦点及其它选项已查看，宽度和边距不变 | [比例列表](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-video_aspect.webp) |
| M09 弹幕搜索 | 加载／空／失败、恢复到 20 项长结果以及 DPAD 滚动已查看；错误状态在恢复后清空 | [失败](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-search-error.webp)、[长列表](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-font130-search-content-scrolled.webp)；状态从既有公开观察接口注入，不算实时服务或 IME 验收 |
| M10 栈／退出 | 字幕→样式→左侧→BACK→关键词→BACK→320dp 面板；12 次连续嵌套开关；最后返回呼出入口；嵌套释放／销毁无残留 | [保留左侧形态](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-left-picker.webp)、[保留关键词遮罩](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-keyword-form.webp)；两种字体均通过 |
| M11 背景／字体／内核 | 九类面板的黑／白背景合成底色相同；亮场／暗场及 1.3 字体已查看；Media3／mpv 打开侧栏后时间线继续前进，BACK 恢复入口 | [mpv 实际画面](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/images/a06-after-real-type_mpv_player-panel.webp)；性能帧率及实体电视 PQ／HDR 不由此推定 |

## 原型与原生对照

- 已重新查看确认稿 `?panel=settings&mode=after`、选集参考及两种字体的原生九类面板、轨道选中、搜索状态、明暗场和排除项。[确认稿与原生对照](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/reference-vs-native.jpg) 保留原生控件图标／排布；网页标题行距、设置行间距等原本是近似重绘，本轮没有用网页示例覆盖原生数据或更改这些几何。
- 面板仍为 264dp，即 396px 宽；15dp 外边距及完整可见边界有仪器断言。所有改前／改后对比使用同一历史画面覆盖层，真实双内核播放另行截图与断言。
- 播放设置前后左半画面取 `(0,110)–(720,600)` 共 352,800 像素比较，仅 74 个像素有最多 1／255 通道差，平均各通道差低于 0.0002；新增压暗发生在右半。面板自身不透明性由黑／白底合成检查直接验证。
- 画廊浏览器检查通过：九组前后对比、58 个原生状态、字体／面板筛选、全部图片解码、链接与原尺寸打开、760px 视口；脚本错误为 0。源码、APK、原型及图片 SHA-256 见 [身份记录](../../../document/prototypes/tv-ui/previews/player-panel-layers/native-2026-11-02/verification.json)，画廊自身检查见同目录 `gallery-verification.json`。

## 构建与运行证据

采用当前工程实际工具链：Kotlin 2.2.10、AGP 8.10.1、Gradle 8.11.1、JDK 17。通过 `scripts/dev-env/gradlew-local.bat`，设置 `JAVA_TOOL_OPTIONS` 的两个临时目录属性到 `.tmp/wh-jvm`；格式检查使用任务局部 init，只包含本次三个生产 Kotlin 和新增仪器文件。

| 检查 | 结果 |
| --- | --- |
| Debug／测试 APK／限定格式 | `debug-build.log`：**BUILD SUCCESSFUL in 18s** |
| 全量 `testDebugUnitTest lint verifyModuleDependencies` | `gates-online.log`：**BUILD SUCCESSFUL in 7m 31s**；516 项：514 通过、2 项既有跳过、0 失败／错误 |
| 最终生产构建、受影响 lint／依赖／限定格式 | `gates-final.log`：**BUILD SUCCESSFUL in 47s**；生产文件此后未改 |
| 补充倍速用例后测试 APK／限定格式、app lint | `test-speed-build.log`／`lint-final-speed-test.log`：**BUILD SUCCESSFUL in 9s／14s** |
| 普通字体 | 6 项兼容回归 + A06 的 4 项有效结果 + 1 项倍速补测，共 **11 个不同场景**；来自多个批次，栈用例以最后单独重跑为准 |
| 1.3 字体 | `native-final-font130.log`：**OK (6 tests)**；倍速补测 **OK (1 test)**，共 **7 个不同场景** |
| 安装身份 | 模拟器内 Debug／test APK SHA-256 与最终本地文件一致；基线 APK 身份独立保存 |

运行使用 `adb -P 5085 -s emulator-5586 shell am instrument -w -r -e class ... com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`；两种字体分别采集。仪器运行不是 Gradle connected/UTP 的通过证据。完整日志、114 个 JVM 报告的摘要与哈希、用例名称和批次结果均保存在画廊的 `evidence/` 与身份记录。

最终生产 APK 的 SHA-256 为 `b4d098b3d95d11ec5cda556a203b77a08e6ba0c6987cd8aef2083ba3dc3b9797`；最终测试 APK 身份以 `verification.json` 为准。只在任务模拟器上安装和导航。

## 复现、失败与修正

1. 首次测试 APK 构建直接调用模块内部 `renderState`，**BUILD FAILED**；改为现有公开的 `observerDanmuSearch` + LiveData 后 **BUILD SUCCESSFUL**，没有扩大生产方法可见性。
2. 初始夹具权限失败、桌面 ANR 导致的窗口焦点超时均发生在材质断言之前，不计作 A06 原问题复现。授权任务模拟器读取已声明的媒体权限并清除桌面 ANR 后取得改前截图；背景图片改放应用专属目录，避免缺少图片读取权限时静默回退。
3. 基线实际 APK 的“不受视频背景影响”检查失败：同一个面板 drawable 在黑／白背景上合成得到不同阅读底色。新实现九个面板全部通过，是本任务有效的改前失败／改后通过证据。
4. 首次格式命令用了不存在的 source-set 任务名，随后格式检查指出新文件换行格式；两次 **BUILD FAILED** 均已按实际任务名及局部 formatter 修正。首次全量离线检查缺少 `kotlinx-coroutines-test:1.7.1` 缓存而 **BUILD FAILED**；常规在线解析后全量检查通过，没有改依赖声明。
5. 遮罩测试起初把原控制层的 alpha=39 当成 A06 残留，又把关键词已有全屏遮罩 alpha=147 当成新增压暗；改用开面板前后差值、关键词左右均匀性检查，生产实现没有因此改动。最终栈用例在两种字体通过，保留早期失败日志。
6. lint 仍会输出既有 mpv AAR 的 Kotlin 2.4 元数据诊断，但任务最终均为 **BUILD SUCCESSFUL**，两个实际内核的播放／返回检查也通过；本任务未改内核依赖或编译器配置。

## 验证边界与环境收口

本地实施、工程检查、实际查看截图及记录范围内的 DPAD 已完成。实体电视交付、用户视觉验收、平台帧时序／阴影性能、HDR 与代表性真实多轨／在线服务／IME 场景仍独立开放。静态历史背景覆盖层不能证明真实视频性能；快速切换断言不构成长时性能基准。

倍速补测因清单缺少直接操作证据，在第一次关闭任务 AVD 后重新启动同一只读任务 AVD 完成；没有占用用户电视。最终字体已读回 1.0，任务 AVD／私有 ADB 5085 已关闭；18106 本地预览服务保留供查看。未操作默认 ADB 5037，未打 Release、改版本、暂存或提交。
