package com.bartech.demonfc_app.smarttag

import android.nfc.Tag
import com.bartech.demonfc_app.smarttag.core.AioiSmartTagDetector
import com.bartech.demonfc_app.smarttag.core.NfcFTransport
import com.bartech.demonfc_app.smarttag.core.SmartTagException
import com.bartech.demonfc_app.smarttag.core.SmartTagProtocol
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplay
import com.bartech.demonfc_app.smarttag.model.SmartTagInfo
import com.bartech.demonfc_app.smarttag.model.SmartTagStatus

class SmartTagManager {

    // ============================================================
    // Internal components
    // ============================================================

    private val transport = NfcFTransport()

    private val protocol = SmartTagProtocol(
        transport
    )

    // ============================================================
    // Current Tag
    // ============================================================

    private var currentInfo: SmartTagInfo? = null

    // ============================================================
    // Display
    // ============================================================

    /**
     * Creates the Smart Tag display API for the currently
     * selected tag.
     */
    fun createDisplay(): SmartTagDisplay {

        val info = currentInfo
            ?: throw SmartTagException(
                "No Smart Tag has been selected."
            )

        val displayInfo = info.displayInfo
            ?: throw SmartTagException(
                "Display information is unavailable for this Smart Tag."
            )

        return SmartTagDisplay(
            protocol = protocol,
            model = info.model,
            displayInfo = displayInfo
        )
    }

    // ============================================================
    // Tag Selection
    // ============================================================

    /**
     * Selects and identifies an AIOI Smart Tag.
     *
     * This does not connect to the tag.
     */
    fun selectTag(tag: Tag): SmartTagInfo {

        val idm = tag.id.copyOf()

        if (!AioiSmartTagDetector.isSmartTag(idm)) {
            throw SmartTagException(
                "The detected NFC tag is not a supported AIOI Smart Tag."
            )
        }

        // Store the NFC-F tag inside the transport.
        transport.selectTag(tag)

        val model =
            AioiSmartTagDetector.detectModel(idm)

        val systemCode =
            transport.getSystemCode()

        val displayInfo =
            AioiSmartTagDetector.getDisplayInfo(model)

        val info = SmartTagInfo(
            model = model,
            idm = idm,
            systemCode = systemCode,
            firmwareVersion = 0,
            displayInfo = displayInfo
        )

        currentInfo = info

        return info
    }

    // ============================================================
    // Connection
    // ============================================================

    fun connect() {

        ensureTagSelected()

        protocol.connect()
    }

    fun disconnect() {

        protocol.close()

        currentInfo = null
    }

    fun isConnected(): Boolean {

        return protocol.isConnected()
    }

    // ============================================================
    // Tag Information
    // ============================================================

    fun getTagInfo(): SmartTagInfo? {

        return currentInfo
    }

    fun getTagModel() =
        currentInfo?.model

    fun getDisplayInfo() =
        currentInfo?.displayInfo

    fun getIdm(): ByteArray? {

        return currentInfo?.idm?.copyOf()
    }

    // ============================================================
    // Status
    // ============================================================

    fun checkStatus(): SmartTagStatus {

        ensureConnected()

        val status =
            protocol.checkStatus()

        currentInfo?.firmwareVersion =
            protocol.firmwareVersion

        return status
    }

    fun waitForIdle() {

        ensureConnected()

        protocol.waitForIdle()
    }

    // ============================================================
    // User Area
    // ============================================================

    /**
     * Writes data into the Smart Tag User Area.
     *
     * The protocol layer handles the AIOI DATA WRITE
     * command and 512-byte frame splitting.
     */
    fun writeUserData(
        startAddress: Int,
        data: ByteArray
    ) {

        ensureConnected()

        protocol.writeUserData(
            startAddress = startAddress,
            data = data
        )
    }

    /**
     * Reads data from the Smart Tag User Area.
     *
     * The protocol layer handles the AIOI DATA READ
     * command and NFC-F response parsing.
     */
    fun readUserData(
        startAddress: Int,
        size: Int
    ): ByteArray {

        ensureConnected()

        return protocol.readUserData(
            startAddress = startAddress,
            size = size
        )
    }

    // ============================================================
    // Card Area
    // ============================================================

    /**
     * Reads blocks from the AIOI Card Area.
     *
     * blockCount represents the number of 16-byte blocks.
     */
    fun readCardArea(
        startBlock: Int,
        blockCount: Int
    ): ByteArray {

        ensureConnected()

        return protocol.readCardArea(
            startBlock = startBlock,
            blockCount = blockCount
        )
    }

    /**
     * Writes complete 16-byte blocks to the AIOI Card Area.
     */
    fun writeCardArea(
        startBlock: Int,
        data: ByteArray
    ) {

        ensureConnected()

        protocol.writeCardArea(
            startBlock = startBlock,
            data = data
        )
    }

    // ============================================================
    // Display
    // ============================================================

    fun clearDisplay() {

        ensureConnected()

        protocol.clearDisplay()
    }

    fun saveLayout(
        layoutNo: Int
    ) {

        ensureConnected()

        protocol.saveLayout(
            layoutNo
        )
    }

    fun loadLayout(
        layoutNo: Int
    ) {

        ensureConnected()

        protocol.loadLayout(
            layoutNo
        )
    }

    // ============================================================
    // Internal Validation
    // ============================================================

    private fun ensureTagSelected() {

        if (currentInfo == null) {

            throw SmartTagException(
                "No Smart Tag has been selected."
            )
        }
    }

    private fun ensureConnected() {

        ensureTagSelected()

        if (!protocol.isConnected()) {

            throw SmartTagException(
                "Smart Tag is not connected."
            )
        }
    }
}