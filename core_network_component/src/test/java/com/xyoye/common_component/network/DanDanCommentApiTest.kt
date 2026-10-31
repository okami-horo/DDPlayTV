package com.xyoye.common_component.network

import com.squareup.moshi.Moshi
import com.xyoye.common_component.network.helper.DecompressInterceptor
import com.xyoye.common_component.network.helper.OkHttpClientConfig
import com.xyoye.common_component.network.helper.OkHttpClientFactory
import com.xyoye.common_component.network.service.DanDanService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class DanDanCommentApiTest {
    @Test
    fun followsCommentAccelerationRedirectAndParsesGzipped64BitIds() =
        runBlocking<Unit> {
            val request = AtomicReference<String>()
            val json = """{"count":1,"comments":[{"cid":9007199254740993,"p":"12.34,1,16777215,user","m":"comment"}]}"""
            val compressed =
                ByteArrayOutputStream()
                    .apply {
                        GZIPOutputStream(this).use { it.write(json.toByteArray()) }
                    }.toByteArray()
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 5000
            val worker = Executors.newSingleThreadExecutor()
            val responses =
                worker.submit {
                    repeat(4) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = checkNotNull(input.readLine()).split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                            val output = socket.getOutputStream()
                            if (path.startsWith("/api/v2/comment/123?")) {
                                request.set(path)
                                output.write(
                                    "HTTP/1.1 302 Found\r\nLocation: /accelerated-comments\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                            } else {
                                output.write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: ${compressed.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                output.write(compressed)
                            }
                            output.flush()
                        }
                    }
                }
            try {
                val service =
                    Retrofit
                        .Builder()
                        .baseUrl("http://127.0.0.1:${server.localPort}/")
                        .client(OkHttpClientFactory.create(OkHttpClientConfig(interceptors = listOf(DecompressInterceptor()))))
                        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
                        .build()
                        .create(DanDanService::class.java)

                for (related in listOf(true, false)) {
                    val data = service.getDanmuContent("123", hashMapOf("withRelated" to related))

                    assertEquals("/api/v2/comment/123?withRelated=$related", request.get())
                    assertEquals(1, data.count)
                    assertEquals(9_007_199_254_740_993L, data.comments.single().cid)
                    assertEquals("comment", data.comments.single().m)
                }
                responses.get(5, TimeUnit.SECONDS)
            } finally {
                server.close()
                worker.shutdownNow()
            }
        }
}
