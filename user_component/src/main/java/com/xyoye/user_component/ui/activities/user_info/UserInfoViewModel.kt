package com.xyoye.user_component.ui.activities.user_info

import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.UserInfoHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.LoginData
import kotlinx.coroutines.launch

class UserInfoViewModel : BaseViewModel() {
    val userAccountField = ObservableField<String>()
    val userScreenNameField = ObservableField<String>()

    val updatePasswordLiveData = MutableLiveData<String>()
    val updateScreenNameLiveData = MutableLiveData<String>()

    fun applyLoginData(loginData: LoginData) {
        try {
            userAccountField.set(loginData.userName)
            userScreenNameField.set(loginData.screenName)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "UserInfoViewModel",
                "applyLoginData",
                "Failed to apply login data for user: ${loginData.userName}",
            )
        }
    }

    fun updateScreenName(screenName: String) {
        viewModelScope.launch {
            try {
                showLoading()
                val result = UserRepository.updateScreenName(screenName)
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    exception?.let {
                        ErrorReportHelper.postCatchedExceptionWithContext(
                            it,
                            "UserInfoViewModel",
                            "updateScreenName",
                            "Update screen name network request failed for user: ${UserInfoHelper.mLoginData?.userName}, new name: $screenName",
                        )
                    }
                    result.exceptionOrNull()?.message?.toastError()
                    return@launch
                }

                UserInfoHelper.mLoginData?.screenName = screenName
                UserInfoHelper.updateLoginInfo()
                updateScreenNameLiveData.postValue(screenName)
                ToastCenter.showSuccess("修改昵称成功")
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "UserInfoViewModel",
                    "updateScreenName",
                    "Unexpected error during screen name update for user: ${UserInfoHelper.mLoginData?.userName}",
                )
                ToastCenter.showError("修改昵称过程中发生错误，请稍后再试")
            }
        }
    }

    fun updatePassword(
        oldPassword: String,
        newPassword: String
    ) {
        viewModelScope.launch {
            try {
                showLoading()
                val result = UserRepository.updatePassword(oldPassword, newPassword)
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    exception?.let {
                        ErrorReportHelper.postCatchedExceptionWithContext(
                            it,
                            "UserInfoViewModel",
                            "updatePassword",
                            "Update password network request failed for user: ${UserInfoHelper.mLoginData?.userName}",
                        )
                    }
                    result.exceptionOrNull()?.message?.toastError()
                    return@launch
                }

                val userAccount = UserInfoHelper.mLoginData?.userName.orEmpty()
                UserInfoHelper.exitLogin()
                updatePasswordLiveData.postValue(userAccount)
                ToastCenter.showSuccess("修改密码成功")
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "UserInfoViewModel",
                    "updatePassword",
                    "Unexpected error during password update for user: ${UserInfoHelper.mLoginData?.userName}",
                )
                ToastCenter.showError("修改密码过程中发生错误，请稍后再试")
            }
        }
    }
}
