package com.xyoye.common_component.storage.file.impl

import android.net.Uri
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.FtpStorage
import org.apache.commons.net.ftp.FTPFile

/**
 * Created by XYJ on 2023/1/16.
 */

class FtpStorageFile(
    storage: FtpStorage,
    val parentPath: String,
    private val ftpFile: FTPFile
) : AbstractStorageFile(storage) {
    override fun getRealFile(): Any = ftpFile

    override fun filePath(): String =
        Uri
            .parse(parentPath)
            .buildUpon()
            .appendEncodedPath(fileName())
            .build()
            .toString()

    override fun fileUrl(): String {
        val url = "ftp://${storage.library.ftpAddress}:${storage.library.port}/"
        return Uri
            .parse(url)
            .buildUpon()
            .path(filePath())
            .build()
            .toString()
    }

    override fun isDirectory(): Boolean = ftpFile.isDirectory

    override fun fileName(): String = ftpFile.name

    override fun fileLength(): Long = ftpFile.size

    override fun clone(): StorageFile =
        FtpStorageFile(
            storage as FtpStorage,
            parentPath,
            ftpFile,
        ).also {
            it.playHistory = playHistory
        }
}
