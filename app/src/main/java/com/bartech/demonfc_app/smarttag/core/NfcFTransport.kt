package com.bartech.demonfc_app.smarttag.core

import android.nfc.Tag
import android.nfc.tech.NfcF

class NfcFTransport {

    private var nfcF: NfcF? = null

    private var currentTag: Tag? = null

    fun selectTag(tag: Tag) {

        val tech =
            NfcF.get(tag)
                ?: throw SmartTagException(
                    "The detected tag does not support NFC-F."
                )

        nfcF = tech
        currentTag = tag
    }

    fun connect() {

        val nfc =
            nfcF
                ?: throw SmartTagException(
                    "No NFC-F tag has been selected."
                )

        try {

            if (!nfc.isConnected) {

                nfc.connect()
            }

        } catch (e: Exception) {

            throw SmartTagException(
                "Unable to connect to NFC-F tag: " +
                        (
                                e.message
                                    ?: e.javaClass.simpleName
                                ),
                e
            )
        }
    }

    fun transceive(
        command: ByteArray
    ): ByteArray {

        val nfc =
            nfcF
                ?: throw SmartTagException(
                    "No NFC-F tag has been selected."
                )

        if (!nfc.isConnected) {

            throw SmartTagException(
                "NFC-F tag is not connected."
            )
        }

        return try {

            nfc.transceive(command)

        } catch (e: Exception) {

            throw SmartTagException(
                "NFC-F transceive failed: " +
                        (
                                e.message
                                    ?: e.javaClass.simpleName
                                ),
                e
            )
        }
    }

    fun close() {

        try {
            nfcF?.close()
        } catch (_: Exception) {
        } finally {
            nfcF = null
            currentTag = null
        }
    }

    fun isConnected(): Boolean {
        return nfcF?.isConnected == true
    }

    fun getTag(): Tag? {
        return currentTag
    }

    fun getIdm(): ByteArray? {
        return nfcF?.tag?.id?.copyOf()
    }

    fun getSystemCode(): ByteArray? {
        return nfcF?.systemCode?.copyOf()
    }
}