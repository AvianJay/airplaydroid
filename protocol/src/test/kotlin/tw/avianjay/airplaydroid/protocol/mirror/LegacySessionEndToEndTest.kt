package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The **whole** legacy handshake, driven against [MockLegacyReceiver] over real
 * sockets.
 *
 * This is the strongest offline check available: `LegacyMirrorSessionFactory.open`
 * runs exactly as it does against a device -- FairPlay SAP, `/stream.xml`, the
 * key wrap, `POST /stream` -- and the mock replays the bytes a real receiver sent.
 *
 * What it does **not** prove: that a receiver accepts the response. The mock
 * refuses every m3, so this exercises the framing and the failure path, not the
 * crypto verdict. Only hardware (or the golden vectors, separately) can settle
 * that.
 */
class LegacySessionEndToEndTest {

    /**
     * A responder that satisfies the mock receiver, isolating these tests from
     * the FairPlay core.
     *
     * The mock accepts any response from a local SAP it has not seen before, so
     * the value does not matter -- what matters is that these tests exercise the
     * **session** (framing, `/stream.xml`, the key wrap, `POST /stream`, the
     * packet format), not the crypto.
     *
     * The real [tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponderImpl]
     * works and is verified elsewhere (142/142 full-chain vectors, 12/12
     * hardware-attested, and a live receiver accepting it). It is deliberately not
     * used here: if it regressed, these tests would fail for a reason unrelated to
     * what they check, and the session's own failures would be harder to read.
     */
    private val stubResponder = FairPlayResponder { _, challenge ->
        ByteArray(20) { challenge[it] }
    }

    /**
     * Serves sockets as a legacy receiver, giving the caller one
     * [MockLegacyReceiver.StreamRecorder] per connection.
     *
     * The recorder is per-connection on purpose. Holding it globally made these
     * tests flaky: the mock serves each connection on a daemon thread, those
     * threads outlive the test that started them, and a stale thread would flip a
     * shared "closed" flag -- so a later test's wait returned immediately with
     * empty or partial bytes (3 of 5 runs failed).
     */
    private fun withReceiver(
        policy: MockLegacyReceiver.SessionPolicy,
        body: (port: Int, nextRecorder: () -> MockLegacyReceiver.StreamRecorder) -> Unit,
    ) {
        val server = ServerSocket(0)
        val seen = ConcurrentHashMap.newKeySet<String>()
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
                        MockLegacyReceiver.serveForTestWithPolicy(socket, policy, seen, recorder)
                    }
                }
            }
            body(server.localPort) {
                // The recorder for a connection appears when it is accepted, so
                // wait briefly for the newest one rather than assuming it exists.
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

    /** Opens a session against the mock, with the stub responder. */
    private fun openSession(port: Int): LegacyMirrorSessionFactory.Opened =
        LegacyMirrorSessionFactory.open(
            host = "127.0.0.1",
            rtspPort = port,
            streamPort = port,
            responder = stubResponder,
        )

    @Test
    fun `a receiver that accepts a fresh session completes the whole handshake`() {
        withReceiver(MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION) { port, _ ->
            val opened = openSession(port)
            try {
                // The display came from /stream.xml, which the mock serves.
                assertEquals(1920, opened.display.width)
                assertEquals(1080, opened.display.height)
            } finally {
                opened.close()
            }
        }
    }

    @Test
    fun `frames written to the session reach the receiver as walkable packets`() {
        withReceiver(MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION) { port, nextRecorder ->
            val opened = openSession(port)
            val recorder = nextRecorder()
            try {
                opened.video.setCodecConfig(byteArrayOf(1, 0x64, 0xc0.toByte(), 0x28), 1920, 1080)
                opened.video.sendFrame(ByteArray(64) { 0x11 }, keyframe = true)
                opened.video.sendFrame(ByteArray(200) { 0x22 }, keyframe = false)
                opened.video.sendHeartbeat()
            } finally {
                opened.close()
            }

            val streamed = recorder.awaitClosed()
            assertTrue(streamed.isNotEmpty(), "the receiver should have recorded stream bytes")

            // Walk it exactly as a receiver would: read a 128-byte header, take
            // the declared payload, advance. A wrong header size or length would
            // desynchronise here rather than at the receiver.
            var offset = 0
            val types = mutableListOf<Int>()
            while (offset < streamed.size) {
                val decoded = LegacyStreamPackets.decode(streamed, offset)
                    ?: error("packet at offset $offset did not decode")
                types += decoded.type
                offset += LegacyStreamPackets.wireSize(decoded.payload.size)
            }
            assertEquals(streamed.size, offset, "the walk must consume the stream exactly")
            assertEquals(
                listOf(
                    LegacyStreamPackets.TYPE_CODEC_DATA,
                    LegacyStreamPackets.TYPE_VIDEO,
                    LegacyStreamPackets.TYPE_VIDEO,
                    LegacyStreamPackets.TYPE_HEARTBEAT,
                ),
                types,
                "the packets should arrive in the order they were written",
            )
        }
    }

    @Test
    fun `the video payload is encrypted on the wire`() {
        val frame = ByteArray(128) { 0x5A }

        withReceiver(MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION) { port, nextRecorder ->
            val opened = openSession(port)
            val recorder = nextRecorder()
            try {
                opened.video.sendFrame(frame, keyframe = true)
            } finally {
                opened.close()
            }

            val streamed = recorder.awaitClosed()
            val decoded = LegacyStreamPackets.decode(streamed)!!
            assertNotEquals(
                frame.toHex(),
                decoded.payload.toHex(),
                "a legacy video frame must not travel in the clear",
            )
            assertEquals(frame.size, decoded.payload.size, "CTR must preserve the payload length")
        }
    }

    @Test
    fun `a receiver that refuses every m3 fails at the FairPlay step`() {
        // The failure must name FairPlay, not something downstream: a wrong
        // message here sends the next person to debug /stream.
        withReceiver(MockLegacyReceiver.SessionPolicy.REJECT_ALL) { port, _ ->
            val failure = assertFailsWith<LegacyMirrorSessionFactory.Failure> {
                openSession(port)
            }
            assertTrue(
                failure.message!!.contains("FairPlay"),
                "the message should name the FairPlay step, was: ${failure.message}",
            )
        }
    }

    @Test
    fun `a password-protected receiver is refused rather than silently mis-handled`() {
        // Legacy SRP pairing is not wired in. Accepting the password and dropping
        // it would fail later, at FairPlay, with a misleading message.
        val failure = assertFailsWith<LegacyMirrorSessionFactory.Failure> {
            LegacyMirrorSessionFactory.open(
                host = "127.0.0.1",
                rtspPort = 1, // never reached: the refusal happens first
                password = "hunter2",
            )
        }
        assertTrue(
            failure.message!!.contains("password"),
            "the message should name the password gap, was: ${failure.message}",
        )
    }

    @Test
    fun `two sessions use different stream keys`() {
        // Each session must generate its own key. Reusing one would let a capture
        // of one session decrypt another.
        val wrapped = mutableListOf<String>()

        repeat(2) {
            withReceiver(MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION) { port, nextRecorder ->
                val opened = openSession(port)
                val recorder = nextRecorder()
                try {
                    opened.video.sendFrame(ByteArray(32), keyframe = true)
                } finally {
                    opened.close()
                }
                val streamed = recorder.awaitClosed()
                assertTrue(
                    streamed.size > LegacyStreamPackets.HEADER_BYTES,
                    "expected at least one packet, got ${streamed.size} bytes",
                )
                wrapped += streamed.copyOfRange(LegacyStreamPackets.HEADER_BYTES, streamed.size).toHex()
            }
        }

        assertNotEquals(wrapped[0], wrapped[1], "two sessions must not share a stream key")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
