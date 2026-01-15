package com.xyoye.common_component.network.open115

import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.data.open115.Open115DownUrlResponse
import com.xyoye.data_component.data.open115.Open115FolderInfoResponse
import com.xyoye.data_component.data.open115.Open115RefreshTokenResponse
import com.xyoye.data_component.data.open115.Open115UserInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Open115ModelsMoshiTest {
    @Test
    fun userInfo_allowsArrayData_whenError() {
        val json = """{"state":false,"code":401001,"message":"invalid","data":[]}"""
        val adapter = JsonHelper.MO_SHI.adapter(Open115UserInfoResponse::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals(false, parsed!!.state)
        assertEquals(401001, parsed.code)
        assertEquals("invalid", parsed.message)
        assertNull(parsed.data)
    }

    @Test
    fun userInfo_allowsSingleObjectWrappedInArray() {
        val json =
            """
            {
              "state": true,
              "code": 0,
              "message": "success",
              "data": [
                {
                  "user_id": "37610873",
                  "user_name": "test"
                }
              ]
            }
            """.trimIndent()

        val adapter = JsonHelper.MO_SHI.adapter(Open115UserInfoResponse::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertTrue(parsed!!.state)
        assertNotNull(parsed.data)
        assertEquals("37610873", parsed.data!!.userId)
        assertEquals("test", parsed.data!!.userName)
    }

    @Test
    fun downUrl_allowsArrayData_whenError() {
        val json = """{"state":false,"code":401001,"message":"invalid","data":[]}"""
        val adapter = JsonHelper.MO_SHI.adapter(Open115DownUrlResponse::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals(false, parsed!!.state)
        assertEquals(401001, parsed.code)
        assertNull(parsed.data)
    }

    @Test
    fun folderInfo_allowsArrayData_whenError() {
        val json = """{"state":false,"code":401001,"message":"invalid","data":[]}"""
        val adapter = JsonHelper.MO_SHI.adapter(Open115FolderInfoResponse::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals(false, parsed!!.state)
        assertEquals(401001, parsed.code)
        assertNull(parsed.data)
    }

    @Test
    fun refreshToken_allowsArrayData_whenError() {
        val json =
            """
            {
              "state": 0,
              "code": 401001,
              "message": "invalid",
              "errno": 401001,
              "error": "invalid",
              "data": []
            }
            """.trimIndent()

        val adapter = JsonHelper.MO_SHI.adapter(Open115RefreshTokenResponse::class.java)
        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals(0, parsed!!.state)
        assertEquals(401001, parsed.code)
        assertEquals("invalid", parsed.message)
        assertNull(parsed.data)
    }
}

