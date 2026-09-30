package tw.avianjay.airplaydroid.protocol.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The hand-written protobuf, against bytes from the real one.
 *
 * Every expected hex string below was produced by `cast_channel_pb2` -- the
 * generated protobuf that pychromecast ships -- so these tests pin this codec to
 * what a real sender puts on the wire rather than to its own reading of the
 * `.proto` file. A codec that agrees only with itself is how the SRP bug in the
 * README got through.
 */
class CastMessageTest {

    private val pingHex = "0800120873656e6465722d301a0a72656365697665722d30222775726e3a782d636173743a636f6d2e676f6f676c" +
        "652e636173742e74702e6865617274626561742800320f7b2274797065223a2250494e47227d"

    private val authHex = "0800120873656e6465722d301a0a72656365697665722d30222875726e3a782d636173743a636f6d2e676f6f676c" +
        "652e636173742e74702e6465766963656175746828013a180a1608011210000102030405060708090a0b0c0d0e0f1801"

    @Test
    fun encodesAStringMessageExactlyAsProtobufDoes() {
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = CastReceiver.NS_HEARTBEAT,
            payloadUtf8 = """{"type":"PING"}""",
        )
        assertEquals(pingHex, message.encode().toHex())
    }

    @Test
    fun decodesProtobufsStringMessage() {
        val message = CastMessage.decode(pingHex.fromHex())
        assertEquals("sender-0", message.sourceId)
        assertEquals("receiver-0", message.destinationId)
        assertEquals(CastReceiver.NS_HEARTBEAT, message.namespace)
        assertEquals("""{"type":"PING"}""", message.payloadUtf8)
    }

    @Test
    fun decodesProtobufsBinaryMessageAndItsChallenge() {
        val message = CastMessage.decode(authHex.fromHex())
        assertEquals(CastReceiver.NS_DEVICE_AUTH, message.namespace)
        val challenge = assertNotNull(DeviceAuth.parseChallenge(assertNotNull(message.payloadBinary)))
        assertContentEquals(ByteArray(16) { it.toByte() }, challenge.senderNonce)
        assertEquals(DeviceAuth.HashAlgorithm.SHA256, challenge.hashAlgorithm)
        // And it re-encodes to the same bytes.
        assertEquals(authHex, message.encode().toHex())
    }

    @Test
    fun anEmptyChallengeDefaultsToSha1AndNoNonce() {
        // What the first-generation Chromecast senders sent: `challenge {}`.
        val challenge = assertNotNull(DeviceAuth.parseChallenge("0a00".fromHex()))
        assertEquals(0, challenge.senderNonce.size)
        assertEquals(DeviceAuth.HashAlgorithm.SHA1, challenge.hashAlgorithm)
    }

    @Test
    fun framesRoundTripThroughAStream() {
        val out = ByteArrayOutputStream()
        val first = CastMessage("a", "b", CastReceiver.NS_RECEIVER, payloadUtf8 = "{}")
        val second = CastMessage("c", "d", CastReceiver.NS_DEVICE_AUTH, payloadBinary = byteArrayOf(1, 2, 3))
        CastMessage.write(out, first)
        CastMessage.write(out, second)

        val bytes = out.toByteArray()
        // A 4-byte big-endian length, then the body.
        assertEquals(first.encode().size, (bytes[0].toInt() shl 24) or (bytes[3].toInt() and 0xFF))

        val input = ByteArrayInputStream(bytes)
        assertEquals("{}", CastMessage.read(input).payloadUtf8)
        assertContentEquals(byteArrayOf(1, 2, 3), CastMessage.read(input).payloadBinary)
        assertFailsWith<EOFException> { CastMessage.read(input) }
    }

    @Test
    fun refusesAnOversizedFrameRatherThanAllocatingIt() {
        val hostile = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<IOException> { CastMessage.read(ByteArrayInputStream(hostile)) }
    }

    @Test
    fun skipsFieldsItDoesNotKnow() {
        // Field 9 (a varint) appended: a later protocol version's field must not
        // make the message unreadable.
        val extended = pingHex.fromHex() + byteArrayOf(0x48, 0x05)
        assertEquals("""{"type":"PING"}""", CastMessage.decode(extended).payloadUtf8)
    }

    @Test
    fun rejectsTruncatedInput() {
        val truncated = pingHex.fromHex().copyOf(20)
        assertFailsWith<ProtoException> { CastMessage.decode(truncated) }
    }

    @Test
    fun multiByteLengthsAndUtf8SurviveARoundTrip() {
        val long = "名".repeat(300)
        val decoded = CastMessage.decode(CastMessage("s", "d", "n", payloadUtf8 = long).encode())
        assertEquals(long, decoded.payloadUtf8)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.fromHex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
