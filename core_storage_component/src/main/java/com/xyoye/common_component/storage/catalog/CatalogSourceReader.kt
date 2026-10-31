package com.xyoye.common_component.storage.catalog

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.DocumentStorageFile
import com.xyoye.common_component.storage.file.impl.VideoStorageFile
import com.xyoye.common_component.storage.impl.DocumentFileStorage
import com.xyoye.common_component.storage.impl.VideoStorage
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class CatalogSourceReader(
    private val createStorage: (MediaLibraryEntity) -> Storage? = StorageFactory::createStorage
) {
    companion object {
        fun supports(type: MediaType): Boolean =
            type in
                setOf(
                    MediaType.LOCAL_STORAGE,
                    MediaType.EXTERNAL_STORAGE,
                    MediaType.SMB_SERVER,
                    MediaType.WEBDAV_SERVER
                )

        fun capture(file: StorageFile): PlaybackFileReference = PlaybackReferenceFactory.reference(file)
    }

    suspend fun file(
        library: MediaLibraryEntity,
        reference: PlaybackFileReference
    ): CatalogFileSnapshot =
        withStorage(library) { storage ->
            val file = storage.pathFile(reference.storagePath, false) ?: throw IOException("FILE_UNAVAILABLE")
            try {
                if (!file.isFile() || !file.canRead()) throw IOException("FILE_UNAVAILABLE")
                val snapshot = snapshot(file, parentPath(file))
                if (snapshot.reference.uniqueKey != reference.uniqueKey) throw IOException("FILE_CHANGED")
                snapshot
            } finally {
                file.close()
            }
        }

    suspend fun directory(
        library: MediaLibraryEntity,
        path: String
    ): CatalogDirectorySnapshot =
        withStorage(library) { storage ->
            // Read the existing local index; VideoStorage.listFiles can initiate a filesystem scan.
            if (storage is VideoStorage) {
                val rows = DatabaseProvider.instance.getVideoDao().getAll()
                val actualPath = path.ifBlank { library.url }
                if (actualPath == library.url) {
                    return@withStorage CatalogDirectorySnapshot(actualPath, emptyList(), rows.map { it.folderPath }.distinct(), true)
                }
                val files =
                    rows.filter { it.folderPath.trimEnd('/') == actualPath.trimEnd('/') }.map {
                        snapshot(VideoStorageFile(storage, it), actualPath)
                    }
                return@withStorage CatalogDirectorySnapshot(actualPath, files, emptyList(), true)
            }
            if (storage is DocumentFileStorage) return@withStorage documentDirectory(storage, path)
            val directory = if (path.isBlank()) storage.getRootFile() else storage.pathFile(path, true)
            if (directory == null || !directory.isDirectory()) throw IOException("DIRECTORY_UNAVAILABLE")
            try {
                val files = storage.openDirectory(directory, false)
                currentCoroutineContext().ensureActive()
                try {
                    if (files.any { !it.canRead() }) throw IOException("DIRECTORY_INCOMPLETE")
                    val actualPath = directory.storagePath()
                    CatalogDirectorySnapshot(
                        actualPath,
                        files.filter { it.isVideoFile() }.map { snapshot(it, actualPath) },
                        files.filter { it.isDirectory() }.map { it.storagePath() },
                        true
                    )
                } finally {
                    files.forEach { it.close() }
                }
            } finally {
                directory.close()
            }
        }

    private suspend fun documentDirectory(
        storage: DocumentFileStorage,
        path: String
    ): CatalogDirectorySnapshot {
        val context = BaseApplication.getAppContext()
        val tree = Uri.parse(storage.library.url)
        val uri = if (path.isBlank()) tree else Uri.parse(path)
        val id =
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                DocumentsContract.getTreeDocumentId(uri)
            }
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val document = DocumentFile.fromSingleUri(context, documentUri)
        if (document == null || !document.canRead() || !document.isDirectory) throw IOException("DIRECTORY_UNAVAILABLE")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor =
            context.contentResolver.query(children, columns, null, null, null)
                ?: throw IOException("DIRECTORY_INCOMPLETE")
        val files = mutableListOf<CatalogFileSnapshot>()
        val directories = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                val child = DocumentFile.fromSingleUri(context, childUri) ?: throw IOException("DIRECTORY_INCOMPLETE")
                if (!child.canRead()) throw IOException("DIRECTORY_INCOMPLETE")
                val file = DocumentStorageFile(child, storage)
                if (file.isDirectory()) {
                    directories += childUri.toString()
                } else if (file.isVideoFile()) {
                    files += snapshot(file, documentUri.toString())
                }
            }
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                it.extras.getString(DocumentsContract.EXTRA_ERROR) != null
            ) {
                throw IOException("DIRECTORY_INCOMPLETE")
            }
        }
        return CatalogDirectorySnapshot(documentUri.toString(), files, directories, true)
    }

    private suspend fun <T> withStorage(
        library: MediaLibraryEntity,
        block: suspend (Storage) -> T
    ): T =
        withContext(Dispatchers.IO) {
            require(supports(library.mediaType)) { "SOURCE_UNSUPPORTED" }
            val storage = createStorage(library.copy()) ?: throw IOException("SOURCE_UNAVAILABLE")
            (storage as? AbstractStorage)?.throwPlaybackErrors = true
            try {
                block(storage).also { currentCoroutineContext().ensureActive() }
            } finally {
                storage.close()
            }
        }

    // SAF document IDs are opaque. Only a directory listing can establish their parent.
    private fun parentPath(file: StorageFile): String =
        if (file is DocumentStorageFile) "" else file.storagePath().substringBeforeLast('/', "")

    private fun snapshot(
        file: StorageFile,
        parent: String
    ): CatalogFileSnapshot {
        val reference = capture(file)
        val local = if (file is VideoStorageFile) File(file.filePath()) else null
        if (local != null && !local.canRead()) throw IOException("FILE_UNAVAILABLE")
        val modified =
            when {
                local != null -> local.lastModified()
                file is DocumentStorageFile -> file.getFile<DocumentFile>()?.lastModified()
                else -> null
            }?.takeIf { it > 0 }
        return CatalogFileSnapshot(
            file.storage.library.id,
            reference,
            reference.protocolReference?.let { "id:$it" } ?: "path:${reference.storagePath}",
            file.fileName(),
            parent,
            (local?.length() ?: file.fileLength()).takeIf { it > 0 },
            modified
        )
    }
}
