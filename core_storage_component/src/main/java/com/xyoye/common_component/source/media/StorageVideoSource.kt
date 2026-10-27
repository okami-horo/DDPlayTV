package com.xyoye.common_component.source.media

import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.storage.transport.BilibiliPlaybackTransport
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.enums.MediaType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by xyoye on 2023/1/2.
 */

class StorageVideoSource(
    private val playUrl: String,
    private val file: StorageFile,
    videoSources: List<StorageFile>,
    private var danmu: LocalDanmuBean?,
    private var subtitlePath: String?,
    private var audioPath: String?,
    private val playbackProfile: PlaybackProfile,
    private val transportLease: PlaybackTransportLease,
    private val playbackAddon: PlaybackAddon? = createDefaultPlaybackAddon(playUrl, file, transportLease),
    private val httpHeaders: Map<String, String>? = file.storage.getNetworkHeaders(file)?.toMap(),
    private val releaseResolution: () -> Unit = {}
) : BaseVideoSource(
        videoSources.indexOfFirst { it.uniqueKey() == file.uniqueKey() },
        videoSources
    ) {
    private val released = AtomicBoolean(false)
    private val sourceTitles = videoSources.map { getFileName(it.fileName()) }
    private var queueMetadata: Pair<Int, List<String>>? = null

    /** Read-only controller projection; the active session owns queue selection and acquisition. */
    fun setQueueMetadata(
        index: Int,
        titles: List<String>
    ) {
        require(index in titles.indices)
        queueMetadata = index to titles.toList()
    }

    override fun getGroupIndex(): Int = queueMetadata?.first ?: super.getGroupIndex()

    override fun getGroupSize(): Int = queueMetadata?.second?.size ?: super.getGroupSize()

    override fun hasNextSource(): Boolean = getGroupIndex() + 1 < getGroupSize()

    override fun hasPreviousSource(): Boolean = getGroupIndex() > 0

    /** Call after backend detachment; this Source is the sole Addon/lease owner. */
    fun releasePlaybackResources() {
        if (released.compareAndSet(false, true)) {
            releaseTransportResources(
                transportLease.leaseId,
                listOf(
                    {
                        (playbackAddon as? PlaybackReleasableAddon)?.onRelease()
                        Unit
                    },
                    { transportLease.release() },
                    releaseResolution
                )
            )
        }
    }

    override fun getPlaybackAddon(): PlaybackAddon? = playbackAddon

    override fun getDanmu(): LocalDanmuBean? = danmu

    override fun setDanmu(danmu: LocalDanmuBean?) {
        this.danmu = danmu
    }

    override fun getSubtitlePath(): String? = subtitlePath

    override fun setSubtitlePath(path: String?) {
        subtitlePath = path
    }

    override fun getAudioPath(): String? = audioPath

    override fun setAudioPath(path: String?) {
        audioPath = path
    }

    override fun indexTitle(index: Int): String {
        queueMetadata?.let { return it.second[index] }
        return sourceTitles[index]
    }

    override fun getVideoUrl(): String = playUrl

    override fun getVideoTitle(): String = file.fileName()

    override fun getCurrentPosition(): Long = file.playHistory?.videoPosition ?: 0

    override fun getMediaType(): MediaType = file.storage.library.mediaType

    override fun getUniqueKey(): String = file.uniqueKey()

    override fun getHttpHeader(): Map<String, String>? = httpHeaders

    override fun getStorageId(): Int = file.storage.library.id

    override fun getStoragePath(): String = file.storagePath()

    fun getTorrentPath(): String? {
        if (file is TorrentStorageFile) {
            return file.filePath()
        }
        return null
    }

    fun getTorrentIndex(): Int {
        if (file is TorrentStorageFile) {
            return file.getFileInfo().fileIndex
        }
        return -1
    }

    fun getPlayTaskId(): Long {
        if (file is TorrentStorageFile) {
            return ThunderManager.getInstance().getTaskIdForPlayUrl(playUrl)
        }
        return -1L
    }

    fun getStorageFile(): StorageFile = file

    fun getPlaybackProfile(): PlaybackProfile = playbackProfile

    internal companion object {
        fun createDefaultPlaybackAddon(
            playUrl: String,
            file: StorageFile,
            transportLease: PlaybackTransportLease?
        ): PlaybackAddon? =
            if (file.storage.library.mediaType == MediaType.BILIBILI_STORAGE && transportLease is BilibiliPlaybackTransport) {
                BilibiliPlaybackAddon(
                    identity =
                        PlaybackIdentity(
                            storageId = file.storage.library.id,
                            uniqueKey = file.uniqueKey(),
                            mediaType = file.storage.library.mediaType,
                            storagePath = file.storagePath(),
                            videoTitle = file.fileName(),
                            videoUrl = playUrl
                        ),
                    token = transportLease.sessionToken
                )
            } else {
                null
            }
    }
}
