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

    // 弹弹play Res
    const val DAN_DAN_RES = "http://res.acplay.net/"

    // 百度 OAuth（设备码/刷新 token 等）
    const val BAIDU_OAUTH = "https://openapi.baidu.com/"

    // 百度网盘 OpenAPI（xpan 等）
    const val BAIDU_PAN = "https://pan.baidu.com/"

    // 115 Open API（proapi）
    const val OPEN_115_PRO_API = "https://proapi.115.com/"

    // 115 Open OAuth/刷新（passportapi）
    const val OPEN_115_PASSPORT_API = "https://passportapi.115.com/"

    // Thunder字幕
    const val THUNDER_SUB = "http://sub.xmp.sandai.net:8000/subxl/"

    // Shooter字幕
    const val SHOOTER_SUB = "https://www.shooter.cn/api/subapi.php/"

    // Shooter（伪）字幕
    const val ASSRT_SUB = "http://api.assrt.net/"

    // BiliBili
    const val BILI_BILI_M = "https://m.bilibili.com/"

    // BiliBili API
    const val BILI_BILI_API = "https://api.bilibili.com/"

    // BiliBili弹幕
    const val BILI_BILI_COMMENT = "http://comment.bilibili.com/"

    // HanLP
    const val HAN_LP = "https://www.hanlp.com/"

    // Media3 internal gateway
    //
    // NOTE: 本项目当前不使用 Media3 网关能力，避免在公网环境触发 DNS 解析失败上报。
    // 需要恢复时再引入对应的可访问网关地址与调用链路。
    //
    // const val MEDIA3 = "https://player-component.internal/api/"
}
