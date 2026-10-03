package com.bartech.demonfc_app.smarttag.display

sealed class SmartTagDisplayField {

    abstract val fieldNo: Byte
    abstract val x: Int
    abstract val y: Int
    abstract val reverse: Byte

    /**
     * Creates the field definition data required
     * by the AIOI Smart Tag protocol.
     */
    abstract fun createFieldData(): ByteArray

    /**
     * Creates the text data for this field.
     *
     * Returns null when the field does not contain text.
     */
    abstract fun createTextData(
        characterCode: Byte
    ): ByteArray?
}