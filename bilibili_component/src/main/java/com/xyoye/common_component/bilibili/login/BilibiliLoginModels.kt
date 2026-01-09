package com.xyoye.common_component.bilibili.login

data class BilibiliLoginQrCode(
    val url: String,
    val qrcodeKey: String,
)

sealed class BilibiliLoginPollResult {
    data object WaitingScan : BilibiliLoginPollResult()
    data object WaitingConfirm : BilibiliLoginPollResult()
    data object Expired : BilibiliLoginPollResult()
    data object Success : BilibiliLoginPollResult()
    data class Error(
        val message: String,
    ) : BilibiliLoginPollResult()
}

