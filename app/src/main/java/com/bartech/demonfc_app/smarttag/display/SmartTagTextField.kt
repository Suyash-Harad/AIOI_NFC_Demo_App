package com.bartech.demonfc_app.smarttag.display

class SmartTagTextField(
    override val fieldNo: Byte,
    override val x: Int,
    override val y: Int,
    private val font: Byte,
    override val reverse: Byte,
    private val text: String
) : SmartTagDisplayField() {

    override fun createFieldData(): ByteArray {

        val data = ByteArray(6)

        // X coordinate - little endian
        data[0] = (x and 0xFF).toByte()
        data[1] = ((x shr 8) and 0xFF).toByte()

        // Y coordinate - little endian
        data[2] = (y and 0xFF).toByte()
        data[3] = ((y shr 8) and 0xFF).toByte()

        // Font
        data[4] = font

        // Reverse
        data[5] = reverse

        return data
    }

    override fun createTextData(
        characterCode: Byte
    ): ByteArray {

        val encodedText = when (characterCode.toInt() and 0xFF) {

            // UTF-16 Little Endian
            0 -> text.toByteArray(
                Charsets.UTF_16LE
            )

            // UTF-16 Big Endian
            1 -> text.toByteArray(
                Charsets.UTF_16BE
            )

            // Shift-JIS
            2 -> text.toByteArray(
                charset("Shift_JIS")
            )

            else -> {
                throw IllegalArgumentException(
                    "Unsupported character code: " +
                            "${characterCode.toInt() and 0xFF}"
                )
            }
        }

        return ByteArray(
            2 + encodedText.size
        ).apply {

            /*
             * AIOI text TLV:
             *
             * byte 0 = field number
             * byte 1 = text data length
             * byte 2+ = encoded text
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