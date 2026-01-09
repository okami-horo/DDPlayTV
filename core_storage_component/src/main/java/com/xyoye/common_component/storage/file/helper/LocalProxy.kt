package com.xyoye.common_component.storage.file.helper

import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType

object LocalProxy {
    suspend fun wrapIfNeeded(
        playerType: PlayerType,
        modeValue: Int?,
        upstreamUrl: String,
        upstreamHeaders: Map<String, String>? = null,
        contentLength: Long,
        prePlayRangeMinIntervalMs: Long,
        fileName: String,
        autoEnabled: Boolean,
        onRangeUnsupported: (() -> HttpPlayServer.UpstreamSource?)? = null
    ): String {
        if (upstreamUrl.isBlank()) return upstreamUrl

        val mode = LocalProxyMode.from(modeValue)
        if (mode == LocalProxyMode.OFF) return upstreamUrl
        if (mode == LocalProxyMode.AUTO && !autoEnabled) return upstreamUrl

        val isHttp =
            upstreamUrl.startsWith("http://", ignoreCase = true) ||
                upstreamUrl.startsWith("https://", ignoreCase = true)
        if (!isHttp) return upstreamUrl

        val resolvedLength = contentLength.coerceAtLeast(-1L)
        if (resolvedLength <= 0) return upstreamUrl

        val playServer = HttpPlayServer.getInstance()
        if (playServer.isServingUrl(upstreamUrl)) return upstreamUrl

        val started = playServer.startSync()
        if (!started) return upstreamUrl

        return playServer.generatePlayUrl(
            upstreamUrl = upstreamUrl,
            upstreamHeaders = upstreamHeaders.orEmpty(),
            contentLength = resolvedLength,
            prePlayRangeMinIntervalMs = prePlayRangeMinIntervalMs,
            fileName = fileName.ifBlank { playerType.name.lowercase() },
            onRangeUnsupported = onRangeUnsupported,
        )
    }
}
