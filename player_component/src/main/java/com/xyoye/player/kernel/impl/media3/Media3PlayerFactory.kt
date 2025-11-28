package com.xyoye.player.kernel.impl.media3

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.xyoye.player.kernel.facoty.PlayerFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

@UnstableApi
class Media3PlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return Media3VideoPlayer(context)
    }
}
