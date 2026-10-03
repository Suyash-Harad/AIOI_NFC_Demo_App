package com.bartech.demonfc_app.smarttag.display

class SmartTagBarcodeField(
    override val fieldNo: Byte,
    override val x: Int,
    override val y: Int,
    override val reverse: Byte,
    private val height: Int,
    private val codeType: Byte,
    private val checkDigit: Byte,
    private val text: String
) : SmartTagDisplayField() {

    companion object {
        private const val BARCODE_FONT: Byte = 11
    }

    override fun createFieldData(): ByteArray {

        val data = ByteArray(10)

        // X coordinate - little endian
        data[0] = (x and 0xFF).toByte()
        data[1] = ((x shr 8) and 0xFF).toByte()

        // Y coordinate - little endian
        data[2] = (y and 0xFF).toByte()
        data[3] = ((y shr 8) and 0xFF).toByte()

        // Barcode font
        data[4] = BARCODE_FONT

        // Reverse
        data[5] = reverse

        // Barcode height - little endian
        data[6] = (height and 0xFF).toByte()
        data[7] = ((height shr 8) and 0xFF).toByte()

        // Barcode type
        data[8] = codeType

        // Check digit
        data[9] = checkDigit

        return data
    }

    override fun createTextData(
        characterCode: Byte
    ): ByteArray {

        /*
         * AIOI BarcodeField uses ASCII regardless
         * of the selected display character code.
         */
        val encodedText = text.toByteArray(
            Charsets.US_ASCII
        )

        require(encodedText.size <= 255) {
            "Barcode text cannot exceed 255 bytes."
        }

        return ByteArray(
            2 + encodedText.size
        ).apply {

            /*
             * AIOI text TLV:
             *
             * byte 0 = field number
             * byte 1 = text length
             * byte 2+ = ASCII barcode data
             */

            this[0] = fieldNo
            this[1] = encodedText.size.toByte()

            System.arraycopy(
                encodedText,
                0,
                this,
                2,
                encodedText.size
            )
        }
    }
}