package com.xyoye.common_component.storage

import com.xyoye.common_component.network.RetrofitManager
import com.xyoye.common_component.network.RetrofitServiceProvider
import com.xyoye.common_component.network.service.AlistService
import com.xyoye.common_component.source.handoff.PlaybackSourceResolverTest.DefaultPreferences
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.BaiduPanStorageFile
import com.xyoye.common_component.storage.file.impl.Cloud115StorageFile
import com.xyoye.common_component.storage.file.impl.Open115StorageFile
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.AlistStorage
import com.xyoye.common_component.storage.impl.BaiduPanStorage
import com.xyoye.common_component.storage.impl.Cloud115Storage
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.storage.impl.Open115Storage
import com.xyoye.common_component.storage.impl.RemoteStorage
import com.xyoye.common_component.storage.impl.SmbConnection
import com.xyoye.common_component.storage.impl.SmbFileInfo
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.common_component.utils.subtitle.SubtitleFontCacheHelper
import com.xyoye.data_component.data.CommonJsonModel
import com.xyoye.data_component.data.alist.AlistDirectoryData
import com.xyoye.data_component.data.alist.AlistLoginData
import com.xyoye.data_component.data.alist.AlistRootData
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanFileItem
import com.xyoye.data_component.data.cloud115.Cloud115FileInfo
import com.xyoye.data_component.data.open115.Open115FileItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [DefaultPreferences::class])
class StorageBrowsingFailureTest {
    private val storages = mutableListOf<Storage>()

    private fun library(type: MediaType) = MediaLibraryEntity(id = 42, displayName = "fixture", url = "http://127.0.0.1", mediaType = type)

    @Before fun resetPreferences() {
        DefaultPreferences.groupByAnime = false
    }

    @After fun closeFixtures() {
        storages.forEach { it.close() }
        RetrofitManager.resetServiceProvider()
    }

    @Test fun ftpDirectoryFailureAndAuthFailureAreNotEmptyDirectories() =
        runBlocking {
            val client = FtpClient()
            val storage = FtpStorage(library(MediaType.FTP_SERVER), client).also { storages.add(it) }
            val root = storage.getRootFile()
            client.failure = IOException("offline")
            assertTrue(runCatching { storage.openDirectory(root, true) }.exceptionOrNull() is IOException)
            assertNull(storage.directory)
            client.failure = null
            assertTrue(storage.openDirectory(root, true).isEmpty())
            assertSame(root, storage.directory)
            storage.close()

            val refused = FtpClient().apply { loginSucceeds = false }
            val denied = FtpStorage(library(MediaType.FTP_SERVER), refused).also { storages.add(it) }
            assertTrue(runCatching { denied.listFiles(denied.getRootFile()) }.exceptionOrNull() is javax.security.auth.login.LoginException)
        }

    @Test fun smbFailureKeepsPreviousSnapshotAndSuccessCanBeEmpty() =
        runBlocking {
            val connection = Connection()
            val storage = SmbStorage(library(MediaType.SMB_SERVER), connection).also { storages.add(it) }
            val root = storage.getRootFile()!!
            val previous = storage.openDirectory(root, false)
            assertEquals(1, previous.size)
            connection.failure = IOException("offline")
            assertTrue(runCatching { storage.openDirectory(root, true) }.exceptionOrNull() is IOException)
            assertSame(previous, storage.directoryFiles)
            connection.failure = null
            connection.shareNames = emptyList()
            assertTrue(storage.openDirectory(root, true).isEmpty())
        }

    @Test fun ftpNegativeListRepliesFailWhilePositiveEmptyListingSucceeds() =
        runBlocking {
            val client = FtpClient()
            val storage = FtpStorage(library(MediaType.FTP_SERVER), client).also { storages.add(it) }
            val root = storage.getRootFile()
            val previous = storage.openDirectory(root, false)
            for (code in listOf(450, 530, 550)) {
                client.listReplyCode = code
                val failure = runCatching { storage.openDirectory(root, true) }.exceptionOrNull()
                assertTrue("Reply $code must fail", failure is IOException)
                assertTrue(failure?.message.orEmpty().contains(code.toString()))
                assertSame(root, storage.directory)
                assertSame(previous, storage.directoryFiles)
            }
            client.listReplyCode = 226
            assertTrue(storage.openDirectory(root, true).isEmpty())
        }

    @Test fun smbCancellationIsPropagatedWithoutBecomingEmpty() =
        runBlocking {
            val connection = Connection().apply { failure = CancellationException("cancelled") }
            val storage = SmbStorage(library(MediaType.SMB_SERVER), connection).also { storages.add(it) }
            val root = storage.getRootFile()!!
            assertTrue(runCatching { storage.listFiles(root) }.exceptionOrNull() is CancellationException)
        }

    @Test fun fontDirectoryCancellationRestoresBrowserSnapshotAndPropagates() =
        runBlocking {
            val connection = Connection()
            val storage = SmbStorage(library(MediaType.SMB_SERVER), connection).also { storages.add(it) }
            val root = storage.getRootFile()!!
            val files = storage.openDirectory(root, false)
            connection.failure = CancellationException("cancelled")
            val fonts = SmbStorageFile(storage, "media", "fonts")
            assertTrue(runCatching { SubtitleFontCacheHelper.listFontFiles(storage, fonts) }.exceptionOrNull() is CancellationException)
            assertSame(root, storage.directory)
            assertSame(files, storage.directoryFiles)
        }

    @Test fun pcListPropagatesFailureAndCancellationAndCanRecoverWithEmptyResult() =
        runBlocking {
            var failure: Throwable? = IOException("offline")
            val storage =
                RemoteStorage(library(MediaType.REMOTE_STORAGE)) {
                    failure?.let { Result.failure(it) } ?: Result.success(emptyList())
                }.also { storages.add(it) }
            val root = storage.getRootFile()
            assertTrue(runCatching { storage.openDirectory(root, true) }.exceptionOrNull() is IOException)
            assertNull(storage.directory)
            failure = CancellationException("cancelled")
            assertTrue(runCatching { storage.listFiles(root) }.exceptionOrNull() is CancellationException)
            failure = null
            assertTrue(storage.openDirectory(root, true).isEmpty())
        }

    @Test fun webdavHttpFailureIsDistinctFromSuccessfulEmptyMultistatus() =
        runBlocking {
            var denied = true
            val server =
                object : NanoHTTPD(0) {
                    override fun serve(session: IHTTPSession): Response {
                        session.parseBody(mutableMapOf())
                        return if (denied) {
                            newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "denied")
                        } else {
                            newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", DAV_EMPTY)
                        }
                    }
                }
            server.start()
            try {
                val library = library(MediaType.WEBDAV_SERVER).copy(url = "http://127.0.0.1:" + server.listeningPort + "/")
                val storage = WebDavStorage(library).also { storages.add(it) }
                val root = storage.getRootFile()
                assertNotNull(runCatching { storage.openDirectory(root, true) }.exceptionOrNull())
                assertNull(storage.directory)
                denied = false
                assertTrue(storage.openDirectory(root, true).isEmpty())
            } finally {
                server.stop()
            }
        }

    @Test fun alistApplicationFailureIsNotAnEmptyDirectory() =
        runBlocking {
            var denied = true
            val service =
                Proxy.newProxyInstance(AlistService::class.java.classLoader, arrayOf(AlistService::class.java)) { _, method, _ ->
                    when (method.name) {
                        "login" -> CommonJsonModel(code = 200, data = AlistLoginData("fixture"))
                        "getUserInfo" -> CommonJsonModel(code = 200, data = AlistRootData("fixture"))
                        "openDirectory" ->
                            if (denied) {
                                CommonJsonModel<AlistDirectoryData>(code = 403, message = "denied")
                            } else {
                                CommonJsonModel(code = 200, data = AlistDirectoryData())
                            }
                        else -> error("Unexpected API: " + method.name)
                    }
                } as AlistService
            val provider =
                Proxy.newProxyInstance(
                    RetrofitServiceProvider::class.java.classLoader,
                    arrayOf(RetrofitServiceProvider::class.java)
                ) { _, method, _ ->
                    check(method.name == "getAlistService")
                    service
                } as RetrofitServiceProvider
            RetrofitManager.replaceServiceProvider(provider)
            val storage =
                AlistStorage(library(MediaType.ALSIT_STORAGE).copy(account = "user", password = "password"))
                    .also { storages.add(it) }
            val root = storage.getRootFile()!!
            assertNotNull(runCatching { storage.openDirectory(root, true) }.exceptionOrNull())
            assertNull(storage.directory)
            denied = false
            assertTrue(storage.openDirectory(root, true).isEmpty())
        }

    @Test fun webdavDirectorySelfHrefToleratesRootAndTrailingSlashDifferences() =
        runBlocking {
            var href = "/"
            val server =
                object : NanoHTTPD(0) {
                    override fun serve(session: IHTTPSession): Response {
                        session.parseBody(mutableMapOf())
                        val response = DAV_EMPTY.replace("<d:href>/</d:href>", "<d:href>$href</d:href>")
                        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", response)
                    }
                }
            server.start()
            try {
                val endpoint = "http://127.0.0.1:" + server.listeningPort
                val storage =
                    WebDavStorage(library(MediaType.WEBDAV_SERVER).copy(url = "$endpoint/"))
                        .also { storages.add(it) }
                for ((path, selfHref) in listOf("/" to "/", "/" to endpoint, "media" to "/media", "media" to "/media/")) {
                    href = selfHref
                    val directory = if (path == "/") storage.getRootFile() else storage.pathFile(path, true)
                    assertTrue("Directory self href $selfHref must be excluded", storage.openDirectory(directory, true).isEmpty())
                }
            } finally {
                server.stop()
            }
        }

    @Test fun baiduRefreshFailurePreservesCommittedPageAndNextOffset() =
        runBlocking {
            var fail = false
            val offsets = mutableListOf<Int>()
            val storage =
                BaiduPanStorage(library(MediaType.BAIDU_PAN_STORAGE)) { _, offset ->
                    offsets.add(offset)
                    if (fail) throw IOException("refresh failed")
                    if (offset > 0) emptyList() else List(200) { BaiduPanXpanFileItem(fsId = it.toLong(), path = "/$it.mkv") }
                }.also { storages.add(it) }
            assertRefreshPreservesPage(storage, BaiduPanStorageFile.root(storage), { fail = it })
            assertEquals(listOf(0, 0, 200), offsets)
        }

    @Test fun open115RefreshFailurePreservesCommittedPageAndNextOffset() =
        runBlocking {
            var fail = false
            val offsets = mutableListOf<Int>()
            val storage =
                Open115Storage(library(MediaType.OPEN_115_STORAGE)) { _, offset ->
                    offsets.add(offset)
                    if (fail) throw IOException("refresh failed")
                    if (offset > 0) emptyList() else List(200) { Open115FileItem(fid = it.toString(), fc = "1", fn = "$it.mkv") }
                }.also { storages.add(it) }
            assertRefreshPreservesPage(storage, Open115StorageFile.root(storage), { fail = it })
            assertEquals(listOf(0, 0, 200), offsets)
        }

    @Test fun cloud115RefreshFailurePreservesCommittedPageAndNextOffset() =
        runBlocking {
            var fail = false
            val offsets = mutableListOf<Int>()
            val storage =
                Cloud115Storage(library(MediaType.CLOUD_115_STORAGE)) { _, offset ->
                    offsets.add(offset)
                    if (fail) throw IOException("refresh failed")
                    if (offset > 0) emptyList() else List(200) { Cloud115FileInfo(fid = it.toString(), n = "$it.mkv") }
                }.also { storages.add(it) }
            assertRefreshPreservesPage(storage, Cloud115StorageFile.root(storage), { fail = it })
            assertEquals(listOf(0, 0, 200), offsets)
        }

    private suspend fun assertRefreshPreservesPage(
        storage: Storage,
        root: StorageFile,
        fail: (Boolean) -> Unit
    ) {
        val paged = storage as PagedStorage
        val files = storage.openDirectory(root, false)
        assertEquals(200, files.size)
        val page = paged.snapshotPaging()
        fail(true)
        assertTrue(runCatching { storage.openDirectory(root, true) }.exceptionOrNull() is IOException)
        assertEquals(page, paged.snapshotPaging())
        assertSame(files, storage.directoryFiles)
        assertSame(root, storage.directory)
        fail(false)
        assertTrue(paged.loadMore().getOrThrow().isEmpty())
    }

    private class FtpClient : FTPClient() {
        var connected = false
        var loginSucceeds = true
        var failure: IOException? = null
        var listReplyCode = 226

        override fun isConnected() = connected

        override fun isAvailable() = connected

        override fun connect(
            hostname: String,
            port: Int
        ) {
            connected = true
        }

        override fun login(
            username: String?,
            password: String?
        ) = loginSucceeds

        override fun setFileType(type: Int) = true

        override fun printWorkingDirectory() = "/"

        override fun listFiles(path: String): Array<FTPFile> {
            failure?.let { throw it }
            return emptyArray()
        }

        override fun getReplyCode() = listReplyCode

        override fun disconnect() {
            connected = false
        }
    }

    private class Connection : SmbConnection {
        var failure: Exception? = null
        var shareNames = listOf("media")

        override fun shares(): List<String> {
            failure?.let { throw it }
            return shareNames
        }

        override fun selectShare(name: String) = Unit

        override fun stat(path: String) = SmbFileInfo(1, false)

        override fun list(path: String): List<String> {
            failure?.let { throw it }
            return emptyList()
        }

        override fun open(path: String) = ByteArrayInputStream(byteArrayOf())

        override fun close() = Unit
    }

    companion object {
        private const val DAV_EMPTY = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
            <d:getcontentlength>0</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat></d:response></d:multistatus>"""
    }
}
