package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BilibiliPlaybackSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @After
    fun clearSessions() {
        BilibiliPlaybackSessionStore.clear()
        BilibiliLivePlaybackSessionStore.clear()
    }

    private fun live() = BilibiliLivePlaybackSession(7, LIVE_KEY, "test", BilibiliRepository("test"), 1)

    private fun regular() =
        BilibiliPlaybackSession(
            7,
            REGULAR_KEY,
            "test",
            BilibiliRepository("test"),
            BilibiliKeys.ArchiveKey("BVtest", 1),
            PlayerType.TYPE_EXO_PLAYER
        )

    @Test
    fun releasingOldAddonMustNotDeleteEitherSessionWithSameIdentity() =
        runTest {
            val first = live()
            val firstToken = BilibiliLivePlaybackSessionStore.put(first)
            val addon =
                BilibiliPlaybackAddon(
                    PlaybackIdentity(7, LIVE_KEY, MediaType.BILIBILI_STORAGE, LIVE_KEY, "test", "https://test/video"),
                    firstToken
                )
            val second = live()
            val secondToken = BilibiliLivePlaybackSessionStore.put(second)
            addon.onRelease()
            addon.onRelease()
            assertNotEquals(firstToken, secondToken)
            assertSame(first, BilibiliLivePlaybackSessionStore.get(7, LIVE_KEY, firstToken))
            assertSame(second, BilibiliLivePlaybackSessionStore.get(7, LIVE_KEY, secondToken))
            try {
                addon.getSettingSpec()
                fail("A retired Addon must reject new work")
            } catch (_: CancellationException) {
                // Its caller is still active.
            }
        }

    @Test
    fun liveRetirementIsTokenSpecificAndIdempotent() {
        val first = BilibiliLivePlaybackSessionStore.put(live())
        val second = live()
        val token = BilibiliLivePlaybackSessionStore.put(second)
        BilibiliLivePlaybackSessionStore.remove(7, LIVE_KEY, first)
        BilibiliLivePlaybackSessionStore.remove(7, LIVE_KEY, first)
        assertNull(BilibiliLivePlaybackSessionStore.get(7, LIVE_KEY, first))
        assertSame(second, BilibiliLivePlaybackSessionStore.get(7, LIVE_KEY, token))
        assertNull(BilibiliLivePlaybackSessionStore.get(8, LIVE_KEY, token))
    }

    @Test
    fun regularRetirementPreservesOtherGenerationAndPreventsReuse() =
        runTest {
            val first = regular()
            val firstToken = BilibiliPlaybackSessionStore.put(first)
            val second = regular()
            val secondToken = BilibiliPlaybackSessionStore.put(second)
            assertSame(first, BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, firstToken))
            BilibiliPlaybackSessionStore.remove(7, REGULAR_KEY, firstToken)
            assertSame(second, BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, secondToken))
            assertNull(BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, firstToken))
            try {
                first.prepare()
                fail("Removed sessions cannot start network work")
            } catch (_: CancellationException) {
            }
        }

    @Test
    fun accountCleanupExplicitlyRemovesAllItsGenerations() {
        val first = BilibiliPlaybackSessionStore.put(regular())
        val second = BilibiliPlaybackSessionStore.put(regular())
        BilibiliPlaybackSessionStore.clearStorage(8)
        assertNotNull(BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, first))
        BilibiliPlaybackSessionStore.clearStorage(7)
        assertNull(BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, first))
        assertNull(BilibiliPlaybackSessionStore.get(7, REGULAR_KEY, second))
    }

    @Test
    fun manifestPathsAreIndependentAndRetiredWritesCannotRecreateFiles() {
        val first = BilibiliSessionManifest { temporary.root }
        val second = BilibiliSessionManifest { temporary.root }
        val firstPath = first.write { it.writeText("first") }
        val secondPath = second.write { it.writeText("second") }
        assertNotEquals(firstPath, secondPath)
        first.close()
        assertFalse(File(firstPath).exists())
        assertEquals("second", File(secondPath).readText())
        try {
            first.write { it.writeText("late response") }
            fail("Retired manifest accepted a write")
        } catch (_: CancellationException) {
        }
        assertFalse(File(firstPath).exists())
        second.close()
    }

    @Test
    fun ownerCancellationCancelsInflightChildWithoutCancellingCaller() =
        runTest {
            val owner = BilibiliPlaybackWork()
            val pending = async { owner.run { awaitCancellation() } }
            runCurrent()
            owner.close()
            runCurrent()
            assertTrue(pending.isCancelled)
            assertEquals("caller alive", async { "caller alive" }.await())
        }

    @Test
    fun heartbeatRetirementCancelsOnlyOldGenerationAndRejectsLateAdmission() =
        runTest {
            val first = BilibiliPlaybackSessionStore.Key(7, REGULAR_KEY, "first")
            val second = first.copy(token = "second")
            val active = mutableSetOf(first, second)
            val started = mutableListOf<String>()
            val cancelled = mutableListOf<String>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val dispatcher =
                    BilibiliHeartbeatDispatcher(scope, active::contains) { key, _, _ ->
                        started.add(key.token)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.add(key.token)
                        }
                    }
                dispatcher.send(first, 1, 10000)
                dispatcher.send(second, 1, 10000)
                runCurrent()
                assertEquals(listOf("first", "second"), started)
                active.remove(first)
                dispatcher.clear(first)
                runCurrent()
                assertEquals(listOf("first"), cancelled)
                dispatcher.send(first, 5, 20000)
                dispatcher.send(second, 5, 20000)
                runCurrent()
                assertEquals(listOf("first", "second", "second"), started)
                dispatcher.clear(second)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun retirementRejectsLateNonCancellableResult() =
        runTest {
            val owner = BilibiliPlaybackWork()
            val resume = CompletableDeferred<Unit>()
            val pending =
                async {
                    owner.run {
                        withContext(NonCancellable) { resume.await() }
                        "stale result"
                    }
                }
            runCurrent()
            owner.close()
            resume.complete(Unit)
            runCurrent()
            assertTrue(pending.isCancelled)
        }

    companion object {
        private const val LIVE_KEY = "bilibili://live/1"
        private const val REGULAR_KEY = "bilibili://archive/BVtest?cid=1"
    }
}
