package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule

@UnstableApi
object Media3Diagnostics {
    private const val TAG = "Media3Diag"
    private val MODULE = LogModule.PLAYER
    private const val ANIME4K_PREFIX = "Anime4K"

    data class HttpOpenSnapshot(
        val url: String?,
        val code: Int?,
        val contentType: String?,
        val timestampMs: Long
    )

    /** 外部可控制是否输出详细日志，必要时可关闭减少噪音。 */
    @JvmStatic
    var loggingEnabled: Boolean = true

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
        log {
            LogFacade.d(
                MODULE,
                TAG,
                "Playback descriptor: uri=${descriptor.uri} container=${descriptor.containerHint} ext=${descriptor.extension} declaredMime=${descriptor.declaredMimeType}",
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
        contentType: String?
    ) {
        lastHttpOpenSnapshot =
            HttpOpenSnapshot(
                url = url,
                code = code,
                contentType = contentType,
                timestampMs = System.currentTimeMillis(),
            )
        log {
            val message = "HTTP open: url=${url ?: "<null>"} code=${code ?: -1} contentType=${contentType ?: ""}"
            if (code != null && code >= 400) {
                LogFacade.w(MODULE, TAG, message)
            } else {
                LogFacade.d(MODULE, TAG, message)
            }
        }
        emit(
            "http_open",
            mapOf(
                "url" to (url ?: ""),
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
        log {
            LogFacade.i(MODULE, TAG, "Content type override: url=$url contentType=$contentType reason=$reason")
        }
        emit(
            "content_type_override",
            mapOf("url" to url, "contentType" to contentType, "reason" to reason),
        )
    }

    fun logAnime4kModeChanged(
        requestedMode: Int,
        appliedMode: Int
    ) {
        log {
            LogFacade.i(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX mode changed: requested=$requestedMode applied=$appliedMode",
            )
        }
        emit(
            "anime4k_mode_changed",
            mapOf(
                "requested" to requestedMode.toString(),
                "applied" to appliedMode.toString(),
            ),
        )
    }

    fun logAnime4kEffectsApplied(
        mode: Int,
        effectsCount: Int
    ) {
        log {
            LogFacade.i(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX effects applied: mode=$mode effectsCount=$effectsCount",
            )
        }
        emit(
            "anime4k_effects_applied",
            mapOf(
                "mode" to mode.toString(),
                "effectsCount" to effectsCount.toString(),
            ),
        )
    }

    fun logAnime4kGlEffectDecision(
        useHdr: Boolean,
        decision: String,
        throwable: Throwable? = null
    ) {
        log {
            val message = "$ANIME4K_PREFIX GlEffect decision: useHdr=$useHdr decision=$decision"
            if (throwable == null) {
                LogFacade.i(MODULE, TAG, message)
            } else {
                LogFacade.w(MODULE, TAG, message, throwable = throwable)
            }
        }
        emit(
            "anime4k_gl_effect_decision",
            buildMap {
                put("useHdr", useHdr.toString())
                put("decision", decision)
                throwable?.let { put("error", it.javaClass.simpleName) }
            },
        )
    }

    fun logAnime4kShaderParsed(
        restorePassCount: Int,
        upscalePassCount: Int
    ) {
        log {
            LogFacade.i(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX shader parsed: restorePasses=$restorePassCount upscalePasses=$upscalePassCount",
            )
        }
        emit(
            "anime4k_shader_parsed",
            mapOf(
                "restorePasses" to restorePassCount.toString(),
                "upscalePasses" to upscalePassCount.toString(),
            ),
        )
    }

    fun logAnime4kShaderPipelineParsed(
        shaderFiles: List<String>,
        totalPasses: Int
    ) {
        log {
            val files = shaderFiles.joinToString()
            LogFacade.i(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX shader pipeline parsed: files=[$files] totalPasses=$totalPasses",
            )
        }
        emit(
            "anime4k_shader_pipeline_parsed",
            mapOf(
                "files" to shaderFiles.joinToString(),
                "totalPasses" to totalPasses.toString(),
            ),
        )
    }

    fun logAnime4kShaderPipelinePlanned(
        inputSize: Size,
        outputControlSize: Size,
        totalPasses: Int,
        activePasses: Int,
        activeMainPasses: Int,
        outputSize: Size,
        skippedPasses: List<String>
    ) {
        log {
            val skippedSummary =
                if (skippedPasses.isEmpty()) {
                    "none"
                } else {
                    val head = skippedPasses.take(6).joinToString()
                    if (skippedPasses.size > 6) "$head ...(+${skippedPasses.size - 6})" else head
                }
            LogFacade.i(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX pipeline planned: input=${inputSize.width}x${inputSize.height} " +
                    "outputControl=${outputControlSize.width}x${outputControlSize.height} " +
                    "output=${outputSize.width}x${outputSize.height} " +
                    "passes=$activePasses/$totalPasses mainPasses=$activeMainPasses skipped=[$skippedSummary]",
            )
        }
        emit(
            "anime4k_shader_pipeline_planned",
            mapOf(
                "input" to "${inputSize.width}x${inputSize.height}",
                "outputControl" to "${outputControlSize.width}x${outputControlSize.height}",
                "output" to "${outputSize.width}x${outputSize.height}",
                "totalPasses" to totalPasses.toString(),
                "activePasses" to activePasses.toString(),
                "activeMainPasses" to activeMainPasses.toString(),
                "skippedPassesCount" to skippedPasses.size.toString(),
            ),
        )
    }

    fun logAnime4kShaderDirectiveEvalFailed(
        passDescription: String,
        directive: String,
        expression: String,
        mainSize: Size,
        nativeSize: Size,
        outputSize: Size
    ) {
        log {
            LogFacade.w(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX directive eval failed: pass=$passDescription directive=$directive expr=\"$expression\" " +
                    "main=${mainSize.width}x${mainSize.height} native=${nativeSize.width}x${nativeSize.height} " +
                    "output=${outputSize.width}x${outputSize.height}",
            )
        }
        emit(
            "anime4k_shader_directive_eval_failed",
            mapOf(
                "pass" to passDescription,
                "directive" to directive,
                "expression" to expression,
                "main" to "${mainSize.width}x${mainSize.height}",
                "native" to "${nativeSize.width}x${nativeSize.height}",
                "output" to "${outputSize.width}x${outputSize.height}",
            ),
        )
    }

    fun logAnime4kShaderFirstFrame(
        inputSize: Size,
        outputSize: Size,
        activePasses: Int,
        outputFboId: Int
    ) {
        log {
            LogFacade.d(
                MODULE,
                TAG,
                "$ANIME4K_PREFIX drawFrame: firstFrame input=${inputSize.width}x${inputSize.height} " +
                    "output=${outputSize.width}x${outputSize.height} activePasses=$activePasses outputFboId=$outputFboId",
            )
        }
        emit(
            "anime4k_shader_first_frame",
            mapOf(
                "input" to "${inputSize.width}x${inputSize.height}",
                "output" to "${outputSize.width}x${outputSize.height}",
                "activePasses" to activePasses.toString(),
                "outputFboId" to outputFboId.toString(),
            ),
        )
    }

    fun logAnime4kShaderConfigured(
        inputSize: Size,
        outputSize: Size,
        fallbackToCopy: Boolean,
        reason: String? = null,
        glInfo: String? = null,
        throwable: Throwable? = null
    ) {
        log {
            val suffix =
                buildString {
                    if (!reason.isNullOrBlank()) {
                        append(" reason=").append(reason)
                    }
                    if (!glInfo.isNullOrBlank()) {
                        append(" gl=").append(glInfo)
                    }
                }
            val message =
                "$ANIME4K_PREFIX shader configured: input=${inputSize.width}x${inputSize.height} " +
                    "output=${outputSize.width}x${outputSize.height} fallbackToCopy=$fallbackToCopy$suffix"
            if (throwable == null) {
                if (fallbackToCopy) {
                    LogFacade.w(MODULE, TAG, message)
                } else {
                    LogFacade.i(MODULE, TAG, message)
                }
            } else {
                LogFacade.w(MODULE, TAG, message, throwable = throwable)
            }
        }
        emit(
            "anime4k_shader_configured",
            buildMap {
                put("input", "${inputSize.width}x${inputSize.height}")
                put("output", "${outputSize.width}x${outputSize.height}")
                put("fallbackToCopy", fallbackToCopy.toString())
                reason?.let { put("reason", it) }
                glInfo?.let { put("glInfo", it) }
                throwable?.let { put("error", it.javaClass.simpleName) }
            },
        )
    }

    fun logAnime4kOutputResolutionMessage(
        resolution: Size,
        renderer: String,
        sent: Boolean,
        reason: String? = null
    ) {
        log {
            val message =
                "$ANIME4K_PREFIX output resolution message: " +
                    "resolution=${resolution.width}x${resolution.height} renderer=$renderer sent=$sent" +
                    (reason?.takeIf { it.isNotBlank() }?.let { " reason=$it" } ?: "")
            if (sent) {
                LogFacade.d(MODULE, TAG, message)
            } else {
                LogFacade.w(MODULE, TAG, message)
            }
        }
        emit(
            "anime4k_output_resolution_message",
            buildMap {
                put("resolution", "${resolution.width}x${resolution.height}")
                put("renderer", renderer)
                put("sent", sent.toString())
                reason?.let { put("reason", it) }
            },
        )
    }

    fun logAnime4kOutputResolutionSkipped(reason: String) {
        log {
            LogFacade.w(MODULE, TAG, "$ANIME4K_PREFIX output resolution skipped: reason=$reason")
        }
        emit(
            "anime4k_output_resolution_skipped",
            mapOf("reason" to reason),
        )
    }
}
