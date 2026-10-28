# 项目本地开发环境

核对日期：2026-10-28。权威配置见 [Versions.kt](../buildSrc/src/main/java/Versions.kt)、[Gradle Wrapper](../gradle/wrapper/gradle-wrapper.properties) 和各模块构建脚本。

## 工程要求

当前使用 JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.2，compile/target SDK 35、min SDK 21。公共 NDK 为 `25.2.9519653`，播放器 CMake 最低为 `3.22.1`；旧文档所称公共 NDK `23.1.7779620` 已失效。

本机项目私有环境相对仓库根目录如下：

| 内容 | 路径 |
| --- | --- |
| JDK | `.dev-env/jdk/ms-17.0.15/` |
| SDK | `.dev-env/android-sdk/` |
| Gradle 用户目录 | `.dev-env/gradle-home/` |
| 包装脚本 | `scripts/dev-env/gradlew-local.bat`、`.ps1` |

本次目录检查确认 SDK 有 Platform 35、Build Tools `34.0.0`/`35.0.0`、NDK `25.2.9519653` 和 CMake `3.22.1`。这是本机状态；`.dev-env/` 被忽略，克隆仓库不会获得这些工具。

## 构建

Windows 可使用批处理包装脚本，在当前进程注入项目环境：

```bat
scripts\dev-env\gradlew-local.bat :app:assembleDebug
```

允许执行本地 PowerShell 脚本的环境可运行：

```powershell
.\scripts\dev-env\gradlew-local.ps1 :app:assembleDebug
```

若执行策略阻止 `.ps1`，可在当前 PowerShell 设置环境后使用 Wrapper，无需修改系统策略：

```powershell
$projectRoot = (Get-Location).Path
$env:JAVA_HOME = Join-Path $projectRoot '.dev-env\jdk\ms-17.0.15'
$env:ANDROID_HOME = Join-Path $projectRoot '.dev-env\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.dev-env\gradle-home'
.\gradlew.bat :app:assembleDebug
```

只有依赖已缓存时才能添加 `--offline`。直接使用根目录 Wrapper 时，自行保证 shell 的 JDK、SDK 与 `local.properties` 一致。

## 环境准备与迁移

从 `local.properties.template` 创建本机配置，将 `sdk.dir` 指向真实 SDK；保留已有凭证，不复制他人的绝对路径。迁移后重新核对该路径。

`setup-local-env.ps1` 保存了本机复制/安装工具的意图，但不是已验证的一键重建入口：默认源路径依赖原机器，当前 `param(...)` 位于赋值语句后，需先修正参数块位置；脚本也只在 `local.properties` 已存在时更新 `sdk.dir`。本次未执行或修改此安装脚本。准备环境按工程版本安装工具，脚本修复另行跟踪。

## 模拟器

使用同一 SDK 的 `adb devices -l`、`emulator -list-avds` 核对实时状态，不把旧设备编号当作当前基线。`scripts/dev-env/start-avd.bat`/`.sh` 可辅助启动；先检查同一 AVD 是否已经运行，避免重复启动和无界日志重定向。

仪器测试要求设备为 `device` 且 `sys.boot_completed=1`。当前未提供 x86/x86_64 的播放器原生库，x86 AVD 对 ARM 内核的可用性取决于系统镜像与转换能力，APK 安装成功不能证明播放可用。检查 Gradle 尾部并报告 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。
