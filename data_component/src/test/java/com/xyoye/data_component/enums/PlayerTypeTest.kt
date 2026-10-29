package com.xyoye.data_component.enums

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTypeTest {
    @Test
    fun retiredPlayerIdFallsBackToMedia3() {
        assertEquals(PlayerType.TYPE_EXO_PLAYER, PlayerType.valueOf(3))
    }

    @Test
    fun retainedPlayerIdsRemainCompatibleWithSavedSettings() {
        assertEquals(2, PlayerType.TYPE_EXO_PLAYER.value)
        assertEquals(4, PlayerType.TYPE_MPV_PLAYER.value)
        assertEquals(PlayerType.TYPE_EXO_PLAYER, PlayerType.valueOf(2))
        assertEquals(PlayerType.TYPE_MPV_PLAYER, PlayerType.valueOf(4))
    }
}
