package com.xyoye.common_component.storage.credential

import com.xyoye.data_component.entity.MediaLibraryEntity

object MediaLibraryCredentialResolver {
    internal fun resolveForPlayback(library: MediaLibraryEntity): MediaLibraryEntity = resolve(library)

    fun resolve(library: MediaLibraryEntity): MediaLibraryEntity {
        val libraryId = library.id
        if (libraryId <= 0) return library

        val password =
            library.password?.takeIf { it.isNotBlank() }
                ?: MediaLibraryCredentialStore.readPassword(libraryId)
        if (password == library.password && library.remoteSecret == null) {
            return library
        }

        return library.copy(password = password, remoteSecret = null)
    }
}
