package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.log.AppLogger
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
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
    }

    override fun onResume() {
        super.onResume()
        updateLastUploadSummary()
    }

    private fun initLogUploadPreferences() {
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
                true
            }
        }

        findPreference<Preference>(KEY_LOG_UPLOAD_TRIGGER)?.apply {
            setOnPreferenceClickListener {
                AppLogger.triggerUpload()
                ToastCenter.showSuccess(getString(R.string.developer_log_upload_trigger_toast))
                updateLastUploadSummary()
                true
            }
        }

        updateLastUploadSummary()
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

    private class DeveloperSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                KEY_LOG_UPLOAD_ENABLE -> DevelopConfig.isLogUploadEnable()
                else -> defValue
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
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
