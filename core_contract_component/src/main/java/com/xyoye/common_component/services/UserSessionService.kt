package com.xyoye.common_component.services

import com.alibaba.android.arouter.facade.template.IProvider
import com.xyoye.data_component.data.LoginData

/**
 * 用户登录态恢复 / token 续期能力
 *
 * 壳层通过该 Service 解耦对 user/network 实现的直接依赖。
 */
interface UserSessionService : IProvider {
    /**
     * 尝试刷新 token 并恢复登录态；成功返回最新 LoginData，否则返回 null。
     */
    suspend fun refreshTokenAndLogin(): LoginData?
}

