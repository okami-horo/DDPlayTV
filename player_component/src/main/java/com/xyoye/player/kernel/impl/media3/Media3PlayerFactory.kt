package com.xyoye.player.kernel.impl.media3

import android.content.Context
import com.xyoye.player.kernel.facoty.PlayerFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

class Media3PlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return Media3VideoPlayer(context)
    }
}
