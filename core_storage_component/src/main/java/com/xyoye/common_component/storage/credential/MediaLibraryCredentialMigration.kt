package com.xyoye.common_component.storage.credential

import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.entity.MediaLibraryEntity
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaLibraryCredentialMigration {
    private const val TAG = "MediaLibraryCredentialMigration"

    private val scheduled = AtomicBoolean(false)

    fun trySchedule() {
        if (!scheduled.compareAndSet(false, true)) return

        SupervisorScope.IO.launch {
            runCatching { migrateLegacyCredentials() }
                .onFailure { t ->
                    LogFacade.e(
                        module = LogModule.STORAGE,
                        tag = TAG,
                        message = "migrate legacy credentials failed",
                        throwable = t,
                    )
                }
        }
    }

    private suspend fun migrateLegacyCredentials() {
        val dao = DatabaseProvider.instance.getMediaLibraryDao()
        val libraries = dao.getAllSuspend()
        libraries.forEach { library ->
            migrateLibrary(dao = dao, library = library)
        }
    }

    private suspend fun migrateLibrary(
        dao: com.xyoye.common_component.database.dao.MediaLibraryDao,
        library: MediaLibraryEntity
    ) {
        val libraryId = library.id
        if (libraryId <= 0) return

        val legacyPassword = library.password?.takeIf { it.isNotBlank() }
        if (legacyPassword != null) {
            val stored = MediaLibraryCredentialStore.readPassword(libraryId)
            if (!stored.isNullOrBlank()) {
                dao.clearPassword(libraryId)
            } else {
                val saved = MediaLibraryCredentialStore.writePassword(libraryId, legacyPassword)
                if (saved) {
                    dao.clearPassword(libraryId)
                } else {
                    LogFacade.w(
                        module = LogModule.STORAGE,
                        tag = TAG,
                        message = "credential persistence failed, retain legacy password in db libraryId=$libraryId",
                    )
                }
            }
        }

        if (library.remoteSecret != null) dao.clearRemoteSecret(libraryId)
        MediaLibraryCredentialStore.clearRetiredRemoteSecret(libraryId)
    }
}
