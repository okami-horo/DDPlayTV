package com.xyoye.subtitle

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.dp2px
import com.xyoye.subtitle.ass.AssOverrideParser
import java.util.Locale

/**
 * Created by xyoye on 2020/12/14.
 */

open class BaseSubtitleView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        companion object {
            private const val TAG = "BaseSubtitleView"
            private const val POSITIONED_SAMPLE_LIMIT = 5
            private const val POSITIONED_SAMPLE_INTERVAL = 50
            private const val FALLBACK_SAMPLE_LIMIT = 5
            private const val FALLBACK_SAMPLE_INTERVAL = 50
        }

        private var defaultTextSize = dp2px(20f).toFloat()
        private var defaultStokeWidth = dp2px(5f).toFloat()
        private var defaultTextColor = Color.WHITE
        private var defaultStokeColor = Color.BLACK
        private var defaultAlpha = 100

        private val mTextPaint =
            TextPaint().apply {
                isAntiAlias = true
                flags = Paint.ANTI_ALIAS_FLAG
                textAlign = Paint.Align.CENTER
                textSize = defaultTextSize
                color = defaultTextColor
            }

        private val mStrokePaint =
            TextPaint().apply {
                isAntiAlias = true
                flags = Paint.ANTI_ALIAS_FLAG
                textAlign = Paint.Align.CENTER
                textSize = defaultTextSize
                color = defaultStokeColor

                strokeWidth = defaultStokeWidth
                style = Paint.Style.FILL_AND_STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                setShadowLayer(3f, 1f, 1f, Color.GRAY)
            }
        private val mTextBounds = Rect()
        private var verticalOffsetPercent = 0

        init {
            // 在所有Paint对象初始化完成后，调用updateShadowLayer来应用阴影设置
            updateShadowLayer()
        }

        // 底部字幕
        private val mBottomSubtitles = mutableListOf<SubtitleText>()

        // 顶部字幕
        private val mTopSubtitles = mutableListOf<SubtitleText>()

        // 定点字幕
        private val mPositionedSubtitles = mutableListOf<SubtitleText>()
        private var positionedRenderCounter = 0
        private var fallbackRenderCounter = 0

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            // 绘制前，清除上一次的字幕
            clearSubtitle(canvas)

            drawPositionedSubtitles(canvas)

            // 底部字幕倒序绘制，从下往上绘制
            val verticalOffsetPx = calculateVerticalOffsetPx(measuredHeight)
            val lineSpacing = dp2px(5f).toFloat()
            var mSubtitleY = measuredHeight.toFloat() - dp2px(10f).toFloat() - verticalOffsetPx
            for (i in mBottomSubtitles.indices.reversed()) {
                val subtitle = mBottomSubtitles[i]
                if (TextUtils.isEmpty(subtitle.text)) {
                    continue
                }

                mTextPaint.getTextBounds(subtitle.text, 0, subtitle.text.length, mTextBounds)
                val textHeight = mTextBounds.height().toFloat()
                val x = measuredWidth / 2f
                val y = mSubtitleY - textHeight / 2f
                canvas.drawText(subtitle.text, x, y, mStrokePaint)
                canvas.drawText(subtitle.text, x, y, mTextPaint)
                mSubtitleY -= textHeight + lineSpacing
            }

            // 顶部字幕绘制，从上往下绘制
            mSubtitleY = dp2px(10f).toFloat() - verticalOffsetPx
            for (topSubtitle in mTopSubtitles) {
                if (TextUtils.isEmpty(topSubtitle.text)) {
                    continue
                }
                mTextPaint.getTextBounds(topSubtitle.text, 0, topSubtitle.text.length, mTextBounds)
                val textHeight = mTextBounds.height().toFloat()
                val x = measuredWidth / 2f
                val y = mSubtitleY + textHeight / 2f
                canvas.drawText(topSubtitle.text, x, y, mStrokePaint)
                canvas.drawText(topSubtitle.text, x, y, mTextPaint)
                mSubtitleY += textHeight + lineSpacing
            }
            super.onDraw(canvas)
        }

        private fun clearSubtitle(canvas: Canvas) {
            val paint = Paint()
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawPaint(paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        fun showSubtitle(subtitleList: List<SubtitleText>) {
            mBottomSubtitles.clear()
            mTopSubtitles.clear()
            mPositionedSubtitles.clear()

            subtitleList.forEach { subtitle ->
                when {
                    subtitle.x != null && subtitle.y != null -> mPositionedSubtitles.add(subtitle)
                    subtitle.top -> mTopSubtitles.add(subtitle)
                    else -> mBottomSubtitles.add(subtitle)
                }
            }
            invalidate()
        }

        protected fun setTextSize(dpVale: Int) {
            mTextPaint.textSize = dp2px(dpVale).toFloat()
            mStrokePaint.textSize = dp2px(dpVale).toFloat()
            invalidate()
        }

        protected fun setTextColor(
            @ColorInt color: Int
        ) {
            mTextPaint.color = color
            invalidate()
        }

        protected fun setStrokeWidth(dpVale: Int) {
            mStrokePaint.strokeWidth = dp2px(dpVale).toFloat()
            invalidate()
        }

        protected fun setStrokeColor(
            @ColorInt color: Int
        ) {
            mStrokePaint.color = color
            invalidate()
        }

        protected fun setAlpha(alpha: Int) {
            // 将百分比转换为0-255的alpha值
            val alphaValue = (255 * alpha / 100).coerceIn(0, 255)

            // 设置文字颜色的alpha值
            val textColor = mTextPaint.color
            val newTextColor = Color.argb(alphaValue, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
            mTextPaint.color = newTextColor

            // 设置描边颜色的alpha值
            val strokeColor = mStrokePaint.color
            val newStrokeColor = Color.argb(alphaValue, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            mStrokePaint.color = newStrokeColor

            invalidate()
        }

        protected fun setVerticalOffset(percent: Int) {
            verticalOffsetPercent = percent
            invalidate()
        }

        protected fun updateShadowLayer() {
            if (SubtitleConfig.isShadowEnabled()) {
                mStrokePaint.setShadowLayer(3f, 1f, 1f, Color.GRAY)
            } else {
                mStrokePaint.clearShadowLayer()
            }
        }

        protected fun setShadowEnabled(enabled: Boolean) {
            SubtitleConfig.putShadowEnabled(enabled)
            updateShadowLayer()
            invalidate()
        }

        protected fun resetParams() {
            mTextPaint.textSize = defaultTextSize
            mStrokePaint.textSize = defaultTextSize
            mTextPaint.color = defaultTextColor
            mTextPaint.strokeWidth = defaultStokeWidth
            mStrokePaint.color = defaultStokeColor
            updateShadowLayer()
            invalidate()
        }

        private fun drawPositionedSubtitles(canvas: Canvas) {
            val viewWidth = measuredWidth
            val viewHeight = measuredHeight
            if (viewWidth <= 0 || viewHeight <= 0) {
                return
            }

            if (mPositionedSubtitles.isEmpty()) {
                return
            }

            val startNs = SystemClock.elapsedRealtimeNanos()
            var drawnCount = 0

            mPositionedSubtitles.forEach { subtitle ->
                if (drawPositionedSubtitle(canvas, subtitle, viewWidth, viewHeight)) {
                    drawnCount++
                }
            }

            logPositionedRender(startNs, drawnCount, mPositionedSubtitles.size)
        }

        private fun drawPositionedSubtitle(
            canvas: Canvas,
            subtitle: SubtitleText,
            viewWidth: Int,
            viewHeight: Int
        ): Boolean {
            val text = subtitle.text
            if (text.isEmpty()) {
                return false
            }

            mTextPaint.getTextBounds(text, 0, text.length, mTextBounds)
            val textWidth = mTextPaint.measureText(text)

            val scriptWidth = subtitle.playResX ?: AssOverrideParser.DEFAULT_PLAY_RES_X
            val scriptHeight = subtitle.playResY ?: AssOverrideParser.DEFAULT_PLAY_RES_Y

            val scaledX = scaleCoordinate(subtitle.x, scriptWidth, viewWidth)
            val scaledY = scaleCoordinate(subtitle.y, scriptHeight, viewHeight)

            if (scaledX == null || scaledY == null) {
                drawFallbackSubtitle(canvas, subtitle, viewWidth, viewHeight)
                return false
            }

            val align = subtitle.align
            val fontMetrics = mTextPaint.fontMetrics
            val drawX = calculateAlignedCenterX(scaledX, textWidth, align)
            val baseline =
                calculateBaseline(
                    scaledY,
                    align,
                    subtitle.lineIndex,
                    subtitle.lineCount,
                    fontMetrics,
                )

            if (!drawX.isFinite() || !baseline.isFinite()) {
                drawFallbackSubtitle(canvas, subtitle, viewWidth, viewHeight)
                return false
            }

            val rotation = subtitle.rotation
            if (rotation != null) {
                val textCenterY = baseline + (fontMetrics.top + fontMetrics.bottom) / 2f
                canvas.save()
                canvas.rotate(rotation, drawX, textCenterY)
                drawText(canvas, text, drawX, baseline)
                canvas.restore()
            } else {
                drawText(canvas, text, drawX, baseline)
            }

            return true
        }

        private fun drawText(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float
        ) {
            canvas.drawText(text, x, y, mStrokePaint)
            canvas.drawText(text, x, y, mTextPaint)
        }

        private fun calculateAlignedCenterX(
            baseX: Float,
            textWidth: Float,
            align: Int?
        ): Float =
            when {
                isLeftAligned(align) -> baseX + textWidth / 2f
                isRightAligned(align) -> baseX - textWidth / 2f
                else -> baseX
            }

        private fun calculateBaseline(
            anchorY: Float,
            align: Int?,
            lineIndex: Int,
            lineCount: Int,
            fontMetrics: Paint.FontMetrics
        ): Float {
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val anchorRatio =
                when {
                    isTopAligned(align) -> 0f
                    isBottomAligned(align) -> 1f
                    else -> 0.5f
                }

            val lineAnchorY =
                when {
                    isTopAligned(align) -> anchorY + lineIndex * lineHeight
                    isBottomAligned(align) -> anchorY - (lineCount - 1 - lineIndex) * lineHeight
                    else -> anchorY + (lineIndex - (lineCount - 1) / 2f) * lineHeight
                }

            return lineAnchorY - fontMetrics.top - anchorRatio * (fontMetrics.bottom - fontMetrics.top)
        }

        private fun drawFallbackSubtitle(
            canvas: Canvas,
            subtitle: SubtitleText,
            viewWidth: Int,
            viewHeight: Int
        ) {
            fallbackRenderCounter++
            if (shouldSample(fallbackRenderCounter, FALLBACK_SAMPLE_LIMIT, FALLBACK_SAMPLE_INTERVAL)) {
                LogFacade.w(
                    LogModule.PLAYER,
                    TAG,
                    String.format(
                        Locale.US,
                        "[FR-009] fallback render textLen=%d align=%s",
                        subtitle.text.length,
                        subtitle.align?.toString() ?: "-",
                    ),
                )
            }

            val text = subtitle.text
            if (text.isEmpty()) {
                return
            }

            mTextPaint.getTextBounds(text, 0, text.length, mTextBounds)
            val textWidth = mTextPaint.measureText(text)
            val fontMetrics = mTextPaint.fontMetrics

            val baseX = viewWidth / 2f
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val baseY =
                if (subtitle.top) {
                    dp2px(10f) + (subtitle.lineIndex + 0.5f) * lineHeight
                } else {
                    viewHeight - dp2px(10f) - (subtitle.lineCount - 1 - subtitle.lineIndex + 0.5f) * lineHeight
                }

            val adjustedBaseY = baseY - calculateVerticalOffsetPx(viewHeight)
            val baseline = adjustedBaseY - (fontMetrics.ascent + fontMetrics.descent) / 2f
            val alignedX =
                when {
                    subtitle.align == null -> baseX
                    subtitle.align in listOf(1, 4, 7) -> baseX - textWidth / 2f
                    subtitle.align in listOf(3, 6, 9) -> baseX + textWidth / 2f
                    else -> baseX
                }

            drawText(canvas, text, alignedX, baseline)
        }

        private fun logPositionedRender(
            startNs: Long,
            drawnCount: Int,
            total: Int
        ) {
            positionedRenderCounter++
            if (!shouldSample(positionedRenderCounter, POSITIONED_SAMPLE_LIMIT, POSITIONED_SAMPLE_INTERVAL)) {
                return
            }
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            LogFacade.d(
                LogModule.PLAYER,
                TAG,
                String.format(
                    Locale.US,
                    "[FR-010] drawPositioned %.3fms drawn=%d total=%d",
                    durationMs,
                    drawnCount,
                    total,
                ),
            )
        }

        private fun shouldSample(
            counter: Int,
            limit: Int,
            interval: Int
        ): Boolean = counter <= limit || counter % interval == 0

        private fun scaleCoordinate(
            value: Float?,
            scriptSize: Int,
            viewSize: Int
        ): Float? {
            val coordinate = value ?: return null
            if (scriptSize <= 0) {
                return null
            }
            return coordinate * (viewSize.toFloat() / scriptSize)
        }

        private fun calculateVerticalOffsetPx(viewHeight: Int): Float {
            if (viewHeight <= 0 || verticalOffsetPercent == 0) {
                return 0f
            }
            return viewHeight * (verticalOffsetPercent / 100f)
        }

        private fun isLeftAligned(align: Int?): Boolean = align == 1 || align == 4 || align == 7

        private fun isRightAligned(align: Int?): Boolean = align == 3 || align == 6 || align == 9

        private fun isTopAligned(align: Int?): Boolean = align == 7 || align == 8 || align == 9

        private fun isBottomAligned(align: Int?): Boolean = align == 1 || align == 2 || align == 3
    }
