# TMDB 媒体库实施记录

日期：2026-10-31。用户已要求实施[设计](../designs/2026-10-31-tmdb-media-library-design.md)，覆盖设计内关联闭环、原生资料页面与手动目录索引；不扩展 NFO、跨作品自动连播或常驻后台任务。

## 顺序与验证

1. 独立 metadata 模块、Room 18→19、新增文件/作品/关联/任务表及来源代次。验证迁移保留旧数据、不同来源/版本身份、人工关联保护和迟到提交。
2. 独立 TMDB 客户端、保守解析与匹配、独立来源读取、人工季集纠错。验证固定响应、失败分类、语言回退和无媒体下载。
3. 沉浸放映首页、作品库/详情、纠错、任务设置入口。验证真实数据库状态、纯 DPAD 导航、原文件回退和精确历史播放。
4. 手动目录扫描、检查点、取消/重试与完整性保护。首批支持本地已有索引、SAF、SMB、WebDAV；未验收的其他协议不显示可全量扫描。
5. Debug 构建、JVM、lint、格式与边界检查；可用 AVD 的直接 runner/DPAD 检查；更新记忆与产品文档。

## 已实施范围

- 新增 `core_metadata_component`，local/storage/user 直接使用，app/player 不引入元数据实现依赖；治理允许矩阵和 23 模块快照同步更新。
- Room 18→19 只增加资料、来源代次、文件、绑定和扫描表；来源内容变更隔离旧资产，密码轮换使请求失效，单纯显示名编辑保留身份。历史与弹幕字段未改作 TMDB 身份。
- 独立 TMDB API 客户端：固定 HTTPS 主机、最多两个并发请求、有界重试、HTTP 缓存、电影/剧集搜索与季详情；中文缺文回退，图片配置拼接。令牌通过环境变量/Gradle/local.properties 注入，缺省明确提示未配置。
- 文件名解析、强证据匹配、候选分页、人工锁定/解除/刷新、S00/单集/合并集和目录映射预览。未知类型、连续集数和歧义不会自动猜测。旧请求不能覆盖新绑定或已刷新的季集文本。
- 本地已有视频索引、SAF、SMB、WebDAV 使用独立 Storage，读取后关闭；不读取视频流或解析播放直链。SAF 文档 ID 视为不透明标识，未获目录快照时不猜父目录、不开放目录映射。
- 手动目录扫描按来源串行，同范围去重；持久化计数/完成目录、取消/重试、来源变更失效。只在完整目录后标记缺失，进程重启将旧运行记录标记中断。
- 顶部导航与沉浸首页、海报库、详情/已持有选集、纠错和任务设置。只显示实际 Room 数据；版本选择与继续观看使用原文件身份和历史，不推断跨文件播放队列。
- 关于页面加入官方 TMDB 标志与声明。标志来自官方 SVG，转换保留原有 path/gradient；未重绘商标。

## 参考与本地适配

NOVA `aos-MediaLib` 固定为 `0eb2e2108f76cc3533d652c663e91d6f0ea81936`（`SearchPreprocessor.java`），Jellyfin 固定为 `a46fa4559daaea352257d0196b56e392c18dd09c`（`TmdbMovieProvider.cs`）。参考预处理、ID 与搜索分流，落地为纯 Kotlin 解析、Room 事务与本地协程编排；没有复制上游 UI、Service 或数据库实现。TMDB 官方鉴权、图片配置、季详情和 FAQ 在 2026-10-31 复核；真实访问仍未验收。

## 验证记录

- `.tmp/tmdb-first-build.log`：5 个解析/匹配测试，`BUILD SUCCESSFUL in 6m`。
- 中途构建曾因 Robolectric 运行时离线缺失、Activity 的协程 `this`、HOME 枚举位置及编译期间源码变动失败；已修正并使用 `.dev-env/robolectric`，之后不在构建时修改输入源码。
- `.tmp/tmdb-stale-episode-red.log`：新增回归先失败，明确复现候选旧文本覆盖刷新文本；修复为绑定只插入缺失单集，刷新独占已有资料更新。
- `.tmp/tmdb-build5.log`：20 个 metadata 测试通过（5 解析/匹配 + 15 仓储），数据库迁移测试通过；整轮 `BUILD FAILED`，原因是新增 Storage 测试需 Android `MediaStore` 初始化，已改用 Robolectric。
- `.tmp/tmdb-build6.log`：全量验证 `BUILD FAILED`，离线仓库解析不到 `kotlinx-coroutines-test:1.7.1`；使用上一任务已有的本地 Maven 缓存路由，不改生产依赖版本。
- 新仪器测试曾因误用 ActivityHelper 属性编译失败，已按现有 `getTopActivity()` API 修正；Storage 代理测试两次被 Java Proxy/协程异常包装影响断言，已检查原始失败仍传播且取消类型不被吞掉。对应失败保留在 `.tmp/tmdb-build7.log` 至 `build9.log`。
- `.tmp/tmdb-build10.log`：Debug APK、测试 APK、全量 `testDebugUnitTest`、`lint`、变更范围 `ktlintCheck`、`verifyModuleDependencies verifyPlaybackBoundaries` 全部完成，尾部 **BUILD SUCCESSFUL in 4m 36s**。XML 汇总 **495 通过、2 跳过、0 失败**；跳过项为既有外部服务测试。lint 仍输出既有 mpv 上游 Kotlin metadata 诊断，任务与报告通过，不使用编译绕过。
- 后续 TV 焦点和视觉修复的构建：`.tmp/tmdb-build11.log`（22s）、`build12.log`（32s）、`build14.log`（35s）均 **BUILD SUCCESSFUL**；`build13.log` 因新增可见性断言的格式规则失败，格式化后已通过。

### 本地命令与测试环境

使用项目 JDK 17 包装器：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-env/gradlew-local.ps1 `
  :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lint `
  verifyModuleDependencies verifyPlaybackBoundaries ktlintCheck `
  -I .tmp/ui-repairs-local.gradle -I .tmp/tmdb-validation.gradle -I .tmp/tmdb-style.gradle `
  --offline --console=plain
```

三个本地 init 脚本分别复用既有协程测试 Maven 缓存、指定离线 Robolectric 目录，以及仅检查本次变更的 Kotlin 文件（按项目约定禁用尾逗号规则）。这些临时路径属于本机验证环境，不是工程新增依赖。

设备为任务创建的 `Pixel_5_API_34` 只读 AVD、`emulator-5558 device`，1920×1080 / 240dpi。测试使用 `adb install -r -g` 安装 Debug 与 androidTest APK，再运行 `am instrument -w -r -e class ... com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner`。这是直接 runner 验证；本轮未重新执行 Gradle connected/UTP，也未构建 Release。

### 设备失败、修复与通过结果

- 首轮误用不含 `.debug` 的 runner 包名；修正后又被 System UI ANR 对话框挡住窗口焦点（`.tmp/tmdb-device1.log` / `device2.log`、`.tmp/tmdb-screen.png`）。在任务 AVD 临时禁用 System UI 后重跑，不将环境阻塞算作通过。
- `.tmp/tmdb-device3.log`：6/7 通过，来源页“新增”按钮向上误跳顶部导航。修复 `TvContentContainer`：优先使用内容区可达焦点，再在上边界回导航。
- `.tmp/tmdb-device4.log`：13/14 通过；来源选择器测试的快速按键超过滚动完成节奏，最后停在第 7 项。改为每次按键后等待该项及滚动空闲，仍断言 9 个入口、底部取消及原控件返回。
- `.tmp/tmdb-device5.log`：**14/14 通过**，包含原生首页→作品库→详情→纠错→返回的纯 DPAD 路径、详情重建、实际 Media3 90% / mpv 95% 续播及播放中资料更新不切源/不重置进度、顶部边界/网格更新和原有功能页导航。
- `.tmp/tmdb-permission-denied.log` / `tmdb-permission-granted.log`：各 **1/1 通过**；拒绝本地读取权限时仍可进入远程来源与历史，恢复授权后本地入口可用且返回焦点恢复。
- 截图检查发现详情行裁字、首次输入框抢焦点时解析词未填入；修正行高、独立显示原文件名及初始化搜索词。`.tmp/tmdb-device6.log`：相关 **4/4 通过**，新增断言检查文件文字的完整可见边界。截图目录 `.tmp/tmdb-ui-verified/`，使用标明用途的固定数据库资料，不代表真实 TMDB 图片验收。
- 后续手动检查发现任务空列表获得不可见焦点；所有新增页面隐藏空列表并恢复操作按钮，资料已无文件时不再生成“播放电影”空行。`.tmp/tmdb-build15.log` 重建最终 Debug/测试 APK，并执行受影响模块 JVM、lint、格式及两项边界检查，尾部 **BUILD SUCCESSFUL in 1m 4s**。
- `.tmp/tmdb-device7.log` 补测暴露兜底焦点过宽，干扰返回海报位置；同时任务页测试误把“返回”当成唯一默认动作。改为仅在原列表持有焦点且变为空时转移焦点，任务页默认选择清缓存动作。最终 `.tmp/tmdb-build16.log` 同范围构建/受影响 JVM/lint/格式/边界检查尾部为 **BUILD SUCCESSFUL in 34s**。
- `.tmp/tmdb-device8.log`：最终资料页面 **5/5 通过**，包含空任务页纯 DPAD 清理图片/HTTP 缓存、人工关联不丢失及返回入口焦点。结合不受最后修改影响的前述导航与权限回归，共 **17 个不同设备用例通过**；不把重复运行累计成不同用例。最终截图为 `.tmp/tmdb-ui-final-verified/`。
- 文档核对：97 个本地 Markdown 链接和代码围栏通过，`git diff --check` 通过。任务 AVD 已恢复 1080×2340 / 440dpi、System UI enabled、非 root adbd，移除本次视频和设备截图夹具；恢复读回为 `.tmp/tmdb-avd-restored.log`。
- `adb emu kill` 在本机返回不支持；核对端口 5558、只读参数及父子进程身份后，仅结束任务创建的 PID 8908/28160。`adb devices -l` 读回为空，临时 AVD 已关闭。

## 验收边界

真实 TMDB 凭证/API/图片网络、四类真实服务和物理电视尚未验收；固定响应、代理 Storage 测试与 AVD 不代表服务可用性或识别准确率。未提供人工标注真实样本，不能报告自动精确率/覆盖率。FTP、Alist、PC 和云盘索引保留后续逐协议验收，不开放为已支持；原文件浏览与播放保留。资料语言首期固定，NFO、自选图片、常驻后台和跨作品自动连播不在范围内。

本地目录仅读取已有索引，不触发全盘刷新；不可读目录保留旧记录，缺少可靠大小/修改时间时无法保证发现同路径替换。任务重试从当前范围重新读取，逐目录完成标记不代表可复用远程游标。用户原有暂存文档保留；未执行 Git 提交。
