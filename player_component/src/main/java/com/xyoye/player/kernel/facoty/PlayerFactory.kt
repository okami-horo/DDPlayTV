package com.xyoye.player.kernel.facoty

import android.content.Context
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.kernel.impl.media3.Media3PlayerFactory
import com.xyoye.player.kernel.impl.vlc.VlcPlayerFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

/**
 * Created by xyoye on 2020/10/29.
 */

abstract class PlayerFactory {

    companion object {
        fun getFactory(playerType: PlayerType): PlayerFactory {
            return when (playerType) {
                PlayerType.TYPE_EXO_PLAYER -> Media3PlayerFactory()
                // IJK kernel is disabled; fallback to Media3 to avoid unused dependency
                PlayerType.TYPE_IJK_PLAYER -> Media3PlayerFactory()
                PlayerType.TYPE_VLC_PLAYER -> VlcPlayerFactory()
                else -> Media3PlayerFactory()
            }
        }
    }

    abstract fun createPlayer(context: Context): AbstractVideoPlayer
}
