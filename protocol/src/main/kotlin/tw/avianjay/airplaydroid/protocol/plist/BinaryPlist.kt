package tw.avianjay.airplaydroid.protocol.plist

import java.io.ByteArrayOutputStream

/**
 * The subset of the Apple property-list model that AirPlay actually uses on the
 * wire: dictionaries of strings, numbers, booleans, data and arrays.
 */
sealed interface PlistValue {

    data class PString(val value: String) : PlistValue

    data class PInt(val value: Long) : PlistValue

    data class PReal(val value: Double) : PlistValue

    data class PBool(val value: Boolean) : PlistValue

    class PData(val value: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is PData && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "PData(" + value.size + " bytes)"
    }

    data class PArray(val values: List<PlistValue>) : PlistValue

    data class PDict(val entries: Map<String, PlistValue>) : PlistValue {
        operator fun get(key: String): PlistValue? = entries[key]

        fun string(key: String): String? = (entries[key] as? PString)?.value

        /** Tolerates an integer: receivers write booleans with plist_new_uint. */
        fun bool(key: String): Boolean? = when (val v = entries[key]) {
            is PBool -> v.value
            is PInt -> v.value != 0L
            else -> null
        }

        /** Accepts either a real or an integer, because receivers spell numbers inconsistently. */
        fun number(key: String): Double? = when (val v = entries[key]) {
            is PReal -> v.value
            is PInt -> v.value.toDouble()
            else -> null
        }
    }

    companion object {
        fun dict(vararg pairs: Pair<String, PlistValue>): PDict = PDict(linkedMapOf(*pairs))
    }
}

/**
 * Minimal `bplist00` codec.
 *
 * AirPlay speaks binary plists for `POST /play` bodies and `GET /playback-info`
 * responses. Only what the protocol needs is implemented -- notably there is no
 * UID, date, or set support, and the encoder does not deduplicate equal objects
 * (correct, just slightly larger than Apple's output).
 */
object BinaryPlist {

    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    class FormatException(message: String) : Exception(message)

    // ------------------------------------------------------------- encoding

    fun encode(root: PlistValue): ByteArray {
        val objects = ArrayList<PlistValue>()
        val dictRefs = HashMap<Int, Pair<IntArray, IntArray>>()
        val arrayRefs = HashMap<Int, IntArray>()

        objects.add(root)
        var cursor = 0
        while (cursor < objects.size) {
            val index = cursor
            when (val value = objects[index]) {
                is PlistValue.PDict -> {
                    val keys = IntArray(value.entries.size)
                    val values = IntArray(value.entries.size)
                    // Apple writes all key refs then all value refs, and the
                    // objects themselves follow in the same grouping.
                    value.entries.keys.forEachIndexed { n, key ->
                        keys[n] = objects.size
                        objects.add(PlistValue.PString(key))
                    }
                    value.entries.values.forEachIndexed { n, child ->
                        values[n] = objects.size
                        objects.add(child)
                    }
                    dictRefs[index] = keys to values
                }

                is PlistValue.PArray -> {
                    val refs = IntArray(value.values.size)
                    value.values.forEachIndexed { n, child ->
                        refs[n] = objects.size
                        objects.add(child)
                    }
                    arrayRefs[index] = refs
                }

                else -> Unit
            }
            cursor++
        }

        val refSize = byteWidth(objects.size.toLong())
        val body = ByteArrayOutputStream()
        body.write(MAGIC)

        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, value ->
            offsets[index] = body.size()
            writeObject(body, value, index, refSize, dictRefs, arrayRefs)
        }

        val offsetTableOffset = body.size()
        val offsetSize = byteWidth(offsetTableOffset.toLong())
        offsets.forEach { writeSized(body, it.toLong(), offsetSize) }

        // Trailer: 6 unused, offsetIntSize, objectRefSize, numObjects,
        // topObjectIndex, offsetTableOffset. 32 bytes exactly.
        repeat(6) { body.write(0) }
        body.write(offsetSize)
        body.write(refSize)
        writeSized(body, objects.size.toLong(), 8)
        writeSized(body, 0L, 8)
        writeSized(body, offsetTableOffset.toLong(), 8)

        return body.toByteArray()
    }

    private fun writeObject(
        out: ByteArrayOutputStream,
        value: PlistValue,
        index: Int,
        refSize: Int,
        dictRefs: Map<Int, Pair<IntArray, IntArray>>,
        arrayRefs: Map<Int, IntArray>,
    ) {
        when (value) {
            is PlistValue.PBool -> out.write(if (value.value) 0x09 else 0x08)

            is PlistValue.PInt -> {
                val width = byteWidth(value.value)
                out.write(0x10 or log2(width))
                writeSized(out, value.value, width)
            }

            is PlistValue.PReal -> {
                out.write(0x23)
                writeSized(out, java.lang.Double.doubleToRawLongBits(value.value), 8)
            }

            is PlistValue.PString -> {
                val ascii = value.value.all { it.code in 0..127 }
                if (ascii) {
                    val bytes = value.value.toByteArray(Charsets.US_ASCII)
                    writeMarkerAndLength(out, 0x50, bytes.size)
                    out.write(bytes)
                } else {
                    val bytes = value.value.toByteArray(Charsets.UTF_16BE)
                    // Length is counted in UTF-16 code units, not bytes.
                    writeMarkerAndLength(out, 0x60, bytes.size / 2)
                    out.write(bytes)
                }
            }

            is PlistValue.PData -> {
                writeMarkerAndLength(out, 0x40, value.value.size)
                out.write(value.value)
            }

            is PlistValue.PArray -> {
                val refs = arrayRefs.getValue(index)
                writeMarkerAndLength(out, 0xA0, refs.size)
                refs.forEach { writeSized(out, it.toLong(), refSize) }
            }

            is PlistValue.PDict -> {
                val (keys, values) = dictRefs.getValue(index)
                writeMarkerAndLength(out, 0xD0, keys.size)
                keys.forEach { writeSized(out, it.toLong(), refSize) }
                values.forEach { writeSized(out, it.toLong(), refSize) }
            }
        }
    }

    private fun writeMarkerAndLength(out: ByteArrayOutputStream, marker: Int, length: Int) {
        if (length < 0x0F) {
            out.write(marker or length)
        } else {
            out.write(marker or 0x0F)
            val width = byteWidth(length.toLong())
            out.write(0x10 or log2(width))
            writeSized(out, length.toLong(), width)
        }
    }

    private fun writeSized(out: ByteArrayOutputStream, value: Long, width: Int) {
        for (shift in (width - 1) downTo 0) {
            out.write(((value ushr (shift * 8)) and 0xFF).toInt())
        }
    }

    private fun byteWidth(value: Long): Int = when {
        value < 0 -> 8
        value <= 0xFF -> 1
        value <= 0xFFFF -> 2
        value <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun log2(width: Int): Int = when (width) {
        1 -> 0
        2 -> 1
        4 -> 2
        else -> 3
    }

    // ------------------------------------------------------------- decoding

    fun decode(bytes: ByteArray): PlistValue {
        if (bytes.size < MAGIC.size + 32) throw FormatException("too short to be a binary plist")
        if (!bytes.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw FormatException("missing bplist00 magic")
        }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val count = readSized(bytes, trailer + 8, 8).toInt()
        val top = readSized(bytes, trailer + 16, 8).toInt()
        val offsetTableOffset = readSized(bytes, trailer + 24, 8).toInt()

        if (offsetSize !in 1..8 || refSize !in 1..8) throw FormatException("bad trailer widths")
        if (count < 0 || top !in 0 until count) throw FormatException("bad object count")
        // The offset table must actually fit between its start and the trailer.
        // Without this, a 40-byte input can declare 2^31 objects and the
        // IntArray(count) below allocates gigabytes before any read fails.
        if (offsetTableOffset < MAGIC.size || offsetTableOffset > trailer) {
            throw FormatException("offset table outside the buffer")
        }
        if (count.toLong() * offsetSize.toLong() > (trailer - offsetTableOffset).toLong()) {
            throw FormatException("offset table too small for " + count + " objects")
        }

        val offsets = IntArray(count) { i ->
            readSized(bytes, offsetTableOffset + i * offsetSize, offsetSize).toInt()
        }

        return Decoder(bytes, offsets, refSize).read(top, 0)
    }

    private class Decoder(
        private val bytes: ByteArray,
        private val offsets: IntArray,
        private val refSize: Int,
    ) {
        // The depth guard alone bounds nesting, not total work: objects may be
        // referenced repeatedly, so a small well-formed-looking plist can expand
        // exponentially. A well-formed document visits each object roughly once.
        private var budget = offsets.size.toLong() * 4 + 1024

        fun read(index: Int, depth: Int): PlistValue {
            if (depth > 32) throw FormatException("plist nested too deeply")
            if (--budget < 0) throw FormatException("plist expands to too many objects")
            if (index !in offsets.indices) throw FormatException("object ref out of range")
            var pos = offsets[index]
            if (pos !in bytes.indices) throw FormatException("object offset out of range")

            val marker = bytes[pos].toInt() and 0xFF
            val high = marker and 0xF0
            val low = marker and 0x0F
            pos++

            return when (high) {
                0x00 -> when (low) {
                    0x08 -> PlistValue.PBool(false)
                    0x09 -> PlistValue.PBool(true)
                    else -> throw FormatException("unsupported primitive 0x" + marker.toString(16))
                }

                0x10 -> PlistValue.PInt(readSized(bytes, pos, 1 shl low))

                0x20 -> when (low) {
                    2 -> PlistValue.PReal(
                        java.lang.Float.intBitsToFloat(readSized(bytes, pos, 4).toInt()).toDouble()
                    )
                    3 -> PlistValue.PReal(
                        java.lang.Double.longBitsToDouble(readSized(bytes, pos, 8))
                    )
                    else -> throw FormatException("unsupported real width")
                }

                0x40 -> {
                    val (length, next) = readLength(low, pos)
                    requireSlice(next, length.toLong())
                    PlistValue.PData(bytes.copyOfRange(next, next + length))
                }

                0x50 -> {
                    val (length, next) = readLength(low, pos)
                    requireSlice(next, length.toLong())
                    PlistValue.PString(String(bytes, next, length, Charsets.US_ASCII))
                }

                0x60 -> {
                    val (length, next) = readLength(low, pos)
                    // length is in UTF-16 code units; length * 2 would overflow Int.
                    requireSlice(next, length.toLong() * 2L)
                    PlistValue.PString(String(bytes, next, length * 2, Charsets.UTF_16BE))
                }

                0xA0 -> {
                    val (length, next) = readLength(low, pos)
                    PlistValue.PArray(
                        (0 until length).map { i ->
                            read(readSized(bytes, next + i * refSize, refSize).toInt(), depth + 1)
                        }
                    )
                }

                0xD0 -> {
                    val (length, next) = readLength(low, pos)
                    val entries = LinkedHashMap<String, PlistValue>(length)
                    for (i in 0 until length) {
                        val keyRef = readSized(bytes, next + i * refSize, refSize).toInt()
                        val valRef = readSized(
                            bytes,
                            next + (length + i) * refSize,
                            refSize,
                        ).toInt()
                        val key = read(keyRef, depth + 1)
                        if (key !is PlistValue.PString) throw FormatException("non-string dict key")
                        entries[key.value] = read(valRef, depth + 1)
                    }
                    PlistValue.PDict(entries)
                }

                else -> throw FormatException("unsupported marker 0x" + marker.toString(16))
            }
        }

        /** Long-width bounds check so the arithmetic cannot overflow before the test. */
        private fun requireSlice(start: Int, length: Long) {
            if (start < 0 || length < 0 || start.toLong() + length > bytes.size.toLong()) {
                throw FormatException("slice past end of plist")
            }
        }

        /** Returns the element count and the position just past the length field. */
        private fun readLength(low: Int, pos: Int): Pair<Int, Int> {
            if (low != 0x0F) return low to pos
            if (pos !in bytes.indices) throw FormatException("truncated extended length")
            val intMarker = bytes[pos].toInt() and 0xFF
            if (intMarker and 0xF0 != 0x10) throw FormatException("bad extended length marker")
            val width = 1 shl (intMarker and 0x0F)
            val length = readSized(bytes, pos + 1, width).toInt()
            if (length < 0) throw FormatException("negative length")
            return length to (pos + 1 + width)
        }
    }

    private fun readSized(bytes: ByteArray, offset: Int, width: Int): Long {
        if (offset < 0 || offset + width > bytes.size) throw FormatException("read past end of plist")
        var result = 0L
        for (i in 0 until width) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
