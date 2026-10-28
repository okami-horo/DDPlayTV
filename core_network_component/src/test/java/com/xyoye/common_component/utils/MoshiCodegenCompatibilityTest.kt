package com.xyoye.common_component.utils

import com.xyoye.data_component.data.alist.AlistFileData
import com.xyoye.data_component.data.open115.Open115RefreshTokenResponse
import com.xyoye.data_component.data.remote.RemoteVideoData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshiCodegenCompatibilityTest {
    @Test
    fun generatedAdapterPreservesJsonNamesQualifiersAndDefaults() {
        val adapter = JsonHelper.MO_SHI.adapter(AlistFileData::class.java)
        val file = checkNotNull(adapter.fromJson("""{"name":null,"is_dir":true,"raw_url":null}"""))

        assertEquals("", file.name)
        assertEquals("", file.rawUrl)
        assertEquals("", file.thumb)
        assertEquals(0L, file.size)
        assertTrue(file.isDirectory)
        assertTrue(adapter.toJson(file).contains("\"is_dir\":true"))
    }

    @Test
    fun generatedAdapterAppliesNumericAndStringNullQualifiers() {
        val adapter = JsonHelper.MO_SHI.adapter(RemoteVideoData::class.java)
        val video = checkNotNull(adapter.fromJson("""{"AnimeId":null,"Duration":null,"Size":null,"Name":null}"""))

        assertEquals(0, video.AnimeId)
        assertEquals(0L, video.Duration)
        assertEquals(0L, video.Size)
        assertEquals("", video.Name)
        assertTrue(video.childData.isEmpty())
    }

    @Test
    fun generatedAdapterAcceptsEmptyArrayAndObjectResponseData() {
        val adapter = JsonHelper.MO_SHI.adapter(Open115RefreshTokenResponse::class.java)
        val empty = checkNotNull(adapter.fromJson("""{"state":0,"data":[]}"""))
        val response = checkNotNull(adapter.fromJson("""{"state":1,"data":{"access_token":"fixture"}}"""))

        assertNull(empty.data)
        assertEquals(1, response.state)
        assertEquals("fixture", response.data?.accessToken)
        assertEquals(0, response.data?.expiresIn)
    }
}
