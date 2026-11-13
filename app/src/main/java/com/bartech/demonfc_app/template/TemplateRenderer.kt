package com.bartech.demonfc_app.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [Template] at any display size. Output is black on white; the
 * SmartTagDisplayPainter converts it to the tag's 1-bit format afterwards, so
 * the designer preview, the update-screen preview and the upload all match.
 */
object TemplateRenderer {

    private const val MIN_TEXT_PX = 6f

    /** Used for barcodes in the designer, where {fields} have no value yet. */
    private const val PREVIEW_BARCODE_SAMPLE = "123456789012"

    /**
     * @param previewMode true in the designer: {Field} placeholders stay visible
     *                    in text, and barcodes use a sample value.
     * @param images      resolves an IMAGE component's imageId to a bitmap.
     */
    fun render(
        template: Template,
        width: Int,
        height: Int,
        values: Map<String, String>,
        previewMode: Boolean,
        images: (String) -> Bitmap?
    ): Bitmap {
        require(width > 0 && height > 0) { "Display size must be positive." }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        for (c in template.components) {
            val boxW = max(1f, c.w * width)
            val boxH = max(1f, c.h * height)
            val rect = RectF(-boxW / 2f, -boxH / 2f, boxW / 2f, boxH / 2f)

            canvas.save()
            canvas.translate(c.cx * width, c.cy * height)
            canvas.rotate(c.rotation)
            try {
                when (c.type) {
                    ComponentType.RECTANGLE,
                    ComponentType.ELLIPSE,
                    ComponentType.LINE -> drawShape(canvas, c, rect, height)

                    ComponentType.TEXT -> {
                        val content = if (previewMode) c.text
                        else TemplateConstants.resolve(c.text, values)
                        drawText(canvas, c, rect, content, height)
                    }

                    ComponentType.IMAGE -> drawImage(canvas, rect, images(c.imageId))

                    ComponentType.BARCODE -> {
                        val content = if (previewMode) PREVIEW_BARCODE_SAMPLE
                        else TemplateConstants.resolve(c.text, values).trim()
                        if (content.isNotEmpty()) drawBarcode(canvas, c.barcode, content, rect)
                    }
                }
            } finally {
                canvas.restore()
            }
        }
        return bitmap
    }

    /**
     * Problems that would stop a barcode from scanning at this display size
     * (invalid data for the type, or the barcode does not fit its box).
     * Empty barcodes are not a problem.
     */
    fun barcodeProblems(
        template: Template,
        width: Int,
        height: Int,
        values: Map<String, String>
    ): List<String> {
        val problems = ArrayList<String>()
        for (c in template.components) {
            if (c.type != ComponentType.BARCODE) continue
            val content = TemplateConstants.resolve(c.text, values).trim()
            if (content.isEmpty()) continue

            val label = c.barcode.label
            try {
                val m = BarcodeEncoder.encode(c.barcode, content)
                val boxW = max(1f, c.w * width)
                val boxH = max(1f, c.h * height)
                if (m.width > boxW || (c.barcode.is2D && m.height > boxH)) {
                    problems += "$label barcode is too small for this display. " +
                            "Make it larger in the template or shorten the data."
                }
            } catch (e: Exception) {
                problems += "$label barcode: ${e.message ?: "invalid data"}"
            }
        }
        return problems
    }

    // ------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------

    private fun drawShape(canvas: Canvas, c: TemplateComponent, rect: RectF, displayH: Int) {
        val stroke = max(1f, (c.strokeWidth * displayH).roundToInt().toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (c.inverted) Color.WHITE else Color.BLACK
        }

        when (c.type) {
            ComponentType.LINE -> {
                paint.style = Paint.Style.FILL
                canvas.drawRect(rect.left, -stroke / 2f, rect.right, stroke / 2f, paint)
            }

            ComponentType.RECTANGLE, ComponentType.ELLIPSE -> {
                val r = RectF(rect)
                if (c.filled) {
                    paint.style = Paint.Style.FILL
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    if (r.width() > stroke && r.height() > stroke) r.inset(stroke / 2f, stroke / 2f)
                }
                if (c.type == ComponentType.RECTANGLE) canvas.drawRect(r, paint)
                else canvas.drawOval(r, paint)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------
    // Text: wraps inside the box and shrinks until it fits
    // ------------------------------------------------------------

    private fun drawText(
        canvas: Canvas,
        c: TemplateComponent,
        rect: RectF,
        content: String,
        displayH: Int
    ) {
        val inverted = c.inverted
        if (inverted) {
            canvas.drawRect(rect, Paint().apply { color = Color.BLACK })
        }
        if (content.isBlank()) return

        val pad = if (inverted) 2f else 0f
        val availW = max(1, (rect.width() - 2 * pad).toInt())
        val availH = rect.height() - 2 * pad

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (inverted) Color.WHITE else Color.BLACK
            typeface = if (c.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val alignment = when (c.align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        var hi = max(MIN_TEXT_PX, c.textSize * displayH)
        paint.textSize = hi
        var layout = buildLayout(content, paint, availW, alignment, Int.MAX_VALUE)

        if (layout.height > availH) {
            var lo = MIN_TEXT_PX
            var best = MIN_TEXT_PX
            repeat(8) {
                val mid = (lo + hi) / 2f
                paint.textSize = mid
                if (buildLayout(content, paint, availW, alignment, Int.MAX_VALUE).height <= availH) {
                    best = mid
                    lo = mid
                } else {
                    hi = mid
                }
            }
            paint.textSize = best
            layout = buildLayout(content, paint, availW, alignment, Int.MAX_VALUE)

            if (layout.height > availH) {
                // Still too tall at the smallest size: cut with "...".
                val lineH = layout.height / layout.lineCount.toFloat()
                val maxLines = max(1, floor(availH / lineH).toInt())
                layout = buildLayout(content, paint, availW, alignment, maxLines)
            }
        }

        canvas.save()
        canvas.translate(rect.left + pad, rect.top + pad + max(0f, (availH - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        maxLines: Int
    ): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
        if (maxLines != Int.MAX_VALUE) {
            builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    // ------------------------------------------------------------
    // Image
    // ------------------------------------------------------------

    private fun drawImage(canvas: Canvas, rect: RectF, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            drawMissing(canvas, rect)
            return
        }
        val scale = min(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dst = RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f)
        canvas.drawBitmap(bitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** Box with a cross: image not available on this phone / barcode not drawable. */
    private fun drawMissing(canvas: Canvas, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(rect, paint)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.top, paint)
    }

    // ------------------------------------------------------------
    // Barcode: integer module sizes so every bar is crisp on the 1-bit tag
    // ------------------------------------------------------------

    private fun drawBarcode(canvas: Canvas, kind: BarcodeKind, content: String, rect: RectF) {
        val matrix = try {
            BarcodeEncoder.encode(kind, content)
        } catch (_: Exception) {
            drawMissing(canvas, rect)
            return
        }

        val mw = matrix.width
        val mh = matrix.height

        if (mw > rect.width() || (kind.is2D && mh > rect.height())) {
            drawMissing(canvas, rect)
            return
        }

        val sx: Int
        val sy: Float
        val drawnH: Float

        if (kind.is2D) {
            val s = max(1, min(floor(rect.width() / mw).toInt(), floor(rect.height() / mh).toInt()))
            sx = s
            sy = s.toFloat()
            drawnH = mh * sy
        } else {
            sx = max(1, floor(rect.width() / mw).toInt())
            sy = rect.height()          // bars fill the full height
            drawnH = rect.height()
        }

        val drawnW = mw * sx
        val left = -drawnW / 2f
        val top = -drawnH / 2f
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            style = Paint.Style.FILL
        }

        val rows = if (kind.is2D) mh else 1
        for (y in 0 until rows) {
            var x = 0
            while (x < mw) {
                if (matrix[x, y]) {
                    val start = x
                    while (x < mw && matrix[x, y]) x++
                    canvas.drawRect(
                        left + start * sx,
                        top + y * sy,
                        left + x * sx,
                        top + (y + 1) * sy,
                        paint
                    )
                } else {
                    x++
                }
            }
        }
    }
}

/** ZXing wrapper: returns the barcode at its natural size (1 module = 1 unit). */
object BarcodeEncoder {

    fun encode(kind: BarcodeKind, content: String): BitMatrix {
        val format = when (kind) {
            BarcodeKind.CODE_128 -> BarcodeFormat.CODE_128
            BarcodeKind.CODE_39 -> BarcodeFormat.CODE_39
            BarcodeKind.EAN_13 -> BarcodeFormat.EAN_13
            BarcodeKind.EAN_8 -> BarcodeFormat.EAN_8
            BarcodeKind.UPC_A -> BarcodeFormat.UPC_A
            BarcodeKind.ITF -> BarcodeFormat.ITF
            BarcodeKind.QR_CODE -> BarcodeFormat.QR_CODE
            BarcodeKind.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
            BarcodeKind.PDF_417 -> BarcodeFormat.PDF_417
            BarcodeKind.AZTEC -> BarcodeFormat.AZTEC
        }

        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 0
        if (kind.is2D) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        if (kind == BarcodeKind.QR_CODE) {
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        }

        // Width/height 0 -> ZXing returns the natural, unscaled matrix.
        return MultiFormatWriter().encode(content, format, 0, 0, hints)
    }
}
