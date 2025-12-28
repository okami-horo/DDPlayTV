package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.data_component.enums.MpvLocalProxyMode
import com.xyoye.data_component.enums.PlayerType

object MpvLocalProxy {
    suspend fun wrapIfNeeded(
        upstreamUrl: String,
        upstreamHeaders: Map<String, String>? = null,
        contentLength: Long,
        fileName: String,
        autoEnabled: Boolean,
        onRangeUnsupported: (() -> HttpPlayServer.UpstreamSource?)? = null
    ): String {
        if (upstreamUrl.isBlank()) return upstreamUrl
        if (PlayerConfig.getUsePlayerType() != PlayerType.TYPE_MPV_PLAYER.value) return upstreamUrl

        val mode = MpvLocalProxyMode.from(PlayerConfig.getMpvLocalProxyMode())
        if (mode == MpvLocalProxyMode.OFF) return upstreamUrl
        if (mode == MpvLocalProxyMode.AUTO && !autoEnabled) return upstreamUrl

        val isHttp =
            upstreamUrl.startsWith("http://", ignoreCase = true) ||
                upstreamUrl.startsWith("https://", ignoreCase = true)
        if (!isHttp) return upstreamUrl
        if (contentLength <= 0) return upstreamUrl

        val playServer = HttpPlayServer.getInstance()
        if (playServer.isServingUrl(upstreamUrl)) return upstreamUrl

        val started = playServer.startSync()
        if (!started) return upstreamUrl

        return playServer.generatePlayUrl(
            upstreamUrl = upstreamUrl,
            upstreamHeaders = upstreamHeaders.orEmpty(),
            contentLength = contentLength,
            fileName = fileName.ifBlank { "video" },
            onRangeUnsupported = onRangeUnsupported,
        )
    }
}
