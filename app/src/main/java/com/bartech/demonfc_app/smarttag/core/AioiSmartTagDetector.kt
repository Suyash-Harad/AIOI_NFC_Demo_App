package com.bartech.demonfc_app.smarttag.core

import com.bartech.demonfc_app.smarttag.model.DisplayInfo
import com.bartech.demonfc_app.smarttag.model.SmartTagModel
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily

object AioiSmartTagDetector {

    private const val SMART_TAG_PREFIX_0 = 0x02
    private const val SMART_TAG_PREFIX_1 = 0xFE
    private const val SMART_TAG_PREFIX_2 = 0x00
    private const val SMART_TAG_PREFIX_3 = 0x00

    /*
     * AIOI product type values from the official Smart Tag protocol.
     */
    private const val SMARTTAG_20 = 20
    private const val SMARTTAG_27 = 27
    private const val SMARTCARD_29 = 29
    private const val SMARTTAG_29 = 1129
    private const val SMARTTAG_37 = 37


    /**
     * Checks whether the supplied IDm belongs to an AIOI Smart Tag.
     *
     * AIOI Smart Tags use:
     *
     * 02 FE 00 00 ...
     *
     * as the IDm prefix.
     */
    fun isSmartTag(idm: ByteArray): Boolean {

        if (idm.size < 8) {
            return false
        }

        return (idm[0].toInt() and 0xFF) == SMART_TAG_PREFIX_0 &&
                (idm[1].toInt() and 0xFF) == SMART_TAG_PREFIX_1 &&
                (idm[2].toInt() and 0xFF) == SMART_TAG_PREFIX_2 &&
                (idm[3].toInt() and 0xFF) == SMART_TAG_PREFIX_3
    }


    /**
     * Detects the AIOI product family from IDm.
     *
     * Important:
     * The official protocol identifies the product family using IDm[4].
     * It does not provide enough information here to reliably determine
     * the L/U variant for every product.
     *
     * Therefore the variant remains UNKNOWN unless it can be determined
     * by another reliable source later.
     */
    fun detectModel(idm: ByteArray): SmartTagModel {

        if (!isSmartTag(idm)) {
            return SmartTagModel.UNKNOWN
        }

        val productByte = idm[4].toInt() and 0xFF

        val family = when {

            // SmartTag 2.0
            (productByte and 0xF0) == 0x00 -> {
                SmartTagProductFamily.SMARTTAG_20
            }

            // SmartTag 2.7
            productByte == 0x12 -> {
                SmartTagProductFamily.SMARTTAG_27
            }

            // SmartCard 2.9
            productByte == 0x31 ||
                    productByte == 0x33 ||
                    productByte == 0x35 ||
                    productByte == 0x36 ||
                    productByte == 0x37 -> {
                SmartTagProductFamily.SMARTCARD_29
            }

            // SmartTag 2.9
            productByte == 0x41 ||
                    productByte == 0x42 ||
                    productByte == 0x43 -> {
                SmartTagProductFamily.SMARTTAG_29
            }

            // SmartTag 3.7
            productByte == 0x44 ||
                    productByte == 0x46 -> {
                SmartTagProductFamily.SMARTTAG_37
            }

            else -> {
                SmartTagProductFamily.UNKNOWN
            }
        }

        return SmartTagModel(
            family = family
        )
    }


    /**
     * Returns display information for the detected product family.
     *
     * Display dimensions are taken from the AIOI sample's supported
     * display configurations.
     */
    fun getDisplayInfo(model: SmartTagModel): DisplayInfo? {

        return when (model.family) {

            SmartTagProductFamily.SMARTTAG_20 -> {
                DisplayInfo(
                    width = 200,
                    height = 96
                )
            }

            SmartTagProductFamily.SMARTTAG_27 -> {
                DisplayInfo(
                    width = 264,
                    height = 176
                )
            }

            SmartTagProductFamily.SMARTCARD_29 -> {
                DisplayInfo(
                    width = 300,
                    height = 200
                )
            }

            SmartTagProductFamily.SMARTTAG_29 -> {
                DisplayInfo(
                    width = 296,
                    height = 128
                )
            }

            SmartTagProductFamily.SMARTTAG_37 -> {
                DisplayInfo(
                    width = 480,
                    height = 280
                )
            }

            SmartTagProductFamily.UNKNOWN -> {
                null
            }
        }
    }


    /**
     * Convenience method for checking whether the detected model
     * supports text fields.
     *
     * SC1029 / SmartCard 2.9 does not support Show Texts.
     */
    fun supportsText(model: SmartTagModel): Boolean {
        return model.family != SmartTagProductFamily.SMARTCARD_29
    }
}