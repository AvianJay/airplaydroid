package tw.avianjay.airplaydroid.protocol.fairplay

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the stream AES key into the 72-byte `FPLY` record that a legacy receiver
 * expects as `param1` (mirroring) or `a=fpaeskey` (RAOP audio).
 *
 * ### The record
 *
 * ```
 * [0:16]  FPLY header: 46 50 4c 59 01 02 01 00 00 00 00 3c 00 00 00 00
 * [16:32] per-key random mask
 * [32:36] big-endian raw-key length (16)
 * [36:56] HMAC-SHA1(session MAC key, record[0:36] || rawKey)    20 bytes
 * [56:72] AES-128-ECB(rawKey XOR mask)                          16 bytes
 * ```
 *
 * Every field was checked against two real captures decoded from the unofficial
 * spec: a RAOP `a=fpaeskey` and a mirroring `POST /stream` `param1`. Both are 72
 * bytes with `FPLY`, `0x3c` and a big-endian 16 at offset 32 -- so the two paths
 * share this format. See `docs/fairplay-research.md` §5.4.
 *
 * ### What is NOT verified
 *
 * There is no golden vector for this record, and no receiver has accepted one
 * produced here. The *layout* is verified against captures; the *values* are not.
 * Treat it as the best available construction, not as a confirmed one. The
 * reference implementation is `doubletake`'s `wrapFairPlayKey`.
 */
object FairPlayKeyWrap {

    const val RECORD_BYTES = 72
    const val RAW_KEY_BYTES = 16

    /** The fixed 16-byte header every observed record starts with. */
    private val HEADER = byteArrayOf(
        0x46, 0x50, 0x4c, 0x59, 0x01, 0x02, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x3c, 0x00, 0x00, 0x00, 0x00,
    )

    private val INITIAL_SESSION_KEY = hex("dcdcf3b90b74dcfb867ff76016729051")

    private val KDF_PREFIX = hex("fa9cad4d4b68268c7ff38899de922e951e")

    private val KDF_SUFFIX = hex("ec4e275efdf2e83097ae70fbe0003f1c39")

    /**
     * Derives the session MAC key from the receiver's m2 SAP and our own local SAP.
     *
     * The KDF input is a 290-byte record plus ordinary MD5 padding, compressed
     * with FairPlay's modified MD5/SAP-hash combination rather than standard MD5.
     */
    fun deriveWrappingKey(receiverSap: ByteArray, localSap: ByteArray): ByteArray {
        require(receiverSap.size == 128) { "receiver SAP must be 128 bytes" }
        require(localSap.size == 128) { "local SAP must be 128 bytes" }

        val material = IntArray(320)
        var off = 0
        for (v in KDF_PREFIX) material[off++] = v.toInt() and 0xFF
        for (v in localSap) material[off++] = v.toInt() and 0xFF
        for (v in receiverSap) material[off++] = v.toInt() and 0xFF
        for (v in KDF_SUFFIX) material[off++] = v.toInt() and 0xFF
        material[off] = 0x80
        val bits = off.toLong() * 8L
        for (i in 0 until 8) material[312 + i] = ((bits ushr (8 * i)) and 0xFF).toInt()

        val state = IntArray(4) {
            (INITIAL_SESSION_KEY[it * 4].toInt() and 0xFF) or
                ((INITIAL_SESSION_KEY[it * 4 + 1].toInt() and 0xFF) shl 8) or
                ((INITIAL_SESSION_KEY[it * 4 + 2].toInt() and 0xFF) shl 16) or
                ((INITIAL_SESSION_KEY[it * 4 + 3].toInt() and 0xFF) shl 24)
        }

        var blockOff = 0
        while (blockOff < 320) {
            val block = ByteArray(64) { material[blockOff + it].toByte() }

            // The KDF variant of the round-31 message permutation.
            val message = IntArray(16) {
                ((block[it * 4].toInt() and 0xFF) shl 24) or
                    ((block[it * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((block[it * 4 + 2].toInt() and 0xFF) shl 8) or
                    (block[it * 4 + 3].toInt() and 0xFF)
            }
            val modified = state.copyOf()
            FairPlayBridge.compress(modified, message, 0, FairPlayBridge.BridgeMutation.KDF)

            val hashed = FairPlaySapCore.sapHash(block)
            for (word in 0 until 4) {
                val add = (hashed[word * 4].toInt() and 0xFF) or
                    ((hashed[word * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((hashed[word * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((hashed[word * 4 + 3].toInt() and 0xFF) shl 24)
                state[word] = modified[word] + add
            }
            blockOff += 64
        }

        val out = ByteArray(16)
        for (i in 0 until 4) {
            out[i * 4] = (state[i] ushr 24).toByte()
            out[i * 4 + 1] = (state[i] ushr 16).toByte()
            out[i * 4 + 2] = (state[i] ushr 8).toByte()
            out[i * 4 + 3] = state[i].toByte()
        }
        return out
    }

    /**
     * Builds the 72-byte `FPLY` record wrapping [rawKey].
     *
     * [receiverSap] is the receiver's m2 SAP; [localSap] is this session's own,
     * the same one the m3 carries. Both feed the MAC key.
     */
    fun wrap(
        receiverSap: ByteArray,
        localSap: ByteArray,
        rawKey: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(rawKey.size == RAW_KEY_BYTES) { "the stream key is $RAW_KEY_BYTES bytes" }

        val record = ByteArray(RECORD_BYTES)
        HEADER.copyInto(record, 0)
        val mask = ByteArray(RAW_KEY_BYTES).also { random.nextBytes(it) }
        mask.copyInto(record, 16)

        record[32] = (rawKey.size ushr 24).toByte()
        record[33] = (rawKey.size ushr 16).toByte()
        record[34] = (rawKey.size ushr 8).toByte()
        record[35] = rawKey.size.toByte()

        val wrappingKey = deriveWrappingKey(receiverSap, localSap)
        val masked = ByteArray(RAW_KEY_BYTES) { (rawKey[it].toInt() xor mask[it].toInt()).toByte() }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"))
        cipher.doFinal(masked).copyInto(record, 56)

        val macKey = FairPlaySapCore.descriptorForSap(localSap, receiverSap)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(macKey, "HmacSHA1"))
        mac.update(record, 0, 36)
        mac.update(rawKey)
        mac.doFinal().copyInto(record, 36)

        return record
    }

    /** Kotlin's Byte is signed, so a literal like 0xfa needs an explicit conversion. */
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
