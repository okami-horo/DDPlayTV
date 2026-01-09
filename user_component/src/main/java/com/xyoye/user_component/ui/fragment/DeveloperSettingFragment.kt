package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.PolicySource
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SecurityHelperConfig
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.user_component.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者设置页，配置日志与调试相关选项。
 */
class DeveloperSettingFragment : PreferenceFragmentCompat() {
    companion object {
        fun newInstance() = DeveloperSettingFragment()

        private const val TAG = "DeveloperSetting"
        private const val SUBTITLE_TAG = "DeveloperSubtitle"
        private const val KEY_APP_LOG_ENABLE = "app_log_enable"
        private const val KEY_LOG_LEVEL = "developer_log_level"
        private const val KEY_BUGLY_STATUS = "bugly_status"
        private const val KEY_SUBTITLE_SESSION_STATUS = "subtitle_session_status"
        private const val SUBTITLE_STATUS_PROVIDER =
            "com.xyoye.player.subtitle.debug.PlaybackSessionStatusProvider"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        try {
            preferenceManager.preferenceDataStore = DeveloperSettingDataStore()
            addPreferencesFromResource(R.xml.preference_developer_setting)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "DeveloperSettingFragment",
                "onCreatePreferences",
                "加载开发者设置失败",
            )
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        initLogPreferences()
        initSubtitleDebugPreferences()
        initBuglyStatusPreference()
    }

    override fun onResume() {
        super.onResume()
        refreshLogPreferenceState()
        updateSubtitleSessionSummary()
    }

    private fun initLogPreferences() {
        findPreference<ListPreference>(KEY_LOG_LEVEL)?.apply {
            val runtime = LogSystem.getRuntimeState()
            updateLogLevelPreference(this, runtime.activePolicy.defaultLevel)
            setOnPreferenceChangeListener { _, newValue ->
                val level =
                    (newValue as? String)?.let { raw ->
                        runCatching { LogLevel.valueOf(raw) }.getOrNull()
                    } ?: return@setOnPreferenceChangeListener false
                val current = LogSystem.getRuntimeState()
                LogSystem.updateLoggingPolicy(
                    current.activePolicy.copy(defaultLevel = level),
                    PolicySource.USER_OVERRIDE,
                )
                updateLogLevelPreference(this, level)
                true
            }
        }

        findPreference<SwitchPreference>(KEY_APP_LOG_ENABLE)?.apply {
            val summaryOn = getString(R.string.developer_app_log_enable_summary_on)
            val summaryOff = getString(R.string.developer_app_log_enable_summary_off)
            updateLogSummary(this, summaryOn, summaryOff)
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                val updatedState =
                    if (enable) {
                        LogSystem.startDebugSession()
                    } else {
                        LogSystem.stopDebugSession()
                    }
                val effective = updatedState.debugSessionEnabled
                summary = if (effective) summaryOn else summaryOff
                DevelopConfig.putDdLogEnable(effective)
                LogFacade.i(
                    LogModule.USER,
                    TAG,
                    "toggle debug session enable=$enable state=${updatedState.debugToggleState}",
                )
                true
            }
        }
    }

    private fun initSubtitleDebugPreferences() {
        findPreference<Preference>(KEY_SUBTITLE_SESSION_STATUS)?.apply {
            summary = buildSubtitleStatusSummary()
            setOnPreferenceClickListener {
                summary = buildSubtitleStatusSummary()
                true
            }
        }

    }

    private fun initBuglyStatusPreference() {
        findPreference<Preference>(KEY_BUGLY_STATUS)?.apply {
            try {
                title = getString(R.string.developer_bugly_status_title)
                val statusInfo = SecurityHelperConfig.getBuglyStatusInfo()
                summary =
                    if (statusInfo.isInitialized) {
                        if (statusInfo.isDebugMode) {
                            getString(R.string.developer_bugly_status_on_debug)
                        } else {
                            val shortId =
                                if (statusInfo.appId.length > 8) {
                                    statusInfo.appId.substring(0, 8) + "..."
                                } else {
                                    statusInfo.appId
                                }
                            getString(R.string.developer_bugly_status_on, shortId, statusInfo.source)
                        }
                    } else {
                        getString(R.string.developer_bugly_status_off)
                    }

                setOnPreferenceClickListener {
                    try {
                        val statusInfo = SecurityHelperConfig.getBuglyStatusInfo()
                        val message =
                            buildString {
                                append(getString(R.string.developer_bugly_status_detail_header)).append("\n\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_state,
                                        if (statusInfo.isInitialized) "✅ 已初始化" else "❌ 未初始化",
                                    ),
                                ).append("\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_app_id,
                                        if (statusInfo.isDebugMode) "test_debug_id (测试模式)" else statusInfo.appId,
                                    ),
                                ).append("\n")
                                append(getString(R.string.developer_bugly_status_detail_source, statusInfo.source)).append("\n")
                                append(
                                    getString(R.string.developer_bugly_status_detail_debug, if (statusInfo.isDebugMode) "是" else "否"),
                                ).append("\n\n")
                                if (statusInfo.isInitialized) {
                                    append(getString(R.string.developer_bugly_status_detail_working)).append("\n")
                                    if (statusInfo.isDebugMode) {
                                        append(getString(R.string.developer_bugly_status_detail_notice))
                                    }
                                } else {
                                    append(getString(R.string.developer_bugly_status_detail_missing))
                                }
                            }
                        ToastCenter.showSuccess(message)
                    } catch (e: Exception) {
                        ErrorReportHelper.postCatchedExceptionWithContext(
                            e,
                            "DeveloperSettingFragment",
                            "bugly_status_click",
                            "Failed to show Bugly status information",
                        )
                        ToastCenter.showError(getString(R.string.developer_bugly_status_failed))
                    }
                    true
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "DeveloperSettingFragment",
                    "bugly_status_setup",
                    "Failed to setup Bugly status preference",
                )
            }
        }
    }

    private fun buildSubtitleStatusSummary(): String =
        runCatching {
            val providerClass = Class.forName(SUBTITLE_STATUS_PROVIDER)
            val snapshot = providerClass.getMethod("snapshot").invoke(null)
            val statusClass = snapshot.javaClass

            fun <T> read(name: String): T? =
                runCatching {
                    statusClass.getMethod("get$name").invoke(snapshot) as? T
                }.getOrNull()
            val videoSize = read<Any>("VideoSizePx")
            val width = videoSize?.javaClass?.getMethod("getWidth")?.invoke(videoSize) as? Int ?: 0
            val height = videoSize?.javaClass?.getMethod("getHeight")?.invoke(videoSize) as? Int ?: 0
            val backend = (read<Any>("ResolvedBackend") as? Enum<*>)?.name ?: "-"
            val surface = (read<Any>("SurfaceType") as? Enum<*>)?.name ?: "-"
            val sessionId = read<String>("SessionId").orEmpty()
            val startedAt = read<Long>("StartedAtEpochMs") ?: 0L
            val startedText =
                if (startedAt > 0L) {
                    dateFormat.format(Date(startedAt))
                } else {
                    getString(R.string.developer_subtitle_session_status_summary_empty)
                }
            val firstRenderedAt = read<Long>("FirstRenderedAtEpochMs")
            val firstLatency =
                if (firstRenderedAt != null && startedAt > 0) {
                    "${firstRenderedAt - startedAt}ms"
                } else {
                    "-"
                }
            val fallbackTriggered = read<Boolean>("FallbackTriggered") == true
            val fallbackReason = (read<Any>("FallbackReasonCode") as? Enum<*>)?.name
            val fallbackValue =
                when {
                    fallbackTriggered && !fallbackReason.isNullOrEmpty() -> fallbackReason
                    fallbackTriggered -> "已触发"
                    else -> "未回退"
                }
            val lastError = read<String>("LastErrorMessage") ?: "-"
            getString(
                R.string.developer_subtitle_status_summary,
                backend,
                surface,
                width,
                height,
                sessionId.takeLast(8),
                startedText,
                firstLatency,
                fallbackValue,
                lastError,
            )
        }.getOrElse {
            LogFacade.w(LogModule.SUBTITLE, SUBTITLE_TAG, "failed to read subtitle session status: ${it.message}")
            getString(R.string.developer_subtitle_session_status_summary_empty)
        }

    private fun forceFallback() {
        // Legacy backend fallback disabled.
    }

    private fun updateSubtitleSessionSummary() {
        findPreference<Preference>(KEY_SUBTITLE_SESSION_STATUS)?.summary = buildSubtitleStatusSummary()
    }

    private fun updateLogSummary(
        preference: SwitchPreference,
        summaryOn: String,
        summaryOff: String
    ) {
        val runtime = LogSystem.getRuntimeState()
        preference.isChecked = runtime.debugSessionEnabled
        preference.summary = if (runtime.debugSessionEnabled) summaryOn else summaryOff
    }

    private fun updateLogLevelPreference(
        preference: ListPreference,
        level: LogLevel
    ) {
        preference.value = level.name
        preference.summary =
            getString(
                R.string.developer_log_level_summary,
                resolveLogLevelLabel(level),
            )
    }

    private fun resolveLogLevelLabel(level: LogLevel): String =
        when (level) {
            LogLevel.DEBUG -> getString(R.string.developer_log_level_entry_debug)
            LogLevel.INFO -> getString(R.string.developer_log_level_entry_info)
            LogLevel.WARN -> getString(R.string.developer_log_level_entry_warn)
            LogLevel.ERROR -> getString(R.string.developer_log_level_entry_error)
        }

    private fun refreshLogPreferenceState() {
        val summaryOn = getString(R.string.developer_app_log_enable_summary_on)
        val summaryOff = getString(R.string.developer_app_log_enable_summary_off)
        findPreference<SwitchPreference>(KEY_APP_LOG_ENABLE)?.let {
            updateLogSummary(it, summaryOn, summaryOff)
        }
        findPreference<ListPreference>(KEY_LOG_LEVEL)?.let {
            val level = LogSystem.getRuntimeState().activePolicy.defaultLevel
            updateLogLevelPreference(it, level)
        }
    }

    private class DeveloperSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(
            key: String?,
            defValue: Boolean
        ): Boolean =
            when (key) {
                KEY_APP_LOG_ENABLE -> LogSystem.getRuntimeState().debugSessionEnabled
                else -> defValue
            }

        override fun putBoolean(
            key: String?,
            value: Boolean
        ) {
            when (key) {
                KEY_APP_LOG_ENABLE -> {
                    DevelopConfig.putDdLogEnable(LogSystem.getRuntimeState().debugSessionEnabled)
                }
            }
        }

        override fun getString(
            key: String?,
            defValue: String?
        ): String? =
            when (key) {
                KEY_LOG_LEVEL ->
                    LogSystem
                        .getRuntimeState()
                        .activePolicy.defaultLevel.name
                else -> defValue
            }

        override fun putString(
            key: String?,
            value: String?
        ) {
            when (key) {
                KEY_LOG_LEVEL ->
                    value?.let { raw ->
                        runCatching { LogLevel.valueOf(raw) }.getOrNull()?.let { level ->
                            val current = LogSystem.getRuntimeState()
                            LogSystem.updateLoggingPolicy(
                                current.activePolicy.copy(defaultLevel = level),
                                PolicySource.USER_OVERRIDE,
                            )
                        }
                    }
            }
        }
    }
}
