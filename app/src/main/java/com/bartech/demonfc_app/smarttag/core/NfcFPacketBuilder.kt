package com.bartech.demonfc_app.smarttag.core

object NfcFPacketBuilder {

    private const val COMMAND_READ = 0x06
    private const val COMMAND_WRITE = 0x08

    private const val SERVICE_CODE = 0x0009

    const val BLOCK_SIZE = 16

    private const val NORMAL_BLOCK_LIST_SIZE = 3
    private const val CARD_AREA_BLOCK_LIST_SIZE = 2


    /**
     * Creates an NFC-F Read Without Encryption packet.
     *
     * Normal area block:
     *      00 [block] 04
     *
     * Card area block:
     *      80 [block]
     */
    fun createReadPacket(
        idm: ByteArray,
        blocks: Int,
        blockList: ByteArray
    ): ByteArray {

        validateIdm(idm)

        require(blocks > 0) {
            "Number of blocks must be greater than zero."
        }

        validateBlockList(
            blocks = blocks,
            blockList = blockList
        )

        val packetLength =
            1 +                  // Length
                    1 +                  // Command
                    idm.size +           // IDm
                    1 +                  // Service count
                    2 +                  // Service code
                    1 +                  // Block count
                    blockList.size       // Block list

        require(packetLength <= 255) {
            "NFC-F packet is too large."
        }

        return ByteArray(packetLength).apply {

            var index = 0

            // Packet length
            this[index++] = packetLength.toByte()

            // Read Without Encryption
            this[index++] = COMMAND_READ.toByte()

            // IDm
            System.arraycopy(
                idm,
                0,
                this,
                index,
                idm.size
            )

            index += idm.size

            // Service count
            this[index++] = 0x01

            // Service code 0x0009, little endian
            this[index++] =
                (SERVICE_CODE and 0xFF).toByte()

            this[index++] =
                ((SERVICE_CODE shr 8) and 0xFF).toByte()

            // Block count
            this[index++] = blocks.toByte()

            // Block list
            System.arraycopy(
                blockList,
                0,
                this,
                index,
                blockList.size
            )
        }
    }


    /**
     * Creates an NFC-F Write Without Encryption packet.
     *
     * Block data must contain complete 16-byte blocks.
     */
    fun createWritePacket(
        idm: ByteArray,
        blockData: ByteArray,
        blockList: ByteArray
    ): ByteArray {

        validateIdm(idm)

        require(blockData.isNotEmpty()) {
            "Block data cannot be empty."
        }

        require(blockData.size % BLOCK_SIZE == 0) {
            "Block data must be a multiple of $BLOCK_SIZE bytes."
        }

        val blocks =
            blockData.size / BLOCK_SIZE

        require(blocks > 0) {
            "Number of blocks must be greater than zero."
        }

        validateBlockList(
            blocks = blocks,
            blockList = blockList
        )

        val packetLength =
            1 +                  // Length
                    1 +                  // Command
                    idm.size +           // IDm
                    1 +                  // Service count
                    2 +                  // Service code
                    1 +                  // Block count
                    blockList.size +     // Block list
                    blockData.size       // Block data

        require(packetLength <= 255) {
            "NFC-F packet is too large."
        }

        return ByteArray(packetLength).apply {

            var index = 0

            // Packet length
            this[index++] = packetLength.toByte()

            // Write Without Encryption
            this[index++] = COMMAND_WRITE.toByte()

            // IDm
            System.arraycopy(
                idm,
                0,
                this,
                index,
                idm.size
            )

            index += idm.size

            // Service count
            this[index++] = 0x01

            // Service code 0x0009, little endian
            this[index++] =
                (SERVICE_CODE and 0xFF).toByte()

            this[index++] =
                ((SERVICE_CODE shr 8) and 0xFF).toByte()

            // Block count
            this[index++] = blocks.toByte()

            // Block list
            System.arraycopy(
                blockList,
                0,
                this,
                index,
                blockList.size
            )

            index += blockList.size

            // Block data
            System.arraycopy(
                blockData,
                0,
                this,
                index,
                blockData.size
            )
        }
    }


    /**
     * Creates a normal Smart Tag block list.
     *
     * AIOI normal-area format:
     *
     *      00 [block number] 04
     */
    fun createNormalBlockList(
        startBlock: Int,
        blocks: Int
    ): ByteArray {

        validateBlockRange(
            startBlock = startBlock,
            blocks = blocks
        )

        return ByteArray(
            blocks * NORMAL_BLOCK_LIST_SIZE
        ).apply {

            for (i in 0 until blocks) {

                val index =
                    i * NORMAL_BLOCK_LIST_SIZE

                this[index] =
                    0x00.toByte()

                this[index + 1] =
                    (startBlock + i).toByte()

                this[index + 2] =
                    0x04.toByte()
            }
        }
    }


    /**
     * Creates a Card Area block list.
     *
     * AIOI Card Area format:
     *
     *      80 [block number]
     */
    fun createCardAreaBlockList(
        startBlock: Int,
        blocks: Int
    ): ByteArray {

        validateBlockRange(
            startBlock = startBlock,
            blocks = blocks
        )

        return ByteArray(
            blocks * CARD_AREA_BLOCK_LIST_SIZE
        ).apply {

            for (i in 0 until blocks) {

                val index =
                    i * CARD_AREA_BLOCK_LIST_SIZE

                this[index] =
                    0x80.toByte()

                this[index + 1] =
                    (startBlock + i).toByte()
            }
        }
    }


    /**
     * Creates a Card Area read packet.
     *
     * This is a convenience method so higher layers do not need
     * to know how NFC-F packets are constructed.
     */
    fun buildCardAreaReadPacket(
        idm: ByteArray,
        serviceCode: Int = SERVICE_CODE,
        blockList: ByteArray
    ): ByteArray {

        require(serviceCode == SERVICE_CODE) {
            "Unsupported Smart Tag service code."
        }

        require(blockList.isNotEmpty()) {
            "Card Area block list cannot be empty."
        }

        require(blockList.size % CARD_AREA_BLOCK_LIST_SIZE == 0) {
            "Invalid Card Area block list."
        }

        val blocks =
            blockList.size / CARD_AREA_BLOCK_LIST_SIZE

        return createReadPacket(
            idm = idm,
            blocks = blocks,
            blockList = blockList
        )
    }


    /**
     * Creates a Card Area write packet.
     */
    fun buildCardAreaWritePacket(
        idm: ByteArray,
        serviceCode: Int = SERVICE_CODE,
        blockList: ByteArray,
        data: ByteArray
    ): ByteArray {

        require(serviceCode == SERVICE_CODE) {
            "Unsupported Smart Tag service code."
        }

        require(blockList.isNotEmpty()) {
            "Card Area block list cannot be empty."
        }

        require(blockList.size % CARD_AREA_BLOCK_LIST_SIZE == 0) {
            "Invalid Card Area block list."
        }

        val blocks =
            blockList.size / CARD_AREA_BLOCK_LIST_SIZE

        require(data.size == blocks * BLOCK_SIZE) {
            "Card Area data size does not match the block list."
        }

        return createWritePacket(
            idm = idm,
            blockData = data,
            blockList = blockList
        )
    }


    /**
     * Extracts block data from an NFC-F response.
     *
     * AIOI response structure:
     *
     * byte 12     = block count
     * byte 13...  = block data
     */
    fun extractBlockData(
        response: ByteArray
    ): ByteArray {

        require(response.size >= 13) {
            "Invalid NFC-F response."
        }

        val blockCount =
            response[12].toInt() and 0xFF

        val dataSize =
            blockCount * BLOCK_SIZE

        require(
            response.size >= 13 + dataSize
        ) {
            "NFC-F response does not contain " +
                    "the expected block data."
        }

        return response.copyOfRange(
            13,
            13 + dataSize
        )
    }


    private fun validateBlockList(
        blocks: Int,
        blockList: ByteArray
    ) {

        require(
            blockList.size == blocks * NORMAL_BLOCK_LIST_SIZE ||
                    blockList.size == blocks * CARD_AREA_BLOCK_LIST_SIZE
        ) {
            "Invalid block list size. " +
                    "Expected ${blocks * NORMAL_BLOCK_LIST_SIZE} " +
                    "or ${blocks * CARD_AREA_BLOCK_LIST_SIZE}, " +
                    "got ${blockList.size}."
        }
    }


    private fun validateBlockRange(
        startBlock: Int,
        blocks: Int
    ) {

        require(startBlock >= 0) {
            "Start block cannot be negative."
        }

        require(blocks > 0) {
            "Number of blocks must be greater than zero."
        }

        require(startBlock <= 255) {
            "Start block must fit into one byte."
        }

        require(
            startBlock + blocks - 1 <= 255
        ) {
            "Block number must fit into one byte."
        }
    }


    private fun validateIdm(
        idm: ByteArray
    ) {

        require(idm.size == 8) {
            "NFC-F IDm must contain exactly 8 bytes."
        }
    }
}