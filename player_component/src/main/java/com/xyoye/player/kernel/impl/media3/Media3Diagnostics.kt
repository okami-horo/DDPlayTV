package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
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

    fun createDecoderListener(): AnalyticsListener =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                logDecoderInitialized("video", decoderName, initializedTimestampMs, initializationDurationMs)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                logDecoderInitialized("audio", decoderName, initializedTimestampMs, initializationDurationMs)
            }

            override fun onVideoDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String
            ) {
                logDecoderReleased("video", decoderName)
            }

            override fun onAudioDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String
            ) {
                logDecoderReleased("audio", decoderName)
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                logDecoderFormat("video", format)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                logDecoderFormat("audio", format)
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                logDecoderError("video", videoCodecError)
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                logDecoderError("audio", audioCodecError)
            }
        }

    private fun logDecoderInitialized(
        track: String,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        log {
            LogFacade.i(
                MODULE,
                TAG,
                "Decoder initialized: track=$track name=$decoderName durationMs=$initializationDurationMs timestampMs=$initializedTimestampMs"
            )
        }
        emit(
            "decoder_initialized",
            mapOf(
                "track" to track,
                "decoder" to decoderName,
                "initializationDurationMs" to initializationDurationMs.toString(),
                "initializedTimestampMs" to initializedTimestampMs.toString()
            )
        )
    }

    private fun logDecoderReleased(
        track: String,
        decoderName: String
    ) {
        log { LogFacade.i(MODULE, TAG, "Decoder released: track=$track name=$decoderName") }
        emit("decoder_released", mapOf("track" to track, "decoder" to decoderName))
    }

    private fun logDecoderFormat(
        track: String,
        format: Format
    ) {
        val details =
            if (track == "video") {
                "size=${format.width}x${format.height} fps=${format.frameRate} colorTransfer=${format.colorInfo?.colorTransfer}"
            } else {
                "sampleRate=${format.sampleRate} channels=${format.channelCount}"
            }
        log {
            LogFacade.i(MODULE, TAG, "Decoder input format: track=$track mime=${format.sampleMimeType} codecs=${format.codecs} $details")
        }
        emit("decoder_format", mapOf("track" to track, "mime" to format.sampleMimeType.orEmpty(), "codecs" to format.codecs.orEmpty()))
    }

    private fun logDecoderError(
        track: String,
        error: Exception
    ) {
        val reason = SensitiveDataSanitizer.sanitizeFreeText(error.message.orEmpty())
        log { LogFacade.w(MODULE, TAG, "Decoder error: track=$track type=${error.javaClass.simpleName} reason=$reason") }
        emit("decoder_error", mapOf("track" to track, "type" to error.javaClass.simpleName, "reason" to reason))
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
