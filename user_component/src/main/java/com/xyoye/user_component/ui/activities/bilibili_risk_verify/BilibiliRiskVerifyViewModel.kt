package com.xyoye.user_component.ui.activities.bilibili_risk_verify

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BilibiliRiskVerifyViewModel : BaseViewModel() {
    data class GeetestConfig(
        val gt: String,
        val challenge: String,
    )

    val geetestConfigLiveData = MutableLiveData<GeetestConfig>()
    val verifySuccessLiveData = MutableLiveData<String>()
    val errorLiveData = MutableLiveData<String>()

    private var repository: BilibiliRepository? = null
    private var registerChallenge: String? = null
    private var registerToken: String? = null

    fun start(
        storageKey: String,
        vVoucher: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            showLoading("加载验证码中…")
            try {
                val repo = BilibiliRepository(storageKey)
                repository = repo

                val data =
                    repo.gaiaVgateRegister(vVoucher)
                        .getOrElse { throwable ->
                            throw throwable
                        }

                val token =
                    data.token?.takeIf { it.isNotBlank() }
                        ?: throw BilibiliException.from(code = -352, message = "验证码获取失败：token 为空")

                val geetest =
                    data.geetest
                        ?: throw BilibiliException.from(code = -352, message = "该风控无法通过验证码解除（geetest=null）")

                val gt =
                    geetest.gt?.takeIf { it.isNotBlank() }
                        ?: throw BilibiliException.from(code = -352, message = "验证码获取失败：gt 为空")

                val challenge =
                    geetest.challenge?.takeIf { it.isNotBlank() }
                        ?: throw BilibiliException.from(code = -352, message = "验证码获取失败：challenge 为空")

                registerToken = token
                registerChallenge = challenge

                geetestConfigLiveData.postValue(
                    GeetestConfig(
                        gt = gt,
                        challenge = challenge,
                    ),
                )
            } catch (e: Exception) {
                errorLiveData.postValue(e.message ?: "验证码获取失败")
            } finally {
                hideLoading()
            }
        }
    }

    fun submitGeetestResult(
        challengeFromJs: String?,
        validate: String,
        seccode: String,
    ) {
        val repo = repository
        val token = registerToken
        val challenge =
            challengeFromJs?.takeIf { it.isNotBlank() }
                ?: registerChallenge

        if (repo == null || token.isNullOrBlank() || challenge.isNullOrBlank()) {
            errorLiveData.postValue("验证码状态异常，请返回重试")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            showLoading("提交验证中…")
            try {
                val griskId =
                    repo.gaiaVgateValidate(
                        challenge = challenge,
                        token = token,
                        validate = validate,
                        seccode = seccode,
                    ).getOrElse { throwable ->
                        throw throwable
                    }
                verifySuccessLiveData.postValue(griskId)
            } catch (e: Exception) {
                errorLiveData.postValue(e.message ?: "验证失败")
            } finally {
                hideLoading()
            }
        }
    }
}

