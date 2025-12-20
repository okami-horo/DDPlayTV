package com.xyoye.player.surface

import android.content.Context

class MpvSurfaceViewFactory : SurfaceFactory() {
    override fun createRenderView(context: Context): InterSurfaceView = RenderMpvSurfaceView(context)
}
