# 全局配置收敛实施与验证

日期：2026-10-30。状态：**已实现并完成本地验证**。真实 PC 服务、物理电视及下述独立 UI 异常不在已通过证据内。

设计依据：[全局配置收敛设计](../designs/2026-10-30-global-settings-design.md)。进入任务时基线为 `ee00ffe084c243ba90ae9dbf07e3cde423b4d0fd`，已有 TMDB／网页原型和项目记忆的暂存改动，以及本设计的未暂存文档；保留这些改动，不提交。

## 实施范围与分工

- 数据模型／全局配置、集成和验证：移除两个实体偏好字段与 `LIBRARY_OVERRIDE`，新增全局 PC 分组，核对最终变更和运行证据。
- 存储与设置 UI：移除九类来源内核选项和 PC 单库分组，将分组移入全局应用设置，更新 DPAD 仪器测试。
- 来源解析与恢复：统一全局内核、移除重复能力矩阵、固定 PC 实例分组模式，兼容无 Hash 旧身份，保留会话内核重试。
- 数据库：Room 17→18 删除两个偏好列，保留主键、自增高水位、索引、协议、历史与凭据引用，补升级回归。

## 进度

- [x] 设计确认与源码基线复核。
- [x] 数据／全局设置与 UI 收敛。
- [x] 全局内核、PC 分组和身份兼容代码及回归用例。
- [x] Room 升级代码、schema18 生成与数据保留用例。
- [x] 构建、JVM、lint、格式和边界检查。
- [x] 设备仪器与全局设置纯 DPAD 冒烟。
- [x] 独立复核、维护文档及项目记忆更新。

## 验证证据

最终完整命令：

```powershell
scripts/dev-env/gradlew-local.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest testDebugUnitTest lint ktlintCheck verifyModuleDependencies verifyPlaybackBoundaries -I .tmp/handoff14-local.gradle -I .tmp/global-settings-style.gradle --offline --console=plain
```

已读取 `.tmp/global-settings-validation-complete.log` 尾部，结果为 **BUILD SUCCESSFUL in 3m 54s**，1706 个任务。Debug／Release／测试 APK、全量 JVM、完整 lint、变更文件范围 ktlint、模块依赖及播放边界门禁通过。汇总 96 份 JVM XML：**402 项中 400 通过、0 失败、0 错误、2 跳过**，计数保存在 `.tmp/global-settings-jvm-counts.json`；未变化用例的已有输出可由 Gradle 缓存复用。跳过项仅为外部 FTP／SMB 服务夹具，未声称真实网络验收通过。

下列初始失败及修复记录保留；设备直接 runner 与 Gradle 构建结果分开报告。

- `.tmp/global-settings-focused.log`：初次数据库／存储测试为 **BUILD FAILED in 2m 41s**，数据库 9 项中 8 通过、1 失败；空库场景在原生 SQLite 打开阶段发生 `SQLITE_CANTOPEN`，尚未执行迁移。将同一测试内重复删除／重建数据库的两个场景拆为独立用例，保留全部原断言；后续数据库 10 项全部通过。
- `.tmp/global-settings-format.log`：变更范围格式化 **BUILD SUCCESSFUL in 19s**；恢复两份未涉及功能变更的来源文件，避免带入格式变动。
- `.tmp/global-settings-validation.log`：首次完整验证 **BUILD FAILED in 4m 11s**。存储 123 项中 4 失败、2 跳过：两个新配置测试只拦截 MMKV `decodeInt/decodeBool`，生成代码实际调用 `getInt/getBoolean`；旧路由测试缺少新增全局配置读取的 MMKV 环境；另一个拒绝用例揭露真实身份校验遗漏——项目空字符串的 MD5 扩展返回空串，旧 PC 历史因此绕过非空键检查。补齐实际访问 API，并让 PC 历史始终进入身份校验，保留可靠视频 ID 和旧身份边界。
- `.tmp/global-settings-validation-final.log`：第二次完整验证 **BUILD FAILED in 2m 12s**；解析器 20 项通过，路由仍有 1 项测试环境失败。生成配置缓存 MMKV 对象，而两个测试类切换不同 shadow 类型，触发 `ClassCastException`；改为共享同一 shadow 类型，所有路由断言保留。
- `.tmp/global-settings-storage-regression.log`：存储全模块 JVM 与变更范围格式检查 **BUILD SUCCESSFUL in 29s**，123 项中 121 通过、0 失败、2 项外部 FTP／SMB 服务用例跳过；解析器 20 项和路由 6 项均通过。
- `.tmp/global-settings-ui-format.log`：焦点修复及加强后的仪器测试格式化 **BUILD SUCCESSFUL in 6s**。
- schema18 由 KSP 构建生成；独立复核确认仅移除目标两列，其他实体和保留列一致。运行时／UI 的独立复核无具体问题；另一轮复核指出播放测试需断言实际后端类型，已补齐 Media3/mpv 实际类断言。

设备检查：

- `.tmp/global-settings-device-ui.log`：首次 UI runner 9 项中 7 通过、2 失败；外部目录名称和 PC 密钥输入框的 DPAD_DOWN 未到达后续动作。输入框提前消费纵向箭头，现由来源编辑对话框在非触摸模式下先处理纵向焦点导航；为两处动作增加双向焦点出口，外部目录动作复用现有焦点 overlay；保留目标断言并补往返导航断言。
- `.tmp/global-settings-device-ui-final.log`：安装 19:19／19:20 新 Debug／test APK 后直接 runner **OK (9 tests)**，41.263 秒；覆盖九类编辑页默认焦点／BACK、WebDAV 协议选项、外部目录及 PC 连接测试双向 DPAD、全局分组开关保存／重建／重入，以及全局内核旧值归一化和播放器设置持久化／分类导航。
- `.tmp/global-settings-device-playback.log`：直接 AndroidJUnitRunner **OK (8 tests)**，60.374 秒；覆盖 Media3/mpv 的存储／历史／番剧／直链入口、切换全局内核后旧源重建与新源实际后端、临时重试不改全局，以及两内核 seek／加密恢复／请求重入。测试 APK 为 19:02 构建版本；后续旧 PC 身份检查修复不改变这些入口夹具。
- 纯 DPAD 从冷启动主页进入设置，启用 PC 全局分组、BACK 返回、重入确认开启，再恢复原关闭值并返回。UI 树 `.tmp/global-settings-smoke-{before,on,reenter,restored}.xml`；实际检查截图 `.tmp/global-settings-on.png`，焦点可见、文案及开关状态正确。

设备合计 **17 个不同用例通过，无跳过**。使用直接 AndroidJUnitRunner，不宣称 Gradle `connectedDebugAndroidTest` 通过；离线 UTP 工作流的既有不可用边界没有被这些直接 runner 结果替代。

设备使用任务独立的只读 API34 AVD，`emulator-5558`，SwiftShader／ARM64 translation；原始尺寸为 1080×2340／440dpi、非 root、普通 UI mode `0x11`。临时以 1920×1080／160dpi 验证 TV UI，结束后已恢复原尺寸／密度，读回普通 UI mode `0x11` 和 shell uid 2000，见 `.tmp/global-settings-avd-restored.log`。核对本次 launcher PID 17060、命令行的 AVD／只读／无快照／端口 5558，以及其 qemu 子进程归属后，仅关闭这两个任务进程；ADB 设备列表已为空。

首次启动未指定 AVD 目录而找不到实例；指定目录后沙箱内因快照锁超时退出。经获准权限以相同只读／无窗口／不保存快照参数成功启动，未删除既有 AVD 锁或修改 AVD 配置。

## 剩余边界

新原生 UI 和 TMDB 不在本次实施范围。当前无真实 PC 服务配置，分组、刷新及有／无 Hash 历史／快照／队列兼容采用固定响应数据回归；真实连接测试、物理电视的焦点／播放表现尚未验收。

额外手动观察：从主页“添加媒体库”进入的“新增网络媒体库”选择器内容区为空，且没有默认焦点，见 `.tmp/global-settings-final-chooser.xml`。该链路由 `MediaFragment` 本地枚举生成 9 个来源，无网络请求；当时该链路与基线未变，未执行基线 APK 对照，不能宣称基线同样复现，也不能将九类编辑页直接启动的测试解释为完整新增路径通过。该问题随后在 [UI 前置修复 R9](2026-10-30-ui-readiness-repairs.md) 中证实为列表视口高度 0，并完成实际主页入口、九项遍历、编辑／返回、长列表及焦点回归；此处保留原始发现，不再作为未解决根因。
