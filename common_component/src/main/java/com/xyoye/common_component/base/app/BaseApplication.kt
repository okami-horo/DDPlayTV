package com.xyoye.common_component.base.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.alibaba.android.arouter.launcher.ARouter
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.BuildConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.common_component.utils.SecurityHelperConfig
import com.xyoye.open_cc.OpenCCFile

/**
 * Created by xyoye on 2020/4/13.
 */

open class BaseApplication :
    Application(),
    ImageLoaderFactory {
    companion object {
        @Volatile
        private var APPLICATION_CONTEXT: Application? = null

        @Volatile
        private var mMainHandler: Handler? = null

        fun getAppContext(): Context =
            (APPLICATION_CONTEXT ?: resolveApplicationContext()).applicationContext

        fun getMainHandler(): Handler =
            mMainHandler ?: synchronized(this) {
                mMainHandler ?: Handler(Looper.getMainLooper()).also { handler ->
                    mMainHandler = handler
                }
            }

        private fun resolveApplicationContext(): Application =
            synchronized(this) {
                APPLICATION_CONTEXT ?: runCatching { lookupCurrentApplication() }
                    .getOrNull()
                    ?.also { app ->
                        APPLICATION_CONTEXT = app
                        if (mMainHandler == null) {
                            mMainHandler = Handler(Looper.getMainLooper())
                        }
                    }
                    ?: error("Application context is not ready yet.")
            }

        private fun lookupCurrentApplication(): Application? {
            val activityThread =
                runCatching { Class.forName("android.app.ActivityThread") }.getOrNull()
            val currentApp =
                runCatching {
                    activityThread
                        ?.getMethod("currentApplication")
                        ?.invoke(null) as? Application
                }.getOrNull()
            if (currentApp != null) return currentApp

            val appGlobals =
                runCatching { Class.forName("android.app.AppGlobals") }.getOrNull()
            return runCatching {
                appGlobals
                    ?.getMethod("getInitialApplication")
                    ?.invoke(null) as? Application
            }.getOrNull()
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // 提前初始化全局上下文与主线程Handler，避免在ContentProvider或静态初始化阶段访问导致空指针
        APPLICATION_CONTEXT = this
        mMainHandler = Handler(Looper.getMainLooper())
        // 尽早初始化 Bugly，保证 Application onCreate 之前的崩溃也能被捕获
        CrashReport.initCrashReport(
            this,
            SecurityHelperConfig.BUGLY_APP_ID,
            BuildConfig.DEBUG,
        )
    }

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)

        LogFacade.i(
            LogModule.CORE,
            "APP-Init",
            "application onCreate start process=${android.os.Process.myPid()}",
        )

        if (BuildConfig.DEBUG) {
            ARouter.openLog()
            ARouter.openDebug()
            LogFacade.i(LogModule.CORE, "APP-Init", "router debug mode enabled")
        }
        ARouter.init(this)
        Notifications.setupNotificationChannels(this)
        ActivityHelper.instance.init(this)
        OpenCCFile.init(this)
        SubtitleFontManager.initialize(this)

        LogFacade.i(LogModule.CORE, "APP-Init", "application onCreate finished")
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }.build()
}
