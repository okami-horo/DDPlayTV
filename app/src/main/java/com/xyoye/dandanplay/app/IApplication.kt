package com.xyoye.dandanplay.app

import android.content.Context
import androidx.multidex.MultiDex
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.media3.Media3CrashTagger

/**
 * Created by xyoye on 2020/7/27.
 */

class IApplication : BaseApplication(){

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
        Media3CrashTagger.init()
    }
}
