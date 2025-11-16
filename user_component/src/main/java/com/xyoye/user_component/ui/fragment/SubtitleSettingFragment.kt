package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.enums.RendererPreferenceSource
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/6.
 */

class SubtitleSettingFragment : PreferenceFragmentCompat() {

    companion object {
        fun newInstance() = SubtitleSettingFragment()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = SubtitleSettingDataStore()
        addPreferencesFromResource(R.xml.preference_subtitle_setting)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val loadSameSubtitleSwitch = findPreference<SwitchPreference>("auto_load_same_name_subtitle")
        val sameSubtitlePriority = findPreference<EditTextPreference>("same_name_subtitle_priority")
        val backendPreference = findPreference<ListPreference>("subtitle_renderer_backend")
        val backendNote = findPreference<Preference>("subtitle_renderer_backend_note")

        backendPreference?.apply {
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                updateBackendNoteVisibility(backendNote, newValue as String)
                return@setOnPreferenceChangeListener true
            }
        }
        updateBackendNoteVisibility(
            backendNote,
            backendPreference?.value ?: SubtitleConfig.getSubtitleRendererBackend()
        )

        loadSameSubtitleSwitch?.setOnPreferenceChangeListener { _, newValue ->
            sameSubtitlePriority?.isVisible = newValue as Boolean
            return@setOnPreferenceChangeListener true
        }

        sameSubtitlePriority?.apply {
            isVisible = loadSameSubtitleSwitch?.isChecked ?: false
            summary = if (TextUtils.isEmpty(this.text)) "未设置" else text
            setOnPreferenceChangeListener { _, newValue ->
                summary = if (TextUtils.isEmpty(newValue as String?)) "未设置" else newValue
                return@setOnPreferenceChangeListener true
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }

    private fun updateBackendNoteVisibility(note: Preference?, backendValue: String?) {
        val backend = SubtitleRendererBackend.fromName(backendValue)
        note?.isVisible = backend == SubtitleRendererBackend.LIBASS
    }

    inner class SubtitleSettingDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "auto_load_same_name_subtitle" -> SubtitleConfig.isAutoLoadSameNameSubtitle()
                "auto_match_subtitle" -> SubtitleConfig.isAutoMatchSubtitle()
                "subtitle_shadow_enabled" -> SubtitleConfig.isShadowEnabled()
                else -> super.getBoolean(key, defValue)
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "same_name_subtitle_priority" -> SubtitleConfig.getSubtitlePriority()
                "subtitle_renderer_backend" -> SubtitleConfig.getSubtitleRendererBackend()
                else -> super.getString(key, defValue)
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "auto_load_same_name_subtitle" -> SubtitleConfig.putAutoLoadSameNameSubtitle(value)
                "auto_match_subtitle" -> SubtitleConfig.putAutoMatchSubtitle(value)
                "subtitle_shadow_enabled" -> SubtitleConfig.putShadowEnabled(value)
                else -> super.putBoolean(key, value)
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "same_name_subtitle_priority" -> SubtitleConfig.putSubtitlePriority(value ?: "")
                "subtitle_renderer_backend" -> {
                    val backend = SubtitleRendererBackend.fromName(
                        value ?: SubtitleRendererBackend.LEGACY_CANVAS.name
                    )
                    SubtitlePreferenceUpdater.persistBackend(
                        backend,
                        RendererPreferenceSource.LOCAL_SETTINGS
                    )
                }
                else -> super.putString(key, value)
            }
        }
    }
}
