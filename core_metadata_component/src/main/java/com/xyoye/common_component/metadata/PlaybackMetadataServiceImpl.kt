package com.xyoye.common_component.metadata

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.services.PlaybackMetadataFile
import com.xyoye.common_component.services.PlaybackMetadataService

@Route(path = RouteTable.Metadata.PlaybackService)
class PlaybackMetadataServiceImpl : PlaybackMetadataService {
    override fun init(context: Context?) = Unit

    override fun submit(
        version: PlaybackSessionVersion,
        file: PlaybackMetadataFile
    ) {
        MetadataCoordinator.startPlayback(version, file)
    }
}
