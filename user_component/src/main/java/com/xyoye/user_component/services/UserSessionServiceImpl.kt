package com.xyoye.user_component.services

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.services.UserSessionService
import com.xyoye.common_component.utils.UserInfoHelper
import com.xyoye.data_component.data.LoginData

@Route(path = RouteTable.User.UserSessionService, name = "用户登录态恢复 Service")
class UserSessionServiceImpl : UserSessionService {
    override fun init(context: Context?) {
    }

    override suspend fun refreshTokenAndLogin(): LoginData? {
        val loginData = UserRepository.refreshToken().getOrNull() ?: return null
        return if (UserInfoHelper.login(loginData)) loginData else null
    }
}

