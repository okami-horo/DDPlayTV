package com.xyoye.player.kernel.impl.media3

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.Locale

@SuppressLint("InlinedApi")
@UnstableApi
object Media3FormatUtil {
    /**
     * 根据后缀和常见信号推断 MIME，并在不支持 Dolby Vision 时回退到 HEVC。
     * 仅做“播放尽量成功”优化，不做严格语义保证。
     */
    fun normalizeMime(
        context: Context,
        uri: Uri
    ): String? {
        val lower = uri.toString().lowercase(Locale.getDefault())
        val infer =
            when {
                lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v") -> null // 容器内再判
                lower.endsWith(".mkv") -> null
                lower.endsWith(".hevc") || lower.endsWith(".h265") -> MimeTypes.VIDEO_H265
                lower.endsWith(".h264") || lower.endsWith(".avc") -> MimeTypes.VIDEO_H264
                lower.endsWith(".av1") || lower.contains("av01") -> MimeTypes.VIDEO_AV1
                lower.contains("dvhe") || lower.contains("dvh1") || lower.contains("dav1") -> MimeTypes.VIDEO_DOLBY_VISION
                lower.endsWith(".vc1") || lower.contains("wvc1") -> "video/wvc1"
                else -> null
            }
        return resolveDolbyVisionFallback(context, infer)
    }

    private fun resolveDolbyVisionFallback(
        context: Context,
        mime: String?
    ): String? {
        if (mime != MimeTypes.VIDEO_DOLBY_VISION) return mime

        val dvSupported = hasDecoder(MimeTypes.VIDEO_DOLBY_VISION)
        val displayHdr = getDisplayHdrTypes(context)
        val displaySupportsDv = displayHdr.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        if ((!dvSupported || !displaySupportsDv) && hasDecoder(MimeTypes.VIDEO_H265)) {
            // 设备/显示不支持 DV，降级 HEVC（若显示也不支持 HDR，将由显示侧做 SDR 输出）
            Media3Diagnostics.logFormatDowngrade(
                MimeTypes.VIDEO_DOLBY_VISION,
                MimeTypes.VIDEO_H265,
                if (!dvSupported) "decoder_absent" else "display_not_hdr_dv",
            )
            return MimeTypes.VIDEO_H265
        }
        return mime
    }

    /**
     * 根据显示 HDR 能力返回一个“偏好视频 MIME 类型”序列，用于 TrackSelector 优先级设置。
     * 目标：有可用的 HDR/DV 时优先选用对应轨；不支持时优先选择更兼容的 SDR（H.264/AV1）。
     */
    fun preferredVideoMimeTypes(context: Context): List<String> {
        val hdrTypes = getDisplayHdrTypes(context).toSet()
        val supportsDv = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        val supportsHdr10Plus = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
        val supportsHdr10 = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
        val supportsHlg = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HLG)

        return when {
            supportsDv ->
                listOf(
                    MimeTypes.VIDEO_DOLBY_VISION,
                    MimeTypes.VIDEO_H265, // HDR10/10+ 常见封装
                    MimeTypes.VIDEO_AV1,
                    MimeTypes.VIDEO_H264,
                )
            supportsHdr10Plus || supportsHdr10 || supportsHlg ->
                listOf(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_AV1,
                    MimeTypes.VIDEO_H264,
                )
            else ->
                listOf(
                    MimeTypes.VIDEO_H264,
                    MimeTypes.VIDEO_AV1,
                    MimeTypes.VIDEO_H265,
                )
        }
    }

    private fun hasDecoder(mime: String): Boolean =
        try {
            MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
        } catch (_: Exception) {
            false
        }

    fun getDisplayHdrTypes(context: Context): IntArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return intArrayOf()
        }
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.displays?.firstOrNull()
            display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        } catch (_: Exception) {
            intArrayOf()
        }
    }

    data class DolbyVisionDescriptor(
        val codecPrefix: String,
        val profile: Int,
        val level: Int,
        val compatibilityId: Int?
    )

    fun describeDolbyVision(format: Format): DolbyVisionDescriptor? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION && format.codecs.isNullOrEmpty()) {
            return null
        }
        return parseDolbyVisionCodecs(format.codecs)
    }

    fun hasHdr10Fallback(descriptor: DolbyVisionDescriptor?): Boolean {
        descriptor ?: return false
        return when (descriptor.profile) {
            5, 7 -> true
            8 -> descriptor.compatibilityId == COMPATIBILITY_HDR10
            else -> false
        }
    }

    fun hasHlgFallback(descriptor: DolbyVisionDescriptor?): Boolean {
        descriptor ?: return false
        return descriptor.profile == 8 && descriptor.compatibilityId == COMPATIBILITY_HLG
    }

    fun hasSdrFallback(descriptor: DolbyVisionDescriptor?): Boolean {
        descriptor ?: return false
        return descriptor.profile == 8 && descriptor.compatibilityId == COMPATIBILITY_SDR
    }

    private fun parseDolbyVisionCodecs(codecs: String?): DolbyVisionDescriptor? {
        if (codecs.isNullOrEmpty()) return null
        val normalized = codecs.lowercase(Locale.US)
        val prefix = dvCodecsPrefixes.firstOrNull { normalized.startsWith(it) } ?: return null
        val tokens = normalized.split('.')
        if (tokens.size < 3) return null
        val profile = tokens.getOrNull(1)?.toIntOrNull() ?: return null
        val level = tokens.getOrNull(2)?.toIntOrNull() ?: Format.NO_VALUE
        val compatibility = tokens.getOrNull(3)?.toIntOrNull()
        return DolbyVisionDescriptor(prefix, profile, level, compatibility)
    }

    private val dvCodecsPrefixes = listOf("dvhe", "dvh1", "dav1")

    // Dolby Vision profile 8 compatibility ids follow dovi_tool/ISO BMFF conventions.
    private const val COMPATIBILITY_SDR = 1
    private const val COMPATIBILITY_HDR10 = 2
    private const val COMPATIBILITY_HLG = 4
}
