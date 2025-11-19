package com.xyoye.player.kernel.impl.media3

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

@UnstableApi
object Media3Diagnostics {
    private const val TAG = "Media3Diag"

    /** 外部可控制是否输出详细日志，必要时可关闭减少噪音。 */
    @JvmStatic
    var loggingEnabled: Boolean = true

    /** 预留遥测回调，后续可挂到日志/埋点管线。 */
    @JvmStatic
    var telemetryListener: ((event: String, data: Map<String, String>) -> Unit)? = null

    private inline fun log(block: () -> Unit) {
        if (loggingEnabled) {
            block()
        }
    }

    private fun emit(event: String, data: Map<String, String>) {
        telemetryListener?.invoke(event, data)
    }

    fun logDecoderSelected(mime: String, secure: Boolean, decoder: MediaCodecInfo?) {
        log {
            Log.i(TAG, "Decoder selected: mime=$mime secure=$secure name=${decoder?.name ?: "<none>"}")
        }
        emit("decoder_selected", mapOf("mime" to mime, "secure" to secure.toString(), "decoder" to (decoder?.name ?: "")))
    }

    fun logDecoderCandidates(mime: String, secure: Boolean, candidates: List<MediaCodecInfo>) {
        log {
            val names = candidates.joinToString { it.name }
            Log.d(TAG, "Decoder candidates for mime=$mime secure=$secure -> [$names]")
        }
    }

    fun logDecoderBlacklisted(decoder: String, reason: String?) {
        log {
            Log.w(TAG, "Blacklisting decoder=$decoder due to ${reason ?: "runtime failure"}")
        }
    }

    fun logDecoderRetry(decoder: String, attempt: Int) {
        log {
            Log.i(TAG, "Retry playback with decoder fallback, decoder=$decoder attempt=$attempt")
        }
    }

    fun logDecoderGiveUp(decoder: String?, reason: String?) {
        log {
            Log.e(TAG, "Exhausted decoder fallbacks. lastDecoder=${decoder ?: "unknown"} reason=${reason ?: "unknown"}")
        }
    }

    fun logFormatRewritten(original: Format, rewritten: Format) {
        if (original === rewritten) return
        log {
            Log.i(TAG, "Format rewritten: mime=${original.sampleMimeType} -> ${rewritten.sampleMimeType}")
        }
    }

    fun logHdrSelection(format: Format, hdrDisplay: Boolean, hdrTier: Int) {
        val transfer = format.colorInfo?.colorTransfer
        val isDv = format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION
        log {
            Log.i(TAG, "HDR preference applied: selected codec=${format.codecs} hdrDisplay=$hdrDisplay hdrTier=$hdrTier transfer=$transfer isDv=$isDv")
        }
    }

    fun logDrmFallbackDecision(mime: String, allowed: Boolean, reason: String) {
        val action = if (allowed) "allow" else "block"
        log {
            Log.w(TAG, "DRM fallback decision: action=$action mime=$mime reason=$reason")
        }
        emit("drm_fallback", mapOf("mime" to mime, "action" to action, "reason" to reason))
    }

    fun logSecureRequirement(mime: String, requiresSecure: Boolean, allowFallback: Boolean) {
        log {
            Log.d(TAG, "Secure decoder request: mime=$mime requiresSecure=$requiresSecure allowFallback=$allowFallback")
        }
    }

    fun logPlaybackDescriptor(descriptor: Media3CodecPolicy.PlaybackDescriptor?) {
        descriptor ?: return
        log {
            Log.d(
                TAG,
                "Playback descriptor: uri=${descriptor.uri} container=${descriptor.containerHint} ext=${descriptor.extension} declaredMime=${descriptor.declaredMimeType}"
            )
        }
    }

    fun logFormatDowngrade(fromMime: String, toMime: String, reason: String) {
        log {
            Log.w(TAG, "Format downgrade: $fromMime -> $toMime reason=$reason")
        }
        emit("format_downgrade", mapOf("from" to fromMime, "to" to toMime, "reason" to reason))
    }

    fun logDolbyVisionFallback(codecs: String?, target: String, displayInfo: String) {
        log {
            Log.i(TAG, "Dolby Vision fallback: codecs=$codecs target=$target display=$displayInfo")
        }
    }
}
