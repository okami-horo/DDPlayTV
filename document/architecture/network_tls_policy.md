# 网络 TLS 策略

源码核对日期：2026-10-28。本文维护项目的 TLS 配置入口、默认行为与使用约束，不把底层策略能力等同于已经提供对应 UI。

## 默认路径与兼容开关

- 通用客户端和 WebDAV 默认使用严格证书链与主机名校验。
- [MediaLibraryEntity](../../data_component/src/main/java/com/xyoye/data_component/entity/MediaLibraryEntity.kt) 的 webDavAllowInsecureTls 默认 false。
- [WebDavStorageEditDialog](../../storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/WebDavStorageEditDialog.kt) 允许显式选择不安全 TLS；[WebDavStorage](../../core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/WebDavStorage.kt) 按媒体库配置选择客户端，并把同一设置传递给本地播放代理的上游策略。
- Release 不会自动移除这条用户兼容路径，因此不能把 DEBUG 限制网络日志误写成“Release 不可能 trust-all”。

## 策略与实现

策略位于 [OkHttpTlsPolicy](../../core_network_component/src/main/java/com/xyoye/common_component/network/helper/OkHttpTlsPolicy.kt)，由 [OkHttpTlsConfigurer](../../core_network_component/src/main/java/com/xyoye/common_component/network/helper/OkHttpTlsConfigurer.kt) 应用。

| 策略 | 当前行为 |
| --- | --- |
| Strict | 不修改 Builder 的 TLS 配置；从默认安全 Builder 创建时使用平台校验 |
| CustomCaCertificates | 系统默认信任链之外额外接受指定 X.509 CA；支持从 PEM/DER 流或字节解析 |
| PinnedPublicKeys | 安装指定 hostname 的 CertificatePinner，pin 使用 sha256/&lt;base64&gt; 格式；不会替代原有证书链或主机名校验 |
| UnsafeTrustAll | 信任全部证书并忽略主机名校验；受 UnsafeTlsApi 显式 opt-in 保护 |

Strict 不会把已被调用方改成不安全的 Builder 重置为安全状态。调用方应从正确配置的 Builder 创建客户端，避免复用已安装 trust-all TrustManager/HostnameVerifier 的实例。

CA 导入解决自签/私有 CA 的信任问题；公钥 pin 是额外约束，不会独立让未受信任的自签证书通过系统验证。当前这些是底层配置能力，不能据此声称应用已有完整证书导入或 pin 管理界面。

## 调用入口

- [WebDavOkHttpClientFactory](../../core_network_component/src/main/java/com/xyoye/common_component/network/helper/WebDavOkHttpClientFactory.kt)：WebDAV cookie、重定向授权头与 TLS 策略装配；默认 Strict。
- [WebDavOkHttpClient](../../core_network_component/src/main/java/com/xyoye/common_component/network/helper/WebDavOkHttpClient.kt)：共享严格 TLS 客户端。
- [HttpPlayServer](../../core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/HttpPlayServer.kt) / [LocalProxy](../../core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/LocalProxy.kt)：按独立上游上下文选择 STRICT 或 UNSAFE_TRUST_ALL，默认 STRICT；代理租约不能把一条来源的选择扩散到其他来源。
- [UnsafeOkHttpClient](../../core_network_component/src/main/java/com/xyoye/common_component/network/helper/UnsafeOkHttpClient.kt)：已废弃兼容入口；新增调用使用明确策略的客户端工厂。

## 新增调用约束

- 默认保持严格校验；私有 CA 和固定公钥分别按它们的实际验证语义配置。
- 新增 trust-all 路径必须由用户明确选择、给出风险反馈并默认关闭，在代码中显式 opt-in；注解只能提醒开发者，不能代替运行时开关。
- 不因证书失败自动重试 trust-all，也不把切换明文 HTTP 当作等价安全修复。
- 修改媒体库兼容设置时，同时核对浏览请求、播放上游、URL 刷新及多个租约并存，避免安全策略串线。
