package com.bartech.demonfc_app.smarttag.model

data class SmartTagInfo(
    val model: SmartTagModel,
    val idm: ByteArray,
    val systemCode: ByteArray?,
    var firmwareVersion: Byte,
    val displayInfo: DisplayInfo?
) {

    fun idmHex(): String {
        return idm.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    fun systemCodeHex(): String {
        return systemCode?.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        } ?: ""
    }

    fun firmwareVersionNumber(): Int {
        return firmwareVersion.toInt() and 0xFF
    }
}