package com.bartech.demonfc_app.smarttag.display

object SmartTagDisplayEncoder {

    /**
     * Encodes field definitions for AIOI Add Field.
     *
     * Each field is encoded as:
     *
     * [fieldNo][length][fieldData]
     */
    fun encodeFieldDefinitions(
        fields: List<SmartTagDisplayField>
    ): ByteArray {

        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        return fields.fold(
            initial = ByteArray(0)
        ) { result, field ->

            result + encodeFieldDefinition(field)
        }
    }


    /**
     * Encodes text data for AIOI Show Text.
     *
     * Rectangle fields do not have text data.
     */
    fun encodeFieldTexts(
        fields: List<SmartTagDisplayField>,
        characterCode: Byte
    ): ByteArray {

        return fields.fold(
            initial = ByteArray(0)
        ) { result, field ->

            val textData =
                field.createTextData(characterCode)
                    ?: ByteArray(0)

            result + textData
        }
    }


    /**
     * Encodes the combined function data used by:
     *
     * COMMAND_ADD_FIELD_SHOW_TEXT
     *
     * The AIOI format consists of:
     *
     *     Field definitions
     *     +
     *     Field text data
     */
    fun encodeAddAndShow(
        fields: List<SmartTagDisplayField>,
        characterCode: Byte
    ): ByteArray {

        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        val fieldData =
            encodeFieldDefinitions(fields)

        val textData =
            encodeFieldTexts(
                fields = fields,
                characterCode = characterCode
            )

        return fieldData + textData
    }


    /**
     * Encodes all fields in one operation.
     */
    fun encodeFields(
        fields: List<SmartTagDisplayField>,
        characterCode: Byte
    ): ByteArray {

        return encodeAddAndShow(
            fields = fields,
            characterCode = characterCode
        )
    }


    private fun encodeFieldDefinition(
        field: SmartTagDisplayField
    ): ByteArray {

        val fieldData =
            field.createFieldData()

        require(fieldData.size <= 255) {
            "Field data cannot exceed 255 bytes."
        }

        return ByteArray(
            2 + fieldData.size
        ).apply {

            /*
             * TLV:
             *
             * byte 0 = field number
             * byte 1 = data length
             * byte 2+ = field data
             */
            this[0] =
                field.fieldNo

            this[1] =
                fieldData.size.toByte()

            System.arraycopy(
                fieldData,
                0,
                this,
                2,
                fieldData.size
            )
        }
    }
}