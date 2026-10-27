package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.directPlaybackTransport
import com.xyoye.common_component.storage.transport.requireUnownedPlaybackUrl
import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType

object LocalProxy {
    enum class UpstreamTlsPolicy {
        STRICT,
        UNSAFE_TRUST_ALL
    }

    data class UpstreamSource(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val contentType: String = "application/octet-stream",
        val contentLength: Long = -1L,
        val tlsPolicy: UpstreamTlsPolicy = UpstreamTlsPolicy.STRICT
    )

    suspend fun acquireIfNeeded(
        playerType: PlayerType,
        modeValue: Int?,
        upstreamUrl: String,
        upstreamHeaders: Map<String, String>? = null,
        contentLength: Long,
        prePlayRangeMinIntervalMs: Long,
        fileName: String,
        autoEnabled: Boolean,
        upstreamTlsPolicy: UpstreamTlsPolicy = UpstreamTlsPolicy.STRICT,
        onRangeUnsupported: (() -> UpstreamSource?)? = null
    ): PlaybackTransportLease {
        requireUnownedPlaybackUrl(upstreamUrl)
        val playServer = HttpPlayServer.getInstance()
        val mode = LocalProxyMode.from(modeValue)
        val isHttp = upstreamUrl.startsWith("http://", true) || upstreamUrl.startsWith("https://", true)
        if (mode == LocalProxyMode.OFF || (mode == LocalProxyMode.AUTO && !autoEnabled) || !isHttp || contentLength <= 0) {
            return directPlaybackTransport(upstreamUrl)
        }
        return playServer.acquireTransport(
            source =
                HttpPlayServer.UpstreamSource(
                    url = upstreamUrl,
                    headers = upstreamHeaders.orEmpty(),
                    contentLength = contentLength,
                    tlsPolicy = upstreamTlsPolicy.toServerPolicy()
                ),
            prePlayRangeMinIntervalMs = prePlayRangeMinIntervalMs,
            fileName = fileName.ifBlank { playerType.name.lowercase() },
            onRangeUnsupported = onRangeUnsupported?.let { supplier -> { supplier()?.toServerSource() } }
        )
    }

    fun isServingUrl(url: String?): Boolean {
        val normalized = url?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            HttpPlayServer.getInstance().isServingUrl(normalized)
        }.getOrDefault(false)
    }

    fun setSeekEnabledIfServing(
        url: String?,
        enabled: Boolean
    ): Boolean {
        if (!isServingUrl(url)) {
            return false
        }
        return runCatching {
            HttpPlayServer.getInstance().setSeekEnabled(url!!, enabled)
        }.getOrDefault(false)
    }

    private fun UpstreamTlsPolicy.toServerPolicy(): HttpPlayServer.UpstreamTlsPolicy =
        when (this) {
            UpstreamTlsPolicy.STRICT -> HttpPlayServer.UpstreamTlsPolicy.STRICT
            UpstreamTlsPolicy.UNSAFE_TRUST_ALL -> HttpPlayServer.UpstreamTlsPolicy.UNSAFE_TRUST_ALL
        }

    private fun UpstreamSource.toServerSource(): HttpPlayServer.UpstreamSource =
        HttpPlayServer.UpstreamSource(
            url = url,
            headers = headers,
            contentType = contentType,
            contentLength = contentLength,
            tlsPolicy = tlsPolicy.toServerPolicy()
        )
}
