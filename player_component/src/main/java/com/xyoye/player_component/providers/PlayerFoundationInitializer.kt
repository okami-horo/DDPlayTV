package com.xyoye.player_component.providers

import android.content.Context
import androidx.startup.Initializer
import com.xyoye.common_component.base.app.BaseInitializer
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.open_cc.OpenCCFile

class PlayerFoundationInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appContext = context.applicationContext
        runCatching { OpenCCFile.init(appContext) }
            .onFailure { it.printStackTrace() }
        runCatching { SubtitleFontManager.initialize(appContext) }
            .onFailure { it.printStackTrace() }
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf(BaseInitializer::class.java)
}
