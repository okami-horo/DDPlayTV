package com.xyoye.common_component.base.app

import android.app.Application
import android.content.Context
import android.os.Handler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.alibaba.android.arouter.launcher.ARouter
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.BuildConfig
import com.xyoye.common_component.log.AppLogger
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.SecurityHelperConfig
import com.xyoye.common_component.utils.aliyun.EMASHelper
import com.xyoye.open_cc.OpenCCFile

/**
 * Created by xyoye on 2020/4/13.
 */

open class BaseApplication : Application(), ImageLoaderFactory {
    companion object {

        private var APPLICATION_CONTEXT: Application? = null
        private var mMainHandler: Handler? = null

        fun getAppContext(): Context {
            return APPLICATION_CONTEXT!!
        }

        fun getMainHandler(): Handler {
            return mMainHandler!!
        }
    }

    override fun onCreate() {
        super.onCreate()

        DDLog.i("APP-Init", "application onCreate start process=${android.os.Process.myPid()}")

        APPLICATION_CONTEXT = this
        mMainHandler = Handler(getAppContext().mainLooper)

        AppLogger.init(this)
        DDLog.i(
            "APP-Init",
            "app logger ready external=${getExternalFilesDir(null)?.absolutePath ?: "null"}"
        )

        if (BuildConfig.DEBUG) {
            ARouter.openLog()
            ARouter.openDebug()
            DDLog.i("APP-Init", "router debug mode enabled")
        }
        MMKV.initialize(this)
        ARouter.init(this)
        CrashReport.initCrashReport(
            this,
            SecurityHelperConfig.BUGLY_APP_ID,
            BuildConfig.DEBUG
        )
        Notifications.setupNotificationChannels(this)
        ActivityHelper.instance.init(this)
        EMASHelper.init(this)
        OpenCCFile.init(this)

        DDLog.i("APP-Init", "application onCreate finished")
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}