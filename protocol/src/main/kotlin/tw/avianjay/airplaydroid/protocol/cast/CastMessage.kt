package tw.avianjay.airplaydroid.protocol.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One Cast v2 channel message: the `CastMessage` protobuf that every Google Cast
 * sender and receiver exchange over TLS, framed by a 4-byte big-endian length.
 *
 * ```
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1;   // CASTV2_1_0 = 0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;           // STRING = 0, BINARY = 1
 *   optional string payload_utf8 = 6;
 *   optional bytes payload_binary = 7;
 * }
 * ```
 *
 * Written by hand rather than generated: it is seven fields, `:protocol` has no
 * third-party runtime dependency, and a protobuf runtime on Android would be
 * several hundred kilobytes for this one message and the four in [DeviceAuth].
 *
 * Exactly one of [payloadUtf8] and [payloadBinary] is set. Every namespace uses
 * the JSON string payload except device authentication, which is binary.
 */
class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
) {
    init {
        require((payloadUtf8 == null) != (payloadBinary == null)) { "exactly one payload must be set" }
    }

    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, PROTOCOL_VERSION_CASTV2_1_0)
        string(2, sourceId)
        string(3, destinationId)
        string(4, namespace)
        if (payloadBinary != null) {
            varint(5, PAYLOAD_BINARY)
            bytes(7, payloadBinary)
        } else {
            varint(5, PAYLOAD_STRING)
            string(6, payloadUtf8!!)
        }
    }.toByteArray()

    /** For logs: the binary auth payload is summarised, never dumped. */
    override fun toString(): String =
        "$sourceId -> $destinationId [$namespace] " +
            (payloadUtf8 ?: "<${payloadBinary!!.size} bytes>")

    companion object {
        private const val PROTOCOL_VERSION_CASTV2_1_0 = 0L
        private const val PAYLOAD_STRING = 0L
        private const val PAYLOAD_BINARY = 1L

        /**
         * The largest frame a Cast device accepts. Senders never send more, so a
         * bigger length prefix is a corrupt or hostile stream, not a big message.
         */
        const val MAX_FRAME_BYTES = 64 * 1024

        fun decode(bytes: ByteArray): CastMessage {
            var source: String? = null
            var destination: String? = null
            var namespace: String? = null
            var payloadType = PAYLOAD_STRING
            var utf8: String? = null
            var binary: ByteArray? = null
            ProtoReader(bytes).forEachField { field, value ->
                when (field) {
                    2 -> source = value.string()
                    3 -> destination = value.string()
                    4 -> namespace = value.string()
                    5 -> payloadType = value.varint()
                    6 -> utf8 = value.string()
                    7 -> binary = value.bytes()
                }
            }
            if (source == null || destination == null || namespace == null) {
                throw ProtoException("CastMessage without source, destination or namespace")
            }
            return if (payloadType == PAYLOAD_BINARY) {
                CastMessage(source!!, destination!!, namespace!!, payloadBinary = binary ?: ByteArray(0))
            } else {
                CastMessage(source!!, destination!!, namespace!!, payloadUtf8 = utf8 ?: "")
            }
        }

        /** Reads one length-prefixed frame. Throws [EOFException] at a clean end of stream. */
        fun read(input: InputStream): CastMessage {
            val data = DataInputStream(input)
            val length = data.readInt()
            if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("bad Cast frame length $length")
            val body = ByteArray(length)
            data.readFully(body)
            return decode(body)
        }

        /** Writes one length-prefixed frame. The caller serialises writers. */
        fun write(output: OutputStream, message: CastMessage) {
            val body = message.encode()
            val frame = ByteArray(4 + body.size)
            frame[0] = (body.size ushr 24).toByte()
            frame[1] = (body.size ushr 16).toByte()
            frame[2] = (body.size ushr 8).toByte()
            frame[3] = body.size.toByte()
            System.arraycopy(body, 0, frame, 4, body.size)
            // One write per frame: TLS then seals it as one record instead of two.
            output.write(frame)
            output.flush()
        }
    }
}

class ProtoException(message: String) : IOException(message)

/** The subset of the protobuf wire format these messages use: varints and length-delimited fields. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) {
        tag(field, WIRE_VARINT)
        rawVarint(value)
    }

    fun bytes(field: Int, value: ByteArray) {
        tag(field, WIRE_LENGTH_DELIMITED)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** An embedded message is a length-delimited field holding its encoding. */
    fun message(field: Int, block: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
    }
}

internal class ProtoReader(private val data: ByteArray) {
    private var position = 0

    /** One field's value; which accessor applies depends on the field's declared type. */
    class Value(private val varintValue: Long, private val payload: ByteArray?) {
        fun varint(): Long = varintValue
        fun bytes(): ByteArray = payload ?: throw ProtoException("expected a length-delimited field")
        fun string(): String = String(bytes(), Charsets.UTF_8)
    }

    /**
     * Calls [block] for every field, in wire order. Unknown fields -- the chunking
     * fields 8 and 9, or anything a later protocol version adds -- are skipped
     * rather than rejected, which is what protobuf itself does.
     */
    fun forEachField(block: (field: Int, value: Value) -> Unit) {
        while (position < data.size) {
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            when (val wireType = (tag and 0x7).toInt()) {
                ProtoWriter.WIRE_VARINT -> block(field, Value(readVarint(), null))
                ProtoWriter.WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint()
                    if (length < 0 || length > data.size - position) throw ProtoException("truncated field $field")
                    val bytes = data.copyOfRange(position, position + length.toInt())
                    position += length.toInt()
                    block(field, Value(0, bytes))
                }
                ProtoWriter.WIRE_FIXED64 -> skip(8)
                ProtoWriter.WIRE_FIXED32 -> skip(4)
                else -> throw ProtoException("unsupported wire type $wireType")
            }
        }
    }

    private fun skip(count: Int) {
        if (count > data.size - position) throw ProtoException("truncated fixed-width field")
        position += count
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= data.size) throw ProtoException("truncated varint")
            val b = data[position++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw ProtoException("varint too long")
        }
    }
}
