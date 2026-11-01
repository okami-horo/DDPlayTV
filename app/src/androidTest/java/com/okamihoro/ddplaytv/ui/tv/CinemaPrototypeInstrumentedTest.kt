package com.okamihoro.ddplaytv.ui.tv

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Date

/** Explicit local fixtures for comparison with document/prototypes/tv-ui/index.html. */
@RunWith(AndroidJUnit4::class)
class CinemaPrototypeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance
    private val directory get() = requireNotNull(context.getExternalFilesDir("cinema-reference"))
    private val library = MediaLibraryEntity(991001, "原型验收来源", "cinema-fixture", MediaType.LOCAL_STORAGE)

    @Before fun seed() =
        runBlocking {
            instrumentation.setInTouchMode(false)
            db.getMediaLibraryDao().insert(library)
            db.getMetadataDao().putSource(CatalogSourceState(library.id))
            val now = System.currentTimeMillis() + 10000
            val titles = listOf("深海来信", "山野之间", "落日列车", "星际漫游")
            titles.forEachIndexed { index, title ->
                val art = File(directory, "art$index.png")
                assertTrue("Push reference artwork to the task emulator", art.isFile)
                val video = File(directory, "$title.mp4")
                File("/sdcard/Download/ddplaytv-tmdb-fixture.mp4").copyTo(video, overwrite = true)
                val assetId =
                    db.getMetadataDao().putAsset(
                        MediaAsset(
                            libraryId = library.id,
                            contentGeneration = 1,
                            locatorKey = "path:${video.path}",
                            uniqueKey = video.path.toMd5String(),
                            storagePath = video.path,
                            fileName = video.name,
                            parentPath = directory.path,
                            discoveredAt = now - index
                        )
                    )
                db.getMetadataDao().putWork(
                    MetadataWork(
                        "tv",
                        991001L + index,
                        title,
                        "Cinema fixture $index",
                        "有些故事，值得慢慢看。\n从上次停下的地方，再次出发。",
                        "2026-10-31",
                        "测试夹具",
                        0.0,
                        art.toURI().toString(),
                        art.toURI().toString(),
                        "zh-CN",
                        "",
                        now - index,
                        1
                    )
                )
                db.getMetadataDao().putBinding(MediaBinding(assetId, "MATCHED", "tv", 991001L + index, "MANUAL", true))
                db.getPlayHistoryDao().insert(
                    PlayHistoryEntity(
                        videoName = video.name,
                        url = video.path,
                        mediaType = library.mediaType,
                        videoPosition = listOf(62000L, 28000L, 10000L, 0L)[index],
                        videoDuration = 100000,
                        playTime = Date(now - index * 1000),
                        uniqueKey = video.path.toMd5String(),
                        storagePath = video.path,
                        storageId = library.id
                    )
                )
            }
        }

    @After fun cleanup(): Unit =
        runBlocking {
            db.getPlayHistoryDao().deleteByStorageId(library.id)
            MediaLibraryRepository.delete(library.url, library.mediaType)
            db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE tmdbId BETWEEN 991001 AND 991004")
            directory.listFiles()?.filter { it.extension == "mp4" }?.forEach { it.delete() }
        }

    @Test fun referencePagesSearchAndSettingsAreReachableWithRemote() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var home: TvMainActivity
            scenario.onActivity { home = it }
            await("Home artwork and history") {
                home.hasWindowFocus() &&
                    home.findViewById<ImageView>(com.xyoye.local_component.R.id.backdrop)?.drawable is BitmapDrawable &&
                    (
                        home
                            .findViewById<DpadRecyclerView>(
                                com.xyoye.local_component.R.id.continue_watching
                            )?.adapter as? com.xyoye.local_component.ui.metadata.MetadataCardAdapter
                    )?.currentList
                        ?.take(4)
                        ?.map { it.title } == listOf("深海来信", "山野之间", "落日列车", "星际漫游")
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                assertEquals(com.xyoye.local_component.R.id.hero_play, it.currentFocus?.id)
                val position = IntArray(2)
                it.findViewById<View>(com.okamihoro.ddplaytv.R.id.fragment_container).getLocationOnScreen(position)
                assertEquals("Backdrop begins at the top of the fullscreen window", 0, position[1])
            }
            screenshot("home")
            send(KeyEvent.KEYCODE_BACK)
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Library artwork") {
                home.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.library_cards)?.adapter?.itemCount ==
                    4
            }
            screenshot("library")
            repeat(3) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            val recent = home.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.library_cards)
            await("Recent additions receive remote focus") { recent.hasFocus() }
            assertEquals(0, recent.findContainingViewHolder(requireNotNull(home.currentFocus))?.bindingAdapterPosition)
            screenshot("library-first-card")
            repeat(2) { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals(2, recent.findContainingViewHolder(requireNotNull(home.currentFocus))?.bindingAdapterPosition)
            screenshot("library-last-card")
            repeat(2) { send(KeyEvent.KEYCODE_DPAD_LEFT) }
            send(KeyEvent.KEYCODE_BACK)
            await("Back returns to library navigation") {
                home.findViewById<View>(com.okamihoro.ddplaytv.R.id.tv_nav_rv).hasFocus()
            }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Centered search") {
                (
                    home.supportFragmentManager.findFragmentByTag(
                        "cinema_search"
                    ) as? DialogFragment
                )?.dialog?.window?.decorView?.hasWindowFocus() ==
                    true
            }
            val search = home.supportFragmentManager.findFragmentByTag("cinema_search") as DialogFragment
            await("All search results") {
                search
                    .requireView()
                    .findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.search_results)
                    .adapter
                    ?.itemCount ==
                    4
            }
            screenshot("search")
            instrumentation.runOnMainSync {
                search
                    .requireView()
                    .findViewById<EditText>(
                        com.xyoye.local_component.R.id.search_query
                    ).setText("山野")
            }
            await("Real library filtering") {
                search
                    .requireView()
                    .findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.search_results)
                    .adapter
                    ?.itemCount ==
                    1
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(search.requireView().findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.search_results).hasFocus())
            send(KeyEvent.KEYCODE_BACK)
            await("Search returns to its caller") { home.hasWindowFocus() && !search.isAdded }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            val names = listOf("playback", "captions", "media", "about")
            names.forEachIndexed { index, name ->
                if (index > 0) send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Settings content $name") {
                    home.findViewById<View>(com.xyoye.user_component.R.id.settings_content)?.hasFocus() ==
                        true
                }
                screenshot("settings-$name")
                if (index == 0) {
                    val previous =
                        com.xyoye.common_component.config.PlayerConfig
                            .isAutoPlayNext()
                    try {
                        send(KeyEvent.KEYCODE_DPAD_DOWN)
                        assertEquals("顺序播放", home.currentFocus?.findViewById<android.widget.TextView>(android.R.id.title)?.text)
                        send(KeyEvent.KEYCODE_DPAD_CENTER)
                        assertEquals(
                            !previous,
                            com.xyoye.common_component.config.PlayerConfig
                                .isAutoPlayNext()
                        )
                        send(KeyEvent.KEYCODE_DPAD_CENTER)
                        assertEquals(
                            previous,
                            com.xyoye.common_component.config.PlayerConfig
                                .isAutoPlayNext()
                        )
                    } finally {
                        com.xyoye.common_component.config.PlayerConfig
                            .putAutoPlayNext(previous)
                    }
                }
                send(KeyEvent.KEYCODE_BACK)
                assertTrue(home.findViewById<View>(com.xyoye.user_component.R.id.categories).hasFocus())
            }
            send(KeyEvent.KEYCODE_BACK)
            assertTrue(home.findViewById<View>(com.okamihoro.ddplaytv.R.id.tv_nav_rv).hasFocus())
        }
    }

    @Test fun longHeroTitleStaysInItsColumnAndLeavesActionsReadable() =
        longHome { page ->
            screenshot("long-home")
            instrumentation.runOnMainSync {
                val title = page.findViewById<TextView>(com.xyoye.local_component.R.id.hero_title)
                val backdrop = page.findViewById<View>(com.xyoye.local_component.R.id.backdrop)
                assertTrue("Long title must stay in the left content column", title.width <= backdrop.width * 0.52f)
                assertTrue("Hero title uses at most two readable lines", title.lineCount in 1..2)
                assertTrue(
                    "Fallback fonts must preserve the prototype's compact title spacing",
                    title.layout.getLineBottom(0) - title.layout.getLineTop(0) <= title.textSize * 1.3f
                )
                val views =
                    listOf(
                        title,
                        page.findViewById(com.xyoye.local_component.R.id.hero_meta),
                        page.findViewById(com.xyoye.local_component.R.id.hero_overview),
                        page.findViewById(com.xyoye.local_component.R.id.hero_play),
                        page.findViewById<View>(com.xyoye.local_component.R.id.continue_title)
                    )
                views.zipWithNext { before, after ->
                    assertTrue("Hero copy and actions must not overlap", screenBounds(before).bottom <= screenBounds(after).top)
                }
                assertTrue(title.layout.height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
            }
        }

    @Test fun homeCardsShowOneTitleAndKeepRemoteFocusVisible() =
        longHome { page ->
            val list = page.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.continue_watching)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("History receives focus") { list.hasFocus() }
            repeat(4) { index ->
                if (index > 0) send(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Selected home card $index") { list.getSelectedPosition() == index }
                instrumentation.runOnMainSync {
                    val card = requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
                    val title = card.findViewById<TextView>(com.xyoye.local_component.R.id.card_title)

                    fun titleCount(view: View): Int {
                        var count = if (view is TextView && view.isShown && view.text == title.text) 1 else 0
                        if (view is ViewGroup) for (i in 0 until view.childCount) count += titleCount(view.getChildAt(i))
                        return count
                    }
                    assertEquals("Display the work/file title once per card", 1, titleCount(card))
                    for (id in listOf(com.xyoye.local_component.R.id.card_title, com.xyoye.local_component.R.id.card_subtitle)) {
                        val text = card.findViewById<TextView>(id)
                        assertTrue(
                            "Card text must fit at the current font scale: ${text.text}, layout=${text.layout.height}, height=${text.height}",
                            text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                        )
                        assertTrue(text.bottom <= card.height - card.paddingBottom)
                    }
                    val visible = Rect()
                    assertTrue(card.getGlobalVisibleRect(visible))
                    assertEquals("The entire focused card must be visible", screenBounds(card), visible)
                }
            }
            screenshot("single-title-cards")
            send(KeyEvent.KEYCODE_DPAD_UP)
            await("Return to hero action") { page.currentFocus?.id == com.xyoye.local_component.R.id.hero_play }
            send(KeyEvent.KEYCODE_BACK)
            await("Return to navigation") { page.findViewById<View>(com.okamihoro.ddplaytv.R.id.tv_nav_rv).hasFocus() }
        }

    private fun longHome(test: (TvMainActivity) -> Unit) {
        val longTitle = "乱世千金倪亚·利斯顿转生为娇弱千金的弑神武人华丽无双录"
        runBlocking {
            val work =
                db
                    .getMetadataDao()
                    .observeWorks()
                    .first()
                    .first { it.tmdbId == 991001L }
            db.getMetadataDao().putWork(work.copy(title = longTitle))
        }
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var page: TvMainActivity
            scenario.onActivity { page = it }
            await("Long-title homepage with artwork") {
                page.hasWindowFocus() &&
                    page.findViewById<TextView>(com.xyoye.local_component.R.id.hero_title)?.text == longTitle &&
                    page.findViewById<ImageView>(com.xyoye.local_component.R.id.backdrop)?.drawable is BitmapDrawable &&
                    page.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.continue_watching)?.adapter?.itemCount == 4
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Hero action receives focus") { page.currentFocus?.id == com.xyoye.local_component.R.id.hero_play }
            try {
                test(page)
            } catch (failure: Throwable) {
                screenshot("failure-home")
                throw failure
            }
        }
    }

    private fun screenBounds(view: View): Rect {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "native-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        message: String,
        condition: () -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
}
