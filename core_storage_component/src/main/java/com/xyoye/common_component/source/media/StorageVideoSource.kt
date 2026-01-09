package com.xyoye.common_component.source.media

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType

/**
 * Created by xyoye on 2023/1/2.
 */

class StorageVideoSource(
    private val playUrl: String,
    private val file: StorageFile,
    private val videoSources: List<StorageFile>,
    private var danmu: LocalDanmuBean?,
    private var subtitlePath: String?,
    private var audioPath: String?,
    private val playbackProfile: PlaybackProfile,
    private val playbackAddon: PlaybackAddon? = createDefaultPlaybackAddon(playUrl, file, playbackProfile),
) : BaseVideoSource(
        videoSources.indexOfFirst { it.uniqueKey() == file.uniqueKey() },
        videoSources,
    ) {
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
        val fileName = videoSources[index].fileName()
        return getFileName(fileName)
    }

    override suspend fun indexSource(index: Int): BaseVideoSource? =
        StorageVideoSourceFactory.create(
            videoSources[index],
            playbackProfile,
        )

    override fun getVideoUrl(): String = playUrl

    override fun getVideoTitle(): String = file.fileName()

    override fun getCurrentPosition(): Long = file.playHistory?.videoPosition ?: 0

    override fun getMediaType(): MediaType = file.storage.library.mediaType

    override fun getUniqueKey(): String = file.uniqueKey()

    override fun getHttpHeader(): Map<String, String>? = file.storage.getNetworkHeaders(file)

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
            return file.getRealFile().mFileIndex
        }
        return -1
    }

    fun getPlayTaskId(): Long {
        if (file is TorrentStorageFile) {
            return ThunderManager.getInstance().getTaskId(file.filePath())
        }
        return -1L
    }

    fun getStorageFile(): StorageFile = file

    fun indexStorageFile(index: Int): StorageFile = videoSources[index]

    fun getPlaybackProfile(): PlaybackProfile = playbackProfile

    private companion object {
        fun createDefaultPlaybackAddon(
            playUrl: String,
            file: StorageFile,
            playbackProfile: PlaybackProfile,
        ): PlaybackAddon? =
            if (file.storage.library.mediaType == MediaType.BILIBILI_STORAGE) {
                BilibiliPlaybackAddon(
                    identity =
                        PlaybackIdentity(
                            storageId = file.storage.library.id,
                            uniqueKey = file.uniqueKey(),
                            mediaType = file.storage.library.mediaType,
                            storagePath = file.storagePath(),
                            videoTitle = file.fileName(),
                            videoUrl = playUrl,
                        ),
                    supportsSeamlessPreferenceSwitch = playbackProfile.playerType == PlayerType.TYPE_EXO_PLAYER,
                )
            } else {
                null
            }
    }
}
