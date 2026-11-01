package com.xyoye.common_component.base.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.alibaba.android.arouter.launcher.ARouter
import com.alibaba.android.arouter.utils.Consts
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.core_system_component.BuildConfig

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

        private const val APP_INIT_TAG = "APP-Init"
        private const val ROUTER_UPDATE_TIME = "ddplaytv_last_update_time"

        fun getAppContext(): Context = (APPLICATION_CONTEXT ?: resolveApplicationContext()).applicationContext

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
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppConfig.getDarkMode())

        LogFacade.i(
            LogModule.CORE,
            APP_INIT_TAG,
            "application onCreate start process=${android.os.Process.myPid()}",
        )

        if (BuildConfig.DEBUG) {
            ARouter.openLog()
            ARouter.openDebug()
            LogFacade.i(LogModule.CORE, APP_INIT_TAG, "router debug mode enabled")
        }
        // ARouter 1.5.2 only checks versionName/code, which may stay unchanged on an APK replacement.
        val packageUpdateTime = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val routerCache = getSharedPreferences(Consts.AROUTER_SP_CACHE_KEY, MODE_PRIVATE)
        if (routerCache.getLong(ROUTER_UPDATE_TIME, -1) != packageUpdateTime) {
            routerCache
                .edit()
                .remove(Consts.AROUTER_SP_KEY_MAP)
                .remove(Consts.LAST_VERSION_NAME)
                .remove(Consts.LAST_VERSION_CODE)
                .putLong(ROUTER_UPDATE_TIME, packageUpdateTime)
                .apply()
            LogFacade.i(LogModule.CORE, APP_INIT_TAG, "router cache invalidated after package update")
        }
        ARouter.init(this)
        Notifications.setupNotificationChannels(this)
        ActivityHelper.instance.init(this)

        LogFacade.i(LogModule.CORE, APP_INIT_TAG, "application onCreate finished")
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }.build()
}
