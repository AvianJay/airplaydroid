package tw.avianjay.airplaydroid.protocol.fairplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `FPLY` record framing of the FairPlay SAP handshake, as a legacy
 * (AirPlay 1) receiver uses it.
 *
 * This file is deliberately **only the framing**. It encodes and parses the
 * four messages and nothing else. The hard part -- turning an m2's 128-byte
 * challenge into the 20-byte response -- is [FairPlaySapCore], which is a
 * separate concern with its own licence and its own test vectors.
 *
 * Keeping the split matters because the framing is the part that is *fully*
 * determined by public captures, while the core is the part that is
 * reverse-engineered. A caller can therefore be tested end to end against a
 * real receiver's bytes long before the core is trustworthy.
 *
 * Every layout below was verified against bytes captured from a real legacy
 * receiver (model `AppleTV3,2`, `AirTunes/220.68`); the fixtures live in
 * `src/test/resources/fairplay/`. See `docs/fairplay-research.md`.
 *
 * ### The record
 *
 * Every message begins with the same 12-byte header:
 *
 * ```
 * 0   46 50 4c 59   "FPLY"
 * 4   03 01 TT 00   version 03 01, message type TT, 00
 * 8   00 00 00 LL   big-endian body length
 * 12  ...           body
 * ```
 *
 * ### The four messages
 *
 * | | total | body | body layout |
 * |---|---|---|---|
 * | m1 | 16 | `0x04` | `02 00 CC BB` -- `CC` is a capability mask |
 * | m2 | 142 | `0x82` | `02 MM` + 128-byte challenge -- `MM` is the mode |
 * | m3 | 164 | `0x98` | `MM 8f 1a 9c` + 128-byte body + 20-byte response |
 * | m4 | 32 | `0x14` | the 20-byte response echoed back |
 *
 * `CC` in m1 is a **capability mask, not the mode.** The sender advertises
 * capabilities; the *receiver* then picks a mode in m2 byte 13. Both are small
 * integers that reach 3, and conflating them is the classic way to end up
 * answering an m2 with the wrong key schedule.
 */
object FairPlayRecords {

    val MAGIC = byteArrayOf(0x46, 0x50, 0x4c, 0x59) // "FPLY"

    const val VERSION_MAJOR: Byte = 0x03
    const val VERSION_MINOR: Byte = 0x01

    const val TYPE_M1: Byte = 0x01
    const val TYPE_M2: Byte = 0x02
    const val TYPE_M3: Byte = 0x03
    const val TYPE_M4: Byte = 0x04

    const val HEADER_BYTES = 12
    const val CHALLENGE_BYTES = 128
    const val RESPONSE_BYTES = 20

    const val M1_BYTES = HEADER_BYTES + 4          // 16
    const val M2_BYTES = HEADER_BYTES + 130        // 142
    const val M3_BYTES = HEADER_BYTES + 152        // 164
    const val M4_BYTES = HEADER_BYTES + RESPONSE_BYTES // 32

    /** Body marker byte that precedes the payload in m1 and m2. */
    const val PAYLOAD_MARKER: Byte = 0x02

    /**
     * The three-byte label in an m3 body. Constant across every capture seen,
     * from a 2013 Apple TV to a modern HomePod.
     */
    val M3_LABEL = byteArrayOf(0x8f.toByte(), 0x1a, 0x9c.toByte())

    /**
     * Apple's sender advertises 3 here with any unavailable capability cleared.
     * It is a *capability* mask; see the class comment.
     */
    const val DEFAULT_M1_CAPABILITIES: Byte = 0x03

    /** The fourth m1 byte, constant in every observed capture. */
    const val M1_TRAILER: Byte = 0xbb.toByte()

    /**
     * An m2 selects one of four modes in byte 13, and the mode changes the
     * answer: the same challenge produces four different responses because the
     * mode picks both the CBC IV and the AES round keys.
     *
     * Only [MODE_3] has ever been observed, from a 2013 Apple TV through to a
     * HomePod on firmware 23L471.
     */
    enum class Mode(val value: Byte) {
        MODE_0(0), MODE_1(1), MODE_2(2), MODE_3(3);

        companion object {
            fun of(value: Byte): Mode? = entries.firstOrNull { it.value == value }
        }
    }

    // ---------------------------------------------------------------- m1

    /**
     * Builds the 16-byte m1 a sender opens with. The sender's own identity is
     * the capability mask; nothing else is configurable.
     */
    fun m1(capabilities: Byte = DEFAULT_M1_CAPABILITIES): ByteArray {
        val record = header(TYPE_M1, 4)
        record[12] = PAYLOAD_MARKER
        record[13] = 0x00
        record[14] = capabilities
        record[15] = M1_TRAILER
        return record
    }

    // ---------------------------------------------------------------- m2

    /** An m2 as parsed, with the framing already checked. */
    data class Challenge(val mode: Mode, val challenge: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Challenge && mode == other.mode && challenge.contentEquals(other.challenge))

        override fun hashCode(): Int = 31 * mode.hashCode() + challenge.contentHashCode()
    }

    /**
     * Parses a receiver's m2, checking every framing field before trusting the
     * 128 challenge bytes.
     *
     * Returns null when [record] is not a well-formed m2 at all. A mode outside
     * 0..3 is reported as [Failure.UnsupportedMode] rather than as null: the
     * record *is* an m2, and saying so is what lets a caller distinguish "this
     * receiver speaks a different dialect" from "this is not FairPlay".
     */
    fun parseM2(record: ByteArray): Result<Challenge> {
        val framing = checkFraming(record, TYPE_M2, M2_BYTES)
        if (framing != null) return Result.failure(framing)
        if (record[12] != PAYLOAD_MARKER) {
            return Result.failure(Failure.Malformed("m2 payload marker was 0x%02x, expected 0x02".format(record[12])))
        }
        val mode = Mode.of(record[13])
            ?: return Result.failure(Failure.UnsupportedMode(record[13]))
        return Result.success(Challenge(mode, record.copyOfRange(14, M2_BYTES)))
    }

    // ---------------------------------------------------------------- m3

    /**
     * Builds the 164-byte m3 from a receiver's m2 and the 20-byte response the
     * SAP core computed for it.
     *
     * [localSap] is the sender's own 128-byte opaque SAP value, which a
     * session-aware implementation regenerates per session. A *frozen* local
     * SAP makes every m3 byte-identical, which strict receivers reject as a
     * replay -- documented symptom `RTSP/1.0 466 Key Management Error`.
     *
     * ### The body is ENCRYPTED, not raw
     *
     * Bytes 16..144 carry the local SAP **through the mode's message cipher**
     * ([FairPlayMessageCipher.encryptBody]), not in the clear. Upstream:
     *
     * ```go
     * body := fpsapcore.EncryptMessageBodyMode3(s.localSAP)
     * copy(m3[16:144], body[:])
     * ```
     *
     * Writing the raw SAP produces a frame with correct framing, a correct
     * label and a correct 20-byte response -- and which every receiver rejects,
     * because the body it decrypts is not the SAP the response was computed
     * over. This was a real bug here; the m3 body cipher had been implemented and
     * unit-tested but was never actually called on this path.
     */
    fun m3(mode: Mode, localSap: ByteArray, response: ByteArray): ByteArray {
        require(localSap.size == CHALLENGE_BYTES) {
            "local SAP must be $CHALLENGE_BYTES bytes, got ${localSap.size}"
        }
        require(response.size == RESPONSE_BYTES) {
            "response must be $RESPONSE_BYTES bytes, got ${response.size}"
        }
        val record = header(TYPE_M3, 152)
        record[12] = mode.value
        M3_LABEL.copyInto(record, 13)
        FairPlayMessageCipher.encryptBody(mode, localSap).copyInto(record, 16)
        response.copyInto(record, 144)
        return record
    }

    /** Parses an m3 (receiver side). Returns null when it is not a well-formed m3. */
    fun parseM3(record: ByteArray): Result<M3> {
        val framing = checkFraming(record, TYPE_M3, M3_BYTES)
        if (framing != null) return Result.failure(framing)
        val mode = Mode.of(record[12])
            ?: return Result.failure(Failure.UnsupportedMode(record[12]))
        if (!record.copyOfRange(13, 16).contentEquals(M3_LABEL)) {
            return Result.failure(Failure.Malformed("m3 label is not 8f 1a 9c"))
        }
        return Result.success(M3(mode, record.copyOfRange(16, 144), record.copyOfRange(144, M3_BYTES)))
    }

    data class M3(val mode: Mode, val body: ByteArray, val response: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is M3 && mode == other.mode &&
                    body.contentEquals(other.body) && response.contentEquals(other.response)
                )

        override fun hashCode(): Int =
            (31 * mode.hashCode() + body.contentHashCode()) * 31 + response.contentHashCode()
    }

    // ---------------------------------------------------------------- m4

    /**
     * The m4 a receiver returns on success: the m3's 20-byte response echoed
     * back. A receiver that *rejects* an m3 sends no m4 at all -- it answers
     * `403` with an empty body, or the 12-byte error frame.
     */
    fun m4(response: ByteArray): ByteArray {
        require(response.size == RESPONSE_BYTES) {
            "response must be $RESPONSE_BYTES bytes, got ${response.size}"
        }
        val record = header(TYPE_M4, RESPONSE_BYTES)
        response.copyInto(record, HEADER_BYTES)
        return record
    }

    /** Checks an m4 against the m3 it should confirm. */
    fun confirmM4(m4: ByteArray, m3: ByteArray): Boolean {
        if (checkFraming(m4, TYPE_M4, M4_BYTES) != null) return false
        if (m3.size != M3_BYTES) return false
        return m4.copyOfRange(HEADER_BYTES, M4_BYTES)
            .contentEquals(m3.copyOfRange(144, M3_BYTES))
    }

    // ------------------------------------------------------------ errors

    /**
     * The 12-byte frame a receiver sends to refuse an m3.
     *
     * Captured verbatim from the real legacy receiver: a deliberately corrupted
     * m3 earns exactly this and nothing else.
     *
     * ```
     * 1e 1e 1e 1e  03 01 04 9c  00 00 00 00
     * ```
     *
     * Note what is *not* here: the `FPLY` magic. It is a refusal frame, not a
     * record, so [isErrorFrame] has to test the leading `1e` run rather than a
     * magic. Byte 7 carries a status code; `0x9c` is the only value observed.
     */
    val ERROR_FRAME_PREFIX = byteArrayOf(0x1e, 0x1e, 0x1e, 0x1e)

    const val ERROR_FRAME_BYTES = 12
    const val ERROR_STATUS_M3_REJECTED: Byte = 0x9c.toByte()

    fun isErrorFrame(bytes: ByteArray): Boolean =
        bytes.size >= ERROR_FRAME_BYTES && bytes.copyOfRange(0, 4).contentEquals(ERROR_FRAME_PREFIX)

    /** The status byte of an error frame, or null when [bytes] is not one. */
    fun errorStatus(bytes: ByteArray): Byte? =
        if (isErrorFrame(bytes)) bytes[7] else null

    // ----------------------------------------------------------- helpers

    private fun header(type: Byte, bodyLength: Int): ByteArray {
        val record = ByteArray(HEADER_BYTES + bodyLength)
        MAGIC.copyInto(record, 0)
        record[4] = VERSION_MAJOR
        record[5] = VERSION_MINOR
        record[6] = type
        record[7] = 0x00
        ByteBuffer.wrap(record, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(bodyLength)
        return record
    }

    /** Returns a [Failure] when [record]'s framing does not match, else null. */
    private fun checkFraming(record: ByteArray, type: Byte, expectedBytes: Int): Failure? {
        if (record.size != expectedBytes) {
            return Failure.Malformed("expected $expectedBytes bytes, got ${record.size}")
        }
        if (!record.copyOfRange(0, 4).contentEquals(MAGIC)) {
            return Failure.Malformed("missing FPLY magic")
        }
        if (record[4] != VERSION_MAJOR || record[5] != VERSION_MINOR) {
            return Failure.Malformed(
                "version 0x%02x%02x, expected 0x0301".format(record[4], record[5])
            )
        }
        if (record[6] != type) {
            return Failure.Malformed("message type 0x%02x, expected 0x%02x".format(record[6], type))
        }
        if (record[7] != 0x00.toByte()) {
            return Failure.Malformed("reserved byte was 0x%02x".format(record[7]))
        }
        val declared = ByteBuffer.wrap(record, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (declared != expectedBytes - HEADER_BYTES) {
            return Failure.Malformed(
                "declared body length $declared, expected ${expectedBytes - HEADER_BYTES}"
            )
        }
        return null
    }

    /** Why a record could not be used. */
    sealed class Failure(message: String) : Exception(message) {
        class Malformed(message: String) : Failure(message)
        class UnsupportedMode(val mode: Byte) :
            Failure("receiver selected FairPlay mode 0x%02x, which is not implemented".format(mode))
    }
}
