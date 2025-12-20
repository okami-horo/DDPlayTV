package com.xyoye.common_component.log

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import com.xyoye.common_component.config.DevelopLogConfigDefaults
import com.xyoye.common_component.log.model.LogFileMeta
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理本地 debug.log / debug_old.log 双文件。
 * - 优先写入 Download/<应用名>/logs（Android 10+ 使用 MediaStore，示例：/sdcard/Download/<app-name>/logs）
 * - 外部目录不可用时回退到内部 files/logs
 * - 冷启动时将上一会话的 debug.log 合并到 debug_old.log（按上限裁剪）
 * - 仅在需要写入时创建目录和文件
 */
open class LogFileManager(
    private val context: Context,
    private val fileSizeLimitBytes: Long = DevelopLogConfigDefaults.DEFAULT_LOG_FILE_SIZE_LIMIT_BYTES,
    private val minFreeSpaceBytes: Long = MIN_FREE_SPACE_BYTES
) {
    private val prepared = AtomicBoolean(false)
    private val ioLock = Any()
    private val useMediaStore = shouldUseMediaStore()
    private val contentResolver = context.contentResolver
    private val mediaRelativePath = LogPaths.logRelativePath(context)
    private var currentLogUri: Uri? = null
    private var previousLogUri: Uri? = null

    fun prepare() {
        if (prepared.get()) return
        synchronized(ioLock) {
            if (prepared.get()) return
            if (useMediaStore) {
                ensureWritableSpace()
                ensureMediaStoreEntry(LogPaths.CURRENT_LOG_FILE_NAME)
                ensureMediaStoreEntry(LogPaths.PREVIOUS_LOG_FILE_NAME)
                mergeCurrentIntoOldMediaStore()
            } else {
                ensureDirectory()
                ensureWritableSpace()
                mergeCurrentIntoOld()
            }
            prepared.set(true)
        }
    }

    open fun appendLine(line: String) {
        prepare()
        ensureWritableSpace()
        synchronized(ioLock) {
            if (useMediaStore) {
                val current = ensureMediaStoreEntry(LogPaths.CURRENT_LOG_FILE_NAME)
                appendLineToUri(current, line)
                rolloverIfNeededMediaStore(current)
            } else {
                val current = currentLogFile()
                FileWriter(current, true).use { writer ->
                    writer.appendLine(line)
                }
                rolloverIfNeeded(current)
            }
        }
    }

    fun listLogFiles(): List<LogFileMeta> {
        prepare()
        return if (useMediaStore) {
            val current = ensureMediaStoreEntry(LogPaths.CURRENT_LOG_FILE_NAME)
            val previous = ensureMediaStoreEntry(LogPaths.PREVIOUS_LOG_FILE_NAME)
            listOf(
                buildMediaStoreMeta(LogPaths.CURRENT_LOG_FILE_NAME, current),
                buildMediaStoreMeta(LogPaths.PREVIOUS_LOG_FILE_NAME, previous),
            )
        } else {
            val current = currentLogFile()
            val previous = previousLogFile()
            val files = listOf(current, previous)
            files.filter { it.exists() }.map { file ->
                LogFileMeta(
                    fileName = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    readable = file.canRead(),
                )
            }
        }
    }

    /**
     * 扫描日志目录中的现有日志文件，不触发 prepare，也不创建缺失文件。
     */
    fun scanLogFiles(): List<LogFileMeta> {
        return if (useMediaStore) {
            scanMediaStoreLogFiles()
        } else {
            val dir = logDirectory()
            if (!dir.exists() || !dir.isDirectory) {
                return emptyList()
            }
            val files =
                dir.listFiles { file ->
                    LogFileMeta.ALLOWED_FILE_NAMES.contains(file.name)
                } ?: return emptyList()
            files.sortedBy { it.name }.map { file ->
                LogFileMeta(
                    fileName = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    readable = file.canRead(),
                )
            }
        }
    }

    fun logDirectory(): File = LogPaths.logDirectory(context)

    fun currentLogFile(): File = LogPaths.currentLogFile(context)

    fun previousLogFile(): File = LogPaths.previousLogFile(context)

    private fun ensureDirectory() {
        if (useMediaStore) {
            return
        }
        val dir = logDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * 检查日志目录剩余空间，不足时抛出 IOException 触发上层熔断。
     */
    protected open fun ensureWritableSpace() {
        if (useMediaStore) {
            val root = LogPaths.downloadRootDirectory()
            if (root != null && root.exists()) {
                val available =
                    runCatching { StatFs(root.path).availableBytes }
                        .getOrNull()
                        ?: root.usableSpace
                if (available < minFreeSpaceBytes) {
                    throw IOException("insufficient space for log files, available=$available")
                }
            }
            return
        }
        val dir = logDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (dir.usableSpace < minFreeSpaceBytes) {
            throw IOException("insufficient space for log files, available=${dir.usableSpace}")
        }
    }

    private fun mergeCurrentIntoOld() {
        val current = currentLogFile()
        val previous = previousLogFile()
        if (current.exists() && current.length() > 0) {
            appendFileWithClamp(current, previous)
            current.writeText("")
        } else if (!current.exists()) {
            current.createNewFile()
        }
        if (!previous.exists()) {
            previous.createNewFile()
        }
    }

    private fun mergeCurrentIntoOldMediaStore() {
        val current = ensureMediaStoreEntry(LogPaths.CURRENT_LOG_FILE_NAME)
        val previous = ensureMediaStoreEntry(LogPaths.PREVIOUS_LOG_FILE_NAME)
        val currentSize = queryMediaStoreSize(current, LogPaths.CURRENT_LOG_FILE_NAME)
        if (currentSize > 0) {
            appendUriWithClamp(current, previous)
            truncateUri(current)
        }
    }

    private fun rolloverIfNeeded(current: File) {
        if (current.length() <= fileSizeLimitBytes) return
        val previous = previousLogFile()
        appendFileWithClamp(current, previous)
        current.writeText("")
    }

    private fun rolloverIfNeededMediaStore(current: Uri) {
        val currentSize = queryMediaStoreSize(current, LogPaths.CURRENT_LOG_FILE_NAME)
        if (currentSize <= fileSizeLimitBytes) return
        val previous = ensureMediaStoreEntry(LogPaths.PREVIOUS_LOG_FILE_NAME)
        appendUriWithClamp(current, previous)
        truncateUri(current)
    }

    private fun appendFileWithClamp(
        source: File,
        target: File
    ) {
        if (source.length() == 0L) return
        ensureWritableSpace()
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        target.appendBytes(source.readBytes())
        clampFile(target)
    }

    private fun clampFile(file: File) {
        val limit = fileSizeLimitBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (file.length() <= fileSizeLimitBytes) {
            return
        }
        val bytes = file.readBytes()
        if (bytes.size <= limit) {
            return
        }
        val sliceStart = (bytes.size - limit).coerceAtLeast(0)
        file.writeBytes(bytes.copyOfRange(sliceStart, bytes.size))
    }

    private fun appendLineToUri(
        uri: Uri,
        line: String
    ) {
        val outputStream =
            contentResolver.openOutputStream(uri, "wa")
                ?: throw IOException("openOutputStream failed for $uri")
        OutputStreamWriter(outputStream).use { writer ->
            writer.appendLine(line)
        }
    }

    private fun appendUriWithClamp(
        source: Uri,
        target: Uri
    ) {
        val bytes =
            contentResolver.openInputStream(source)?.use { it.readBytes() } ?: ByteArray(0)
        if (bytes.isEmpty()) return
        ensureWritableSpace()
        val outputStream =
            contentResolver.openOutputStream(target, "wa")
                ?: throw IOException("openOutputStream failed for $target")
        outputStream.use { it.write(bytes) }
        clampUri(target)
    }

    private fun clampUri(uri: Uri) {
        val limit = fileSizeLimitBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes =
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        if (bytes.size <= limit) {
            return
        }
        val sliceStart = (bytes.size - limit).coerceAtLeast(0)
        val outputStream =
            contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("openOutputStream failed for $uri")
        outputStream.use { it.write(bytes.copyOfRange(sliceStart, bytes.size)) }
    }

    private fun truncateUri(uri: Uri) {
        val outputStream =
            contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("openOutputStream failed for $uri")
        outputStream.use { it.write(ByteArray(0)) }
    }

    private fun ensureMediaStoreEntry(displayName: String): Uri {
        cachedUri(displayName)?.let { return it }
        val existing = queryMediaStoreEntry(displayName)
        if (existing != null) {
            cacheUri(displayName, existing.uri)
            return existing.uri
        }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, mediaRelativePath)
            }
        val collection = mediaCollectionUri()
        val uri =
            contentResolver.insert(collection, values)
                ?: throw IOException("insert log file failed: $displayName")
        cacheUri(displayName, uri)
        return uri
    }

    private fun buildMediaStoreMeta(
        displayName: String,
        uri: Uri
    ): LogFileMeta {
        val entry = queryMediaStoreEntry(displayName)
        val sizeBytes = entry?.sizeBytes ?: 0L
        val lastModified = entry?.lastModified ?: 0L
        val readable =
            runCatching {
                contentResolver.openInputStream(uri)?.close()
                true
            }.getOrDefault(false)
        return LogFileMeta(
            fileName = displayName,
            path = uri.toString(),
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            readable = readable,
        )
    }

    private fun scanMediaStoreLogFiles(): List<LogFileMeta> {
        return listOfNotNull(
            queryMediaStoreEntry(LogPaths.CURRENT_LOG_FILE_NAME)?.toLogFileMeta(),
            queryMediaStoreEntry(LogPaths.PREVIOUS_LOG_FILE_NAME)?.toLogFileMeta(),
        ).sortedBy { it.fileName }
    }

    private fun queryMediaStoreSize(
        uri: Uri,
        displayName: String
    ): Long {
        val entry = queryMediaStoreEntry(displayName)
        if (entry != null) {
            return entry.sizeBytes
        }
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            } else {
                0L
            }
        } ?: 0L
    }

    private fun queryMediaStoreEntry(displayName: String): MediaStoreEntry? {
        val collection = mediaCollectionUri()
        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(displayName)
        val expectedPath = normalizeRelativePath(mediaRelativePath)
        return contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            var best: MediaStoreEntry? = null
            var fallback: MediaStoreEntry? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val size = cursor.getLong(sizeIndex)
                val dateModified = cursor.getLong(dateIndex)
                val uri = ContentUris.withAppendedId(collection, id)
                val entry =
                    MediaStoreEntry(
                        uri = uri,
                        displayName = displayName,
                        sizeBytes = size,
                        lastModified = dateModified * 1000,
                    )
                val relativePath = cursor.getString(pathIndex)
                if (relativePath.isNullOrBlank()) {
                    if (fallback == null || entry.lastModified > fallback.lastModified) {
                        fallback = entry
                    }
                    continue
                }
                if (normalizeRelativePath(relativePath) != expectedPath) continue
                if (best == null || entry.lastModified > best.lastModified) {
                    best = entry
                }
            }
            best ?: fallback
        }
    }

    private fun mediaCollectionUri(): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        throw IllegalStateException("MediaStore requires Android Q+")
    }

    private fun shouldUseMediaStore(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && LogPaths.downloadRootOverride == null

    private fun cachedUri(displayName: String): Uri? =
        when (displayName) {
            LogPaths.CURRENT_LOG_FILE_NAME -> currentLogUri
            LogPaths.PREVIOUS_LOG_FILE_NAME -> previousLogUri
            else -> null
        }

    private fun cacheUri(
        displayName: String,
        uri: Uri
    ) {
        when (displayName) {
            LogPaths.CURRENT_LOG_FILE_NAME -> currentLogUri = uri
            LogPaths.PREVIOUS_LOG_FILE_NAME -> previousLogUri = uri
        }
    }

    private fun normalizeRelativePath(path: String): String =
        path.trimEnd('/').trimEnd('\\')

    private data class MediaStoreEntry(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long
    ) {
        fun toLogFileMeta(): LogFileMeta =
            LogFileMeta(
                fileName = displayName,
                path = uri.toString(),
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                readable = true,
            )
    }

    companion object {
        private const val MIN_FREE_SPACE_BYTES = 512 * 1024L
    }
}
