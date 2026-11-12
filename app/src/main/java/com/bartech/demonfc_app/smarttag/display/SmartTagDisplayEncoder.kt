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
     * This matches the official AIOI sample (createFieldAndText). Every
     * field becomes ONE TLV entry that holds the field definition followed
     * directly by its raw text:
     *
     *     [fieldNo][length][field definition][raw text]
     *
     * Rectangle fields have no text, so their entry only holds the
     * definition.
     */
    fun encodeAddAndShow(
        fields: List<SmartTagDisplayField>,
        characterCode: Byte
    ): ByteArray {

        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        var result = ByteArray(0)

        for (field in fields) {

            val definition = field.createFieldData()

            // createTextData() returns [fieldNo][length][text]; only the
            // raw text is needed inside the combined entry.
            val rawText = field.createTextData(characterCode)?.let {
                it.copyOfRange(2, it.size)
            } ?: ByteArray(0)

            val entryData = definition + rawText

            require(entryData.size <= 255) {
                "A display field cannot exceed 255 bytes " +
                        "(field ${field.fieldNo.toInt() and 0xFF})."
            }

            val entry = ByteArray(2 + entryData.size)
            entry[0] = field.fieldNo
            entry[1] = entryData.size.toByte()
            entryData.copyInto(entry, 2)

            result += entry
        }

        return result
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