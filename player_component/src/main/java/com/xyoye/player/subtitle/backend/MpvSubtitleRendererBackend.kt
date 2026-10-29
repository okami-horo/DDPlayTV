package com.xyoye.player.subtitle.backend

import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.mpv.MpvNativeBridge
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.subtitle.MixedSubtitle

/** mpv owns parsing, clocks, fonts and OSD rendering; the app only hosts the Surface. */
@UnstableApi
class MpvSubtitleRendererBackend(
    private val bridge: MpvNativeBridge
) : SubtitleRenderer {
    override val backend = SubtitleRendererBackend.MPV_NATIVE
    private var environment: SubtitleRenderEnvironment? = null
    private var overlay: SubtitleSurfaceOverlay? = null
    private var released = false

    override fun bind(environment: SubtitleRenderEnvironment) {
        this.environment = environment
        val player = environment.playerView ?: return
        overlay =
            SubtitleSurfaceOverlay(player.context).also { view ->
                view.bindPlayerView(player)
                view.setSurfaceStateListener(
                    object : SubtitleSurfaceOverlay.SurfaceStateListener {
                        override fun onSurfaceCreated(
                            surface: Surface?,
                            width: Int,
                            height: Int
                        ) {
                            if (surface == null || released) return
                            bridge.setSubtitleSurface(surface) { success ->
                                if (!success && !released) ToastCenter.showWarning("字幕画面初始化失败")
                            }
                            bridge.setSubtitleSurfaceSize(width, height)
                        }

                        override fun onSurfaceChanged(
                            surface: Surface?,
                            width: Int,
                            height: Int
                        ) {
                            bridge.setSubtitleSurfaceSize(width, height)
                        }

                        override fun onSurfaceDestroyed() {
                            bridge.detachSubtitleSurface()
                        }
                    }
                )
                player.attachSubtitleOverlay(view)
            }
        setVisible(environment.subtitleController?.isUserSubtitleVisible() ?: true)
        updateFontScaleOffset(PlayerInitializer.Subtitle.fontScaleOffsetPercent)
        updateVerticalOffset(PlayerInitializer.Subtitle.verticalOffset)
        onOffsetChanged(0)
    }

    override fun release() {
        released = true
        bridge.clearExternalSubtitle()
        bridge.detachSubtitleSurface()
        overlay?.let { view ->
            view.setSurfaceStateListener(null)
            environment?.playerView?.detachSubtitleOverlay(view)
        }
        overlay = null
        environment?.clearUiReferences()
        environment = null
    }

    override fun render(subtitle: MixedSubtitle): Boolean = true

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) = Unit

    // Let mpv probe formats, including SRT, ASS, WebVTT and bitmap subtitles.
    override fun supportsExternalTrack(extension: String): Boolean = !released

    override suspend fun loadExternalSubtitle(path: String): Boolean = !released && bridge.loadExternalSubtitle(path)

    override fun clearExternalSubtitle() = bridge.clearExternalSubtitle()

    override fun setVisible(visible: Boolean) = bridge.setSubtitleVisible(visible)

    // mpv has no global subtitle opacity multiplier; the UI hides this control.
    override fun updateOpacity(alphaPercent: Int) = Unit

    override fun updateFontScaleOffset(offsetPercent: Int) = bridge.setSubtitleScale(offsetPercent)

    override fun updateVerticalOffset(offsetPercent: Int) = bridge.setSubtitlePosition(offsetPercent)

    override fun onSeek(positionMs: Long) = Unit

    override fun onOffsetChanged(positionMs: Long) = bridge.setSubtitleDelay(-PlayerInitializer.Subtitle.offsetPosition)
}
