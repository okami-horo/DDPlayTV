package com.xyoye.common_component.storage.file.impl

import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.sardine.DavResource
import java.net.URI

/**
 * Created by xyoye on 2022/12/29
 */

class WebDavStorageFile(
    private val davResource: DavResource,
    storage: WebDavStorage
) : AbstractStorageFile(storage) {
    override fun getRealFile(): Any = davResource

    override fun filePath(): String = davResource.href.path

    override fun fileUrl(): String =
        storage.rootUri
            .buildUpon()
            .path(davResource.path)
            .toString()

    override fun isDirectory(): Boolean = davResource.isDirectory

    override fun fileName(): String = davResource.name

    override fun fileLength(): Long = davResource.contentLength

    override fun clone(): StorageFile =
        WebDavStorageFile(
            davResource,
            storage as WebDavStorage,
        ).also { it.playHistory = playHistory }

    override fun uniqueKey(): String {
        val baseUri = URI(storage.rootUri.toString()).resolve("/")
        val baseUrl = baseUri.toString().removeSuffix("/")
        return (baseUrl + "_" + davResource.href.toASCIIString()).toMd5String()
    }
}
