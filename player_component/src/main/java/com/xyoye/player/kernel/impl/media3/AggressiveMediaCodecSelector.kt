package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule

@UnstableApi
class AggressiveMediaCodecSelector(
    private val policy: Media3CodecPolicy = Media3CodecPolicy
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val tried = LinkedHashSet<Pair<String, Boolean>>() // mime + secure
        val candidates = mutableListOf<MediaCodecInfo>()

        fun appendFor(
            mime: String,
            secure: Boolean
        ) {
            val key = Pair(mime, secure)
            if (!tried.add(key)) return
            try {
                val infos = MediaCodecUtil.getDecoderInfos(mime, secure, requiresTunnelingDecoder)
                candidates.addAll(infos)
            } catch (e: MediaCodecUtil.DecoderQueryException) {
                LogFacade.w(
                    LogModule.PLAYER,
                    TAG,
                    "Skip decoder query for mime=$mime, secure=$secure, tunneling=$requiresTunnelingDecoder: ${e.message}",
                )
            }
        }

        Media3Diagnostics.logSecureRequirement(mimeType, requiresSecureDecoder, DrmPolicy.allowInsecureFallback)

        // 主 MIME
        appendFor(mimeType, requiresSecureDecoder)
        // 别名 MIME
        aliasMap[mimeType]?.forEach { appendFor(it, requiresSecureDecoder) }

        // 若 secure 没有结果，尝试非 secure 降级（仅在策略允许时）。
        if (requiresSecureDecoder && candidates.isEmpty()) {
            if (DrmPolicy.allowInsecureFallback) {
                LogFacade.i(LogModule.PLAYER, TAG, "No secure decoder for $mimeType, trying non-secure fallback (override)")
                Media3Diagnostics.logDrmFallbackDecision(
                    mimeType,
                    // allowed =
                    true,
                    "explicit override: allowInsecureFallback=true",
                )
                appendFor(mimeType, false)
                aliasMap[mimeType]?.forEach { appendFor(it, false) }
            } else {
                Media3Diagnostics.logDrmFallbackDecision(
                    mimeType,
                    // allowed =
                    false,
                    "secure decoder required for DRM session",
                )
            }
        }

        // DV -> HEVC 回退：当 DV 全部缺失时再试 HEVC
        if (candidates.isEmpty() && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            LogFacade.i(LogModule.PLAYER, TAG, "No DV decoder found, trying HEVC fallback (secure=$requiresSecureDecoder)")
            appendFor(MimeTypes.VIDEO_H265, requiresSecureDecoder)
            aliasMap[MimeTypes.VIDEO_H265]?.forEach { appendFor(it, requiresSecureDecoder) }
            if (requiresSecureDecoder && candidates.isEmpty()) {
                if (DrmPolicy.allowInsecureFallback) {
                    LogFacade.i(LogModule.PLAYER, TAG, "No secure HEVC decoder for DV fallback, trying non-secure HEVC (override)")
                    Media3Diagnostics.logDrmFallbackDecision(
                        MimeTypes.VIDEO_H265,
                        // allowed =
                        true,
                        "explicit override: allowInsecureFallback=true",
                    )
                    appendFor(MimeTypes.VIDEO_H265, false)
                    aliasMap[MimeTypes.VIDEO_H265]?.forEach { appendFor(it, false) }
                } else {
                    Media3Diagnostics.logDrmFallbackDecision(
                        MimeTypes.VIDEO_H265,
                        // allowed =
                        false,
                        "secure decoder required for DRM session",
                    )
                }
            }
        }

        // 去重后按“硬件优先、c2.* 优先、名称”排序，尽量命中靠谱实现
        val ordered =
            candidates
                .distinctBy { it.name }
                .sortedWith(
                    compareByDescending<MediaCodecInfo> {
                        policy.decoderPreferenceScore(it.name, it.hardwareAccelerated, it.softwareOnly)
                    }.thenByDescending { it.hardwareAccelerated }
                        .thenBy { if (it.softwareOnly) 1 else 0 }
                        .thenBy { it.name },
                )

        val allowed = ordered.filter { policy.isDecoderAllowed(it.name, mimeType) }
        val base = if (allowed.isNotEmpty()) allowed else ordered

        // 先尝试所有硬件/非纯软解码器，再兜底纯软解，避免一开始就落到软解
        val hardwareFirst = base.filter { it.hardwareAccelerated || !it.softwareOnly }
        val softwareOnly = base.filter { it.softwareOnly }
        val prioritized = if (hardwareFirst.isNotEmpty()) hardwareFirst + softwareOnly else base

        Media3Diagnostics.logDecoderCandidates(mimeType, requiresSecureDecoder, prioritized)
        prioritized.firstOrNull()?.let { selected ->
            Media3Diagnostics.logDecoderSelected(mimeType, requiresSecureDecoder, selected)
            policy.markPreferredDecoder(mimeType, selected.name)
        }

        return prioritized
    }

    companion object {
        private const val TAG = "AggressiveCodecSelector"

        private val aliasMap: Map<String, List<String>> =
            mapOf(
                // 部分厂商对 VC-1 的 MIME 命名不一致
                "video/wvc1" to listOf("video/VC1", "video/vc1"),
                "video/VC1" to listOf("video/wvc1", "video/vc1"),
                "video/vc1" to listOf("video/wvc1", "video/VC1"),
                // Dolby Vision 有些解码器只暴露 HEVC，也尝试 DV 的另一命名
                MimeTypes.VIDEO_DOLBY_VISION to listOf(MimeTypes.VIDEO_H265, "video/dvhe", "video/dvh1", "video/dav1"),
                // HEVC 也尝试 DV，某些流标记为 DV 但设备仅 HEVC
                MimeTypes.VIDEO_H265 to listOf(MimeTypes.VIDEO_DOLBY_VISION),
                // AV1 大小写/别名兼容
                "video/av01" to listOf("video/AV1"),
                MimeTypes.VIDEO_AV1 to listOf("video/av01", "video/AV1"),
            )
    }
}
