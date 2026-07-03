package com.qcgo.quality

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws item boxes over the camera preview. Green = clean, red = dirty.
 * Boxes arrive in frame (analysis image) coordinates and are scaled to the view.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var result: FrameResult? = null

    private val cleanPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val dirtyPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }

    fun setResult(result: FrameResult) {
        this.result = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.frameWidth == 0 || r.frameHeight == 0) return

        val scaleX = width.toFloat() / r.frameWidth
        val scaleY = height.toFloat() / r.frameHeight

        for (item in r.items) {
            val paint = if (item.label == QualityClassifier.Label.CLEAN) cleanPaint else dirtyPaint
            val rf = RectF(
                item.box.left * scaleX,
                item.box.top * scaleY,
                item.box.right * scaleX,
                item.box.bottom * scaleY,
            )
            canvas.drawRect(rf, paint)
            val tag = "${item.label.name.lowercase()} ${(item.confidence * 100).toInt()}%"
            canvas.drawText(tag, rf.left, (rf.top - 8f).coerceAtLeast(36f), labelPaint)
        }
    }
}
