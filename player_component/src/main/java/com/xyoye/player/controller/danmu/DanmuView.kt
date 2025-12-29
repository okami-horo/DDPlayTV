package com.xyoye.player.controller.danmu

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.util.AttributeSet
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferences
import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferencesStore
import com.xyoye.common_component.bilibili.live.danmaku.LiveDanmakuSocketClient
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.danmaku.BiliDanmakuLoader
import com.xyoye.danmaku.BiliDanmakuParser
import com.xyoye.danmaku.EmptyDanmakuParser
import com.xyoye.danmaku.filter.KeywordFilter
import com.xyoye.danmaku.filter.LanguageConverter
import com.xyoye.danmaku.filter.RegexFilter
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.bean.SendDanmuBean
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player.controller.video.InterControllerView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.wrapper.ControlWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer.DANMAKU_STYLE_STROKEN
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import java.io.File
import kotlin.math.max

/**
 * Created by xyoye on 2020/11/17.
 */

class DanmuView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DanmakuView(context, attrs, defStyleAttr),
    InterControllerView {
    companion object {
        private const val DANMU_MAX_TEXT_SIZE = 2f
        private const val DANMU_MAX_TEXT_ALPHA = 1f
        private const val DANMU_MAX_TEXT_SPEED = 2.5f
        private const val DANMU_MAX_TEXT_STOKE = 20f

        private const val DANMU_TEXT_SIZE_MEDIUM = 25f
        private const val DANMU_TEXT_SIZE_SMALL = 18f
        private const val DANMU_TEXT_SIZE_DENSITY_OFFSET = 0.6f

        private const val INVALID_VALUE = -1L
    }

    private lateinit var mControlWrapper: ControlWrapper

    private val mDanmakuContext = DanmakuContext.create()
    private val mDanmakuLoader = BiliDanmakuLoader.instance()
    private val mKeywordFilter = KeywordFilter()
    private val mRegexFilter = RegexFilter()
    private val mLanguageConverter = LanguageConverter()

    private var mSeekPosition = INVALID_VALUE

    // 当前已添加的弹幕轨道，不一定被成功加载或选中
    private var mAddedTrack: VideoTrackBean? = null

    // 当前弹幕轨道是否被选中
    private var mTrackSelected = false

    // 用户层面的显示状态（与 UI 开关绑定）
    private var userVisible = true

    // 弹幕是否加载完成
    private var mDanmuLoaded = false

    private var danmuResource: DanmuTrackResource? = null

    private var liveDanmakuClient: LiveDanmakuSocketClient? = null
    private var liveDanmakuScope: CoroutineScope? = null
    private var liveDanmakuRenderJob: Job? = null
    private var liveDanmakuChannel: Channel<LiveDanmakuEvent.Danmaku>? = null

    private var liveRestrictedHintShown = false
    private var lastLiveStatusAt = 0L
    private var lastLiveStatusMsg: String? = null

    private var liveDanmakuBlockPreferences: BilibiliDanmakuBlockPreferences? = null

    init {
        showFPS(DanmuConfig.isDanmuDebug())

        initDanmuContext()

        setCallback(
            object : DrawHandler.Callback {
                override fun drawingFinished() {
                }

                override fun danmakuShown(danmaku: BaseDanmaku?) {
                }

                override fun prepared() {
                    post {
                        mDanmuLoaded = true
                        if (mControlWrapper.isPlaying()) {
                            val position =
                                if (mSeekPosition == INVALID_VALUE) {
                                    mControlWrapper.getCurrentPosition() + PlayerInitializer.Danmu.offsetPosition
                                } else {
                                    mSeekPosition
                                }
                            seekTo(position)
                            mSeekPosition = INVALID_VALUE
                        }
                        syncLiveDanmakuConnection()
                    }
                }

                override fun updateTimer(timer: DanmakuTimer?) {
                }
            },
        )
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
    }

    override fun onVisibilityChanged(isVisible: Boolean) {
    }

    override fun onPlayStateChanged(playState: PlayState) {
        when (playState) {
            PlayState.STATE_IDLE -> {
                release()
            }

            PlayState.STATE_PLAYING -> {
                if (isPrepared) {
                    resume()
                }
            }

            PlayState.STATE_BUFFERING_PAUSED -> {
                if (isPrepared) {
                    pause()
                }
            }

            PlayState.STATE_BUFFERING_PLAYING -> {
                if (isPrepared && mControlWrapper.isPlaying()) {
                    resume()
                }
            }

            PlayState.STATE_COMPLETED,
            PlayState.STATE_ERROR,
            PlayState.STATE_PAUSED -> {
                if (isPrepared) {
                    pause()
                }
            }

            else -> {
            }
        }

        syncLiveDanmakuConnection()
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long
    ) {
    }

    override fun onLockStateChanged(isLocked: Boolean) {
    }

    override fun onVideoSizeChanged(videoSize: Point) {
    }

    override fun onPopupModeChanged(isPopup: Boolean) {
        // 悬浮窗状态下，将弹幕文字大小与描边缩小为原来的50%
        val sizeProgress = PlayerInitializer.Danmu.size / 100f
        var size = sizeProgress * DANMU_MAX_TEXT_SIZE
        if (isPopup) {
            size *= 0.5f
        }
        mDanmakuContext.setScaleTextSize(size)

        val strokeProgress = PlayerInitializer.Danmu.stoke / 100f
        var stroke = strokeProgress * DANMU_MAX_TEXT_STOKE
        if (isPopup) {
            stroke *= 0.5f
        }
        mDanmakuContext.setDanmakuStyle(DANMAKU_STYLE_STROKEN, stroke)
    }

    override fun resume() {
        if (mSeekPosition != INVALID_VALUE) {
            seekTo(mSeekPosition)
            mSeekPosition = INVALID_VALUE
        }
        super.resume()
    }

    override fun release() {
        stopLiveDanmaku()
        danmuResource = null
        mAddedTrack = null
        mTrackSelected = false
        hide()
        clear()
        clearDanmakusOnScreen()
        super.release()
    }

    fun seekTo(
        timeMs: Long,
        isPlaying: Boolean
    ) {
        if (isPlaying && mDanmuLoaded) {
            seekTo(timeMs + PlayerInitializer.Danmu.offsetPosition)
        } else {
            mSeekPosition = timeMs + PlayerInitializer.Danmu.offsetPosition
        }
    }

    fun addTrack(track: VideoTrackBean): Boolean {
        val resource = track.type.getDanmuResource(track.trackResource) ?: return false

        return when (resource) {
            is DanmuTrackResource.LocalFile -> addLocalTrack(track, resource)
            is DanmuTrackResource.BilibiliLive -> addBilibiliLiveTrack(track, resource)
        }
    }

    fun getAddedTrack() = mAddedTrack?.copy(selected = mTrackSelected)

    fun setTrackSelected(selected: Boolean) {
        mTrackSelected = selected
        syncVisibility()
        syncLiveDanmakuConnection()
    }

    fun toggleVisible() {
        if (mTrackSelected.not()) {
            return
        }

        userVisible = userVisible.not()
        syncVisibility()
    }

    fun setUserVisible(visible: Boolean) {
        userVisible = visible
        syncVisibility()
        syncLiveDanmakuConnection()
    }

    fun isUserVisible(): Boolean {
        return userVisible
    }

    private fun setDanmuVisible(visible: Boolean) {
        if (visible) {
            show()
        } else {
            hide()
        }
    }

    private fun syncVisibility() {
        setDanmuVisible(mTrackSelected && userVisible)
    }

    private fun addLocalTrack(
        track: VideoTrackBean,
        resource: DanmuTrackResource.LocalFile,
    ): Boolean {
        val danmu = resource.danmu
        val danmuFile = File(danmu.danmuPath)
        if (danmuFile.exists().not()) {
            return false
        }

        // 释放上一次加载的弹幕
        release()

        danmuResource = resource

        // 获取弹幕文件
        mDanmakuLoader.load(danmu.danmuPath)
        val dataSource = mDanmakuLoader.dataSource
        if (dataSource == null) {
            ToastCenter.showOriginalToast("弹幕加载失败")
            return false
        }

        mAddedTrack = track
        mDanmuLoaded = false
        val danmuParser =
            BiliDanmakuParser().apply {
                load(dataSource)
            }
        prepare(danmuParser, mDanmakuContext)
        return true
    }

    private fun addBilibiliLiveTrack(
        track: VideoTrackBean,
        resource: DanmuTrackResource.BilibiliLive,
    ): Boolean {
        // 释放上一次加载的弹幕
        release()

        danmuResource = resource
        liveRestrictedHintShown = false

        mAddedTrack = track
        mDanmuLoaded = false
        prepare(EmptyDanmakuParser(), mDanmakuContext)
        return true
    }

    private fun syncLiveDanmakuConnection() {
        val liveResource = danmuResource as? DanmuTrackResource.BilibiliLive
        val shouldConnect =
            liveResource != null &&
                mTrackSelected &&
                userVisible &&
                mDanmuLoaded &&
                isPrepared &&
                this::mControlWrapper.isInitialized &&
                mControlWrapper.isPlaying()

        if (!shouldConnect) {
            stopLiveDanmaku()
            return
        }

        val current = liveDanmakuClient
        if (current != null) {
            return
        }

        startLiveDanmaku(liveResource!!)
    }

    private fun startLiveDanmaku(resource: DanmuTrackResource.BilibiliLive) {
        stopLiveDanmaku()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        liveDanmakuScope = scope
        liveDanmakuBlockPreferences = BilibiliDanmakuBlockPreferencesStore.read(resource.storageKey)
        liveDanmakuChannel =
            Channel(
                capacity = 200,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        startLiveRenderLoop(scope)

        liveDanmakuClient =
            LiveDanmakuSocketClient(
                storageKey = resource.storageKey,
                roomId = resource.roomId,
                scope = scope,
                listener =
                    object : LiveDanmakuSocketClient.Listener {
                        override fun onStateChanged(state: LiveDanmakuSocketClient.LiveDanmakuState) {
                            handleLiveState(state)
                        }

                        override fun onEvent(event: LiveDanmakuEvent) {
                            handleLiveEvent(event)
                        }
                    },
            ).also { it.start() }
    }

    private fun startLiveRenderLoop(scope: CoroutineScope) {
        if (liveDanmakuRenderJob?.isActive == true) return
        val channel = liveDanmakuChannel ?: return

        liveDanmakuRenderJob =
            scope.launch {
                while (isActive) {
                    val first = channel.receive()
                    val batch = ArrayList<LiveDanmakuEvent.Danmaku>(16)
                    batch.add(first)
                    while (batch.size < 30) {
                        val next = channel.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }

                    post {
                        if (!mDanmuLoaded || !mTrackSelected || !userVisible) return@post
                        batch.forEach { event ->
                            val bean =
                                SendDanmuBean(
                                    position = currentTime,
                                    text = event.text,
                                    isScroll = event.mode == LiveDanmakuEvent.DanmakuMode.SCROLL,
                                    isTop = event.mode == LiveDanmakuEvent.DanmakuMode.TOP,
                                    color = event.color,
                                )
                            addDanmuToView(bean)
                        }
                    }

                    delay(16)
                }
            }
    }

    private fun handleLiveState(state: LiveDanmakuSocketClient.LiveDanmakuState) {
        val message =
            when (state) {
                LiveDanmakuSocketClient.LiveDanmakuState.Connecting -> "直播弹幕：连接中"
                is LiveDanmakuSocketClient.LiveDanmakuState.Connected -> "直播弹幕：已连接"
                is LiveDanmakuSocketClient.LiveDanmakuState.Reconnecting -> "直播弹幕：断开重连（${state.attempt}）"
                is LiveDanmakuSocketClient.LiveDanmakuState.Disconnected -> "直播弹幕：已断开"
                is LiveDanmakuSocketClient.LiveDanmakuState.Error -> "直播弹幕：${state.message}"
            }
        showLiveStatus(message)
    }

    private fun handleLiveEvent(event: LiveDanmakuEvent) {
        when (event) {
            is LiveDanmakuEvent.Danmaku -> {
                val prefs = liveDanmakuBlockPreferences
                if (prefs != null && prefs.aiSwitch) {
                    val effectiveLevel = if (prefs.aiLevel == 0) 3 else prefs.aiLevel
                    if (event.recommendScore < effectiveLevel) {
                        return
                    }
                }
                if (!liveRestrictedHintShown && event.userId == 0L && event.userName.contains('*')) {
                    liveRestrictedHintShown = true
                    showLiveStatus("直播弹幕：游客态昵称已打码，可登录 Bilibili 媒体库解除")
                }
                liveDanmakuChannel?.trySend(event)
            }

            is LiveDanmakuEvent.Popularity -> {
                // ignore for MVP
            }
        }
    }

    private fun showLiveStatus(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastLiveStatusMsg && now - lastLiveStatusAt < 3_000L) return
        if (now - lastLiveStatusAt < 800L) return
        lastLiveStatusAt = now
        lastLiveStatusMsg = message
        post {
            if (!this::mControlWrapper.isInitialized) return@post
            mControlWrapper.showMessage(message)
        }
    }

    private fun stopLiveDanmaku() {
        liveDanmakuClient?.stop()
        liveDanmakuClient = null

        liveDanmakuRenderJob?.cancel()
        liveDanmakuRenderJob = null

        liveDanmakuChannel?.let { channel ->
            while (channel.tryReceive().getOrNull() != null) {
                // drain
            }
        }

        liveDanmakuScope?.cancel()
        liveDanmakuScope = null
        liveDanmakuChannel = null
        liveDanmakuBlockPreferences = null
    }

    private fun initDanmuContext() {
        // 设置禁止重叠
        val overlappingPair: MutableMap<Int, Boolean> = HashMap()
        overlappingPair[BaseDanmaku.TYPE_SCROLL_LR] = true
        overlappingPair[BaseDanmaku.TYPE_SCROLL_RL] = true
        overlappingPair[BaseDanmaku.TYPE_FIX_TOP] = true
        overlappingPair[BaseDanmaku.TYPE_FIX_BOTTOM] = true

        // 弹幕更新方式, 0:Choreographer, 1:new Thread, 2:DrawHandler
        val danmuUpdateMethod: Byte =
            if (PlayerInitializer.Danmu.updateInChoreographer) 0 else 2

        mDanmakuContext.apply {
            // 合并重复弹幕
            isDuplicateMergingEnabled = true
            // 弹幕view开启绘制缓存
            enableDanmakuDrawingCache(true)
            // 设置禁止重叠
            mDanmakuContext.preventOverlapping(overlappingPair)
            // 使用DrawHandler驱动刷新，避免在高刷新率时时间轴错位
            updateMethod = danmuUpdateMethod
            // 添加关键字过滤器
            registerFilter(mKeywordFilter)
            // 添加正则过滤器
            registerFilter(mRegexFilter)
            // 添加简繁转换器
            registerFilter(mLanguageConverter)
        }

        updateDanmuSize()
        updateDanmuSpeed()
        updateDanmuAlpha()
        updateDanmuStoke()
        updateMobileDanmuState()
        updateTopDanmuState()
        updateBottomDanmuState()
        updateMaxLine()
        updateMaxScreenNum()
        setLanguage(PlayerInitializer.Danmu.language)
    }

    fun updateDanmuSize() {
        val progress = PlayerInitializer.Danmu.size / 100f
        val size = progress * DANMU_MAX_TEXT_SIZE
        mDanmakuContext.setScaleTextSize(size)
    }

    fun updateDanmuSpeed() {
        val progress = PlayerInitializer.Danmu.speed / 100f
        var speed = DANMU_MAX_TEXT_SPEED * (1 - progress)
        speed = max(0.1f, speed)
        mDanmakuContext.setScrollSpeedFactor(speed)
    }

    fun updateDanmuAlpha() {
        val progress = PlayerInitializer.Danmu.alpha / 100f
        val alpha = progress * DANMU_MAX_TEXT_ALPHA
        mDanmakuContext.setDanmakuTransparency(alpha)
    }

    fun updateDanmuStoke() {
        val progress = PlayerInitializer.Danmu.stoke / 100f
        val stoke = progress * DANMU_MAX_TEXT_STOKE
        mDanmakuContext.setDanmakuStyle(DANMAKU_STYLE_STROKEN, stoke)
    }

    fun updateMobileDanmuState() {
        mDanmakuContext.r2LDanmakuVisibility = PlayerInitializer.Danmu.mobileDanmu
    }

    fun updateTopDanmuState() {
        mDanmakuContext.ftDanmakuVisibility = PlayerInitializer.Danmu.topDanmu
    }

    fun updateBottomDanmuState() {
        mDanmakuContext.fbDanmakuVisibility = PlayerInitializer.Danmu.bottomDanmu
    }

    fun updateOffsetTime() {
        seekTo(currentTime, mControlWrapper.isPlaying())
    }

    fun updateMaxLine() {
        val danmuMaxLineMap: MutableMap<Int, Int?> = mutableMapOf()

        val scrollLine = PlayerInitializer.Danmu.maxScrollLine
        val topLine = PlayerInitializer.Danmu.maxTopLine
        val bottomLine = PlayerInitializer.Danmu.maxBottomLine
        danmuMaxLineMap[BaseDanmaku.TYPE_SCROLL_LR] = getLineLimitValue(scrollLine)
        danmuMaxLineMap[BaseDanmaku.TYPE_SCROLL_RL] = getLineLimitValue(scrollLine)
        danmuMaxLineMap[BaseDanmaku.TYPE_FIX_TOP] = getLineLimitValue(topLine)
        danmuMaxLineMap[BaseDanmaku.TYPE_FIX_BOTTOM] = getLineLimitValue(bottomLine)
        mDanmakuContext.setMaximumLines(danmuMaxLineMap)
    }

    private fun getLineLimitValue(line: Int): Int? {
        if (line <= 0) {
            return null
        }
        return line
    }

    fun updateMaxScreenNum() {
        mDanmakuContext.setMaximumVisibleSizeInScreen(PlayerInitializer.Danmu.maxNum)
    }

    fun addBlackList(
        isRegex: Boolean,
        vararg keyword: String
    ) {
        keyword.forEach {
            if (isRegex) {
                mRegexFilter.addRegex(it)
            } else {
                mKeywordFilter.addKeyword(it)
            }
        }
        notifyFilterChanged()
    }

    fun removeBlackList(
        isRegex: Boolean,
        keyword: String
    ) {
        if (isRegex) {
            mRegexFilter.removeRegex(keyword)
        } else {
            mKeywordFilter.removeKeyword(keyword)
        }
        notifyFilterChanged()
    }

    fun setCloudBlockLiveData(cloudBlockLiveData: LiveData<MutableList<DanmuBlockEntity>>?) {
        if (PlayerInitializer.Danmu.cloudBlock) {
            cloudBlockLiveData?.observe(context as LifecycleOwner) {
                it.forEach { entity ->
                    if (entity.isRegex) {
                        mRegexFilter.addRegex(entity.keyword)
                    } else {
                        mKeywordFilter.addKeyword(entity.keyword)
                    }
                }
                notifyFilterChanged()
            }
        }
    }

    fun isDanmuLoaded(): Boolean = mDanmuLoaded

    fun addDanmuToView(danmuBean: SendDanmuBean) {
        val type =
            when {
                danmuBean.isScroll -> BaseDanmaku.TYPE_SCROLL_RL
                danmuBean.isTop -> BaseDanmaku.TYPE_FIX_TOP
                else -> BaseDanmaku.TYPE_FIX_BOTTOM
            }

        val danmaku = mDanmakuContext.mDanmakuFactory.createDanmaku(type, mDanmakuContext) ?: return
        val baseTextSize = if (danmuBean.isSmallSize) DANMU_TEXT_SIZE_SMALL else DANMU_TEXT_SIZE_MEDIUM
        val densityScale = max(0.1f, mDanmakuContext.displayer.density - DANMU_TEXT_SIZE_DENSITY_OFFSET)
        danmaku.apply {
            text = danmuBean.text
            textSize = baseTextSize * densityScale
            padding = 5
            isLive = false
            priority = 0
            textColor = danmuBean.color
            textShadowColor = if (textColor <= Color.BLACK) Color.WHITE else Color.BLACK
            underlineColor = Color.GREEN
            time = this@DanmuView.currentTime + 500
        }
        addDanmaku(danmaku)
    }

    fun setSpeed(speed: Float) {
        mDanmakuContext.setSpeed(speed)
    }

    fun setLanguage(language: DanmakuLanguage) {
        mLanguageConverter.setData(language)
    }

    private fun notifyFilterChanged() {
        // 该方法内部会调用弹幕刷新，能达到相应效果
        mDanmakuContext.addUserHashBlackList()
    }
}
