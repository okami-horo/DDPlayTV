package com.xyoye.player.utils

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.info.PlayerInitializer

/**
 * Created by xyoye on 2020/10/29.
 */

object VideoLog {
    const val TAG = "DanDanPlay.VideoPlayer"

    fun e(message: String?) {
        log(LogLevel.ERROR, message)
    }

    fun d(message: String?) {
        log(LogLevel.DEBUG, message)
    }

    fun i(message: String?) {
        log(LogLevel.INFO, message)
    }

    private fun log(logLevel: LogLevel, message: String?) {
        if (!PlayerInitializer.isPrintLog) return
        LogFacade.log(
            level = logLevel,
            module = LogModule.PLAYER,
            tag = TAG,
            message = message ?: ""
        )
    }
}
