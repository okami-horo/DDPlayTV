package com.xyoye.player.surface

import android.content.Context
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SurfaceType

/**
 * Created by xyoye on 2020/11/3.
 */

abstract class SurfaceFactory {
    companion object {
        fun getFactory(
            playerType: PlayerType,
            surfaceType: SurfaceType
        ): SurfaceFactory =
            when {
                playerType == PlayerType.TYPE_MPV_PLAYER && surfaceType == SurfaceType.VIEW_SURFACE -> MpvSurfaceViewFactory()
                playerType == PlayerType.TYPE_MPV_PLAYER -> MpvViewFactory()
                playerType == PlayerType.TYPE_VLC_PLAYER -> VLCViewFactory()
                surfaceType == SurfaceType.VIEW_SURFACE -> SurfaceViewFactory()
                surfaceType == SurfaceType.VIEW_TEXTURE -> TextureViewFactory()
                else -> SurfaceViewFactory()
            }
    }

    abstract fun createRenderView(context: Context): InterSurfaceView
}
