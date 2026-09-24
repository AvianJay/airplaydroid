package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class H264Test {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `splits on both start code lengths and drops trailing zeros`() {
        val stream = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 0, 1, 0x65, 4, 5, 0)
        val nals = H264.splitAnnexB(stream)

        assertEquals(3, nals.size)
        assertContentEquals(b(0x67, 1, 2), nals[0])
        assertContentEquals(b(0x68, 3), nals[1])
        assertContentEquals(b(0x65, 4, 5), nals[2])
    }

    @Test
    fun `respects offset and length`() {
        val stream = b(9, 9, 0, 0, 1, 0x41, 7, 9, 9)
        assertContentEquals(b(0x41, 7), H264.splitAnnexB(stream, 2, 5).single())
    }

    @Test
    fun `avcc prefixes each nal with a big-endian length`() {
        assertContentEquals(
            b(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x41),
            H264.toAvcc(listOf(b(0x65, 1), b(0x41))),
        )
    }

    @Test
    fun `avcC matches the layout from the airplay-spec iPad capture`() {
        val sps = b(0x67, 0x64, 0xc0, 0x28, 0xac, 0x56, 0x20, 0x0d, 0x81, 0x4f, 0xe5, 0x9b, 0x81, 0x01, 0x01, 0x01)
        val pps = b(0x28, 0xee, 0x3c, 0xb0)
        val expected = b(
            0x01, 0x64, 0xc0, 0x28, 0xff, 0xe1, 0x00, 0x10,
            0x67, 0x64, 0xc0, 0x28, 0xac, 0x56, 0x20, 0x0d, 0x81, 0x4f, 0xe5, 0x9b, 0x81, 0x01, 0x01, 0x01,
            0x01, 0x00, 0x04, 0x28, 0xee, 0x3c, 0xb0,
        )
        assertContentEquals(expected, H264.avcC(sps, pps))
    }

    @Test
    fun `credentials survive encode and decode`() {
        val original = HomeKitPairing.Credentials(
            clientId = "ABC-123",
            clientSeed = ByteArray(32) { it.toByte() },
            receiverId = "EEC4975A".toByteArray(),
            receiverPublicKey = ByteArray(32) { (255 - it).toByte() },
        )
        val decoded = HomeKitPairing.Credentials.decode(original.encode())

        assertEquals(original.clientId, decoded.clientId)
        assertContentEquals(original.clientSeed, decoded.clientSeed)
        assertContentEquals(original.receiverId, decoded.receiverId)
        assertContentEquals(original.receiverPublicKey, decoded.receiverPublicKey)
    }
}
