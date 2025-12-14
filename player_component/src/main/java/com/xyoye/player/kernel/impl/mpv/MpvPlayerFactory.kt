package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import com.xyoye.player.kernel.facoty.PlayerFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

class MpvPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return MpvVideoPlayer(context)
    }
}
