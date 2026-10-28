# 隐私与数据处理说明

更新日期：2026-10-28。本文依据本仓库源码静态核对；具体分发包的配置、合并权限及第三方 SDK 行为可能不同。

## 本地数据

应用保存设置、媒体库配置、播放历史、字幕/弹幕缓存，以及用户配置的存储凭证和第三方登录态，分别位于数据库、键值存储及应用可访问的文件目录。下载、导出或用户选择的媒体可能位于外部存储。

播放交接和进程恢复会保存请求、位置与恢复信息。当前恢复快照使用 Android Keystore 支持的加密机制，不代表全部设置、凭证、缓存或用户文件采用相同加密方式。

## 联网数据

使用弹弹play、网盘、字幕服务或自行配置的远端存储时，会发送完成操作所需的媒体文件名、大小、哈希、搜索词、播放标识、请求头和鉴权信息。服务方也会收到网络请求本身包含的 IP 地址等信息。

弹弹play账户登录入口要求开发者凭证，配置后调用账户 API，提交用户填写的账号、密码及请求签名。不能将其描述为“所有远端用户接口都关闭”。当前 `Media3Repository` 的远端遥测/下载校验兼容入口不发起请求，不代表其他联网功能关闭。

第三方服务独立处理收到的数据，应按实际使用服务查阅其规则。本文不替第三方服务或 SDK 保证无收集、无上传。

## Bugly 与诊断

配置有效 Bugly App ID 时，AndroidX Startup 会初始化腾讯 Bugly。应用可提交崩溃、捕获异常和诊断上下文，可能包含设备、系统、应用版本、堆栈及播放运行态信息。项目对自身提交的上下文和日志脱敏，但不能据此承诺 SDK 采集内容全部受本项目控制。

未配置有效 App ID 时，当前初始化入口跳过 Bugly。启用状态以具体构建配置和运行状态为准，不能只凭 Debug/Release 名称判断。

## 日志与局域网

应用按日志级别和采样策略输出到 logcat。开启 HTTP 日志服务后，会将应用事件与当前进程可读取的 logcat 写入私有 `files/http_logs/`，提供页面、ZIP 和分段下载。访问要求 Token，并限制到实现允许的回环/局域网来源；当前没有 SSE 实时推送接口。

存储档位为 7/14/30 天，对应上限 1/2/4 GiB；空间不足可能降档或暂停持久化。关闭服务停止监听和新采集，不会自动清空历史。开发者设置提供清空历史及重置凭证。

HTTP 下载由持有凭证的客户端主动发起，不是云端自动上传入口；Bugly 与联网服务另按上文处理。脱敏不能替代导出前人工检查。操作见 [日志指南](monitoring/logging-system.md)。

## 权限

以下按当前源码声明说明，最终以分发包合并后的 Manifest 和系统授权为准：

| 权限 | 用途与范围 |
| --- | --- |
| `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` | 联网与网络状态 |
| `READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` | 新版系统媒体读取 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14 起所选视觉媒体访问声明 |
| `READ_EXTERNAL_STORAGE`（最高 API 32） | 旧版媒体/文件读取 |
| `WRITE_EXTERNAL_STORAGE`（最高 API 29） | 旧版写入兼容 |
| `REQUEST_INSTALL_PACKAGES` | 用户选择安装 APK |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 已声明媒体相关前台服务，不能据此推断完整后台播放已实现 |
| `CHANGE_WIFI_MULTICAST_STATE` | 局域网发现 |

当前工程主线未启用 `CAMERA` 声明。声明与实际授权不同，拒绝权限可能使相应功能不可用。

## 数据管理

通过应用设置管理缓存、历史、存储配置及日志。卸载由系统清理私有数据，但不保证清理下载或导出的外部文件。反馈问题只提交最少必要信息。

组件见 [第三方库](Third_Party_Libraries.md)。维护核对入口为 `core_system_component/src/main/AndroidManifest.xml`、`storage_component/src/main/AndroidManifest.xml`、`BuglyInitializer`、`UserRepository`、`HttpLogServerManager`；本文不替代具体发布包的隐私审查。
