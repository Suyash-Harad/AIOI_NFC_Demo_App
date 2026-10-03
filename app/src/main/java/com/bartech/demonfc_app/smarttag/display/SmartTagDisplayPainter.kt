package com.bartech.demonfc_app.smarttag.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point

class SmartTagDisplayPainter(
    width: Int,
    height: Int
) {

    init {
        require(width > 0) {
            "Display width must be greater than zero."
        }

        require(height > 0) {
            "Display height must be greater than zero."
        }
    }

    private val screen: Bitmap =
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

    private val canvas = Canvas(screen)

    init {
        clear()
    }

    val width: Int
        get() = screen.width

    val height: Int
        get() = screen.height
    /**
     * Clears the preview and fills it with white.
     */
    fun clear() {
        canvas.drawColor(Color.WHITE)
    }

    /**
     * Returns the current preview bitmap.
     */
    fun getPreview(): Bitmap {
        return screen.copy(
            Bitmap.Config.ARGB_8888,
            false
        )
    }

    /**
     * Draws text on the preview.
     *
     * The y coordinate represents the top of the text area.
     */
    fun putText(
        text: String,
        x: Int,
        y: Int,
        size: Int
    ) {
        require(size > 0) {
            "Text size must be greater than zero."
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.isAntiAlias = false
        paint.color = Color.BLACK
        paint.textSize = size.toFloat()
        paint.style = Paint.Style.FILL

        val metrics = paint.fontMetrics
        val baseline = y - metrics.top

        canvas.drawText(
            text,
            x.toFloat(),
            baseline,
            paint
        )
    }

    /**
     * Draws a line on the preview.
     */
    fun putLine(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        size: Int,
        invert: Boolean = false,
        dashed: Boolean = false
    ) {
        require(size > 0) {
            "Line size must be greater than zero."
        }

        val paint = Paint()

        paint.isAntiAlias = false
        paint.color =
            if (invert) {
                Color.WHITE
            } else {
                Color.BLACK
            }

        paint.strokeWidth = size.toFloat()
        paint.style = Paint.Style.STROKE

        if (dashed) {
            paint.pathEffect = DashPathEffect(
                floatArrayOf(2f, 2f),
                0f
            )
        }

        canvas.drawLine(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            paint
        )
    }

    /**
     * Draws a rectangle border on the preview.
     */
    fun putRectangle(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        border: Int
    ) {
        require(width > 0) {
            "Rectangle width must be greater than zero."
        }

        require(height > 0) {
            "Rectangle height must be greater than zero."
        }

        require(border > 0) {
            "Rectangle border must be greater than zero."
        }

        val paint = Paint()

        paint.isAntiAlias = false
        paint.color = Color.BLACK
        paint.strokeWidth = border.toFloat()
        paint.style = Paint.Style.STROKE

        canvas.drawRect(
            x.toFloat(),
            y.toFloat(),
            (x + width - 1).toFloat(),
            (y + height - 1).toFloat(),
            paint
        )
    }

    /**
     * Draws a resized image on the preview.
     */
    fun putImage(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        dither: Boolean = false
    ) {
        require(width > 0) {
            "Image width must be greater than zero."
        }

        require(height > 0) {
            "Image height must be greater than zero."
        }

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )

        putImage(
            bitmap = resized,
            x = x,
            y = y,
            dither = dither
        )

        if (!resized.sameAs(bitmap)) {
            resized.recycle()
        }
    }

    /**
     * Draws an image without resizing.
     */
    fun putImage(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        dither: Boolean = false
    ) {
        val converted =
            if (dither) {
                getDitheredImage(bitmap)
            } else {
                getThresholdImage(bitmap)
            }

        canvas.drawBitmap(
            converted,
            x.toFloat(),
            y.toFloat(),
            null
        )

        if (converted !== bitmap) {
            converted.recycle()
        }
    }

    /**
     * Converts the complete preview into AIOI 1-bit image data.
     *
     * Each byte contains eight horizontal pixels.
     * The most significant bit represents the leftmost pixel.
     */
    fun getSmartTagImage(
        dither: Boolean = false
    ): ByteArray {
        val bitmap =
            if (dither) {
                getDitheredImage(screen)
            } else {
                getThresholdImage(screen)
            }

        val result = convertBitmapToSmartTagData(bitmap)

        bitmap.recycle()

        return result
    }

    private fun getDitheredImage(
        bitmap: Bitmap
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val source = getGrayPixels(bitmap)
        val destination = IntArray(width * height)

        val diffusion = arrayOf(
            doubleArrayOf(
                0.0,
                0.0,
                7.0 / 16.0
            ),
            doubleArrayOf(
                3.0 / 16.0,
                5.0 / 16.0,
                1.0 / 16.0
            )
        )

        for (y in 0 until height) {
            for (x in 0 until width) {

                val index = x + y * width
                val currentPixel = source[index]

                val output =
                    if (currentPixel > 127.0) {
                        255
                    } else {
                        0
                    }

                destination[index] = output

                val error = currentPixel - output

                for (iy in 0 until 2) {
                    for (ix in -1..1) {

                        val weight = diffusion[iy][ix + 1]

                        if (weight <= 0.0) {
                            continue
                        }

                        val targetX = x + ix
                        val targetY = y + iy

                        if (
                            targetX in 0 until width &&
                            targetY in 0 until height
                        ) {
                            val targetIndex =
                                targetX + targetY * width

                            source[targetIndex] += error * weight
                        }
                    }
                }
            }
        }

        return createBlackWhiteImage(
            destination,
            width,
            height
        )
    }

    private fun getThresholdImage(
        bitmap: Bitmap
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val source = getGrayPixels(bitmap)
        val destination = IntArray(width * height)

        for (index in source.indices) {
            destination[index] =
                if (source[index] > 127.0) {
                    255
                } else {
                    0
                }
        }

        return createBlackWhiteImage(
            destination,
            width,
            height
        )
    }

    private fun getGrayPixels(
        bitmap: Bitmap
    ): DoubleArray {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val range = getPixelRange(pixels)
        val rangeSize = range.y - range.x

        val adjustment =
            if (rangeSize == 0) {
                1.0
            } else {
                255.0 / rangeSize
            }

        val result = DoubleArray(width * height)

        for (index in pixels.indices) {
            val pixel = pixels[index]

            val average = getAverage(
                Color.red(pixel),
                Color.green(pixel),
                Color.blue(pixel)
            )

            result[index] =
                (average - range.x) * adjustment
        }

        return result
    }

    private fun createBlackWhiteImage(
        pixels: IntArray,
        width: Int,
        height: Int
    ): Bitmap {
        val resultPixels = IntArray(width * height)

        for (index in pixels.indices) {
            val value = pixels[index]

            resultPixels[index] = Color.argb(
                255,
                value,
                value,
                value
            )
        }

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        bitmap.setPixels(
            resultPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return bitmap
    }

    private fun getAverage(
        red: Int,
        green: Int,
        blue: Int
    ): Int {
        return (
                red * 0.298912f +
                        green * 0.586611f +
                        blue * 0.114478f
                ).toInt()
    }

    private fun getPixelRange(
        pixels: IntArray
    ): Point {
        var minimum = 255
        var maximum = 0

        for (pixel in pixels) {
            val value = getAverage(
                Color.red(pixel),
                Color.green(pixel),
                Color.blue(pixel)
            )

            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }

        return Point(
            minimum,
            maximum
        )
    }

    private fun convertBitmapToSmartTagData(
        bitmap: Bitmap
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        val columns = (width + 7) / 8
        val result = ByteArray(columns * height)

        var resultIndex = 0

        for (y in 0 until height) {

            var bitPosition = 7

            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)

                if (Color.red(pixel) == 0) {
                    result[resultIndex] =
                        (
                                result[resultIndex].toInt() or
                                        (1 shl bitPosition)
                                ).toByte()
                }

                bitPosition--

                if (
                    bitPosition < 0 ||
                    x == width - 1
                ) {
                    resultIndex++
                    bitPosition = 7
                }
            }
        }

        return result
    }
}