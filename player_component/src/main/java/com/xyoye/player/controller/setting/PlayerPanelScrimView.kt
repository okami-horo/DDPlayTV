package com.xyoye.player.controller.setting

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

class PlayerPanelScrimView(
    context: Context
) : View(context) {
    private val paint = Paint()
    private var panelVisible = false

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        visibility = GONE
    }

    fun setPanelVisible(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible
        animate().cancel()
        if (visible) visibility = VISIBLE
        animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(200)
            .withEndAction { if (!panelVisible) visibility = GONE }
            .start()
    }

    fun reset() {
        panelVisible = false
        animate().cancel()
        alpha = 0f
        visibility = GONE
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            paint.shader =
                LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    0f,
                    intArrayOf(0, 0, 0x42000000, 0x70000000),
                    floatArrayOf(0f, .5f, .7f, 1f),
                    Shader.TileMode.CLAMP
                )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }
}
