package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.RendererPreferenceSource
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.log.AppLogger
import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.user_component.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者设置页，配置日志上传相关选项。
 */
class DeveloperSettingFragment : PreferenceFragmentCompat() {

    companion object {
        fun newInstance() = DeveloperSettingFragment()

        private const val KEY_LOG_UPLOAD_ENABLE = "log_upload_enable"
        private const val KEY_LOG_UPLOAD_URL = "log_upload_url"
        private const val KEY_LOG_UPLOAD_USERNAME = "log_upload_username"
        private const val KEY_LOG_UPLOAD_PASSWORD = "log_upload_password"
        private const val KEY_LOG_UPLOAD_REMOTE_PATH = "log_upload_remote_path"
        private const val KEY_LOG_UPLOAD_TRIGGER = "log_upload_trigger"
        private const val KEY_APP_LOG_ENABLE = "app_log_enable"
        private const val KEY_SUBTITLE_TELEMETRY_LOG_ENABLE = "subtitle_telemetry_log_enable"
        private const val KEY_SUBTITLE_SESSION_STATUS = "subtitle_session_status"
        private const val KEY_SUBTITLE_FORCE_FALLBACK = "subtitle_force_fallback"
        private const val SUBTITLE_STATUS_PROVIDER =
            "com.xyoye.player.subtitle.debug.PlaybackSessionStatusProvider"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        try {
            preferenceManager.preferenceDataStore = DeveloperSettingDataStore()
            addPreferencesFromResource(R.xml.preference_developer_setting)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "DeveloperSettingFragment",
                "onCreatePreferences",
                "加载开发者设置失败"
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initLogUploadPreferences()
        initSubtitleDebugPreferences()
    }

    override fun onResume() {
        super.onResume()
        updateLastUploadSummary()
        updateSubtitleSessionSummary()
    }

    private fun initLogUploadPreferences() {
        findPreference<SwitchPreference>(KEY_APP_LOG_ENABLE)?.apply {
            val summaryOn = getString(R.string.developer_app_log_enable_summary_on)
            val summaryOff = getString(R.string.developer_app_log_enable_summary_off)
            summary = if (isChecked) summaryOn else summaryOff
            DDLog.setEnable(isChecked)
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                summary = if (enable) summaryOn else summaryOff
                DDLog.setEnable(enable)
                DDLog.i("DEV-LogUpload", "toggle ddlog enable=$enable")
                true
            }
        }

        findPreference<SwitchPreference>(KEY_LOG_UPLOAD_ENABLE)?.apply {
            summary = if (isChecked) {
                getString(R.string.developer_log_upload_enable_summary_on)
            } else {
                getString(R.string.developer_log_upload_enable_summary_off)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                summary = if (enable) {
                    getString(R.string.developer_log_upload_enable_summary_on)
                } else {
                    getString(R.string.developer_log_upload_enable_summary_off)
                }
                DDLog.i("DEV-LogUpload", "toggle enable=$enable")
                true
            }
        }

        findPreference<EditTextPreference>(KEY_LOG_UPLOAD_URL)?.apply {
            summary = DevelopConfig.getLogUploadUrl() ?: ""
            setOnBindEditTextListener { editText ->
                editText.hint = getString(R.string.developer_log_upload_url_hint)
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            setOnPreferenceChangeListener { _, newValue ->
                val url = (newValue as? String)?.trim().orEmpty()
                if (!validateUrl(url)) {
                    ToastCenter.showError(getString(R.string.developer_log_upload_url_invalid))
                    return@setOnPreferenceChangeListener false
                }
                summary = url
                DDLog.i("DEV-LogUpload", "update url length=${url.length}")
                true
            }
        }

        findPreference<EditTextPreference>(KEY_LOG_UPLOAD_USERNAME)?.apply {
            summary = maskCredential(DevelopConfig.getLogUploadUsername())
            setOnBindEditTextListener {
                it.hint = getString(R.string.developer_log_upload_username_hint)
                it.inputType = InputType.TYPE_CLASS_TEXT
            }
            setOnPreferenceChangeListener { _, newValue ->
                val username = (newValue as? String)?.trim().orEmpty()
                summary = maskCredential(username)
                DDLog.i("DEV-LogUpload", "update username length=${username.length}")
                true
            }
        }

        findPreference<EditTextPreference>(KEY_LOG_UPLOAD_PASSWORD)?.apply {
            summary = maskCredential(DevelopConfig.getLogUploadPassword())
            setOnBindEditTextListener {
                it.hint = getString(R.string.developer_log_upload_password_hint)
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                val password = (newValue as? String)?.trim().orEmpty()
                summary = maskCredential(password)
                DDLog.i("DEV-LogUpload", "update password length=${password.length}")
                true
            }
        }

        findPreference<EditTextPreference>(KEY_LOG_UPLOAD_REMOTE_PATH)?.apply {
            summary = DevelopConfig.getLogUploadRemotePath() ?: ""
            setOnBindEditTextListener {
                it.hint = getString(R.string.developer_log_upload_remote_path_hint)
                it.inputType = InputType.TYPE_CLASS_TEXT
            }
            setOnPreferenceChangeListener { _, newValue ->
                val remotePath = (newValue as? String)?.trim().orEmpty()
                summary = remotePath
                DDLog.i("DEV-LogUpload", "update remote path=${remotePath}")
                true
            }
        }

        findPreference<Preference>(KEY_LOG_UPLOAD_TRIGGER)?.apply {
            setOnPreferenceClickListener {
                DDLog.i("DEV-LogUpload", "manual trigger upload")
                AppLogger.triggerUpload("manual")
                ToastCenter.showSuccess(getString(R.string.developer_log_upload_trigger_toast))
                updateLastUploadSummary()
                true
            }
        }

        updateLastUploadSummary()
    }

    private fun initSubtitleDebugPreferences() {
        findPreference<SwitchPreference>(KEY_SUBTITLE_TELEMETRY_LOG_ENABLE)?.apply {
            val summaryOn = getString(R.string.developer_subtitle_telemetry_log_enable_summary_on)
            val summaryOff = getString(R.string.developer_subtitle_telemetry_log_enable_summary_off)
            summary = if (isChecked) summaryOn else summaryOff
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                summary = if (enable) summaryOn else summaryOff
                SubtitleTelemetryLogger.setEnable(enable)
                DDLog.i("DEV-Subtitle", "toggle telemetry log enable=$enable")
                true
            }
        }

        findPreference<Preference>(KEY_SUBTITLE_SESSION_STATUS)?.apply {
            summary = buildSubtitleStatusSummary()
            setOnPreferenceClickListener {
                summary = buildSubtitleStatusSummary()
                true
            }
        }

        findPreference<Preference>(KEY_SUBTITLE_FORCE_FALLBACK)?.apply {
            setOnPreferenceClickListener {
                forceFallback()
                true
            }
        }
    }

    private fun updateLastUploadSummary() {
        val lastUploadPreference = findPreference<Preference>(KEY_LOG_UPLOAD_TRIGGER) ?: return
        val lastTime = DevelopConfig.getLogUploadLastTime()
        if (lastTime <= 0L) {
            lastUploadPreference.summary = getString(R.string.developer_log_upload_trigger_summary_empty)
        } else {
            val time = dateFormat.format(Date(lastTime))
            lastUploadPreference.summary = getString(R.string.developer_log_upload_trigger_summary, time)
        }
    }

    private fun validateUrl(url: String): Boolean {
        if (url.isEmpty()) {
            return true
        }
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun maskCredential(value: String?): String {
        if (value.isNullOrEmpty()) {
            return getString(R.string.developer_log_upload_credential_empty)
        }
        return getString(R.string.developer_log_upload_credential_masked)
    }

    private fun buildSubtitleStatusSummary(): String {
        return runCatching {
            val providerClass = Class.forName(SUBTITLE_STATUS_PROVIDER)
            val snapshot = providerClass.getMethod("snapshot").invoke(null)
            val statusClass = snapshot.javaClass
            fun <T> read(name: String): T? {
                return runCatching {
                    statusClass.getMethod("get$name").invoke(snapshot) as? T
                }.getOrNull()
            }
            val videoSize = read<Any>("VideoSizePx")
            val width = videoSize?.javaClass?.getMethod("getWidth")?.invoke(videoSize) as? Int ?: 0
            val height = videoSize?.javaClass?.getMethod("getHeight")?.invoke(videoSize) as? Int ?: 0
            val backend = (read<Any>("ResolvedBackend") as? Enum<*>)?.name ?: "-"
            val surface = (read<Any>("SurfaceType") as? Enum<*>)?.name ?: "-"
            val sessionId = read<String>("SessionId").orEmpty()
            val startedAt = read<Long>("StartedAtEpochMs") ?: 0L
            val startedText = if (startedAt > 0L) {
                dateFormat.format(Date(startedAt))
            } else {
                getString(R.string.developer_subtitle_session_status_summary_empty)
            }
            val firstRenderedAt = read<Long>("FirstRenderedAtEpochMs")
            val firstLatency = if (firstRenderedAt != null && startedAt > 0) {
                "${firstRenderedAt - startedAt}ms"
            } else {
                "-"
            }
            val fallbackTriggered = read<Boolean>("FallbackTriggered") == true
            val fallbackReason = (read<Any>("FallbackReasonCode") as? Enum<*>)?.name
            val fallbackValue = when {
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
                lastError
            )
        }.getOrElse {
            DDLog.w("DEV-Subtitle", "failed to read subtitle session status: ${it.message}")
            getString(R.string.developer_subtitle_session_status_summary_empty)
        }
    }

    private fun forceFallback() {
        SubtitlePreferenceUpdater.persistBackend(
            SubtitleRendererBackend.LEGACY_CANVAS,
            RendererPreferenceSource.LOCAL_SETTINGS
        )
        runCatching {
            val providerClass = Class.forName(SUBTITLE_STATUS_PROVIDER)
            val updateBackend = providerClass.getMethod(
                "updateBackend",
                SubtitleRendererBackend::class.java
            )
            updateBackend.invoke(null, SubtitleRendererBackend.LEGACY_CANVAS)
            val markFallback = providerClass.getMethod(
                "markFallback",
                SubtitleFallbackReason::class.java,
                Throwable::class.java
            )
            markFallback.invoke(null, SubtitleFallbackReason.USER_REQUEST, null)
        }.onFailure {
            DDLog.w("DEV-Subtitle", "force fallback reflection failed: ${it.message}")
        }
        ToastCenter.showSuccess(getString(R.string.developer_subtitle_force_fallback_toast))
        updateSubtitleSessionSummary()
    }

    private fun updateSubtitleSessionSummary() {
        findPreference<Preference>(KEY_SUBTITLE_SESSION_STATUS)?.summary = buildSubtitleStatusSummary()
    }

    private class DeveloperSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                KEY_APP_LOG_ENABLE -> DevelopConfig.isDdLogEnable()
                KEY_SUBTITLE_TELEMETRY_LOG_ENABLE -> DevelopConfig.isSubtitleTelemetryLogEnable()
                KEY_LOG_UPLOAD_ENABLE -> DevelopConfig.isLogUploadEnable()
                else -> defValue
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                KEY_APP_LOG_ENABLE -> {
                    DevelopConfig.putDdLogEnable(value)
                    DDLog.setEnable(value)
                }
                KEY_SUBTITLE_TELEMETRY_LOG_ENABLE -> {
                    DevelopConfig.putSubtitleTelemetryLogEnable(value)
                    SubtitleTelemetryLogger.setEnable(value)
                }
                KEY_LOG_UPLOAD_ENABLE -> DevelopConfig.putLogUploadEnable(value)
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                KEY_LOG_UPLOAD_URL -> DevelopConfig.getLogUploadUrl() ?: defValue
                KEY_LOG_UPLOAD_USERNAME -> DevelopConfig.getLogUploadUsername() ?: defValue
                KEY_LOG_UPLOAD_PASSWORD -> DevelopConfig.getLogUploadPassword() ?: defValue
                KEY_LOG_UPLOAD_REMOTE_PATH -> DevelopConfig.getLogUploadRemotePath() ?: defValue
                else -> defValue
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                KEY_LOG_UPLOAD_URL -> DevelopConfig.putLogUploadUrl(value ?: "")
                KEY_LOG_UPLOAD_USERNAME -> DevelopConfig.putLogUploadUsername(value ?: "")
                KEY_LOG_UPLOAD_PASSWORD -> DevelopConfig.putLogUploadPassword(value ?: "")
                KEY_LOG_UPLOAD_REMOTE_PATH -> DevelopConfig.putLogUploadRemotePath(value?.trim('/').orEmpty())
            }
        }
    }
}
