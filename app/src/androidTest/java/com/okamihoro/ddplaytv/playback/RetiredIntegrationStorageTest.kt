package com.okamihoro.ddplaytv.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.base.app.BaseInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RetiredIntegrationStorageTest {
    @Test
    fun startupRemovesRetiredCredentialsAndPreservesOtherSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(MMKV.initialize(context))
        val retiredId = "bilibili_retirement_fixture"
        val retainedId = "retirement_retained_fixture"
        val legacyKey = "key_bilibili_tv_app_secret_encrypted"
        val config = MMKV.defaultMMKV()
        MMKV.mmkvWithID(retiredId).apply {
            encode("cookie", "retired-fixture")
            close()
        }
        val retained = MMKV.mmkvWithID(retainedId)
        retained.encode("setting", "keep-fixture")
        config.encode(legacyKey, "retired-secret-fixture")
        try {
            assertTrue(File(root, retiredId).isFile)
            BaseInitializer().create(context)
            assertFalse(File(root, retiredId).exists())
            assertFalse(File(root, "$retiredId.crc").exists())
            assertFalse(config.containsKey(legacyKey))
            assertEquals("keep-fixture", retained.decodeString("setting"))
            assertTrue(File(root, retainedId).isFile)
        } finally {
            config.removeValueForKey(legacyKey)
            retained.close()
            for (id in listOf(retiredId, retainedId)) {
                File(root, id).delete()
                File(root, "$id.crc").delete()
            }
        }
    }
}
