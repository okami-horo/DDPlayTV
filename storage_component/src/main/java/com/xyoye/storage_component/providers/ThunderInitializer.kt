package com.xyoye.storage_component.providers

import android.content.Context
import android.os.Build
import androidx.startup.Initializer
import com.xunlei.downloadlib.XLTaskHelper
import com.xyoye.common_component.base.app.BaseInitializer
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.thunder.ThunderManager

class ThunderInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Guard initialization to avoid crashing process on devices without proper native support.
        val supportXL = ThunderManager.SUPPORTED_ABI.any { Build.SUPPORTED_ABIS.contains(it) }
        if (supportXL) {
            try {
                XLTaskHelper.init(context)
            } catch (t: Throwable) {
                // Avoid hard crash during provider initialization; log and continue.
                LogFacade.e(
                    LogModule.STORAGE,
                    "ThunderInitializer",
                    "XLTaskHelper.init failed",
                    throwable = t,
                )
            }
        }
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf(BaseInitializer::class.java)
}
