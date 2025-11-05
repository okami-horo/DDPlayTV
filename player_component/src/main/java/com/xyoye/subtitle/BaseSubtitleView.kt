package com.xyoye.subtitle

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.utils.dp2px
import com.xyoye.subtitle.ass.AssOverrideParser

/**
 * Created by xyoye on 2020/12/14.
 */

open class BaseSubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var defaultTextSize = dp2px(20f).toFloat()
    private var defaultStokeWidth = dp2px(5f).toFloat()
    private var defaultTextColor = Color.WHITE
    private var defaultStokeColor = Color.BLACK
    private var defaultAlpha = 100

    private val mTextPaint = TextPaint().apply {
        isAntiAlias = true
        flags = Paint.ANTI_ALIAS_FLAG
        textAlign = Paint.Align.CENTER
        textSize = defaultTextSize
        color = defaultTextColor
    }

    private val mStrokePaint = TextPaint().apply {
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

    init {
        // 在所有Paint对象初始化完成后，调用updateShadowLayer来应用阴影设置
        updateShadowLayer()
    }

    //底部字幕
    private val mBottomSubtitles = mutableListOf<SubtitleText>()

    //顶部字幕
    private val mTopSubtitles = mutableListOf<SubtitleText>()

    //定点字幕
    private val mPositionedSubtitles = mutableListOf<SubtitleText>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        //绘制前，清除上一次的字幕
        clearSubtitle(canvas)

        drawPositionedSubtitles(canvas)

        //底部字幕倒序绘制，从下往上绘制
        var mSubtitleY = measuredHeight - dp2px(10f)
        for (i in mBottomSubtitles.indices.reversed()) {
            val subtitle = mBottomSubtitles[i]
            if (TextUtils.isEmpty(subtitle.text)) {
                continue
            }

            mTextPaint.getTextBounds(subtitle.text, 0, subtitle.text.length, mTextBounds)
            val x = measuredWidth / 2f
            val y = mSubtitleY - mTextBounds.height() / 2f
            canvas.drawText(subtitle.text, x, y, mStrokePaint)
            canvas.drawText(subtitle.text, x, y, mTextPaint)
            mSubtitleY -= mTextBounds.height() + dp2px(5f)
        }

        //顶部字幕绘制，从上往下绘制
        mSubtitleY = dp2px(10f)
        for (topSubtitle in mTopSubtitles) {
            if (TextUtils.isEmpty(topSubtitle.text)) {
                continue
            }
            mTextPaint.getTextBounds(topSubtitle.text, 0, topSubtitle.text.length, mTextBounds)
            val x = measuredWidth / 2f
            val y = mSubtitleY + mTextBounds.height() / 2f
            canvas.drawText(topSubtitle.text, x, y, mStrokePaint)
            canvas.drawText(topSubtitle.text, x, y, mTextPaint)
            mSubtitleY += mTextBounds.height() + dp2px(5f)
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

    protected fun setTextColor(@ColorInt color: Int) {
        mTextPaint.color = color
        invalidate()
    }

    protected fun setStrokeWidth(dpVale: Int) {
        mStrokePaint.strokeWidth = dp2px(dpVale).toFloat()
        invalidate()
    }

    protected fun setStrokeColor(@ColorInt color: Int) {
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

        for (subtitle in mPositionedSubtitles) {
            drawPositionedSubtitle(canvas, subtitle, viewWidth, viewHeight)
        }
    }

    private fun drawPositionedSubtitle(
        canvas: Canvas,
        subtitle: SubtitleText,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val text = subtitle.text
        if (text.isEmpty()) {
            return
        }

        mTextPaint.getTextBounds(text, 0, text.length, mTextBounds)
        val textWidth = mTextPaint.measureText(text)

        val scriptWidth = subtitle.playResX ?: AssOverrideParser.DEFAULT_PLAY_RES_X
        val scriptHeight = subtitle.playResY ?: AssOverrideParser.DEFAULT_PLAY_RES_Y

        val scaledX = scaleCoordinate(subtitle.x, scriptWidth, viewWidth)
        val scaledY = scaleCoordinate(subtitle.y, scriptHeight, viewHeight)

        if (scaledX == null || scaledY == null) {
            return
        }

        val align = subtitle.align
        val fontMetrics = mTextPaint.fontMetrics
        val drawX = calculateAlignedCenterX(scaledX, textWidth, align)
        val baseline = calculateBaseline(
            scaledY,
            align,
            subtitle.lineIndex,
            subtitle.lineCount,
            fontMetrics
        )

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
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, mStrokePaint)
        canvas.drawText(text, x, y, mTextPaint)
    }

    private fun calculateAlignedCenterX(baseX: Float, textWidth: Float, align: Int?): Float {
        return when {
            isLeftAligned(align) -> baseX + textWidth / 2f
            isRightAligned(align) -> baseX - textWidth / 2f
            else -> baseX
        }
    }

    private fun calculateBaseline(
        anchorY: Float,
        align: Int?,
        lineIndex: Int,
        lineCount: Int,
        fontMetrics: Paint.FontMetrics
    ): Float {
        val lineHeight = fontMetrics.descent - fontMetrics.ascent
        val anchorRatio = when {
            isTopAligned(align) -> 0f
            isBottomAligned(align) -> 1f
            else -> 0.5f
        }

        val lineAnchorY = when {
            isTopAligned(align) -> anchorY + lineIndex * lineHeight
            isBottomAligned(align) -> anchorY - (lineCount - 1 - lineIndex) * lineHeight
            else -> anchorY + (lineIndex - (lineCount - 1) / 2f) * lineHeight
        }

        return lineAnchorY - fontMetrics.top - anchorRatio * (fontMetrics.bottom - fontMetrics.top)
    }

    private fun scaleCoordinate(value: Float?, scriptSize: Int, viewSize: Int): Float? {
        val coordinate = value ?: return null
        if (scriptSize <= 0) {
            return null
        }
        return coordinate * (viewSize.toFloat() / scriptSize)
    }

    private fun isLeftAligned(align: Int?): Boolean {
        return align == 1 || align == 4 || align == 7
    }

    private fun isRightAligned(align: Int?): Boolean {
        return align == 3 || align == 6 || align == 9
    }

    private fun isTopAligned(align: Int?): Boolean {
        return align == 7 || align == 8 || align == 9
    }

    private fun isBottomAligned(align: Int?): Boolean {
        return align == 1 || align == 2 || align == 3
    }
}