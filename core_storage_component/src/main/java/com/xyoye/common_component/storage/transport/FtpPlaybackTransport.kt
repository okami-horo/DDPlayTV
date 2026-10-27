package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.file.helper.FtpPlayServer
import com.xyoye.common_component.storage.file.impl.FtpStorageFile
import com.xyoye.common_component.storage.impl.FtpStorage

internal class FtpPlaybackTransport(
    server: FtpPlayServer,
    file: FtpStorageFile,
    storage: FtpStorage
) : PlaybackTransportLease by ManagedPlaybackTransportLease(
        server.playUrl(),
        PlaybackTransportType.FTP,
        listOf({ server.release() }, { file.close() }, { storage.close() }),
        server.leaseId
    )
