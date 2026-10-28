package com.xyoye.common_component.storage

import com.xyoye.common_component.storage.credential.MediaLibraryCredentialResolver
import com.xyoye.common_component.storage.impl.AlistStorage
import com.xyoye.common_component.storage.impl.BaiduPanStorage
import com.xyoye.common_component.storage.impl.Cloud115Storage
import com.xyoye.common_component.storage.impl.DocumentFileStorage
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.impl.Open115Storage
import com.xyoye.common_component.storage.impl.RemoteStorage
import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.storage.impl.TorrentStorage
import com.xyoye.common_component.storage.impl.VideoStorage
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType

/**
 * Created by xyoye on 2022/12/29
 */

object StorageFactory {
    fun createStorage(library: MediaLibraryEntity): Storage? =
        when (library.mediaType) {
            MediaType.EXTERNAL_STORAGE -> DocumentFileStorage(library)
            MediaType.WEBDAV_SERVER -> WebDavStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.SMB_SERVER -> SmbStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.FTP_SERVER -> FtpStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.LOCAL_STORAGE -> VideoStorage(library)
            MediaType.REMOTE_STORAGE -> RemoteStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.MAGNET_LINK -> TorrentStorage(library)
            MediaType.STREAM_LINK -> LinkStorage(library)
            MediaType.OTHER_STORAGE -> LinkStorage(library)
            MediaType.SCREEN_CAST -> ScreencastStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.ALSIT_STORAGE -> AlistStorage(MediaLibraryCredentialResolver.resolve(library))
            MediaType.BAIDU_PAN_STORAGE -> BaiduPanStorage(library)
            MediaType.OPEN_115_STORAGE -> Open115Storage(library)
            MediaType.CLOUD_115_STORAGE -> Cloud115Storage(library)
            else -> null
        }
}
