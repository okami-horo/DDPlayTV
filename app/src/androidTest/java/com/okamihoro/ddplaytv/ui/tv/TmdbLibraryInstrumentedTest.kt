package com.okamihoro.ddplaytv.ui.tv

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.metadata.MetadataRepository
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.metadata.MetadataDetailActivity
import com.xyoye.local_component.ui.metadata.MetadataLibraryActivity
import com.xyoye.local_component.ui.metadata.MetadataMatchActivity
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.user_component.ui.activities.metadata.MetadataSettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TmdbLibraryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance
    private val fixture = File("/sdcard/Download/ddplaytv-tmdb-fixture.mp4")
    private val library = MediaLibraryEntity(990011, "TMDB 验收来源", "content://tmdb-fixture", MediaType.LOCAL_STORAGE)
    private var assetId = 0L

    @Before fun seed() =
        runBlocking {
            instrumentation.setInTouchMode(false)
            assertTrue("Push the task-owned fixture before running", fixture.isFile)
            db.getMediaLibraryDao().insert(library)
            db.getVideoDao().insert(
                VideoEntity(
                    fileId = 0,
                    filePath = fixture.path,
                    folderPath = requireNotNull(fixture.parent),
                    fileLength = fixture.length(),
                    videoDuration = 120_000
                )
            )
            db.getMetadataDao().putSource(CatalogSourceState(library.id))
            assetId =
                db.getMetadataDao().putAsset(
                    MediaAsset(
                        libraryId = library.id,
                        contentGeneration = 1,
                        locatorKey = "path:${fixture.path}",
                        uniqueKey = fixture.path.toMd5String(),
                        storagePath = fixture.path,
                        fileName = fixture.name,
                        parentPath = requireNotNull(fixture.parent),
                        discoveredAt = System.currentTimeMillis()
                    )
                )
            db.getMetadataDao().putWork(work())
            db.getMetadataDao().putBinding(MediaBinding(assetId, "MATCHED", "movie", 990011, "MANUAL", true))
        }

    @After fun cleanup() =
        runBlocking {
            db.getPlayHistoryDao().deleteByStorageId(library.id)
            db.getVideoDao().deleteByPath(fixture.path)
            MediaLibraryRepository.delete(library.url, library.mediaType)
            db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE tmdbId = 990011")
        }

    @Test fun detailUsesStoredDataAndDpadReturnsFromCorrection() {
        ActivityScenario.launch<MetadataDetailActivity>(detailIntent()).use { scenario ->
            lateinit var activity: MetadataDetailActivity
            scenario.onActivity { activity = it }
            await("Owned file row") {
                activity.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.files).adapter?.itemCount == 1 &&
                    activity.hasWindowFocus()
            }
            scenario.onActivity {
                assertEquals("TMDB 验收作品", it.findViewById<TextView>(com.xyoye.local_component.R.id.title).text.toString())
                it.findViewById<android.view.View>(com.xyoye.local_component.R.id.correct).requestFocus()
            }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Correction entry") { ActivityHelper.instance.getTopActivity() is MetadataMatchActivity }
            screenshot("ui-polish-correction")
            send(KeyEvent.KEYCODE_BACK)
            await("Return to detail") { activity.hasWindowFocus() }
            scenario.onActivity { assertTrue(it.currentFocus?.isShown == true) }
            screenshot("ui-polish-detail")
            scenario.recreate()
            scenario.onActivity { activity = it }
            await("Recreated cached title") { activity.findViewById<TextView>(com.xyoye.local_component.R.id.title).text == "TMDB 验收作品" }
        }
    }

    @Test fun media3PlaysOwnedFileAndRefreshDoesNotResetNinetyPercentProgress() = verifyPlayback(PlayerType.TYPE_EXO_PLAYER, 90)

    @Test fun mpvPlaysOwnedFileAndRefreshDoesNotResetNinetyFivePercentProgress() = verifyPlayback(PlayerType.TYPE_MPV_PLAYER, 95)

    @Test fun cinemaHeroResumesTheOriginalFileAtItsExactHistoryPosition() = verifyPlayback(PlayerType.TYPE_EXO_PLAYER, 90, true)

    @Test fun liveTmdbArtworkLoadsInCinemaAndDetail() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTmdb") == "true")
        val repository = MetadataRepository()
        val previous = runBlocking { repository.dao.work("movie", 157336) }
        try {
            val remote = runBlocking { repository.details("movie", 157336, refresh = true) }
            assertTrue(remote.title.isNotBlank())
            assertTrue(remote.poster?.startsWith("https://image.tmdb.org/") == true)
            runBlocking {
                repository.dao.putWork(remote.copy(tmdbId = 990011))
                db.getPlayHistoryDao().insert(
                    PlayHistoryEntity(
                        videoName = fixture.name,
                        url = fixture.path,
                        mediaType = library.mediaType,
                        videoPosition = 60_000,
                        videoDuration = 120_000,
                        uniqueKey = fixture.path.toMd5String(),
                        storagePath = fixture.path,
                        storageId = library.id
                    )
                )
            }
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                lateinit var home: TvMainActivity
                scenario.onActivity { home = it }
                await("Live backdrop rendered", 40000) {
                    home.hasWindowFocus() &&
                        home.findViewById<ImageView>(com.xyoye.local_component.R.id.backdrop)?.drawable is BitmapDrawable
                }
                screenshot("home-live")
            }
            ActivityScenario.launch<MetadataDetailActivity>(detailIntent()).use { scenario ->
                lateinit var detail: MetadataDetailActivity
                scenario.onActivity { detail = it }
                await("Live poster rendered", 40000) {
                    detail.hasWindowFocus() &&
                        detail.findViewById<ImageView>(com.xyoye.local_component.R.id.poster)?.drawable is BitmapDrawable
                }
                screenshot("detail-live")
            }
        } finally {
            runBlocking {
                if (previous != null) {
                    repository.dao.putWork(previous)
                } else {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE mediaKind = 'movie' AND tmdbId = 157336")
                }
            }
        }
    }

    @Test fun cinemaLibraryDetailAndCorrectionAreReachableUsingOnlyDpad() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var home: TvMainActivity
            scenario.onActivity { home = it }
            await("Cinema ready") {
                home.hasWindowFocus() && home.findViewById<TextView>(com.xyoye.local_component.R.id.hero_play)?.isShown == true
            }
            screenshot("home")
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Embedded library") {
                home.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.library_cards)?.adapter?.itemCount ==
                    1
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(8) { if (home.currentFocus?.id != com.xyoye.local_component.R.id.show_all) send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals(com.xyoye.local_component.R.id.show_all, home.currentFocus?.id)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Library") { ActivityHelper.instance.getTopActivity() is MetadataLibraryActivity }
            val library = ActivityHelper.instance.getTopActivity() as MetadataLibraryActivity
            await("Owned poster") { library.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.items).adapter?.itemCount == 1 }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Detail") { ActivityHelper.instance.getTopActivity() is MetadataDetailActivity }
            val detail = ActivityHelper.instance.getTopActivity() as MetadataDetailActivity
            await("Detail title") { detail.findViewById<TextView>(com.xyoye.local_component.R.id.title).text == "TMDB 验收作品" }
            await("Complete filename remains visible in the owned-file row") {
                val subtitle = detail.findViewById<TextView>(com.xyoye.local_component.R.id.card_subtitle) ?: return@await false
                val layout = subtitle.layout ?: return@await false
                val rect = android.graphics.Rect()
                subtitle.getGlobalVisibleRect(rect) &&
                    rect.height() >= subtitle.height &&
                    subtitle.height >=
                    layout.getLineBottom(layout.lineCount - 1) + subtitle.compoundPaddingTop + subtitle.compoundPaddingBottom
            }
            screenshot("detail")
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Correction") { ActivityHelper.instance.getTopActivity() is MetadataMatchActivity }
            await("Original filename and parsed search remain visible") {
                val match = ActivityHelper.instance.getTopActivity() as? MetadataMatchActivity ?: return@await false
                match.findViewById<TextView>(com.xyoye.local_component.R.id.file_name).text == fixture.name &&
                    match.findViewById<TextView>(com.xyoye.local_component.R.id.query).text.isNotBlank()
            }
            screenshot("correction")
            send(KeyEvent.KEYCODE_BACK)
            await(
                "Return to detail caller"
            ) { detail.hasWindowFocus() && detail.currentFocus?.id == com.xyoye.local_component.R.id.correct }
            send(KeyEvent.KEYCODE_BACK)
            await("Return to owned poster") {
                library.hasWindowFocus() &&
                    library.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.items).hasFocus()
            }
            send(KeyEvent.KEYCODE_BACK)
            await("Return to cinema caller") { home.hasWindowFocus() && home.currentFocus?.id == com.xyoye.local_component.R.id.show_all }
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("tmdb-verification"), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun emptyTaskPageKeepsActionsReachableAndCacheClearingPreservesBindings() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var home: TvMainActivity
            scenario.onActivity { home = it }
            await("Cinema ready") { home.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(
                "Embedded library"
            ) { home.findViewById<android.view.View>(com.xyoye.local_component.R.id.metadata_tasks)?.isShown == true }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(8) { if (home.currentFocus?.id != com.xyoye.local_component.R.id.metadata_tasks) send(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals(com.xyoye.local_component.R.id.metadata_tasks, home.currentFocus?.id)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Task settings") { ActivityHelper.instance.getTopActivity() is MetadataSettingsActivity }
            val settings = ActivityHelper.instance.getTopActivity() as MetadataSettingsActivity
            await("Empty task page focuses an actionable button") {
                settings.hasWindowFocus() && settings.currentFocus?.id == com.xyoye.user_component.R.id.clear_cache
            }
            screenshot("ui-polish-tasks")
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Images cleared") { settings.findViewById<TextView>(com.xyoye.user_component.R.id.message).text.startsWith("图片缓存已清理") }
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(
                "HTTP cache cleared"
            ) { settings.findViewById<TextView>(com.xyoye.user_component.R.id.message).text.startsWith("请求缓存已清理") }
            assertEquals("MATCHED", runBlocking { db.getMetadataDao().binding(assetId)?.state })
            send(KeyEvent.KEYCODE_BACK)
            await(
                "Return to task entry"
            ) { home.hasWindowFocus() && home.currentFocus?.id == com.xyoye.local_component.R.id.metadata_tasks }
        }
    }

    private fun verifyPlayback(
        kernel: PlayerType,
        percent: Int,
        fromHome: Boolean = false
    ) {
        val previous = PlayerConfig.getUsePlayerType()
        val retriever = android.media.MediaMetadataRetriever()
        val duration =
            try {
                retriever.setDataSource(fixture.path)
                requireNotNull(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            } finally {
                retriever.release()
            }
        val position = duration * percent / 100
        PlayerConfig.putUsePlayerType(kernel.value)
        runBlocking {
            db.getPlayHistoryDao().insert(
                PlayHistoryEntity(
                    videoName = fixture.name,
                    url = fixture.path,
                    mediaType = library.mediaType,
                    videoPosition = position,
                    videoDuration = duration,
                    uniqueKey = fixture.path.toMd5String(),
                    storagePath = fixture.path,
                    storageId = library.id
                )
            )
        }
        try {
            val intent = if (fromHome) Intent(context, TvMainActivity::class.java) else detailIntent()
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                lateinit var activity: Activity
                scenario.onActivity { activity = it }
                await("File row") {
                    activity.hasWindowFocus() &&
                        if (fromHome) {
                            activity.findViewById<TextView>(com.xyoye.local_component.R.id.hero_play)?.text == "继续播放"
                        } else {
                            activity.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.files).adapter?.itemCount == 1
                        }
                }
                scenario.onActivity {
                    it
                        .findViewById<android.view.View>(
                            if (fromHome) com.xyoye.local_component.R.id.hero_play else com.xyoye.local_component.R.id.files
                        ).requestFocus()
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Player prepared", 20000) {
                    (ActivityHelper.instance.getTopActivity() as? PlayerActivity)?.let { player(it).isPlaying() } ==
                        true
                }
                val playing = ActivityHelper.instance.getTopActivity() as PlayerActivity
                lateinit var identity: Any
                instrumentation.runOnMainSync {
                    identity = player(playing).getVideoSource()
                    assertEquals(fixture.path.toMd5String(), player(playing).getVideoSource().getUniqueKey())
                    assertTrue("Exact history progress must survive", player(playing).getCurrentPosition() >= position - 2500)
                }
                if (fromHome) {
                    send(KeyEvent.KEYCODE_DPAD_UP)
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Pause for control navigation") { !player(playing).isPlaying() }
                    await(
                        "Pause icon reflects the backend"
                    ) { !playing.findViewById<ImageView>(com.xyoye.player_component.R.id.play_iv).isSelected }
                    screenshot("player-controls")
                    instrumentation.runOnMainSync {
                        val playButton = playing.findViewById<ImageView>(com.xyoye.player_component.R.id.play_iv)
                        assertFalse("Playback stays paused while controls are visible", player(playing).isPlaying())
                        assertFalse(
                            "Pause icon stays synchronized; drawable state=${playButton.drawable.state.contentToString()}",
                            playButton.isSelected
                        )
                    }
                    listOf(
                        com.xyoye.player_component.R.id.subtitle_control_iv to "subtitles",
                        com.xyoye.player_component.R.id.audio_control to "audio",
                        com.xyoye.player_component.R.id.danmu_control_iv to "danmaku"
                    ).forEach { (id, name) ->
                        repeat(8) { if (playing.currentFocus?.id != id) send(KeyEvent.KEYCODE_DPAD_RIGHT) }
                        assertEquals("$name entry is reachable", id, playing.currentFocus?.id)
                        send(KeyEvent.KEYCODE_DPAD_CENTER)
                        await("$name panel owns focus") {
                            playing.findViewById<android.view.View>(com.xyoye.player_component.R.id.rv_track)?.hasFocus() == true ||
                                playing.currentFocus?.id == com.xyoye.player_component.R.id.track_visible ||
                                playing.currentFocus?.id == com.xyoye.player_component.R.id.tv_add_track
                        }
                        screenshot("player-$name")
                        send(KeyEvent.KEYCODE_BACK)
                        await("$name returns to caller") { playing.currentFocus?.id == id }
                    }
                    send(KeyEvent.KEYCODE_MENU)
                    await("Playback settings are reachable") {
                        playing.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)?.hasFocus() == true
                    }
                    screenshot("player-settings")
                    send(KeyEvent.KEYCODE_BACK)
                }
                runBlocking { db.getMetadataDao().putWork(work().copy(title = "已刷新资料")) }
                instrumentation.runOnMainSync {
                    assertSame(identity, player(playing).getVideoSource())
                    assertTrue(player(playing).getCurrentPosition() >= position - 2500)
                    playing.finish()
                }
                await("Return from playback") { activity.hasWindowFocus() }
            }
        } finally {
            PlayerConfig.putUsePlayerType(previous)
        }
    }

    private fun detailIntent() = Intent(context, MetadataDetailActivity::class.java).putExtra("kind", "movie").putExtra("tmdbId", 990011L)

    private fun work() =
        MetadataWork(
            "movie",
            990011,
            "TMDB 验收作品",
            "Fixture",
            "固定数据库夹具，用于离线页面与播放验收。",
            "2026-10-31",
            "测试",
            0.0,
            null,
            null,
            "zh-CN",
            "",
            System.currentTimeMillis(),
            1
        )

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        label: String,
        timeout: Long = 8000,
        condition: () -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail(label)
    }
}
