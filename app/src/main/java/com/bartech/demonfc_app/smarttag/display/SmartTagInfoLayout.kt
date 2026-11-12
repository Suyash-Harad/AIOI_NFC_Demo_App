package com.bartech.demonfc_app.smarttag.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the "tag information" screen that is shown on the Smart Tag after
 * an update:
 *
 *     +------------------------------------------+
 *     | Smart Tag Data                (header)   |
 *     +------------------------------------------+
 *     | Tag ID    02FE000041xxxxxx               |
 *     | Updated   19 Oct 2026, 02:35 PM          |
 *     | Data      the text that was written      |
 *     |           (wrapped over several lines)   |
 *     +------------------------------------------+
 *
 * The layout is described once as a list of [Item]s and can then be turned
 * into AIOI text fields (for models with text support) or into a bitmap
 * (for models without text support).
 *
 * Font codes follow the official AIOI sample: 0 = small, 1 = medium,
 * 3 = large, 6 = extra large.
 */
object SmartTagInfoLayout {

    sealed class Item {

        /** A filled rectangle (black, or white when [reverse] is true). */
        data class Box(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val reverse: Boolean = false
        ) : Item()

        /** A line of text (white on black when [reverse] is true). */
        data class Text(
            val x: Int,
            val y: Int,
            val font: Int,
            val reverse: Boolean,
            val text: String
        ) : Item()
    }

    private class Profile(
        val pad: Int,
        val headerHeight: Int,
        val headerFont: Int,
        val headerPx: Int,
        val bodyFont: Int,
        val bodyPx: Int,
        val charWidth: Int,
        val labelWidth: Int,
        val rowStep: Int,
        val separatorOffset: Int,
        val lineStep: Int,
        val firstRowY: Int
    )

    /** Compact layout for 296x128 and smaller displays. */
    private val small = Profile(
        pad = 4,
        headerHeight = 20,
        headerFont = 1,
        headerPx = 16,
        bodyFont = 0,
        bodyPx = 12,
        charWidth = 8,      // conservative estimate, so text never overflows
        labelWidth = 64,
        rowStep = 19,
        separatorOffset = 15,
        lineStep = 16,
        firstRowY = 24
    )

    /** Larger fonts for the 480x280 display. */
    private val large = Profile(
        pad = 8,
        headerHeight = 40,
        headerFont = 3,
        headerPx = 24,
        bodyFont = 3,
        bodyPx = 24,
        charWidth = 13,
        labelWidth = 120,
        rowStep = 40,
        separatorOffset = 30,
        lineStep = 30,
        firstRowY = 50
    )

    private const val MAX_FIELDS = 20
    private const val MAX_DATA_LINES = 8

    // ============================================================
    // Layout
    // ============================================================

    fun build(
        width: Int,
        height: Int,
        tagIdHex: String,
        updatedAt: Date,
        data: String
    ): List<Item> {

        val p = if (width >= 400) large else small

        val valueX = p.pad + p.labelWidth
        val capacity = ((width - valueX - p.pad) / p.charWidth).coerceAtLeast(4)

        val items = ArrayList<Item>()

        // Header bar with white title text.
        items += Item.Box(0, 0, width, p.headerHeight)
        items += Item.Text(
            x = p.pad,
            y = (p.headerHeight - p.headerPx) / 2,
            font = p.headerFont,
            reverse = true,
            text = "Smart Tag Data"
        )

        val lineWidth = width - 2 * p.pad

        // Row 1: Tag ID
        var y = p.firstRowY
        items += Item.Text(p.pad, y, p.bodyFont, false, "Tag ID")
        items += Item.Text(valueX, y, p.bodyFont, false, tagIdHex.take(capacity))
        items += Item.Box(p.pad, y + p.separatorOffset, lineWidth, 1)

        // Row 2: Date and time
        y += p.rowStep
        items += Item.Text(p.pad, y, p.bodyFont, false, "Updated")
        items += Item.Text(
            valueX, y, p.bodyFont, false,
            formatDateTime(updatedAt, capacity)
        )
        items += Item.Box(p.pad, y + p.separatorOffset, lineWidth, 1)

        // Row 3: the data, wrapped over as many lines as fit.
        y += p.rowStep
        items += Item.Text(p.pad, y, p.bodyFont, false, "Data")

        val maxLines = ((height - y - 2) / p.lineStep)
            .coerceIn(1, MAX_DATA_LINES)

        val lines = wrap(data, capacity, maxLines)
        for ((index, line) in lines.withIndex()) {
            if (line.isNotEmpty()) {
                items += Item.Text(
                    valueX,
                    y + index * p.lineStep,
                    p.bodyFont,
                    false,
                    line
                )
            }
        }

        check(items.size <= MAX_FIELDS) {
            "Too many display fields (${items.size})."
        }

        return items
    }

    // ============================================================
    // Date and time
    // ============================================================

    /**
     * Example: "19 Oct 2026, 02:35 PM". Falls back to a shorter format
     * ("19/10/26 14:35") when the display is too narrow.
     */
    fun formatDateTime(date: Date, capacity: Int): String {
        val pattern =
            if (capacity >= 21) "dd MMM yyyy, hh:mm a" else "dd/MM/yy HH:mm"

        // English month names keep the text inside the tag's font.
        return SimpleDateFormat(pattern, Locale.ENGLISH).format(date)
    }

    // ============================================================
    // Text wrapping
    // ============================================================

    private fun wrap(text: String, capacity: Int, maxLines: Int): List<String> {

        val cleaned = text
            .replace("\r", "")
            .map { if (it.isISOControl() && it != '\n') ' ' else it }
            .joinToString("")

        val lines = ArrayList<String>()

        for (paragraph in cleaned.split('\n')) {

            var rest = paragraph.trim()

            if (rest.isEmpty()) {
                lines += ""
                continue
            }

            while (rest.isNotEmpty()) {
                if (rest.length <= capacity) {
                    lines += rest
                    break
                }

                // Prefer breaking at a space.
                val space = rest.lastIndexOf(' ', capacity)
                val cut = if (space > capacity / 2) space else capacity

                lines += rest.substring(0, cut).trimEnd()
                rest = rest.substring(cut).trimStart()
            }
        }

        while (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        if (lines.isEmpty()) {
            return listOf("(empty)")
        }

        if (lines.size > maxLines) {
            val kept = ArrayList(lines.take(maxLines))
            val last = kept.last()
            kept[kept.lastIndex] =
                (if (last.length > capacity - 3) last.take(capacity - 3) else last) +
                        "..."
            return kept
        }

        return lines
    }

    // ============================================================
    // Output 1: AIOI text fields
    // ============================================================

    fun toFields(items: List<Item>): List<SmartTagDisplayField> {

        return items.mapIndexed { index, item ->

            val fieldNo = (index + 1).toByte()

            when (item) {

                is Item.Box -> SmartTagRectangleField(
                    fieldNo = fieldNo,
                    x = item.x,
                    y = item.y,
                    reverse = if (item.reverse) 1 else 0,
                    width = item.width,
                    height = item.height
                )

                is Item.Text -> SmartTagTextField(
                    fieldNo = fieldNo,
                    x = item.x,
                    y = item.y,
                    font = item.font.toByte(),
                    reverse = if (item.reverse) 1 else 0,
                    text = item.text
                )
            }
        }
    }

    // ============================================================
    // Output 2: bitmap (models without text support)
    // ============================================================

    fun toBitmap(items: List<Item>, width: Int, height: Int): Bitmap {

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply { isAntiAlias = false }

        for (item in items) {
            when (item) {

                is Item.Box -> {
                    paint.style = Paint.Style.FILL
                    paint.color = if (item.reverse) Color.WHITE else Color.BLACK
                    canvas.drawRect(
                        item.x.toFloat(),
                        item.y.toFloat(),
                        (item.x + item.width).toFloat(),
                        (item.y + item.height).toFloat(),
                        paint
                    )
                }

                is Item.Text -> {
                    paint.style = Paint.Style.FILL
                    paint.color = if (item.reverse) Color.WHITE else Color.BLACK
                    paint.textSize = fontPixels(item.font).toFloat()

                    val top = item.y - paint.fontMetrics.top
                    canvas.drawText(item.text, item.x.toFloat(), top, paint)
                }
            }
        }

        return bitmap
    }

    private fun fontPixels(font: Int): Int = when (font) {
        0 -> 12
        1 -> 16
        2 -> 20
        3 -> 24
        4 -> 32
        5 -> 40
        6 -> 48
        else -> 12
    }
}