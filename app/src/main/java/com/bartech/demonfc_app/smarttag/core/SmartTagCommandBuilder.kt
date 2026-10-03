package com.bartech.demonfc_app.smarttag.core

/**
 * Builds AIOI Smart Tag command frames.
 *
 * This implementation mirrors the command framing used by the official
 * AIOI Smart Tag sample project.
 */
object SmartTagCommandBuilder {

    const val COMMAND_DATA_WRITE: Byte = 0xB0.toByte()
    const val COMMAND_CHANGE_SECURITY_CODE: Byte = 0xBD.toByte()
    const val COMMAND_DATA_READ: Byte = 0xC0.toByte()
    const val COMMAND_CHECK_STATUS: Byte = 0xD0.toByte()
    const val COMMAND_CLEAR: Byte = 0xA1.toByte()
    const val COMMAND_SHOW_DISPLAY_OLD: Byte = 0xA0.toByte()
    const val COMMAND_SAVE_LAYOUT: Byte = 0xB2.toByte()
    const val COMMAND_SHOW_DISPLAY: Byte = 0xA2.toByte()
    const val COMMAND_SHOW_DISPLAY3: Byte = 0xA3.toByte()
    const val COMMAND_ADD_FIELD_SHOW_TEXT: Byte = 0xAD.toByte()

    const val SECURITY_CODE_TYPE1: Byte = 1
    const val SECURITY_CODE_TYPE2: Byte = 2
    const val SECURITY_CODE_TYPE3: Byte = 3

    private const val MAX_BLOCKS = 12
    private const val BLOCK_SIZE = 16
    private const val HEADER_SIZE = 16
    private const val MAX_WRITE_SIZE = 512
    private const val MAX_FUNCTION_DATA_PER_FRAME = (MAX_BLOCKS - 1) * BLOCK_SIZE

    private var sequence: Int = 0

    private val securityCode1 = byteArrayOf(0x30, 0x30, 0x30)
    private val securityCode2 = byteArrayOf(0x30, 0x30, 0x30)
    private val securityCode3 = byteArrayOf(0x30, 0x30, 0x30)

    fun resetSequence() {
        sequence = 0
    }

    fun setSequence(value: Int) {
        sequence = value and 0xFF
    }

    fun getSequence(): Int = sequence

    fun buildCommand(
        functionNo: Byte,
        paramData: ByteArray = ByteArray(8),
        functionData: ByteArray? = null
    ): List<ByteArray> {
        require(paramData.size <= 8) { "Parameter data cannot exceed 8 bytes." }
        require(functionData == null || functionData.size <= 0xFFFF) {
            "Function data is too large."
        }

        val params = ByteArray(8)
        paramData.copyInto(params)

        if (functionData == null) {
            return listOf(
                buildFrame(
                    functionNo = functionNo,
                    splitCount = 1,
                    frameNumber = 1,
                    payload = ByteArray(0),
                    paramData = params,
                    sequenceValue = if (functionNo == COMMAND_CHECK_STATUS) 0 else nextSequence()
                )
            )
        }

        val dataBlocks = if (functionData.isEmpty()) 0
        else (functionData.size + BLOCK_SIZE - 1) / BLOCK_SIZE

        val padded = if (dataBlocks == 0) {
            ByteArray(0)
        } else {
            ByteArray(dataBlocks * BLOCK_SIZE).also {
                functionData.copyInto(it)
            }
        }

        val blocksPerFrame = MAX_BLOCKS - 1
        val splitCount = if (dataBlocks == 0) 1
        else (dataBlocks + blocksPerFrame - 1) / blocksPerFrame

        val result = ArrayList<ByteArray>(splitCount)
        var offset = 0

        for (frameIndex in 0 until splitCount) {
            val frameDataLength = if (frameIndex == splitCount - 1) {
                padded.size - offset
            } else {
                blocksPerFrame * BLOCK_SIZE
            }

            val frameBlocks = 1 + ((frameDataLength + BLOCK_SIZE - 1) / BLOCK_SIZE)
            val payload = if (frameDataLength == 0) {
                ByteArray(0)
            } else {
                padded.copyOfRange(offset, offset + frameDataLength)
            }

            result += buildFrame(
                functionNo = functionNo,
                splitCount = splitCount,
                frameNumber = frameIndex + 1,
                payload = payload,
                paramData = params,
                sequenceValue = if (functionNo == COMMAND_CHECK_STATUS) 0 else nextSequence()
            )

            offset += frameDataLength
        }

        return result
    }

    fun buildDataWriteCommand(
        startAddress: Int,
        functionData: ByteArray
    ): List<ByteArray> {
        require(startAddress in 0..0xFFFF) {
            "Address must be between 0 and 65535."
        }
        require(functionData.isNotEmpty()) { "Function data cannot be empty." }
        require(functionData.size <= MAX_WRITE_SIZE) {
            "DATA WRITE cannot exceed $MAX_WRITE_SIZE bytes per call."
        }

        val unit = MAX_FUNCTION_DATA_PER_FRAME
        val splitCount = (functionData.size + unit - 1) / unit
        val result = ArrayList<ByteArray>(splitCount)
        var offset = 0

        for (frameIndex in 0 until splitCount) {
            val dataLen = minOf(unit, functionData.size - offset)
            val frameBlocks = 1 + ((dataLen + BLOCK_SIZE - 1) / BLOCK_SIZE)
            val frame = ByteArray(frameBlocks * BLOCK_SIZE)

            frame[0] = COMMAND_DATA_WRITE
            frame[1] = splitCount.toByte()
            frame[2] = (frameIndex + 1).toByte()
            frame[3] = dataLen.toByte()
            frame[4] = nextSequence().toByte()

            securityCode2.copyInto(frame, 5)

            val address = startAddress + offset
            require(address in 0..0xFFFF) {
                "Address must be between 0 and 65535."
            }

            frame[8] = (address shr 8).toByte()
            frame[9] = address.toByte()
            frame[10] = (dataLen shr 8).toByte()
            frame[11] = dataLen.toByte()

            functionData.copyInto(
                destination = frame,
                destinationOffset = HEADER_SIZE,
                startIndex = offset,
                endIndex = offset + dataLen
            )

            result += frame
            offset += dataLen
        }

        return result
    }

    private fun buildFrame(
        functionNo: Byte,
        splitCount: Int,
        frameNumber: Int,
        payload: ByteArray,
        paramData: ByteArray,
        sequenceValue: Int
    ): ByteArray {
        val dataBlocks = if (payload.isEmpty()) 0
        else (payload.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        val frameBlocks = 1 + dataBlocks

        require(frameBlocks <= MAX_BLOCKS) { "Command exceeds $MAX_BLOCKS blocks." }

        val frame = ByteArray(frameBlocks * BLOCK_SIZE)
        frame[0] = functionNo
        frame[1] = splitCount.toByte()
        frame[2] = frameNumber.toByte()
        frame[3] = (frameBlocks - 1).let { it * BLOCK_SIZE }.toByte()
        frame[4] = sequenceValue.toByte()

        getSecurityCode(functionNo).copyInto(frame, 5)
        paramData.copyInto(frame, 8)
        payload.copyInto(frame, HEADER_SIZE)

        return frame
    }

    private fun getSecurityCode(functionNo: Byte): ByteArray = when (functionNo) {
        COMMAND_SHOW_DISPLAY_OLD,
        COMMAND_SHOW_DISPLAY,
        COMMAND_SHOW_DISPLAY3,
        COMMAND_CLEAR,
        COMMAND_SAVE_LAYOUT -> securityCode1

        COMMAND_DATA_WRITE -> securityCode2
        COMMAND_DATA_READ -> securityCode3
        COMMAND_CHANGE_SECURITY_CODE -> securityCode1
        else -> byteArrayOf(0x30, 0x30, 0x30)
    }

    private fun nextSequence(): Int {
        val result = sequence and 0xFF
        sequence = (sequence + 1) and 0xFF
        return result
    }
}
