package com.xyoye.common_component.bilibili.error

import com.xyoye.common_component.network.request.PassThroughException
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel

class BilibiliException(
    val code: Int,
    val bilibiliMessage: String? = null,
    val hint: String? = null
) : RuntimeException(buildMessage(code, bilibiliMessage, hint)),
    PassThroughException {
    companion object {
        fun from(model: BilibiliJsonModel<*>): BilibiliException =
            from(
                code = model.code,
                message = model.message,
            )

        fun from(
            code: Int,
            message: String? = null
        ): BilibiliException {
            val hint =
                when (code) {
                    -101 -> "账号未登录或登录已失效，请扫码登录"
                    -400 -> "请求参数错误"
                    -403 -> "权限不足或访问受限"
                    -404 -> "资源不存在或已下架"
                    -352 -> "风控校验失败，可能需要进行验证（如验证码）"
                    -351 -> "请求被拦截（风控），请稍后重试"
                    -412 -> "请求被拦截（风控），请稍后重试"
                    -509 -> "请求过于频繁，请稍后重试"
                    else -> null
                }
            return BilibiliException(code, message, hint)
        }

        private fun buildMessage(
            code: Int,
            message: String?,
            hint: String?
        ): String {
            val base = hint ?: "Bilibili 请求失败"
            val detail = message?.takeIf { it.isNotBlank() }
            return if (detail.isNullOrEmpty()) {
                "$base（code=$code）"
            } else {
                "$base：$detail（code=$code）"
            }
        }
    }
}
