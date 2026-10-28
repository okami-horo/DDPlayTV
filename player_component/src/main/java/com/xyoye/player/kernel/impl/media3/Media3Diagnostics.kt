package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.privacy.SensitiveDataSanitizer

@UnstableApi
object Media3Diagnostics {
    private const val TAG = "Media3Diag"
    private val MODULE = LogModule.PLAYER

    data class HttpOpenSnapshot(
        val url: String?,
        val code: Int?,
        val contentType: String?,
        val timestampMs: Long
    )

    /** 外部可控制是否输出详细日志，必要时可关闭减少噪音。 */
    @JvmStatic
    var loggingEnabled: Boolean = true

    /**
     * URL 细节开关：
     * - 默认仅输出 host/path（不包含 query/fragment）
     * - 可选追加 fingerprint 便于定位“过期直链/签名变化”等问题，但不泄露 query 明文
     */
    @JvmStatic
    var includeUrlFingerprint: Boolean = true

    /** 预留遥测回调，后续可挂到日志/埋点管线。 */
    @JvmStatic
    var telemetryListener: ((event: String, data: Map<String, String>) -> Unit)? = null

    @Volatile
    private var lastHttpOpenSnapshot: HttpOpenSnapshot? = null

    fun snapshotLastHttpOpen(): HttpOpenSnapshot? = lastHttpOpenSnapshot

    fun clearLastHttpOpen() {
        lastHttpOpenSnapshot = null
    }

    private inline fun log(block: () -> Unit) {
        if (loggingEnabled) {
            block()
        }
    }

    private fun emit(
        event: String,
        data: Map<String, String>
    ) {
        telemetryListener?.invoke(event, data)
    }

    fun logDecoderSelected(
        mime: String,
        secure: Boolean,
        decoder: MediaCodecInfo?
    ) {
        log {
            LogFacade.i(MODULE, TAG, "Decoder selected: mime=$mime secure=$secure name=${decoder?.name ?: "<none>"}")
        }
        emit("decoder_selected", mapOf("mime" to mime, "secure" to secure.toString(), "decoder" to (decoder?.name ?: "")))
    }

    fun logDecoderCandidates(
        mime: String,
        secure: Boolean,
        candidates: List<MediaCodecInfo>
    ) {
        log {
            val names = candidates.joinToString { it.name }
            LogFacade.d(MODULE, TAG, "Decoder candidates for mime=$mime secure=$secure -> [$names]")
        }
    }

    fun logDecoderBlacklisted(
        decoder: String,
        reason: String?
    ) {
        log {
            LogFacade.w(MODULE, TAG, "Blacklisting decoder=$decoder due to ${reason ?: "runtime failure"}")
        }
    }

    fun logDecoderRetry(
        decoder: String,
        attempt: Int
    ) {
        log {
            LogFacade.i(MODULE, TAG, "Retry playback with decoder fallback, decoder=$decoder attempt=$attempt")
        }
    }

    fun logDecoderGiveUp(
        decoder: String?,
        reason: String?
    ) {
        log {
            LogFacade.e(MODULE, TAG, "Exhausted decoder fallbacks. lastDecoder=${decoder ?: "unknown"} reason=${reason ?: "unknown"}")
        }
    }

    fun logFormatRewritten(
        original: Format,
        rewritten: Format
    ) {
        if (original === rewritten) return
        log {
            LogFacade.i(MODULE, TAG, "Format rewritten: mime=${original.sampleMimeType} -> ${rewritten.sampleMimeType}")
        }
    }

    fun logHdrSelection(
        format: Format,
        hdrDisplay: Boolean,
        hdrTier: Int
    ) {
        val transfer = format.colorInfo?.colorTransfer
        val isDv = format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION
        log {
            LogFacade.i(
                MODULE,
                TAG,
                "HDR preference applied: selected codec=${format.codecs} hdrDisplay=$hdrDisplay hdrTier=$hdrTier transfer=$transfer isDv=$isDv",
            )
        }
    }

    fun logDrmFallbackDecision(
        mime: String,
        allowed: Boolean,
        reason: String
    ) {
        val action = if (allowed) "allow" else "block"
        log {
            LogFacade.w(MODULE, TAG, "DRM fallback decision: action=$action mime=$mime reason=$reason")
        }
        emit("drm_fallback", mapOf("mime" to mime, "action" to action, "reason" to reason))
    }

    fun logSecureRequirement(
        mime: String,
        requiresSecure: Boolean,
        allowFallback: Boolean
    ) {
        log {
            LogFacade.d(MODULE, TAG, "Secure decoder request: mime=$mime requiresSecure=$requiresSecure allowFallback=$allowFallback")
        }
    }

    fun logPlaybackDescriptor(descriptor: Media3CodecPolicy.PlaybackDescriptor?) {
        descriptor ?: return
        val safeUri =
            SensitiveDataSanitizer.sanitizeUrl(
                descriptor.uri.toString(),
                SensitiveDataSanitizer.UrlMode.SAFE,
            )
        log {
            LogFacade.d(
                MODULE,
                TAG,
                "Playback descriptor: uri=$safeUri container=${descriptor.containerHint} ext=${descriptor.extension} declaredMime=${descriptor.declaredMimeType}",
            )
        }
    }

    fun logFormatDowngrade(
        fromMime: String,
        toMime: String,
        reason: String
    ) {
        log {
            LogFacade.w(MODULE, TAG, "Format downgrade: $fromMime -> $toMime reason=$reason")
        }
        emit("format_downgrade", mapOf("from" to fromMime, "to" to toMime, "reason" to reason))
    }

    fun logDolbyVisionFallback(
        codecs: String?,
        target: String,
        displayInfo: String
    ) {
        log {
            LogFacade.i(MODULE, TAG, "Dolby Vision fallback: codecs=$codecs target=$target display=$displayInfo")
        }
    }

    fun logHttpOpen(
        url: String?,
        code: Int?,
        contentType: String?,
        logSuccess: Boolean = true,
        failed: Boolean = false
    ) {
        val safeUrl = SensitiveDataSanitizer.sanitizeUrl(url, SensitiveDataSanitizer.UrlMode.SAFE)
        val urlFp = url?.takeIf { includeUrlFingerprint }?.let { SensitiveDataSanitizer.fingerprint(it) }
        val urlForLog = if (urlFp.isNullOrBlank()) safeUrl else "$safeUrl (fp=$urlFp)"
        lastHttpOpenSnapshot =
            HttpOpenSnapshot(
                url = safeUrl,
                code = code,
                contentType = contentType,
                timestampMs = System.currentTimeMillis(),
            )
        log {
            val message = "HTTP open: url=$urlForLog code=${code ?: -1} contentType=${contentType ?: ""}"
            if (failed || (code != null && code >= 400)) {
                LogFacade.w(MODULE, TAG, message)
            } else if (logSuccess) {
                LogFacade.d(MODULE, TAG, message)
            }
        }
        emit(
            "http_open",
            mapOf(
                "url" to safeUrl,
                "urlFp" to (urlFp ?: ""),
                "code" to (code?.toString() ?: ""),
                "contentType" to (contentType ?: ""),
            ),
        )
    }

    fun logContentTypeOverride(
        url: String,
        contentType: String,
        reason: String
    ) {
        val safeUrl = SensitiveDataSanitizer.sanitizeUrl(url, SensitiveDataSanitizer.UrlMode.SAFE)
        log {
            LogFacade.i(MODULE, TAG, "Content type override: url=$safeUrl contentType=$contentType reason=$reason")
        }
        emit(
            "content_type_override",
            mapOf("url" to safeUrl, "contentType" to contentType, "reason" to reason),
        )
    }
}
