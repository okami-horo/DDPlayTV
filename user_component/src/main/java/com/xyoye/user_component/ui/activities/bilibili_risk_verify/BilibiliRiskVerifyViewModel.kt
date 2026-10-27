package com.xyoye.user_component.ui.activities.bilibili_risk_verify

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.PlaybackVerificationAnswer
import com.xyoye.common_component.playback.handoff.PlaybackVerificationChallenge
import com.xyoye.common_component.playback.handoff.PlaybackVerificationResult
import com.xyoye.common_component.services.PlaybackRequestService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class BilibiliRiskVerifyViewModel : BaseViewModel() {
    val geetestConfigLiveData = MutableLiveData<PlaybackVerificationChallenge>()
    private val results = Channel<PlaybackSubmissionResult>(Channel.CONFLATED)
    val outcomes = results.receiveAsFlow()
    private val provider: PlaybackRequestService? by lazy { ARouter.getInstance().navigation(PlaybackRequestService::class.java) }
    private var verificationId: String? = null
    private var completed = false
    private var submitting = false

    fun start(id: String) {
        if (verificationId == id) return
        verificationId = id
        viewModelScope.launch {
            showLoading("加载验证码中…")
            try {
                when (val result = provider?.verificationChallenge(id)) {
                    is PlaybackVerificationResult.Ready -> geetestConfigLiveData.value = result.challenge
                    is PlaybackVerificationResult.Rejected -> results.trySend(PlaybackSubmissionResult.Rejected(result.failure))
                    null -> results.trySend(PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.UnsupportedSource))
                }
            } finally {
                hideLoading()
            }
        }
    }

    fun submitGeetestResult(
        challengeFromJs: String?,
        validate: String,
        seccode: String
    ) {
        viewModelScope.launch {
            val id = verificationId ?: return@launch
            if (submitting || completed) return@launch
            submitting = true
            showLoading("提交验证中…")
            try {
                val result =
                    provider?.completeVerification(id, PlaybackVerificationAnswer(challengeFromJs, validate, seccode))
                        ?: PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
                completed = result is PlaybackSubmissionResult.Accepted
                results.trySend(result)
            } finally {
                submitting = false
                hideLoading()
            }
        }
    }

    fun cancel() {
        if (completed) return
        val id = verificationId ?: return
        viewModelScope.launch(NonCancellable) { provider?.cancelVerification(id) }
    }
}
