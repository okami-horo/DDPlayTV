package com.xyoye.user_component.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.utils.AppUtils
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/23.
 */

class AppSettingFragment : PreferenceFragmentCompat() {
    companion object {
        fun newInstance() = AppSettingFragment()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        try {
            preferenceManager.preferenceDataStore = AppSettingDataStore()
            addPreferencesFromResource(R.xml.preference_app_setting)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "AppSettingFragment",
                "onCreatePreferences",
                "Failed to create preferences from resource",
            )
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        val backupDomainAddress = findPreference<EditTextPreference>("backup_domain_address")

        findPreference<Preference>("dark_mode")?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.SwitchTheme)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("app_version")?.apply {
            try {
                summary = AppUtils.getVersionName()
                isSelectable = false
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppSettingFragment",
                    "app_version_setup",
                    "Failed to setup app version preference",
                )
            }
        }

        findPreference<Preference>("license")?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.License)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>("about_us")?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.AboutUs)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<SwitchPreference>("backup_domain_enable")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                backupDomainAddress?.isVisible = newValue as Boolean
                return@setOnPreferenceChangeListener true
            }
            backupDomainAddress?.isVisible = isChecked
        }

        backupDomainAddress?.apply {
            summary = AppConfig.getBackupDomain()
            setOnPreferenceChangeListener { _, newValue ->
                val newAddress = newValue as String
                if (checkDomainUrl(newAddress)) {
                    summary = newAddress
                    return@setOnPreferenceChangeListener true
                }
                return@setOnPreferenceChangeListener false
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }

    private fun checkDomainUrl(url: String): Boolean {
        if (TextUtils.isEmpty(url)) {
            ToastCenter.showError("地址保存失败，地址为空")
            return false
        }
        val uri = Uri.parse(url)
        if (TextUtils.isEmpty(uri.scheme)) {
            ToastCenter.showError("地址保存失败，协议错误")
            return false
        }
        if (TextUtils.isEmpty(uri.host)) {
            ToastCenter.showError("地址保存失败，域名错误")
            return false
        }
        if (uri.port == -1) {
            ToastCenter.showError("地址保存失败，端口错误")
            return false
        }
        return true
    }

    inner class AppSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(
            key: String?,
            defValue: Boolean
        ): Boolean =
            try {
                when (key) {
                    "hide_file" -> AppConfig.isShowHiddenFile()
                    "splash_page" -> AppConfig.isShowSplashAnimation()
                    "backup_domain_enable" -> AppConfig.isBackupDomainEnable()
                    else -> super.getBoolean(key, defValue)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppSettingDataStore",
                    "getBoolean",
                    "Failed to get boolean value for key: $key",
                )
                defValue
            }

        override fun putBoolean(
            key: String?,
            value: Boolean
        ) {
            try {
                when (key) {
                    "hide_file" -> AppConfig.putShowHiddenFile(value)
                    "splash_page" -> AppConfig.putShowSplashAnimation(value)
                    "backup_domain_enable" -> AppConfig.putBackupDomainEnable(value)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppSettingDataStore",
                    "putBoolean",
                    "Failed to put boolean value for key: $key, value: $value",
                )
            }
        }

        override fun getString(
            key: String?,
            defValue: String?
        ): String? =
            try {
                when (key) {
                    "backup_domain_address" -> AppConfig.getBackupDomain()
                    else -> super.getString(key, defValue)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppSettingDataStore",
                    "getString",
                    "Failed to get string value for key: $key",
                )
                defValue
            }

        override fun putString(
            key: String?,
            value: String?
        ) {
            try {
                when (key) {
                    "backup_domain_address" -> AppConfig.putBackupDomain(value ?: Api.DAN_DAN_SPARE)
                    else -> super.putString(key, value)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppSettingDataStore",
                    "putString",
                    "Failed to put string value for key: $key, value: $value",
                )
            }
        }
    }
}
