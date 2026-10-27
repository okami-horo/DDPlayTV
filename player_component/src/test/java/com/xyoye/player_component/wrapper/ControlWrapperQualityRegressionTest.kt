package com.xyoye.player_component.wrapper

import android.graphics.Point
import android.graphics.PointF
import android.view.KeyEvent
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.bean.SendDanmuBean
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.data_component.enums.VideoScreenScale
import com.xyoye.player.surface.InterSurfaceView
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player.wrapper.InterDanmuController
import com.xyoye.player.wrapper.InterSettingController
import com.xyoye.player.wrapper.InterSubtitleController
import com.xyoye.player.wrapper.InterVideoController
import com.xyoye.player.wrapper.InterVideoPlayer
import com.xyoye.subtitle.MixedSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlWrapperQualityRegressionTest {
    @Test
    fun seekToUpdatesDanmuAndControllerProgressByPlayState() {
        val videoPlayer = FakeVideoPlayer()
        val videoController = FakeVideoController()
        val danmuController = FakeDanmuController()
        val wrapper =
            ControlWrapper(
                mVideoPlayer = videoPlayer,
                mController = videoController,
                mDanmuController = danmuController,
                mSubtitleController = FakeSubtitleController(),
                mSettingController = FakeSettingController()
            )

        videoPlayer.playing = false
        wrapper.seekTo(1_200)
        assertEquals(listOf(1_200L), videoPlayer.seekCalls)
        assertEquals(listOf(DanmuSeekCall(1_200L, false)), danmuController.seekCalls)
        assertEquals(listOf(1_200L), videoController.progressValues)
        assertEquals(0, videoController.startProgressCalls)

        videoPlayer.playing = true
        wrapper.seekTo(2_400)
        assertEquals(listOf(1_200L, 2_400L), videoPlayer.seekCalls)
        assertEquals(
            listOf(
                DanmuSeekCall(1_200L, false),
                DanmuSeekCall(2_400L, true)
            ),
            danmuController.seekCalls
        )
        assertEquals(1, videoController.startProgressCalls)
        assertEquals(listOf(1_200L), videoController.progressValues)
    }

    @Test
    fun getTracksPrependsDisableTrackForSubtitleList() {
        val videoPlayer = FakeVideoPlayer()
        val subtitleController = FakeSubtitleController()
        val wrapper =
            ControlWrapper(
                mVideoPlayer = videoPlayer,
                mController = FakeVideoController(),
                mDanmuController = FakeDanmuController(),
                mSubtitleController = subtitleController,
                mSettingController = FakeSettingController()
            )

        videoPlayer.setTrackSupport(TrackType.SUBTITLE, false)
        videoPlayer.setTracks(TrackType.SUBTITLE, listOf(VideoTrackBean(name = "video.srt", type = TrackType.SUBTITLE)))
        subtitleController.setTracks(TrackType.SUBTITLE, listOf(VideoTrackBean(name = "external.ass", type = TrackType.SUBTITLE)))

        val tracks = wrapper.getTracks(TrackType.SUBTITLE)

        assertEquals(3, tracks.size)
        assertTrue(tracks.first().disable)
        assertTrue(tracks.first().selected)
        assertEquals("video.srt", tracks[1].name)
        assertEquals("external.ass", tracks[2].name)
    }

    @Test
    fun showSettingViewAlwaysHidesControllerButRespectsLockState() {
        val videoController = FakeVideoController()
        val settingController = FakeSettingController()
        val wrapper =
            ControlWrapper(
                mVideoPlayer = FakeVideoPlayer(),
                mController = videoController,
                mDanmuController = FakeDanmuController(),
                mSubtitleController = FakeSubtitleController(),
                mSettingController = settingController
            )

        videoController.lockedState = false
        wrapper.showSettingView(SettingViewType.PLAYER_SETTING, null)

        videoController.lockedState = true
        wrapper.showSettingView(SettingViewType.PLAYER_SETTING, "payload")

        assertEquals(2, videoController.hideControllerCalls)
        assertEquals(1, settingController.showCalls.size)
        assertEquals(SettingViewType.PLAYER_SETTING, settingController.showCalls.single().first)
        assertEquals(null, settingController.showCalls.single().second)
    }

    @Test
    fun addTrackFallsBackToSubtitleControllerWhenPlayerCannotHandleTrack() {
        val videoPlayer = FakeVideoPlayer()
        val videoController = FakeVideoController()
        val subtitleController = FakeSubtitleController()
        val wrapper =
            ControlWrapper(
                mVideoPlayer = videoPlayer,
                mController = videoController,
                mDanmuController = FakeDanmuController(),
                mSubtitleController = subtitleController,
                mSettingController = FakeSettingController()
            )

        videoPlayer.setTrackSupport(TrackType.SUBTITLE, false)
        subtitleController.setTrackSupport(TrackType.SUBTITLE, true)
        val track = VideoTrackBean(name = "sub.srt", type = TrackType.SUBTITLE)

        val added = wrapper.addTrack(track)

        assertTrue(added)
        assertEquals(listOf(track), subtitleController.addedTracks)
        assertEquals(listOf(TrackType.SUBTITLE), videoPlayer.deselectedTypes)
        assertEquals(listOf(track), subtitleController.selectedTracks)
        assertEquals(listOf(TrackType.SUBTITLE), videoController.updatedTrackTypes)
        assertEquals(listOf(track), videoController.addedTracks)
    }

    @Test
    fun selectTrackUsesVideoPlayerForInternalSubtitleEvenWhenExternalSubtitlesAreRoutedToController() {
        val videoPlayer = FakeVideoPlayer()
        val videoController = FakeVideoController()
        val subtitleController = FakeSubtitleController()
        val wrapper =
            ControlWrapper(
                mVideoPlayer = videoPlayer,
                mController = videoController,
                mDanmuController = FakeDanmuController(),
                mSubtitleController = subtitleController,
                mSettingController = FakeSettingController()
            )

        videoPlayer.setTrackSupport(TrackType.SUBTITLE, false)
        subtitleController.setTrackSupport(TrackType.SUBTITLE, true)
        val internalSubtitle = VideoTrackBean.internal(id = "2:1", name = "embedded", type = TrackType.SUBTITLE, selected = false)

        wrapper.selectTrack(internalSubtitle)

        assertEquals(listOf(internalSubtitle), videoPlayer.selectedTracks)
        assertEquals(listOf(TrackType.SUBTITLE), subtitleController.deselectedTypes)
        assertTrue(subtitleController.selectedTracks.isEmpty())
        assertEquals(listOf(TrackType.SUBTITLE), videoController.updatedTrackTypes)
    }
}

private data class DanmuSeekCall(
    val timeMs: Long,
    val isPlaying: Boolean
)

private class FakeVideoPlayer : InterVideoPlayer {
    var playing: Boolean = false
    var lockedSilence: Boolean = false
    private var volume = PointF(1f, 1f)
    private var speed: Float = 1f
    private var currentPosition: Long = 0L

    val seekCalls = mutableListOf<Long>()
    val deselectedTypes = mutableListOf<TrackType>()
    val selectedTracks = mutableListOf<VideoTrackBean>()

    private val supportTrackTypes = mutableSetOf<TrackType>()
    private val tracks = mutableMapOf<TrackType, List<VideoTrackBean>>()

    fun setTrackSupport(
        type: TrackType,
        supported: Boolean
    ) {
        if (supported) {
            supportTrackTypes += type
        } else {
            supportTrackTypes -= type
        }
    }

    fun setTracks(
        type: TrackType,
        values: List<VideoTrackBean>
    ) {
        tracks[type] = values
    }

    override fun start() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun getVideoSource(): BaseVideoSource = error("Not used in quality regression tests")

    override fun getDuration(): Long = 60_000L

    override fun getCurrentPosition(): Long = currentPosition

    override fun seekTo(timeMs: Long) {
        currentPosition = timeMs
        seekCalls += timeMs
    }

    override fun isPlaying(): Boolean = playing

    override fun getBufferedPercentage(): Int = 0

    override fun supportBufferedPercentage(): Boolean = true

    override fun setSilence(isSilence: Boolean) {
        lockedSilence = isSilence
    }

    override fun isSilence(): Boolean = lockedSilence

    override fun setVolume(point: PointF) {
        volume = point
    }

    override fun getVolume(): PointF = volume

    override fun setScreenScale(scaleType: VideoScreenScale) {
        // no-op
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
    }

    override fun getSpeed(): Float = speed

    override fun getTcpSpeed(): Long = 0

    override fun supportTcpSpeed(): Boolean = false

    override fun getRenderView(): InterSurfaceView? = null

    override fun getVideoSize(): Point = Point(1920, 1080)

    override fun setRotation(rotation: Float) {
        // no-op
    }

    override fun updateSubtitleOffsetTime() {
        // no-op
    }

    override fun supportAddTrack(type: TrackType): Boolean = type in supportTrackTypes

    override fun addTrack(track: VideoTrackBean): Boolean = true

    override fun getTracks(type: TrackType): List<VideoTrackBean> = tracks[type].orEmpty()

    override fun selectTrack(track: VideoTrackBean) {
        selectedTracks += track
    }

    override fun deselectTrack(type: TrackType) {
        deselectedTypes += type
    }
}

private class FakeVideoController : InterVideoController {
    var lockedState: Boolean = false
    var popupModeState: Boolean = false

    var startProgressCalls: Int = 0
    var hideControllerCalls: Int = 0
    val progressValues = mutableListOf<Long>()
    val addedTracks = mutableListOf<VideoTrackBean>()
    val updatedTrackTypes = mutableListOf<TrackType>()

    override fun startFadeOut() {
        // no-op
    }

    override fun stopFadeOut() {
        // no-op
    }

    override fun isControllerShowing(): Boolean = false

    override fun showMessage(
        text: String,
        time: com.xyoye.player.utils.MessageTime
    ) {
        // no-op
    }

    override fun setLocked(locked: Boolean) {
        lockedState = locked
    }

    override fun isLocked(): Boolean = lockedState

    override fun setPopupMode(isPopup: Boolean) {
        popupModeState = isPopup
    }

    override fun isPopupMode(): Boolean = popupModeState

    override fun startProgress() {
        startProgressCalls += 1
    }

    override fun stopProgress() {
        // no-op
    }

    override fun setProgress(position: Long) {
        progressValues += position
    }

    override fun hideController() {
        hideControllerCalls += 1
    }

    override fun showController(ignoreShowing: Boolean) {
        // no-op
    }

    override fun setTrackAdded(track: VideoTrackBean) {
        addedTracks += track
    }

    override fun setTrackUpdated(type: TrackType) {
        updatedTrackTypes += type
    }

    override fun destroy() {
        // no-op
    }
}

private open class FakeTrackController {
    private val supportTrackTypes = mutableSetOf<TrackType>()
    private val tracks = mutableMapOf<TrackType, List<VideoTrackBean>>()

    val addedTracks = mutableListOf<VideoTrackBean>()
    val selectedTracks = mutableListOf<VideoTrackBean>()
    val deselectedTypes = mutableListOf<TrackType>()

    fun setTrackSupport(
        type: TrackType,
        supported: Boolean
    ) {
        if (supported) {
            supportTrackTypes += type
        } else {
            supportTrackTypes -= type
        }
    }

    fun setTracks(
        type: TrackType,
        values: List<VideoTrackBean>
    ) {
        tracks[type] = values
    }

    open fun supportAddTrack(type: TrackType): Boolean = type in supportTrackTypes

    open fun addTrack(track: VideoTrackBean): Boolean {
        addedTracks += track
        return true
    }

    open fun getTracks(type: TrackType): List<VideoTrackBean> = tracks[type].orEmpty()

    open fun selectTrack(track: VideoTrackBean) {
        selectedTracks += track
    }

    open fun deselectTrack(type: TrackType) {
        deselectedTypes += type
    }
}

private class FakeDanmuController :
    FakeTrackController(),
    InterDanmuController {
    val seekCalls = mutableListOf<DanmuSeekCall>()

    override fun updateDanmuSize() {
        // no-op
    }

    override fun updateDanmuSpeed() {
        // no-op
    }

    override fun updateDanmuAlpha() {
        // no-op
    }

    override fun updateDanmuStoke() {
        // no-op
    }

    override fun updateDanmuOffsetTime() {
        // no-op
    }

    override fun updateMobileDanmuState() {
        // no-op
    }

    override fun updateTopDanmuState() {
        // no-op
    }

    override fun updateBottomDanmuState() {
        // no-op
    }

    override fun updateMaxLine() {
        // no-op
    }

    override fun updateMaxScreenNum() {
        // no-op
    }

    override fun toggleDanmuVisible() {
        // no-op
    }

    override fun setUserDanmuVisible(visible: Boolean) {
        // no-op
    }

    override fun isUserDanmuVisible(): Boolean = true

    override fun allowSendDanmu(): Boolean = false

    override fun addDanmuToView(danmuBean: SendDanmuBean) {
        // no-op
    }

    override fun addBlackList(
        isRegex: Boolean,
        vararg keyword: String
    ) {
        // no-op
    }

    override fun removeBlackList(
        isRegex: Boolean,
        keyword: String
    ) {
        // no-op
    }

    override fun setSpeed(speed: Float) {
        // no-op
    }

    override fun seekTo(
        timeMs: Long,
        isPlaying: Boolean
    ) {
        seekCalls += DanmuSeekCall(timeMs, isPlaying)
    }

    override fun setLanguage(language: DanmakuLanguage) {
        // no-op
    }

    override fun danmuRelease() {
        // no-op
    }

    override fun supportAddTrack(type: TrackType): Boolean = super.supportAddTrack(type)

    override fun addTrack(track: VideoTrackBean): Boolean = super.addTrack(track)

    override fun getTracks(type: TrackType): List<VideoTrackBean> = super.getTracks(type)

    override fun selectTrack(track: VideoTrackBean) {
        super.selectTrack(track)
    }

    override fun deselectTrack(type: TrackType) {
        super.deselectTrack(type)
    }
}

private class FakeSubtitleController :
    FakeTrackController(),
    InterSubtitleController {
    private var userSubtitleVisible = true

    override fun toggleSubtitleVisible() {
        userSubtitleVisible = !userSubtitleVisible
    }

    override fun setUserSubtitleVisible(visible: Boolean) {
        userSubtitleVisible = visible
    }

    override fun isUserSubtitleVisible(): Boolean = userSubtitleVisible

    override fun updateSubtitleOffsetTime() {
        // no-op
    }

    override fun updateTextSize() {
        // no-op
    }

    override fun updateStrokeWidth() {
        // no-op
    }

    override fun updateTextColor() {
        // no-op
    }

    override fun updateStrokeColor() {
        // no-op
    }

    override fun updateAlpha() {
        // no-op
    }

    override fun updateVerticalOffset() {
        // no-op
    }

    override fun updateShadow() {
        // no-op
    }

    override fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
        // no-op
    }

    override fun supportAddTrack(type: TrackType): Boolean = super.supportAddTrack(type)

    override fun addTrack(track: VideoTrackBean): Boolean = super.addTrack(track)

    override fun getTracks(type: TrackType): List<VideoTrackBean> = super.getTracks(type)

    override fun selectTrack(track: VideoTrackBean) {
        super.selectTrack(track)
    }

    override fun deselectTrack(type: TrackType) {
        super.deselectTrack(type)
    }
}

private class FakeSettingController : InterSettingController {
    val showCalls = mutableListOf<Pair<SettingViewType, Any?>>()

    override fun isSettingViewShowing(): Boolean = false

    override fun showSettingView(
        viewType: SettingViewType,
        extra: Any?
    ) {
        showCalls += viewType to extra
    }

    override fun hideSettingView() {
        // no-op
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean = false

    override fun settingRelease() {
        // no-op
    }
}
