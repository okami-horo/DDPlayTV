package com.xyoye.common_component.playback.addon

data class PlaybackRecoveryRequest(
    val identity: PlaybackIdentity,
    val positionMs: Long,
    val playbackError: Throwable?,
    /**
     * 播放器侧可观测的诊断信息（来源可能包含 Media3/播放器内核）。
     * 仅传递事实，不在通用层固化“某个存储专属字段”。
     */
    val diagnostics: Map<String, String> = emptyMap()
)

interface PlaybackUrlRecoverableAddon : PlaybackAddon {
    /**
     * 返回值语义：
     * - Result.success(playUrl)：表示需要播放器重建 Source，并以该 URL 重新起播（续播到 positionMs）。
     * - Result.success(null)：表示无法恢复（由播放器走通用错误处理）。
     * - Result.failure(e)：表示恢复过程中发生异常（用于上报/日志）。
     */
    suspend fun recover(request: PlaybackRecoveryRequest): Result<String?>
}
