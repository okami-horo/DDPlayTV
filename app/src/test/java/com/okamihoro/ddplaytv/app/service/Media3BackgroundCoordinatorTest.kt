package com.okamihoro.ddplaytv.app.service

import com.xyoye.common_component.playback.handoff.*
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Media3BackgroundCoordinatorTest {
    private val version = PlaybackSessionVersion("session", 2)

    private fun state(capabilities: Set<Media3Capability>) =
        PlaybackStateView(
            version,
            PlaybackSourceView("media", "key", MediaType.STREAM_LINK, "title", null),
            Media3PlaybackState.PLAYING,
            capabilities,
            emptySet(),
            2,
            false,
            PlayerType.TYPE_EXO_PLAYER
        )

    @Test fun platformForwardsTheExactVersionToThePlayerOwner() =
        runBlocking {
            val commands = mutableListOf<PlaybackCommand>()
            val coordinator =
                Media3BackgroundCoordinator {
                    commands += it
                    PlaybackOperationResult.Success
                }
            val command = PlaybackCommand(version, PlaybackAction.Pause)
            assertEquals(PlaybackOperationResult.Success, coordinator.dispatch(command, state(setOf(Media3Capability.PAUSE)), false))
            assertEquals(listOf(command), commands)
        }

    @Test fun stalePlatformCommandsCannotControlTheNewSource() =
        runBlocking {
            val coordinator =
                Media3BackgroundCoordinator {
                    fail("Stale command reached the owner")
                    PlaybackOperationResult.Success
                }
            val command = PlaybackCommand(version.copy(sourceRevision = 1), PlaybackAction.Play)
            assertTrue(coordinator.dispatch(command, state(setOf(Media3Capability.PLAY)), false) is PlaybackOperationResult.Rejected)
            assertTrue(coordinator.dispatch(command, null, false) is PlaybackOperationResult.Rejected)
        }

    @Test fun tvAndUnavailableLocalOperationsRejectBackgroundCommands() =
        runBlocking {
            val commands = mutableListOf<PlaybackCommand>()
            val coordinator =
                Media3BackgroundCoordinator {
                    commands += it
                    PlaybackOperationResult.Success
                }
            val command = PlaybackCommand(version, PlaybackAction.EnterPictureInPicture)
            assertTrue(coordinator.dispatch(command, state(setOf(Media3Capability.PIP)), true) is PlaybackOperationResult.Rejected)
            assertTrue(coordinator.dispatch(command, state(emptySet()), false) is PlaybackOperationResult.Rejected)
            assertTrue(commands.isEmpty())
            assertEquals(PlaybackOperationResult.Success, coordinator.dispatch(command, state(setOf(Media3Capability.PIP)), false))
            assertEquals(listOf(command), commands)
        }
}
