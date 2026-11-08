package com.okamihoro.ddplaytv.ui.smoke

import android.content.Intent
import android.view.KeyEvent
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialStore
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.StorageEditDialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCredentialRecoveryInstrumentedTest {
    @Test fun unavailableCredentialPreservesCiphertextAndEditorRecovers() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.setInTouchMode(false)
            val fixture =
                MediaLibraryEntity(
                    displayName = "credential recovery fixture",
                    url = "http://127.0.0.1:1/credential-recovery/",
                    mediaType = MediaType.WEBDAV_SERVER,
                    account = "fixture",
                    isAnonymous = false
                )
            MediaLibraryRepository.insert(fixture)
            val library = requireNotNull(MediaLibraryRepository.getByUrl(fixture.url, fixture.mediaType))
            val store = MMKV.mmkvWithID("dandanplay.media_library.credentials")
            val key = "pw_${library.id}"
            try {
                assertTrue(MediaLibraryCredentialStore.writePassword(library.id, "fixture-password"))
                val encrypted = requireNotNull(store.decodeString(key))
                val unavailable = "ddenc1:invalid:unavailable"
                store.encode(key, unavailable)
                val edit =
                    Intent(instrumentation.targetContext, StoragePlusActivity::class.java)
                        .putExtra("mediaType", library.mediaType)
                        .putExtra("editData", library)
                ActivityScenario.launch<StoragePlusActivity>(edit).use {
                    assertEquals(Lifecycle.State.DESTROYED, it.state)
                    assertEquals(unavailable, store.decodeString(key))
                }
                val browse =
                    Intent(instrumentation.targetContext, StorageFileActivity::class.java)
                        .putExtra("storageLibrary", library)
                ActivityScenario.launch<StorageFileActivity>(browse).use {
                    assertEquals(Lifecycle.State.DESTROYED, it.state)
                    assertEquals(unavailable, store.decodeString(key))
                }
                store.encode(key, encrypted)
                ActivityScenario.launch<StoragePlusActivity>(edit).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val dialog =
                            StoragePlusActivity::class.java
                                .getDeclaredField("storageEditDialog")
                                .apply { isAccessible = true }
                                .get(it) as StorageEditDialog<*>
                        assertEquals("fixture-password", dialog.findViewById<EditText>(R.id.password_et)!!.text.toString())
                        assertTrue(dialog.currentFocus?.isShown == true)
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    instrumentation.waitForIdleSync()
                }
                assertEquals("fixture-password", MediaLibraryCredentialStore.readPassword(library.id))
            } finally {
                MediaLibraryCredentialStore.clear(library.id)
                MediaLibraryRepository.delete(library.url, library.mediaType)
            }
        }
}
