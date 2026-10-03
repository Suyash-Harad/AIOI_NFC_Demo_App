package com.bartech.demonfc_app.smarttag.display

class SmartTagRectangleField(
    override val fieldNo: Byte,
    override val x: Int,
    override val y: Int,
    override val reverse: Byte,
    private val width: Int,
    private val height: Int
) : SmartTagDisplayField() {

    companion object {
        private const val RECTANGLE_FONT: Byte = 10
    }

    override fun createFieldData(): ByteArray {

        val data = ByteArray(10)

        // X coordinate - little endian
        data[0] = (x and 0xFF).toByte()
        data[1] = ((x shr 8) and 0xFF).toByte()

        // Y coordinate - little endian
        data[2] = (y and 0xFF).toByte()
        data[3] = ((y shr 8) and 0xFF).toByte()

        // Rectangle font/type
        data[4] = RECTANGLE_FONT

        // Reverse
        data[5] = reverse

        // Height - little endian
        data[6] = (height and 0xFF).toByte()
        data[7] = ((height shr 8) and 0xFF).toByte()

        // Width - little endian
        data[8] = (width and 0xFF).toByte()
        data[9] = ((width shr 8) and 0xFF).toByte()

        return data
    }

    override fun createTextData(
        characterCode: Byte
    ): ByteArray? {
        // Rectangle fields have no text data.
        return null
    }
}