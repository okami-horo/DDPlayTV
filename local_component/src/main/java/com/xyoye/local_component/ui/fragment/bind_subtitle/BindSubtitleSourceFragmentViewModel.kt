package com.xyoye.local_component.ui.fragment.bind_subtitle

import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.getFileNameNoExtension
import com.xyoye.common_component.utils.subtitle.SubtitleMatchHelper
import com.xyoye.common_component.utils.subtitle.SubtitleSearchHelper
import com.xyoye.common_component.utils.subtitle.SubtitleUtils
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.SubDetailData
import com.xyoye.data_component.data.SubtitleSourceBean
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody

/**
 * Created by xyoye on 2022/1/25
 */
class BindSubtitleSourceFragmentViewModel : BaseViewModel() {
    private val searchSubtitleRepository = SubtitleSearchHelper(viewModelScope)

    lateinit var storageFile: StorageFile

    val subtitleSearchLiveData = searchSubtitleRepository.subtitleLiveData
    val subtitleMatchLiveData = MutableLiveData<PagingData<SubtitleSourceBean>>()
    val searchSubtitleDetailLiveData = MutableLiveData<SubDetailData>()
    val unzipResultLiveData = MutableLiveData<String>()

    fun matchSubtitle() {
        if (storageFile.storage.library.mediaType != MediaType.LOCAL_STORAGE) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                showLoading()
                val subtitleSources = SubtitleMatchHelper.matchSubtitle(storageFile.filePath())
                val matchPagingData = PagingData.from(subtitleSources)
                hideLoading()
                subtitleMatchLiveData.postValue(matchPagingData)
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BindSubtitleSourceFragmentViewModel",
                    "matchSubtitle",
                    "File: ${storageFile.fileName()}",
                )
            }
        }
    }

    fun searchSubtitle(text: String) {
        searchSubtitleRepository.search(text)
    }

    fun detailSearchSubtitle(sourceBean: SubtitleSourceBean) {
        viewModelScope.launch {
            try {
                showLoading()
                val result =
                    ResourceRepository.getSubtitleDetail(
                        SubtitleConfig.getShooterSecret().orEmpty(),
                        sourceBean.id.toString(),
                    )
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown subtitle detail error"),
                        "BindSubtitleSourceFragmentViewModel",
                        "detailSearchSubtitle",
                        "Subtitle ID: ${sourceBean.id}",
                    )
                    exception?.message?.toastError()
                    return@launch
                }

                val subtitle =
                    result
                        .getOrNull()
                        ?.sub
                        ?.subs
                        ?.firstOrNull()
                        ?: run {
                            ToastCenter.showError("获取字幕详情失败")
                            return@launch
                        }

                searchSubtitleDetailLiveData.postValue(subtitle)
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BindSubtitleSourceFragmentViewModel",
                    "detailSearchSubtitle",
                    "Unexpected error for subtitle ID: ${sourceBean.id}",
                )
                e.message?.toastError()
            }
        }
    }

    fun downloadSearchSubtitle(
        fileName: String?,
        sourceUrl: String,
        unzip: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                showLoading()
                val name =
                    if (TextUtils.isEmpty(fileName)) {
                        "${getFileNameNoExtension(storageFile.filePath())}.ass"
                    } else {
                        fileName!!
                    }

                val result = ResourceRepository.getResourceResponseBody(sourceUrl)
                if (result.isFailure) {
                    hideLoading()
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown download error"),
                        "BindSubtitleSourceFragmentViewModel",
                        "downloadSearchSubtitle",
                        "File: $name, URL: $sourceUrl",
                    )
                    exception?.message?.toastError()
                    return@launch
                }

                result.getOrNull()?.let {
                    if (unzip) {
                        unzipSaveSubtitle(name, it)
                    } else {
                        saveSubtitle(name, it)
                    }
                }
                hideLoading()
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BindSubtitleSourceFragmentViewModel",
                    "downloadSearchSubtitle",
                    "Unexpected error downloading subtitle: $fileName",
                )
                ToastCenter.showError("下载字幕失败")
            }
        }
    }

    private suspend fun unzipSaveSubtitle(
        fileName: String,
        responseBody: ResponseBody
    ) {
        try {
            val unzipDirPath =
                SubtitleUtils.saveAndUnzipFile(fileName, responseBody.byteStream()).orEmpty()
            if (unzipDirPath.isEmpty()) {
                ErrorReportHelper.postException(
                    "Subtitle unzip failed",
                    "BindSubtitleSourceFragmentViewModel",
                    null,
                )
                ToastCenter.showError("解压字幕文件失败，请尝试手动解压")
                return
            }
            unzipResultLiveData.postValue(unzipDirPath)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "BindSubtitleSourceFragmentViewModel",
                "unzipSaveSubtitle",
                "File: $fileName",
            )
            ToastCenter.showError("解压字幕文件失败，请尝试手动解压")
        }
    }

    private fun saveSubtitle(
        fileName: String,
        responseBody: ResponseBody
    ) {
        try {
            val subtitlePath = SubtitleUtils.saveSubtitle(fileName, responseBody.byteStream())
            if (subtitlePath != null) {
                databaseSubtitle(subtitlePath)
                ToastCenter.showSuccess("绑定字幕成功！")
            } else {
                ErrorReportHelper.postException(
                    "Subtitle save failed",
                    "BindSubtitleSourceFragmentViewModel",
                    null,
                )
                ToastCenter.showError("保存字幕失败")
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "BindSubtitleSourceFragmentViewModel",
                "saveSubtitle",
                "File: $fileName",
            )
            ToastCenter.showError("保存字幕失败")
        }
    }

    fun unbindSubtitle() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                databaseSubtitle(null)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BindSubtitleSourceFragmentViewModel",
                    "unbindSubtitle",
                    "File: ${storageFile.fileName()}",
                )
            }
        }
    }

    fun databaseSubtitle(filePath: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val storageId = storageFile.storage.library.id
                val history =
                    DatabaseManager.instance
                        .getPlayHistoryDao()
                        .getPlayHistory(storageFile.uniqueKey(), storageId)

                if (history != null) {
                    history.subtitlePath = filePath
                    DatabaseManager.instance.getPlayHistoryDao().insert(history)
                    return@launch
                }

                val newHistory =
                    PlayHistoryEntity(
                        0,
                        "",
                        "",
                        mediaType = storageFile.storage.library.mediaType,
                        uniqueKey = storageFile.uniqueKey(),
                        subtitlePath = filePath,
                        storageId = storageId,
                    )
                DatabaseManager.instance.getPlayHistoryDao().insert(newHistory)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BindSubtitleSourceFragmentViewModel",
                    "databaseSubtitle",
                    "File path: $filePath, Storage file: ${storageFile.fileName()}",
                )
            }
        }
    }
}
