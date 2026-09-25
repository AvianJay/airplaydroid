package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RTSP type-110 legacy path, driven end to end against [MockLegacyReceiver]
 * over real sockets: pair-setup and pair-verify, FairPlay on the same
 * connection, both SETUPs, the timing query, the data channel and TEARDOWN.
 *
 * The frames are then decrypted the way RPiPlay's `mirror_buffer_init_aes`
 * does, with the key derived **here**, independently of the session. That is
 * what makes this more than a round trip through our own code.
 *
 * The same session has been accepted, decrypted and displayed by LonelyScreen;
 * this test is what keeps it that way offline.
 */
class LegacyRtspSessionEndToEndTest {

    /** Satisfies the mock's fresh-SAP policy; the real core is tested elsewhere. */
    private val stubResponder = FairPlayResponder { _, challenge -> ByteArray(20) { challenge[it] } }

    private fun withReceiver(
        rtsp: Boolean,
        timingPort: Int = 0,
        body: (port: Int, records: LinkedBlockingQueue<MockLegacyReceiver.RtspRecord>) -> Unit,
    ) {
        val server = ServerSocket(0)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val records = LinkedBlockingQueue<MockLegacyReceiver.RtspRecord>()
        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                    thread(isDaemon = true) {
                        if (rtsp) {
                            val record = MockLegacyReceiver.RtspRecord(timingPort).also { records += it }
                            MockLegacyReceiver.serveRtspForTest(socket, MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION, seen, record)
                        } else {
                            MockLegacyReceiver.serveForTestWithPolicy(
                                socket, MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION, seen, MockLegacyReceiver.StreamRecorder(),
                            )
                        }
                    }
                }
            }
            body(server.localPort, records)
        } finally {
            server.close()
        }
    }

    private fun sha512(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-512").run {
        parts.forEach { update(it) }
        digest()
    }

    private val avcC = byteArrayOf(0x01, 0x42, 0xC0.toByte(), 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x04, 0x67, 0x42, 0xC0.toByte(), 0x1F, 0x01, 0x00, 0x02, 0x68, 0xCE.toByte())
    private val frames = listOf(
        byteArrayOf(0, 0, 0, 5, 0x65, 1, 2, 3, 4),
        ByteArray(300) { (it * 7).toByte() },
        ByteArray(17) { 0x41 },
    )

    @Test
    fun `a session pairs, keys and streams video the receiver can decrypt`() = withReceiver(rtsp = true) { port, records ->
        val streamKey = ByteArray(16) { (0xA0 + it).toByte() }
        val session = LegacyRtspMirrorSession.open(
            Endpoint("127.0.0.1", port), responder = stubResponder, streamKey = streamKey,
        )
        session.setCodecConfig(avcC, 1280, 720)
        frames.forEachIndexed { i, f -> session.sendFrame(f, keyframe = i == 0) }
        session.close()

        val record = assertNotNull(records.poll(5, TimeUnit.SECONDS), "the receiver saw no control connection")
        val stream = record.video.awaitClosed()

        assertTrue(record.pairVerified, "pair-verify must complete, with both signatures checked")
        assertTrue(session.paired)
        assertEquals(72, record.ekey!!.size)
        assertEquals("FPLY", String(record.ekey!!.copyOf(4)))
        assertEquals(16, record.eiv!!.size)
        assertEquals(1, record.timingReplies, "the timing query must be answered on the advertised timingPort")
        assertTrue(record.teardown, "close() must send TEARDOWN")

        // The receiver's derivation, written out here rather than calling ours.
        val id = java.lang.Long.toUnsignedString(assertNotNull(record.streamConnectionId))
        val key = sha512("AirPlayStreamKey$id".toByteArray(), streamKey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(), streamKey).copyOf(16)
        val decrypt = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

        val packets = generateSequence(0 to LegacyStreamPackets.decode(stream, 0)) { (offset, packet) ->
            val next = offset + LegacyStreamPackets.wireSize(packet!!.payload.size)
            if (next >= stream.size) null else next to LegacyStreamPackets.decode(stream, next)
        }.map { (offset, packet) -> offset to packet!! }
            .filter { it.second.type != LegacyStreamPackets.TYPE_HEARTBEAT }
            .toList()

        val (codecOffset, codec) = packets.first()
        assertEquals(LegacyStreamPackets.TYPE_CODEC_DATA, codec.type)
        assertContentEquals(avcC, codec.payload, "codec data goes in the clear")
        assertEquals(0x16, stream[codecOffset + 6].toInt(), "the iOS 9 header on this path")

        val video = packets.drop(1)
        assertEquals(frames.size, video.size)
        video.forEachIndexed { i, (offset, packet) ->
            assertContentEquals(frames[i], decrypt.update(packet.payload), "frame $i must decrypt on one running keystream")
            assertEquals(if (i == 0) 0x10 else 0, stream[offset + 5].toInt(), "keyframe flag on frame $i")
        }
    }

    /** Streams [frames] (only the first a keyframe unless [allKeyframes]) and returns what the mock recorded. */
    private fun stream(
        port: Int,
        records: LinkedBlockingQueue<MockLegacyReceiver.RtspRecord>,
        streamKey: ByteArray,
        allKeyframes: Boolean = false,
    ): Pair<LegacyRtspMirrorSession, MockLegacyReceiver.RtspRecord> {
        val session = LegacyRtspMirrorSession.open(Endpoint("127.0.0.1", port), responder = stubResponder, streamKey = streamKey)
        session.setCodecConfig(avcC, 1280, 720)
        frames.forEachIndexed { i, f -> session.sendFrame(f, keyframe = allKeyframes || i == 0) }
        session.close()
        val record = assertNotNull(records.poll(5, TimeUnit.SECONDS))
        record.video.awaitClosed()
        return session to record
    }

    /** The recorded packets other than heartbeats. */
    private fun packets(stream: ByteArray): List<LegacyStreamPackets.Decoded> {
        val out = mutableListOf<LegacyStreamPackets.Decoded>()
        var offset = 0
        while (offset < stream.size) {
            val packet = LegacyStreamPackets.decode(stream, offset)!!
            if (packet.type != LegacyStreamPackets.TYPE_HEARTBEAT) out += packet
            offset += LegacyStreamPackets.wireSize(packet.payload.size)
        }
        return out
    }

    @Test
    fun `a receiver that advertises a timingPort gets the key mixed with the pair-verify secret`() =
        withReceiver(rtsp = true, timingPort = 7011) { port, records ->
            val streamKey = ByteArray(16) { (0x30 + it).toByte() }
            val (session, record) = stream(port, records, streamKey)
            assertEquals(LegacyRtspMirrorSession.KeySeed.MIXED, session.keySeed)

            // RPiPlay's mirror_buffer_init_aes, with the receiver's own copy of the secret.
            val seed = sha512(streamKey, assertNotNull(record.sharedSecret)).copyOf(16)
            val id = java.lang.Long.toUnsignedString(record.streamConnectionId!!)
            val decrypt = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(sha512("AirPlayStreamKey$id".toByteArray(), seed).copyOf(16), "AES"),
                    IvParameterSpec(sha512("AirPlayStreamIV$id".toByteArray(), seed).copyOf(16)),
                )
            }
            val video = packets(record.video.bytes).filter { it.type == LegacyStreamPackets.TYPE_VIDEO }
            video.forEachIndexed { i, p -> assertContentEquals(frames[i], decrypt.update(p.payload), "frame $i") }
        }

    @Test
    fun `a receiver that advertises no timingPort gets the raw key`() = withReceiver(rtsp = true) { port, records ->
        val (session, _) = stream(port, records, ByteArray(16))
        assertEquals(LegacyRtspMirrorSession.KeySeed.RAW, session.keySeed)
    }

    @Test
    fun `every keyframe is preceded by the codec packet, once`() = withReceiver(rtsp = true) { port, records ->
        // A receiver whose decoder attaches mid-stream needs SPS/PPS again.
        val (_, record) = stream(port, records, ByteArray(16), allKeyframes = true)
        val types = packets(record.video.bytes).map { it.type }
        val c = LegacyStreamPackets.TYPE_CODEC_DATA
        val v = LegacyStreamPackets.TYPE_VIDEO
        assertEquals(listOf(c, v, c, v, c, v), types, "one codec packet before each keyframe, never two in a row")
    }

    @Test
    fun `the video key is RPiPlay's derivation from the raw FairPlay key`() {
        // Pinned against Python's hashlib, independently of this code.
        val (key, iv) = LegacyRtspMirrorSession.videoKey(ByteArray(16) { it.toByte() }, null, 1234567890123456789L)
        assertEquals("90361780ce6c4a81b11725ffe57b4db8", key.joinToString("") { "%02x".format(it) })
        assertEquals("5a885205c8ee6252cedbfc64354b7965", iv.joinToString("") { "%02x".format(it) })

        // %llu: an id with the top bit set is printed unsigned, not negative.
        val (high, _) = LegacyRtspMirrorSession.videoKey(ByteArray(16) { it.toByte() }, null, Long.MIN_VALUE + 5)
        assertEquals("47b6f713d5631face24aa09bf0f3a8b9", high.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `connect takes the RTSP path when the receiver serves it`() = withReceiver(rtsp = true) { port, _ ->
        LegacyMirrorSessionFactory.connect("127.0.0.1", port, responder = stubResponder).use { connected ->
            assertEquals(LegacyMirrorSessionFactory.Path.RTSP_TYPE_110, connected.path)
        }
    }

    @Test
    fun `connect falls back to port 7100 when the receiver refuses the RTSP path`() = withReceiver(rtsp = false) { port, _ ->
        // Without RTSP support the mock answers /pair-setup and SETUP with 404,
        // like a receiver that only serves /stream -- here on the same port.
        val trace = mutableListOf<String>()
        LegacyMirrorSessionFactory.connect(
            "127.0.0.1", port, streamPort = port, responder = stubResponder, trace = { trace += it },
        ).use { connected ->
            assertEquals(LegacyMirrorSessionFactory.Path.PORT_7100, connected.path)
            assertEquals(ReceiverDisplay(1920, 1080), connected.display)
        }
        assertTrue(trace.any { it.startsWith("RTSP mirroring refused") }, "the fallback must be traced: $trace")
    }
}
