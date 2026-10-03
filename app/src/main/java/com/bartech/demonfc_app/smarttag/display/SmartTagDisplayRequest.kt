package com.bartech.demonfc_app.smarttag.display

data class SmartTagDisplayRequest(
    val fields: List<SmartTagDisplayField>,
    val characterCode: Byte = 0,
    val displayMode: Byte = 0,
    val shutdown: Byte = 0,
    val layoutNo: Byte = 0,
    val drawMode: Byte = 3
) {

    init {
        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        require(fields.size <= 20) {
            "A maximum of 20 display fields is supported."
        }

        require(
            (characterCode.toInt() and 0xFF) in 0..2
        ) {
            "Unsupported character code: " +
                    "${characterCode.toInt() and 0xFF}"
        }

        require(
            (displayMode.toInt() and 0xFF) in 0..255
        ) {
            "Invalid display mode."
        }

        require(
            (shutdown.toInt() and 0xFF) in 0..255
        ) {
            "Invalid shutdown value."
        }

        require(
            (layoutNo.toInt() and 0xFF) in 0..255
        ) {
            "Invalid layout number."
        }

        require(
            (drawMode.toInt() and 0xFF) in 0..15
        ) {
            "Draw mode must be between 0 and 15."
        }
    }

    companion object {

        fun text(
            fieldNo: Byte,
            x: Int,
            y: Int,
            font: Byte,
            reverse: Byte,
            text: String,
            characterCode: Byte = 0,
            displayMode: Byte = 0,
            shutdown: Byte = 0,
            layoutNo: Byte = 0,
            drawMode: Byte = 3
        ): SmartTagDisplayRequest {

            return SmartTagDisplayRequest(
                fields = listOf(
                    SmartTagTextField(
                        fieldNo = fieldNo,
                        x = x,
                        y = y,
                        font = font,
                        reverse = reverse,
                        text = text
                    )
                ),
                characterCode = characterCode,
                displayMode = displayMode,
                shutdown = shutdown,
                layoutNo = layoutNo,
                drawMode = drawMode
            )
        }

        fun barcode(
            fieldNo: Byte,
            x: Int,
            y: Int,
            height: Int,
            codeType: Byte,
            checkDigit: Byte,
            text: String,
            reverse: Byte = 0,
            characterCode: Byte = 0,
            displayMode: Byte = 0,
            shutdown: Byte = 0,
            layoutNo: Byte = 0,
            drawMode: Byte = 3
        ): SmartTagDisplayRequest {

            return SmartTagDisplayRequest(
                fields = listOf(
                    SmartTagBarcodeField(
                        fieldNo = fieldNo,
                        x = x,
                        y = y,
                        reverse = reverse,
                        height = height,
                        codeType = codeType,
                        checkDigit = checkDigit,
                        text = text
                    )
                ),
                characterCode = characterCode,
                displayMode = displayMode,
                shutdown = shutdown,
                layoutNo = layoutNo,
                drawMode = drawMode
            )
        }

        fun rectangle(
            fieldNo: Byte,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            reverse: Byte = 0,
            characterCode: Byte = 0,
            displayMode: Byte = 0,
            shutdown: Byte = 0,
            layoutNo: Byte = 0,
            drawMode: Byte = 3
        ): SmartTagDisplayRequest {

            return SmartTagDisplayRequest(
                fields = listOf(
                    SmartTagRectangleField(
                        fieldNo = fieldNo,
                        x = x,
                        y = y,
                        reverse = reverse,
                        width = width,
                        height = height
                    )
                ),
                characterCode = characterCode,
                displayMode = displayMode,
                shutdown = shutdown,
                layoutNo = layoutNo,
                drawMode = drawMode
            )
        }
    }
}