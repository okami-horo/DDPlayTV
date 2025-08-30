package com.xyoye.user_component.ui.activities.forgot

import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SecurityHelper
import com.xyoye.common_component.weight.ToastCenter
import kotlinx.coroutines.launch

class ForgotViewModel : BaseViewModel() {

    val isForgotPassword = ObservableField<Boolean>()

    val accountField = ObservableField("")
    val emailField = ObservableField("")

    val accountErrorLiveData = MutableLiveData<String>()
    val emailErrorLiveData = MutableLiveData<String>()
    val requestLiveData = MutableLiveData<Boolean>()

    fun confirm() {
        if (isForgotPassword.get() == true)
            resetPassword()
        else
            retrieveAccount()
    }

    private fun resetPassword() {
        try {
            val account = accountField.get()
            val email = emailField.get()

            val allowReset = checkAccount(account) && checkEmail(email)
            if (!allowReset)
                return

            val appId = SecurityHelper.getInstance().appId
            val unixTimestamp = System.currentTimeMillis() / 1000
            val hashInfo = appId + email + unixTimestamp + account
            val hash = SecurityHelper.getInstance().buildHash(hashInfo)

            viewModelScope.launch {
                try {
                    showLoading()
                    val result = UserRepository.resetPassword(
                        account!!,
                        email!!,
                        appId,
                        unixTimestamp.toString(),
                        hash
                    )
                    hideLoading()

                    if (result.isFailure) {
                        val exception = result.exceptionOrNull()
                        exception?.let {
                            ErrorReportHelper.postCatchedExceptionWithContext(
                                it,
                                "ForgotViewModel",
                                "resetPassword",
                                "Reset password network request failed for user: $account, email: $email"
                            )
                        }
                        result.exceptionOrNull()?.message?.toastError()
                        return@launch
                    }

                    ToastCenter.showSuccess("重置成功，密码已发送至邮箱")
                    requestLiveData.postValue(true)
                } catch (e: Exception) {
                    hideLoading()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "ForgotViewModel",
                        "resetPassword",
                        "Unexpected error during password reset for user: $account"
                    )
                    ToastCenter.showError("密码重置过程中发生错误，请稍后再试")
                }
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ForgotViewModel",
                "resetPassword",
                "Error in resetPassword method initialization"
            )
            ToastCenter.showError("密码重置初始化失败，请稍后再试")
        }
    }

    private fun retrieveAccount() {
        try {
            val email = emailField.get()

            val allowRetrieve = checkEmail(email)
            if (!allowRetrieve)
                return

            val appId = SecurityHelper.getInstance().appId
            val unixTimestamp = System.currentTimeMillis() / 1000
            val hashInfo = appId + email + unixTimestamp
            val hash = SecurityHelper.getInstance().buildHash(hashInfo)

            viewModelScope.launch {
                try {
                    showLoading()
                    val result = UserRepository.retrieveAccount(
                        email!!,
                        appId,
                        unixTimestamp.toString(),
                        hash
                    )
                    hideLoading()

                    if (result.isFailure) {
                        val exception = result.exceptionOrNull()
                        exception?.let {
                            ErrorReportHelper.postCatchedExceptionWithContext(
                                it,
                                "ForgotViewModel",
                                "retrieveAccount",
                                "Retrieve account network request failed for email: $email"
                            )
                        }
                        result.exceptionOrNull()?.message?.toastError()
                        return@launch
                    }

                    ToastCenter.showSuccess("验证成功，帐号已发送至邮箱")
                    requestLiveData.postValue(true)
                } catch (e: Exception) {
                    hideLoading()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "ForgotViewModel",
                        "retrieveAccount",
                        "Unexpected error during account retrieval for email: $email"
                    )
                    ToastCenter.showError("账号找回过程中发生错误，请稍后再试")
                }
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ForgotViewModel",
                "retrieveAccount",
                "Error in retrieveAccount method initialization"
            )
            ToastCenter.showError("账号找回初始化失败，请稍后再试")
        }
    }

    private fun checkAccount(account: String?): Boolean {
        if (account.isNullOrEmpty()) {
            accountErrorLiveData.postValue("请输入帐号")
            return false
        }
        return true
    }

    private fun checkEmail(email: String?): Boolean {
        if (email.isNullOrEmpty()) {
            emailErrorLiveData.postValue("请输入邮箱")
            return false
        }
        return true
    }
}