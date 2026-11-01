package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.metadata.MetadataCardAdapter
import com.xyoye.local_component.ui.metadata.MetadataLibraryActivity
import com.xyoye.local_component.ui.metadata.MetadataMatchActivity
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import com.xyoye.user_component.ui.activities.metadata.MetadataSettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileBrowserCompatibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance
    private val library = MediaLibraryEntity(990012, "菜单兼容验收", "content://file-menu-fixture", MediaType.LOCAL_STORAGE)

    @Test fun scanToUnmatchedRemainsReachableFromTheLibrary() =
        fixture { file ->
            ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
                lateinit var home: TvMainActivity
                scenario.onActivity { home = it }
                await { home.hasWindowFocus() }
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { home.findViewById<View>(com.xyoye.local_component.R.id.show_all)?.isShown == true }
                scenario.onActivity {
                    it.startActivity(Intent(it, StorageFileActivity::class.java).putExtra("storageLibrary", library))
                }
                await { ActivityHelper.instance.getTopActivity() is StorageFileActivity }
                val browser = ActivityHelper.instance.getTopActivity() as StorageFileActivity

                fun list(): DpadRecyclerView =
                    browser.supportFragmentManager.fragments
                        .filterIsInstance<StorageFileFragment>()
                        .last { !it.isHidden }
                        .requireView()
                        .findViewById(R.id.storage_file_rv)

                fun files() = (list().adapter as BaseAdapter).items.filterIsInstance<StorageFile>()
                await { files().any { it.filePath() == file.parent } && browser.hasWindowFocus() }
                instrumentation.runOnMainSync {
                    list().setSelectedPosition(files().indexOfFirst { it.filePath() == file.parent })
                    list().requestFocus()
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { files().any { it.fileName() == file.name } }
                assertTrue(runBlocking { db.getMetadataDao().directoryAssets(library.id, 1, file.parent!!).isEmpty() })
                instrumentation.runOnMainSync { browser.findViewById<View>(R.id.browser_tools).requestFocus() }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                var confirmation = ""
                await {
                    confirmation =
                        instrumentation.uiAutomation.rootInActiveWindow
                            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?.text
                            ?.toString()
                            .orEmpty()
                    confirmation == "取消" || confirmation == "开始扫描"
                }
                if (confirmation == "取消") send(KeyEvent.KEYCODE_DPAD_RIGHT)
                await {
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?.text
                        ?.toString() == "开始扫描"
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { ActivityHelper.instance.getTopActivity() is MetadataSettingsActivity }
                await {
                    runBlocking {
                        db
                            .getMetadataDao()
                            .observeScans()
                            .first()
                            .any { it.libraryId == library.id && it.state == "COMPLETED" }
                    }
                }
                val asset = runBlocking { db.getMetadataDao().directoryAssets(library.id, 1, file.parent!!).single() }
                assertEquals(file.name, asset.fileName)
                assertEquals("NEEDS_CONFIRMATION", runBlocking { db.getMetadataDao().binding(asset.assetId)?.state })
                send(KeyEvent.KEYCODE_BACK)
                await { browser.hasWindowFocus() }
                instrumentation.runOnMainSync { browser.findViewById<View>(R.id.browser_nav_1).requestFocus() }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { home.hasWindowFocus() }
                scenario.onActivity { it.findViewById<View>(com.xyoye.local_component.R.id.show_all).requestFocus() }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { ActivityHelper.instance.getTopActivity() is MetadataLibraryActivity }
                val all = ActivityHelper.instance.getTopActivity() as MetadataLibraryActivity
                await { all.hasWindowFocus() }
                instrumentation.runOnMainSync { all.findViewById<View>(com.xyoye.local_component.R.id.unmatched).requestFocus() }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                val items = all.findViewById<DpadRecyclerView>(com.xyoye.local_component.R.id.items)
                await { (items.adapter as MetadataCardAdapter).currentList.any { it.key == "asset:${asset.assetId}" } }
                instrumentation.runOnMainSync {
                    items.setSelectedPosition(
                        (items.adapter as MetadataCardAdapter).currentList.indexOfFirst {
                            it.key ==
                                "asset:${asset.assetId}"
                        }
                    )
                    items.requestFocus()
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await { ActivityHelper.instance.getTopActivity() is MetadataMatchActivity }
                assertEquals(
                    asset.assetId,
                    ActivityHelper.instance
                        .getTopActivity()!!
                        .intent
                        .getLongExtra("assetId", 0)
                )
                send(KeyEvent.KEYCODE_BACK)
                await { all.hasWindowFocus() }
                send(KeyEvent.KEYCODE_BACK)
                await { home.hasWindowFocus() }
            }
        }

    @Test fun savedResourcesKeepPriorityAndSameDirectoryDiscoveryStillWorks() =
        fixture { file ->
            val danmuEnabled = DanmuConfig.isAutoLoadSameNameDanmu()
            val subtitleEnabled = SubtitleConfig.isAutoLoadSameNameSubtitle()
            DanmuConfig.putAutoLoadSameNameDanmu(true)
            SubtitleConfig.putAutoLoadSameNameSubtitle(true)
            try {
                val sameDanmu = File(file.parentFile, "${file.nameWithoutExtension}.xml").apply { writeText("<i/>") }
                val sameSubtitle =
                    File(
                        file.parentFile,
                        "${file.nameWithoutExtension}.srt"
                    ).apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nsame") }
                val savedDanmu = File(file.parentFile, "saved.xml").apply { writeText("<i/>") }
                val savedSubtitle = File(file.parentFile, "saved.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nsaved") }
                val savedAudio = File(file.parentFile, "saved.aac").apply { writeBytes(byteArrayOf(1)) }
                runBlocking {
                    val history =
                        PlayHistoryEntity(
                            videoName = file.name,
                            url = file.path,
                            mediaType = library.mediaType,
                            uniqueKey = file.path.toMd5String(),
                            storageId = library.id,
                            storagePath = file.path,
                            danmuPath = savedDanmu.path,
                            episodeId = "1234",
                            subtitlePath = savedSubtitle.path,
                            audioPath = savedAudio.path
                        )
                    db.getPlayHistoryDao().insert(history)
                    val storage = requireNotNull(StorageFactory.createStorage(library))
                    try {
                        val restored = requireNotNull(db.getPlayHistoryDao().getPlayHistory(history.uniqueKey, library.id))
                        val input = requireNotNull(storage.historyFile(restored))
                        val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
                        val source = requireNotNull(StorageVideoSourceFactory.create(input, profile, listOf(input)))
                        try {
                            assertEquals(savedDanmu.path, source.getDanmu()?.danmuPath)
                            assertEquals("1234", source.getDanmu()?.episodeId)
                            assertEquals(savedSubtitle.path, source.getSubtitlePath())
                            assertEquals(savedAudio.path, source.getAudioPath())
                        } finally {
                            source.releasePlaybackResources()
                        }
                        val unbound = requireNotNull(storage.pathFile(file.path, false))
                        val discovered = requireNotNull(StorageVideoSourceFactory.create(unbound, profile, listOf(unbound)))
                        try {
                            assertEquals(sameDanmu.path, discovered.getDanmu()?.danmuPath)
                            assertEquals(sameSubtitle.path, discovered.getSubtitlePath())
                            assertNull(discovered.getAudioPath())
                        } finally {
                            discovered.releasePlaybackResources()
                        }
                        assertEquals(savedDanmu.path, db.getPlayHistoryDao().getPlayHistory(history.uniqueKey, library.id)?.danmuPath)
                    } finally {
                        storage.close()
                    }
                }
            } finally {
                DanmuConfig.putAutoLoadSameNameDanmu(danmuEnabled)
                SubtitleConfig.putAutoLoadSameNameSubtitle(subtitleEnabled)
            }
        }

    private fun fixture(block: (File) -> Unit) {
        instrumentation.setInTouchMode(false)
        val directory = File(context.getExternalFilesDir(null), "file-menu-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "menu trailer.mp4").apply { writeBytes(byteArrayOf(1)) }
        runBlocking {
            db.getMediaLibraryDao().insert(library)
            db.getVideoDao().insert(VideoEntity(fileId = 0, filePath = file.path, folderPath = directory.path, fileLength = file.length()))
        }
        try {
            block(file)
        } finally {
            runBlocking {
                db.getPlayHistoryDao().deleteByStorageId(library.id)
                db.getVideoDao().deleteByPath(file.path)
                MediaLibraryRepository.delete(library.url, library.mediaType)
            }
            directory.deleteRecursively()
        }
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { ready = runCatching(condition).getOrDefault(false) }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Expected browser/metadata state", ready)
    }
}
