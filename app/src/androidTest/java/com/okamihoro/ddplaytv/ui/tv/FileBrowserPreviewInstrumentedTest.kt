package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragmentViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileBrowserPreviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val longName = "03 [DDPlayTV] 深海来信 - 潮汐之外 [WEB-DL 2160p HEVC 10bit][Dolby Vision HDR10][DTS-HD MA 5.1 AAC][简繁日内封字幕][特别收录版].mkv"
    private val deepName = "Season 02 - The Ocean Beyond the Horizon - Collector Edition 2160p 中文字幕"

    @Test fun deepPathsKeepTheCurrentDirectoryAndRestoreEachParent() =
        fixture { scenario, server ->
            await(scenario) { files(it).size == 6 && list(it).hasFocus() }
            server.deep = true
            repeat(3) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            await(scenario) { preview(it) == "Movies" }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { files(it).size == 1 && preview(it) == deepName }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { files(it).size == 8 && list(it).hasFocus() }
            scenario.recreate()
            await(scenario) {
                files(it).size == 8 &&
                    list(it).hasFocus() &&
                    fragment(it)
                        .requireView()
                        .findViewById<TextView>(R.id.preview_path)
                        .text
                        .contains(deepName)
            }
            shot("deep-path")
            send(KeyEvent.KEYCODE_DPAD_UP)
            await(scenario) { it.findViewById<View>(R.id.path_rv).hasFocus() }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertPreviewMatchesFocus(scenario)
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { focused(it)?.fileName() == deepName }
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { focused(it)?.fileName() == "Movies" }
        }

    @Test fun directoriesPreviewAndExactMoreCallerSurviveBackAndRecreation() =
        fixture { scenario, _ ->
            await(scenario) { files(it).size == 6 && list(it).hasFocus() }
            shot("root")
            repeat(5) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            await(scenario) { preview(it) == "TV" }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { files(it).size == 8 && list(it).hasFocus() }
            shot("videos")
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { preview(it) == "02 深海来信.mkv" }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            await(scenario) { it.currentFocus?.id == R.id.more_action_iv && preview(it) == "02 深海来信.mkv" }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { it.currentFocus?.id == R.id.more_action_iv && preview(it) == "02 深海来信.mkv" }
            scenario.recreate()
            await(scenario) { files(it).size == 8 && focused(it)?.fileName() == "02 深海来信.mkv" }
            assertPreviewMatchesFocus(scenario)
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { preview(it) == longName }
            shot("long-name")
            send(KeyEvent.KEYCODE_MENU)
            shot("long-name-actions")
            assertTrue(
                instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(longName)
                    .isNotEmpty()
            )
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { focused(it)?.fileName() == longName }
            repeat(4) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(3) { send(KeyEvent.KEYCODE_DPAD_UP) }
            // Rapid reversal can reach the valid top boundary while scrolling coalesces keys.
            var onPath = false
            scenario.onActivity { onPath = it.findViewById<View>(R.id.path_rv).hasFocus() }
            if (onPath) send(KeyEvent.KEYCODE_DPAD_DOWN)
            assertPreviewMatchesFocus(scenario)
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { focused(it)?.fileName() == "TV" && preview(it) == "TV" }
            send(KeyEvent.KEYCODE_DPAD_UP)
            await(scenario) { preview(it) == "Music" }
        }

    @Test fun searchClearsPreviewSortKeepsIdentityAndToolsReturnToCaller() =
        fixture { scenario, _ ->
            await(scenario) { files(it).size == 6 }
            scenario.onActivity { it.findViewById<View>(R.id.browser_search).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("search")
            send(KeyEvent.KEYCODE_BACK)
            await(scenario) { it.currentFocus?.id == R.id.browser_search }
            scenario.onActivity { model(it).searchByText("does-not-exist") }
            await(scenario) {
                it.findViewById<TextView>(R.id.empty_tv).text == "没有找到匹配的文件" &&
                    !fragment(it).requireView().findViewById<View>(R.id.preview_panel).isShown
            }
            shot("search-empty")
            scenario.onActivity { fragment(it).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { files(it).size == 6 && preview(it) == "Anime" }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { preview(it) == "chara card" }
            scenario.onActivity { it.findViewById<View>(R.id.browser_sort).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            shot("sort")
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { it.currentFocus?.id == R.id.browser_sort }
            scenario.onActivity { fragment(it).requestFocus() }
            await(scenario) { focused(it)?.fileName() == "chara card" && preview(it) == "chara card" }
            scenario.onActivity { it.findViewById<View>(R.id.browser_nav_3).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            val deadline = SystemClock.uptimeMillis() + 5000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(30)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }

    @Test fun loadingEmptyFailureAndRefreshRecoveryUseVisibleRemoteActions() =
        fixture { scenario, server ->
            await(scenario) { files(it).size == 6 }
            scenario.onActivity { it.findViewById<View>(R.id.browser_refresh).requestFocus() }
            server.fail = true
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { model(it).state.value?.error != null && files(it).size == 6 }
            shot("refresh-error")
            scenario.onActivity { it.findViewById<View>(R.id.browser_status).requestFocus() }
            server.fail = false
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { model(it).state.value?.let { s -> !s.loading && s.error == null } == true }
            server.empty = true
            send(KeyEvent.KEYCODE_SETTINGS)
            await(scenario) { model(it).state.value?.let { s -> !s.loading && s.directoryFiles.isEmpty() } == true }
            shot("empty")
            server.fail = true
            send(KeyEvent.KEYCODE_SETTINGS)
            await(scenario) { it.findViewById<TextView>(R.id.empty_action_tv).text == "重试加载" }
            scenario.onActivity {
                assertEquals("", it.findViewById<TextView>(R.id.directory_count).text.toString())
                assertEquals("目录读取失败", it.findViewById<TextView>(R.id.browser_status).text.toString())
            }
            shot("error")
            server.fail = false
            server.empty = false
            server.gate = CountDownLatch(1)
            scenario.onActivity { fragment(it).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { it.findViewById<View>(R.id.loading).isShown }
            shot("loading")
            server.gate?.countDown()
            await(scenario) { files(it).size == 6 && model(it).state.value?.loading == false }
            scenario.onActivity { fragment(it).requestFocus() }
            repeat(8) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            await(scenario) { focused(it)?.fileName() == "TV" }
            assertPreviewMatchesFocus(scenario)
        }

    @Test fun lateCoverPagingRetryAndTerminalFooterKeepTheSelectedFile() =
        fixture { scenario, server ->
            await(scenario) { files(it).size == 6 }
            val pager = Pager("http://127.0.0.1:${server.listeningPort}")
            scenario.onActivity {
                StorageFileActivity::class.java
                    .getDeclaredField("storage")
                    .apply { isAccessible = true }
                    .set(it, pager)
                model(it).storage = pager
                model(it).listFile(null)
            }
            await(scenario) { files(it).size == 4 && list(it).hasFocus() }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) {
                val drawable = fragment(it).requireView().findViewById<ImageView>(R.id.preview_cover).drawable as? BitmapDrawable
                preview(it) == "02.mkv" && drawable?.bitmap?.let(::pixel) == Color.BLUE
            }
            SystemClock.sleep(1300) // The first cover must not win after the next file has already rendered.
            scenario.onActivity {
                val cover = fragment(it).requireView().findViewById<ImageView>(R.id.preview_cover).drawable as BitmapDrawable
                assertEquals(Color.BLUE, pixel(cover.bitmap))
            }
            shot("existing-cover")
            repeat(3) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            await(scenario) { (list(it).adapter as BaseAdapter).items.getOrNull(list(it).getSelectedPosition()) !is StorageFile }
            val lastPreview = "04.mkv"
            assertEquals(
                lastPreview,
                run {
                    var name = ""
                    scenario.onActivity { name = preview(it) }
                    name
                }
            )
            shot("page-idle")
            pager.gate = CompletableDeferred()
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { model(it).state.value?.loading == true }
            scenario.onActivity {
                assertFalse(
                    it
                        .findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
                            com.xyoye.storage_component.R.id.refresh_layout
                        ).isRefreshing
                )
            }
            shot("page-loading")
            pager.gate?.complete(Unit)
            await(scenario) { model(it).state.value?.error != null }
            shot("page-error")
            scenario.onActivity { assertEquals(lastPreview, preview(it)) }
            pager.failPage = false
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { files(it).size == 6 && model(it).state.value?.error == null }
            repeat(8) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            await(scenario) { focused(it)?.fileName() == "06.mkv" }
            shot("page-end")
            assertPreviewMatchesFocus(scenario)
            pager.mixed = true
            send(KeyEvent.KEYCODE_SETTINGS)
            await(scenario) { files(it).any { f -> f.isDirectory() } }
            scenario.onActivity {
                list(it).setSelectedPosition(0)
                list(it).requestFocus()
            }
            await(scenario) { preview(it) == "Season 02" }
            shot("mixed")
            pager.loggedIn = false
            scenario.onActivity {
                // Reset the fixture source to exercise the same initial authorization failure as an expired provider.
                val expired = Pager(pager.rootUri.toString()).apply { loggedIn = false }
                StorageFileActivity::class.java
                    .getDeclaredField("storage")
                    .apply { isAccessible = true }
                    .set(it, expired)
                model(it).storage = expired
                model(it).listFile(null)
            }
            await(scenario) { it.findViewById<TextView>(R.id.empty_action_tv).text == "重新授权" }
            shot("login")
            scenario.onActivity { fragment(it).requestFocus() }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            val deadline = SystemClock.uptimeMillis() + 5000
            var editor = false
            while (!editor && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    editor =
                        ActivityLifecycleMonitorRegistry
                            .getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .any { it.javaClass.simpleName == "StoragePlusActivity" }
                }
                if (!editor) SystemClock.sleep(30)
            }
            assertTrue("Existing 115 authorization editor is reachable", editor)
            send(KeyEvent.KEYCODE_BACK)
        }

    private class Pager(
        url: String
    ) : AbstractStorage(
            MediaLibraryEntity(displayName = "115", mediaType = MediaType.OPEN_115_STORAGE, url = url)
        ),
        PagedStorage,
        AuthStorage {
        var page = PagedStorage.Snapshot("/", 4, true, PagedStorage.State.IDLE)
        var gate: CompletableDeferred<Unit>? = null
        var failPage = true
        var mixed = false
        var loggedIn = true
        override var state: PagedStorage.State
            get() = page.state
            set(value) {
                page = page.copy(state = value)
            }

        override fun snapshotPaging() = page

        override fun restorePaging(snapshot: PagedStorage.Snapshot) {
            page = snapshot
        }

        override fun hasMore() = page.hasMore

        override suspend fun reset() = Unit

        override suspend fun loadMore(): Result<List<StorageFile>> {
            gate?.await()
            if (failPage) return Result.failure(IOException("fixture page unavailable"))
            page = page.copy(offset = 6, hasMore = false, state = PagedStorage.State.NO_MORE)
            return Result.success((5..6).map { Entry(this, "/%02d.mkv".format(it)) })
        }

        override fun isConnected() = loggedIn

        override fun requiresLogin(directory: StorageFile?) = true

        override fun loginActionText(directory: StorageFile?) = "重新授权"

        override suspend fun listFiles(file: StorageFile): List<StorageFile> =
            (if (mixed) listOf(Entry(this, "/Season 02", true)) else emptyList()) +
                (1..4).map { Entry(this, "/%02d.mkv".format(it)) }

        override suspend fun getRootFile() = Entry(this, "/", true)

        override suspend fun openFile(file: StorageFile): InputStream? = null

        override suspend fun pathFile(
            path: String,
            isDirectory: Boolean
        ) = Entry(this, path, isDirectory)

        override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? = null
    }

    private class Entry(
        storage: Pager,
        private val path: String,
        private val directory: Boolean = false
    ) : AbstractStorageFile(storage) {
        override fun getRealFile(): Any = path

        override fun filePath() = path

        override fun fileUrl() = storage.rootUri.toString() + path

        override fun fileName() = path.substringAfterLast('/')

        override fun fileLength() = if (path.contains("03")) 0L else 1200000000L

        override fun isDirectory() = directory

        override fun fileCover(): String? =
            when (fileName()) {
                "01.mkv" -> storage.rootUri.toString() + "/slow.png"
                "02.mkv" -> storage.rootUri.toString() + "/fast.png"
                else -> null
            }

        override fun clone(): StorageFile = Entry(storage as Pager, path, directory).also { it.playHistory = playHistory }
    }

    private fun fixture(block: (ActivityScenario<StorageFileActivity>, BrowserServer) -> Unit) {
        val sort = StorageSortOption.getSort()
        val asc = StorageSortOption.isAsc()
        StorageSortOption.setSort(StorageSort.NAME)
        if (!asc) StorageSortOption.changeAsc()
        val server = BrowserServer()
        server.start()
        instrumentation.setInTouchMode(false)
        try {
            val library =
                MediaLibraryEntity(
                    displayName = "115",
                    mediaType = MediaType.WEBDAV_SERVER,
                    url = "http://127.0.0.1:${server.listeningPort}/"
                )
            val intent = Intent(context, StorageFileActivity::class.java).putExtra("storageLibrary", library)
            ActivityScenario.launch<StorageFileActivity>(intent).use { block(it, server) }
        } finally {
            server.gate?.countDown()
            server.stop()
            StorageSortOption.setSort(sort)
            if (StorageSortOption.isAsc() != asc) StorageSortOption.changeAsc()
        }
    }

    private inner class BrowserServer : NanoHTTPD(0) {
        @Volatile var deep = false

        @Volatile var fail = false

        @Volatile var empty = false

        @Volatile var gate: CountDownLatch? = null

        override fun serve(session: IHTTPSession): Response {
            if (session.uri.endsWith(".png")) {
                if (session.uri == "/slow.png") SystemClock.sleep(1200)
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (session.uri == "/slow.png") Color.RED else Color.BLUE)
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                return newFixedLengthResponse(Response.Status.OK, "image/png", output.toByteArray().inputStream(), output.size().toLong())
            }
            session.parseBody(mutableMapOf())
            gate?.await(5, TimeUnit.SECONDS)
            if (fail) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture offline")
            val root = session.uri == "/"
            val season = deep && session.uri.trimEnd('/') == "/Movies"
            val names =
                if (season) {
                    listOf(deepName)
                } else if (empty) {
                    emptyList()
                } else if (root) {
                    listOf("Anime", "chara card", "Comic", "Movies", "Music", "TV")
                } else {
                    (1..8).map {
                        if (it == 3) longName else "%02d 深海来信.mkv".format(it)
                    }
                }

            fun entry(
                path: String,
                directory: Boolean
            ): String {
                val escaped = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                return "<d:response><d:href>$escaped</d:href><d:propstat><d:prop><d:resourcetype>" +
                    (if (directory) "<d:collection/>" else "") +
                    "</d:resourcetype><d:getcontentlength>${if (directory) 0 else 1200000000}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            }
            val xml =
                "<d:multistatus xmlns:d=\"DAV:\">" + entry(session.uri, true) +
                    names.joinToString("") {
                        entry(
                            session.uri.trimEnd('/') + "/" + it +
                                if (root ||
                                    season
                                ) {
                                    "/"
                                } else {
                                    ""
                                },
                            root || season
                        )
                    } +
                    "</d:multistatus>"
            return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", xml)
        }
    }

    private fun fragment(activity: StorageFileActivity) =
        activity.supportFragmentManager.fragments.filterIsInstance<StorageFileFragment>().last {
            !it.isHidden
        }

    private fun model(activity: StorageFileActivity) = ViewModelProvider(fragment(activity))[StorageFileFragmentViewModel::class.java]

    private fun list(activity: StorageFileActivity) = fragment(activity).requireView().findViewById<DpadRecyclerView>(R.id.storage_file_rv)

    private fun files(activity: StorageFileActivity) = (list(activity).adapter as BaseAdapter).items.filterIsInstance<StorageFile>()

    private fun preview(activity: StorageFileActivity) =
        fragment(activity)
            .requireView()
            .findViewById<TextView>(R.id.preview_title)
            .text
            .toString()

    private fun focused(activity: StorageFileActivity): StorageFile? {
        val list = list(activity)
        val position = activity.currentFocus?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
        return (list.adapter as BaseAdapter).items.getOrNull(position) as? StorageFile
    }

    private fun assertPreviewMatchesFocus(scenario: ActivityScenario<StorageFileActivity>) {
        await(scenario) { focused(it)?.fileName() == preview(it) }
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun shot(name: String) {
        SystemClock.sleep(250)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(
                context.getExternalFilesDir("file-browser-c"),
                "$name.png"
            ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun pixel(bitmap: Bitmap): Int {
        val readable = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return readable.getPixel(0, 0).also { readable.recycle() }
    }

    private fun await(
        scenario: ActivityScenario<StorageFileActivity>,
        predicate: (StorageFileActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 8000
        var ready = false
        var state = ""
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ready = runCatching { predicate(it) }.getOrDefault(false)
                state =
                    "focus=${it.currentFocus?.id}, preview=${runCatching {
                        preview(
                            it
                        )
                    }.getOrNull()}, files=${runCatching { files(it).size }.getOrNull()}"
            }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue(state, ready)
    }
}
