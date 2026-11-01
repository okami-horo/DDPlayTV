package com.xyoye.common_component.utils

import com.xyoye.data_component.data.alist.AlistDirectoryData
import com.xyoye.data_component.data.alist.AlistFileData
import com.xyoye.data_component.data.open115.Open115RefreshTokenResponse
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
    fun alistPaginationAcceptsLegacyAndCurrentResponseFields() {
        val adapter = JsonHelper.MO_SHI.adapter(AlistDirectoryData::class.java)
        val legacy = checkNotNull(adapter.fromJson("""{"total":201,"content":[]}"""))
        assertEquals(201, legacy.fileCount)
        assertNull(legacy.hasMore)
        val current = checkNotNull(adapter.fromJson("""{"total":201,"filtered_total":2,"has_more":false,"content":[]}"""))
        assertEquals(2, current.filteredTotal)
        assertEquals(false, current.hasMore)
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
