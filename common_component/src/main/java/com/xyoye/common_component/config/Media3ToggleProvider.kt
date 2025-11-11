package com.xyoye.common_component.config

import com.xyoye.common_component.BuildConfig
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import java.util.UUID

object Media3ToggleProvider {

    fun snapshot(
        appliesToSession: String? = null,
        overrideValue: Boolean? = null
    ): RolloutToggleSnapshot {
        val remoteValue = AppConfig.isMedia3EnabledRemote()
        val now = System.currentTimeMillis()
        val (value, source) = when {
            overrideValue != null -> overrideValue to Media3RolloutSource.MANUAL_OVERRIDE
            remoteValue -> true to Media3RolloutSource.REMOTE_CONFIG
            BuildConfig.MEDIA3_ENABLED_FALLBACK -> true to Media3RolloutSource.GRADLE_FALLBACK
            else -> false to Media3RolloutSource.REMOTE_CONFIG
        }
        return RolloutToggleSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            value = value,
            source = source,
            evaluatedAt = now,
            appliesToSession = appliesToSession
        )
    }

    fun isEnabled(): Boolean {
        val remoteValue = AppConfig.isMedia3EnabledRemote()
        return remoteValue || BuildConfig.MEDIA3_ENABLED_FALLBACK
    }
}
