package com.xyoye.common_component.resolver

import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackProfileResolverTest {
    @Test
    fun retiredLibraryOverrideFollowsTheCurrentGlobalPlayer() {
        val library =
            MediaLibraryEntity(
                displayName = "Saved library",
                url = "https://example.test/dav",
                mediaType = MediaType.WEBDAV_SERVER,
                playerTypeOverride = 3
            )
        for (global in listOf(PlayerType.TYPE_EXO_PLAYER, PlayerType.TYPE_MPV_PLAYER)) {
            val profile =
                PlaybackProfileResolver.resolve(
                    library,
                    global,
                    library.mediaType,
                    setOf(PlayerType.TYPE_EXO_PLAYER, PlayerType.TYPE_MPV_PLAYER),
                    PlayerType.TYPE_EXO_PLAYER
                )
            assertEquals(global, profile.playerType)
            assertEquals(PlaybackProfileSource.FALLBACK, profile.source)
        }
    }
}
