package com.xyoye.common_component.media3

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.xyoye.common_component.log.BuglyReporter
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot

/**
 * Tags Bugly crash reports with Media3 rollout and session metadata so crashes/ANRs
 * can be segmented by cohort during the migration.
 */
object Media3CrashTagger {
    private var reporter: CrashReporterBridge = BuglyCrashReporter()

    fun init() {
        reporter.setUserSceneTag(SCENE_MEDIA3)
        reporter.putUserData(KEY_BOOT_ENABLED, Media3ToggleProvider.isEnabled().toString())
    }

    fun tagSnapshot(snapshot: RolloutToggleSnapshot) {
        reporter.putUserData(KEY_TOGGLE_VALUE, snapshot.value.toString())
        reporter.putUserData(KEY_TOGGLE_SOURCE, snapshot.source.name)
        snapshot.appliesToSession?.let {
            reporter.putUserData(KEY_SNAPSHOT_SESSION, it)
        }
    }

    fun tagSession(session: PlaybackSession) {
        reporter.putUserData(KEY_SESSION_ID, session.sessionId)
        reporter.putUserData(KEY_MEDIA_ID, session.mediaId)
        reporter.putUserData(KEY_SOURCE_TYPE, session.sourceType.name)
        reporter.putUserData(KEY_PLAYER_ENGINE, session.playerEngine.name)
        reporter.putUserData(KEY_TOGGLE_COHORT, session.toggleCohort?.name ?: "UNKNOWN")
    }

    @VisibleForTesting
    fun setReporterForTest(fake: CrashReporterBridge) {
        reporter = fake
    }

    @VisibleForTesting
    fun resetReporterForTest() {
        reporter = BuglyCrashReporter()
    }

    interface CrashReporterBridge {
        fun putUserData(
            key: String,
            value: String
        )

        fun setUserSceneTag(tagId: Int)
    }

    private class BuglyCrashReporter : CrashReporterBridge {
        private val context: Context
            get() = BaseApplication.getAppContext()

        override fun putUserData(
            key: String,
            value: String
        ) {
            BuglyReporter.putUserData(context, key, value.take(MAX_VALUE_LENGTH))
        }

        override fun setUserSceneTag(tagId: Int) {
            BuglyReporter.setUserSceneTag(context, tagId)
        }
    }

    private const val MAX_VALUE_LENGTH = 200
    private const val SCENE_MEDIA3 = 30_003
    private const val KEY_PREFIX = "media3."
    private const val KEY_PLAYER_ENGINE = "${KEY_PREFIX}player_engine"
    private const val KEY_TOGGLE_COHORT = "${KEY_PREFIX}toggle_cohort"
    private const val KEY_TOGGLE_VALUE = "${KEY_PREFIX}toggle_value"
    private const val KEY_TOGGLE_SOURCE = "${KEY_PREFIX}toggle_source"
    private const val KEY_SESSION_ID = "${KEY_PREFIX}session_id"
    private const val KEY_MEDIA_ID = "${KEY_PREFIX}media_id"
    private const val KEY_SOURCE_TYPE = "${KEY_PREFIX}source_type"
    private const val KEY_SNAPSHOT_SESSION = "${KEY_PREFIX}snapshot_session"
    private const val KEY_BOOT_ENABLED = "${KEY_PREFIX}boot_enabled"
}
