package com.blinkboard.accessibilitycaptions.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

import androidx.core.graphics.toColorInt

data class BadgedElement(
    val number: Int,
    val bounds: Rect,
    val node: AccessibilityNodeInfo
)

class NumberOverlayView(context: Context) : View(context) {

    private val elements = mutableListOf<BadgedElement>()

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#0D47A1".toColorInt() // High-contrast deep blue
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFD600".toColorInt() // Bright gold yellow border
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    fun setBadgedElements(newElements: List<BadgedElement>) {
        elements.clear()
        elements.addAll(newElements)
        postInvalidate()
    }

    fun clearBadges() {
        elements.clear()
        postInvalidate()
    }

    fun getBadgedElements(): List<BadgedElement> = elements.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (elements.isEmpty()) return

        val radius = 28f

        for (item in elements) {
            val rect = item.bounds
            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) continue

            // Align badge tag cleanly at top-left corner of element bounds
            val centerX = (rect.left + radius).coerceIn(radius, width.toFloat() - radius)
            val centerY = (rect.top + radius).coerceIn(radius, height.toFloat() - radius)

            // Draw circle background & border
            canvas.drawCircle(centerX, centerY, radius, badgeBgPaint)
            canvas.drawCircle(centerX, centerY, radius, badgeBorderPaint)

            // Draw number text
            val textY = centerY - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(item.number.toString(), centerX, textY, textPaint)
        }
    }
}
