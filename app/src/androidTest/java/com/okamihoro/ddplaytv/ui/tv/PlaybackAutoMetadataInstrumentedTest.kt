package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.data.tmdb.TmdbConfiguration
import com.xyoye.data_component.data.tmdb.TmdbEpisode
import com.xyoye.data_component.data.tmdb.TmdbImageConfiguration
import com.xyoye.data_component.data.tmdb.TmdbPage
import com.xyoye.data_component.data.tmdb.TmdbSeason
import com.xyoye.data_component.data.tmdb.TmdbTitle
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.metadata.MetadataMatchActivity
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackAutoMetadataInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance
    private val library = MediaLibraryEntity(991051, "播放识别验收", "content://auto-metadata-fixture", MediaType.LOCAL_STORAGE)

    @Test fun media3PlaybackDiscoversUnindexedEpisodesAndUpdatesCinema() = verify(PlayerType.TYPE_EXO_PLAYER, false)

    @Test fun mpvPlaybackDiscoversUnindexedEpisodesAndUpdatesCinema() = verify(PlayerType.TYPE_MPV_PLAYER, false)

    @Test fun media3NetworkFailureDoesNotBlockPlaybackAndCanRetry() = verify(PlayerType.TYPE_EXO_PLAYER, true)

    @Test fun mpvNetworkFailureDoesNotBlockPlaybackAndCanRetry() = verify(PlayerType.TYPE_MPV_PLAYER, true)

    private fun verify(
        kernel: PlayerType,
        offline: Boolean
    ) {
        val workId = 991051L + kernel.value * 10 + if (offline) 1 else 0
        val media = File("/sdcard/Download/file-browser-c.mp4")
        assertTrue(media.isFile)
        val folder = File(context.getExternalFilesDir(null), "auto-metadata-fixture").apply { mkdirs() }
        val files =
            (1..12).map { number ->
                File(folder, "Auto Show S01E${number.toString().padStart(2, '0')} [tmdb-tv-$workId].mp4").also { media.copyTo(it, true) }
            }
        val selected = files[2]
        val unrelated = File(folder, "Unrelated Movie (2024).mp4").apply { writeText("unrelated") }
        val oldKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(kernel.value)
        instrumentation.setInTouchMode(false)
        val repository = MetadataCoordinator.repository
        val field = repository.javaClass.getDeclaredField("service").apply { isAccessible = true }
        val previousService = field.get(repository)
        val service = FixtureService(offline)
        field.set(repository, service.proxy(field.type))
        runBlocking {
            db.getMediaLibraryDao().insert(library)
            db.getVideoDao().insert(
                VideoEntity(
                    fileId = 0,
                    filePath = selected.path,
                    folderPath = folder.path,
                    fileLength = selected.length(),
                    videoDuration = 120000
                )
            )
            db.getPlayHistoryDao().insert(
                PlayHistoryEntity(
                    videoName = selected.name,
                    url = selected.path,
                    mediaType = library.mediaType,
                    videoPosition = 8000,
                    videoDuration = 120000,
                    uniqueKey = selected.path.toMd5String(),
                    storagePath = selected.path,
                    storageId = library.id
                )
            )
        }
        try {
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                lateinit var home: TvMainActivity
                scenario.onActivity { home = it }
                await("Home shows original file") {
                    home.hasWindowFocus() &&
                        home.findViewById<TextView>(com.xyoye.local_component.R.id.hero_title)?.text == selected.name
                }
                // The Cinema default focus is the play action; all navigation below uses DPAD.
                repeat(3) { if (home.currentFocus?.id != com.xyoye.local_component.R.id.hero_play) send(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("Play action reachable") { home.currentFocus?.id == com.xyoye.local_component.R.id.hero_play }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Playback starts while metadata service is waiting", 20000) { activePlayer()?.let { player(it).isPlaying() } == true }
                val playing = requireNotNull(activePlayer())
                await("Asynchronous resume seek completes while metadata is still blocked") {
                    player(playing).isPlaying() && player(playing).getCurrentPosition() >= 6500
                }
                var originalSource: Any? = null
                instrumentation.runOnMainSync {
                    originalSource = player(playing).getVideoSource()
                    assertTrue("Resume position=${player(playing).getCurrentPosition()}", player(playing).getCurrentPosition() >= 6500)
                }
                assertEquals(
                    0,
                    runBlocking {
                        repository.dao.directoryAssets(library.id, 1, folder.path).count {
                            repository.dao.binding(it.assetId)?.state ==
                                "MATCHED"
                        }
                    }
                )
                service.gate.countDown()
                awaitDatabase("Playback task completes") {
                    val assets = repository.dao.directoryAssets(library.id, 1, folder.path)
                    if (offline) {
                        assets.singleOrNull()?.let { repository.dao.binding(it.assetId)?.failure == "NETWORK" } == true
                    } else {
                        assets.size == 12 && assets.all { repository.dao.binding(it.assetId)?.state == "MATCHED" }
                    }
                }
                instrumentation.runOnMainSync {
                    assertSame(originalSource, player(playing).getVideoSource())
                    assertTrue(player(playing).isPlaying())
                    assertTrue(player(playing).getCurrentPosition() >= 6500)
                }
                assertNull(runBlocking { db.getPlayHistoryDao().getPlayHistory(files[0].path.toMd5String(), library.id) })
                val run =
                    runBlocking {
                        repository.dao
                            .observeScans()
                            .first()
                            .single { it.libraryId == library.id }
                    }
                assertEquals(selected.path.toMd5String(), run.triggerKey)
                assertFalse(run.recursive)
                if (!offline) {
                    runBlocking { MetadataCoordinator.retry(run) }
                    assertEquals(12, runBlocking { repository.dao.directoryAssets(library.id, 1, folder.path).size })
                    assertNull(runBlocking { db.getVideoDao().getVideo(files[0].path) })
                    assertNotNull(
                        runBlocking {
                            com.xyoye.common_component.storage.impl
                                .VideoStorage(library)
                                .pathFile(files[0].path, false)
                        }
                    )
                }
                repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
                await("Return home after playback") { home.hasWindowFocus() }
                if (!offline) {
                    await("Automatic metadata updates Cinema") {
                        home.findViewById<TextView>(com.xyoye.local_component.R.id.hero_title)?.text ==
                            "Auto Show"
                    }
                    screenshot("${kernel.name}-home")
                }
            }
            val asset =
                runBlocking {
                    repository.dao.directoryAssets(library.id, 1, folder.path).first {
                        it.uniqueKey ==
                            selected.path.toMd5String()
                    }
                }
            val revision = runBlocking { repository.dao.binding(asset.assetId)!!.revision }
            ActivityScenario
                .launch<MetadataMatchActivity>(
                    Intent(context, MetadataMatchActivity::class.java).putExtra("assetId", asset.assetId)
                ).use { scenario ->
                    lateinit var page: MetadataMatchActivity
                    scenario.onActivity { page = it }
                    await("Problem page visible") {
                        page.hasWindowFocus() &&
                            page.findViewById<TextView>(com.xyoye.local_component.R.id.file_name).text == selected.name
                    }
                    assertEquals(
                        "Opening the page must not start a binding attempt",
                        revision,
                        runBlocking {
                            repository.dao.binding(asset.assetId)!!.revision
                        }
                    )
                    if (offline) {
                        screenshot("${kernel.name}-error")
                        service.offline = false
                        service.gate = java.util.concurrent.CountDownLatch(1)
                        repeat(2) { if (page.currentFocus?.id != com.xyoye.local_component.R.id.retry) send(KeyEvent.KEYCODE_DPAD_LEFT) }
                        assertEquals(com.xyoye.local_component.R.id.retry, page.currentFocus?.id)
                        send(KeyEvent.KEYCODE_DPAD_CENTER)
                        await("Retry is asynchronous and status owns focus") {
                            page.findViewById<TextView>(com.xyoye.local_component.R.id.status).text == "正在重新识别…" &&
                                page.currentFocus?.id == com.xyoye.local_component.R.id.status_panel
                        }
                        screenshot("${kernel.name}-loading")
                        service.gate.countDown()
                        awaitDatabase("Explicit retry succeeds") {
                            repository.dao.binding(asset.assetId)?.let {
                                it.state == "MATCHED" &&
                                    it.failure == null
                            } ==
                                true
                        }
                        await(
                            "Success feedback"
                        ) { page.findViewById<TextView>(com.xyoye.local_component.R.id.status).text.startsWith("已识别") }
                    }
                    screenshot("${kernel.name}-recognized")
                    send(KeyEvent.KEYCODE_BACK)
                }
        } finally {
            service.gate.countDown()
            field.set(repository, previousService)
            PlayerConfig.putUsePlayerType(oldKernel)
            runBlocking {
                MediaLibraryRepository.delete(library.url, library.mediaType)
                db.getPlayHistoryDao().deleteByStorageId(library.id)
                db.getVideoDao().deleteByPath(selected.path)
                db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE tmdbId = $workId")
                db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_season WHERE seriesId = $workId")
                db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_episode WHERE seriesId = $workId")
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM metadata_scan_directory WHERE runId IN (SELECT runId FROM metadata_scan WHERE libraryId = ${library.id})"
                )
                db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_scan WHERE libraryId = ${library.id}")
            }
            files.forEach { it.delete() }
            unrelated.delete()
            folder.delete()
        }
    }

    private fun activePlayer() = ActivityHelper.instance.getTopActivity() as? PlayerActivity

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
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
        fail(label)
    }

    private fun awaitDatabase(
        label: String,
        condition: suspend () -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (runBlocking { condition() }) return
            SystemClock.sleep(50)
        }
        fail(label)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("auto-metadata-verification"), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class FixtureService(
        var offline: Boolean
    ) {
        var gate = java.util.concurrent.CountDownLatch(1)

        // Keep the app's test dependency boundary unchanged; only the HTTP service is replaced.
        fun proxy(type: Class<*>): Any =
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "details" -> {
                        check(gate.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        if (offline) {
                            @Suppress("UNCHECKED_CAST")
                            val continuation = args.last() as kotlin.coroutines.Continuation<Any?>
                            continuation.resumeWith(Result.failure(java.io.IOException("Offline fixture")))
                            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                        } else {
                            TmdbTitle(args[1] as Long, name = "Auto Show", overview = "Playback-triggered fixture")
                        }
                    }
                    "search" -> TmdbPage(1, emptyList(), 1)
                    "season" -> {
                        val season = args[1] as Int
                        TmdbSeason(season, episodes = (1..12).map { TmdbEpisode(it.toLong(), it, season, "Episode $it", "Fixture") })
                    }
                    "configuration" -> TmdbConfiguration(TmdbImageConfiguration("https://image.tmdb.org/t/p/"))
                    else -> error("Unexpected service method: ${method.name}")
                }
            }
    }
}
