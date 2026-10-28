package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreference
import com.xyoye.common_component.base.BasePreferenceFragmentCompat
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.config.DeveloperCredentialStore
import com.xyoye.common_component.extension.addToClipboard
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.log.BuglyReporter
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.http.HttpLogServerState
import com.xyoye.common_component.log.http.model.HttpDegradeMode
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.PolicySource
import com.xyoye.common_component.preference.MappingPreferenceDataStore
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SecurityHelperConfig
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.utils.formatFileSize
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.user_component.R
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者设置页，配置日志与调试相关选项。
 */
class DeveloperSettingFragment : BasePreferenceFragmentCompat() {
    companion object {
        fun newInstance() = DeveloperSettingFragment()

        private const val TAG = "DeveloperSetting"
        private const val SUBTITLE_TAG = "DeveloperSubtitle"
        private const val KEY_APP_LOG_ENABLE = "app_log_enable"
        private const val KEY_HTTP_LOG_SERVER_ENABLE = "http_log_server_enable"
        private const val KEY_HTTP_LOG_SERVER_ADDRESS = "http_log_server_address"
        private const val KEY_HTTP_LOG_SERVER_TOKEN = "http_log_server_token"
        private const val KEY_HTTP_LOG_SERVER_RESET_TOKEN = "http_log_server_reset_token"
        private const val KEY_HTTP_LOG_SERVER_CLEAR_LOGS = "http_log_server_clear_logs"
        private const val KEY_HTTP_LOG_SERVER_RETENTION_DAYS = "http_log_server_retention_days"
        private const val KEY_HTTP_LOG_SERVER_STORAGE_USAGE = "http_log_server_storage_usage"
        private const val KEY_LOG_LEVEL = "developer_log_level"
        private const val KEY_BUGLY_STATUS = "bugly_status"
        private const val KEY_BUGLY_TEST_REPORT = "bugly_test_report"
        private const val KEY_CREDENTIAL_PLAINTEXT_FALLBACK = "developer_credential_plaintext_fallback"
        private const val KEY_CREDENTIAL_MIGRATE_PLAINTEXT = "developer_credential_migrate_plaintext"
        private const val KEY_SUBTITLE_SESSION_STATUS = "subtitle_session_status"
        private const val SUBTITLE_STATUS_PROVIDER =
            "com.xyoye.player.subtitle.debug.PlaybackSessionStatusProvider"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private var httpLogServerIpText: String = "-"

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
        initBuglyTestPreference()
        initDeveloperCredentialPreferences()

        if (requireContext().isTelevisionUiMode()) {
            view.post { requestPreferenceItemFocus(KEY_HTTP_LOG_SERVER_ENABLE) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLogPreferenceState()
        updateSubtitleSessionSummary()
        refreshHttpLogServerDetailPreferences(LogSystem.getHttpLogServerState())
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

        initHttpLogServerPreference()
        initHttpLogServerDetailPreferences()
    }

    private fun initHttpLogServerPreference() {
        findPreference<SwitchPreference>(KEY_HTTP_LOG_SERVER_ENABLE)?.apply {
            refreshHttpLogServerPreferenceState(this, LogSystem.getHttpLogServerState())
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                val updated = LogSystem.setHttpLogServerEnabled(enable)
                isChecked = updated.enabled
                refreshHttpLogServerPreferenceState(this, updated)
                refreshHttpLogServerDetailPreferences(updated)

                if (enable) {
                    if (updated.running) {
                        ToastCenter.showSuccess(getString(R.string.developer_http_log_server_toast_on))
                    } else {
                        val reason = updated.lastError ?: "-"
                        ToastCenter.showError(getString(R.string.developer_http_log_server_toast_on_failed, reason))
                    }
                } else {
                    ToastCenter.showSuccess(getString(R.string.developer_http_log_server_toast_off))
                }
                false
            }
        }
    }

    private fun initHttpLogServerDetailPreferences() {
        findPreference<Preference>(KEY_HTTP_LOG_SERVER_TOKEN)?.apply {
            summary = getString(R.string.developer_http_log_server_token_summary_empty)
            setOnPreferenceClickListener {
                val token = LogSystem.getHttpLogServerState().token
                if (token.isNotBlank()) {
                    token.addToClipboard()
                    ToastCenter.showSuccess(getString(R.string.developer_http_log_server_token_toast_copied))
                }
                true
            }
        }

        findPreference<Preference>(KEY_HTTP_LOG_SERVER_RESET_TOKEN)?.apply {
            setOnPreferenceClickListener {
                CommonDialog
                    .Builder(requireActivity())
                    .apply {
                        content = getString(R.string.developer_http_log_server_reset_token_confirm)
                        addPositive {
                            it.dismiss()
                            val updated = LogSystem.resetHttpLogServerToken()
                            findPreference<SwitchPreference>(KEY_HTTP_LOG_SERVER_ENABLE)?.let { pref ->
                                refreshHttpLogServerPreferenceState(pref, updated)
                            }
                            refreshHttpLogServerDetailPreferences(updated)
                            ToastCenter.showSuccess(getString(R.string.developer_http_log_server_reset_token_toast))
                        }
                        addNegative { dialog -> dialog.dismiss() }
                    }.build()
                    .show()
                true
            }
        }

        findPreference<Preference>(KEY_HTTP_LOG_SERVER_CLEAR_LOGS)?.apply {
            setOnPreferenceClickListener {
                CommonDialog
                    .Builder(requireActivity())
                    .apply {
                        content = getString(R.string.developer_http_log_server_clear_logs_confirm)
                        addPositive {
                            it.dismiss()
                            val updated = LogSystem.clearHttpLogServerLogs()
                            refreshHttpLogServerDetailPreferences(updated)
                            ToastCenter.showSuccess(getString(R.string.developer_http_log_server_clear_logs_toast))
                        }
                        addNegative { dialog -> dialog.dismiss() }
                    }.build()
                    .show()
                true
            }
        }

        findPreference<ListPreference>(KEY_HTTP_LOG_SERVER_RETENTION_DAYS)?.apply {
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                val requestedDays = (newValue as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                val updated = LogSystem.setHttpLogRetentionDays(requestedDays)
                refreshHttpLogServerDetailPreferences(updated)
                val label = resolveRetentionLabel(updated.retention.days)
                when {
                    updated.degradeMode == HttpDegradeMode.PERSISTENCE_PAUSED -> {
                        ToastCenter.showWarning(getString(R.string.developer_http_log_server_persistence_paused_toast))
                    }
                    updated.retention.days != requestedDays -> {
                        ToastCenter.showWarning(getString(R.string.developer_http_log_server_retention_fallback_toast, label))
                    }
                    else -> {
                        ToastCenter.showSuccess(getString(R.string.developer_http_log_server_retention_toast, label))
                    }
                }
                false
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
                val runtimeAppId = BuglyReporter.getAppId().orEmpty()
                summary =
                    if (statusInfo.isInitialized) {
                        if (statusInfo.isDebugMode) {
                            getString(R.string.developer_bugly_status_on_debug)
                        } else if (runtimeAppId.isBlank()) {
                            getString(R.string.developer_bugly_status_runtime_missing)
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
                        val statusSnapshot = SecurityHelperConfig.getBuglyStatusInfo()
                        val runtimeAppIdSnapshot = BuglyReporter.getAppId().orEmpty()
                        val runtimeVersionSnapshot = BuglyReporter.getVersion(requireContext()).orEmpty()
                        val runtimeReady = runtimeAppIdSnapshot.isNotBlank()
                        val message =
                            buildString {
                                append(getString(R.string.developer_bugly_status_detail_header)).append("\n\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_state,
                                        when {
                                            statusSnapshot.isInitialized && runtimeReady -> "✅ 已初始化"
                                            statusSnapshot.isInitialized -> "⚠ 已配置但未初始化"
                                            else -> "❌ 未配置"
                                        },
                                    ),
                                ).append("\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_app_id,
                                        if (statusSnapshot.isDebugMode) "test_debug_id (测试模式)" else statusSnapshot.appId,
                                    ),
                                ).append("\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_runtime_app_id,
                                        runtimeAppIdSnapshot.ifBlank { "-" },
                                    ),
                                ).append("\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_runtime_version,
                                        runtimeVersionSnapshot.ifBlank { "-" },
                                    ),
                                ).append("\n")
                                append(getString(R.string.developer_bugly_status_detail_source, statusSnapshot.source)).append("\n")
                                append(
                                    getString(
                                        R.string.developer_bugly_status_detail_debug,
                                        if (statusSnapshot.isDebugMode) "是" else "否",
                                    ),
                                ).append("\n\n")
                                if (statusSnapshot.isInitialized) {
                                    if (runtimeReady) {
                                        append(getString(R.string.developer_bugly_status_detail_working)).append("\n")
                                    } else {
                                        append(getString(R.string.developer_bugly_status_detail_runtime_missing)).append("\n")
                                    }
                                    if (statusSnapshot.isDebugMode) {
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

    private fun initBuglyTestPreference() {
        findPreference<Preference>(KEY_BUGLY_TEST_REPORT)?.apply {
            title = getString(R.string.developer_bugly_test_report_title)
            summary = getString(R.string.developer_bugly_test_report_summary)
            setOnPreferenceClickListener {
                if (!SecurityHelperConfig.isConfigured()) {
                    ToastCenter.showError(getString(R.string.developer_bugly_test_report_not_configured))
                    return@setOnPreferenceClickListener true
                }

                val now = dateFormat.format(Date())
                ErrorReportHelper.postException(
                    message = "Bugly test report at $now",
                    tag = "BuglyTest",
                )
                ToastCenter.showSuccess(getString(R.string.developer_bugly_test_report_toast))
                true
            }
        }
    }

    private fun initDeveloperCredentialPreferences() {
        initCredentialPlaintextFallbackPreference()
        initCredentialMigratePreference()
    }

    private fun initCredentialPlaintextFallbackPreference() {
        findPreference<SwitchPreference>(KEY_CREDENTIAL_PLAINTEXT_FALLBACK)?.apply {
            val switchPreference = this
            isVisible = DeveloperCredentialStore.isPlaintextFallbackSwitchVisible()
            if (!isVisible) {
                return@apply
            }

            isChecked = DeveloperCredentialStore.isPlaintextFallbackEnabled()
            summary =
                if (isChecked) {
                    getString(R.string.developer_credential_plaintext_fallback_summary_on)
                } else {
                    getString(R.string.developer_credential_plaintext_fallback_summary_off)
                }
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                if (enabled) {
                    CommonDialog
                        .Builder(requireActivity())
                        .apply {
                            content = getString(R.string.developer_credential_plaintext_fallback_confirm_message)
                            addPositive {
                                it.dismiss()
                                applyCredentialPlaintextFallback(enabled = true, preference = switchPreference)
                            }
                            addNegative { dialog -> dialog.dismiss() }
                        }.build()
                        .show()
                    return@setOnPreferenceChangeListener false
                }

                applyCredentialPlaintextFallback(enabled = false, preference = switchPreference)
                true
            }
        }
    }

    private fun applyCredentialPlaintextFallback(
        enabled: Boolean,
        preference: SwitchPreference
    ) {
        DeveloperCredentialStore.setPlaintextFallbackEnabled(enabled)
        val effectiveEnabled = DeveloperCredentialStore.isPlaintextFallbackEnabled()
        preference.isChecked = effectiveEnabled
        preference.summary =
            if (effectiveEnabled) {
                getString(R.string.developer_credential_plaintext_fallback_summary_on)
            } else {
                getString(R.string.developer_credential_plaintext_fallback_summary_off)
            }

        if (effectiveEnabled) {
            ToastCenter.showWarning(getString(R.string.developer_credential_plaintext_fallback_toast_enabled))
        } else {
            ToastCenter.showSuccess(getString(R.string.developer_credential_plaintext_fallback_toast_disabled))
        }
    }

    private fun initCredentialMigratePreference() {
        findPreference<Preference>(KEY_CREDENTIAL_MIGRATE_PLAINTEXT)?.apply {
            isVisible = DeveloperCredentialStore.isPlaintextFallbackSwitchVisible()
            if (!isVisible) {
                return@apply
            }

            refreshCredentialMigrateSummary(this)
            setOnPreferenceClickListener {
                val migrationResult = DeveloperCredentialStore.migrateLegacyPlaintextCredentials()
                when {
                    migrationResult.migratedCount > 0 && migrationResult.failedCount == 0 -> {
                        ToastCenter.showSuccess(
                            getString(
                                R.string.developer_credential_migrate_toast_success,
                                migrationResult.migratedCount,
                            ),
                        )
                    }

                    migrationResult.migratedCount > 0 -> {
                        ToastCenter.showWarning(
                            getString(
                                R.string.developer_credential_migrate_toast_partial,
                                migrationResult.migratedCount,
                                migrationResult.failedCount,
                            ),
                        )
                    }

                    else -> {
                        ToastCenter.showSuccess(getString(R.string.developer_credential_migrate_toast_no_data))
                    }
                }
                refreshCredentialMigrateSummary(this)
                true
            }
        }
    }

    private fun refreshCredentialMigrateSummary(preference: Preference) {
        preference.summary =
            if (DeveloperCredentialStore.hasLegacyPlaintextCredentials()) {
                getString(R.string.developer_credential_migrate_summary_pending)
            } else {
                getString(R.string.developer_credential_migrate_summary_clean)
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
        findPreference<SwitchPreference>(KEY_HTTP_LOG_SERVER_ENABLE)?.let {
            refreshHttpLogServerPreferenceState(it, LogSystem.getHttpLogServerState())
        }
    }

    private fun refreshHttpLogServerPreferenceState(
        preference: SwitchPreference,
        state: HttpLogServerState
    ) {
        preference.isChecked = state.enabled
        updateHttpLogServerSummary(preference, state)
        if (state.enabled && state.running) {
            refreshHttpLogServerIpTextAsync()
        }
    }

    private fun updateHttpLogServerSummary(
        preference: SwitchPreference,
        state: HttpLogServerState
    ) {
        preference.summary =
            when {
                !state.enabled -> getString(R.string.developer_http_log_server_summary_off)
                !state.running -> getString(R.string.developer_http_log_server_summary_error, state.lastError ?: "-")
                else -> {
                    val port = if (state.boundPort > 0) state.boundPort else state.requestedPort
                    val ipText = state.ipAddresses.joinToString(separator = "\n").ifBlank { httpLogServerIpText.ifBlank { "-" } }
                    getString(
                        R.string.developer_http_log_server_summary_on,
                        port,
                        ipText,
                    )
                }
            }
    }

    private fun refreshHttpLogServerDetailPreferences(state: HttpLogServerState) {
        val port = if (state.boundPort > 0) state.boundPort else state.requestedPort

        findPreference<Preference>(KEY_HTTP_LOG_SERVER_ADDRESS)?.apply {
            summary =
                if (!state.enabled || !state.running) {
                    getString(R.string.developer_http_log_server_address_summary_off)
                } else {
                    val urls =
                        state.ipAddresses
                            .map { ip -> "http://${wrapHttpHost(ip)}:$port/api/v1/logs/download?token=<token>" }
                            .ifEmpty { listOf("http://<ip>:$port/api/v1/logs/download?token=<token>") }
                    urls.joinToString(separator = "\n")
                }
        }

        findPreference<Preference>(KEY_HTTP_LOG_SERVER_TOKEN)?.apply {
            summary = state.token.ifBlank { getString(R.string.developer_http_log_server_token_summary_empty) }
        }

        findPreference<ListPreference>(KEY_HTTP_LOG_SERVER_RETENTION_DAYS)?.apply {
            value = state.retention.days.toString()
            summary = resolveRetentionLabel(state.retention.days)
        }

        findPreference<Preference>(KEY_HTTP_LOG_SERVER_STORAGE_USAGE)?.apply {
            val used = formatFileSize(state.storeUsedBytes)
            val max = formatFileSize(state.retention.maxBytes)
            val base = getString(R.string.developer_http_log_server_usage_summary_format, used, max)
            summary =
                if (state.degradeMode == HttpDegradeMode.PERSISTENCE_PAUSED) {
                    val extra = state.message?.takeIf { it.isNotBlank() }.orEmpty()
                    if (extra.isBlank()) base else base + "\n" + extra
                } else {
                    base
                }
        }
    }

    private fun resolveRetentionLabel(days: Int): String =
        when (days) {
            14 -> getString(R.string.developer_http_log_retention_14d)
            30 -> getString(R.string.developer_http_log_retention_30d)
            else -> getString(R.string.developer_http_log_retention_7d)
        }

    private fun wrapHttpHost(raw: String): String {
        val host = raw.trim()
        if (host.isEmpty()) return raw
        return if (host.contains(':') && !host.startsWith("[") && !host.endsWith("]")) "[$host]" else host
    }

    private fun refreshHttpLogServerIpTextAsync() {
        SupervisorScope.IO.launch {
            val ipText = resolveLocalIpText()
            SupervisorScope.Main.launch uiLaunch@{
                if (!isAdded) return@uiLaunch
                httpLogServerIpText = ipText
                val state = LogSystem.getHttpLogServerState()
                findPreference<SwitchPreference>(KEY_HTTP_LOG_SERVER_ENABLE)?.let { pref ->
                    updateHttpLogServerSummary(pref, state)
                }
            }
        }
    }

    private fun resolveLocalIpText(): String {
        val ipv4 = mutableListOf<String>()
        val ipv6 = mutableListOf<String>()
        try {
            val element = NetworkInterface.getNetworkInterfaces()
            while (element.hasMoreElements()) {
                val networkInterface = element.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) {
                        continue
                    }

                    val ip = address.hostAddress?.toString().orEmpty()
                    if (ip.isEmpty()) continue

                    if (address is Inet4Address) {
                        ipv4.add(ip)
                    } else {
                        ipv6.add(ip)
                    }
                }
            }
        } catch (e: SocketException) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "DeveloperSettingFragment",
                "resolveLocalIpText",
                "Failed to resolve local IP addresses",
            )
        }

        val all = ipv4 + ipv6
        return if (all.isEmpty()) "-" else all.joinToString(separator = "\n")
    }

    private class DeveloperSettingDataStore : MappingPreferenceDataStore(
        dataStoreName = "DeveloperSettingDataStore",
        booleanReaders =
            mapOf(
                KEY_APP_LOG_ENABLE to { LogSystem.getRuntimeState().debugSessionEnabled },
                KEY_HTTP_LOG_SERVER_ENABLE to { LogSystem.getHttpLogServerState().enabled },
            ),
        booleanWriters =
            mapOf(
                KEY_APP_LOG_ENABLE to {
                    DevelopConfig.putDdLogEnable(LogSystem.getRuntimeState().debugSessionEnabled)
                },
            ),
        stringReaders =
            mapOf(
                KEY_LOG_LEVEL to {
                    LogSystem
                        .getRuntimeState()
                        .activePolicy.defaultLevel.name
                },
            ),
        stringWriters =
            mapOf(
                KEY_LOG_LEVEL to { value ->
                    value?.let { raw ->
                        runCatching { LogLevel.valueOf(raw) }.getOrNull()?.let { level ->
                            val current = LogSystem.getRuntimeState()
                            LogSystem.updateLoggingPolicy(
                                current.activePolicy.copy(defaultLevel = level),
                                PolicySource.USER_OVERRIDE,
                            )
                        }
                    }
                },
            ),
    )

    private fun requestPreferenceItemFocus(key: String) {
        val preference = findPreference<Preference>(key) ?: return
        val recyclerView = listView ?: return
        val position = findPreferenceAdapterPosition(preference)
        if (position < 0) return

        recyclerView.scrollToPosition(position)
        recyclerView.post {
            if (!isAdded) return@post
            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    private fun findPreferenceAdapterPosition(target: Preference): Int {
        val screen = preferenceScreen ?: return -1
        val flat = ArrayList<Preference>(64)
        for (i in 0 until screen.preferenceCount) {
            flattenPreference(screen.getPreference(i), flat)
        }
        val visible = flat.filter { it.isVisible }
        return visible.indexOf(target)
    }

    private fun flattenPreference(
        preference: Preference,
        output: MutableList<Preference>,
    ) {
        output.add(preference)
        if (preference is PreferenceGroup) {
            for (i in 0 until preference.preferenceCount) {
                flattenPreference(preference.getPreference(i), output)
            }
        }
    }
}
