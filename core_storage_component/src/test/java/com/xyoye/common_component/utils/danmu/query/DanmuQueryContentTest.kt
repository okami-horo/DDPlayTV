package com.xyoye.common_component.utils.danmu.query

import com.xyoye.data_component.data.DanmuContentData
import com.xyoye.data_component.data.DanmuData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DanmuQueryContentTest {
    @Test
    fun defaultDownloadIncludesRelatedCommentsAndOfficialOnlyRemainsAvailable() =
        runBlocking {
            val requests = mutableListOf<Pair<String, Boolean>>()
            val comment = DanmuContentData(1, "1,1,16777215,user", "comment")
            val query =
                DanmuQueryImpl(contentRequest = { id, related ->
                    requests.add(id to related)
                    Result.success(DanmuData(1, mutableListOf(comment)))
                })

            assertEquals(listOf(comment), query.getContentByEpisodeId("123"))
            assertEquals(listOf(comment), query.getContentByEpisodeId("123", false))
            assertEquals(listOf("123" to true, "123" to false), requests)
        }

    @Test
    fun requestFailuresAndCancellationDoNotBecomeEmptyLibraries() =
        runBlocking {
            for (failure in listOf(IOException("request failed"), CancellationException("cancelled"))) {
                val query = DanmuQueryImpl(contentRequest = { _, _ -> Result.failure(failure) })

                try {
                    query.getContentByEpisodeId("123")
                    fail("The original request failure must propagate")
                } catch (actual: Exception) {
                    assertSame(failure, actual)
                }
            }
        }

    @Test
    fun successfulEmptyLibraryRemainsEmpty() =
        runBlocking {
            val query = DanmuQueryImpl(contentRequest = { _, _ -> Result.success(DanmuData(0)) })

            assertTrue(query.getContentByEpisodeId("123").isEmpty())
        }
}
