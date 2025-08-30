package com.xyoye.local_component.ui.activities.shooter_subtitle

import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.utils.subtitle.SubtitleSearchHelper
import com.xyoye.common_component.utils.subtitle.SubtitleUtils
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.SubDetailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShooterSubtitleViewModel : BaseViewModel() {

    private val searchSubtitleRepository = SubtitleSearchHelper(viewModelScope)
    val searchSubDetailLiveData = MutableLiveData<SubDetailData>()

    val searchSubtitleLiveData = searchSubtitleRepository.subtitleLiveData

    /**
     * 搜索字幕
     */
    fun searchSubtitle(videoName: String) {
        searchSubtitleRepository.search(videoName)
    }

    /**
     * 获取搜索字幕详情
     */
    fun getSearchSubDetail(subtitleId: Int) {
        viewModelScope.launch {
            try {
                showLoading()
                val result = ResourceRepository.getSubtitleDetail(
                    SubtitleConfig.getShooterSecret().orEmpty(),
                    subtitleId.toString()
                )
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown subtitle detail error"),
                        "ShooterSubtitleViewModel",
                        "getSearchSubDetail",
                        "Subtitle ID: $subtitleId"
                    )
                    exception?.message?.toastError()
                    return@launch
                }

                val subtitle = result.getOrNull()?.sub?.subs?.firstOrNull()
                if (subtitle == null) {
                    ToastCenter.showError("获取字幕详情失败")
                    return@launch
                }

                searchSubDetailLiveData.postValue(subtitle)
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "ShooterSubtitleViewModel",
                    "getSearchSubDetail",
                    "Unexpected error for subtitle ID: $subtitleId"
                )
                e.message?.toastError()
            }
        }
    }

    fun downloadSubtitle(fileName: String, downloadUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                showLoading()
                val result = ResourceRepository.getResourceResponseBody(downloadUrl)
                val subtitlePath = result.getOrNull()?.byteStream()?.let {
                    SubtitleUtils.saveSubtitle(fileName, it)
                }
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown download error"),
                        "ShooterSubtitleViewModel",
                        "downloadSubtitle",
                        "File: $fileName, URL: $downloadUrl"
                    )
                }

                if (subtitlePath.isNullOrEmpty()) {
                    ErrorReportHelper.postException(
                        "Subtitle save failed",
                        "ShooterSubtitleViewModel",
                        null
                    )
                    ToastCenter.showError("保存字幕失败")
                    return@launch
                }

                ToastCenter.showSuccess("字幕下载成功：$subtitlePath", Toast.LENGTH_LONG)
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "ShooterSubtitleViewModel",
                    "downloadSubtitle",
                    "Unexpected error downloading subtitle: $fileName"
                )
                ToastCenter.showError("保存字幕失败")
            }
        }
    }

    /**
     * 下载压缩文件，并解压
     */
    fun downloadAndUnzipFile(fileName: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                showLoading()
                val result = ResourceRepository.getResourceResponseBody(url)
                val unzipDirPath = result.getOrNull()?.byteStream()?.let {
                    SubtitleUtils.saveAndUnzipFile(fileName, it)
                }
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown download error"),
                        "ShooterSubtitleViewModel",
                        "downloadAndUnzipFile",
                        "File: $fileName, URL: $url"
                    )
                }

                if (unzipDirPath.isNullOrEmpty()) {
                    ErrorReportHelper.postException(
                        "Subtitle unzip failed",
                        "ShooterSubtitleViewModel",
                        null
                    )
                    ToastCenter.showError("解压字幕文件失败，请尝试手动解压")
                    return@launch
                }

                ToastCenter.showSuccess("字幕下载成功：$unzipDirPath", Toast.LENGTH_LONG)
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "ShooterSubtitleViewModel",
                    "downloadAndUnzipFile",
                    "Unexpected error downloading and unzipping: $fileName"
                )
                ToastCenter.showError("解压字幕文件失败，请尝试手动解压")
            }
        }
    }
}