package com.xyoye.common_component.storage.transport

internal class HttpPlaybackTransport(
    leaseId: String,
    playUrl: String,
    retireContext: () -> Unit
) : PlaybackTransportLease by ManagedPlaybackTransportLease(
        playUrl,
        PlaybackTransportType.HTTP_PROXY,
        listOf(retireContext),
        leaseId
    )
