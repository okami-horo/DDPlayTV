package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.R
import com.xyoye.local_component.ui.metadata.MetadataCardAdapter
import com.xyoye.local_component.ui.metadata.MetadataLibraryActivity
import com.xyoye.local_component.ui.metadata.MetadataMatchActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataAutoFocusInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = DatabaseProvider.instance

    @Test fun cancelledUserTaskDeliversFailureSoThePageCanRetry() {
        val started = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var category: String? = null
        val job =
            com.xyoye.common_component.metadata.MetadataCoordinator.submit({
                started.countDown()
                kotlinx.coroutines.awaitCancellation()
            }) { result ->
                category = result.exceptionOrNull()?.let(com.xyoye.common_component.metadata.MetadataCoordinator::failureCategory)
                finished.countDown()
            }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("INTERRUPTED", category)
    }

    @Test fun unresolvedLongFileCanReturnAndKeepFocusWhenItsWorkArrives() {
        instrumentation.setInTouchMode(false)
        val library = MediaLibraryEntity(991052, "焦点验收", "content://metadata-focus", MediaType.LOCAL_STORAGE)
        val name = "长文件名与作品信息 ".repeat(12) + "S01E03.mkv"
        val dao = db.getMetadataDao()
        val assetId =
            runBlocking {
                db.getMediaLibraryDao().insert(library)
                dao.putSource(CatalogSourceState(library.id))
                dao
                    .putAsset(
                        MediaAsset(
                            libraryId = library.id,
                            contentGeneration = 1,
                            locatorKey = "focus",
                            uniqueKey = "focus",
                            storagePath = "/fixture/long.mkv",
                            fileName = name,
                            parentPath = "/fixture",
                            discoveredAt = System.currentTimeMillis()
                        )
                    ).also {
                        dao.putBinding(MediaBinding(it, failure = "AMBIGUOUS"))
                    }
            }
        try {
            ActivityScenario.launch<MetadataLibraryActivity>(Intent(context, MetadataLibraryActivity::class.java)).use { scenario ->
                lateinit var page: MetadataLibraryActivity
                scenario.onActivity { page = it }
                await("Library default focus") { page.hasWindowFocus() && page.currentFocus?.id == R.id.all }
                repeat(3) { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Unresolved asset listed") {
                    (page.findViewById<DpadRecyclerView>(R.id.items).adapter as MetadataCardAdapter).currentList.firstOrNull()?.key ==
                        "asset:$assetId"
                }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Problem page") { ActivityHelper.instance.getTopActivity() is MetadataMatchActivity }
                val problem = ActivityHelper.instance.getTopActivity() as MetadataMatchActivity
                await("Ambiguity explained without candidates") { problem.findViewById<TextView>(R.id.advice).text.startsWith("找到多个同名作品") }
                assertEquals("Retry owns initial focus", R.id.retry, problem.currentFocus?.id)
                screenshot("unresolved-long")
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertEquals("Disabled refresh must not take focus", R.id.retry, problem.currentFocus?.id)
                send(KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals(R.id.retry, problem.currentFocus?.id)
                send(KeyEvent.KEYCODE_BACK)
                await("Returns to unresolved file") { page.hasWindowFocus() && page.findViewById<DpadRecyclerView>(R.id.items).hasFocus() }
                runBlocking {
                    dao.putWork(
                        MetadataWork("tv", 991052, "Focus Fixture", "Focus Fixture", "", "2024", "", 0.0, null, null, "zh-CN", "", 0, 1)
                    )
                    dao.putBinding(MediaBinding(assetId, "MATCHED", "tv", 991052))
                }
                await("Focused asset maps to the new work card") {
                    val list = page.findViewById<DpadRecyclerView>(R.id.items)
                    list.hasFocus() &&
                        (list.adapter as MetadataCardAdapter).currentList.getOrNull(list.getSelectedPosition())?.key == "tv:991052"
                }
                screenshot("resolved-focus")
                send(KeyEvent.KEYCODE_BACK)
            }
        } finally {
            runBlocking {
                MediaLibraryRepository.delete(library.url, library.mediaType)
                db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_work WHERE tmdbId = 991052")
            }
        }
    }

    @Test fun missingAssetCanReturnWithRemoteWithoutStartingRecognition() {
        instrumentation.setInTouchMode(false)
        ActivityScenario
            .launch<MetadataMatchActivity>(
                Intent(context, MetadataMatchActivity::class.java).putExtra("assetId", Long.MAX_VALUE)
            ).use { scenario ->
                lateinit var page: MetadataMatchActivity
                scenario.onActivity { page = it }
                await("Empty state") { page.hasWindowFocus() && page.findViewById<TextView>(R.id.status).text == "尚无文件资料" }
                assertEquals(R.id.status_panel, page.currentFocus?.id)
                screenshot("empty")
                send(KeyEvent.KEYCODE_BACK)
                await("Empty page exits with BACK") { page.isDestroyed }
            }
    }

    @Test fun taskSummaryRemainsVisibleWhenFailureDetailsArrive() {
        instrumentation.setInTouchMode(false)
        val run =
            com.xyoye.data_component.entity.MetadataScanRun(
                "auto-metadata-layout",
                991052,
                1,
                1,
                "/fixture",
                false,
                "COMPLETED",
                discovered = 12,
                matched = 12,
                startedAt = System.currentTimeMillis(),
                triggerKey = "layout",
                triggerPath = "/fixture/Auto Show S01E03.mkv",
                triggerName = "Auto Show S01E03.mkv"
            )
        runBlocking { db.getMetadataDao().putScan(run) }
        try {
            val intent = Intent(context, com.xyoye.user_component.ui.activities.metadata.MetadataSettingsActivity::class.java)
            ActivityScenario.launch<com.xyoye.user_component.ui.activities.metadata.MetadataSettingsActivity>(intent).use { scenario ->
                lateinit var page: com.xyoye.user_component.ui.activities.metadata.MetadataSettingsActivity
                scenario.onActivity { page = it }
                await("Task focused") {
                    page.hasWindowFocus() &&
                        page.findViewById<DpadRecyclerView>(com.xyoye.user_component.R.id.tasks).hasFocus()
                }

                fun assertSummaryFits() {
                    instrumentation.runOnMainSync {
                        val row = page.currentFocus ?: error("No task focus")
                        val summary = row.findViewById<TextView>(com.xyoye.user_component.R.id.summary)
                        val available = summary.height - summary.compoundPaddingTop - summary.compoundPaddingBottom
                        assertTrue(
                            "Summary height=${summary.height}, available=$available, layout=${summary.layout.height}, lines=${summary.lineCount}, row=${row.height}",
                            summary.layout.height <= available
                        )
                        assertTrue(
                            "Summary bottom=${summary.bottom}, row=${row.height}, padding=${row.paddingBottom}",
                            summary.bottom <= row.height - row.paddingBottom
                        )
                    }
                }
                screenshot("task-completed")
                assertSummaryFits()
                runBlocking { db.getMetadataDao().putScan(run.copy(state = "PARTIAL", failure = "NETWORK", matched = 11, failed = 1)) }
                await("Task failure displayed") {
                    page.currentFocus
                        ?.findViewById<TextView>(com.xyoye.user_component.R.id.summary)
                        ?.text
                        ?.contains("原因：") ==
                        true
                }
                instrumentation.waitForIdleSync()
                screenshot("task-partial")
                assertSummaryFits()
                send(KeyEvent.KEYCODE_BACK)
            }
        } finally {
            runBlocking { db.openHelper.writableDatabase.execSQL("DELETE FROM metadata_scan WHERE runId = ?", arrayOf(run.runId)) }
        }
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        label: String,
        condition: () -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
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
}
