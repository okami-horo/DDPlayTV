package com.xyoye.user_component.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BasePreferenceFragmentCompat
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.preference.MappingPreferenceDataStore
import com.xyoye.common_component.utils.AppUtils
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/23.
 */

class AppSettingFragment : BasePreferenceFragmentCompat() {
    companion object {
        private const val KEY_HIDE_FILE = "hide_file"
        private const val KEY_REMOTE_ANIME_GROUPING = "remote_anime_grouping"
        private const val KEY_SPLASH_PAGE = "splash_page"
        private const val KEY_BACKUP_DOMAIN_ENABLE = "backup_domain_enable"
        private const val KEY_BACKUP_DOMAIN_ADDRESS = "backup_domain_address"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_LICENSE = "license"
        private const val KEY_ABOUT_US = "about_us"

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
        val backupDomainAddress = findPreference<EditTextPreference>(KEY_BACKUP_DOMAIN_ADDRESS)

        findPreference<Preference>(KEY_DARK_MODE)?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.SwitchTheme)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>(KEY_APP_VERSION)?.apply {
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

        findPreference<Preference>(KEY_LICENSE)?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.License)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>(KEY_ABOUT_US)?.apply {
            setOnPreferenceClickListener {
                ARouter
                    .getInstance()
                    .build(RouteTable.User.AboutUs)
                    .navigation()
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<SwitchPreference>(KEY_BACKUP_DOMAIN_ENABLE)?.apply {
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

    private class AppSettingDataStore :
        MappingPreferenceDataStore(
            dataStoreName = "AppSettingDataStore",
            booleanReaders =
                mapOf(
                    KEY_HIDE_FILE to { AppConfig.isShowHiddenFile() },
                    KEY_REMOTE_ANIME_GROUPING to { AppConfig.isRemoteAnimeGrouping() },
                    KEY_SPLASH_PAGE to { AppConfig.isShowSplashAnimation() },
                    KEY_BACKUP_DOMAIN_ENABLE to { AppConfig.isBackupDomainEnable() },
                ),
            booleanWriters =
                mapOf(
                    KEY_HIDE_FILE to { value -> AppConfig.putShowHiddenFile(value) },
                    KEY_REMOTE_ANIME_GROUPING to { value -> AppConfig.putRemoteAnimeGrouping(value) },
                    KEY_SPLASH_PAGE to { value -> AppConfig.putShowSplashAnimation(value) },
                    KEY_BACKUP_DOMAIN_ENABLE to { value -> AppConfig.putBackupDomainEnable(value) },
                ),
            stringReaders =
                mapOf(
                    KEY_BACKUP_DOMAIN_ADDRESS to { AppConfig.getBackupDomain() },
                ),
            stringWriters =
                mapOf(
                    KEY_BACKUP_DOMAIN_ADDRESS to { value ->
                        AppConfig.putBackupDomain(value ?: Api.DAN_DAN_SPARE)
                    },
                ),
        )
}
