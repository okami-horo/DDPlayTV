package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.playback.BilibiliLivePlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliLivePlaybackSessionStore
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.playback.addon.PlaybackReplacementIntent
import com.xyoye.common_component.playback.addon.PlaybackReplacementOperation
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.file.impl.BilibiliStorageFile
import com.xyoye.common_component.storage.impl.BilibiliStorage
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BilibiliPlaybackTransportTest {
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val sessions = mutableListOf<BilibiliPlaybackSession>()
    private val liveSessions = mutableListOf<BilibiliLivePlaybackSession>()
    private var prepare: suspend () -> String = { "https://example.test/video" }
    private val storage by lazy {
        BilibiliStorage(
            MediaLibraryEntity(id = 7, displayName = "Bilibili", url = "bilibili://test", mediaType = MediaType.BILIBILI_STORAGE),
            {
                sessions.add(it)
                prepare()
            },
            {
                liveSessions.add(it)
                prepare()
            }
        )
    }
    private val factory = PlaybackTransportFactory()
    private val leases = mutableListOf<PlaybackTransportLease>()

    @After
    fun cleanup() {
        leases.forEach { it.release() }
        BilibiliPlaybackSessionStore.clear()
        BilibiliLivePlaybackSessionStore.clear()
    }

    private fun file(key: String) = BilibiliStorageFile(storage, key, "video", false, key, playable = true)

    private suspend fun acquire(key: String): BilibiliPlaybackTransport {
        val result = factory.acquire(storage, file(key), profile) as TransportAcquisitionResult.Success
        return (result.transport.lease as BilibiliPlaybackTransport).also { leases.add(it) }
    }

    @Test fun recoveryAcquiresIndependentTokenAndLeavesOriginalCdnSelectionUntouched() =
        runBlocking {
            for (key in listOf(REGULAR, LIVE)) {
                val original = acquire(key)
                val active: Any = if (key == REGULAR) sessions.last() else liveSessions.last()
                val urls = listOf("https://first.test/video", "https://second.test/video")
                val urlsField =
                    active.javaClass
                        .getDeclaredField(
                            if (key ==
                                REGULAR
                            ) {
                                "selectedDurlCandidates"
                            } else {
                                "urlCandidates"
                            }
                        ).apply { isAccessible = true }
                val indexField =
                    active.javaClass
                        .getDeclaredField(
                            if (key ==
                                REGULAR
                            ) {
                                "selectedDurlIndex"
                            } else {
                                "selectedIndex"
                            }
                        ).apply { isAccessible = true }
                urlsField.set(active, urls)
                indexField.setInt(active, 0)
                val identity = PlaybackIdentity(7, key, MediaType.BILIBILI_STORAGE, key, "fixture", urls.first())
                val intent =
                    PlaybackReplacementIntent(
                        identity,
                        original.sessionToken,
                        PlaybackReplacementOperation.Recover(urls.first(), null, false, 42)
                    )
                val prepared = factory.acquire(storage, file(key), profile, intent) as TransportAcquisitionResult.Success
                val candidate = (prepared.transport.lease as BilibiliPlaybackTransport).also(leases::add)
                assertNotEquals(original.sessionToken, candidate.sessionToken)
                assertEquals(urls[1], candidate.playUrl)
                assertEquals(0, indexField.getInt(active))
                candidate.release()
                val remaining: Any? =
                    if (key == REGULAR) {
                        BilibiliPlaybackSessionStore.get(7, key, original.sessionToken)
                    } else {
                        BilibiliLivePlaybackSessionStore.get(7, key, original.sessionToken)
                    }
                assertSame(active, remaining)
                assertFalse(original.isReleased)
            }
        }

    @Test fun replacementTokenMustMatchResolvedIdentityAndMustStillExist() =
        runBlocking {
            val original = acquire(REGULAR)
            val identity = PlaybackIdentity(7, LIVE, MediaType.BILIBILI_STORAGE, LIVE, "fixture", "https://test/video")
            val wrong =
                PlaybackReplacementIntent(identity, original.sessionToken, PlaybackReplacementOperation.Recover(null, null, false, 0))
            assertTrue(factory.acquire(storage, file(REGULAR), profile, wrong) is TransportAcquisitionResult.Failure)
            original.release()
            val stale = PlaybackReplacementIntent(identity.copy(uniqueKey = REGULAR), original.sessionToken, wrong.operation)
            assertTrue(factory.acquire(storage, file(REGULAR), profile, stale) is TransportAcquisitionResult.Failure)
        }

    @Test
    fun regularLeasesKeepTwoSameIdentitySessionsAlive() =
        runBlocking {
            val first = acquire(REGULAR)
            val second = acquire(REGULAR)
            assertNotEquals(first.sessionToken, second.sessionToken)
            assertSame(sessions[0], BilibiliPlaybackSessionStore.get(7, REGULAR, first.sessionToken))
            first.release()
            first.release()
            assertNull(BilibiliPlaybackSessionStore.get(7, REGULAR, first.sessionToken))
            assertSame(sessions[1], BilibiliPlaybackSessionStore.get(7, REGULAR, second.sessionToken))
        }

    @Test
    fun liveLeaseCanBeRetiredBeforeAddonConstruction() =
        runBlocking {
            val first = acquire(LIVE)
            val second = acquire(LIVE)
            second.release()
            assertSame(liveSessions[0], BilibiliLivePlaybackSessionStore.get(7, LIVE, first.sessionToken))
            assertNull(BilibiliLivePlaybackSessionStore.get(7, LIVE, second.sessionToken))
        }

    @Test
    fun sourceOwnsLeaseAndAddonOnlyCleanupPreservesBothTokens() =
        runBlocking {
            val first = acquire(LIVE)
            val source =
                StorageVideoSource(
                    first.playUrl,
                    file(LIVE),
                    listOf(file(LIVE)),
                    null,
                    null,
                    null,
                    profile,
                    first,
                    httpHeaders = emptyMap()
                )
            val second = acquire(LIVE)
            (source.getPlaybackAddon() as PlaybackReleasableAddon).onRelease()
            assertNotNull(BilibiliLivePlaybackSessionStore.get(7, LIVE, first.sessionToken))
            source.releasePlaybackResources()
            source.releasePlaybackResources()
            assertNull(BilibiliLivePlaybackSessionStore.get(7, LIVE, first.sessionToken))
            assertSame(liveSessions[1], BilibiliLivePlaybackSessionStore.get(7, LIVE, second.sessionToken))
        }

    @Test
    fun failedPreparationPreservesActiveSessionAndOriginalRiskError() =
        runBlocking {
            val first = acquire(REGULAR)
            val error = BilibiliException(-352, "risk control voucher=fixture")
            prepare = { throw error }
            val result = factory.acquire(storage, file(REGULAR), profile) as TransportAcquisitionResult.Failure
            assertSame(error, result.cause)
            assertSame(sessions[0], BilibiliPlaybackSessionStore.get(7, REGULAR, first.sessionToken))
            assertRetired(sessions[1])
        }

    @Test
    fun cancellationDuringPreparationRetiresOnlyCandidate() =
        runBlocking {
            val first = acquire(REGULAR)
            val started = CompletableDeferred<Unit>()
            prepare = {
                started.complete(Unit)
                awaitCancellation()
            }
            val pending = launch { factory.acquire(storage, file(REGULAR), profile) }
            started.await()
            pending.cancelAndJoin()
            assertSame(sessions[0], BilibiliPlaybackSessionStore.get(7, REGULAR, first.sessionToken))
            assertRetired(sessions[1])
        }

    @Test
    fun blankPreparedUrlDoesNotLeaveAnOwnedSession() =
        runBlocking {
            prepare = { "" }
            assertTrue(factory.acquire(storage, file(REGULAR), profile) is TransportAcquisitionResult.Failure)
            assertRetired(sessions.single())
        }

    private suspend fun assertRetired(session: BilibiliPlaybackSession) {
        try {
            session.prepare()
            fail("Retired candidate accepted preparation")
        } catch (_: CancellationException) {
        }
    }

    companion object {
        private const val LIVE = "bilibili://live/1"
        private const val REGULAR = "bilibili://archive/BVtest?cid=1"
    }
}
