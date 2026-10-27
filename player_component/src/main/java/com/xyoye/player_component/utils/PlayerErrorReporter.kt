package com.xyoye.player_component.utils

import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.log.privacy.SensitiveDataSanitizer
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.player.info.PlayerInitializer

object PlayerErrorReporter {
    @JvmStatic
    fun report(
        throwable: Throwable,
        className: String,
        methodName: String,
        extraInfo: String = ""
    ) {
        ErrorReportHelper.postCatchedExceptionWithContext(
            throwable,
            className,
            methodName,
            enrich(extraInfo)
        )
    }

    private fun enrich(extraInfo: String): String {
        val session =
            ARouter
                .getInstance()
                .navigation(PlaybackRequestService::class.java)
                ?.observePlayback()
                ?.value
        val engine = session?.playerType?.name ?: PlayerInitializer.playerType.name
        val sessionId = session?.version?.sessionId ?: "UNKNOWN"
        val sourceType = session?.source?.mediaType?.name ?: "UNKNOWN"
        val mediaId =
            session?.source?.mediaId?.takeIf { it.isNotBlank() }?.let {
                SensitiveDataSanitizer.sanitizeValueForKey(
                    "mediaId",
                    it
                )
            }

        return buildString {
            append("engine=").append(engine)
            append(", sessionId=").append(sessionId)
            session?.let { append(", sourceRevision=").append(it.version.sourceRevision) }
            append(", sourceType=").append(sourceType)
            if (!mediaId.isNullOrBlank()) {
                append(", mediaId=").append(mediaId)
            }
            if (extraInfo.isNotBlank()) {
                append(" | ").append(extraInfo)
            }
        }
    }
}
