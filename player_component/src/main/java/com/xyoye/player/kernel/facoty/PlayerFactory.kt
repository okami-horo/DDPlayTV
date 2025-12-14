package com.xyoye.player.kernel.facoty

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.kernel.impl.media3.Media3PlayerFactory
import com.xyoye.player.kernel.impl.mpv.MpvPlayerFactory
import com.xyoye.player.kernel.impl.vlc.VlcPlayerFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

/**
 * Created by xyoye on 2020/10/29.
 */

abstract class PlayerFactory {

    companion object {
        @UnstableApi
        @OptIn(UnstableApi::class)
        fun getFactory(playerType: PlayerType): PlayerFactory {
            return when (playerType) {
                PlayerType.TYPE_EXO_PLAYER -> Media3PlayerFactory()
                PlayerType.TYPE_VLC_PLAYER -> VlcPlayerFactory()
                PlayerType.TYPE_MPV_PLAYER -> MpvPlayerFactory()
                else -> Media3PlayerFactory()
            }
        }
    }

    abstract fun createPlayer(context: Context): AbstractVideoPlayer
}
