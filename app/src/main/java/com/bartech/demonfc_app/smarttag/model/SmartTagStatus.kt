package com.bartech.demonfc_app.smarttag.model

enum class SmartTagStatus(val value: Byte) {

    INIT(0x00),

    PROCESSED(0xF0.toByte()),

    BUSY(0xF2.toByte()),

    UNKNOWN(0x70.toByte());

    companion object {

        fun fromValue(value: Byte): SmartTagStatus {
            return entries.firstOrNull { it.value == value }
                ?: UNKNOWN
        }
    }
}