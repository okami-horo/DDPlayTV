package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BasePreferenceFragmentCompat
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.SubtitleConfig

class CinemaCaptionPreferences : BasePreferenceFragmentCompat() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen.addPreference(
            SwitchPreference(requireContext()).apply {
                key = "cinema_subtitle_load"
                title = "自动加载字幕"
                summary = "播放时加载同文件夹内的同名字幕"
                isPersistent = false
                isChecked = SubtitleConfig.isAutoLoadSameNameSubtitle()
                setOnPreferenceChangeListener { _, value ->
                    SubtitleConfig.putAutoLoadSameNameSubtitle(value as Boolean)
                    true
                }
            }
        )
        preferenceScreen.addPreference(
            SwitchPreference(requireContext()).apply {
                key = "cinema_danmaku_load"
                title = "自动加载弹幕"
                summary = "播放时加载同文件夹内的同名弹幕"
                isPersistent = false
                isChecked = DanmuConfig.isAutoLoadSameNameDanmu()
                setOnPreferenceChangeListener { _, value ->
                    DanmuConfig.putAutoLoadSameNameDanmu(value as Boolean)
                    true
                }
            }
        )
        listOf("字幕设置" to 2, "弹幕设置" to 1).forEach { (label, index) ->
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = label
                    summary = "匹配、加载与显示偏好"
                    setOnPreferenceClickListener {
                        ARouter
                            .getInstance()
                            .build(RouteTable.User.SettingPlayer)
                            .withInt("initialCategory", index)
                            .navigation()
                        true
                    }
                }
            )
        }
    }
}
