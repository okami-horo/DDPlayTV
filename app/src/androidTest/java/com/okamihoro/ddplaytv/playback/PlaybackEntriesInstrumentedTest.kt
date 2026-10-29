package com.okamihoro.ddplaytv.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.app.service.Media3SessionService
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.xyoye.anime_component.ui.fragment.anime_episode.AnimeEpisodeFragmentViewModel
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.impl.VideoStorageFile
import com.xyoye.common_component.storage.impl.VideoStorage
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.EpisodeHistoryEntity
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryViewModel
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.player_component.ui.activities.player.PlayerViewModel
import com.xyoye.player_component.ui.activities.player_intent.PlayerIntentActivity
import com.xyoye.player_component.ui.activities.player_intent.PlayerIntentViewModel
import com.xyoye.player_component.utils.PlayerErrorReporter
import com.xyoye.storage_component.services.ScreencastReceiveService
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(AndroidJUnit4::class)
class PlaybackEntriesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun smbAndFtpRequestsSurviveBrowserCloseAndReplaceThroughTheSession() =
        withFixture {
            val host = InstrumentationRegistry.getArguments().getString("leaseFixtureHost")
            org.junit.Assume.assumeTrue("Requires documented SMB/FTP wire fixtures", host != null)
            val libraries =
                listOf(
                    MediaLibraryEntity(
                        displayName = "handoff SMB fixture",
                        mediaType = MediaType.SMB_SERVER,
                        url = host!!,
                        port = 1445,
                        account = "lease",
                        password = "lease-fixture",
                        smbSharePath = "videos"
                    ),
                    MediaLibraryEntity(
                        displayName = "handoff FTP fixture",
                        mediaType = MediaType.FTP_SERVER,
                        url = "ftp://$host:2121",
                        ftpAddress = host,
                        port = 2121,
                        account = "lease",
                        password = "lease-fixture",
                        ftpEncoding = "UTF-8"
                    )
                )
            val dao = DatabaseProvider.instance.getMediaLibraryDao()
            val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
            libraries.forEach { input ->
                val previous = runBlocking { dao.getByUrl(input.url, input.mediaType) }
                assertNull("Fixture must not overwrite an existing library", previous)
                runBlocking { dao.insert(input) }
                val library = runBlocking { dao.getByUrl(input.url, input.mediaType)!! }
                val browser = requireNotNull(StorageFactory.createStorage(library))
                var historyKey: String? = null
                try {
                    val path = if (library.mediaType == MediaType.SMB_SERVER) "videos/playback-long.mp4" else "/playback-long.mp4"
                    val file = runBlocking { requireNotNull(browser.pathFile(path, false)) }
                    historyKey = file.uniqueKey()
                    val descriptor = PlaybackReferenceFactory.fromFile(file, StorageSort.NAME, false, true)
                    browser.close()

                    fun request() =
                        runBlocking {
                            provider.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.STORAGE_FILE))
                        } as PlaybackSubmissionResult.Accepted
                    ActivityScenario.launch<PlayerActivity>(playerIntent(request().requestId)).use { scenario ->
                        var activity: PlayerActivity? = null
                        scenario.onActivity { activity = it }
                        val first = awaitSource { activity }
                        assertEquals(library.mediaType, first.getMediaType())
                        val replacement = request()
                        scenario.onActivity { it.startActivity(playerIntent(replacement.requestId)) }
                        val second = awaitSource(first) { activity }
                        assertEquals(first.getUniqueKey(), second.getUniqueKey())
                        assertNotEquals(first.getVideoUrl(), second.getVideoUrl())
                        scenario.onActivity { assertTrue(player(it).isPlaying()) }
                    }
                } finally {
                    browser.close()
                    runBlocking {
                        dao.delete(library.url, library.mediaType)
                        DatabaseProvider.instance
                            .getPlayHistoryDao()
                            .getPlayHistory(
                                historyKey.orEmpty(),
                                library.id
                            )?.let { DatabaseProvider.instance.getPlayHistoryDao().delete(it.id) }
                    }
                }
            }
        }

    @Test fun localProjectionAndPlatformCommandsWorkWithoutGatewayResponses() =
        withFixture { fixture ->
            val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)

            fun submit(title: String) =
                runBlocking {
                    provider.submit(
                        PlaybackRequestInput(
                            ExternalUriDescriptor(fixture.toURI().toString(), Media3SourceType.STREAM, displayTitle = title),
                            PlaybackLaunchOptions(
                                "requested-id",
                                Media3SourceType.STREAM,
                                listOf(Media3Capability.CAST, Media3Capability.PIP, Media3Capability.BACKGROUND)
                            ),
                            PlaybackRequestOrigin.EXTERNAL_INTENT
                        )
                    ) as PlaybackSubmissionResult.Accepted
                }
            val connected = CompletableDeferred<Media3SessionClient>()
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?
                    ) {
                        connected.complete(service as Media3SessionClient)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
            assertTrue(context.bindService(Intent(context, Media3SessionService::class.java), connection, Context.BIND_AUTO_CREATE))
            try {
                val client = runBlocking { withTimeout(10000) { connected.await() } }
                ActivityScenario.launch<PlayerActivity>(playerIntent(submit("first projection").requestId)).use { scenario ->
                    var activity: PlayerActivity? = null
                    scenario.onActivity { activity = it }
                    val firstSource = awaitSource { activity }
                    val first =
                        runBlocking {
                            withTimeout(
                                10000
                            ) { provider.observePlayback().first { it?.state == Media3PlaybackState.PLAYING }!! }
                        }
                    assertEquals("first projection", first.source.displayTitle)
                    assertSame(first, client.playback().value)
                    assertTrue(Media3Capability.PAUSE in first.capabilities)
                    assertTrue(Media3Capability.SEEK in first.capabilities)
                    assertFalse(Media3Capability.CAST in first.capabilities)
                    assertFalse(Media3Capability.PIP in first.capabilities)
                    assertFalse(Media3Capability.BACKGROUND in first.capabilities)
                    assertEquals(
                        PlaybackOperationResult.Success,
                        runBlocking { client.dispatch(PlaybackCommand(first.version, PlaybackAction.Pause)) }
                    )
                    assertEquals(Media3PlaybackState.PAUSED, provider.observePlayback().value!!.state)
                    assertEquals(
                        PlaybackOperationResult.Success,
                        runBlocking { client.dispatch(PlaybackCommand(first.version, PlaybackAction.Seek(5000))) }
                    )
                    assertEquals(
                        PlaybackOperationResult.Success,
                        runBlocking { client.dispatch(PlaybackCommand(first.version, PlaybackAction.Play)) }
                    )
                    awaitSource { activity }
                    scenario.onActivity { assertTrue(player(it).getCurrentPosition() >= 4000) }
                    val secondRequest = submit("second projection")
                    scenario.onActivity { it.startActivity(playerIntent(secondRequest.requestId)) }
                    awaitSource(firstSource) { activity }
                    val second = provider.observePlayback().value!!
                    assertEquals("second projection", second.source.displayTitle)
                    assertTrue(second.version.sourceRevision > first.version.sourceRevision)
                    assertTrue(
                        runBlocking {
                            client.dispatch(
                                PlaybackCommand(first.version, PlaybackAction.Pause)
                            )
                        } is PlaybackOperationResult.Rejected
                    )
                    scenario.onActivity { assertTrue(player(it).isPlaying()) }
                    val diagnostics =
                        PlayerErrorReporter::class.java
                            .getDeclaredMethod("enrich", String::class.java)
                            .apply { isAccessible = true }
                            .invoke(PlayerErrorReporter, "fixture")
                            .toString()
                    assertTrue(diagnostics.contains(second.version.sessionId))
                    assertTrue(diagnostics.contains("sourceRevision=${second.version.sourceRevision}"))
                    assertEquals(
                        PlaybackOperationResult.Success,
                        runBlocking { client.dispatch(PlaybackCommand(second.version, PlaybackAction.Exit)) }
                    )
                    runBlocking { withTimeout(10000) { provider.observePlayback().first { it == null } } }
                    assertNull(client.playback().value)
                }
            } finally {
                context.unbindService(connection)
            }
        }

    @Test fun kernelRetryKeepsActiveSourcePositionAndGlobalPreference() =
        withFixture { fixture ->
            val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
            val result =
                runBlocking {
                    provider.submit(
                        PlaybackRequestInput(
                            com.xyoye.common_component.playback.handoff.ExternalUriDescriptor(
                                fixture.toURI().toString(),
                                com.xyoye.data_component.media3.entity.Media3SourceType.STREAM
                            ),
                            null,
                            PlaybackRequestOrigin.EXTERNAL_INTENT
                        )
                    )
                } as PlaybackSubmissionResult.Accepted
            ActivityScenario.launch<PlayerActivity>(playerIntent(result.requestId)).use { scenario ->
                var activity: PlayerActivity? = null
                scenario.onActivity { activity = it }
                val source = awaitSource { activity }
                var kernel: Any? = null
                var oldAdapter: Any? = null
                var model: PlayerViewModel? = null
                scenario.onActivity {
                    kernel = exoPlayer(it)
                    oldAdapter =
                        DanDanVideoPlayer::class.java
                            .getDeclaredField("mVideoPlayer")
                            .apply { isAccessible = true }
                            .get(player(it))
                    model = ViewModelProvider(it)[PlayerViewModel::class.java]
                    player(it).seekTo(5000)
                }
                val versionGetter = model!!.javaClass.methods.single { it.name.startsWith("getPlaybackVersion") }
                val before = versionGetter.invoke(model)
                val preference = PlayerConfig.getUsePlayerType()
                val fallback = model!!.javaClass.methods.single { it.name.startsWith("fallbackPlaybackKernel") }
                val applied =
                    runBlocking(Dispatchers.Main) {
                        suspendCoroutineUninterceptedOrReturn<Any> { continuation ->
                            fallback.invoke(
                                model,
                                PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.FALLBACK),
                                before,
                                continuation
                            )
                        }
                    }
                assertEquals("Activated", applied.javaClass.simpleName)
                assertSame(source, awaitSource { activity })
                scenario.onActivity {
                    assertNotSame(kernel, exoPlayer(it))
                    val oldListener =
                        AbstractVideoPlayer::class.java
                            .getDeclaredField("mPlayerEventListener")
                            .apply { isAccessible = true }
                            .get(oldAdapter) as VideoPlayerEventListener
                    assertSame(VideoPlayerEventListener.NO_OP, oldListener)
                    oldListener.onError(IllegalStateException("Delayed old kernel error"))
                    assertTrue("Retired kernel error interrupted playback", player(it).isPlaying())
                    assertTrue("Kernel retry lost the current position", player(it).getCurrentPosition() >= 4000)
                    assertEquals(before, versionGetter.invoke(model))
                    assertEquals(preference, PlayerConfig.getUsePlayerType())
                }
            }
        }

    @Test fun manualAndNativeNextKeepTheStableQueueAfterBrowserStateIsGone() =
        withFixture { fixture ->
            val firstFile = File(fixture.parentFile, fixture.nameWithoutExtension + "-01.mp4")
            val secondFile = File(fixture.parentFile, fixture.nameWithoutExtension + "-02.mp4")
            fixture.copyTo(firstFile)
            fixture.copyTo(secondFile)
            val oldAutoNext = PlayerConfig.isAutoPlayNext()
            val database = DatabaseProvider.instance
            val videos = listOf(firstFile, secondFile).map { VideoEntity(fileId = 0, filePath = it.path, folderPath = it.parent!!) }
            runBlocking { database.getVideoDao().insert(*videos.toTypedArray()) }
            try {
                listOf(false, true).forEach { native ->
                    PlayerConfig.putAutoPlayNext(native)
                    val browser = VideoStorage(MediaLibraryEntity.LOCAL)
                    val files = videos.map { VideoStorageFile(browser, it) }
                    browser.directoryFiles = files
                    val descriptor = PlaybackReferenceFactory.fromFile(files.first(), StorageSort.NAME, false, true)
                    val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                    val result = runBlocking { provider.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.STORAGE_FILE)) }
                    assertTrue(result is PlaybackSubmissionResult.Accepted)
                    browser.directoryFiles = emptyList()
                    browser.close()
                    val intent = playerIntent((result as PlaybackSubmissionResult.Accepted).requestId)
                    ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                        var activity: PlayerActivity? = null
                        scenario.onActivity { activity = it }
                        val first = awaitSource { activity }
                        assertEquals(2, first.getGroupSize())
                        var originalKernel: Any? = null
                        scenario.onActivity { originalKernel = exoPlayer(it) }
                        if (native) {
                            val deadline = SystemClock.elapsedRealtime() + 10000
                            var queued = false
                            while (!queued && SystemClock.elapsedRealtime() < deadline) {
                                scenario.onActivity {
                                    queued = Class
                                        .forName("androidx.media3.common.Player")
                                        .getMethod("getMediaItemCount")
                                        .invoke(exoPlayer(it)) == 2
                                }
                                if (!queued) SystemClock.sleep(50)
                            }
                            assertTrue("Session did not enqueue its stable next descriptor", queued)
                            scenario.onActivity { player(it).seekTo(player(it).getDuration() - 400) }
                        } else {
                            instrumentation.setInTouchMode(false)
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                            instrumentation.waitForIdleSync()
                            scenario.onActivity {
                                val next = it.findViewById<android.view.View>(com.xyoye.player_component.R.id.iv_next_source)
                                assertTrue(next.isShown)
                                assertTrue(next.isEnabled)
                                assertTrue(next.isClickable)
                                assertTrue(next.performClick())
                            }
                        }
                        val next = awaitSource(first) { activity }
                        assertEquals(files[1].uniqueKey(), next.getUniqueKey())
                        assertEquals(1, next.getGroupIndex())
                        assertEquals(2, next.getGroupSize())
                        assertFalse(next.hasNextSource())
                        scenario.onActivity {
                            if (native) {
                                assertSame("Native adoption restarted the kernel", originalKernel, exoPlayer(it))
                            } else {
                                assertNotSame(originalKernel, exoPlayer(it))
                            }
                        }
                    }
                }
            } finally {
                PlayerConfig.putAutoPlayNext(oldAutoNext)
                runBlocking {
                    videos.forEach { video ->
                        database.getVideoDao().deleteByPath(video.filePath)
                        val key = VideoStorageFile(VideoStorage(MediaLibraryEntity.LOCAL), video).uniqueKey()
                        database.getPlayHistoryDao().getPlayHistory(key, MediaLibraryEntity.LOCAL.id)?.let {
                            database.getPlayHistoryDao().delete(it.id)
                        }
                    }
                }
                firstFile.delete()
                secondFile.delete()
            }
        }

    @Test fun storageHistoryAnimeAndLinksSubmitRealRequestsWithIsolatedInputs() =
        withFixture { fixture ->
            val models = ViewModelStore()
            val video = VideoEntity(fileId = 0, filePath = fixture.path, folderPath = fixture.parent!!)
            val storage = VideoStorage(MediaLibraryEntity.LOCAL)
            val file = VideoStorageFile(storage, video)
            storage.directoryFiles = listOf(file)
            val database = DatabaseProvider.instance
            runBlocking {
                database.getVideoDao().insert(video)
                database.getPlayHistoryDao().insert(
                    PlayHistoryEntity(
                        videoName = fixture.name,
                        url = fixture.path,
                        mediaType = MediaType.LOCAL_STORAGE,
                        uniqueKey = file.uniqueKey(),
                        storagePath = fixture.path,
                        storageId = MediaLibraryEntity.LOCAL.id
                    )
                )
            }
            try {
                val storageModel = model(models, StorageFileViewModel::class.java)
                play(submit(storageModel.playEvents) { storageModel.playItem(file) }) { source ->
                    assertEquals(file.uniqueKey(), source.getUniqueKey())
                    assertEquals(MediaType.LOCAL_STORAGE, source.getMediaType())
                }

                fun history() =
                    runBlocking {
                        database.getPlayHistoryDao().getPlayHistory(file.uniqueKey(), MediaLibraryEntity.LOCAL.id)!!
                    }
                val historyModel = model(models, PlayHistoryViewModel::class.java)
                val saved = history()
                play(submit(historyModel.playEvents) { historyModel.openHistory(saved) }) { source ->
                    assertEquals(saved.uniqueKey, source.getUniqueKey())
                }
                val animeModel = model(models, AnimeEpisodeFragmentViewModel::class.java)
                val episode = EpisodeHistoryEntity(history().copy(episodeId = "entry-fixture-episode"), MediaLibraryEntity.LOCAL)
                play(submit(animeModel.playVideoFlow) { animeModel.playLocalEpisode(episode) }) { source ->
                    assertEquals(file.uniqueKey(), source.getUniqueKey())
                }
                val headers = mutableMapOf("X-Entry-Fixture" to "original")
                play(
                    submit(historyModel.playEvents) {
                        historyModel.openStreamLink(fixture.toURI().toString(), headers)
                        headers["X-Entry-Fixture"] = "mutated-after-click"
                    }
                ) { source ->
                    assertEquals(mapOf("X-Entry-Fixture" to "original"), source.getHttpHeader())
                }
                val intentModel = model(models, PlayerIntentViewModel::class.java)
                play(submit(intentModel.playEvents) { intentModel.openIntentUrl(fixture.toURI().toString()) }) { source ->
                    assertTrue("Prior entry headers leaked into a later request", source.getHttpHeader().isNullOrEmpty())
                }
            } finally {
                instrumentation.runOnMainSync { models.clear() }
                runBlocking {
                    database.getVideoDao().deleteByPath(fixture.path)
                    database.getPlayHistoryDao().getPlayHistory(file.uniqueKey(), MediaLibraryEntity.LOCAL.id)?.let {
                        database.getPlayHistoryDao().delete(it.id)
                    }
                }
            }
        }

    @Test fun externalIntentUsesIdOnlyNavigationAndReentersExistingPlayer() = verifyExternalIntentReentry(PlayerType.TYPE_EXO_PLAYER)

    @Test fun mpvExternalIntentReentersExistingPlayer() = verifyExternalIntentReentry(PlayerType.TYPE_MPV_PLAYER)

    private fun verifyExternalIntentReentry(kernel: PlayerType) =
        withFixture(kernel) { fixture ->
            val models = ViewModelStore()
            val viewModel = model(models, PlayerIntentViewModel::class.java)
            try {
                val request = submit(viewModel.playEvents) { viewModel.openIntentUrl(fixture.toURI().toString()) }
                ActivityScenario.launch<PlayerActivity>(playerIntent(request)).use { scenario ->
                    var activity: PlayerActivity? = null
                    scenario.onActivity { activity = it }
                    val before = awaitSource { activity }
                    scenario.onActivity {
                        it.startActivity(
                            Intent(it, PlayerIntentActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.fromFile(fixture), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                    awaitSource(before) { topPlayer() }
                    assertSame(activity, topPlayer())
                    instrumentation.runOnMainSync {
                        assertEquals(setOf(PlaybackNavigation.REQUEST_ID), activity!!.intent.extras!!.keySet())
                    }
                }
            } finally {
                instrumentation.runOnMainSync { models.clear() }
            }
        }

    @Test fun castReceiverUsesSameRequestRouteWhenIdleAndAlreadyPlaying() =
        withFixture { fixture ->
            val originalConfirm = ScreencastConfig.isReceiveNeedConfirm()
            val receiverPort = ServerSocket(0).use { it.localPort }
            val sender =
                object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response {
                        if (session.uri != "/video") return newFixedLengthResponse("{}")
                        val offset =
                            session.headers["range"]
                                ?.substringAfter("bytes=")
                                ?.substringBefore('-')
                                ?.toLongOrNull() ?: 0
                        val stream = fixture.inputStream()
                        stream.channel.position(offset)
                        val status = if (offset > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                        return newFixedLengthResponse(status, "video/mp4", stream, fixture.length() - offset).apply {
                            addHeader("Accept-Ranges", "bytes")
                            if (offset > 0) addHeader("Content-Range", "bytes $offset-${fixture.length() - 1}/${fixture.length()}")
                        }
                    }
                }
            sender.start()
            ScreencastConfig.putReceiveNeedConfirm(false)
            try {
                ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                    scenario.onActivity { ScreencastReceiveService.start(it, receiverPort, null) }
                    val readyDeadline = SystemClock.elapsedRealtime() + 5000
                    while (true) {
                        try {
                            java.net.Socket("127.0.0.1", receiverPort).close()
                            break
                        } catch (unready: java.io.IOException) {
                            if (SystemClock.elapsedRealtime() >= readyDeadline) throw unready
                            SystemClock.sleep(50)
                        }
                    }

                    fun cast(key: String) {
                        val connection = URL("http://127.0.0.1:$receiverPort/play").openConnection() as HttpURLConnection
                        try {
                            connection.requestMethod = "POST"
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.outputStream.bufferedWriter().use {
                                it.write(
                                    """{"port":${sender.listeningPort},"playUniqueKey":"$key","ip":"untrusted.invalid","relatedVideos":[{"title":"$key","uniqueKey":"$key"}],"httpHeader":{"X-Cast-Fixture":"kept"}}"""
                                )
                            }
                            assertEquals(200, connection.responseCode)
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            assertTrue("Cast endpoint rejected the fixture: $response", org.json.JSONObject(response).getBoolean("success"))
                        } finally {
                            connection.disconnect()
                        }
                    }
                    cast("idle-cast")
                    val first = awaitSource { topPlayer() }
                    val activity = topPlayer()
                    assertEquals("idle-cast", first.getUniqueKey())
                    assertEquals("127.0.0.1", Uri.parse(first.getVideoUrl()).host)
                    cast("foreground-cast")
                    val replacement = awaitSource(first) { topPlayer() }
                    assertSame("Foreground cast must reuse the existing player", activity, topPlayer())
                    assertEquals("foreground-cast", replacement.getUniqueKey())
                    assertEquals("kept", replacement.getHttpHeader()?.get("X-Cast-Fixture"))
                    instrumentation.runOnMainSync {
                        assertEquals(setOf(PlaybackNavigation.REQUEST_ID), activity!!.intent.extras!!.keySet())
                        activity.finish()
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    topPlayer()?.finish()
                    ScreencastReceiveService.stop(context)
                }
                ScreencastConfig.putReceiveNeedConfirm(originalConfirm)
                sender.stop()
            }
        }

    private fun <T : ViewModel> model(
        store: ViewModelStore,
        type: Class<T>
    ): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[type] }
        return result!!
    }

    private fun submit(
        outcomes: Flow<PlaybackSubmissionResult>,
        action: () -> Unit
    ): String {
        instrumentation.runOnMainSync(action)
        val result = runBlocking { withTimeout(10000) { outcomes.first() } }
        assertTrue("Entry failed to submit: $result", result is PlaybackSubmissionResult.Accepted)
        return (result as PlaybackSubmissionResult.Accepted).requestId
    }

    private fun playerIntent(id: String) = Intent(context, PlayerActivity::class.java).putExtra(PlaybackNavigation.REQUEST_ID, id)

    private fun play(
        id: String,
        check: (BaseVideoSource) -> Unit
    ) {
        ActivityScenario.launch<PlayerActivity>(playerIntent(id)).use { scenario ->
            var activity: PlayerActivity? = null
            scenario.onActivity { activity = it }
            check(awaitSource { activity })
        }
    }

    private fun topPlayer() = ActivityHelper.instance.getTopActivity() as? PlayerActivity

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer

    // Inspect the real internal kernel without adding a production API or an app ExoPlayer dependency.
    private fun exoPlayer(activity: PlayerActivity): Any =
        player(activity).let { player ->
            checkNotNull(
                player.javaClass.methods
                    .single { it.name.startsWith("exoPlayerOrNull") }
                    .invoke(player)
            )
        }

    private fun awaitSource(
        previous: BaseVideoSource? = null,
        activity: () -> PlayerActivity?
    ): BaseVideoSource {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var source: BaseVideoSource? = null
            instrumentation.runOnMainSync {
                val player =
                    activity()
                        ?.findViewById<FrameLayout>(
                            com.xyoye.player_component.R.id.player_container
                        )?.getChildAt(0) as? DanDanVideoPlayer
                if (player?.isPlaying() == true && player.getDuration() > 0) {
                    source = player.getVideoSource().takeUnless { it === previous }
                }
            }
            source?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Entry request did not reach actual playback")
    }

    private fun withFixture(
        kernel: PlayerType = PlayerType.TYPE_EXO_PLAYER,
        block: (File) -> Unit
    ) {
        val originalKernel = PlayerConfig.getUsePlayerType()
        val input = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue("Install the documented playback fixture", input.isFile)
        val fixture = File.createTempFile("handoff-entry-", ".mp4", context.cacheDir)
        input.copyTo(fixture, overwrite = true)
        PlayerConfig.putUsePlayerType(kernel.value)
        try {
            block(fixture)
        } finally {
            PlayerConfig.putUsePlayerType(originalKernel)
            fixture.delete()
        }
    }
}
