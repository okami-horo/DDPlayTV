package com.xyoye.dandanplay.app.service

import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract

/**
 * Calculates which MediaSession commands and background modes should be exposed based on the
 * latest Media3 capability contract coming from the backend.
 */
class Media3BackgroundCoordinator(
    private val bridge: MediaSessionCommandBridge
) {

    private var lastCommands: Set<String> = emptySet()
    private var lastModes: Set<Media3BackgroundMode> = emptySet()

    fun sync(contract: PlayerCapabilityContract?) {
        // TV adaptation: 禁用后台播放与画中画，同步时强制清空能力指令
        emitCommands(emptySet())
        emitModes(emptySet())
        /*
        if (contract == null) {
            emitCommands(emptySet())
            emitModes(emptySet())
            return
        }

        val commands = buildSet {
            addAll(contract.sessionCommands)
            if (contract.capabilities.contains(Media3Capability.BACKGROUND)) {
                add(COMMAND_BACKGROUND_RESUME)
            }
            if (
                contract.capabilities.contains(Media3Capability.PIP) &&
                contract.backgroundModes.contains(Media3BackgroundMode.PIP)
            ) {
                add(COMMAND_ENTER_PIP)
            }
        }

        val modes = contract.backgroundModes.toSet()
        emitCommands(commands)
        emitModes(modes)
        */
    }

    private fun emitCommands(commands: Set<String>) {
        if (commands == lastCommands) return
        lastCommands = commands
        bridge.updateSessionCommands(commands)
    }

    private fun emitModes(modes: Set<Media3BackgroundMode>) {
        if (modes == lastModes) return
        lastModes = modes
        bridge.updateBackgroundModes(modes)
    }

    companion object {
        const val COMMAND_BACKGROUND_RESUME = "media3.background.resume"
        const val COMMAND_ENTER_PIP = "media3.pip.enter"
    }
}
