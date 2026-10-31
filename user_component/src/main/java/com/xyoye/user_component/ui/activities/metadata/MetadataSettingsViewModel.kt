package com.xyoye.user_component.ui.activities.metadata

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.network.tmdb.TmdbClient
import com.xyoye.data_component.entity.MetadataScanRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MetadataSettingsViewModel : BaseViewModel() {
    val scans = MetadataCoordinator.repository.dao.observeScans()
    val message = MutableLiveData("")
    val configured get() = TmdbClient.configured

    init {
        MetadataCoordinator.initialize()
        viewModelScope.launch {
            MetadataCoordinator.taskFailure.collect { if (it != null) message.value = "任务未能保存或启动，请检查来源后重试。" }
        }
    }

    fun cancel(scan: MetadataScanRun) {
        MetadataCoordinator.cancel(scan.runId)
    }

    fun retry(scan: MetadataScanRun) {
        MetadataCoordinator.startScan(scan.libraryId, scan.directory, scan.recursive)
    }

    fun clearHttpCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TmdbClient.clearHttpCache()
                message.postValue("请求缓存已清理，文件和人工关联保留。")
            } catch (
                _: Exception
            ) {
                message.postValue("缓存清理失败，请重试。")
            }
        }
    }

    fun clearImages() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loader = BaseApplication.getAppContext().imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                message.postValue("图片缓存已清理，文件与人工关联保留。")
            } catch (_: Exception) {
                message.postValue("图片缓存清理失败，请重试。")
            }
        }
    }
}
