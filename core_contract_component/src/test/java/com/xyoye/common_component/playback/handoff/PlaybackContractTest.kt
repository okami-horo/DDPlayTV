package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import com.xyoye.data_component.media3.entity.Media3SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackContractTest {
    @Test
    fun diagnosticStringsDoNotExposeDescriptorOrChallengeSecrets() {
        val secret = "private-fixture-value"
        val file = PlaybackFileReference(secret, secret, secret, protocolReference = secret)
        val queue = PlaybackQueueContext(file, StorageSort.NAME, true, secret, listOf(file))
        val castVideo = PlaybackCastVideo(secret, secret, danmuFileName = secret)
        val descriptors =
            listOf(
                StoredLibraryFileDescriptor(1, MediaType.SMB_SERVER, file, queue),
                PlayHistoryDescriptor(1, secret),
                ScreencastDescriptor(secret, 1234, listOf(castVideo), secret, mapOf("Cookie" to secret))
            )
        val options = PlaybackLaunchOptions(secret, Media3SourceType.STREAM)
        val diagnostics =
            descriptors +
                listOf(
                    file,
                    queue,
                    castVideo,
                    options,
                    PlaybackRequestInput(descriptors.first(), options, PlaybackRequestOrigin.STORAGE_FILE)
                )
        diagnostics.forEach { assertFalse(it.toString().contains(secret)) }
    }

    @Test
    fun fallbackQueueRejectsMissingSelectionAndExcessIdentities() {
        val file = PlaybackFileReference("selected", "path")
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueContext(null, StorageSort.NAME, false, "absent", listOf(file))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueContext(null, StorageSort.NAME, false, "selected", List(201) { file })
        }
        assertEquals(
            200,
            PlaybackQueueContext(null, StorageSort.NAME, false, "selected", List(200) { file })
                .fallbackItems.size
        )
    }

    @Test
    fun publishedCapabilitiesCannotBeChangedByProducerOrReader() {
        val capabilities = mutableSetOf(Media3Capability.PLAY)
        val view =
            PlaybackStateView(
                PlaybackSessionVersion("session", 2),
                PlaybackSourceView("id", "key", MediaType.LOCAL_STORAGE, "title", null),
                Media3PlaybackState.PLAYING,
                capabilities,
                emptySet(),
                1,
                true,
                com.xyoye.data_component.enums.PlayerType.TYPE_EXO_PLAYER
            )
        capabilities.clear()
        assertEquals(setOf(Media3Capability.PLAY), view.capabilities)
        assertThrows(UnsupportedOperationException::class.java) {
            (view.capabilities as MutableSet).clear()
        }
    }
}
