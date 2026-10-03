package com.bartech.demonfc_app.smarttag.display

import android.graphics.Bitmap
import com.bartech.demonfc_app.smarttag.core.SmartTagProtocol
import com.bartech.demonfc_app.smarttag.model.DisplayInfo
import com.bartech.demonfc_app.smarttag.model.SmartTagModel
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily

class SmartTagDisplay(
    private val protocol: SmartTagProtocol,
    private val model: SmartTagModel,
    private val displayInfo: DisplayInfo
) {

    /**
     * Clears the complete Smart Tag display.
     */
    fun clear() {
        protocol.clearDisplay()
    }

    /**
     * Saves the current display into the specified layout.
     */
    fun saveLayout(layoutNo: Int) {
        validateLayout(layoutNo)
        protocol.saveLayout(layoutNo)
    }

    /**
     * Loads a previously saved layout.
     */
    fun loadLayout(layoutNo: Int) {
        validateLayout(layoutNo)
        protocol.loadLayout(layoutNo)
    }

    /**
     * Displays an Android Bitmap on the Smart Tag.
     *
     * The bitmap is converted to the AIOI 1-bit display format internally.
     */
    fun showImage(
        bitmap: Bitmap,
        x: Int = 0,
        y: Int = 0,
        width: Int? = null,
        height: Int? = null,
        dither: Boolean = false,
        drawMode: Int = 0,
        layoutNo: Int = 0
    ) {
        validateLayout(layoutNo)

        val targetWidth = width ?: bitmap.width
        val targetHeight = height ?: bitmap.height

        validateArea(
            x = x,
            y = y,
            width = targetWidth,
            height = targetHeight
        )

        require(targetWidth > 0) {
            "Image width must be greater than zero."
        }

        require(targetHeight > 0) {
            "Image height must be greater than zero."
        }

        require(drawMode in 0..15) {
            "Draw mode must be between 0 and 15."
        }

        val painter = SmartTagDisplayPainter(
            targetWidth,
            targetHeight
        )

        painter.putImage(
            bitmap = bitmap,
            x = 0,
            y = 0,
            width = targetWidth,
            height = targetHeight,
            dither = dither
        )

        val imageData = painter.getSmartTagImage(
            dither = false
        )

        protocol.showImage(
            imageData = imageData,
            x = x,
            y = y,
            width = targetWidth,
            height = targetHeight,
            drawMode = drawMode,
            layoutNo = layoutNo
        )
    }

    /**
     * Displays a bitmap using its original dimensions.
     */
    fun showBitmap(
        bitmap: Bitmap,
        x: Int = 0,
        y: Int = 0,
        dither: Boolean = false,
        drawMode: Int = 0,
        layoutNo: Int = 0
    ) {
        showImage(
            bitmap = bitmap,
            x = x,
            y = y,
            width = bitmap.width,
            height = bitmap.height,
            dither = dither,
            drawMode = drawMode,
            layoutNo = layoutNo
        )
    }

    /**
     * Displays text using AIOI's Add Field + Show Text command.
     */
    fun showText(
        text: String,
        x: Int,
        y: Int,
        font: Int,
        reverse: Boolean = false,
        fieldNo: Int = 0,
        characterCode: Int = 0,
        displayMode: Int = 0,
        shutdown: Int = 0,
        layoutNo: Int = 0,
        drawMode: Int = 3
    ) {
        ensureTextSupported()

        val field = SmartTagTextField(
            fieldNo = fieldNo.toByte(),
            x = x,
            y = y,
            font = font.toByte(),
            reverse = if (reverse) 1 else 0,
            text = text
        )

        showFields(
            fields = listOf(field),
            characterCode = characterCode,
            displayMode = displayMode,
            shutdown = shutdown,
            layoutNo = layoutNo,
            drawMode = drawMode
        )
    }

    /**
     * Displays a barcode.
     */
    fun showBarcode(
        text: String,
        x: Int,
        y: Int,
        height: Int,
        codeType: Int,
        checkDigit: Boolean = false,
        reverse: Boolean = false,
        fieldNo: Int = 0,
        characterCode: Int = 0,
        displayMode: Int = 0,
        shutdown: Int = 0,
        layoutNo: Int = 0,
        drawMode: Int = 3
    ) {
        ensureTextSupported()

        val field = SmartTagBarcodeField(
            fieldNo = fieldNo.toByte(),
            x = x,
            y = y,
            height = height,
            codeType = codeType.toByte(),
            checkDigit = if (checkDigit) 1 else 0,
            reverse = if (reverse) 1 else 0,
            text = text
        )

        showFields(
            fields = listOf(field),
            characterCode = characterCode,
            displayMode = displayMode,
            shutdown = shutdown,
            layoutNo = layoutNo,
            drawMode = drawMode
        )
    }

    /**
     * Displays a rectangle.
     */
    fun showRectangle(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        reverse: Boolean = false,
        fieldNo: Int = 0,
        characterCode: Int = 0,
        displayMode: Int = 0,
        shutdown: Int = 0,
        layoutNo: Int = 0,
        drawMode: Int = 3
    ) {
        ensureTextSupported()

        val field = SmartTagRectangleField(
            fieldNo = fieldNo.toByte(),
            x = x,
            y = y,
            width = width,
            height = height,
            reverse = if (reverse) 1 else 0
        )

        showFields(
            fields = listOf(field),
            characterCode = characterCode,
            displayMode = displayMode,
            shutdown = shutdown,
            layoutNo = layoutNo,
            drawMode = drawMode
        )
    }

    /**
     * Displays multiple fields in one AIOI Add Field + Show Text command.
     */
    fun showFields(
        fields: List<SmartTagDisplayField>,
        characterCode: Int = 0,
        displayMode: Int = 0,
        shutdown: Int = 0,
        layoutNo: Int = 0,
        drawMode: Int = 3
    ) {
        ensureTextSupported()

        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        require(fields.size <= 20) {
            "A maximum of 20 display fields is supported."
        }

        validateLayout(layoutNo)

        validateCharacterCode(characterCode)

        require(displayMode in 0..255) {
            "Display mode must be between 0 and 255."
        }

        require(shutdown in 0..255) {
            "Shutdown value must be between 0 and 255."
        }

        require(drawMode in 0..15) {
            "Draw mode must be between 0 and 15."
        }

        validateFieldNumbers(fields)

        for (field in fields) {
            validateFieldPosition(field)
        }

        protocol.addFieldsAndShowText(
            fields = fields,
            characterCode = characterCode.toByte(),
            displayMode = displayMode.toByte(),
            shutdown = shutdown.toByte(),
            layoutNo = layoutNo.toByte(),
            drawMode = drawMode.toByte()
        )
    }

    /**
     * Creates an Android preview without communicating with the tag.
     *
     * This is useful for testing and for the Activity/UI layer.
     */
    fun createPreview(
        draw: SmartTagDisplayPainter.() -> Unit
    ): Bitmap {
        val painter = SmartTagDisplayPainter(
            displayInfo.width,
            displayInfo.height
        )

        painter.draw()

        return painter.getPreview()
    }

    fun getDisplayInfo(): DisplayInfo {
        return displayInfo
    }

    fun getModel(): SmartTagModel {
        return model
    }

    fun getWidth(): Int {
        return displayInfo.width
    }

    fun getHeight(): Int {
        return displayInfo.height
    }

    fun supportsText(): Boolean {
        return model.family != SmartTagProductFamily.SMARTCARD_29
    }

    private fun ensureTextSupported() {
        require(supportsText()) {
            "Text fields are not supported by this Smart Tag model."
        }
    }

    private fun validateLayout(layoutNo: Int) {
        require(layoutNo in 0..255) {
            "Layout number must be between 0 and 255."
        }
    }

    private fun validateCharacterCode(characterCode: Int) {
        require(characterCode in 0..255) {
            "Character code must be between 0 and 255."
        }
    }

    private fun validateFieldNumbers(
        fields: List<SmartTagDisplayField>
    ) {
        val numbers = fields.map {
            it.fieldNo.toInt() and 0xFF
        }

        require(numbers.distinct().size == numbers.size) {
            "Each display field must have a unique field number."
        }

        for (number in numbers) {
            require(number in 0..255) {
                "Field number must be between 0 and 255."
            }
        }
    }

    private fun validateFieldPosition(
        field: SmartTagDisplayField
    ) {
        require(field.x >= 0) {
            "Field X position cannot be negative."
        }

        require(field.y >= 0) {
            "Field Y position cannot be negative."
        }

        require(field.x < displayInfo.width) {
            "Field X position is outside the display."
        }

        require(field.y < displayInfo.height) {
            "Field Y position is outside the display."
        }
    }

    private fun validateArea(
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        require(x >= 0) {
            "X position cannot be negative."
        }

        require(y >= 0) {
            "Y position cannot be negative."
        }

        require(width > 0) {
            "Width must be greater than zero."
        }

        require(height > 0) {
            "Height must be greater than zero."
        }

        require(x + width <= displayInfo.width) {
            "The image exceeds the Smart Tag display width."
        }

        require(y + height <= displayInfo.height) {
            "The image exceeds the Smart Tag display height."
        }
    }
}