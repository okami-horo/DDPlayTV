package com.xyoye.player.kernel.impl.mpv

internal object MpvOptions {
    const val VO_GPU = "gpu"
    const val VO_GPU_NEXT = "gpu-next"
    const val VO_MEDIACODEC_EMBED = "mediacodec_embed"

    fun resolveVideoOutput(configured: String?): String {
        val normalized = configured.orEmpty().trim()
        return when {
            normalized.equals(VO_GPU_NEXT, ignoreCase = true) -> VO_GPU_NEXT
            normalized.equals(VO_MEDIACODEC_EMBED, ignoreCase = true) -> VO_MEDIACODEC_EMBED
            else -> VO_GPU
        }
    }

    fun isGpuVideoOutput(configured: String?): Boolean =
        when (resolveVideoOutput(configured)) {
            VO_GPU, VO_GPU_NEXT -> true
            else -> false
        }

    fun isAnime4kSupportedVideoOutput(configured: String?): Boolean = resolveVideoOutput(configured) == VO_GPU
}
