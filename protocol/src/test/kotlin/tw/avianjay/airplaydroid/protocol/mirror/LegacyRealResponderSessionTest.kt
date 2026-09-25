package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **whole legacy path with the real FairPlay core** and a receiver that
 * checks the m3 body.
 *
 * [LegacySessionEndToEndTest] drives the same session but with a stub responder,
 * deliberately, so that a crypto regression cannot be confused with a session
 * one. This test is the complement: it uses
 * [tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponderImpl] *and* the
 * mock's [MockLegacyReceiver.SessionPolicy.ACCEPT_DECRYPTABLE_BODY] policy, which
 * decrypts the m3 body and refuses anything that is not a well-formed local SAP.
 *
 * That combination is new and it closes a real gap: until Phase 2 landed, this
 * test was impossible, and the m3-body bug (a raw, unencrypted body) survived
 * because nothing exercised the real responder against a receiver that checks.
 *
 * What it still does **not** prove: that a real receiver accepts the session. The
 * mock is not a receiver. But it does prove the three pieces - real crypto, real
 * framing, body-checking peer - work together, offline and repeatably.
 */
class LegacyRealResponderSessionTest {

    private fun withBodyCheckingMock(
        seen: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        body: (port: Int, nextRecorder: () -> MockLegacyReceiver.StreamRecorder) -> Unit,
    ) {
        val server = ServerSocket(0)
        val recorders = java.util.concurrent.ConcurrentLinkedQueue<MockLegacyReceiver.StreamRecorder>()

        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    val recorder = MockLegacyReceiver.StreamRecorder()
                    recorders += recorder
                    thread(isDaemon = true) {
                        MockLegacyReceiver.serveForTestWithPolicy(
                            socket,
                            MockLegacyReceiver.SessionPolicy.ACCEPT_DECRYPTABLE_BODY,
                            seen,
                            recorder,
                        )
                    }
                }
            }
            body(server.localPort) {
                val before = recorders.size
                val deadline = System.currentTimeMillis() + 5_000
                while (recorders.size <= before && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5)
                }
                recorders.last()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `the real FairPlay responder completes a session with a body-checking receiver`() {
        withBodyCheckingMock { port, _ ->
            // No responder override: this runs FairPlayResponderImpl, i.e. the
            // real Phase 1 + bridge + Phase 2 chain.
            val opened = LegacyMirrorSessionFactory.open(
                host = "127.0.0.1",
                rtspPort = port,
                streamPort = port,
            )
            try {
                assertEquals(1920, opened.display.width)
                assertEquals(1080, opened.display.height)
            } finally {
                opened.close()
            }
        }
    }

    @Test
    fun `video written after a real handshake reaches the receiver`() {
        withBodyCheckingMock { port, nextRecorder ->
            val opened = LegacyMirrorSessionFactory.open(
                host = "127.0.0.1",
                rtspPort = port,
                streamPort = port,
            )
            val recorder = nextRecorder()
            try {
                opened.video.setCodecConfig(byteArrayOf(1, 0x64, 0xc0.toByte(), 0x28), 1920, 1080)
                opened.video.sendFrame(ByteArray(96) { 0x5a }, keyframe = true)
            } finally {
                opened.close()
            }

            val streamed = recorder.awaitClosed()
            assertTrue(streamed.isNotEmpty(), "the receiver should have recorded stream bytes")

            // The codec packet is cleartext; the frame after it is CIPHERTEXT,
            // because LegacyVideoStream is handed a real LegacyMirrorCipher keyed
            // from this session's FairPlay-wrapped stream key.
            val codec = LegacyStreamPackets.decode(streamed, 0)!!
            assertEquals(LegacyStreamPackets.TYPE_CODEC_DATA, codec.type)
            assertEquals(byteArrayOf(1, 0x64, 0xc0.toByte(), 0x28).toList(), codec.payload.toList())

            val video = LegacyStreamPackets.decode(streamed, LegacyStreamPackets.wireSize(codec.payload.size))!!
            assertEquals(LegacyStreamPackets.TYPE_VIDEO, video.type)
            assertEquals(96, video.payload.size, "CTR must preserve the payload length")

            // The frame was sent as 96 bytes of 0x5a. Assert it is not STILL
            // that -- comparing whole buffers, not scanning for a byte value:
            // with 96 ciphertext bytes there is a ~31% chance some single byte
            // happens to equal 0x5a, so a per-byte check would be flaky.
            val plaintext = ByteArray(96) { 0x5a }
            assertFalse(
                video.payload.contentEquals(plaintext),
                "the frame must not travel in the clear",
            )
        }
    }

    @Test
    fun `two consecutive sessions both complete, each with its own local SAP`() {
        // A session-aware sender must produce a fresh local SAP per session; a
        // receiver that has seen one refuses a byte-identical repeat. The seen-set
        // is shared across both sessions here, so a sender that reused its local
        // SAP would fail the second session.
        val seenAcrossSessions = ConcurrentHashMap.newKeySet<String>()

        repeat(2) { i ->
            withBodyCheckingMock(seenAcrossSessions) { port, _ ->
                val opened = LegacyMirrorSessionFactory.open(
                    host = "127.0.0.1",
                    rtspPort = port,
                    streamPort = port,
                )
                try {
                    assertEquals(1920, opened.display.width, "session $i")
                } finally {
                    opened.close()
                }
            }
        }

        assertEquals(
            2,
            seenAcrossSessions.size,
            "both sessions must have presented a distinct local SAP",
        )
    }
}
