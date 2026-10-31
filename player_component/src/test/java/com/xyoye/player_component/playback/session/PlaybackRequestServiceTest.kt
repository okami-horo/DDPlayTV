package com.xyoye.player_component.playback.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.PlaybackCastVideo
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.source.handoff.PlaybackSourceResolver
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.player_component.service.PlaybackRequestServiceImpl
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [PlaybackRequestServiceTest.DefaultPreferences::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackRequestServiceTest {
    private lateinit var database: DatabaseInfo

    @Before fun setUpDatabase() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DatabaseInfo::class.java)
                .allowMainThreadQueries()
                .build()
        DatabaseProvider.overrideProviderForTest { database }
    }

    @After fun closeDatabase() {
        DatabaseProvider.overrideProviderForTest(null)
        database.close()
    }

    @Test fun actualProviderResolverAndFactoryKeepSourceAndOptionsAtomicAndConsumeOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val provider = PlaybackRequestServiceImpl(f.store) { PlaybackSourceResolver().resolve(it) }
            val session = provider.createSession(f.backend, dispatcher, dispatcher)
            val first =
                provider.submit(
                    PlaybackRequestInput(
                        f.descriptor("abandoned"),
                        PlaybackLaunchOptions("abandoned", Media3SourceType.STREAM, autoplay = false),
                        PlaybackRequestOrigin.STORAGE_FILE
                    )
                ) as PlaybackSubmissionResult.Accepted
            val headers = mutableMapOf("Authorization" to "fixture-header")
            val last =
                provider.submit(
                    PlaybackRequestInput(
                        ScreencastDescriptor("example.test", 8080, listOf(PlaybackCastVideo("current", "current")), "current", headers),
                        null,
                        PlaybackRequestOrigin.STORAGE_FILE
                    )
                ) as PlaybackSubmissionResult.Accepted
            headers.clear()
            assertEquals(
                PlaybackPreparationFailure.RequestSuperseded,
                (session.deliver(first.requestId) as SessionRequestResult.Rejected).failure
            )
            assertTrue(session.deliver(last.requestId) is SessionRequestResult.Activated)
            assertEquals("http://example.test:8080/video?uniqueKey=current", f.backend.current!!.getVideoUrl())
            assertEquals("fixture-header", f.backend.current!!.getHttpHeader()!!["Authorization"])
            assertNull(session.runtime!!.playback.launchOptions)
            assertTrue(session.runtime!!.playback.playWhenReady)
            assertEquals(SessionRequestResult.Ignored, session.deliver(last.requestId))
            assertEquals(listOf("current"), f.backend.applied)
            session.exit()
        }

    @Test fun failedSubmissionReturnsNoNavigationIdAndCreatesNoSource() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val provider =
                PlaybackRequestServiceImpl(f.store) {
                    fail("Submission must not acquire resources")
                    f.resolve(it)
                }
            f.blob.failWrite = true
            val result = provider.submit(PlaybackRequestInput(f.descriptor("failed"), null, PlaybackRequestOrigin.STORAGE_FILE))
            assertTrue(result is PlaybackSubmissionResult.Rejected)
            assertTrue(f.leases.isEmpty())
            assertNull(f.blob.data)
        }

    @Implements(value = MMKV::class, isInAndroidSdk = false)
    class DefaultPreferences {
        @Implementation fun decodeString(key: String): String? = null

        @Implementation fun decodeString(
            key: String,
            defaultValue: String?
        ): String? = defaultValue

        @Implementation fun decodeBool(
            key: String,
            defaultValue: Boolean
        ): Boolean = defaultValue

        @Implementation fun decodeInt(
            key: String,
            defaultValue: Int
        ): Int = defaultValue

        companion object {
            @JvmStatic @Implementation
            fun defaultMMKV(): MMKV = Shadow.newInstanceOf(MMKV::class.java)

            @JvmStatic @Implementation
            fun mmkvWithID(id: String): MMKV = Shadow.newInstanceOf(MMKV::class.java)
        }
    }
}
