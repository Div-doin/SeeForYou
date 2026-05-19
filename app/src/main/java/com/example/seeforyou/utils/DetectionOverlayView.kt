package com.example.seeforyou.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 42f
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private var detections: List<Detection> = emptyList()
    private var scaleX = 1f
    private var scaleY = 1f

    fun setYoloResults(
        results: List<Detection>,
        imgWidth: Int,
        imgHeight: Int
    ) {
        detections = results
        scaleX = width.toFloat() / imgWidth.toFloat()
        scaleY = height.toFloat() / imgHeight.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (det in detections) {
            val box = det.boundingBox

            val scaledLeft   = box.left   * scaleX
            val scaledTop    = box.top    * scaleY
            val scaledRight  = box.right  * scaleX
            val scaledBottom = box.bottom * scaleY

            canvas.drawRect(
                RectF(scaledLeft, scaledTop, scaledRight, scaledBottom),
                boxPaint
            )

            val conf = (det.confidence * 100).toInt()
            val labelText = "${det.label}  $conf%"

            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

            val bgRect = RectF(
                scaledLeft,
                scaledTop - textBounds.height() - 16,
                scaledLeft + textBounds.width() + 16,
                scaledTop
            )
            canvas.drawRect(bgRect, bgPaint)

            canvas.drawText(
                labelText,
                scaledLeft + 8,
                scaledTop - 8,
                textPaint
            )
        }
    }
}