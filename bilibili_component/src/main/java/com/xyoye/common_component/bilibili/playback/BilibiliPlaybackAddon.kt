package com.xyoye.common_component.bilibili.playback

import android.os.SystemClock
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlayMode
import com.xyoye.common_component.bilibili.BilibiliQuality
import com.xyoye.common_component.bilibili.BilibiliVideoCodec
import com.xyoye.common_component.bilibili.error.BilibiliPlaybackErrorReporter
import com.xyoye.common_component.playback.addon.PlaybackEvent
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackRecoveryRequest
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.playback.addon.PlaybackSettingSpec
import com.xyoye.common_component.playback.addon.PlaybackSettingUpdate
import com.xyoye.common_component.playback.addon.PlaybackPreferenceSwitchableAddon
import com.xyoye.common_component.playback.addon.PlaybackSettingsAddon
import com.xyoye.common_component.playback.addon.PlaybackUrlRecoverableAddon
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayState

class BilibiliPlaybackAddon(
    private val identity: PlaybackIdentity,
    private val supportsSeamlessPreferenceSwitch: Boolean,
) : PlaybackSettingsAddon,
    PlaybackPreferenceSwitchableAddon,
    PlaybackUrlRecoverableAddon,
    PlaybackReleasableAddon {
    private val storageId: Int = identity.storageId
    private val uniqueKey: String = identity.uniqueKey
    private val isLiveKey: Boolean = BilibiliKeys.parse(uniqueKey) is BilibiliKeys.LiveKey

    private var isInitializedBySourceChanged: Boolean = false
    private var lastHttpHeader: Map<String, String>? = null

    private var liveSessionStartElapsedMs: Long? = null
    private var liveCompletionReported: Boolean = false
    private var liveErrorReported: Boolean = false
    private var lastDurationMs: Long? = null

    override val addonId: String = "bilibili/playback"

    override fun onRelease() {
        BilibiliPlaybackSessionStore.remove(storageId, uniqueKey)
    }

    override suspend fun getSettingSpec(): Result<PlaybackSettingSpec?> =
        runCatching {
            if (isLiveKey) return@runCatching null
            if (identity.mediaType != MediaType.BILIBILI_STORAGE) return@runCatching null

            val session = BilibiliPlaybackSessionStore.get(storageId, uniqueKey) ?: return@runCatching null
            val snapshot = session.snapshot() ?: return@runCatching null

            PlaybackSettingSpec(
                identity = identity,
                sections = buildSettingSections(snapshot),
            )
        }

    override suspend fun applySettingUpdate(
        update: PlaybackSettingUpdate,
        positionMs: Long,
    ): Result<String?> {
        if (isLiveKey) return Result.success(null)
        if (identity.mediaType != MediaType.BILIBILI_STORAGE) return Result.success(null)

        val session =
            BilibiliPlaybackSessionStore.get(storageId, uniqueKey)
                ?: return Result.failure(IllegalStateException("未获取到B站播放会话"))

        val preferenceUpdate =
            runCatching { update.toPreferenceUpdate() }
                .getOrElse { return Result.failure(it) }

        val result = session.applyPreferenceUpdate(preferenceUpdate, positionMs)
        if (!supportsSeamlessPreferenceSwitch) {
            return Result.success(null)
        }

        val playUrl = result.getOrElse { return Result.failure(it) }
        if (playUrl.isBlank()) {
            return Result.failure(IllegalStateException("切换失败"))
        }
        return Result.success(playUrl)
    }

    override suspend fun recover(request: PlaybackRecoveryRequest): Result<String?> {
        if (!canHandle(request.identity.mediaType, request.identity.storageId, request.identity.uniqueKey)) {
            return Result.success(null)
        }
        if (isLiveKey) return Result.success(null)
        if (identity.mediaType != MediaType.BILIBILI_STORAGE) return Result.success(null)

        val session =
            BilibiliPlaybackSessionStore.get(storageId, uniqueKey)
                ?: return Result.failure(IllegalStateException("未获取到B站播放会话"))

        val playUrl =
            session.recover(
                failure = request.toFailureContext(),
                positionMs = request.positionMs,
            ).getOrElse { return Result.failure(it) }

        if (playUrl.isBlank()) {
            return Result.success(null)
        }
        return Result.success(playUrl)
    }

    override fun onEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.SourceChanged -> {
                if (!canHandle(event.identity.mediaType, event.identity.storageId, event.identity.uniqueKey)) return
                isInitializedBySourceChanged = true
                lastHttpHeader = event.httpHeader

                if (!isLiveKey) {
                    liveSessionStartElapsedMs = null
                    liveCompletionReported = false
                    liveErrorReported = false
                    lastDurationMs = null
                    return
                }

                liveSessionStartElapsedMs = SystemClock.elapsedRealtime()
                liveCompletionReported = false
                liveErrorReported = false
                lastDurationMs = null
            }

            is PlaybackEvent.Progress -> {
                if (!canHandle(event.identity.mediaType, event.identity.storageId, event.identity.uniqueKey)) return
                lastDurationMs = event.durationMs

                if (isLiveKey && liveSessionStartElapsedMs == null) {
                    liveSessionStartElapsedMs = SystemClock.elapsedRealtime()
                }

                BilibiliPlaybackHeartbeat.onProgress(
                    storageId = storageId,
                    uniqueKey = uniqueKey,
                    mediaType = event.identity.mediaType,
                    positionMs = event.positionMs,
                    isPlaying = event.isPlaying,
                )
            }

            is PlaybackEvent.PlayStateChanged -> {
                if (!canHandle(event.identity.mediaType, event.identity.storageId, event.identity.uniqueKey)) return
                if (isLiveKey && liveSessionStartElapsedMs == null) {
                    liveSessionStartElapsedMs = SystemClock.elapsedRealtime()
                }

                BilibiliPlaybackHeartbeat.onPlayStateChanged(
                    storageId = storageId,
                    uniqueKey = uniqueKey,
                    mediaType = event.identity.mediaType,
                    playState = event.playState,
                    positionMs = event.positionMs,
                )

                if (event.playState == PlayState.STATE_COMPLETED) {
                    reportBilibiliLiveCompletionIfNeeded(event)
                }
            }

            is PlaybackEvent.PlaybackError -> {
                if (!canHandle(event.identity.mediaType, event.identity.storageId, event.identity.uniqueKey)) return
                if (!isInitializedBySourceChanged) return

                val snapshot = event.identity.toBilibiliPlaybackSourceSnapshot()
                if (!BilibiliPlaybackErrorReporter.isBilibiliSource(snapshot)) return

                if (isLiveKey) {
                    if (liveErrorReported) return
                    liveErrorReported = true
                }

                BilibiliPlaybackErrorReporter.reportPlaybackError(
                    source = snapshot,
                    throwable = event.throwable,
                    scene = event.scene,
                    extra = event.diagnostics,
                )
            }

            else -> Unit
        }
    }

    private fun PlaybackSettingUpdate.toPreferenceUpdate(): BilibiliPlaybackSession.PreferenceUpdate =
        when (settingId) {
            SETTING_ID_PLAY_MODE -> {
                val mode =
                    BilibiliPlayMode.entries.firstOrNull { it.name == optionId }
                        ?: throw IllegalArgumentException("未知播放模式：$optionId")
                BilibiliPlaybackSession.PreferenceUpdate(playMode = mode)
            }

            SETTING_ID_QUALITY_QN -> {
                val qn = optionId.toIntOrNull() ?: throw IllegalArgumentException("未知画质选项：$optionId")
                BilibiliPlaybackSession.PreferenceUpdate(qualityQn = qn)
            }

            SETTING_ID_VIDEO_CODEC -> {
                val codec =
                    BilibiliVideoCodec.entries.firstOrNull { it.name == optionId }
                        ?: throw IllegalArgumentException("未知视频编码：$optionId")
                BilibiliPlaybackSession.PreferenceUpdate(videoCodec = codec)
            }

            SETTING_ID_AUDIO_QUALITY -> {
                val id = optionId.toIntOrNull() ?: throw IllegalArgumentException("未知音质选项：$optionId")
                BilibiliPlaybackSession.PreferenceUpdate(audioQualityId = id)
            }

            else -> throw IllegalArgumentException("未知设置项：$settingId")
        }

    private fun PlaybackRecoveryRequest.toFailureContext(): BilibiliPlaybackSession.FailureContext {
        val failingUrl =
            diagnostics[DIAG_LAST_HTTP_URL]
                ?.takeIf { it.isNotBlank() }
                ?: diagnostics[DIAG_FAILING_URL]?.takeIf { it.isNotBlank() }

        val httpResponseCode = diagnostics[DIAG_HTTP_RESPONSE_CODE]?.toIntOrNull()

        val isDecoderError =
            diagnostics[DIAG_IS_DECODER_ERROR]?.toBooleanStrictOrNull()
                ?: (diagnostics[DIAG_MEDIA3_ERROR_CODE_NAME] == DIAG_MEDIA3_DECODING_FAILED_NAME)

        return BilibiliPlaybackSession.FailureContext(
            failingUrl = failingUrl,
            httpResponseCode = httpResponseCode,
            isDecoderError = isDecoderError,
        )
    }

    private fun buildSettingSections(snapshot: BilibiliPlaybackSession.Snapshot): List<PlaybackSettingSpec.Section> {
        val sections = mutableListOf<PlaybackSettingSpec.Section>()
        sections.add(buildPlayModeSection(snapshot))

        if (!snapshot.dashAvailable) {
            return sections
        }

        sections.add(buildQualitySection(snapshot))
        sections.add(buildVideoCodecSection(snapshot))
        sections.add(buildAudioQualitySection(snapshot))
        return sections
    }

    private fun buildPlayModeSection(snapshot: BilibiliPlaybackSession.Snapshot): PlaybackSettingSpec.Section =
        PlaybackSettingSpec.Section(
            sectionId = SETTING_ID_PLAY_MODE,
            title = "播放模式",
            items =
                listOf(
                    PlaybackSettingSpec.Item.SingleChoice(
                        settingId = SETTING_ID_PLAY_MODE,
                        title = "播放模式",
                        options =
                            BilibiliPlayMode.entries.map { mode ->
                                PlaybackSettingSpec.Option(
                                    optionId = mode.name,
                                    label = mode.label,
                                )
                            },
                        selectedOptionId = snapshot.playMode.name,
                    ),
                ),
        )

    private fun buildQualitySection(snapshot: BilibiliPlaybackSession.Snapshot): PlaybackSettingSpec.Section =
        PlaybackSettingSpec.Section(
            sectionId = SETTING_ID_QUALITY_QN,
            title = "画质",
            items =
                listOf(
                    PlaybackSettingSpec.Item.SingleChoice(
                        settingId = SETTING_ID_QUALITY_QN,
                        title = "画质",
                        options =
                            snapshot.qualities
                                .sorted()
                                .map { qn ->
                                    PlaybackSettingSpec.Option(
                                        optionId = qn.toString(),
                                        label = qualityLabel(qn),
                                    )
                                },
                        selectedOptionId = snapshot.selectedQualityQn.toString(),
                    ),
                ),
        )

    private fun buildVideoCodecSection(snapshot: BilibiliPlaybackSession.Snapshot): PlaybackSettingSpec.Section =
        PlaybackSettingSpec.Section(
            sectionId = SETTING_ID_VIDEO_CODEC,
            title = "视频编码",
            items =
                listOf(
                    PlaybackSettingSpec.Item.SingleChoice(
                        settingId = SETTING_ID_VIDEO_CODEC,
                        title = "视频编码",
                        options =
                            snapshot.videoCodecs.map { codec ->
                                PlaybackSettingSpec.Option(
                                    optionId = codec.name,
                                    label = codec.label,
                                )
                            },
                        selectedOptionId = snapshot.selectedVideoCodec.name,
                    ),
                ),
        )

    private fun buildAudioQualitySection(snapshot: BilibiliPlaybackSession.Snapshot): PlaybackSettingSpec.Section =
        PlaybackSettingSpec.Section(
            sectionId = SETTING_ID_AUDIO_QUALITY,
            title = "音质",
            items =
                listOf(
                    PlaybackSettingSpec.Item.SingleChoice(
                        settingId = SETTING_ID_AUDIO_QUALITY,
                        title = "音质",
                        options =
                            snapshot.audios.map { audio ->
                                val kbps = if (audio.bandwidth > 0) audio.bandwidth / 1000 else 0
                                val label =
                                    if (kbps > 0) {
                                        "音质 ${audio.id}（${kbps}kbps）"
                                    } else {
                                        "音质 ${audio.id}"
                                    }
                                PlaybackSettingSpec.Option(
                                    optionId = audio.id.toString(),
                                    label = label,
                                )
                            },
                        selectedOptionId = snapshot.selectedAudioQualityId.toString(),
                    ),
                ),
        )

    private fun qualityLabel(qn: Int): String {
        val mapped = BilibiliQuality.fromQn(qn)
        if (mapped != BilibiliQuality.AUTO) {
            return mapped.label
        }
        if (qn == BilibiliQuality.AUTO.qn) {
            return mapped.label
        }
        return "QN $qn"
    }

    private fun reportBilibiliLiveCompletionIfNeeded(event: PlaybackEvent.PlayStateChanged) {
        if (!isInitializedBySourceChanged) return

        val snapshot = event.identity.toBilibiliPlaybackSourceSnapshot()
        if (!isLiveKey) return
        if (liveCompletionReported) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val watchElapsedMs = liveSessionStartElapsedMs?.let { nowElapsed - it }
        val isLiveFlag = (lastDurationMs ?: 0L) < 0L

        val shouldReport =
            isLiveFlag ||
                (watchElapsedMs != null && watchElapsedMs >= BILIBILI_LIVE_COMPLETION_REPORT_MIN_WATCH_MS)

        if (!shouldReport) {
            return
        }

        liveCompletionReported = true
        val extra =
            linkedMapOf<String, String>().apply {
                put("watchElapsedMs", watchElapsedMs?.toString().orEmpty())
                put("isLiveFlag", isLiveFlag.toString())
                put("playState", event.playState.name)
                put("positionMs", event.positionMs.toString())
                lastDurationMs?.let { put("durationMs", it.toString()) }
            }

        BilibiliPlaybackErrorReporter.reportUnexpectedCompletion(
            source = snapshot,
            scene = "live_completed",
            extra = extra,
        )
    }

    private fun canHandle(
        mediaType: MediaType,
        eventStorageId: Int,
        eventUniqueKey: String,
    ): Boolean {
        if (mediaType != MediaType.BILIBILI_STORAGE) return false
        if (eventStorageId != storageId) return false
        if (eventUniqueKey != uniqueKey) return false
        return true
    }

    private fun PlaybackIdentity.toBilibiliPlaybackSourceSnapshot(): BilibiliPlaybackErrorReporter.SourceSnapshot =
        BilibiliPlaybackErrorReporter.SourceSnapshot(
            mediaType = mediaType,
            storageId = storageId,
            storagePath = storagePath,
            uniqueKey = uniqueKey,
            videoTitle = videoTitle,
            videoUrl = videoUrl,
            httpHeader = lastHttpHeader,
        )

    private companion object {
        private const val SETTING_ID_PLAY_MODE = "bilibili.play_mode"
        private const val SETTING_ID_QUALITY_QN = "bilibili.quality_qn"
        private const val SETTING_ID_VIDEO_CODEC = "bilibili.video_codec"
        private const val SETTING_ID_AUDIO_QUALITY = "bilibili.audio_quality"
        private const val BILIBILI_LIVE_COMPLETION_REPORT_MIN_WATCH_MS = 5 * 60_000L

        private const val DIAG_LAST_HTTP_URL = "lastHttpUrl"
        private const val DIAG_FAILING_URL = "failingUrl"
        private const val DIAG_HTTP_RESPONSE_CODE = "httpResponseCode"
        private const val DIAG_IS_DECODER_ERROR = "isDecoderError"
        private const val DIAG_MEDIA3_ERROR_CODE_NAME = "media3.errorCodeName"
        private const val DIAG_MEDIA3_DECODING_FAILED_NAME = "ERROR_CODE_DECODING_FAILED"
    }
}
