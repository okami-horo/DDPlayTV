package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BilibiliLiveFollowModelsTest {
    @Test
    fun parseLiveFollowResponse() {
        val json =
            """
            {
              "code": 0,
              "message": "0",
              "ttl": 1,
              "data": {
                "title": "哔哩哔哩直播 - 我的关注",
                "pageSize": 2,
                "totalPage": 26,
                "list": [
                  {
                    "roomid": 544853,
                    "uid": 686127,
                    "uname": "籽岷",
                    "title": "尝试双机位",
                    "face": "https://i0.hdslb.com/bfs/face/7efb679569b2faeff38fa08f6f992fa1ada5e948.webp",
                    "live_status": 1,
                    "text_small": "10.9万",
                    "room_cover": "http://i0.hdslb.com/bfs/live/new_room_cover/6c89c41d7695a080d31ae21c128f7759a7f419e5.jpg"
                  },
                  {
                    "roomid": 21686237,
                    "uid": 456664753,
                    "uname": "央视新闻",
                    "title": "央视新闻的直播间",
                    "live_status": 0
                  }
                ],
                "count": 52,
                "never_lived_count": 30,
                "live_count": 1
              }
            }
            """.trimIndent()

        val moshi = Moshi.Builder().build()
        val type =
            Types.newParameterizedType(
                BilibiliJsonModel::class.java,
                BilibiliLiveFollowData::class.java,
            )
        val adapter = moshi.adapter<BilibiliJsonModel<BilibiliLiveFollowData>>(type)
        val model = adapter.fromJson(json)

        assertNotNull(model)
        assertEquals(0, model?.code)

        val data = model?.data
        assertNotNull(data)
        assertEquals(1, data?.liveCount)
        assertEquals(2, data?.pageSize)
        assertEquals(26, data?.totalPage)

        val first = data?.list?.firstOrNull()
        assertNotNull(first)
        assertEquals(544853L, first?.roomId)
        assertEquals(1, first?.liveStatus)
        assertEquals("籽岷", first?.uname)
        assertEquals("尝试双机位", first?.title)
        assertEquals("http://i0.hdslb.com/bfs/live/new_room_cover/6c89c41d7695a080d31ae21c128f7759a7f419e5.jpg", first?.roomCover)
    }
}
