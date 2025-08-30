package com.xyoye.user_component.ui.activities.cache_manager

import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.utils.*
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.CacheBean
import com.xyoye.data_component.enums.CacheType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class CacheManagerViewModel : BaseViewModel() {

    private val appCacheDir = BaseApplication.getAppContext().cacheDir
    private val externalCacheDir = File(PathHelper.getCachePath())

    val systemCachePath = appCacheDir.absolutePath ?: ""
    val externalCachePath = externalCacheDir.absolutePath ?: ""

    val systemCacheSizeText = ObservableField("")
    val externalCacheSizeText = ObservableField("")

    val cacheDirsLiveData = MutableLiveData<List<CacheBean>>()

    fun refreshCache() {
        try {
            val appCacheDirSize = IOUtils.getDirectorySize(appCacheDir)
            systemCacheSizeText.set(formatFileSize(appCacheDirSize))

            val externalCacheDirSize = IOUtils.getDirectorySize(externalCacheDir)
            externalCacheSizeText.set(formatFileSize(externalCacheDirSize))

            val cacheDirs = mutableListOf<CacheBean>()
            CacheType.values().forEach {
                var fileCount = 0
                try {
                    if (it == CacheType.DANMU_CACHE) {
                        fileCount = getDanmuFileCount(PathHelper.getDanmuDirectory())
                    } else if (it == CacheType.SUBTITLE_CACHE) {
                        fileCount = getSubtitleFileCount(PathHelper.getSubtitleDirectory())
                    }
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "CacheManagerViewModel",
                        "refreshCache",
                        "Failed to count files for cache type: ${it.name}"
                    )
                }
                val cacheBean = CacheBean(it, fileCount, getCacheSize(it))
                cacheDirs.add(cacheBean)
            }

            val otherCacheBean = CacheBean(null, 0, getCacheSize(null))
            cacheDirs.add(otherCacheBean)

            cacheDirsLiveData.postValue(cacheDirs)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "CacheManagerViewModel",
                "refreshCache",
                "Failed to refresh cache information"
            )
        }
    }

    fun clearAppCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clearCacheDirectory(appCacheDir)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "CacheManagerViewModel",
                    "clearAppCache",
                    "Failed to clear app cache directory: ${appCacheDir.absolutePath}"
                )
            }
        }
    }

    fun clearCacheByType(cacheType: CacheType?) {
        if (cacheType != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    clearCacheDirectory(PathHelper.getCacheDirectory(cacheType))
                    refreshCache()
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "CacheManagerViewModel",
                        "clearCacheByType",
                        "Failed to clear cache directory for type: ${cacheType.name}"
                    )
                }
            }
            return
        }
        val childCacheDirs = externalCacheDir.listFiles()
            ?: return

        val namedCacheDirPaths = CacheType.values().map {
            PathHelper.getCacheDirectory(it).absolutePath
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                childCacheDirs.forEach {
                    if (it.absolutePath !in namedCacheDirPaths) {
                        clearCacheDirectory(it)
                    }
                }
                refreshCache()
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "CacheManagerViewModel",
                    "clearCacheByType",
                    "Failed to clear other cache directories"
                )
            }
        }
    }

    private fun getCacheSize(cacheType: CacheType?): Long {
        return try {
            if (cacheType != null) {
                val cacheTypeDir = PathHelper.getCacheDirectory(cacheType)
                IOUtils.getDirectorySize(cacheTypeDir)
            } else {
                val totalCacheSize = IOUtils.getDirectorySize(externalCacheDir)
                var namedCacheSize = 0L
                CacheType.values().forEach {
                    namedCacheSize += getCacheSize(it)
                }
                totalCacheSize - namedCacheSize
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "CacheManagerViewModel",
                "getCacheSize",
                "Failed to get cache size for type: ${cacheType?.name ?: "other"}"
            )
            0L
        }
    }

    /**
     * 删除文件夹内所有文件
     */
    private fun clearCacheDirectory(directory: File) {
        try {
            if (!directory.exists())
                return

            if (directory.isFile)
                directory.delete()

            directory.listFiles()?.forEach {
                if (it.isDirectory) {
                    clearCacheDirectory(it)
                } else {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "CacheManagerViewModel",
                "clearCacheDirectory",
                "Failed to clear cache directory: ${directory.absolutePath}"
            )
        }
    }

    /**
     * 获取文件夹内弹幕文件数量
     */
    private fun getDanmuFileCount(danmuDirectory: File): Int {
        if (!danmuDirectory.exists())
            return 0
        if (danmuDirectory.isFile && isDanmuFile(danmuDirectory.absolutePath))
            return 1

        var totalCount = 0
        danmuDirectory.listFiles()?.forEach {
            if (it.isDirectory) {
                totalCount += getDanmuFileCount(it)
            } else if (isDanmuFile(it.absolutePath)) {
                totalCount += 1
            }
        }

        return totalCount
    }

    /**
     * 获取文件夹内字幕文件数量
     */
    private fun getSubtitleFileCount(subtitleDirectory: File): Int {
        if (!subtitleDirectory.exists())
            return 0
        if (subtitleDirectory.isFile && isSubtitleFile(subtitleDirectory.absolutePath))
            return 1

        var totalCount = 0
        subtitleDirectory.listFiles()?.forEach {
            if (it.isDirectory) {
                totalCount += getSubtitleFileCount(it)
            } else if (isSubtitleFile(it.absolutePath)) {
                totalCount += 1
            }
        }

        return totalCount
    }
}