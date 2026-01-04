package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/5.
 */

class PlayerSettingFragment : PreferenceFragmentCompat() {
    private var isNormalizingRangeInterval = false

    companion object {
        private const val DEFAULT_MPV_VIDEO_OUTPUT = "gpu"
        private const val DEFAULT_MPV_HWDEC_PRIORITY = "mediacodec"
        private const val DEFAULT_LOCAL_PROXY_MODE = "1"

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
                "vlc_proxy_range_interval_ms",
                "vlc_local_proxy_mode",
            )

        val mpvPreference =
            arrayOf(
                "mpv_proxy_range_interval_ms",
                "mpv_local_proxy_mode",
                "mpv_hwdec_priority",
                "mpv_video_output",
            )

        val mpvVideoOutput =
            mapOf(
                Pair("gpu（默认，可使用自定义后处理效果）", "gpu"),
                Pair("gpu-next（实验）", "gpu-next"),
                Pair("mediacodec_embed（系统硬件渲染，MPV 不会渲染字幕）", "mediacodec_embed"),
            )

        val mpvHwdecPriority =
            mapOf(
                Pair("mediacodec", "mediacodec"),
                Pair("mediacodec-copy", "mediacodec-copy"),
            )

        val localProxyMode =
            mapOf(
                Pair("关闭", LocalProxyMode.OFF.value.toString()),
                Pair("自动（推荐）", LocalProxyMode.AUTO.value.toString()),
                Pair("强制开启", LocalProxyMode.FORCE.value.toString()),
            )
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        preferenceManager.preferenceDataStore = PlayerSettingDataStore()
        addPreferencesFromResource(R.xml.preference_player_setting)
        setupRangeIntervalPreference("mpv_proxy_range_interval_ms")
        setupRangeIntervalPreference("vlc_proxy_range_interval_ms")
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

        // MPV视频输出
        findPreference<ListPreference>("mpv_video_output")?.apply {
            entries = mpvVideoOutput.keys.toTypedArray()
            entryValues = mpvVideoOutput.values.toTypedArray()
            val safeValue =
                value?.takeIf { mpvVideoOutput.containsValue(it) }
                    ?: DEFAULT_MPV_VIDEO_OUTPUT
            if (value != safeValue) {
                value = safeValue
                PlayerConfig.putMpvVideoOutput(safeValue)
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        // MPV硬解优先级
        findPreference<ListPreference>("mpv_hwdec_priority")?.apply {
            entries = mpvHwdecPriority.keys.toTypedArray()
            entryValues = mpvHwdecPriority.values.toTypedArray()
            val safeValue =
                value?.takeIf { mpvHwdecPriority.containsValue(it) }
                    ?: DEFAULT_MPV_HWDEC_PRIORITY
            if (value != safeValue) {
                value = safeValue
                PlayerConfig.putMpvHwdecPriority(safeValue)
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        // MPV 本地防风控代理（HttpPlayServer）
        findPreference<ListPreference>("mpv_local_proxy_mode")?.apply {
            entries = localProxyMode.keys.toTypedArray()
            entryValues = localProxyMode.values.toTypedArray()
            val stored = PlayerConfig.getMpvLocalProxyMode()
            val safeValue = LocalProxyMode.from(stored).value.toString()
            val resolved =
                value?.takeIf { localProxyMode.containsValue(it) }
                    ?: safeValue.takeIf { localProxyMode.containsValue(it) }
                    ?: DEFAULT_LOCAL_PROXY_MODE
            if (value != resolved) {
                value = resolved
                PlayerConfig.putMpvLocalProxyMode(resolved.toInt())
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        // VLC 本地防风控代理（HttpPlayServer）
        findPreference<ListPreference>("vlc_local_proxy_mode")?.apply {
            entries = localProxyMode.keys.toTypedArray()
            entryValues = localProxyMode.values.toTypedArray()
            val stored = PlayerConfig.getVlcLocalProxyMode()
            val safeValue = LocalProxyMode.from(stored).value.toString()
            val resolved =
                value?.takeIf { localProxyMode.containsValue(it) }
                    ?: safeValue.takeIf { localProxyMode.containsValue(it) }
                    ?: DEFAULT_LOCAL_PROXY_MODE
            if (value != resolved) {
                value = resolved
                PlayerConfig.putVlcLocalProxyMode(resolved.toInt())
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        super.onViewCreated(view, savedInstanceState)
    }

    private fun setupRangeIntervalPreference(key: String) {
        val preference = findPreference<SeekBarPreference>(key) ?: return

        preference.value = normalizeMpvRangeInterval(preference.value)

        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { changedPreference, newValue ->
                if (isNormalizingRangeInterval) {
                    return@OnPreferenceChangeListener true
                }

                val rawValue = newValue as? Int ?: return@OnPreferenceChangeListener true
                val normalized = normalizeMpvRangeInterval(rawValue)
                if (normalized == rawValue) {
                    return@OnPreferenceChangeListener true
                }

                isNormalizingRangeInterval = true
                (changedPreference as? SeekBarPreference)?.value = normalized
                isNormalizingRangeInterval = false
                false
            }
    }

    private fun normalizeMpvRangeInterval(value: Int): Int {
        val stepMs = 100
        val rounded = ((value + (stepMs / 2)) / stepMs) * stepMs
        return rounded.coerceIn(0, 2000)
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
                    "mpv_video_output" -> {
                        val current = PlayerConfig.getMpvVideoOutput()
                        val safeValue =
                            current.takeIf { mpvVideoOutput.containsValue(it) }
                                ?: DEFAULT_MPV_VIDEO_OUTPUT
                        if (current != safeValue) {
                            PlayerConfig.putMpvVideoOutput(safeValue)
                        }
                        safeValue
                    }
                    "mpv_hwdec_priority" -> {
                        val current = PlayerConfig.getMpvHwdecPriority()
                        val safeValue =
                            current.takeIf { mpvHwdecPriority.containsValue(it) }
                                ?: DEFAULT_MPV_HWDEC_PRIORITY
                        if (current != safeValue) {
                            PlayerConfig.putMpvHwdecPriority(safeValue)
                        }
                        safeValue
                    }
                    "mpv_local_proxy_mode" -> {
                        val current = PlayerConfig.getMpvLocalProxyMode()
                        val safeValue = LocalProxyMode.from(current).value.toString()
                        if (current.toString() != safeValue) {
                            PlayerConfig.putMpvLocalProxyMode(safeValue.toInt())
                        }
                        safeValue
                    }
                    "vlc_local_proxy_mode" -> {
                        val current = PlayerConfig.getVlcLocalProxyMode()
                        val safeValue = LocalProxyMode.from(current).value.toString()
                        if (current.toString() != safeValue) {
                            PlayerConfig.putVlcLocalProxyMode(safeValue.toInt())
                        }
                        safeValue
                    }
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
                        "mpv_video_output" -> {
                            val safeValue =
                                value.takeIf { mpvVideoOutput.containsValue(it) }
                                    ?: DEFAULT_MPV_VIDEO_OUTPUT
                            PlayerConfig.putMpvVideoOutput(safeValue)
                        }
                        "mpv_hwdec_priority" -> {
                            val safeValue =
                                value.takeIf { mpvHwdecPriority.containsValue(it) }
                                    ?: DEFAULT_MPV_HWDEC_PRIORITY
                            PlayerConfig.putMpvHwdecPriority(safeValue)
                        }
                        "mpv_local_proxy_mode" -> {
                            val safeValue = LocalProxyMode.from(value.toIntOrNull()).value
                            PlayerConfig.putMpvLocalProxyMode(safeValue)
                        }
                        "vlc_local_proxy_mode" -> {
                            val safeValue = LocalProxyMode.from(value.toIntOrNull()).value
                            PlayerConfig.putVlcLocalProxyMode(safeValue)
                        }
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
                    "mpv_proxy_range_interval_ms" ->
                        normalizeMpvRangeInterval(PlayerConfig.getMpvProxyRangeMinIntervalMs())
                    "vlc_proxy_range_interval_ms" ->
                        normalizeMpvRangeInterval(PlayerConfig.getVlcProxyRangeMinIntervalMs())
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
                        PlayerConfig.putMpvProxyRangeMinIntervalMs(normalizeMpvRangeInterval(value))
                    }
                    "vlc_proxy_range_interval_ms" -> {
                        PlayerConfig.putVlcProxyRangeMinIntervalMs(normalizeMpvRangeInterval(value))
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
