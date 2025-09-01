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
        updateShadowLayer()
    }
    private val mTextBounds = Rect()

    //底部字幕
    private val mBottomSubtitles = mutableListOf<SubtitleText>()

    //顶部字幕
    private val mTopSubtitles = mutableListOf<SubtitleText>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        //绘制前，清除上一次的字幕
        clearSubtitle(canvas)

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
        val subtitleMap = subtitleList.groupBy { it.top }

        mBottomSubtitles.clear()
        mTopSubtitles.clear()

        subtitleMap[true]?.let {
            mTopSubtitles.addAll(it)
        }
        subtitleMap[false]?.let {
            mBottomSubtitles.addAll(it)
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
}