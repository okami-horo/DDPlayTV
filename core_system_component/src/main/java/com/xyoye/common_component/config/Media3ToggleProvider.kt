package com.xyoye.common_component.config

import com.xyoye.core_system_component.BuildConfig
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import java.util.UUID

object Media3ToggleProvider {
    fun snapshot(
        appliesToSession: String? = null,
        overrideValue: Boolean? = null
    ): RolloutToggleSnapshot {
        val now = System.currentTimeMillis()
        val (value, source) =
            when {
                overrideValue != null -> overrideValue to Media3RolloutSource.MANUAL_OVERRIDE
                else -> BuildConfig.MEDIA3_ENABLED_FALLBACK to Media3RolloutSource.GRADLE_FALLBACK
            }
        return RolloutToggleSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            value = value,
            source = source,
            evaluatedAt = now,
            appliesToSession = appliesToSession,
        )
    }

    fun isEnabled(): Boolean = BuildConfig.MEDIA3_ENABLED_FALLBACK
}
