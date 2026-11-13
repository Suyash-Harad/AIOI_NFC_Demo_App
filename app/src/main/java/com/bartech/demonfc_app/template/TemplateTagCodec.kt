package com.bartech.demonfc_app.template

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

class TemplateTagException(message: String) : Exception(message)

/**
 * The record kept in the tag's User Area: which template is on the screen,
 * the template itself (when it fits) and the values the user entered.
 *
 *  off  size  content
 *    0    4   magic "BTPL"
 *    4    1   format version (1)
 *    5    1   flags: bit0 = body stored, bit1 = body deflated
 *    6    2   total record length
 *    8    4   CRC32 of bytes 12 .. total
 *   12   16   template id (UUID)
 *   28    4   template hash (CRC32 of the uncompressed body)
 *   32    2   stored body length
 *   34    n   body (compact binary template, deflated when that is smaller)
 *    .    1   number of values, then per value: 1 length byte + UTF-8 bytes
 *
 * Values are listed in the order of Template.fieldNames().
 * If the template body does not fit, only the id and the values are stored
 * and the template must exist on the phone.
 */
object TemplateTagCodec {

    /**
     * Where the record lives in the User Area.
     *
     * NOTE: the "Tag Data Read/Write" screen also uses the User Area from
     * address 0 (512 bytes). Both features cannot use the same tag at the
     * same time. If your tag's User Area is larger than 512 bytes, move this
     * to 512 (and keep RECORD_CAPACITY within the real size).
     */
    const val RECORD_ADDRESS = 0
    const val RECORD_CAPACITY = 512

    const val HEADER_SIZE = 34

    private val MAGIC = byteArrayOf(0x42, 0x54, 0x50, 0x4C)   // "BTPL"
    private const val VERSION = 1
    private const val FLAG_BODY = 1
    private const val FLAG_DEFLATED = 2

    class Record(
        val templateId: String,
        val templateHash: Int,
        /** Null when the template was too big to be stored on the tag. */
        val body: Template?,
        val values: List<String>
    )

    class Encoded(val bytes: ByteArray, val bodyIncluded: Boolean)

    // ============================================================
    // Encode
    // ============================================================

    fun encode(
        template: Template,
        values: Map<String, String>,
        capacity: Int = RECORD_CAPACITY
    ): Encoded {
        val fields = template.fieldNames()
        val valueBytes = encodeValues(fields.map { values[it].orEmpty() }, fields)

        val rawBody = encodeBody(template)
        val hash = crc32(rawBody)
        val deflated = deflate(rawBody)
        val deflatedSmaller = deflated.size < rawBody.size
        val stored = if (deflatedSmaller) deflated else rawBody

        val withBody = HEADER_SIZE + stored.size + valueBytes.size
        val includeBody = withBody <= capacity
        val total = if (includeBody) withBody else HEADER_SIZE + valueBytes.size

        if (total > capacity) {
            throw TemplateTagException(
                "The entered data needs ${valueBytes.size} bytes but the tag memory " +
                        "only has ${capacity - HEADER_SIZE} bytes free. Shorten the values."
            )
        }

        var flags = 0
        if (includeBody) {
            flags = flags or FLAG_BODY
            if (deflatedSmaller) flags = flags or FLAG_DEFLATED
        }
        val bodyOut = if (includeBody) stored else ByteArray(0)

        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.put(flags.toByte())
        buf.putShort(total.toShort())
        buf.putInt(0)                                   // CRC placeholder
        val uuid = try {
            UUID.fromString(template.id)
        } catch (_: Exception) {
            throw TemplateTagException("Template id is invalid.")
        }
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.putInt(hash)
        buf.putShort(bodyOut.size.toShort())
        buf.put(bodyOut)
        buf.put(valueBytes)

        val bytes = buf.array()
        val crc = CRC32().apply { update(bytes, 12, total - 12) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(8, crc)

        return Encoded(bytes, includeBody)
    }

    /** Size of the (compressed) template body, for the designer's capacity hint. */
    fun storedBodySize(template: Template): Int {
        val raw = encodeBody(template)
        return minOf(raw.size, deflate(raw).size)
    }

    /** Fingerprint of a template's design. */
    fun hashOf(template: Template): Int = crc32(encodeBody(template))

    // ============================================================
    // Decode
    // ============================================================

    /** Total record length from the first bytes, or -1 if this is not a record. */
    fun peekLength(header: ByteArray): Int {
        if (header.size < 8) return -1
        for (i in 0 until 4) if (header[i] != MAGIC[i]) return -1
        if ((header[4].toInt() and 0xFF) !in 1..VERSION) return -1
        val total = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        return if (total in HEADER_SIZE..RECORD_CAPACITY) total else -1
    }

    /** Returns null for blank memory, foreign data or a damaged record. */
    fun decode(bytes: ByteArray): Record? {
        return try {
            val total = peekLength(bytes)
            if (total < 0 || total > bytes.size) return null

            val crcCalc = CRC32().apply { update(bytes, 12, total - 12) }.value.toInt()
            val buf = ByteBuffer.wrap(bytes, 0, total).order(ByteOrder.BIG_ENDIAN)

            buf.position(5)
            val flags = buf.get().toInt() and 0xFF
            buf.position(8)
            val crcStored = buf.int
            if (crcStored != crcCalc) return null

            val msb = buf.long
            val lsb = buf.long
            val hash = buf.int
            val bodyLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < bodyLen + 1) return null

            val stored = ByteArray(bodyLen)
            buf.get(stored)

            val count = buf.get().toInt() and 0xFF
            val values = ArrayList<String>(count)
            repeat(count) {
                val len = buf.get().toInt() and 0xFF
                if (buf.remaining() < len) return null
                val b = ByteArray(len)
                buf.get(b)
                values += String(b, Charsets.UTF_8)
            }

            val id = UUID(msb, lsb).toString()

            var body: Template? = null
            if ((flags and FLAG_BODY) != 0 && bodyLen > 0) {
                body = try {
                    val raw = if ((flags and FLAG_DEFLATED) != 0) inflate(stored) else stored
                    if (crc32(raw) == hash) decodeBody(raw, id) else null
                } catch (_: Exception) {
                    null
                }
            }

            Record(id, hash, body, values)
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // Body (compact binary template)
    // ============================================================

    private fun encodeBody(t: Template): ByteArray {
        if (t.components.size > TemplateConstants.MAX_COMPONENTS) {
            throw TemplateTagException("A template can have at most ${TemplateConstants.MAX_COMPONENTS} components.")
        }

        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)

        out.writeShort(t.designWidth)
        out.writeShort(t.designHeight)
        writeString(out, t.name, 24)
        out.writeByte(t.components.size)

        for (c in t.components) {
            out.writeByte(c.type.code)

            var flags = 0
            if (c.filled) flags = flags or 0x01
            if (c.inverted) flags = flags or 0x02
            if (c.bold) flags = flags or 0x04
            flags = flags or ((c.align and 0x03) shl 3)
            out.writeByte(flags)

            if (c.type == ComponentType.BARCODE) out.writeByte(c.barcode.code)

            writeFraction(out, c.cx)
            writeFraction(out, c.cy)
            writeFraction(out, c.w)
            writeFraction(out, c.h)

            val rot = (((c.rotation % 360f) + 360f) % 360f * 10f).roundToInt().coerceIn(0, 3599)
            out.writeShort(rot)

            when (c.type) {
                ComponentType.TEXT -> {
                    out.writeByte((c.textSize * 255f).roundToInt().coerceIn(1, 255))
                    writeString(out, c.text, 255)
                }

                ComponentType.RECTANGLE,
                ComponentType.ELLIPSE,
                ComponentType.LINE ->
                    out.writeByte((c.strokeWidth * 255f).roundToInt().coerceIn(1, 255))

                ComponentType.BARCODE -> writeString(out, c.text, 255)

                ComponentType.IMAGE -> out.write(imageIdToBytes(c.imageId))
            }
        }

        out.flush()
        return bos.toByteArray()
    }

    private fun decodeBody(raw: ByteArray, id: String): Template {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)

        val w = buf.short.toInt() and 0xFFFF
        val h = buf.short.toInt() and 0xFFFF
        val name = readString(buf)
        val count = buf.get().toInt() and 0xFF

        val components = ArrayList<TemplateComponent>(count)
        repeat(count) {
            val type = ComponentType.fromCode(buf.get().toInt() and 0xFF)
                ?: throw TemplateTagException("Unknown component type.")
            val flags = buf.get().toInt() and 0xFF

            val c = TemplateComponent(type)
            c.filled = (flags and 0x01) != 0
            c.inverted = (flags and 0x02) != 0
            c.bold = (flags and 0x04) != 0
            c.align = (flags shr 3) and 0x03

            if (type == ComponentType.BARCODE) {
                c.barcode = BarcodeKind.fromCode(buf.get().toInt() and 0xFF)
            }

            c.cx = readFraction(buf)
            c.cy = readFraction(buf)
            c.w = readFraction(buf)
            c.h = readFraction(buf)
            c.rotation = (buf.short.toInt() and 0xFFFF) / 10f

            when (type) {
                ComponentType.TEXT -> {
                    c.textSize = (buf.get().toInt() and 0xFF) / 255f
                    c.text = readString(buf)
                }

                ComponentType.RECTANGLE,
                ComponentType.ELLIPSE,
                ComponentType.LINE -> c.strokeWidth = (buf.get().toInt() and 0xFF) / 255f

                ComponentType.BARCODE -> c.text = readString(buf)

                ComponentType.IMAGE -> {
                    val b = ByteArray(8)
                    buf.get(b)
                    c.imageId = bytesToImageId(b)
                }
            }
            components += c
        }

        return Template(
            id = id,
            name = name,
            designWidth = w,
            designHeight = h,
            components = components
        )
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun encodeValues(values: List<String>, fields: List<String>): ByteArray {
        if (values.size > 255) throw TemplateTagException("Too many fields.")
        val bos = ByteArrayOutputStream()
        bos.write(values.size)
        values.forEachIndexed { i, v ->
            val b = v.toByteArray(Charsets.UTF_8)
            if (b.size > 255) {
                throw TemplateTagException("The value for '${fields[i]}' is too long.")
            }
            bos.write(b.size)
            bos.write(b)
        }
        return bos.toByteArray()
    }

    private fun writeFraction(out: DataOutputStream, v: Float) {
        out.writeShort((v.coerceIn(0f, 1f) * 65535f).roundToInt())
    }

    private fun readFraction(buf: ByteBuffer): Float =
        (buf.short.toInt() and 0xFFFF) / 65535f

    private fun writeString(out: DataOutputStream, s: String, maxBytes: Int) {
        var text = s
        var b = text.toByteArray(Charsets.UTF_8)
        while (b.size > maxBytes && text.isNotEmpty()) {
            text = text.dropLast(1)
            b = text.toByteArray(Charsets.UTF_8)
        }
        out.writeByte(b.size)
        out.write(b)
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.get().toInt() and 0xFF
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    private fun imageIdToBytes(id: String): ByteArray {
        val out = ByteArray(8)
        if (id.length == 16) {
            for (i in 0 until 8) {
                out[i] = (id.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0).toByte()
            }
        }
        return out
    }

    private fun bytesToImageId(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun crc32(data: ByteArray): Int =
        CRC32().apply { update(data) }.value.toInt()

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)   // raw deflate, no zlib header
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data + byteArrayOf(0))              // dummy byte needed in raw mode
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 8192) throw TemplateTagException("Template body is too large.")
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }
}
