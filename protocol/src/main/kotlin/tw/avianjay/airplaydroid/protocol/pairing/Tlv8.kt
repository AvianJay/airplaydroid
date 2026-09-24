package tw.avianjay.airplaydroid.protocol.pairing

import java.io.ByteArrayOutputStream

/**
 * TLV8, the encoding HomeKit pairing uses.
 *
 * Two things trip people up, both confirmed against a real Apple TV capture:
 *
 *  - Values longer than 255 bytes are split into consecutive entries of the
 *    SAME type, which the reader must concatenate. The 384-byte SRP public key
 *    always arrives as 255 + 129.
 *  - The HTTP response claims `Content-Type: application/x-apple-binary-plist`
 *    but the body is TLV8. Trusting that header gets you a parse failure.
 */
object Tlv8 {

    // Types used by pair-setup / pair-verify.
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FLAGS = 0x13

    /** `Flags` bit 4: transient pairing, i.e. no long-term keys are stored. */
    const val FLAG_TRANSIENT = 0x10

    enum class PairError(val code: Int) {
        UNKNOWN(1), AUTHENTICATION(2), BACKOFF(3),
        MAX_PEERS(4), MAX_TRIES(5), UNAVAILABLE(6), BUSY(7);

        companion object {
            fun of(code: Int): PairError? = entries.firstOrNull { it.code == code }
        }
    }

    fun encode(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { (type, value) ->
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                return@forEach
            }
            var offset = 0
            while (offset < value.size) {
                val chunk = minOf(255, value.size - offset)
                out.write(type)
                out.write(chunk)
                out.write(value, offset, chunk)
                offset += chunk
            }
        }
        return out.toByteArray()
    }

    fun encode(vararg entries: Pair<Int, ByteArray>): ByteArray = encode(entries.toList())

    /** Decodes, concatenating the fragments of any repeated type. */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val parts = LinkedHashMap<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            val end = minOf(i + 2 + length, data.size)
            parts.getOrPut(type) { ByteArrayOutputStream() }.write(data, i + 2, end - (i + 2))
            i = i + 2 + length
        }
        return parts.mapValues { it.value.toByteArray() }
    }

    fun byte(value: Int): ByteArray = byteArrayOf(value.toByte())

    fun stateOf(tlv: Map<Int, ByteArray>): Int? = tlv[STATE]?.firstOrNull()?.toInt()

    fun errorOf(tlv: Map<Int, ByteArray>): PairError? =
        tlv[ERROR]?.firstOrNull()?.toInt()?.let { PairError.of(it) }
}
