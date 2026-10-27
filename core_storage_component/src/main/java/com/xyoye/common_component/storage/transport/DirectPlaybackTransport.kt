package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.utils.thunder.ThunderManager
import java.net.URI

/** Only for resolved sources which own no server, connection, task or session. */
internal fun directPlaybackTransport(url: String): PlaybackTransportLease {
    requireUnownedPlaybackUrl(url)
    return ManagedPlaybackTransportLease(url, PlaybackTransportType.DIRECT, emptyList())
}

/** Reserved loopback lease routes include independent SMB/FTP listeners and retired URLs. */
internal fun requireUnownedPlaybackUrl(url: String) {
    if (url.isBlank()) throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
    val leaseRoute =
        runCatching {
            val uri = URI(url)
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host in setOf("127.0.0.1", "localhost", "[::1]") &&
                uri.path.orEmpty().startsWith("/lease/")
        }.getOrDefault(false)
    if (leaseRoute ||
        HttpPlayServer.getInstance().isLocalServerUrl(url) ||
        ThunderManager.getInstance().getTaskIdForPlayUrl(url) >= 0
    ) {
        throw TransportAcquisitionException(TransportAcquisitionFailure.UnsupportedTransport)
    }
}
