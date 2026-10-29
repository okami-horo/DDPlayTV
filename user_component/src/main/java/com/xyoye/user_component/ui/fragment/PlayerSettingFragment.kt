package com.xyoye.user_component.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.xyoye.common_component.base.BasePreferenceFragmentCompat
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.preference.MappingPreferenceDataStore
import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.user_component.R

/**
 * Created by xyoye on 2021/2/5.
 */

class PlayerSettingFragment : BasePreferenceFragmentCompat() {
    private var isNormalizingRangeInterval = false

    companion object {
        private const val DEFAULT_MPV_VIDEO_OUTPUT = "gpu"
        private const val DEFAULT_MPV_HWDEC_PRIORITY = "mediacodec"
        private const val DEFAULT_MPV_AUDIO_OUTPUT = "default"
        private const val DEFAULT_MPV_VIDEO_SYNC = "default"
        private const val DEFAULT_LOCAL_PROXY_MODE = "1"
        private const val KEY_PLAYER_TYPE = "player_type"
        private const val KEY_EXO_PROXY_RANGE_INTERVAL_MS = "exo_proxy_range_interval_ms"
        private const val KEY_EXO_LOCAL_PROXY_MODE = "exo_local_proxy_mode"
        private const val KEY_MPV_PROXY_RANGE_INTERVAL_MS = "mpv_proxy_range_interval_ms"
        private const val KEY_MPV_LOCAL_PROXY_MODE = "mpv_local_proxy_mode"
        private const val KEY_MPV_AUDIO_OUTPUT = "mpv_audio_output"
        private const val KEY_MPV_VIDEO_SYNC = "mpv_video_sync"
        private const val KEY_MPV_HWDEC_PRIORITY = "mpv_hwdec_priority"
        private const val KEY_MPV_VIDEO_OUTPUT = "mpv_video_output"
        private const val KEY_SURFACE_RENDERS = "surface_renders"

        fun newInstance() = PlayerSettingFragment()

        val playerData =
            mapOf(
                Pair("Media3 Player", PlayerType.TYPE_EXO_PLAYER.value.toString()),
                Pair("mpv Player", PlayerType.TYPE_MPV_PLAYER.value.toString()),
            )

        val exoPreference =
            arrayOf(
                KEY_EXO_PROXY_RANGE_INTERVAL_MS,
                KEY_EXO_LOCAL_PROXY_MODE,
            )

        val mpvPreference =
            arrayOf(
                KEY_MPV_PROXY_RANGE_INTERVAL_MS,
                KEY_MPV_LOCAL_PROXY_MODE,
                KEY_MPV_AUDIO_OUTPUT,
                KEY_MPV_VIDEO_SYNC,
                KEY_MPV_HWDEC_PRIORITY,
                KEY_MPV_VIDEO_OUTPUT,
            )

        val mpvVideoOutput =
            mapOf(
                Pair("gpu（默认，OpenGL 渲染）", "gpu"),
                Pair("gpu-next（实验）", "gpu-next"),
                Pair("mediacodec_embed（Android embed，系统硬件渲染）", "mediacodec_embed"),
            )

        val mpvHwdecPriority =
            mapOf(
                Pair("mediacodec", "mediacodec"),
                Pair("mediacodec-copy", "mediacodec-copy"),
            )

        val mpvAudioOutput =
            mapOf(
                Pair("默认（交给 MPV）", "default"),
                Pair("OpenSL ES 优先", "opensles"),
                Pair("AudioTrack 优先", "audiotrack"),
            )

        val mpvVideoSync =
            mapOf(
                Pair("默认（交给 MPV）", "default"),
                Pair("audio", "audio"),
                Pair("display-resample（推荐）", "display-resample"),
                Pair("display-resample-vdrop", "display-resample-vdrop"),
                Pair("display-resample-desync", "display-resample-desync"),
                Pair("display-tempo", "display-tempo"),
                Pair("display-vdrop", "display-vdrop"),
                Pair("display-adrop", "display-adrop"),
                Pair("display-desync", "display-desync"),
                Pair("desync", "desync"),
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
        setupRangeIntervalPreference(KEY_EXO_PROXY_RANGE_INTERVAL_MS)
        setupRangeIntervalPreference(KEY_MPV_PROXY_RANGE_INTERVAL_MS)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        // 播放器类型
        findPreference<ListPreference>(KEY_PLAYER_TYPE)?.apply {
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

        // MPV视频输出
        findPreference<ListPreference>(KEY_MPV_VIDEO_OUTPUT)?.apply {
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

        // MPV音频输出（ao）
        findPreference<ListPreference>(KEY_MPV_AUDIO_OUTPUT)?.apply {
            entries = mpvAudioOutput.keys.toTypedArray()
            entryValues = mpvAudioOutput.values.toTypedArray()
            val current = PlayerConfig.getMpvAudioOutput()
            val safeValue =
                current.takeIf { mpvAudioOutput.containsValue(it) }
                    ?: DEFAULT_MPV_AUDIO_OUTPUT
            if (value != safeValue) {
                value = safeValue
                PlayerConfig.putMpvAudioOutput(safeValue)
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        // MPV视频同步（video-sync）
        findPreference<ListPreference>(KEY_MPV_VIDEO_SYNC)?.apply {
            entries = mpvVideoSync.keys.toTypedArray()
            entryValues = mpvVideoSync.values.toTypedArray()
            val current = PlayerConfig.getMpvVideoSync()
            val safeValue =
                current.takeIf { mpvVideoSync.containsValue(it) }
                    ?: DEFAULT_MPV_VIDEO_SYNC
            if (value != safeValue) {
                value = safeValue
                PlayerConfig.putMpvVideoSync(safeValue)
            }
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        // MPV硬解优先级
        findPreference<ListPreference>(KEY_MPV_HWDEC_PRIORITY)?.apply {
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
        findPreference<ListPreference>(KEY_MPV_LOCAL_PROXY_MODE)?.apply {
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

        // Media3 本地防风控代理（HttpPlayServer）
        findPreference<ListPreference>(KEY_EXO_LOCAL_PROXY_MODE)?.apply {
            entries = localProxyMode.keys.toTypedArray()
            entryValues = localProxyMode.values.toTypedArray()
            val stored = PlayerConfig.getExoLocalProxyMode()
            val safeValue = LocalProxyMode.from(stored).value.toString()
            val resolved =
                value?.takeIf { localProxyMode.containsValue(it) }
                    ?: safeValue.takeIf { localProxyMode.containsValue(it) }
                    ?: DEFAULT_LOCAL_PROXY_MODE
            if (value != resolved) {
                value = resolved
                PlayerConfig.putExoLocalProxyMode(resolved.toInt())
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
            PlayerType.TYPE_MPV_PLAYER.value.toString() -> {
                mpvPreference.forEach { findPreference<Preference>(it)?.isVisible = true }
                exoPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
            }
            else -> {
                mpvPreference.forEach { findPreference<Preference>(it)?.isVisible = false }
                exoPreference.forEach { findPreference<Preference>(it)?.isVisible = true }
            }
        }
    }

    private inner class PlayerSettingDataStore : MappingPreferenceDataStore(
        dataStoreName = "PlayerSettingDataStore",
        stringReaders =
            mapOf(
                KEY_PLAYER_TYPE to {
                    val currentType = PlayerConfig.getUsePlayerType()
                    val safeType =
                        when (PlayerType.valueOf(currentType)) {
                            PlayerType.TYPE_MPV_PLAYER -> PlayerType.TYPE_MPV_PLAYER
                            else -> PlayerType.TYPE_EXO_PLAYER
                        }
                    if (safeType.value != currentType) {
                        PlayerConfig.putUsePlayerType(safeType.value)
                    }
                    safeType.value.toString()
                },
                KEY_MPV_VIDEO_OUTPUT to {
                    val current = PlayerConfig.getMpvVideoOutput()
                    val safeValue = current.takeIf { mpvVideoOutput.containsValue(it) } ?: DEFAULT_MPV_VIDEO_OUTPUT
                    if (current != safeValue) {
                        PlayerConfig.putMpvVideoOutput(safeValue)
                    }
                    safeValue
                },
                KEY_MPV_HWDEC_PRIORITY to {
                    val current = PlayerConfig.getMpvHwdecPriority()
                    val safeValue = current.takeIf { mpvHwdecPriority.containsValue(it) } ?: DEFAULT_MPV_HWDEC_PRIORITY
                    if (current != safeValue) {
                        PlayerConfig.putMpvHwdecPriority(safeValue)
                    }
                    safeValue
                },
                KEY_MPV_AUDIO_OUTPUT to {
                    val current = PlayerConfig.getMpvAudioOutput()
                    val safeValue = current.takeIf { mpvAudioOutput.containsValue(it) } ?: DEFAULT_MPV_AUDIO_OUTPUT
                    if (current != safeValue) {
                        PlayerConfig.putMpvAudioOutput(safeValue)
                    }
                    safeValue
                },
                KEY_MPV_VIDEO_SYNC to {
                    val current = PlayerConfig.getMpvVideoSync()
                    val safeValue = current.takeIf { mpvVideoSync.containsValue(it) } ?: DEFAULT_MPV_VIDEO_SYNC
                    if (current != safeValue) {
                        PlayerConfig.putMpvVideoSync(safeValue)
                    }
                    safeValue
                },
                KEY_MPV_LOCAL_PROXY_MODE to {
                    val current = PlayerConfig.getMpvLocalProxyMode()
                    val safeValue = LocalProxyMode.from(current).value.toString()
                    if (current.toString() != safeValue) {
                        PlayerConfig.putMpvLocalProxyMode(safeValue.toInt())
                    }
                    safeValue
                },
                KEY_EXO_LOCAL_PROXY_MODE to {
                    val current = PlayerConfig.getExoLocalProxyMode()
                    val safeValue = LocalProxyMode.from(current).value.toString()
                    if (current.toString() != safeValue) {
                        PlayerConfig.putExoLocalProxyMode(safeValue.toInt())
                    }
                    safeValue
                },
            ),
        stringWriters =
            mapOf(
                KEY_PLAYER_TYPE to { value ->
                    value?.toIntOrNull()?.let { parsedType ->
                        val safeType =
                            when (PlayerType.valueOf(parsedType)) {
                                PlayerType.TYPE_MPV_PLAYER -> PlayerType.TYPE_MPV_PLAYER
                                else -> PlayerType.TYPE_EXO_PLAYER
                            }
                        PlayerConfig.putUsePlayerType(safeType.value)
                    }
                },
                KEY_MPV_VIDEO_OUTPUT to { value ->
                    val safeValue = value?.takeIf { mpvVideoOutput.containsValue(it) } ?: DEFAULT_MPV_VIDEO_OUTPUT
                    PlayerConfig.putMpvVideoOutput(safeValue)
                },
                KEY_MPV_HWDEC_PRIORITY to { value ->
                    val safeValue = value?.takeIf { mpvHwdecPriority.containsValue(it) } ?: DEFAULT_MPV_HWDEC_PRIORITY
                    PlayerConfig.putMpvHwdecPriority(safeValue)
                },
                KEY_MPV_AUDIO_OUTPUT to { value ->
                    val safeValue = value?.takeIf { mpvAudioOutput.containsValue(it) } ?: DEFAULT_MPV_AUDIO_OUTPUT
                    PlayerConfig.putMpvAudioOutput(safeValue)
                },
                KEY_MPV_VIDEO_SYNC to { value ->
                    val safeValue = value?.takeIf { mpvVideoSync.containsValue(it) } ?: DEFAULT_MPV_VIDEO_SYNC
                    PlayerConfig.putMpvVideoSync(safeValue)
                },
                KEY_MPV_LOCAL_PROXY_MODE to { value ->
                    val safeValue = LocalProxyMode.from(value?.toIntOrNull()).value
                    PlayerConfig.putMpvLocalProxyMode(safeValue)
                },
                KEY_EXO_LOCAL_PROXY_MODE to { value ->
                    val safeValue = LocalProxyMode.from(value?.toIntOrNull()).value
                    PlayerConfig.putExoLocalProxyMode(safeValue)
                },
            ),
        intReaders =
            mapOf(
                KEY_MPV_PROXY_RANGE_INTERVAL_MS to {
                    normalizeMpvRangeInterval(PlayerConfig.getMpvProxyRangeMinIntervalMs())
                },
                KEY_EXO_PROXY_RANGE_INTERVAL_MS to {
                    normalizeMpvRangeInterval(PlayerConfig.getExoProxyRangeMinIntervalMs())
                },
            ),
        intWriters =
            mapOf(
                KEY_MPV_PROXY_RANGE_INTERVAL_MS to { value ->
                    PlayerConfig.putMpvProxyRangeMinIntervalMs(normalizeMpvRangeInterval(value))
                },
                KEY_EXO_PROXY_RANGE_INTERVAL_MS to { value ->
                    PlayerConfig.putExoProxyRangeMinIntervalMs(normalizeMpvRangeInterval(value))
                },
            ),
        booleanReaders =
            mapOf(
                KEY_SURFACE_RENDERS to { PlayerConfig.isUseSurfaceView() },
            ),
        booleanWriters =
            mapOf(
                KEY_SURFACE_RENDERS to { value -> PlayerConfig.putUseSurfaceView(value) },
            ),
    )
}
