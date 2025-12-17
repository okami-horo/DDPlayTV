package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/5.
 */

class PlayerSettingFragment : PreferenceFragmentCompat() {
    companion object {
        fun newInstance() = PlayerSettingFragment()

        val playerData =
            mapOf(
                Pair("Media3 Player", PlayerType.TYPE_EXO_PLAYER.value.toString()),
                Pair("VLC Player", PlayerType.TYPE_VLC_PLAYER.value.toString()),
                Pair("mpv Player", PlayerType.TYPE_MPV_PLAYER.value.toString()),
            )

        val vlcHWDecode =
            mapOf(
                Pair("自动", VLCHWDecode.HW_ACCELERATION_AUTO.value.toString()),
                Pair("禁用", VLCHWDecode.HW_ACCELERATION_DISABLE.value.toString()),
                Pair("解码加速", VLCHWDecode.HW_ACCELERATION_DECODING.value.toString()),
                Pair("完全加速", VLCHWDecode.HW_ACCELERATION_FULL.value.toString()),
            )

        val vlcAudioOutput =
            mapOf(
                Pair("自动", VLCAudioOutput.AUTO.value),
                Pair("OpenSL ES", VLCAudioOutput.OPEN_SL_ES.value),
            )

        val vlcPreference =
            arrayOf(
                "vlc_hardware_acceleration",
                "vlc_audio_output",
            )

        val mpvPreference =
            arrayOf(
                "mpv_proxy_range_interval_ms",
            )
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        preferenceManager.preferenceDataStore = PlayerSettingDataStore()
        addPreferencesFromResource(R.xml.preference_player_setting)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        // 播放器类型
        findPreference<ListPreference>("player_type")?.apply {
            entries = playerData.keys.toTypedArray()
            entryValues = playerData.values.toTypedArray()
            val safeValue =
                value?.takeIf { playerData.containsValue(it) }
                    ?: PlayerType.TYPE_EXO_PLAYER.value.toString()
            if (value != safeValue) {
                value = safeValue
                PlayerConfig.putUsePlayerType(safeValue.toInt())
            }
            summary = entry
            setOnPreferenceChangeListener { _, newValue ->
                playerData.forEach {
                    if (it.value == newValue) {
                        summary = it.key
                        updateVisible(newValue.toString())
                    }
                }
                return@setOnPreferenceChangeListener true
            }

            updateVisible(safeValue)
        }

        // VLC硬件加速
        findPreference<ListPreference>("vlc_hardware_acceleration")?.apply {
            entries = vlcHWDecode.keys.toTypedArray()
            entryValues = vlcHWDecode.values.toTypedArray()
        }

        // VLC音频输出
        findPreference<ListPreference>("vlc_audio_output")?.apply {
            entries = vlcAudioOutput.keys.toTypedArray()
            entryValues = vlcAudioOutput.values.toTypedArray()
        }

        super.onViewCreated(view, savedInstanceState)
    }

    private fun updateVisible(playerType: String) {
        when (playerType) {
            PlayerType.TYPE_VLC_PLAYER.value.toString() -> {
                vlcPreference.forEach { findPreference<Preference>(it)?.isVisible = true }
                mpvPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
            }
            PlayerType.TYPE_MPV_PLAYER.value.toString() -> {
                vlcPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
                mpvPreference.forEach { findPreference<Preference>(it)?.isVisible = true }
            }
            else -> {
                vlcPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
                mpvPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
            }
        }
    }

    inner class PlayerSettingDataStore : PreferenceDataStore() {
        override fun getString(
            key: String?,
            defValue: String?
        ): String? =
            try {
                when (key) {
                    "player_type" -> {
                        val currentType = PlayerConfig.getUsePlayerType()
                        val safeType =
                            when (PlayerType.valueOf(currentType)) {
                                PlayerType.TYPE_VLC_PLAYER -> PlayerType.TYPE_VLC_PLAYER
                                PlayerType.TYPE_MPV_PLAYER -> PlayerType.TYPE_MPV_PLAYER
                                else -> PlayerType.TYPE_EXO_PLAYER
                            }
                        if (safeType.value != currentType) {
                            PlayerConfig.putUsePlayerType(safeType.value)
                        }
                        safeType.value.toString()
                    }
                    "vlc_hardware_acceleration" -> PlayerConfig.getUseVLCHWDecoder().toString()
                    "vlc_audio_output" -> PlayerConfig.getUseVLCAudioOutput()
                    else -> super.getString(key, defValue)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
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
                if (value != null) {
                    when (key) {
                        "player_type" -> {
                            val safeType =
                                when (PlayerType.valueOf(value.toInt())) {
                                    PlayerType.TYPE_VLC_PLAYER -> PlayerType.TYPE_VLC_PLAYER
                                    PlayerType.TYPE_MPV_PLAYER -> PlayerType.TYPE_MPV_PLAYER
                                    else -> PlayerType.TYPE_EXO_PLAYER
                                }
                            PlayerConfig.putUsePlayerType(safeType.value)
                        }
                        "vlc_hardware_acceleration" -> PlayerConfig.putUseVLCHWDecoder(value.toInt())
                        "vlc_audio_output" -> PlayerConfig.putUseVLCAudioOutput(value)
                        else -> super.putString(key, value)
                    }
                } else {
                    super.putString(key, value)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
                    "putString",
                    "Failed to put string value for key: $key, value: $value",
                )
            }
        }

        override fun getInt(
            key: String?,
            defValue: Int
        ): Int =
            try {
                when (key) {
                    "mpv_proxy_range_interval_ms" -> PlayerConfig.getMpvProxyRangeMinIntervalMs()
                    else -> super.getInt(key, defValue)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
                    "getInt",
                    "Failed to get int value for key: $key",
                )
                defValue
            }

        override fun putInt(
            key: String?,
            value: Int
        ) {
            try {
                when (key) {
                    "mpv_proxy_range_interval_ms" -> {
                        val normalized = ((value + 50) / 100) * 100
                        PlayerConfig.putMpvProxyRangeMinIntervalMs(normalized.coerceIn(0, 2000))
                    }
                    else -> super.putInt(key, value)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
                    "putInt",
                    "Failed to put int value for key: $key, value: $value",
                )
            }
        }

        override fun getBoolean(
            key: String?,
            defValue: Boolean
        ): Boolean =
            try {
                when (key) {
                    "surface_renders" -> PlayerConfig.isUseSurfaceView()
                    else -> super.getBoolean(key, defValue)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
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
                    "surface_renders" -> PlayerConfig.putUseSurfaceView(value)
                    else -> super.putBoolean(key, value)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerSettingDataStore",
                    "putBoolean",
                    "Failed to put boolean value for key: $key, value: $value",
                )
            }
        }
    }
}
