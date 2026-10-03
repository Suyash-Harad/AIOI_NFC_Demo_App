package com.bartech.demonfc_app.smarttag.model

enum class SmartTagProductFamily {
    SMARTTAG_20,
    SMARTTAG_27,
    SMARTCARD_29,
    SMARTTAG_29,
    SMARTTAG_37,
    UNKNOWN
}

enum class SmartTagVariant {
    L,
    U,
    UNKNOWN
}

data class SmartTagModel(
    val family: SmartTagProductFamily,
    val variant: SmartTagVariant = SmartTagVariant.UNKNOWN
) {

    val isKnown: Boolean
        get() = family != SmartTagProductFamily.UNKNOWN

    /**
     * SC1029L / SC1029U do not support Show Texts.
     */
    val supportsText: Boolean
        get() = family != SmartTagProductFamily.SMARTCARD_29

    companion object {

        val UNKNOWN = SmartTagModel(
            family = SmartTagProductFamily.UNKNOWN,
            variant = SmartTagVariant.UNKNOWN
        )
    }
}