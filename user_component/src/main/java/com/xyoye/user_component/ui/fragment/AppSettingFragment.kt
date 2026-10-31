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

        fun newInstance(section: String? = null) =
            AppSettingFragment().apply { arguments = androidx.core.os.bundleOf("section" to section) }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        try {
            preferenceManager.preferenceDataStore = AppSettingDataStore()
            addPreferencesFromResource(R.xml.preference_app_setting)
            val section = arguments?.getString("section")
            if (section != null) {
                val mediaKeys = setOf("metadata_settings", KEY_HIDE_FILE, KEY_REMOTE_ANIME_GROUPING)
                val aboutKeys = setOf("app_version", "license", "about_us")

                fun filter(group: androidx.preference.PreferenceGroup) {
                    for (index in 0 until group.preferenceCount) {
                        val item = group.getPreference(index)
                        if (item is androidx.preference.PreferenceGroup) {
                            filter(item)
                            item.isVisible = (0 until item.preferenceCount).any { item.getPreference(it).isVisible }
                        } else {
                            item.isVisible = item.key in if (section == "media") mediaKeys else aboutKeys
                        }
                    }
                }
                filter(preferenceScreen)
                val visible = mutableListOf<Preference>()

                fun collect(group: androidx.preference.PreferenceGroup) {
                    (0 until group.preferenceCount).map(group::getPreference).forEach { item ->
                        if (item is androidx.preference.PreferenceGroup) {
                            collect(item)
                        } else if (item.isVisible) {
                            group.removePreference(item)
                            visible.add(item)
                        }
                    }
                }
                collect(preferenceScreen)
                preferenceScreen.removeAll()
                visible.forEach { preferenceScreen.addPreference(it) }
                preferenceScreen.addPreference(
                    Preference(requireContext()).apply {
                        title = if (section == "media") "管理媒体来源" else "账户与工具"
                        summary = if (section == "media") "本地文件与网络媒体" else "账户、收藏、字幕搜索与应用工具"
                        setOnPreferenceClickListener {
                            if (section == "media") {
                                requireActivity().supportFragmentManager.setFragmentResult(
                                    RouteTable.App.CinemaNavigation,
                                    androidx.core.os.bundleOf(
                                        "page" to "library"
                                    )
                                )
                            } else {
                                com.alibaba.android.arouter.launcher.ARouter
                                    .getInstance()
                                    .build(RouteTable.User.AccountTools)
                                    .navigation()
                            }
                            true
                        }
                    }
                )
                if (section == "about") {
                    preferenceScreen.addPreference(
                        Preference(requireContext()).apply {
                            title = "应用高级设置"
                            summary = "主题、启动页与网络服务"
                            setOnPreferenceClickListener {
                                ARouter.getInstance().build(RouteTable.User.SettingApp).navigation()
                                true
                            }
                        }
                    )
                }
            }
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
        findPreference<Preference>("metadata_settings")?.setOnPreferenceClickListener {
            ARouter.getInstance().build(RouteTable.User.MetadataSettings).navigation()
            true
        }

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
