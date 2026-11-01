package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.entity.*
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.R
import com.xyoye.local_component.ui.metadata.CinemaFragment
import com.xyoye.local_component.ui.metadata.MetadataCardAdapter
import com.xyoye.local_component.ui.metadata.MetadataDetailActivity
import com.xyoye.local_component.ui.metadata.MetadataEpisodeAdapter
import com.xyoye.local_component.ui.metadata.MetadataLibraryViewModel
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Date

@RunWith(AndroidJUnit4::class)
class WatchHistoryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance
    private val dao get() = db.getMetadataDao()
    private val directory get() = requireNotNull(context.getExternalFilesDir("watch-history"))
    private val library = MediaLibraryEntity(991071, "115", "watch-history-fixture", MediaType.LOCAL_STORAGE)
    private val alternate = MediaLibraryEntity(991072, "NAS", "watch-history-nas", MediaType.LOCAL_STORAGE)
    private val workId = 991071L
    private val assets = mutableMapOf<Pair<Int, Int>, MediaAsset>()
    private val title = "乱世千金倪亚·利斯顿转生成为娇弱千金的弑神武人华丽无双录"
    private val now get() = System.currentTimeMillis()

    @Before fun seed() =
        runBlocking {
            instrumentation.setInTouchMode(false)
            listOf(library, alternate).forEach {
                db.getMediaLibraryDao().insert(it)
                dao.putSource(CatalogSourceState(it.id))
            }
            dao.putWork(work())
            for (number in 1..12) assets[1 to number] = addEpisode(1, number)
            addHistory(assets.getValue(1 to 3), 8400, now - 10000)
        }

    @After fun cleanup(): Unit =
        runBlocking {
            listOf(library, alternate).forEach {
                db.getPlayHistoryDao().deleteByStorageId(it.id)
                MediaLibraryRepository.delete(it.url, it.mediaType)
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE tmdbId BETWEEN 991071 AND 991073")
            db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_episode WHERE seriesId = 991071")
            directory.listFiles()?.filter { it.extension == "mp4" }?.forEach {
                db.getVideoDao().deleteByPath(it.path)
                it.delete()
            }
        }

    @Test fun selectedBShowsFourFullCardsAndAllPrimaryActionsAreReachable() {
        detail { scenario, page ->
            assertEquals(R.id.resume, page.currentFocus?.id)
            val titleView = page.findViewById<TextView>(R.id.title)
            assertEquals(title, titleView.text.toString())
            assertTrue(
                "Full title is measured without ellipsis",
                (0 until titleView.lineCount).all {
                    titleView.layout.getEllipsisCount(it) ==
                        0
                }
            )
            assertFalse(page.findViewById<View>(R.id.season_scroll).isShown)
            screenshot("normal")
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Resume enters last episode") { selectedKey(page) == "episode:1:3" && list(page).hasFocus() }
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            await("First episode") { selectedKey(page) == "episode:1:1" }
            assertVisibleFocus(page)
            instrumentation.runOnMainSync {
                val recycler = list(page)
                val fullyVisible =
                    recycler.children.count { child ->
                        val rect = Rect()
                        child.getGlobalVisibleRect(rect) && rect.width() == child.width && rect.height() == child.height
                    }
                assertTrue("Four full cards at 960dp viewport, actual=$fullyVisible", fullyVisible >= 4)
                val image = recycler.getChildAt(0).findViewById<View>(R.id.card_image)
                assertEquals(194f, image.width / context.resources.displayMetrics.density, 1f)
            }
            screenshot("first-card")
            repeat(7) { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertVisibleFocus(page)
            screenshot("scrolled")
            send(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals(R.id.resume, page.currentFocus?.id)
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(R.id.synopsis, page.currentFocus?.id)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Synopsis dialog") { !page.hasWindowFocus() }
            screenshot("synopsis")
            send(KeyEvent.KEYCODE_BACK)
            await("Synopsis returns to opener") { page.hasWindowFocus() && page.currentFocus?.id == R.id.synopsis }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(R.id.correct, page.currentFocus?.id)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Management file picker") { !page.hasWindowFocus() }
            send(KeyEvent.KEYCODE_BACK)
            await("Picker returns to management") { page.hasWindowFocus() && page.currentFocus?.id == R.id.correct }
            scenario.recreate()
        }
    }

    @Test fun seasonsVersionsAndRecreationPreserveBrowseSelectionAndOriginalResume() {
        runBlocking {
            for (n in 1..6) assets[2 to n] = addEpisode(2, n)
            addHistory(assets.getValue(2 to 3), 8400, now)
            val second = addEpisode(2, 3, alternate.id)
            addHistory(second, 0, now - 5000)
        }
        detail { scenario, initial ->
            var page = initial
            assertTrue(page.findViewById<TextView>(R.id.resume_note).text.startsWith("第 2 季 · 第 3 集"))
            screenshot("seasons")
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("episode:2:3", selectedKey(page))
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Version dialog") { !page.hasWindowFocus() }
            val hierarchy = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(hierarchy.findAccessibilityNodeInfosByText("42%").isNotEmpty())
            assertTrue(hierarchy.findAccessibilityNodeInfosByText("0%").isNotEmpty())
            screenshot("versions")
            send(KeyEvent.KEYCODE_BACK)
            await("Version cancel restores episode") { page.hasWindowFocus() && list(page).hasFocus() }
            send(KeyEvent.KEYCODE_DPAD_UP)
            assertEquals(2, page.currentFocus?.tag)
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Browse season one without changing resume") { list(page).adapter?.itemCount == 12 }
            assertTrue(page.findViewById<TextView>(R.id.resume_note).text.startsWith("第 2 季 · 第 3 集"))
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(5) { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            val key = selectedKey(page)
            scenario.recreate()
            scenario.onActivity { page = it }
            await("Season/episode focus restored after recreation") {
                page.hasWindowFocus() &&
                    list(page).hasFocus() &&
                    selectedKey(page) == key
            }
            screenshot("recreated-season")
            runBlocking { dao.putWork(work().copy(overview = "后台更新简介，不能重置选季或焦点。")) }
            await("Updated overview") { page.findViewById<TextView>(R.id.overview).text.startsWith("后台") }
            assertEquals(key, selectedKey(page))
            assertTrue(list(page).hasFocus())
        }
    }

    @Test fun freshSpecialsUnknownDurationAndMergedFilesHaveHonestStates() {
        runBlocking { db.getPlayHistoryDao().deleteByStorageId(library.id) }
        detail { _, page ->
            assertEquals("开始观看", page.findViewById<TextView>(R.id.resume).text)
            assertTrue(page.findViewById<TextView>(R.id.resume_note).text.startsWith("第 1 季 · 第 1 集"))
            screenshot("fresh")
        }
        runBlocking {
            assets.values.forEach { dao.clearEpisodes(it.assetId) }
            val special = assets.getValue(1 to 3)
            dao.putBindingEpisodes(listOf(MediaBindingEpisode(special.assetId, 0, 1, 0), MediaBindingEpisode(special.assetId, 0, 2, 1)))
            addHistory(special, 754000, now, duration = 0)
        }
        detail { _, page ->
            assertEquals("特别篇", page.findViewById<TextView>(R.id.season_label).text)
            assertTrue(page.findViewById<TextView>(R.id.resume_note).text.contains("12:34"))
            assertEquals("合并集共享同一文件进度", page.findViewById<TextView>(R.id.hint).text)
            assertTrue((list(page).adapter as MetadataEpisodeAdapter).currentList.take(2).all { it.card.progress == null })
            screenshot("specials-merged-unknown")
        }
    }

    @Test fun missingLatestFileKeepsReasonAndMovesFocusToAvailableEpisode() {
        runBlocking { dao.putAsset(assets.getValue(1 to 3).copy(available = false)) }
        detail { _, page ->
            assertFalse(page.findViewById<View>(R.id.resume).isEnabled)
            assertTrue(page.findViewById<TextView>(R.id.resume_note).text.contains("暂不可用"))
            assertTrue(list(page).hasFocus())
            assertEquals("episode:1:1", selectedKey(page))
            screenshot("missing")
            repeat(2) { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Known missing message") { page.findViewById<TextView>(R.id.hint).text.contains("暂不可用") }
            assertTrue(list(page).hasFocus())
            assertFalse(ActivityHelper.instance.getTopActivity() is PlayerActivity)
        }
    }

    @Test fun emptyAndInvalidWorkRemainReachableWithoutFakeEpisodes() {
        runBlocking {
            assets.values.forEach { dao.putBinding(MediaBinding(it.assetId)) }
            db.getPlayHistoryDao().deleteByStorageId(library.id)
        }
        detail { _, page ->
            assertFalse(list(page).isShown)
            assertEquals(title, page.findViewById<TextView>(R.id.title).text.toString())
            assertEquals(R.id.browse, page.currentFocus?.id)
            screenshot("empty")
            send(KeyEvent.KEYCODE_BACK)
            await("Remote exits empty page") { page.isDestroyed }
        }
        detail(workId + 2) { _, page ->
            assertEquals("作品资料暂不可用", page.findViewById<TextView>(R.id.title).text)
            assertEquals(R.id.browse, page.currentFocus?.id)
            screenshot("invalid")
        }
    }

    @Test fun failedPlaybackRetainsProgressAndTriggerFocus() {
        val asset = assets.getValue(1 to 3)
        File(asset.storagePath).delete()
        detail { _, page ->
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playback failure persists on detail", 15000) { page.hasWindowFocus() && page.findViewById<View>(R.id.hint).isShown }
            assertEquals(R.id.resume, page.currentFocus?.id)
            assertFalse(ActivityHelper.instance.getTopActivity() is PlayerActivity)
            assertEquals(8400L, runBlocking { db.getPlayHistoryDao().getPlayHistory(asset.uniqueKey, library.id)?.videoPosition })
            screenshot("playback-failure")
        }
    }

    @Test fun movieCardOpensFileVersionsAndReturnsToTheSameWork() {
        runBlocking {
            val movie = assets.getValue(1 to 3)
            val second = addEpisode(1, 3, alternate.id)
            dao.putWork(work().copy(mediaKind = "movie", tmdbId = workId + 1, title = "电影作品"))
            listOf(movie, second).forEach {
                dao.clearEpisodes(it.assetId)
                dao.putBinding(MediaBinding(it.assetId, "MATCHED", "movie", workId + 1, "MANUAL", true))
            }
        }
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var home: TvMainActivity
            scenario.onActivity { home = it }
            await("Movie history loaded") { home.hasWindowFocus() && homeCards(home).firstOrNull()?.key == "movie:${workId + 1}" }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Movie detail") { ActivityHelper.instance.getTopActivity() is MetadataDetailActivity }
            val page = ActivityHelper.instance.getTopActivity() as MetadataDetailActivity
            await("Movie ready") { page.currentFocus?.id == R.id.resume }
            assertEquals("影片文件", page.findViewById<TextView>(R.id.episodes_title).text)
            assertEquals("2 个版本", page.findViewById<TextView>(R.id.episode_count).text)
            assertFalse(page.findViewById<View>(R.id.season_label).isShown)
            assertFalse(page.findViewById<View>(R.id.season_scroll).isShown)
            screenshot("movie")
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Movie versions") { !page.hasWindowFocus() }
            assertTrue(
                instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText("尚未观看")
                    .isNotEmpty()
            )
            screenshot("movie-versions")
            send(KeyEvent.KEYCODE_BACK)
            await("Movie picker cancel") { page.hasWindowFocus() && list(page).hasFocus() }
            send(KeyEvent.KEYCODE_BACK)
            await("Movie card restored") { home.hasWindowFocus() && selectedHomeKey(home) == "movie:${workId + 1}" }
        }
    }

    @Test fun pendingDataShowsLoadingWithoutEmptyActionsOrPrematureFocus() {
        val application = context.applicationContext as android.app.Application
        // Hold the state producer before STARTED, leaving the real UI at its initial data state.
        val holdData =
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityPostCreated(
                    activity: android.app.Activity,
                    state: android.os.Bundle?
                ) {
                    if (activity is MetadataDetailActivity) activity.getOwnerViewModel().viewModelScope.cancel()
                    if (activity is TvMainActivity) {
                        activity.supportFragmentManager.fragments.filterIsInstance<CinemaFragment>().forEach {
                            ViewModelProvider(it)[MetadataLibraryViewModel::class.java].viewModelScope.cancel()
                        }
                    }
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    state: android.os.Bundle?
                ) = Unit

                override fun onActivityStarted(activity: android.app.Activity) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivityStopped(activity: android.app.Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    state: android.os.Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
        application.registerActivityLifecycleCallbacks(holdData)
        try {
            ActivityScenario
                .launch<MetadataDetailActivity>(
                    Intent(context, MetadataDetailActivity::class.java)
                        .putExtra("kind", "tv")
                        .putExtra("tmdbId", workId)
                ).use { scenario ->
                    scenario.onActivity { page ->
                        assertEquals("正在读取作品资料…", page.findViewById<TextView>(R.id.title).text)
                        assertFalse(page.findViewById<View>(R.id.resume).isEnabled)
                        assertFalse(page.findViewById<View>(R.id.browse).isShown)
                        assertFalse(page.findViewById<View>(R.id.correct).isShown)
                        assertNull(page.currentFocus)
                    }
                    screenshot("initial-detail")
                }
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                scenario.onActivity { page ->
                    assertEquals("正在读取观看足迹…", page.findViewById<TextView>(R.id.empty).text)
                    assertFalse(page.findViewById<View>(R.id.continue_watching).isShown)
                    assertFalse(page.findViewById<View>(R.id.hero_play).isEnabled)
                }
                screenshot("initial-home")
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(holdData)
        }
        detail { _, page -> assertEquals(R.id.resume, page.currentFocus?.id) }
    }

    @Test fun unidentifiedHistoryStillPlaysItsOwnFileAndEmptyHomeHasALibraryAction() {
        val old = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(PlayerType.TYPE_EXO_PLAYER.value)
        val asset = assets.getValue(1 to 3)
        runBlocking { dao.putBinding(MediaBinding(asset.assetId)) }
        try {
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                lateinit var home: TvMainActivity
                scenario.onActivity { home = it }
                await("Unidentified file ready") {
                    home.hasWindowFocus() &&
                        homeCards(home).firstOrNull()?.key?.startsWith("history:") == true
                }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                screenshot("home-unidentified")
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                checkPlaying(asset, 8400)
                exitPlayer()
                await("File returns to home") { home.hasWindowFocus() }
                runBlocking { db.getPlayHistoryDao().deleteByStorageId(library.id) }
                await("Real empty history") { homeCards(home).isEmpty() && home.findViewById<TextView>(R.id.empty).text.startsWith("从媒体库") }
                assertEquals(R.id.hero_play, home.currentFocus?.id)
                assertTrue(home.findViewById<View>(R.id.filter_all).isShown)
                screenshot("home-empty")
            }
        } finally {
            PlayerConfig.putUsePlayerType(old)
        }
    }

    @Test fun recognitionAndReorderingKeepTheFocusedHistoryCard() {
        val target = assets.getValue(1 to 3)
        runBlocking { dao.putBinding(MediaBinding(target.assetId)) }
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var page: TvMainActivity
            scenario.onActivity { page = it }
            await("Home history loaded") { homeCards(page).isNotEmpty() }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("File history focused") { page.findViewById<DpadRecyclerView>(R.id.continue_watching).hasFocus() }
            assertTrue(selectedHomeKey(page).startsWith("history:"))
            runBlocking { dao.putBinding(MediaBinding(target.assetId, "MATCHED", "tv", workId, "MANUAL", true)) }
            await("Recognized card retains focus") {
                selectedHomeKey(page) == "tv:$workId" &&
                    page.findViewById<DpadRecyclerView>(R.id.continue_watching).hasFocus()
            }
            screenshot("home-recognized")
            runBlocking { addHistory(assets.getValue(1 to 4), 1000, now) }
            await("New episode uses same work key") { homeCards(page).firstOrNull()?.subtitle?.contains("第 4 集") == true }
            assertEquals("tv:$workId", selectedHomeKey(page))
        }
    }

    @Test fun media3HomepageDetailPlaybackAndLayeredBack() = playbackPath(PlayerType.TYPE_EXO_PLAYER)

    @Test fun mpvHomepageDetailPlaybackAndLayeredBack() = playbackPath(PlayerType.TYPE_MPV_PLAYER)

    private fun playbackPath(kernel: PlayerType) {
        val old = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(kernel.value)
        runBlocking {
            val movie = addEpisode(1, 1, alternate.id)
            dao.clearEpisodes(movie.assetId)
            dao.putWork(work().copy(mediaKind = "movie", tmdbId = workId + 1, title = "另一部电影"))
            dao.putBinding(MediaBinding(movie.assetId, "MATCHED", "movie", workId + 1, "MANUAL", true))
            addHistory(movie, 2000, now)
        }
        try {
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                lateinit var home: TvMainActivity
                scenario.onActivity { home = it }
                await("Two home work cards") { home.hasWindowFocus() && homeCards(home).size >= 2 }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Non-first series card selected") { selectedHomeKey(home) == "tv:$workId" }
                screenshot("home-two-works")
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Card opens work detail") { ActivityHelper.instance.getTopActivity() is MetadataDetailActivity }
                val page = ActivityHelper.instance.getTopActivity() as MetadataDetailActivity
                await("Ready to resume original episode") {
                    page.currentFocus?.id == R.id.resume &&
                        page.findViewById<TextView>(R.id.resume_note).text.contains("第 3 集")
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                checkPlaying(assets.getValue(1 to 3), 8400)
                backTo(page)
                assertEquals(R.id.resume, page.currentFocus?.id)
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertEquals("episode:1:4", selectedKey(page))
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                checkPlaying(assets.getValue(1 to 4), 0)
                backTo(page)
                await("Return to episode four") { list(page).hasFocus() && selectedKey(page) == "episode:1:4" }
                send(KeyEvent.KEYCODE_BACK)
                await("Return to same series card after reorder") {
                    home.hasWindowFocus() &&
                        selectedHomeKey(home) == "tv:$workId" &&
                        home.findViewById<DpadRecyclerView>(R.id.continue_watching).hasFocus()
                }
                screenshot("home-return-${kernel.value}")
            }
        } finally {
            PlayerConfig.putUsePlayerType(old)
        }
    }

    private fun checkPlaying(
        asset: MediaAsset,
        position: Long
    ) {
        await("Player ready at original file position", 25000) {
            val page = ActivityHelper.instance.getTopActivity() as? PlayerActivity ?: return@await false
            val player = page.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer
            page.hasWindowFocus() &&
                player.isPlaying() &&
                player.getVideoSource().getUniqueKey() == asset.uniqueKey &&
                player.getCurrentPosition() >= position - 2000
        }
    }

    private fun backTo(page: MetadataDetailActivity) {
        exitPlayer()
        await("Player returns to detail") { page.hasWindowFocus() }
    }

    private fun exitPlayer() {
        val player = ActivityHelper.instance.getTopActivity() as PlayerActivity
        repeat(4) {
            var exiting = false
            instrumentation.runOnMainSync { exiting = player.isFinishing || player.isDestroyed }
            if (!exiting) {
                // Do not wait for player-rendering idleness between the two exit-confirm keys.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(350)
            }
        }
        await("Player finishes") { player.isFinishing || player.isDestroyed }
    }

    private fun detail(
        id: Long = workId,
        block: (ActivityScenario<MetadataDetailActivity>, MetadataDetailActivity) -> Unit
    ) {
        ActivityScenario
            .launch<MetadataDetailActivity>(
                Intent(context, MetadataDetailActivity::class.java)
                    .putExtra("kind", "tv")
                    .putExtra("tmdbId", id)
                    .putExtra("historyId", Int.MAX_VALUE)
            ).use { scenario ->
                lateinit var page: MetadataDetailActivity
                scenario.onActivity { page = it }
                await("Detail data and focus ready") {
                    page.hasWindowFocus() &&
                        page.currentFocus?.isShown == true &&
                        page.findViewById<TextView>(R.id.detail_status).text != "正在读取作品资料…"
                }
                block(scenario, page)
            }
    }

    private suspend fun addEpisode(
        season: Int,
        number: Int,
        source: Int = library.id
    ): MediaAsset {
        val video = File(directory, "$source-S${season}E$number.mp4")
        File("/sdcard/Download/ddplaytv-tmdb-fixture.mp4").copyTo(video, overwrite = true)
        db.getVideoDao().insert(
            VideoEntity(fileId = 0, filePath = video.path, folderPath = directory.path, fileLength = video.length(), videoDuration = 20000)
        )
        val raw =
            MediaAsset(
                libraryId = source,
                contentGeneration = 1,
                locatorKey = "path:${video.path}",
                uniqueKey = video.path.toMd5String(),
                storagePath = video.path,
                fileName = video.name,
                parentPath = directory.path,
                discoveredAt = now
            )
        val asset = raw.copy(assetId = dao.putAsset(raw))
        dao.putBinding(MediaBinding(asset.assetId, "MATCHED", "tv", workId, "MANUAL", true))
        dao.putBindingEpisodes(listOf(MediaBindingEpisode(asset.assetId, season, number, 0)))
        dao.putEpisodes(
            listOf(
                MetadataEpisode(
                    workId,
                    season,
                    number,
                    null,
                    if (season == 1 && number <= 3) listOf("自黑暗中浮现", "王都的工作", "舞台落幕之后")[number - 1] else "第 $number 集",
                    "",
                    "",
                    if (season == 1 && number <= 2) File(directory, "episode-$number.png").toURI().toString() else null
                )
            )
        )
        return asset
    }

    private suspend fun addHistory(
        asset: MediaAsset,
        position: Long,
        time: Long,
        duration: Long = 20000
    ) {
        db.getPlayHistoryDao().insert(
            PlayHistoryEntity(
                videoName = asset.fileName,
                url = asset.storagePath,
                mediaType = MediaType.LOCAL_STORAGE,
                videoPosition = position,
                videoDuration = duration,
                playTime = Date(time),
                uniqueKey = asset.uniqueKey,
                storagePath = asset.storagePath,
                storageId = asset.libraryId
            )
        )
    }

    private fun work() =
        MetadataWork(
            "tv",
            workId,
            title,
            title,
            "拥有最强前世的转生千金，第二人生也要大闹天下。在病弱的千金倪亚·利斯顿体内苏醒后，她决定以自己的方式，重新踏上这片未知的世界。",
            "2026",
            "动画 · 奇幻",
            0.0,
            File(directory, "poster.png").toURI().toString(),
            null,
            "zh-CN",
            "",
            0,
            1
        )

    private fun list(page: MetadataDetailActivity) = page.findViewById<DpadRecyclerView>(R.id.files)

    private fun selectedKey(page: MetadataDetailActivity) =
        (list(page).adapter as MetadataEpisodeAdapter)
            .currentList
            .getOrNull(list(page).getSelectedPosition())
            ?.card
            ?.key

    private fun homeCards(page: TvMainActivity) =
        (page.findViewById<DpadRecyclerView>(R.id.continue_watching)?.adapter as? MetadataCardAdapter)?.currentList.orEmpty()

    private fun selectedHomeKey(page: TvMainActivity) =
        homeCards(page).getOrNull(page.findViewById<DpadRecyclerView>(R.id.continue_watching).getSelectedPosition())?.key.orEmpty()

    private fun assertVisibleFocus(page: MetadataDetailActivity) =
        instrumentation.runOnMainSync {
            val view = requireNotNull(page.currentFocus)
            val rect = Rect()
            assertTrue(view.getGlobalVisibleRect(rect))
            assertEquals("Focus width", view.width, rect.width())
            assertEquals("Focus height", view.height, rect.height())
        }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun await(
        label: String,
        timeout: Long = 10000,
        condition: () -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        screenshot("failure")
        instrumentation.runOnMainSync {
            val page = ActivityHelper.instance.getTopActivity()
            val focus = page?.currentFocus
            if (page is TvMainActivity) {
                android.util.Log.e(
                    "WatchHistoryTest",
                    "Home selection=${selectedHomeKey(
                        page
                    )} cards=${homeCards(page)} listFocus=${page.findViewById<DpadRecyclerView>(R.id.continue_watching)?.hasFocus()}"
                )
            }
            android.util.Log.e(
                "WatchHistoryTest",
                "$label: activity=$page focus=$focus window=${page?.hasWindowFocus()} status=${page?.findViewById<TextView>(
                    R.id.detail_status
                )?.text} title=${page?.findViewById<TextView>(R.id.title)?.text}"
            )
        }
        fail(label)
    }
}
