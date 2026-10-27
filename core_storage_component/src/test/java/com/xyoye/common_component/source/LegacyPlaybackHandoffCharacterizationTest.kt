package com.xyoye.common_component.source

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media3.Media3LaunchParams
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.media3.entity.Media3SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Records the legacy defects, not the desired handoff contract. Replace these cases with
 * provider/session regressions in handoff Tasks 6/9 before deleting the manager in Task 13.
 */
class LegacyPlaybackHandoffCharacterizationTest {
    @Test
    fun abandonedStorageNavigationLeavesOptionsForAnUnrelatedHistorySource() {
        val manager = VideoSourceManager { }
        val storage = Source("storage")
        val history = Source("history")
        val storageOptions = options("storage")

        // Historical offline rejection happened here. Lease Task 5 now validates earlier;
        // abandoning navigation after preparation still exposes the split-slot defect.
        manager.attachMedia3LaunchParams(storageOptions)
        manager.setSource(storage)
        manager.setSource(history)

        assertSame(history, manager.claimSource())
        assertEquals(storageOptions, manager.consumeMedia3LaunchParams())
    }

    @Test
    fun lateOldPreparationOverwritesNewerSourceButConsumesNewerOptions() {
        val manager = VideoSourceManager { }
        val oldSource = Source("old")
        val newerSource = Source("newer")

        manager.attachMedia3LaunchParams(options("old"))
        manager.attachMedia3LaunchParams(options("newer"))
        manager.setSource(newerSource)
        manager.setSource(oldSource)

        assertSame(oldSource, manager.claimSource())
        assertEquals("newer", manager.consumeMedia3LaunchParams()?.mediaId)
    }

    @Test
    fun duplicateDeliveryReplaysSourceAfterOptionsWereConsumed() {
        val manager = VideoSourceManager { }
        val source = Source("once")
        manager.attachMedia3LaunchParams(options("once"))
        manager.setSource(source)

        assertSame(source, manager.claimSource())
        assertEquals("once", manager.consumeMedia3LaunchParams()?.mediaId)
        manager.onSourceApplied(source)

        assertSame(source, manager.claimSource())
        assertNull(manager.consumeMedia3LaunchParams())
    }

    private fun options(id: String) = Media3LaunchParams(id, Media3SourceType.DOWNLOAD)

    private class Source(
        private val identity: String
    ) : BaseVideoSource(0, listOf(identity)) {
        override fun indexTitle(index: Int) = identity

        override fun getVideoUrl() = "https://example.test/$identity"

        override fun getVideoTitle() = identity

        override fun getCurrentPosition() = 0L

        override fun getMediaType() = MediaType.STREAM_LINK

        override fun getUniqueKey() = identity

        override fun getStorageId() = 1
    }
}
