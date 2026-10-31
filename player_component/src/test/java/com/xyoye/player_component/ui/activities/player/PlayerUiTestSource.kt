package com.xyoye.player_component.ui.activities.player

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.MediaType

internal class PlayerUiTestSource(
    private val key: String,
    private val historyPosition: Long = 0
) : BaseVideoSource(0, listOf(key)) {
    override fun getVideoUrl() = "https://example.test/$key.mp4"

    override fun getVideoTitle() = key

    override fun getCurrentPosition() = historyPosition

    override fun getMediaType() = MediaType.LOCAL_STORAGE

    override fun getUniqueKey() = key

    override fun getStorageId() = 1

    override fun indexTitle(index: Int) = key
}
