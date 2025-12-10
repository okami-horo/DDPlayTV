package com.xyoye.common_component.utils.subtitle

import android.content.Context
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.common_component.utils.IOUtils
import com.xyoye.common_component.utils.getFileExtension
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

object SubtitleFontCacheHelper {
    private const val TAG = "SubtitleFontCacheHelper"
    private val fontDirCandidates = listOf("fonts", "font")
    private val supportedExtensions = setOf("ttf", "otf", "ttc", "otc")

    fun findFontDirectory(files: List<StorageFile>): StorageFile? {
        return files.firstOrNull { file ->
            file.isDirectory() && fontDirCandidates.any { it.equals(file.fileName(), ignoreCase = true) }
        }
    }

    suspend fun listFontFiles(storage: Storage, fontDirectory: StorageFile): List<StorageFile> {
        val currentDirectory = storage.directory
        val currentFiles = storage.directoryFiles
        return try {
            storage.openDirectory(fontDirectory, false)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogFacade.w(LogModule.SUBTITLE, TAG, "open font directory failed: ${e.message}")
            }
            emptyList()
        } finally {
            storage.directory = currentDirectory
            storage.directoryFiles = currentFiles
        }
    }

    fun filterFontFiles(files: List<StorageFile>): List<StorageFile> {
        return files.filter { it.isFile() && isFontFile(it.fileName()) }
    }

    fun isFontFile(fileName: String): Boolean {
        val extension = getFileExtension(fileName).lowercase()
        return supportedExtensions.contains(extension)
    }

    fun ensureFontDirectory(context: Context): File? {
        SubtitleFontManager.ensureDefaultFont(context)
        return SubtitleFontManager.getFontsDirectoryPath(context)
            ?.let { File(it) }
            ?.apply {
                if (exists().not()) {
                    runCatching { mkdirs() }
                }
            }
    }

    fun isFontCached(directory: File, fileName: String): Boolean {
        val target = File(directory, fileName)
        return target.exists() && target.length() > 0
    }

    fun cacheFontFile(directory: File, fileName: String, inputStream: InputStream): Boolean {
        val target = File(directory, fileName)
        if (target.exists()) {
            target.delete()
        }
        var outputStream: BufferedOutputStream? = null
        return runCatching {
            outputStream = BufferedOutputStream(FileOutputStream(target))
            val buffer = ByteArray(8 * 1024)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream?.write(buffer, 0, len)
            }
            outputStream?.flush()
            true
        }.onFailure {
            target.delete()
            LogFacade.w(LogModule.SUBTITLE, TAG, "cache font failed: ${it.message}")
        }.also {
            IOUtils.closeIO(outputStream)
            IOUtils.closeIO(inputStream)
        }.getOrDefault(false)
    }
}
