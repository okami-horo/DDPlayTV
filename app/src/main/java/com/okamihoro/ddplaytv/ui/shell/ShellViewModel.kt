package com.okamihoro.ddplaytv.ui.shell

import androidx.lifecycle.viewModelScope
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.services.CloudDanmuBlockService
import com.xyoye.common_component.services.UserSessionService
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2020/7/27.
 */

class ShellViewModel : BaseViewModel() {
    private val userSessionService: UserSessionService? by lazy {
        ARouter.getInstance().navigation(UserSessionService::class.java)
    }

    private val cloudDanmuBlockService: CloudDanmuBlockService? by lazy {
        ARouter.getInstance().navigation(CloudDanmuBlockService::class.java)
    }

    fun reLogin() {
        viewModelScope.launch {
            userSessionService?.refreshTokenAndLogin()
        }
    }

    fun initCloudBlockData() {
        viewModelScope.launch {
            cloudDanmuBlockService?.syncIfNeed()
        }
    }
}
