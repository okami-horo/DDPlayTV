package com.xyoye.storage_component.ui.dialog

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import androidx.core.view.isVisible
import com.xyoye.common_component.config.BaiduPanOpenApiConfig
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.common_component.utils.QrCodeHelper
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanDeviceCodeResponse
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanOAuthError
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanTokenResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanUinfoResponse
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogBaiduPanLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BaiduPanLoginDialog(
    private val activity: Activity,
    private val onLoginSuccess: (token: BaiduPanTokenResponse, uinfo: BaiduPanUinfoResponse) -> Unit,
    private val onDismiss: (() -> Unit)? = null
) : BaseBottomDialog<DialogBaiduPanLoginBinding>(activity) {
    private lateinit var binding: DialogBaiduPanLoginBinding

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingJob: Job? = null

    override fun getChildLayoutId(): Int = R.layout.dialog_baidu_pan_login

    override fun initView(binding: DialogBaiduPanLoginBinding) {
        this.binding = binding

        setTitle("百度网盘扫码授权")
        setPositiveText("重试")
        setNegativeText("取消")
        setDialogCancelable(touchCancel = false, backPressedCancel = true)

        setPositiveListener { startLoginFlow() }
        setNegativeListener { dismiss() }

        setOnDismissListener {
            pollingJob?.cancel()
            scope.cancel()
            onDismiss?.invoke()
        }

        startLoginFlow()
    }

    private fun startLoginFlow() {
        pollingJob?.cancel()
        pollingJob =
            scope.launch {
                if (!BaiduPanOpenApiConfig.isConfigured()) {
                    binding.loadingPb.isVisible = false
                    binding.statusTv.text = "未配置百度网盘开放平台密钥"
                    return@launch
                }

                binding.loadingPb.isVisible = true
                binding.statusTv.text = "正在获取二维码…"

                val deviceCode = fetchDeviceCode().getOrNull()
                if (deviceCode == null || deviceCode.deviceCode.isBlank() || deviceCode.userCode.isBlank()) {
                    binding.loadingPb.isVisible = false
                    binding.statusTv.text = "获取二维码失败，请稍后重试"
                    return@launch
                }

                val qrContent = buildQrCodeContent(deviceCode)
                val qrCode =
                    QrCodeHelper.createQrCode(
                        context = activity,
                        content = qrContent,
                        sizePx = dp2px(220),
                        logoResId = R.mipmap.ic_logo,
                        bitmapColor = com.xyoye.core_ui_component.R.color.text_black.toResColor(activity),
                        errorContext = "生成百度网盘授权二维码失败",
                    )
                binding.qrCodeIv.setImageBitmap(qrCode)
                binding.loadingPb.isVisible = false
                binding.statusTv.text = "请使用百度网盘 App 扫码授权"

                pollUntilDone(deviceCode)
            }
    }

    private fun buildQrCodeContent(deviceCode: BaiduPanDeviceCodeResponse): String {
        val userCode = deviceCode.userCode.trim()
        val verificationUrl = deviceCode.verificationUrl?.trim()
        val baseUrl = verificationUrl?.takeIf { it.isNotEmpty() } ?: "https://openapi.baidu.com/device"
        return Uri.parse(baseUrl)
            .buildUpon()
            .appendQueryParameter("display", "mobile")
            .appendQueryParameter("code", userCode)
            .build()
            .toString()
    }

    private suspend fun pollUntilDone(deviceCode: BaiduPanDeviceCodeResponse) {
        val start = SystemClock.elapsedRealtime()
        val expiresAt = start + deviceCode.expiresIn.coerceAtLeast(1) * 1000L

        var intervalMs =
            TimeUnit.SECONDS.toMillis(
                deviceCode.interval.coerceAtLeast(5).toLong(),
            )

        while (scope.coroutineContext[Job]?.isActive == true) {
            if (SystemClock.elapsedRealtime() >= expiresAt) {
                binding.statusTv.text = "二维码已过期，请点击重试"
                return
            }

            val outcome = fetchTokenByDeviceCode(deviceCode.deviceCode).getOrNull()
            if (outcome == null) {
                binding.statusTv.text = "登录状态获取失败，请检查网络后重试"
                return
            }

            when (outcome) {
                is OauthTokenOutcome.Success -> {
                    binding.loadingPb.isVisible = true
                    binding.statusTv.text = "授权成功，正在获取账号信息…"

                    val uinfo = fetchUinfo(outcome.token.accessToken).getOrNull()
                    if (uinfo == null) {
                        binding.loadingPb.isVisible = false
                        binding.statusTv.text = "获取账号信息失败，请稍后重试"
                        return
                    }
                    if (uinfo.errno != 0) {
                        binding.loadingPb.isVisible = false
                        binding.statusTv.text = "获取账号信息失败：${uinfo.errmsg ?: "未知错误"}（errno=${uinfo.errno}）"
                        return
                    }
                    val uk = uinfo.uk ?: 0L
                    if (uk <= 0) {
                        binding.loadingPb.isVisible = false
                        binding.statusTv.text = "获取账号信息失败：uk 无效"
                        return
                    }

                    binding.statusTv.text = "授权成功"
                    delay(300)
                    dismiss()
                    onLoginSuccess.invoke(outcome.token, uinfo)
                    return
                }

                is OauthTokenOutcome.Pending -> {
                    binding.statusTv.text = outcome.message
                }

                is OauthTokenOutcome.TerminalError -> {
                    binding.statusTv.text = outcome.message
                    return
                }

                is OauthTokenOutcome.SlowDown -> {
                    binding.statusTv.text = outcome.message
                    intervalMs = (intervalMs + TimeUnit.SECONDS.toMillis(5)).coerceAtMost(TimeUnit.SECONDS.toMillis(30))
                }
            }

            delay(intervalMs)
        }
    }

    private suspend fun fetchDeviceCode(): Result<BaiduPanDeviceCodeResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    Retrofit.baiduPanService.oauthDeviceCode(
                        baseUrl = Api.BAIDU_OAUTH,
                        responseType = "device_code",
                        clientId = BaiduPanOpenApiConfig.clientId,
                        scope = DEFAULT_SCOPE,
                    )
                val payload =
                    response.body()?.string()
                        ?: response.errorBody()?.string()
                        ?: ""

                val oauthError = JsonHelper.parseJson<BaiduPanOAuthError>(payload)
                if (oauthError != null && oauthError.error.isNullOrBlank().not()) {
                    throw IllegalStateException(formatOauthMessage(oauthError.error.orEmpty(), oauthError.errorDescription))
                }

                JsonHelper.parseJson<BaiduPanDeviceCodeResponse>(payload)
                    ?: throw IllegalStateException("Unexpected device_code payload: code=${response.code()} payload=$payload")
            }
        }

    private suspend fun fetchTokenByDeviceCode(deviceCode: String): Result<OauthTokenOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    Retrofit.baiduPanService.oauthToken(
                        baseUrl = Api.BAIDU_OAUTH,
                        grantType = "device_token",
                        code = deviceCode,
                        refreshToken = null,
                        clientId = BaiduPanOpenApiConfig.clientId,
                        clientSecret = BaiduPanOpenApiConfig.clientSecret,
                    )
                val payload =
                    response.body()?.string()
                        ?: response.errorBody()?.string()
                        ?: ""

                val oauthError = JsonHelper.parseJson<BaiduPanOAuthError>(payload)
                if (oauthError != null && oauthError.error.isNullOrBlank().not()) {
                    val error = oauthError.error?.trim().orEmpty()
                    val message = formatOauthMessage(error, oauthError.errorDescription)
                    return@runCatching when (error) {
                        "authorization_pending" -> OauthTokenOutcome.Pending(message)
                        "slow_down" -> OauthTokenOutcome.SlowDown(message)
                        "access_denied" -> OauthTokenOutcome.TerminalError(message)
                        "expired_token" -> OauthTokenOutcome.TerminalError(message)
                        "invalid_client" -> OauthTokenOutcome.TerminalError(message)
                        "invalid_grant" -> OauthTokenOutcome.TerminalError(message)
                        else -> OauthTokenOutcome.TerminalError(message)
                    }
                }

                val token = JsonHelper.parseJson<BaiduPanTokenResponse>(payload)
                if (token == null || token.accessToken.isBlank() || token.refreshToken.isBlank() || token.expiresIn <= 0) {
                    throw IllegalStateException("Unexpected token payload: code=${response.code()} payload=$payload")
                }

                OauthTokenOutcome.Success(token)
            }
        }

    private suspend fun fetchUinfo(accessToken: String): Result<BaiduPanUinfoResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                Retrofit.baiduPanService.xpanUinfo(
                    baseUrl = Api.BAIDU_PAN,
                    method = "uinfo",
                    accessToken = accessToken,
                    vipVersion = "v2",
                )
            }
        }

    private fun formatOauthMessage(
        error: String,
        description: String?
    ): String {
        val hint =
            when (error) {
                "authorization_pending" -> "等待用户扫码确认"
                "slow_down" -> "请求过于频繁，请稍后重试"
                "access_denied" -> "用户拒绝授权"
                "expired_token" -> "二维码已过期，请重新获取"
                "invalid_client" -> "百度网盘开放平台密钥无效"
                "invalid_grant" -> "授权已失效，请重新扫码授权"
                else -> "百度网盘授权失败"
            }

        val detail = description?.takeIf { it.isNotBlank() }
        return if (detail.isNullOrEmpty()) {
            "$hint（error=$error）"
        } else {
            "$hint：$detail（error=$error）"
        }
    }

    private sealed class OauthTokenOutcome {
        data class Success(val token: BaiduPanTokenResponse) : OauthTokenOutcome()

        data class Pending(val message: String) : OauthTokenOutcome()

        data class SlowDown(val message: String) : OauthTokenOutcome()

        data class TerminalError(val message: String) : OauthTokenOutcome()
    }

    private companion object {
        private const val DEFAULT_SCOPE = "basic,netdisk"
    }
}
