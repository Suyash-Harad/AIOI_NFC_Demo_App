package com.bartech.demonfc_app.smarttag.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws an employee ID card as a bitmap that exactly fits the Smart Tag
 * display (any size: 200x96, 264x176, 296x128, 300x200, 480x280).
 *
 *     +--------------------------------------------+
 *     | [LOGO]                     ID: BT-1023     |
 *     +============================================+
 *     |  Name (bold, largest)                      |
 *     |  Email: name@company.com                   |
 *     |  Phone: +91 98765 43210                    |
 *     +--------------------------------------------+
 *
 * Everything is sized relative to the display height, and every line of text
 * is shrunk automatically until it fits the width, so long names or e-mail
 * addresses never run off the tag. The result is pure black on white; the
 * painter turns it into the tag's 1-bit format afterwards.
 */
object SmartTagIdCardRenderer {

    fun render(
        width: Int,
        height: Int,
        logo: Bitmap?,
        employeeId: String,
        name: String,
        email: String,
        phone: String
    ): Bitmap {

        require(width > 0 && height > 0) { "Display size must be positive." }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val border = if (height >= 150) 2 else 1
        val pad = max(4, (height * 0.05f).roundToInt())
        val headerH = height * 0.30f
        val dividerH = max(2, (height / 64f).roundToInt()).toFloat()

        val innerLeft = (border + pad).toFloat()
        val innerRight = (width - border - pad).toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // ---- Outer border ------------------------------------------------
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border.toFloat()
        canvas.drawRect(
            border / 2f,
            border / 2f,
            width - border / 2f,
            height - border / 2f,
            paint
        )
        paint.style = Paint.Style.FILL

        // ---- Header: logo (left) + employee ID (right) -------------------
        var titleLeft = innerLeft

        if (logo != null && logo.width > 0 && logo.height > 0) {
            val maxH = headerH - pad
            val maxW = (innerRight - innerLeft) * 0.42f
            val scale = min(maxH / logo.height, maxW / logo.width)
            val drawW = logo.width * scale
            val drawH = logo.height * scale
            val top = (headerH - drawH) / 2f

            val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(
                logo,
                null,
                RectF(innerLeft, top, innerLeft + drawW, top + drawH),
                logoPaint
            )
            titleLeft = innerLeft + drawW + pad
        }

        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.RIGHT
        val title = fitText(
            paint,
            "ID: " + employeeId.trim(),
            innerRight - titleLeft,
            headerH * 0.36f,
            8f
        )
        val tm = paint.fontMetrics
        canvas.drawText(
            title,
            innerRight,
            headerH / 2f - (tm.ascent + tm.descent) / 2f,
            paint
        )

        // ---- Divider under the header -----------------------------------
        canvas.drawRect(
            border.toFloat(),
            headerH,
            (width - border).toFloat(),
            headerH + dividerH,
            paint
        )

        // ---- Body: name, e-mail, phone -----------------------------------
        paint.textAlign = Paint.Align.LEFT

        val bodyTop = headerH + dividerH + pad
        val bodyBottom = (height - border - pad).toFloat()
        val avail = bodyBottom - bodyTop
        val maxWidth = innerRight - innerLeft
        val gap = avail * 0.05f

        // Short labels only when the display is wide enough for them.
        val wide = width >= 264
        val emailLine = (if (wide) "Email: " else "") + email.trim()
        val phoneLine = (if (wide) "Phone: " else "") + phone.trim()

        class Line(val text: String, val bold: Boolean, val size: Float, val minSize: Float)

        val lines = listOf(
            Line(name.trim(), true, avail * 0.30f, 10f),
            Line(emailLine, false, avail * 0.20f, 7f),
            Line(phoneLine, false, avail * 0.20f, 7f)
        )

        // Measure first so the whole block can be centred vertically.
        class Placed(val text: String, val size: Float, val bold: Boolean, val height: Float, val ascent: Float)

        val placed = lines.map { line ->
            paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fitted = fitText(paint, line.text, maxWidth, line.size, line.minSize)
            val fm = paint.fontMetrics
            Placed(fitted, paint.textSize, line.bold, fm.descent - fm.ascent, fm.ascent)
        }

        val total = placed.sumOf { it.height.toDouble() }.toFloat() + gap * (placed.size - 1)
        var y = bodyTop + max(0f, (avail - total) / 2f)

        for (p in placed) {
            if (p.text.isNotEmpty()) {
                paint.typeface = if (p.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = p.size
                canvas.drawText(p.text, innerLeft, y - p.ascent, paint)
            }
            y += p.height + gap
        }

        return bitmap
    }

    /**
     * Shrinks the paint's text size from [startSize] down to [minSize] until
     * [text] fits [maxWidth]. If it still does not fit, the text is cut and
     * "..." is added. Returns the text that should be drawn.
     */
    private fun fitText(
        paint: Paint,
        text: String,
        maxWidth: Float,
        startSize: Float,
        minSize: Float
    ): String {

        var size = max(startSize, minSize)
        paint.textSize = size

        while (paint.measureText(text) > maxWidth && size > minSize) {
            size = max(minSize, size - 0.5f)
            paint.textSize = size
        }

        if (paint.measureText(text) <= maxWidth) return text

        var cut = text
        while (cut.length > 1 && paint.measureText("$cut...") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut..."
    }
}