package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@UnstableApi
object Media3CodecPolicy {

    private const val UNKNOWN_VENDOR_PRIORITY = 0
    private val hevcMimes = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION)

    private val vendorPriority = listOf(
        "c2.qti",
        "c2.qcom",
        "c2.qualcomm",
        "c2.exynos",
        "c2.slsi",
        "c2.mtk",
        "c2.mediatek",
        "c2.unisoc",
        "c2.hisi",
        "c2.amlogic",
        "c2.realtek",
        "c2.android",
        "omx.qcom",
        "omx.exynos",
        "omx.mtk",
        "omx.amlogic",
        "omx.realtek",
        "omx.nvidia",
        "omx.google"
    )

    private val staticBlacklist = listOf(
        "c2.dolby.decoder.hevc"
    )

    private val runtimeBlacklist = CopyOnWriteArraySet<String>()
    private val activeDecoders = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastDecoderUsed: String? = null

    private var descriptor: PlaybackDescriptor? = null

    data class PlaybackDescriptor(
        val uri: Uri,
        val containerHint: ContainerHint,
        val extension: String?,
        val declaredMimeType: String?
    )

    enum class ContainerHint {
        DASH,
        HLS,
        SMOOTH_STREAMING,
        MKV,
        MP4,
        TS,
        OTHER
    }

    data class DecoderFailure(
        val decoderName: String,
        val mimeType: String?,
        val diagnosticInfo: String?
    )

    fun updateDescriptor(uri: Uri, contentType: Int, declaredMime: String?) {
        descriptor = PlaybackDescriptor(
            uri = uri,
            containerHint = inferContainer(uri, contentType),
            extension = uri.lastPathSegment?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.ifEmpty { null },
            declaredMimeType = declaredMime
        )
        runtimeBlacklist.clear()
        activeDecoders.clear()
        lastDecoderUsed = null
        Media3Diagnostics.logPlaybackDescriptor(descriptor)
    }

    fun isDecoderAllowed(name: String, mimeType: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        if (staticBlacklist.any { normalized.startsWith(it) }) {
            return false
        }
        if (runtimeBlacklist.contains(normalized)) {
            return false
        }

        val hint = descriptor?.containerHint
        if (hint == ContainerHint.MKV && hevcMimes.contains(mimeType)) {
            // Dolby HEVC implementations on some vendors crash with Matroska payloads.
            if (normalized.contains("dolby")) {
                return false
            }
        }
        return true
    }

    fun decoderPreferenceScore(
        name: String,
        hardwareAccelerated: Boolean,
        softwareOnly: Boolean
    ): Int {
        val normalized = name.lowercase(Locale.ROOT)
        val vendorIndex = vendorPriority.indexOfFirst { normalized.startsWith(it) }
        val vendorScore = if (vendorIndex == -1) UNKNOWN_VENDOR_PRIORITY else vendorPriority.size - vendorIndex
        val hardwareScore = if (hardwareAccelerated) 2 else 0
        val softwarePenalty = if (softwareOnly || normalized.startsWith("omx.google")) 1 else 0
        return vendorScore * 100 + hardwareScore * 10 - softwarePenalty
    }

    fun decoderFailureFromException(
        error: PlaybackException,
        fallbackMimeType: String?
    ): DecoderFailure? {
        val cause = error.cause
        val direct = when (cause) {
            is MediaCodecDecoderException -> {
                val decoderName = cause.codecInfo?.name ?: return null
                DecoderFailure(decoderName, fallbackMimeType, cause.diagnosticInfo)
            }
            is MediaCodecRenderer.DecoderInitializationException -> {
                val decoderName = cause.codecInfo?.name ?: return null
                DecoderFailure(decoderName, cause.mimeType ?: fallbackMimeType, cause.diagnosticInfo)
            }
            else -> null
        }
        if (direct != null) {
            return direct
        }
        val guessedMime = fallbackMimeType ?: descriptor?.declaredMimeType
        val guessedDecoder = guessDecoderForMime(guessedMime)
        if (guessedDecoder != null) {
            return DecoderFailure(guessedDecoder, guessedMime, cause?.message ?: error.message)
        }
        val fallbackDecoder = lastDecoderUsed
        return fallbackDecoder?.let {
            DecoderFailure(it, guessedMime, cause?.message ?: error.message)
        }
    }

    fun recordDecoderFailure(failure: DecoderFailure): Boolean {
        val normalized = failure.decoderName.lowercase(Locale.ROOT)
        val added = runtimeBlacklist.add(normalized)
        if (added) {
            Media3Diagnostics.logDecoderBlacklisted(failure.decoderName, failure.diagnosticInfo)
        }
        return added
    }

    fun markPreferredDecoder(mimeType: String, decoderName: String) {
        val mimeKey = mimeType.lowercase(Locale.ROOT)
        val normalized = decoderName.lowercase(Locale.ROOT)
        activeDecoders[mimeKey] = normalized
        lastDecoderUsed = normalized
    }

    private fun guessDecoderForMime(mimeType: String?): String? {
        val key = mimeType?.lowercase(Locale.ROOT) ?: return null
        return activeDecoders[key]
    }

    private fun inferContainer(uri: Uri, contentType: Int): ContainerHint {
        return when (contentType) {
            C.CONTENT_TYPE_DASH -> ContainerHint.DASH
            C.CONTENT_TYPE_HLS -> ContainerHint.HLS
            C.CONTENT_TYPE_SS -> ContainerHint.SMOOTH_STREAMING
            else -> extensionHint(uri)
        }
    }

    private fun extensionHint(uri: Uri): ContainerHint {
        val segment = uri.lastPathSegment?.lowercase(Locale.ROOT) ?: return ContainerHint.OTHER
        return when {
            segment.endsWith(".mkv") || segment.endsWith(".mk3d") || segment.endsWith(".webm") -> ContainerHint.MKV
            segment.endsWith(".mp4") || segment.endsWith(".m4v") || segment.endsWith(".mov") || segment.endsWith(".ismv") -> ContainerHint.MP4
            segment.endsWith(".ts") || segment.endsWith(".m2ts") || segment.endsWith(".mts") -> ContainerHint.TS
            else -> ContainerHint.OTHER
        }
    }
}
