package com.okamihoro.ddplaytv.app.service

import com.xyoye.data_component.entity.media3.Media3BackgroundMode

/**
 * Pluggable bridge that lets the session service update MediaSession/notification wiring
 * without hard-coding Android platform classes (keeps instrumentation tests lightweight).
 */
interface MediaSessionCommandBridge {
    fun updateSessionCommands(commands: Set<String>)

    fun updateBackgroundModes(modes: Set<Media3BackgroundMode>)
}
