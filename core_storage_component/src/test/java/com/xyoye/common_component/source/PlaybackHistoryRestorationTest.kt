package com.xyoye.common_component.source

import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackHistoryRestorationTest {
    @Test
    fun castHistoryRestoresHeadersProgressAndSelectedFile() =
        runBlocking {
            val storage =
                ScreencastStorage(
                    MediaLibraryEntity(id = 8, displayName = "cast", url = "", mediaType = MediaType.SCREEN_CAST)
                )
            val history =
                PlayHistoryEntity(
                    id = 1,
                    videoName = "episode",
                    url = "http://192.0.2.10:8080/video/key",
                    mediaType = MediaType.SCREEN_CAST,
                    uniqueKey = "key",
                    storageId = 8,
                    videoPosition = 1234,
                    httpHeader = "{\"Authorization\":\"cast-secret\"}"
                )
            val file = storage.historyFile(history)
            assertEquals(mapOf("Authorization" to "cast-secret"), storage.getNetworkHeaders(file))
            assertEquals(1234L, file.playHistory?.videoPosition)
            assertNotNull(storage.getRootFile())
            assertEquals("key", storage.getRootFile()?.uniqueKey())
        }
}
