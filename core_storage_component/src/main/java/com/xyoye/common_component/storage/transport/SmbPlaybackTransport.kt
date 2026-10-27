package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.file.helper.SmbPlayServer
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.SmbStorage

internal class SmbPlaybackTransport(
    server: SmbPlayServer,
    file: SmbStorageFile,
    storage: SmbStorage
) : PlaybackTransportLease by ManagedPlaybackTransportLease(
        server.playUrl(),
        PlaybackTransportType.SMB,
        listOf({ server.release() }, { file.close() }, { storage.close() }),
        server.leaseId
    )
