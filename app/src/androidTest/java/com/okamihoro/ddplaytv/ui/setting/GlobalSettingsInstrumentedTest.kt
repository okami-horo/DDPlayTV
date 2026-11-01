package com.okamihoro.ddplaytv.ui.setting

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.Observer
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.StorageEditDialog
import com.xyoye.user_component.ui.activities.setting_app.SettingAppActivity
import com.xyoye.user_component.ui.fragment.AppSettingFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GlobalSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun useRemoteInputMode() {
        instrumentation.setInTouchMode(false)
    }

    @Test
    fun sourceEditorsKeepVisibleDefaultFocusAndReturnByRemote() {
        for (mediaType in listOf(
            MediaType.EXTERNAL_STORAGE,
            MediaType.FTP_SERVER,
            MediaType.WEBDAV_SERVER,
            MediaType.SMB_SERVER,
            MediaType.ALSIT_STORAGE,
            MediaType.BAIDU_PAN_STORAGE,
            MediaType.OPEN_115_STORAGE,
            MediaType.CLOUD_115_STORAGE
        )) {
            withSourceEditor(mediaType) { _, dialog ->
                instrumentation.runOnMainSync {
                    assertTrue("$mediaType must start on a visible control", dialog.currentFocus?.isShown == true)
                }
                closeByRemote(dialog)
            }
        }
    }

    @Test
    fun webDavConnectionOptionsRemainReachableAcrossRemovedKernelRow() {
        withSourceEditor(MediaType.WEBDAV_SERVER) { scenario, dialog ->
            scenario.onActivity { assertTrue(dialog.findViewById<View>(R.id.account_tv)!!.requestFocus()) }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocused(dialog, R.id.strict_parse_tv)
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertFocused(dialog, R.id.normal_parse_tv)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.runOnMainSync {
                assertTrue(dialog.findViewById<View>(R.id.normal_parse_tv)!!.isSelected)
                assertFalse(dialog.findViewById<View>(R.id.strict_parse_tv)!!.isSelected)
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocused(dialog, R.id.insecure_tls_tv)
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            assertFocused(dialog, R.id.strict_tls_tv)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocused(dialog, R.id.server_test_connect_tv)
            closeByRemote(dialog)
        }
    }

    @Test
    fun externalDirectoryActionRemainsBelowPathAndReachable() {
        withSourceEditor(MediaType.EXTERNAL_STORAGE) { scenario, dialog ->
            scenario.onActivity {
                val path = dialog.findViewById<View>(R.id.path_tv)!!
                val selectRoot = dialog.findViewById<View>(R.id.select_root_tv)!!
                assertTrue(selectRoot.top >= path.bottom)
                assertTrue(selectRoot.isShown && selectRoot.isEnabled)
                assertTrue(dialog.findViewById<View>(R.id.display_name_et)!!.requestFocus())
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocused(dialog, R.id.select_root_tv)
            send(KeyEvent.KEYCODE_DPAD_UP)
            assertFocused(dialog, R.id.display_name_et)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocused(dialog, R.id.select_root_tv)
            closeByRemote(dialog)
        }
    }

    @Test
    fun retiredPcSourceIsHiddenWhileItsSavedRowIsPreserved() {
        val library =
            MediaLibraryEntity(
                displayName = "Retired PC fixture",
                url = "http://retired-pc-fixture.test",
                mediaType = MediaType.REMOTE_STORAGE
            )
        val dao = DatabaseProvider.instance.getMediaLibraryDao()
        val live = dao.getAll()
        val observed = CountDownLatch(1)
        var displayed = emptyList<MediaLibraryEntity>()
        val observer =
            Observer<MutableList<MediaLibraryEntity>> {
                displayed = it
                observed.countDown()
            }
        try {
            runBlocking { MediaLibraryRepository.insert(library) }
            instrumentation.runOnMainSync { live.observeForever(observer) }
            assertTrue(observed.await(5, TimeUnit.SECONDS))
            assertTrue(displayed.none { it.mediaType == MediaType.REMOTE_STORAGE })
            assertTrue(runBlocking { dao.getByUrl(library.url, library.mediaType) } != null)
        } finally {
            instrumentation.runOnMainSync { live.removeObserver(observer) }
            runBlocking { MediaLibraryRepository.delete(library.url, library.mediaType) }
        }
    }

    @Test
    fun retainedSettingsPersistByDpadAndPcGroupingIsAbsent() {
        val original = AppConfig.isShowHiddenFile()
        AppConfig.putShowHiddenFile(false)
        try {
            ActivityScenario.launch(SettingAppActivity::class.java).use { scenario ->
                var position = -1
                scenario.onActivity {
                    val fragment = appSettings(it)
                    assertNull(fragment.findPreference<SwitchPreference>("remote_anime_grouping"))
                    val list = fragment.listView as DpadRecyclerView
                    position = (list.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition("hide_file")
                    assertTrue(position >= 0)
                    list.setSelectedPosition(position)
                    list.requestFocus()
                }
                await(scenario) {
                    val list = appSettings(it).listView
                    it.hasWindowFocus() &&
                        it.currentFocus?.let { focus -> list.findContainingViewHolder(focus)?.bindingAdapterPosition == position } == true
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(AppConfig.isShowHiddenFile())
                scenario.recreate()
                scenario.onActivity {
                    assertTrue(appSettings(it).findPreference<SwitchPreference>("hide_file")!!.isChecked)
                    assertNull(appSettings(it).findPreference<SwitchPreference>("remote_anime_grouping"))
                }
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io
                    .File(
                        instrumentation.targetContext.getExternalFilesDir("all-source-verification"),
                        "settings.png"
                    ).outputStream()
                    .use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                bitmap.recycle()
                send(KeyEvent.KEYCODE_BACK)
            }
            ActivityScenario.launch(SettingAppActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(appSettings(it).findPreference<SwitchPreference>("hide_file")!!.isChecked) }
            }
        } finally {
            AppConfig.putShowHiddenFile(original)
        }
    }

    private fun withSourceEditor(
        mediaType: MediaType,
        block: (ActivityScenario<StoragePlusActivity>, StorageEditDialog<*>) -> Unit
    ) {
        // Empty connection data prevents these navigation fixtures from saving a source.
        val intent =
            Intent(instrumentation.targetContext, StoragePlusActivity::class.java)
                .putExtra("mediaType", mediaType)
        ActivityScenario.launch<StoragePlusActivity>(intent).use { scenario ->
            lateinit var dialog: StorageEditDialog<*>
            scenario.onActivity {
                dialog =
                    StoragePlusActivity::class.java
                        .getDeclaredField("storageEditDialog")
                        .apply { isAccessible = true }
                        .get(it) as StorageEditDialog<*>
            }
            await(scenario) {
                dialog.window?.decorView?.hasWindowFocus() == true && dialog.currentFocus?.isShown == true
            }
            block(scenario, dialog)
        }
    }

    private fun appSettings(activity: SettingAppActivity): AppSettingFragment =
        activity.supportFragmentManager.fragments
            .filterIsInstance<AppSettingFragment>()
            .single()

    private fun assertFocused(
        dialog: StorageEditDialog<*>,
        id: Int
    ) {
        instrumentation.runOnMainSync {
            assertEquals(id, dialog.currentFocus?.id)
            assertTrue(dialog.currentFocus!!.isShown)
        }
    }

    private fun closeByRemote(dialog: StorageEditDialog<*>) {
        send(KeyEvent.KEYCODE_BACK)
        instrumentation.runOnMainSync { assertFalse(dialog.isShowing) }
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun <T : android.app.Activity> await(
        scenario: ActivityScenario<T>,
        condition: (T) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { ready = condition(it) }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue("Expected window and a visible remote focus target", ready)
    }
}
