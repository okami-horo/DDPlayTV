package com.okamihoro.ddplaytv.ui.tv

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.bean.CacheBean
import com.xyoye.data_component.enums.CacheType
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import com.xyoye.user_component.ui.activities.cache_manager.CacheManagerActivity
import com.xyoye.user_component.ui.activities.commonly_folder.CommonlyFolderActivity
import com.xyoye.user_component.ui.activities.license.LicenseActivity
import com.xyoye.user_component.ui.activities.switch_themes.SwitchThemesActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.core_ui_component.R as CoreR
import com.xyoye.user_component.R as UserR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ContainerHierarchyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun cacheConfirmationDelayCancellationAndStatisticsRemainUsable() {
        val previous = AppConfig.getCachePath()
        val directory = File(context.getExternalFilesDir("a03-fixtures"), "cache").apply { mkdirs() }
        AppConfig.putCachePath(directory.path)
        val screenshotFile = File(PathHelper.getScreenShotDirectory(), "a03-only.png").apply { writeBytes(ByteArray(4096)) }
        try {
            ActivityScenario.launch(CacheManagerActivity::class.java).use { scenario ->
                await { scenario.onActivity { assertTrue(it.hasWindowFocus()) } }
                scenario.onActivity { it.findViewById<View>(UserR.id.app_cache_ll).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertTrue(active().findViewById<TextView>(CoreR.id.content_tv).text.contains("系统缓存")) }
                screenshot("cache-system-confirm")
                key(KeyEvent.KEYCODE_BACK)
                scenario.onActivity { assertEquals(UserR.id.app_cache_ll, it.currentFocus?.id) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await {
                    scenario.onActivity { assertTrue(it.findViewById<View>(UserR.id.rv_cache).hasFocus()) }
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertFalse(active().findViewById<View>(CoreR.id.positive_bt).isEnabled) }
                screenshot("cache-delayed-confirm")
                key(KeyEvent.KEYCODE_BACK)
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                await {
                    scenario.onActivity {
                        val list = it.findViewById<DpadRecyclerView>(UserR.id.rv_cache)
                        val row = requireNotNull(list.findContainingItemView(requireNotNull(it.currentFocus)))
                        assertEquals(CacheType.SCREEN_SHOT_CACHE.ordinal, list.getChildAdapterPosition(row))
                    }
                }
                screenshot("cache-screenshot-row")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertTrue(active().findViewById<TextView>(CoreR.id.content_tv).text.contains("截图")) }
                focusActive(CoreR.id.positive_bt)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await {
                    assertFalse(screenshotFile.exists())
                    scenario.onActivity {
                        val items = (it.findViewById<DpadRecyclerView>(UserR.id.rv_cache).adapter as BaseAdapter).items
                        assertEquals(
                            0L,
                            items
                                .filterIsInstance<CacheBean>()
                                .first { bean ->
                                    bean.cacheType == CacheType.SCREEN_SHOT_CACHE
                                }.totalSize
                        )
                    }
                }
                screenshot("cache-cleared")
            }
        } finally {
            AppConfig.putCachePath(previous ?: "")
            screenshotFile.delete()
            directory.deleteRecursively()
        }
    }

    @Test fun directorySelectionSwitchAndLongPressClearPersist() {
        val first = AppConfig.getCommonlyFolder1()
        val second = AppConfig.getCommonlyFolder2()
        val last = AppConfig.getLastOpenFolder()
        val enabled = AppConfig.isLastOpenFolderEnable()
        AppConfig.putCommonlyFolder1("")
        AppConfig.putCommonlyFolder2("/storage/emulated/0/Movies/很长的目录名称/第二层目录/本地验证目录")
        try {
            ActivityScenario.launch(CommonlyFolderActivity::class.java).use { scenario ->
                await { scenario.onActivity { assertTrue(it.hasWindowFocus()) } }
                scenario.onActivity { it.findViewById<View>(UserR.id.commonly_folder_1_ll).requestFocus() }
                scenario.onActivity {
                    for (id in listOf(UserR.id.commonly_folder_1_tv, UserR.id.commonly_folder_2_tv)) {
                        val path = it.findViewById<TextView>(id)
                        assertTrue(
                            "Directory path must fit its text layout at the current font scale",
                            path.height >= path.layout.height + path.compoundPaddingTop + path.compoundPaddingBottom
                        )
                    }
                }
                screenshot("folders-empty-long")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertTrue(active().findViewById<View>(CoreR.id.file_rv).isShown) }
                screenshot("folders-picker")
                focusActive(CoreR.id.positive_bt)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertEquals("/storage/emulated/0", AppConfig.getCommonlyFolder1()) }
                scenario.recreate()
                await {
                    scenario.onActivity {
                        assertTrue(it.findViewById<TextView>(UserR.id.commonly_folder_1_tv).text.contains("/storage/emulated/0"))
                        assertFalse(it.findViewById<View>(UserR.id.current_open_folder_ll).isFocusable)
                        it.findViewById<View>(UserR.id.last_open_folder_sw).requestFocus()
                    }
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(!enabled, AppConfig.isLastOpenFolderEnable())
                scenario.recreate()
                await {
                    scenario.onActivity { assertEquals(!enabled, it.findViewById<SwitchCompat>(UserR.id.last_open_folder_sw).isChecked) }
                }
                scenario.onActivity { it.findViewById<View>(UserR.id.commonly_folder_1_ll).requestFocus() }
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                SystemClock.sleep(800)
                instrumentation.sendKeySync(
                    KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS)
                )
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                await { assertTrue(active().findViewById<TextView>(CoreR.id.content_tv).text.contains("删除常用文件夹1")) }
                screenshot("folders-clear-confirm")
                focusActive(CoreR.id.positive_bt)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals("", AppConfig.getCommonlyFolder1())
                scenario.recreate()
                await { scenario.onActivity { assertTrue(it.findViewById<TextView>(UserR.id.commonly_folder_1_tv).text.contains("未设置")) } }
            }
        } finally {
            AppConfig.putCommonlyFolder1(first ?: "")
            AppConfig.putCommonlyFolder2(second ?: "")
            AppConfig.putLastOpenFolder(last ?: "")
            AppConfig.putLastOpenFolderEnable(enabled)
        }
    }

    @Test fun themeSavePersistsAndRestartCancellationKeepsTheSavedChoice() {
        val previous = AppConfig.getDarkMode()
        val previousRuntime = AppCompatDelegate.getDefaultNightMode()
        try {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
            AppConfig.putDarkMode(AppCompatDelegate.MODE_NIGHT_YES)
            ActivityScenario.launch(SwitchThemesActivity::class.java).use { scenario ->
                await { scenario.onActivity { assertTrue(it.hasWindowFocus()) } }
                scenario.onActivity { it.findViewById<View>(UserR.id.follow_system_sw).requestFocus() }
                screenshot("themes-manual")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppConfig.getDarkMode())
                screenshot("themes-system")
                focusActive(UserR.id.item_save_theme)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppConfig.getDarkMode()) }
            }
            ActivityScenario.launch(SwitchThemesActivity::class.java).use { scenario ->
                await { scenario.onActivity { assertTrue(it.findViewById<SwitchCompat>(UserR.id.follow_system_sw).isChecked) } }
                scenario.onActivity { it.findViewById<View>(UserR.id.follow_system_sw).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                focusActive(UserR.id.item_save_theme)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await { assertTrue(active().findViewById<TextView>(CoreR.id.content_tv).text.contains("重启")) }
                screenshot("themes-restart-confirm")
                key(KeyEvent.KEYCODE_BACK)
                assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppConfig.getDarkMode())
            }
        } finally {
            AppConfig.putDarkMode(previous)
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousRuntime) }
        }
    }

    @Test fun longLicenseScrollsBeforeFocusLeavesAndAllEntriesRemainComplete() {
        ActivityScenario.launch(LicenseActivity::class.java).use { scenario ->
            lateinit var list: DpadRecyclerView
            var index = -1
            await {
                scenario.onActivity {
                    list = it.findViewById(UserR.id.license_rv)
                    val entries = (list.adapter as BaseAdapter).items.filterIsInstance<Pair<String, String>>()
                    assertEquals(context.assets.list("license")!!.size, entries.size)
                    index = entries.indexOfFirst { entry -> entry.first.startsWith("Plezy-mpv") }
                    assertTrue(index >= 0)
                    assertTrue(entries[index].second.contains("GNU GENERAL PUBLIC LICENSE"))
                    assertTrue(entries[index].second.length > 30000)
                }
            }
            screenshot("license-first")
            scenario.onActivity { list.setSelectedPosition(index) }
            await {
                scenario.onActivity {
                    assertEquals(index, list.getSelectedPosition())
                    assertEquals(0, list.scrollState)
                }
            }
            var initialTop = 0
            scenario.onActivity {
                val row = requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
                list.scrollBy(0, row.top - list.paddingTop)
                initialTop = row.top
            }
            screenshot("license-long-start")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await {
                scenario.onActivity {
                    assertEquals("Read the current long license before moving to the next component", index, list.getSelectedPosition())
                    assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView.top < initialTop)
                }
            }
            screenshot("license-long-scrolled")
            repeat(180) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            await { scenario.onActivity { assertEquals(requireNotNull(list.adapter).itemCount - 1, list.getSelectedPosition()) } }
            screenshot("license-last")
            key(KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun lightThemeToolsKeepContentAndVisibleActions() {
        val previous = AppCompatDelegate.getDefaultNightMode()
        try {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
            for (type in listOf(
                CacheManagerActivity::class.java,
                CommonlyFolderActivity::class.java,
                SwitchThemesActivity::class.java,
                LicenseActivity::class.java,
                AboutUsActivity::class.java
            )) {
                ActivityScenario.launch(type).use { scenario ->
                    await {
                        scenario.onActivity {
                            assertTrue(it.hasWindowFocus())
                            assertTrue(it.currentFocus?.isShown == true)
                        }
                    }
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    SystemClock.sleep(500)
                    screenshot("light-${type.simpleName}")
                    key(KeyEvent.KEYCODE_BACK)
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previous) }
        }
    }

    @Test fun aboutActionsScrollFullyIntoViewWithRemote() {
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            await { scenario.onActivity { assertTrue(it.hasWindowFocus()) } }
            for ((id, name) in listOf(
                UserR.id.official_address_tv to "about-first-link",
                UserR.id.source_address_tv to "about-second-link"
            )) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await { scenario.onActivity { assertEquals(id, it.currentFocus?.id) } }
                SystemClock.sleep(500)
                screenshot(name)
                await {
                    scenario.onActivity {
                        val action = it.findViewById<View>(id)
                        val visible = Rect()
                        assertTrue(action.getGlobalVisibleRect(visible))
                        assertTrue("The focused website action must fit the scroll viewport", visible.height() >= action.height - 2)
                    }
                }
            }
            key(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun active(): View = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }

    private fun focusActive(id: Int) {
        await { assertTrue(active().findViewById<View>(id).requestFocus()) }
    }

    private fun await(check: () -> Unit) {
        val end = SystemClock.uptimeMillis() + 8000
        var last: Throwable? = null
        do {
            instrumentation.waitForIdleSync()
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    check()
                } catch (caught: Throwable) {
                    failure = caught
                }
            }
            if (failure == null) return
            last = failure
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < end)
        throw requireNotNull(last)
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("a03-verification"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
