package com.okamihoro.ddplaytv.app

import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.log.LogSystem

/**
 * Created by xyoye on 2020/7/27.
 */

class IApplication : BaseApplication() {
    override fun onCreate() {
        LogSystem.init(this)
        LogSystem.loadPolicyFromStorage()
        super.onCreate()
    }
}
