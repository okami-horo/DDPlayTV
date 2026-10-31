package com.xyoye.common_component.network.config

/**
 * Created by xyoye on 2024/1/7.
 */
object Api {
    // 占位
    const val PLACEHOLDER = "http://127.0.0.1:80/"

    // 弹弹play Open
    const val DAN_DAN_OPEN = "https://api.dandanplay.net/"

    // 弹弹play Open 备用
    const val DAN_DAN_SPARE = "http://139.217.235.62:16001/"

    // 百度账号体系（设备码/刷新 token 等）
    const val BAIDU_ACCOUNT_API = "https://openapi.baidu.com/"

    // 百度网盘 OpenAPI（xpan 等）
    const val BAIDU_PAN = "https://pan.baidu.com/"

    // 115 Open API（proapi）
    const val OPEN_115_PRO_API = "https://proapi.115.com/"

    // 115 Open 令牌服务（passportapi）
    const val OPEN_115_PASSPORT_API = "https://passportapi.115.com/"

    // 115 Cloud（扫码/状态校验/列表/播放直链等）
    const val CLOUD_115_QRCODE_API = "https://qrcodeapi.115.com/"
    const val CLOUD_115_MY = "https://my.115.com/"
    const val CLOUD_115_WEB_API = "https://webapi.115.com/"
    const val CLOUD_115_PRO_API = "https://proapi.115.com/"
    const val CLOUD_115_PASSPORT_API = "https://passportapi.115.com/"

    // Thunder字幕
    const val THUNDER_SUB = "http://sub.xmp.sandai.net:8000/subxl/"

    // Shooter字幕
    const val SHOOTER_SUB = "https://www.shooter.cn/api/subapi.php/"

    // Shooter（伪）字幕
    const val ASSRT_SUB = "http://api.assrt.net/"

    // HanLP
    const val HAN_LP = "https://www.hanlp.com/"

    @JvmStatic
    fun securitySensitiveEndpoints(): Map<String, String> = SECURITY_SENSITIVE_ENDPOINTS

    @JvmStatic
    fun insecureSecuritySensitiveEndpoints(): List<String> =
        SECURITY_SENSITIVE_ENDPOINTS
            .filterValues { endpoint -> !endpoint.startsWith("https://") }
            .map { (name, endpoint) -> "$name=$endpoint" }

    private val SECURITY_SENSITIVE_ENDPOINTS: Map<String, String> =
        linkedMapOf(
            "baidu_account" to BAIDU_ACCOUNT_API,
            "baidu_pan" to BAIDU_PAN,
            "open115_passport" to OPEN_115_PASSPORT_API,
            "cloud115_passport" to CLOUD_115_PASSPORT_API,
        )

    // Media3 internal gateway
    //
    // NOTE: 本项目当前不使用 Media3 网关能力，避免在公网环境触发 DNS 解析失败上报。
    // 需要恢复时再引入对应的可访问网关地址与调用链路。
    //
    // const val MEDIA3 = "https://player-component.internal/api/"
}
