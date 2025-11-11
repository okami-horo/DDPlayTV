package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.graphics.Point
import android.view.Surface
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.kernel.impl.exo.ExoVideoPlayer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer

/**
 * Temporary Media3-backed player that routes through the legacy Exo implementation while
 * the new delegate wiring is rolled out. It allows the app to flip the factory switch without
 * touching callers again once the Media3 engine fully replaces Exo.
 */
class Media3VideoPlayer(context: Context) : AbstractVideoPlayer() {

    private val legacyPlayer = ExoVideoPlayer(context)

    override fun initPlayer() {
        legacyPlayer.setPlayerEventListener(mPlayerEventListener)
        legacyPlayer.initPlayer()
    }

    override fun setOptions() {
        legacyPlayer.setOptions()
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        legacyPlayer.setDataSource(path, headers)
    }

    override fun setSurface(surface: Surface) {
        legacyPlayer.setSurface(surface)
    }

    override fun prepareAsync() {
        legacyPlayer.prepareAsync()
    }

    override fun start() {
        legacyPlayer.start()
    }

    override fun pause() {
        legacyPlayer.pause()
    }

    override fun stop() {
        legacyPlayer.stop()
    }

    override fun reset() {
        legacyPlayer.reset()
    }

    override fun release() {
        legacyPlayer.release()
    }

    override fun seekTo(timeMs: Long) {
        legacyPlayer.seekTo(timeMs)
    }

    override fun setSpeed(speed: Float) {
        legacyPlayer.setSpeed(speed)
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        legacyPlayer.setVolume(leftVolume, rightVolume)
    }

    override fun setLooping(isLooping: Boolean) {
        legacyPlayer.setLooping(isLooping)
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        legacyPlayer.setSubtitleOffset(offsetMs)
    }

    override fun isPlaying(): Boolean {
        return legacyPlayer.isPlaying()
    }

    override fun getCurrentPosition(): Long {
        return legacyPlayer.getCurrentPosition()
    }

    override fun getDuration(): Long {
        return legacyPlayer.getDuration()
    }

    override fun getSpeed(): Float {
        return legacyPlayer.getSpeed()
    }

    override fun getVideoSize(): Point {
        return legacyPlayer.getVideoSize()
    }

    override fun getBufferedPercentage(): Int {
        return legacyPlayer.getBufferedPercentage()
    }

    override fun getTcpSpeed(): Long {
        return legacyPlayer.getTcpSpeed()
    }

    override fun supportAddTrack(type: TrackType): Boolean {
        return legacyPlayer.supportAddTrack(type)
    }

    override fun addTrack(track: VideoTrackBean): Boolean {
        return legacyPlayer.addTrack(track)
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        return legacyPlayer.getTracks(type)
    }

    override fun selectTrack(track: VideoTrackBean) {
        legacyPlayer.selectTrack(track)
    }

    override fun deselectTrack(type: TrackType) {
        legacyPlayer.deselectTrack(type)
    }
}
