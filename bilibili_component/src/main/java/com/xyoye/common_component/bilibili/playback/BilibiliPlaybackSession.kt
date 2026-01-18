package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlayMode
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliVideoCodec
import com.xyoye.common_component.bilibili.cdn.BilibiliCdnStrategy
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.mpd.BilibiliMpdGenerator
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.data.bilibili.BilibiliDashData
import com.xyoye.data_component.data.bilibili.BilibiliDashMediaData
import com.xyoye.data_component.data.bilibili.BilibiliDurlData
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class BilibiliPlaybackSession(
    val storageId: Int,
    val uniqueKey: String,
    private val storageKey: String,
    private val repository: BilibiliRepository,
    private val key: BilibiliKeys.Key
) {
    data class AudioOption(
        val id: Int,
        val bandwidth: Int
    )

    data class Snapshot(
        val playMode: BilibiliPlayMode,
        val dashAvailable: Boolean,
        val selectedQualityQn: Int,
        val selectedVideoCodec: BilibiliVideoCodec,
        val selectedAudioQualityId: Int,
        val qualities: List<Int>,
        val videoCodecs: List<BilibiliVideoCodec>,
        val audios: List<AudioOption>,
        val lastPositionMs: Long
    )

    data class PreferenceUpdate(
        val playMode: BilibiliPlayMode? = null,
        val qualityQn: Int? = null,
        val videoCodec: BilibiliVideoCodec? = null,
        val audioQualityId: Int? = null
    )

    data class FailureContext(
        val failingUrl: String? = null,
        val httpResponseCode: Int? = null,
        val isDecoderError: Boolean = false
    )

    private val mpdFile =
        File(
            PathHelper.getPlayCacheDirectory(),
            "bilibili_${uniqueKey.toMd5String()}.mpd",
        )

    private val cdnBlacklistTtlMs = TimeUnit.MINUTES.toMillis(10)
    private val cdnHostBlacklist = LinkedHashMap<String, Long>()

    private val expiresMarginMs = TimeUnit.MINUTES.toMillis(1)
    private var selectedExpiresAtMs: Long? = null

    private val pgcSession: String? =
        if (key is BilibiliKeys.PgcEpisodeKey) {
            UUID.randomUUID().toString().replace("-", "")
        } else {
            null
        }

    private var lastPositionMs: Long = 0

    private var preferences: BilibiliPlaybackPreferences = BilibiliPlaybackPreferencesStore.read(storageKey)
    private var playurl: BilibiliPlayurlData? = null
    private var dash: BilibiliDashData? = null
    private var durl: List<BilibiliDurlData> = emptyList()

    private var videoCandidatesByCodec: Map<BilibiliVideoCodec, List<BilibiliDashMediaData>> = emptyMap()
    private var audioCandidates: List<BilibiliDashMediaData> = emptyList()

    private var selectedVideo: BilibiliDashMediaData? = null
    private var selectedAudio: BilibiliDashMediaData? = null
    private var selectedDurlCandidates: List<String> = emptyList()
    private var selectedDurlIndex: Int = 0

    @Volatile
    private var snapshot: Snapshot? = null

    fun snapshot(): Snapshot? = snapshot

    suspend fun reportPlaybackHeartbeat(playedTimeSec: Long): Result<Unit> {
        if (!BilibiliPlaybackPreferencesStore.read(storageKey).enableHeartbeatReport) {
            return Result.success(Unit)
        }
        return repository.playbackHeartbeat(
            key = key,
            playedTimeSec = playedTimeSec,
        )
    }

    suspend fun prepare(): Result<String> =
        runCatching {
            preferences = BilibiliPlaybackPreferencesStore.read(storageKey)
            ensureStreams(forceRefresh = true)
            buildPlayableOrThrow()
        }

    suspend fun recover(
        failure: FailureContext,
        positionMs: Long
    ): Result<String> =
        runCatching {
            lastPositionMs = positionMs
            cleanupBlacklistIfNeeded()

            if (failure.isDecoderError) {
                if (tryFallbackCodec() || tryFallbackQuality()) {
                    return@runCatching buildPlayableOrThrow()
                }
            }

            val forceRefresh =
                shouldForceRefresh(failure) ||
                    isSelectedUrlExpiredSoon() ||
                    (failure.failingUrl.isNullOrBlank() && dash != null)

            val blacklisted = failure.failingUrl?.let { blacklistHost(it) } == true

            if (forceRefresh) {
                ensureStreams(forceRefresh = true)
                return@runCatching buildPlayableOrThrow()
            }

            if (blacklisted) {
                return@runCatching rebuildWithBlacklistOrRefresh(forceRefresh = false)
            }

            rebuildWithBlacklistOrRefresh(forceRefresh = false)
        }

    suspend fun applyPreferenceUpdate(
        update: PreferenceUpdate,
        positionMs: Long
    ): Result<String> =
        runCatching {
            lastPositionMs = positionMs
            val latest = BilibiliPlaybackPreferencesStore.read(storageKey)
            val updated =
                latest.copy(
                    playMode = update.playMode ?: latest.playMode,
                    preferredQualityQn = update.qualityQn ?: latest.preferredQualityQn,
                    preferredVideoCodec = update.videoCodec ?: latest.preferredVideoCodec,
                    preferredAudioQualityId = update.audioQualityId ?: latest.preferredAudioQualityId,
                )
            BilibiliPlaybackPreferencesStore.write(storageKey, updated)
            preferences = updated

            if (playurl == null) {
                ensureStreams(forceRefresh = true)
            } else {
                rebuildCandidates()
            }

            buildPlayableOrThrow()
        }

    private fun shouldForceRefresh(failure: FailureContext): Boolean {
        val code = failure.httpResponseCode ?: return false
        return code == 403 || code == 404 || code == 410
    }

    private fun isSelectedUrlExpiredSoon(): Boolean {
        val expiresAtMs = selectedExpiresAtMs ?: return false
        return System.currentTimeMillis() >= (expiresAtMs - expiresMarginMs)
    }

    private fun parseExpiresAtMs(url: String?): Long? {
        val httpUrl = url?.toHttpUrlOrNull() ?: return null
        val raw =
            httpUrl.queryParameter("deadline")
                ?: httpUrl.queryParameter("expires")
                ?: httpUrl.queryParameter("expire")
                ?: return null
        val value = raw.toLongOrNull() ?: return null
        if (value <= 0) return null

        return if (value > 10_000_000_000L) {
            value
        } else {
            value * 1000
        }
    }

    private fun blacklistHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        val expiry = System.currentTimeMillis() + cdnBlacklistTtlMs
        val existed = cdnHostBlacklist.containsKey(host)
        cdnHostBlacklist[host] = expiry
        return !existed
    }

    private fun cleanupBlacklistIfNeeded() {
        if (cdnHostBlacklist.isEmpty()) return

        val now = System.currentTimeMillis()
        val iterator = cdnHostBlacklist.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= now) {
                iterator.remove()
            }
        }
    }

    private suspend fun rebuildWithBlacklistOrRefresh(forceRefresh: Boolean): String {
        if (forceRefresh) {
            ensureStreams(forceRefresh = true)
            return buildPlayableOrThrow()
        }

        val currentDash = dash
        if (currentDash != null && selectedVideo != null) {
            return buildPlayableOrThrow()
        }

        if (selectedDurlCandidates.isNotEmpty()) {
            val next = nextDurlCandidateOrNull()
            if (next != null) {
                updateSnapshot(
                    dashAvailable = false,
                    selectedQualityQn = preferences.preferredQualityQn,
                    selectedVideoCodec = preferences.preferredVideoCodec,
                    selectedAudioQualityId = preferences.preferredAudioQualityId,
                    qualities = emptyList(),
                    codecs = emptyList(),
                    audios = emptyList(),
                )
                return next
            }
        }

        ensureStreams(forceRefresh = true)
        return buildPlayableOrThrow()
    }

    private fun nextDurlCandidateOrNull(): String? {
        if (selectedDurlCandidates.isEmpty()) return null

        val blacklist = cdnHostBlacklist.keys
        val startIndex = selectedDurlIndex.coerceAtLeast(0)
        for (offset in 1..selectedDurlCandidates.size) {
            val index = (startIndex + offset) % selectedDurlCandidates.size
            val url = selectedDurlCandidates[index]
            val host = url.toHttpUrlOrNull()?.host ?: continue
            if (!blacklist.contains(host)) {
                selectedDurlIndex = index
                return url
            }
        }

        selectedDurlIndex = (startIndex + 1).coerceAtMost(selectedDurlCandidates.lastIndex)
        return selectedDurlCandidates.getOrNull(selectedDurlIndex)
    }

    private fun tryFallbackCodec(): Boolean {
        val current = selectedVideo ?: return false
        val currentCodec = BilibiliVideoCodec.fromCodecid(current.codecid) ?: return false
        val fallbackOrder =
            when (currentCodec) {
                BilibiliVideoCodec.AV1 -> listOf(BilibiliVideoCodec.HEVC, BilibiliVideoCodec.AVC)
                BilibiliVideoCodec.HEVC -> listOf(BilibiliVideoCodec.AVC)
                else -> emptyList()
            }

        for (codec in fallbackOrder) {
            val candidates = videoCandidatesByCodec[codec].orEmpty()
            if (candidates.isEmpty()) continue
            selectedVideo = selectVideoByQuality(candidates)
            return selectedVideo != null
        }

        return false
    }

    private fun tryFallbackQuality(): Boolean {
        val current = selectedVideo ?: return false
        val codec = BilibiliVideoCodec.fromCodecid(current.codecid) ?: BilibiliVideoCodec.AUTO
        val candidates = videoCandidatesByCodec[codec].orEmpty()
        if (candidates.isEmpty()) return false

        val next = candidates.firstOrNull { it.id < current.id } ?: return false
        selectedVideo = next
        return true
    }

    private suspend fun ensureStreams(forceRefresh: Boolean) {
        if (!forceRefresh && playurl != null) {
            return
        }

        val primary =
            when (val parsed = key) {
                is BilibiliKeys.ArchiveKey -> {
                    val cid = parsed.cid ?: throw BilibiliException.from(-1, "取流失败：cid为空")
                    repository.playurl(parsed.bvid, cid, preferences)
                }

                is BilibiliKeys.PgcEpisodeKey -> {
                    repository.pgcPlayurl(
                        epId = parsed.epId,
                        cid = parsed.cid,
                        avid = parsed.avid,
                        preferences = preferences,
                        session = pgcSession,
                    )
                }

                else -> {
                    Result.failure(BilibiliException.from(-1, "取流失败：不支持的资源类型"))
                }
            }
        val primaryData = primary.getOrNull()

        val playable =
            if (primaryData != null && hasPlayableStream(primaryData)) {
                primaryData
            } else {
                val fallback =
                    when (val parsed = key) {
                        is BilibiliKeys.ArchiveKey -> {
                            val cid = parsed.cid ?: throw BilibiliException.from(-1, "取流失败：cid为空")
                            repository.playurlFallbackOrNull(parsed.bvid, cid, preferences)
                        }

                        is BilibiliKeys.PgcEpisodeKey -> {
                            repository.pgcPlayurlFallbackOrNull(
                                epId = parsed.epId,
                                cid = parsed.cid,
                                avid = parsed.avid,
                                preferences = preferences,
                                session = pgcSession,
                            )
                        }

                        else -> null
                    } ?: throw primary.exceptionOrNull()
                        ?: BilibiliException.from(-1, "取流失败：无可用回退流")
                fallback.getOrNull()?.takeIf { hasPlayableStream(it) }
                    ?: throw fallback.exceptionOrNull()
                        ?: primary.exceptionOrNull()
                        ?: BilibiliException.from(-1, "取流失败：响应无可用流")
            }

        playurl = playable
        dash = playable.dash
        durl = playable.durl

        rebuildCandidates()
    }

    private fun hasPlayableStream(data: BilibiliPlayurlData): Boolean {
        if (data.dash?.video?.isNotEmpty() == true) {
            return true
        }
        if (data.durl
                .firstOrNull()
                ?.url
                ?.isNotBlank() == true
        ) {
            return true
        }
        return false
    }

    private fun rebuildCandidates() {
        val dashData = dash

        videoCandidatesByCodec =
            dashData
                ?.video
                ?.groupBy { media -> BilibiliVideoCodec.fromCodecid(media.codecid) ?: BilibiliVideoCodec.AUTO }
                ?.mapValues { (_, list) ->
                    list.sortedWith(compareByDescending<BilibiliDashMediaData> { it.id }.thenByDescending { it.bandwidth })
                }.orEmpty()

        audioCandidates =
            dashData
                ?.audio
                ?.sortedWith(compareByDescending<BilibiliDashMediaData> { it.bandwidth }.thenByDescending { it.id })
                .orEmpty()

        selectedVideo =
            dashData
                ?.video
                ?.takeIf { it.isNotEmpty() }
                ?.let { selectVideoByPreferences(it) }

        selectedAudio =
            if (audioCandidates.isEmpty()) {
                null
            } else if (preferences.preferredAudioQualityId > 0) {
                audioCandidates.firstOrNull { it.id == preferences.preferredAudioQualityId } ?: audioCandidates.first()
            } else {
                audioCandidates.first()
            }

        selectedDurlCandidates =
            durl
                .firstOrNull()
                ?.let { first ->
                    BilibiliCdnStrategy.resolveUrls(
                        baseUrl = first.url,
                        backupUrls = first.backupUrl,
                        options = BilibiliCdnStrategy.Options(hostOverride = preferences.cdnService.host),
                    )
                }.orEmpty()
        selectedDurlIndex = 0
    }

    private fun selectVideoByPreferences(candidates: List<BilibiliDashMediaData>): BilibiliDashMediaData? {
        val codecOrder =
            when (preferences.preferredVideoCodec) {
                BilibiliVideoCodec.AUTO -> listOf(BilibiliVideoCodec.AV1, BilibiliVideoCodec.HEVC, BilibiliVideoCodec.AVC)
                BilibiliVideoCodec.AV1 -> listOf(BilibiliVideoCodec.AV1, BilibiliVideoCodec.HEVC, BilibiliVideoCodec.AVC)
                BilibiliVideoCodec.HEVC -> listOf(BilibiliVideoCodec.HEVC, BilibiliVideoCodec.AVC)
                BilibiliVideoCodec.AVC -> listOf(BilibiliVideoCodec.AVC)
            }

        for (codec in codecOrder) {
            val group = candidates.filter { it.codecid == codec.codecid }
            if (group.isEmpty()) continue
            return selectVideoByQuality(group)
        }

        return selectVideoByQuality(candidates)
    }

    private fun selectVideoByQuality(candidates: List<BilibiliDashMediaData>): BilibiliDashMediaData? {
        val preferredQn = preferences.preferredQualityQn
        if (preferredQn <= 0) {
            return candidates.maxByOrNull { it.bandwidth }
        }

        val exact = candidates.firstOrNull { it.id == preferredQn }
        if (exact != null) return exact

        val lowerOrEqual = candidates.filter { it.id <= preferredQn }
        return (lowerOrEqual.ifEmpty { candidates }).maxByOrNull { it.id }
    }

    private suspend fun buildPlayableOrThrow(): String {
        val dashData = dash
        val allowDash =
            preferences.playMode != BilibiliPlayMode.MP4 &&
                dashData != null &&
                dashData.video.isNotEmpty() &&
                selectedVideo != null

        if (allowDash) {
            val dashValue = dashData ?: throw BilibiliException.from(-1, "取流失败：DASH为空")
            val selectedVideo = selectedVideo ?: throw BilibiliException.from(-1, "取流失败：无可用视频流")
            val blacklist = cdnHostBlacklist.keys

            selectedExpiresAtMs =
                buildList {
                    add(parseExpiresAtMs(selectedVideo.baseUrl))
                    selectedVideo.backupUrl.forEach { add(parseExpiresAtMs(it)) }
                    selectedAudio?.let { audio ->
                        add(parseExpiresAtMs(audio.baseUrl))
                        audio.backupUrl.forEach { add(parseExpiresAtMs(it)) }
                    }
                }.filterNotNull().minOrNull()

            withContext(Dispatchers.IO) {
                BilibiliMpdGenerator.writeDashMpd(
                    outputFile = mpdFile,
                    dash = dashValue,
                    videos = buildDashVideoRepresentations(dashValue, selectedVideo),
                    audios = buildDashAudioRepresentations(audioCandidates, selectedAudio),
                    cdnHostOverride = preferences.cdnService.host,
                    cdnHostBlacklist = blacklist,
                )
            }

            updateSnapshot(
                dashAvailable = true,
                selectedQualityQn = selectedVideo.id,
                selectedVideoCodec = BilibiliVideoCodec.fromCodecid(selectedVideo.codecid) ?: preferences.preferredVideoCodec,
                selectedAudioQualityId = selectedAudio?.id ?: preferences.preferredAudioQualityId,
                qualities =
                    dashValue.video
                        .map { it.id }
                        .distinct()
                        .sorted(),
                codecs =
                    dashValue.video
                        .mapNotNull { BilibiliVideoCodec.fromCodecid(it.codecid) }
                        .distinct(),
                audios = audioCandidates.map { AudioOption(it.id, it.bandwidth) },
            )

            return mpdFile.absolutePath
        }

        val allowMp4 = preferences.playMode != BilibiliPlayMode.DASH
        if (allowMp4) {
            val safeIndex =
                selectedDurlIndex
                    .coerceAtLeast(0)
                    .coerceAtMost(selectedDurlCandidates.lastIndex)
            val candidate =
                selectedDurlCandidates
                    .getOrNull(safeIndex)
                    ?: selectedDurlCandidates.firstOrNull()
            if (!candidate.isNullOrBlank()) {
                selectedExpiresAtMs = parseExpiresAtMs(candidate)
                updateSnapshot(
                    dashAvailable = false,
                    selectedQualityQn = preferences.preferredQualityQn,
                    selectedVideoCodec = preferences.preferredVideoCodec,
                    selectedAudioQualityId = preferences.preferredAudioQualityId,
                    qualities = emptyList(),
                    codecs = emptyList(),
                    audios = emptyList(),
                )
                return candidate
            }
        }

        throw BilibiliException.from(-1, "取流失败：无可用流")
    }

    private fun buildDashVideoRepresentations(
        dash: BilibiliDashData,
        selectedVideo: BilibiliDashMediaData
    ): List<BilibiliDashMediaData> {
        val candidates =
            dash.video
                .filter { it.baseUrl.isNotBlank() }
                .sortedWith(compareByDescending<BilibiliDashMediaData> { it.id }.thenByDescending { it.bandwidth })

        val codecid = selectedVideo.codecid
        val codecFiltered =
            if (codecid == null) {
                candidates
            } else {
                candidates.filter { it.codecid == codecid }.ifEmpty { candidates }
            }

        val maxQualityQn = selectedVideo.id
        val qualityFiltered = codecFiltered.filter { it.id <= maxQualityQn }.ifEmpty { codecFiltered }

        return listOf(selectedVideo) + qualityFiltered.filterNot { it == selectedVideo }
    }

    private fun buildDashAudioRepresentations(
        candidates: List<BilibiliDashMediaData>,
        selectedAudio: BilibiliDashMediaData?
    ): List<BilibiliDashMediaData> {
        if (selectedAudio == null) return emptyList()
        val maxBandwidth = selectedAudio.bandwidth.takeIf { it > 0 } ?: return listOf(selectedAudio)

        val filtered =
            candidates
                .filter { it.baseUrl.isNotBlank() }
                .filter { it.bandwidth in 1..maxBandwidth }
                .sortedWith(compareByDescending<BilibiliDashMediaData> { it.bandwidth }.thenByDescending { it.id })
                .ifEmpty { listOf(selectedAudio) }

        return listOf(selectedAudio) + filtered.filterNot { it == selectedAudio }
    }

    private fun updateSnapshot(
        dashAvailable: Boolean,
        selectedQualityQn: Int,
        selectedVideoCodec: BilibiliVideoCodec,
        selectedAudioQualityId: Int,
        qualities: List<Int>,
        codecs: List<BilibiliVideoCodec>,
        audios: List<AudioOption>
    ) {
        snapshot =
            Snapshot(
                playMode = preferences.playMode,
                dashAvailable = dashAvailable,
                selectedQualityQn = selectedQualityQn,
                selectedVideoCodec = selectedVideoCodec,
                selectedAudioQualityId = selectedAudioQualityId,
                qualities = qualities,
                videoCodecs = codecs,
                audios = audios,
                lastPositionMs = lastPositionMs,
            )
    }
}
