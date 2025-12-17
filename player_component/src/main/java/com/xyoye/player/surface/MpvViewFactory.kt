package com.xyoye.player.surface

import android.content.Context

class MpvViewFactory : SurfaceFactory() {
    override fun createRenderView(context: Context): InterSurfaceView = RenderMpvView(context)
}
