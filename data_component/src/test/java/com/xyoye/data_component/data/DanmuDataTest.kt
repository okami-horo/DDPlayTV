package com.xyoye.data_component.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmuDataTest {
    private val adapter = Moshi.Builder().build().adapter(DanmuData::class.java)

    @Test
    fun parsesAndRoundTrips64BitCommentIds() {
        for (id in listOf(2_147_483_648L, Long.MAX_VALUE)) {
            val json = """{"count":1,"comments":[{"cid":$id,"p":"12.34,1,16777215,user","m":"comment"}]}"""

            val data = checkNotNull(adapter.fromJson(json))
            val comment = data.comments.single()

            assertEquals(id, comment.cid)
            assertEquals("12.34,1,16777215,user", comment.p)
            assertEquals("comment", comment.m)
            assertEquals(data, adapter.fromJson(adapter.toJson(data)))
        }
    }

    @Test
    fun preservesAnActuallyEmptyDanmuLibrary() {
        val data = checkNotNull(adapter.fromJson("""{"count":0,"comments":[]}"""))

        assertEquals(0, data.count)
        assertTrue(data.comments.isEmpty())
    }
}
