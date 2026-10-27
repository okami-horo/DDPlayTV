package com.xyoye.player_component.ui.activities.player_intent

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.PlaybackEntryRequests
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.utils.getDirPath
import com.xyoye.common_component.utils.meida.VideoScan
import com.xyoye.data_component.entity.ExtendFolderEntity
import com.xyoye.data_component.media3.entity.Media3SourceType
import kotlinx.coroutines.launch
import java.io.File

class PlayerIntentViewModel : BaseViewModel() {
    private val requests = PlaybackEntryRequests(viewModelScope, { loading -> if (loading) showLoading() else hideLoading() })
    val playEvents = requests.outcomes

    val isParseError = ObservableField(false)

    /**
     * 未识别的视频，将其目录添加至扩展扫描目录
     */
    fun addUnrecognizedFile(filePath: String) {
        SupervisorScope.IO.launch {
            val videoFile = File(filePath)
            if (videoFile.exists().not()) {
                return@launch
            }

            val videoData = DatabaseProvider.instance.getVideoDao().findVideoByPath(filePath)
            if (videoData != null) {
                return@launch
            }

            val folderPath = getDirPath(filePath)
            val extendVideos = VideoScan.traverse(folderPath)
            if (extendVideos.isNotEmpty()) {
                DatabaseProvider.instance.getExtendFolderDao().insert(
                    ExtendFolderEntity(folderPath, extendVideos.size)
                )
            }
        }
    }

    fun openIntentUrl(link: String) {
        val descriptor = ExternalUriDescriptor(link, Media3SourceType.STREAM)
        requests.submit { PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.EXTERNAL_INTENT) }
    }
}
