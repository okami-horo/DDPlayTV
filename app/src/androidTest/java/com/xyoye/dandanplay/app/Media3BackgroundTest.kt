package com.xyoye.dandanplay.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.dandanplay.app.service.Media3BackgroundCoordinator
import com.xyoye.dandanplay.app.service.MediaSessionCommandBridge
import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Background coordinator must invoke Media3 session commands")
@RunWith(AndroidJUnit4::class)
@Ignore("TV adaptation: 画中画/后台播放已禁用，暂不验证该能力")
class Media3BackgroundTest {

    @Test
    fun syncPushesNotificationAndPipCommands_whenCapabilitiesAllow() {
        val bridge = RecordingBridge()
        val coordinator = Media3BackgroundCoordinator(bridge)
        val contract = PlayerCapabilityContract(
            sessionId = "session-1",
            capabilities = listOf(
                Media3Capability.PLAY,
                Media3Capability.BACKGROUND,
                Media3Capability.PIP
            ),
            backgroundModes = listOf(
                Media3BackgroundMode.NOTIFICATION,
                Media3BackgroundMode.PIP
            ),
            sessionCommands = listOf("REMOTE_HEARTBEAT")
        )

        coordinator.sync(contract)

        assertEquals(
            setOf(
                "REMOTE_HEARTBEAT",
                Media3BackgroundCoordinator.COMMAND_BACKGROUND_RESUME,
                Media3BackgroundCoordinator.COMMAND_ENTER_PIP
            ),
            bridge.commandEvents.single()
        )
        assertEquals(
            setOf(
                Media3BackgroundMode.NOTIFICATION,
                Media3BackgroundMode.PIP
            ),
            bridge.backgroundEvents.single()
        )
    }

    @Test
    fun syncOnlyEmitsChanges_whenContractUpdates() {
        val bridge = RecordingBridge()
        val coordinator = Media3BackgroundCoordinator(bridge)
        val baseline = PlayerCapabilityContract(
            sessionId = "session-2",
            capabilities = listOf(Media3Capability.BACKGROUND),
            backgroundModes = listOf(Media3BackgroundMode.NOTIFICATION),
            sessionCommands = emptyList()
        )

        coordinator.sync(baseline)
        bridge.reset()

        val updated = baseline.copy(
            backgroundModes = listOf(Media3BackgroundMode.NOTIFICATION, Media3BackgroundMode.PIP),
            capabilities = listOf(Media3Capability.BACKGROUND, Media3Capability.PIP)
        )
        coordinator.sync(updated)

        assertEquals(
            setOf(Media3BackgroundMode.NOTIFICATION, Media3BackgroundMode.PIP),
            bridge.backgroundEvents.single()
        )
        assertEquals(
            setOf(
                Media3BackgroundCoordinator.COMMAND_BACKGROUND_RESUME,
                Media3BackgroundCoordinator.COMMAND_ENTER_PIP
            ),
            bridge.commandEvents.single()
        )

        bridge.reset()
        coordinator.sync(updated)
        assertTrue(bridge.commandEvents.isEmpty())
        assertTrue(bridge.backgroundEvents.isEmpty())
    }

    private class RecordingBridge : MediaSessionCommandBridge {
        val commandEvents = mutableListOf<Set<String>>()
        val backgroundEvents = mutableListOf<Set<Media3BackgroundMode>>()

        override fun updateSessionCommands(commands: Set<String>) {
            commandEvents += commands
        }

        override fun updateBackgroundModes(modes: Set<Media3BackgroundMode>) {
            backgroundEvents += modes
        }

        fun reset() {
            commandEvents.clear()
            backgroundEvents.clear()
        }
    }
}
