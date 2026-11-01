package com.xyoye.common_component.storage.catalog

import com.xyoye.common_component.network.RetrofitManager
import com.xyoye.common_component.network.RetrofitServiceProvider
import com.xyoye.common_component.network.service.AlistService
import com.xyoye.common_component.source.handoff.PlaybackSourceResolverTest.DefaultPreferences
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.BaiduPanStorageFile
import com.xyoye.common_component.storage.file.impl.Cloud115StorageFile
import com.xyoye.common_component.storage.file.impl.Open115StorageFile
import com.xyoye.common_component.storage.impl.*
import com.xyoye.data_component.data.CommonJsonModel
import com.xyoye.data_component.data.alist.*
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanFileItem
import com.xyoye.data_component.data.cloud115.Cloud115FileInfo
import com.xyoye.data_component.data.open115.Open115FileItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.Buffer
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [DefaultPreferences::class])
class CatalogProtocolTest {
    @After fun reset() = RetrofitManager.resetServiceProvider()

    @Test fun allCloudAdaptersReadBeyondFirstPageWithStableIdentityAndRealNames() =
        runBlocking {
            for (type in cloudTypes) {
                val offsets = mutableListOf<Int>()
                val (storage, root) =
                    cloud(type) { offset ->
                        offsets += offset
                        if (offset == 0) 1..200 else 201..201
                    }
                var closed = false
                val reader = CatalogSourceReader { catalogStorage(storage, root) { closed = true } }
                val snapshot = reader.directory(storage.library, "")
                assertTrue(type.name, snapshot.complete)
                assertEquals(listOf(0, 200), offsets)
                assertEquals(201, snapshot.files.size)
                assertTrue(closed)
                val last = snapshot.files.last()
                assertEquals("乱世千金倪亚·利斯顿 - S01E201.mp4", last.fileName)
                assertEquals("/", last.parentPath)
                assertEquals(
                    201,
                    snapshot.files
                        .map { it.reference.uniqueKey }
                        .distinct()
                        .size
                )
                assertEquals(last.fileName, last.reference.displayTitle)
                if (type != MediaType.BAIDU_PAN_STORAGE) assertEquals("/201", last.reference.storagePath)
            }
        }

    @Test fun laterCloudPageFailuresAndCancellationNeverReturnCompleteOrLeakReader() =
        runBlocking {
            for (type in cloudTypes) {
                for (failure in listOf(IOException("offline"), CancellationException("cancelled"))) {
                    val (storage, root) = cloud(type) { offset -> if (offset == 0) 1..200 else throw failure }
                    var closed = false
                    val reader = CatalogSourceReader { catalogStorage(storage, root) { closed = true } }
                    val result = runCatching { reader.directory(storage.library, "") }
                    assertTrue(type.name, result.isFailure)
                    if (failure is CancellationException) assertTrue(result.exceptionOrNull() is CancellationException)
                    assertTrue(closed)
                }
            }
        }

    @Test fun alistCatalogAuthenticatesAndReadsEveryPageWithoutOpeningVideo() =
        runBlocking {
            val pages = mutableListOf<Int>()
            var failSecondPage = false
            val service =
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AlistService::class.java)) { _, method, args ->
                    when (method.name) {
                        "login" -> CommonJsonModel(code = 200, data = AlistLoginData("fixture"))
                        "getUserInfo" -> CommonJsonModel(code = 200, data = AlistRootData("fixture"))
                        "openFile" -> CommonJsonModel(code = 200, data = AlistFileData("Show S01E01.mkv", size = 100))
                        "openDirectory" -> {
                            val body = Buffer().also { (args[2] as RequestBody).writeTo(it) }.readUtf8()
                            val page = Regex("\"page\"\\s*:\\s*(\\d+)").find(body)!!.groupValues[1].toInt()
                            pages += page
                            if (page == 2 && failSecondPage) throw IOException("offline")
                            CommonJsonModel(code = 200, data = AlistDirectoryData(2, listOf(AlistFileData("Show S01E0$page.mkv"))))
                        }
                        else -> error("Unexpected media request ${method.name}")
                    }
                } as AlistService
            val provider =
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RetrofitServiceProvider::class.java)) { _, method, _ ->
                    check(method.name == "getAlistService")
                    service
                } as RetrofitServiceProvider
            RetrofitManager.replaceServiceProvider(provider)
            val library = library(MediaType.ALSIT_STORAGE).copy(account = "user", password = "fixture")
            val reader = CatalogSourceReader { AlistStorage(it) }
            val snapshot = reader.directory(library, "")
            assertEquals(listOf(1, 2), pages)
            assertEquals(2, snapshot.files.size)
            val reference = snapshot.files.first().reference
            assertEquals(reference.storagePath, AlistStorage(library).pathFile(reference.storagePath, false)!!.storagePath())
            assertEquals(reference.uniqueKey, reader.file(library, reference).reference.uniqueKey)
            failSecondPage = true
            assertTrue(runCatching { reader.directory(library, "") }.isFailure)
        }

    @Test fun ftpCatalogRejectsSyntheticMissingFilesAndClosesItsOwnConnection() =
        runBlocking {
            val client =
                object : FTPClient() {
                    var closed = 0
                    var exists = true

                    override fun isAvailable() = true

                    override fun printWorkingDirectory() = "/"

                    override fun mlistFile(path: String): FTPFile? = if (exists) entry() else null

                    override fun listFiles(path: String): Array<FTPFile> = if (exists) arrayOf(entry()) else emptyArray()

                    override fun disconnect() {
                        closed++
                    }

                    private fun entry() =
                        FTPFile().apply {
                            name = "Show S01E01.mkv"
                            size = 100
                            type = FTPFile.FILE_TYPE
                        }
                }
            val library = library(MediaType.FTP_SERVER)
            val reader = CatalogSourceReader { FtpStorage(it, client) }
            val snapshot = reader.directory(library, "/")
            val file = snapshot.files.single()
            assertEquals(100L, file.size)
            assertEquals(file.reference.uniqueKey, reader.file(library, file.reference).reference.uniqueKey)
            client.exists = false
            assertTrue(runCatching { reader.file(library, file.reference) }.isFailure)
            assertEquals(3, client.closed)
        }

    private fun library(type: MediaType) = MediaLibraryEntity(42, "fixture", "http://fixture.test", type)

    private fun catalogStorage(
        storage: Storage,
        root: StorageFile,
        close: () -> Unit
    ): Storage =
        object : Storage by storage, PagedStorage by (storage as PagedStorage) {
            override suspend fun getRootFile() = root

            override fun close() {
                storage.close()
                close()
            }
        }

    private fun cloud(
        type: MediaType,
        page: (Int) -> IntRange
    ): Pair<Storage, StorageFile> =
        when (type) {
            MediaType.BAIDU_PAN_STORAGE -> {
                val storage =
                    BaiduPanStorage(library(type)) { _, offset ->
                        page(offset).map {
                            BaiduPanXpanFileItem(fsId = it.toLong(), path = "/${name(it)}", serverFilename = name(it))
                        }
                    }
                storage to BaiduPanStorageFile.root(storage)
            }
            MediaType.OPEN_115_STORAGE -> {
                val storage =
                    Open115Storage(
                        library(type)
                    ) { _, offset -> page(offset).map { Open115FileItem(fid = "$it", fc = "1", fn = name(it)) } }
                storage to Open115StorageFile.root(storage)
            }
            MediaType.CLOUD_115_STORAGE -> {
                val storage =
                    Cloud115Storage(library(type)) { _, offset -> page(offset).map { Cloud115FileInfo(fid = "$it", n = name(it)) } }
                storage to Cloud115StorageFile.root(storage)
            }
            else -> error("Not a cloud source")
        }

    private fun name(episode: Int) = "乱世千金倪亚·利斯顿 - S01E${episode.toString().padStart(2, '0')}.mp4"

    private val cloudTypes = listOf(MediaType.BAIDU_PAN_STORAGE, MediaType.OPEN_115_STORAGE, MediaType.CLOUD_115_STORAGE)
}
