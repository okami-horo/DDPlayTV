package com.xyoye.storage_component.ui.activities.storage_plus

import androidx.room.Room
import com.xyoye.common_component.crypto.KeystoreAesGcmCrypto
import com.xyoye.common_component.database.DatabaseAccessProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [StorageCredentialSaveTest.UnavailableCrypto::class])
class StorageCredentialSaveTest {
    @Test fun failedCredentialSaveDoesNotClearLegacyDatabasePasswordOrPublishSuccess() =
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DatabaseInfo::class.java).build()
            DatabaseProvider.overrideProviderForTest(DatabaseAccessProvider { database })
            Class
                .forName("com.xyoye.common_component.storage.credential.MediaLibraryCredentialMigration")
                .getDeclaredField("scheduled")
                .apply { isAccessible = true }
                .get(null)
                .let { (it as AtomicBoolean).set(true) }
            try {
                val original = MediaLibraryEntity(42, "old", "http://fixture.test/", MediaType.WEBDAV_SERVER, password = "legacy")
                database.getMediaLibraryDao().insert(original)
                val model = StoragePlusViewModel()
                model.addStorage(original, original.copy(displayName = "new", password = "new"), showToast = false).join()
                assertEquals(original, database.getMediaLibraryDao().getById(42))
                assertEquals(null, model.storageSavedLiveData.value)
            } finally {
                DatabaseProvider.overrideProviderForTest(null)
                database.close()
                Dispatchers.resetMain()
            }
        }

    @Implements(value = KeystoreAesGcmCrypto::class, isInAndroidSdk = false)
    class UnavailableCrypto {
        @Implementation fun encrypt(value: String): String? = null
    }
}
