package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `FPLY` framing, asserted against bytes captured from a **real** legacy
 * receiver: an `AppleTV3,2` reporting `AirTunes/220.68`, reached over RTSP from
 * an Android phone on the same subnet.
 *
 * The fixtures in `src/test/resources/fairplay/` are verbatim wire captures, so
 * these tests fail if the framing drifts from what hardware actually accepts.
 * See `docs/fairplay-research.md`.
 */
class FairPlayRecordsTest {

    private fun resource(name: String): ByteArray =
        FairPlayRecordsTest::class.java.getResourceAsStream("/fairplay/$name")
            ?.use { it.readBytes() }
            ?: error("missing test resource fairplay/$name")

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------- real capture

    @Test
    fun `parses the m2 a real AppleTV3_2 sent`() {
        val m2 = resource("appletv32_fpsetup_m2_body.bin")
        assertEquals(142, m2.size, "the captured m2 must be 142 bytes")

        val challenge = FairPlayRecords.parseM2(m2).getOrThrow()

        // Mode 3 is the only mode any device has been observed to select.
        assertEquals(FairPlayRecords.Mode.MODE_3, challenge.mode)
        assertEquals(128, challenge.challenge.size)
        assertEquals("9001e1727e0f57f9", challenge.challenge.copyOfRange(0, 8).toHex())
    }

    @Test
    fun `the captured m2 declares a 130-byte body`() {
        val m2 = resource("appletv32_fpsetup_m2_body.bin")
        // 0x82 = 130: one marker byte, one mode byte, 128 challenge bytes.
        assertContentEquals(hex("00000082"), m2.copyOfRange(8, 12))
        assertEquals(0x02.toByte(), m2[12], "payload marker")
        assertEquals(0x03.toByte(), m2[13], "mode byte")
    }

    @Test
    fun `rejects a real m2 whose framing is corrupted`() {
        val m2 = resource("appletv32_fpsetup_m2_body.bin")

        // Wrong magic.
        val badMagic = m2.copyOf()
        badMagic[0] = 0x00
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM2(badMagic).exceptionOrNull())

        // Wrong declared length: this is the check that catches a hand-sliced
        // body, which is how a sender ends up trusting bytes it should not.
        val badLength = m2.copyOf()
        badLength[11] = 0x00
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM2(badLength).exceptionOrNull())

        // Wrong message type (m3 where an m2 belongs).
        val badType = m2.copyOf()
        badType[6] = 0x03
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM2(badType).exceptionOrNull())

        // Wrong payload marker.
        val badMarker = m2.copyOf()
        badMarker[12] = 0x03
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM2(badMarker).exceptionOrNull())
    }

    @Test
    fun `a mode outside 0 to 3 is reported as unsupported, not as malformed`() {
        val m2 = resource("appletv32_fpsetup_m2_body.bin").copyOf()
        m2[13] = 0x07

        val failure = FairPlayRecords.parseM2(m2).exceptionOrNull()
        assertIs<FairPlayRecords.Failure.UnsupportedMode>(failure)
        assertEquals(0x07.toByte(), failure.mode)
    }

    @Test
    fun `modes 0 to 3 all parse as records, since all four are real modes`() {
        // The framing layer accepts every mode the protocol defines; it is the
        // white-box core that only implements mode 3. Conflating the two would
        // make the layer reject a record it understands perfectly well.
        for (mode in 0..3) {
            val m2 = resource("appletv32_fpsetup_m2_body.bin").copyOf()
            m2[13] = mode.toByte()
            assertEquals(
                mode.toByte(),
                FairPlayRecords.parseM2(m2).getOrThrow().mode.value,
                "mode $mode should parse",
            )
        }
    }

    // ------------------------------------------------------- m1 / m3 / m4

    @Test
    fun `m1 matches the bytes a real sender sent`() {
        val captured = resource("sender_fpsetup_m1_request.bin")
        // The fixture is a full RTSP request; the body is its last 16 bytes.
        val capturedBody = captured.copyOfRange(captured.size - 16, captured.size)

        assertEquals(16, capturedBody.size)
        assertContentEquals(FairPlayRecords.m1(), capturedBody)
    }

    @Test
    fun `m1 declares a 4-byte body and carries the capability mask at byte 14`() {
        val m1 = FairPlayRecords.m1()
        assertEquals(16, m1.size)
        assertContentEquals(FairPlayRecords.MAGIC, m1.copyOfRange(0, 4))
        assertContentEquals(hex("00000004"), m1.copyOfRange(8, 12))
        assertEquals(0x02.toByte(), m1[12], "payload marker")
        assertEquals(0x00.toByte(), m1[13])
        assertEquals(FairPlayRecords.DEFAULT_M1_CAPABILITIES, m1[14])
        assertEquals(FairPlayRecords.M1_TRAILER, m1[15])
    }

    @Test
    fun `m3 round-trips through its own parser`() {
        val localSap = ByteArray(128) { (it * 7).toByte() }
        val response = ByteArray(20) { (it * 3).toByte() }

        val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, localSap, response)
        assertEquals(164, m3.size)

        val parsed = FairPlayRecords.parseM3(m3).getOrThrow()
        assertEquals(FairPlayRecords.Mode.MODE_3, parsed.mode)

        // The body on the wire is the ENCRYPTED local SAP, not the raw one.
        // A receiver decrypts it; the parser is deliberately not a decryptor,
        // so it must NOT equal the plaintext SAP.
        assertFalse(
            localSap.contentEquals(parsed.body),
            "the m3 body must be encrypted, not the raw local SAP",
        )

        // Decrypting it must give the local SAP back.
        assertContentEquals(
            localSap,
            FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, parsed.body),
            "the body must decrypt to the local SAP",
        )
        assertContentEquals(response, parsed.response)
    }

    @Test
    fun `m3 declares a 152-byte body and carries the constant label`() {
        val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, ByteArray(128), ByteArray(20))
        assertContentEquals(hex("00000098"), m3.copyOfRange(8, 12))
        assertEquals(0x03.toByte(), m3[12], "mode")
        assertContentEquals(FairPlayRecords.M3_LABEL, m3.copyOfRange(13, 16))
    }

    @Test
    fun `m3 rejects a wrong label and a wrong body size`() {
        val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, ByteArray(128), ByteArray(20))

        val badLabel = m3.copyOf()
        badLabel[13] = 0x00
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM3(badLabel).exceptionOrNull())

        assertIs<FairPlayRecords.Failure.Malformed>(
            FairPlayRecords.parseM3(m3.copyOf(m3.size - 1)).exceptionOrNull()
        )
    }

    @Test
    fun `m4 echoes the m3 response and confirms it`() {
        val response = ByteArray(20) { (it + 1).toByte() }
        val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, ByteArray(128), response)
        val m4 = FairPlayRecords.m4(response)

        assertEquals(32, m4.size)
        assertTrue(FairPlayRecords.confirmM4(m4, m3))

        // A different response must not confirm.
        assertFalse(FairPlayRecords.confirmM4(FairPlayRecords.m4(ByteArray(20)), m3))
    }

    @Test
    fun `m3 and m4 refuse payloads of the wrong size`() {
        val tooShort = runCatching {
            FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, ByteArray(127), ByteArray(20))
        }
        assertTrue(tooShort.isFailure, "a 127-byte local SAP must be refused")

        val badResponse = runCatching { FairPlayRecords.m4(ByteArray(19)) }
        assertTrue(badResponse.isFailure, "a 19-byte m4 response must be refused")
    }

    // ----------------------------------------------------- the refusal frame

    @Test
    fun `recognises the refusal frame a real receiver sent for a bad m3`() {
        val frame = resource("appletv32_m3_rejection_frame.bin")

        assertEquals(12, frame.size)
        assertTrue(FairPlayRecords.isErrorFrame(frame))
        assertEquals(FairPlayRecords.ERROR_STATUS_M3_REJECTED, FairPlayRecords.errorStatus(frame))

        // Byte-for-byte: this is what the hardware emitted.
        assertContentEquals(hex("1e1e1e1e0301049c00000000"), frame)
    }

    @Test
    fun `the refusal frame is not a FPLY record`() {
        val frame = resource("appletv32_m3_rejection_frame.bin")
        // It has no magic, so parseM3 must not mistake it for a record.
        assertIs<FairPlayRecords.Failure.Malformed>(FairPlayRecords.parseM3(frame).exceptionOrNull())
        assertNotNull(FairPlayRecords.errorStatus(frame))
    }

    @Test
    fun `a normal record is not an error frame`() {
        assertFalse(FairPlayRecords.isErrorFrame(FairPlayRecords.m1()))
        assertFalse(FairPlayRecords.isErrorFrame(resource("appletv32_fpsetup_m2_body.bin")))
        assertEquals(null, FairPlayRecords.errorStatus(FairPlayRecords.m1()))
    }

    // -------------------------------------------------------------- helpers

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
