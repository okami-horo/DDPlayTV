package com.xyoye.common_component.source

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoSourceManagerOwnershipTest {
    @Test
    fun replacingPendingSourceRetiresOnlySupersededInstance() {
        val retired = mutableListOf<BaseVideoSource>()
        val manager = VideoSourceManager(retired::add)
        val first = Source()
        val second = Source()
        manager.setSource(first)
        manager.setSource(first)
        manager.setSource(second)
        assertEquals(listOf(first), retired)
        assertSame(second, manager.claimSource())
    }

    @Test
    fun claimProtectsBackendOwnerWhileNewRequestIsPending() {
        val retired = mutableListOf<BaseVideoSource>()
        val manager = VideoSourceManager(retired::add)
        val active = Source()
        val pending = Source()
        val newest = Source()
        manager.setSource(active)
        assertSame(active, manager.claimSource())
        manager.setSource(pending)
        manager.setSource(newest)
        manager.onSourceApplied(active)
        assertSame(newest, manager.getSource())
        assertEquals(listOf(pending), retired)
        manager.clearSource(active)
        assertSame(newest, manager.getSource())
    }

    @Test
    fun nativeTransitionUpdatesCurrentPointerWithoutDisposingBackendOwner() {
        val retired = mutableListOf<BaseVideoSource>()
        val manager = VideoSourceManager(retired::add)
        val first = Source()
        val second = Source()
        manager.setSource(first)
        manager.claimSource()
        manager.onSourceApplied(second)
        assertSame(second, manager.getSource())
        assertEquals(emptyList<BaseVideoSource>(), retired)
    }

    private class Source : BaseVideoSource(0, listOf("same")) {
        override fun indexTitle(index: Int) = "same"

        override fun getVideoUrl() = "https://example.test/video"

        override fun getVideoTitle() = "same"

        override fun getCurrentPosition() = 0L

        override fun getMediaType() = MediaType.STREAM_LINK

        override fun getUniqueKey() = "same"

        override fun getStorageId() = 1
    }
}
