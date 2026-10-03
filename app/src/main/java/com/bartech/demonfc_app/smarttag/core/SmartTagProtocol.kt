package com.bartech.demonfc_app.smarttag.core

import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayField
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayEncoder
import com.bartech.demonfc_app.smarttag.model.SmartTagStatus

class SmartTagProtocol(
    private val transport: NfcFTransport
) {

    companion object {
        private const val MAX_WRITE_SIZE = 512

        private const val USER_AREA_MAX_READ_BLOCKS = 12

        private const val CARD_AREA_SERVICE_CODE = 0x0009

        private const val CARD_AREA_READ_COMMAND = 0x06
        private const val CARD_AREA_WRITE_COMMAND = 0x08

        private const val BLOCK_SIZE = 16
    }

    private var status: SmartTagStatus = SmartTagStatus.UNKNOWN

    var firmwareVersion: Byte = 0
        private set

    /**
     * Connects to the selected NFC-F tag.
     */
    fun connect() {
        transport.connect()
    }

    /**
     * Closes the NFC connection.
     */
    fun close() {
        transport.close()
    }

    fun isConnected(): Boolean {
        return transport.isConnected()
    }

    /**
     * Performs the AIOI Check Status command.
     */
    fun checkStatus(): SmartTagStatus {

        val params = ByteArray(8)

        val commands = SmartTagCommandBuilder.buildCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_CHECK_STATUS,
            paramData = params
        )

        require(commands.isNotEmpty()) {
            "Failed to build status command."
        }

        sendCommandFrame(commands.first())

        val blockData = readNormalBlocks(0, 2)

        require(blockData.size >= BLOCK_SIZE) {
            "Invalid status response from Smart Tag."
        }

        status = SmartTagStatus.fromValue(
            blockData[3]
        )

        firmwareVersion = blockData[15]
        SmartTagCommandBuilder.setSequence((blockData[4].toInt() and 0xFF) + 1)

        return status
    }

    fun getStatus(): SmartTagStatus {
        return status
    }

    /**
     * Waits until the Smart Tag is no longer busy.
     */
    fun waitForIdle(
        maxAttempts: Int = 600,
        delayMs: Long = 50L
    ): SmartTagStatus {

        require(maxAttempts > 0) {
            "maxAttempts must be greater than zero."
        }

        require(delayMs >= 0) {
            "delayMs cannot be negative."
        }

        repeat(maxAttempts) {

            val currentStatus = checkStatus()

            if (
                currentStatus != SmartTagStatus.BUSY &&
                currentStatus != SmartTagStatus.UNKNOWN
            ) {
                return currentStatus
            }

            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }
        }

        throw SmartTagException(
            "Smart Tag did not become idle within the expected time."
        )
    }

    /**
     * Clears the Smart Tag display.
     */
    fun clearDisplay() {

        executeCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_CLEAR,
            paramData = ByteArray(8)
        )

        waitForIdle()
    }

    /**
     * Saves the current display to a layout.
     */
    fun saveLayout(
        layoutNo: Int
    ) {
        require(layoutNo in 0..255) {
            "Layout number must be between 0 and 255."
        }

        val params = byteArrayOf(
            layoutNo.toByte(), 0, 0, 0,
            0, 0, 0, 0
        )

        executeCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_SAVE_LAYOUT,
            paramData = params
        )

        waitForIdle()
    }

    /**
     * Loads a saved layout.
     *
     * This follows the AIOI sample exactly:
     *
     * 00 00 00 00 00 00 layoutNo 31
     */
    fun loadLayout(
        layoutNo: Int
    ) {
        require(layoutNo in 0..255) {
            "Layout number must be between 0 and 255."
        }

        val params = byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            layoutNo.toByte(),
            0x31
        )

        executeCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_SHOW_DISPLAY3,
            paramData = params
        )

        waitForIdle()
    }

    /**
     * Displays already packed AIOI 1-bit image data.
     */
    fun showImage(
        imageData: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        drawMode: Int = 0,
        layoutNo: Int = 0
    ) {

        require(imageData.isNotEmpty()) {
            "Image data cannot be empty."
        }

        require(x >= 0) {
            "X position cannot be negative."
        }

        require(y >= 0) {
            "Y position cannot be negative."
        }

        require(width > 0) {
            "Image width must be greater than zero."
        }

        require(height > 0) {
            "Image height must be greater than zero."
        }

        require(drawMode in 0..15) {
            "Draw mode must be between 0 and 15."
        }

        require(layoutNo in 0..255) {
            "Layout number must be between 0 and 255."
        }

        val position =
            convertTo3Bytes(x, y)

        val size =
            convertTo3Bytes(width, height)

        val mode =
            ((drawMode and 0x0F) shl 4) or 0x03

        val params = byteArrayOf(
            position[0],
            position[1],
            position[2],

            size[0],
            size[1],
            size[2],

            layoutNo.toByte(),
            mode.toByte()
        )

        executeCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_SHOW_DISPLAY3,
            paramData = params,
            functionData = imageData
        )

        waitForIdle()
    }

    /**
     * Adds display fields and immediately shows them.
     */
    fun addFieldsAndShowText(
        fields: List<SmartTagDisplayField>,
        characterCode: Byte = 0,
        displayMode: Byte = 0,
        shutdown: Byte = 0,
        layoutNo: Byte = 0,
        drawMode: Byte = 3
    ) {

        require(fields.isNotEmpty()) {
            "At least one display field is required."
        }

        require(fields.size <= 20) {
            "A maximum of 20 display fields is supported."
        }

        val functionData =
            SmartTagDisplayEncoder.encodeAddAndShow(
                fields = fields,
                characterCode = characterCode
            )

        val params = byteArrayOf(
            fields.size.toByte(),
            characterCode,
            0x00,
            0x00,
            displayMode,
            shutdown,
            layoutNo,
            drawMode
        )

        executeCommand(
            functionNo =
                SmartTagCommandBuilder.COMMAND_ADD_FIELD_SHOW_TEXT,
            paramData = params,
            functionData = functionData
        )

        waitForIdle()
    }

    /**
     * Writes data into the Smart Tag user area.
     *
     * AIOI limits each write to 512 bytes.
     */
    fun writeUserData(
        startAddress: Int,
        data: ByteArray
    ) {

        require(startAddress >= 0) {
            "Address cannot be negative."
        }

        if (data.isEmpty()) {
            return
        }

        var currentAddress = startAddress
        var offset = 0

        while (offset < data.size) {

            val remaining = data.size - offset

            val chunkSize =
                minOf(
                    MAX_WRITE_SIZE,
                    remaining
                )

            val chunk =
                data.copyOfRange(
                    offset,
                    offset + chunkSize
                )

            val commands =
                SmartTagCommandBuilder.buildDataWriteCommand(
                    startAddress = currentAddress,
                    functionData = chunk
                )

            for (command in commands) {
                sendCommandFrame(command)
            }

            waitForIdle()

            currentAddress += chunkSize
            offset += chunkSize
        }
    }

    /**
     * Reads data from the Smart Tag user area.
     */
    fun readUserData(
        startAddress: Int,
        size: Int
    ): ByteArray {

        require(startAddress >= 0) {
            "Address cannot be negative."
        }

        require(size >= 0) {
            "Length cannot be negative."
        }

        if (size == 0) {
            return ByteArray(0)
        }

        val maxReadSize =
            USER_AREA_MAX_READ_BLOCKS * BLOCK_SIZE - BLOCK_SIZE

        val result = ByteArray(size)

        var currentAddress = startAddress
        var remaining = size
        var outputOffset = 0

        while (remaining > 0) {

            val chunkSize =
                minOf(
                    remaining,
                    maxReadSize
                )

            val chunk =
                readUserDataChunk(
                    address = currentAddress,
                    length = chunkSize
                )

            chunk.copyInto(
                destination = result,
                destinationOffset = outputOffset
            )

            currentAddress += chunk.size
            outputOffset += chunk.size
            remaining -= chunk.size
        }

        return result
    }

    private fun readUserDataChunk(
        address: Int,
        length: Int
    ): ByteArray {
        require(address in 0..0xFFFF) { "Address must be between 0 and 65535." }
        require(length in 1..176) { "AIOI user-area read chunk must be between 1 and 176 bytes." }

        val params = byteArrayOf(
            (address shr 8).toByte(),
            address.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
            0, 0, 0, 0
        )

        val command = SmartTagCommandBuilder.buildCommand(
            functionNo = SmartTagCommandBuilder.COMMAND_DATA_READ,
            paramData = params
        ).first()

        sendCommandFrame(command)

        val blocks = (length + BLOCK_SIZE - 1) / BLOCK_SIZE
        val data = readNormalBlocks(0, blocks + 1)

        require(data.size >= BLOCK_SIZE + length) {
            "Smart Tag returned insufficient user-area data."
        }

        return data.copyOfRange(BLOCK_SIZE, BLOCK_SIZE + length)
    }

    /**
     * Reads blocks from the AIOI Card Area.
     */
    fun readCardArea(
        startBlock: Int,
        blockCount: Int
    ): ByteArray {

        require(startBlock >= 0) {
            "Start block cannot be negative."
        }

        require(blockCount > 0) {
            "Block count must be greater than zero."
        }

        val blockList =
            NfcFPacketBuilder.createCardAreaBlockList(
                startBlock = startBlock,
                blocks = blockCount
            )

        val packet =
            NfcFPacketBuilder.buildCardAreaReadPacket(
                idm = getIdm(),
                serviceCode = CARD_AREA_SERVICE_CODE,
                blockList = blockList
            )

        val response =
            transport.transceive(packet)

        return NfcFPacketBuilder.extractBlockData(
            response
        )
    }

    /**
     * Writes blocks to the AIOI Card Area.
     */
    fun writeCardArea(
        startBlock: Int,
        data: ByteArray
    ) {

        require(startBlock >= 0) {
            "Start block cannot be negative."
        }

        require(data.isNotEmpty()) {
            "Card Area data cannot be empty."
        }

        require(data.size % BLOCK_SIZE == 0) {
            "Card Area data must contain complete 16-byte blocks."
        }

        val blockCount =
            data.size / BLOCK_SIZE

        val blockList =
            NfcFPacketBuilder.createCardAreaBlockList(
                startBlock = startBlock,
                blocks = blockCount
            )

        val packet =
            NfcFPacketBuilder.buildCardAreaWritePacket(
                idm = getIdm(),
                serviceCode = CARD_AREA_SERVICE_CODE,
                blockList = blockList,
                data = data
            )

        transport.transceive(packet)

        waitForIdle()
    }

    private fun executeCommand(
        functionNo: Byte,
        paramData: ByteArray,
        functionData: ByteArray = ByteArray(0)
    ) {
        val commands = SmartTagCommandBuilder.buildCommand(
            functionNo = functionNo,
            paramData = paramData,
            functionData = functionData
        )

        require(commands.isNotEmpty()) { "Failed to build Smart Tag command." }

        commands.forEach(::sendCommandFrame)
    }

    private fun sendCommandFrame(command: ByteArray) {
        require(command.isNotEmpty() && command.size % BLOCK_SIZE == 0) {
            "Invalid Smart Tag command frame."
        }

        val blocks = command.size / BLOCK_SIZE
        val blockList = NfcFPacketBuilder.createNormalBlockList(0, blocks)
        transport.transceive(
            NfcFPacketBuilder.createWritePacket(
                idm = getIdm(),
                blockData = command,
                blockList = blockList
            )
        )
    }

    private fun readNormalBlocks(startBlock: Int, blocks: Int): ByteArray {
        val blockList = NfcFPacketBuilder.createNormalBlockList(startBlock, blocks)
        val response = transport.transceive(
            NfcFPacketBuilder.createReadPacket(
                idm = getIdm(),
                blocks = blocks,
                blockList = blockList
            )
        )
        return NfcFPacketBuilder.extractBlockData(response)
    }

    private fun getIdm(): ByteArray {
        return transport.getIdm()
            ?: throw SmartTagException(
                "Smart Tag IDm is not available."
            )
    }

    /**
     * Converts two 12-bit coordinates into AIOI's 3-byte format.
     */
    private fun convertTo3Bytes(
        first: Int,
        second: Int
    ): ByteArray {

        require(first in 0..0xFFF) {
            "Coordinate value must fit in 12 bits."
        }

        require(second in 0..0xFFF) {
            "Coordinate value must fit in 12 bits."
        }

        return byteArrayOf(
            ((first shr 4) and 0xFF).toByte(),
            (
                    ((first and 0x0F) shl 4) or
                            ((second shr 8) and 0x0F)
                    ).toByte(),
            (second and 0xFF).toByte()
        )
    }
}