package com.xyoye.dandanplay.app

import android.content.Context
import androidx.multidex.MultiDex
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.media3.Media3CrashTagger
import com.xyoye.common_component.log.LogSystem

/**
 * Created by xyoye on 2020/7/27.
 */

class IApplication : BaseApplication(){

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        LogSystem.init(this)
        LogSystem.loadPolicyFromStorage()
        super.onCreate()
        Media3CrashTagger.init()
    }
}
