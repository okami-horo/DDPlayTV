# 日志与异常上下文脱敏策略

源码核对日期：2026-10-28。本文维护统一脱敏入口和已实现的行为；日志开关、服务端点与采集流程见 [日志维护指南](../monitoring/logging-system.md)。

## 统一入口及覆盖边界

- [SensitiveDataSanitizer](../../core_log_component/src/main/java/com/xyoye/common_component/log/privacy/SensitiveDataSanitizer.kt)：结构化字段、URL、路径、Cookie 和自由文本的统一处理。
- [LogFormatter](../../core_log_component/src/main/java/com/xyoye/common_component/log/LogFormatter.kt)：输出前处理 message、context 和渲染的 throwable 堆栈；HTTP/文件日志与 logcat 使用对应格式化入口。
- [ErrorReportHelper](../../core_log_component/src/main/java/com/xyoye/common_component/utils/ErrorReportHelper.kt)：处理 tag、extraInfo、结构化 params 与自定义异常 message；忽略正常协程 CancellationException 的错误上报。
- [BuglyReporter](../../core_log_component/src/main/java/com/xyoye/common_component/log/BuglyReporter.kt)：处理 user data、breadcrumb 和主动日志的上下文。

上述覆盖不能表述为“任何异常对象都已彻底脱敏”。BuglyReporter.postCatchedException 仍把传入 Throwable 交给 SDK；ErrorReportHelper 对附加字段的处理不等于重新构造并清洗原 Throwable/cause 的全部内容。调用方不得把凭证放入异常 message，也不得绕过统一入口输出原始响应。

## 已实现规则

### 结构化字段

context/params 的 key 大小写不敏感。敏感键由 SensitiveDataSanitizer.sensitiveKeys 维护，命中后整个值替换为 &lt;redacted&gt;，包括：

- token/access_token/refresh_token，authorization/proxy-authorization，cookie/set-cookie。
- password/passwd，secret/appsecret/app_sec/appsec，appkey 及 x-appid/x-appsecret/x-appkey/x-appsec。
- Bilibili/验证字段 v_voucher、gt、challenge、validate、seccode、grisk_id、csrf。

完整列表以源码为准。非敏感字段仍会经过 URL、地址、路径或自由文本处理，而不是一律原样保留。

### Header 与 Cookie

sanitizeHeader 是专门入口，规则与结构化 map 不完全相同：Authorization、Proxy-Authorization、Set-Cookie 及部分应用鉴权头会整体遮蔽；Cookie 保留 cookie 名称并遮蔽各值。其他 header 使用自由文本规则。因此不要假定任意自定义 header 名都自动命中 sensitiveKeys；新增敏感头时统一补充规则和回归验证。

### URL、磁力链、地址与路径

| 输入 | 默认输出 |
| --- | --- |
| 常规 URL，SAFE 模式 | scheme、host、port、path；移除 query/fragment |
| URL，KEYS_ONLY 模式 | 保留 query key，所有 query value 遮蔽；移除 fragment |
| magnet | 仅保留 btih hash；无法提取时输出遮蔽值 |
| 本地路径 | 文件名与原值 SHA-256 前 8 位指纹，不输出完整目录 |
| 结构化 address/host/ip 类字段 | IPv4 遮蔽末段，其他 host 使用遮蔽值和指纹；可保留端口 |

自由文本处理包含已知 Bearer、JSON/查询敏感键、平台 Cookie、常见本地路径、URL 与 magnet 模式。这是模式匹配兜底，不保证识别所有自定义格式、路径或任意未知敏感键。优先使用结构化上下文和已明确语义的处理方法，不依赖事后正则替代日志设计。

## HTTP 日志服务

[HttpRequestAuth](../../core_log_component/src/main/java/com/xyoye/common_component/log/http/auth/HttpRequestAuth.kt) 要求有效 token，同时限制来源为 loopback、site-local 或 IPv6 ULA：

- 优先读取合法 Authorization: Bearer 值；未解析到 Bearer 时回退到 token 查询参数。
- token 为空、错误或来源不符合限制时返回未授权。
- 优先通过 Header 传递 token，减少凭证进入 URL 历史或其他访问日志的机会。
- 对外返回及落盘应保持脱敏；不要通过关闭脱敏来排查鉴权或取流问题。

## 维护要求

新增敏感字段在统一工具中维护，分别考虑结构化数据、header、自由文本和异常上报的实际入口，并补充能暴露泄漏的回归样例。业务日志使用 LogFacade 与可定位的结构化字段；URL 默认 SAFE，路径使用文件名/指纹，不拼接完整 Cookie、token、账号配置或原始响应。
