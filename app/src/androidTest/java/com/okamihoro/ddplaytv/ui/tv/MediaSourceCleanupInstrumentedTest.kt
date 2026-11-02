package com.okamihoro.ddplaytv.ui.tv

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.fragment.media.MediaViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSourceCleanupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: DatabaseInfo
    private lateinit var model: MediaViewModel
    private val store = ViewModelStore()
    private var visible = emptyList<MediaLibraryEntity>()
    private val observer = Observer<MutableList<MediaLibraryEntity>> { visible = it.toList() }

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(instrumentation.targetContext, DatabaseInfo::class.java)
                .allowMainThreadQueries()
                .build()
        DatabaseProvider.overrideProviderForTest { database }
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[MediaViewModel::class.java]
            model.mediaLibWithStatusLiveData.observeForever(observer)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            model.mediaLibWithStatusLiveData.removeObserver(observer)
            store.clear()
        }
        DatabaseProvider.overrideProviderForTest(null)
        database.close()
    }

    @Test
    fun repeatedInitializationCreatesOnlyTheRealLocalLibrary() {
        repeat(3) { initialize() }
        assertEquals(listOf(MediaLibraryEntity.LOCAL), runBlocking { database.getMediaLibraryDao().getAllSuspend() })
        assertNull(runBlocking { database.getMetadataDao().source(4) })
        instrumentation.runOnMainSync { assertEquals(listOf(MediaLibraryEntity.LOCAL), visible) }
    }

    @Test
    fun legacyVirtualSourcesAreHiddenWithoutChangingStoredSourcesOrHistory() =
        runBlocking {
            val excluded = setOf(MediaType.OTHER_STORAGE, MediaType.SCREEN_CAST, MediaType.REMOTE_STORAGE)
            val libraries =
                MediaType.entries.mapIndexed { index, type ->
                    when (type) {
                        MediaType.LOCAL_STORAGE -> MediaLibraryEntity.LOCAL.copy()
                        MediaType.OTHER_STORAGE -> MediaLibraryEntity(4, "播放历史", "saved-history-url", type)
                        else -> MediaLibraryEntity(100 + index, type.storageName, "source-cleanup-${type.value}", type)
                    }
                }
            val ftp = libraries.single { it.mediaType == MediaType.FTP_SERVER }
            val history =
                PlayHistoryEntity(
                    id = 71,
                    videoName = "Episode 3",
                    url = "ftp://fixture/episode3.mkv",
                    mediaType = ftp.mediaType,
                    videoPosition = 8400,
                    videoDuration = 100000,
                    uniqueKey = "source-cleanup-episode3",
                    storageId = ftp.id,
                    storagePath = "/episode3.mkv",
                    danmuPath = "/saved.xml",
                    subtitlePath = "/saved.ass"
                )
            val dao = database.getMediaLibraryDao()
            dao.insert(*libraries.toTypedArray())
            database.getPlayHistoryDao().insert(history)
            val original = dao.getAllSuspend()

            repeat(3) { initialize() }

            instrumentation.runOnMainSync {
                assertEquals(
                    libraries.filterNot { it.mediaType in excluded }.sortedBy { it.id },
                    visible
                )
                assertEquals(9, visible.size)
            }
            assertEquals(original, dao.getAllSuspend())
            assertEquals(history, database.getPlayHistoryDao().getById(history.id))
            assertNotNull(dao.getByMediaTypeSuspend(MediaType.SCREEN_CAST).singleOrNull())
        }

    private fun initialize() {
        var jobs = emptyList<Job>()
        instrumentation.runOnMainSync {
            model.initLocalStorage()
            val scopeJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
            jobs = scopeJob.children.toList()
        }
        runBlocking { withTimeout(10000) { jobs.joinAll() } }
        instrumentation.waitForIdleSync()
    }
}
