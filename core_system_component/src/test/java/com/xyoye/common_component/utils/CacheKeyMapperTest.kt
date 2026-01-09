package com.xyoye.common_component.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheKeyMapperTest {
    @Test
    fun toSafeFileName_hashesUriLikeKeys() {
        val key = "bilibili:/live/3383025"
        val fileName = CacheKeyMapper.toSafeFileName(key)

        assertEquals("1183ddfa34699a2a27a2e173b90e642f", fileName)
        assertTrue(fileName.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun toSafeFileName_keepsMd5KeysStable() {
        val key = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val fileName = CacheKeyMapper.toSafeFileName(key)

        assertEquals(key.lowercase(), fileName)
    }

    @Test
    fun toSafeFileName_rejectsBlankKeys() {
        assertEquals("(invalid)", CacheKeyMapper.toSafeFileName("   "))
    }
}

