package com.xyoye.storage_component.ui.dialog.scanlogin

import com.squareup.moshi.JsonDataException
import com.xyoye.common_component.network.request.NetworkException
import org.json.JSONException
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.ParseException
import java.util.Locale
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

object ScanErrorMapper {
    fun map(
        providerId: String,
        step: ScanStep,
        throwable: Throwable,
    ): ScanFailure {
        if (throwable is ScanApiException) {
            return ScanFailure(
                userMessage = throwable.userMessage,
                category = ScanFailureCategory.API,
                debugCode = withPrefix(providerId, step, throwable.debugCode),
                retryable = throwable.retryable,
            )
        }

        if (throwable is ScanConfigException) {
            return ScanFailure(
                userMessage = throwable.userMessage,
                category = ScanFailureCategory.CONFIG,
                debugCode = withPrefix(providerId, step, throwable.debugCode),
                retryable = false,
            )
        }

        if (throwable is NetworkException) {
            val mapped = mapNetworkException(throwable)
            return mapped.copy(debugCode = withPrefix(providerId, step, mapped.debugCode))
        }

        val failure =
            when (throwable) {
                is UnknownHostException ->
                    ScanFailure(
                        userMessage = "网络错误，请检查网络后重试",
                        category = ScanFailureCategory.NETWORK,
                        debugCode = "UNKNOWN_HOST",
                        retryable = true,
                    )

                is SSLHandshakeException ->
                    ScanFailure(
                        userMessage = "证书验证失败，请检查系统时间与网络环境",
                        category = ScanFailureCategory.SSL,
                        debugCode = "SSL_HANDSHAKE",
                        retryable = true,
                    )

                is SocketTimeoutException,
                is TimeoutException ->
                    ScanFailure(
                        userMessage = "网络请求超时，请稍后重试",
                        category = ScanFailureCategory.NETWORK,
                        debugCode = "NETWORK_TIMEOUT",
                        retryable = true,
                    )

                is HttpException ->
                    ScanFailure(
                        userMessage = "服务暂时不可用（HTTP ${throwable.code()}），请稍后重试",
                        category = ScanFailureCategory.HTTP,
                        debugCode = "HTTP_${throwable.code()}",
                        retryable = true,
                    )

                is JsonDataException,
                is JSONException,
                is ParseException,
                is android.util.MalformedJsonException ->
                    ScanFailure(
                        userMessage = "服务返回数据异常，请稍后重试",
                        category = ScanFailureCategory.PARSE,
                        debugCode = "PARSE_ERROR",
                        retryable = true,
                    )

                is CancellationException ->
                    ScanFailure(
                        userMessage = "请求已取消",
                        category = ScanFailureCategory.UNKNOWN,
                        debugCode = "REQUEST_CANCELLED",
                        retryable = true,
                    )

                else ->
                    ScanFailure(
                        userMessage = throwable.message?.ifBlank { "获取二维码失败，请稍后重试" } ?: "获取二维码失败，请稍后重试",
                        category = ScanFailureCategory.UNKNOWN,
                        debugCode = "UNKNOWN",
                        retryable = true,
                    )
            }

        return failure.copy(debugCode = withPrefix(providerId, step, failure.debugCode))
    }

    private fun mapNetworkException(exception: NetworkException): ScanFailure {
        val code = exception.code
        return when {
            code == 1001 ->
                ScanFailure(
                    userMessage = "网络错误，请检查网络后重试",
                    category = ScanFailureCategory.NETWORK,
                    debugCode = "NETWORK_1001",
                    retryable = true,
                )

            code == 1002 || code == 1003 ->
                ScanFailure(
                    userMessage = "网络请求超时，请稍后重试",
                    category = ScanFailureCategory.NETWORK,
                    debugCode = "NETWORK_$code",
                    retryable = true,
                )

            code == 1004 ->
                ScanFailure(
                    userMessage = "证书验证失败，请检查系统时间与网络环境",
                    category = ScanFailureCategory.SSL,
                    debugCode = "NETWORK_1004",
                    retryable = true,
                )

            code in 1005..1008 ->
                ScanFailure(
                    userMessage = "服务返回数据异常，请稍后重试",
                    category = ScanFailureCategory.PARSE,
                    debugCode = "NETWORK_$code",
                    retryable = true,
                )

            code in 400..599 ->
                ScanFailure(
                    userMessage = "服务暂时不可用（HTTP $code），请稍后重试",
                    category = ScanFailureCategory.HTTP,
                    debugCode = "HTTP_$code",
                    retryable = true,
                )

            else -> {
                val raw = exception.message.orEmpty().trim()
                val userMessage = raw.ifBlank { "请求失败，请稍后重试" }
                ScanFailure(
                    userMessage = userMessage,
                    category = ScanFailureCategory.UNKNOWN,
                    debugCode = "NETWORK_$code",
                    retryable = true,
                )
            }
        }
    }

    private fun withPrefix(
        providerId: String,
        step: ScanStep,
        debugCode: String,
    ): String {
        val provider = providerId.trim().ifBlank { "scan" }
        val stepName = step.name.lowercase(Locale.US)
        return "${provider}_${stepName}_$debugCode"
    }
}
