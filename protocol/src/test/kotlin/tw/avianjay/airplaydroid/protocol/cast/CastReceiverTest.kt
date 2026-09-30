package tw.avianjay.airplaydroid.protocol.cast

import tw.avianjay.airplaydroid.protocol.update.Json
import tw.avianjay.airplaydroid.protocol.update.JsonValue
import tw.avianjay.airplaydroid.protocol.update.asArray
import tw.avianjay.airplaydroid.protocol.update.asDouble
import tw.avianjay.airplaydroid.protocol.update.asObject
import tw.avianjay.airplaydroid.protocol.update.asString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receiver protocol, driven the way pychromecast and VLC drive a Chromecast:
 * CONNECT to receiver-0, LAUNCH the Default Media Receiver, CONNECT to its
 * transport, LOAD, then transport commands.
 */
class CastReceiverTest {

    private class RecordingChannel : CastChannel {
        val sent = ArrayList<CastMessage>()
        override fun send(message: CastMessage) { sent += message }

        fun last(namespace: String): JsonValue.Obj =
            Json.parse(sent.last { it.namespace == namespace }.payloadUtf8!!).asObject()!!

        fun take(): List<CastMessage> = sent.toList().also { sent.clear() }
    }

    private class RecordingPlayer : CastPlayer {
        val calls = ArrayList<String>()
        var loaded: CastLoadRequest? = null
        override fun onSessionStarted(appId: String) { calls += "started $appId" }
        override fun onLoad(request: CastLoadRequest) { loaded = request; calls += "load" }
        override fun onPlay() { calls += "play" }
        override fun onPause() { calls += "pause" }
        override fun onSeek(positionSeconds: Double) { calls += "seek $positionSeconds" }
        override fun onStop() { calls += "stop" }
        override fun onSessionEnded() { calls += "ended" }
    }

    private var now = 1_000_000L
    private val player = RecordingPlayer()
    private val receiver = CastReceiver(
        identity = identity,
        player = player,
        clock = { now },
    )
    private val sender = RecordingChannel()

    private fun send(
        namespace: String,
        payload: String,
        destination: String = CastReceiver.RECEIVER_ID,
        channel: RecordingChannel = sender,
        source: String = "sender-0",
    ) = receiver.onMessage(channel, CastMessage(source, destination, namespace, payloadUtf8 = payload))

    private lateinit var transport: String

    @BeforeTest
    fun launch() {
        send(CastReceiver.NS_CONNECTION, """{"type":"CONNECT"}""")
        send(CastReceiver.NS_RECEIVER, """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        val status = sender.last(CastReceiver.NS_RECEIVER)
        val app = status["status"].asObject()!!["applications"].asArray()!!.single().asObject()!!
        transport = app["transportId"].asString()!!
        send(CastReceiver.NS_CONNECTION, """{"type":"CONNECT"}""", destination = transport)
        sender.take()
    }

    private fun load(url: String = "https://example.com/v.mp4", extra: String = "") = send(
        CastReceiver.NS_MEDIA,
        """{"type":"LOAD","requestId":2,"autoplay":true,"currentTime":5$extra,
           "media":{"contentId":"$url","contentType":"video/mp4","streamType":"BUFFERED",
                    "metadata":{"title":"Clip"}}}""",
        destination = transport,
    )

    private fun mediaEntry(channel: RecordingChannel = sender): JsonValue.Obj? =
        channel.last(CastReceiver.NS_MEDIA)["status"].asArray()!!.firstOrNull().asObject()

    @Test
    fun launchStartsASessionAndAnswersWithItsTransport() {
        assertEquals(listOf("started CC1AD845"), player.calls)
        assertTrue(transport.isNotEmpty() && transport != CastReceiver.RECEIVER_ID)
        assertTrue(receiver.sessionActive)
    }

    @Test
    fun answersPingWithPong() {
        send(CastReceiver.NS_HEARTBEAT, """{"type":"PING"}""")
        val pong = sender.sent.single()
        assertEquals("""{"type":"PONG"}""", pong.payloadUtf8)
        // Addressed back to whoever pinged.
        assertEquals("sender-0", pong.destinationId)
        assertEquals(CastReceiver.RECEIVER_ID, pong.sourceId)
    }

    @Test
    fun answersADeviceAuthChallengeWithAWellFormedResponse() {
        val challenge = "0a1608011210000102030405060708090a0b0c0d0e0f1801".fromHex()
        receiver.onMessage(
            sender,
            CastMessage("sender-0", CastReceiver.RECEIVER_ID, CastReceiver.NS_DEVICE_AUTH, payloadBinary = challenge),
        )
        val response = assertNotNull(sender.sent.single().payloadBinary)
        // DeviceAuthMessage.response (field 2) is present; the signature inside it
        // is checked in CastIdentityTest.
        assertEquals(0x12, response[0].toInt())
    }

    @Test
    fun loadHandsAnHttpUrlToThePlayerAndReportsBuffering() {
        load()
        val loaded = assertNotNull(player.loaded)
        assertEquals("https://example.com/v.mp4", loaded.url)
        assertEquals("video/mp4", loaded.contentType)
        assertEquals("Clip", loaded.title)
        assertEquals(5.0, loaded.startSeconds)

        val status = sender.last(CastReceiver.NS_MEDIA)
        // The reply carries the request's id, which is how the sender matches it.
        assertEquals(2.0, status["requestId"].asDouble())
        val entry = assertNotNull(mediaEntry())
        assertEquals("BUFFERING", entry["playerState"].asString())
        assertEquals("https://example.com/v.mp4", entry["media"].asObject()!!["contentId"].asString())
    }

    @Test
    fun contentUrlWinsOverAnOpaqueContentId() {
        send(
            CastReceiver.NS_MEDIA,
            """{"type":"LOAD","requestId":3,"media":{"contentId":"abc123","contentUrl":"http://h/x.m3u8"}}""",
            destination = transport,
        )
        assertEquals("http://h/x.m3u8", player.loaded?.url)
    }

    @Test
    fun aLoadWithNoHttpUrlFailsInsteadOfReachingThePlayer() {
        // An app with its own receiver sends an id only that receiver understands.
        send(
            CastReceiver.NS_MEDIA,
            """{"type":"LOAD","requestId":4,"media":{"contentId":"dQw4w9WgXcQ"}}""",
            destination = transport,
        )
        assertNull(player.loaded)
        assertEquals("LOAD_FAILED", sender.last(CastReceiver.NS_MEDIA)["type"].asString())
    }

    @Test
    fun aLoadOnAFileUrlIsRefused() {
        load(url = "file:///sdcard/secret.mp4")
        assertNull(player.loaded)
        assertEquals("LOAD_FAILED", sender.last(CastReceiver.NS_MEDIA)["type"].asString())
    }

    @Test
    fun mediaCommandsReachThePlayerAndTheStatusFollows() {
        load()
        receiver.updateMedia(CastPlayerState.PLAYING, positionSeconds = 5.0, durationSeconds = 60.0)
        sender.take()

        now += 10_000
        send(CastReceiver.NS_MEDIA, """{"type":"PAUSE","requestId":5,"mediaSessionId":1}""", destination = transport)
        var entry = assertNotNull(mediaEntry())
        assertEquals("PAUSED", entry["playerState"].asString())
        // Extrapolated from the last report: 5 s + 10 s of playing.
        assertEquals(15.0, entry["currentTime"].asDouble())

        send(CastReceiver.NS_MEDIA, """{"type":"SEEK","requestId":6,"currentTime":42.5}""", destination = transport)
        entry = assertNotNull(mediaEntry())
        assertEquals(42.5, entry["currentTime"].asDouble())

        send(CastReceiver.NS_MEDIA, """{"type":"PLAY","requestId":7}""", destination = transport)
        assertEquals("PLAYING", mediaEntry()!!["playerState"].asString())

        send(CastReceiver.NS_MEDIA, """{"type":"STOP","requestId":8}""", destination = transport)
        entry = assertNotNull(mediaEntry())
        assertEquals("IDLE", entry["playerState"].asString())
        assertEquals("CANCELLED", entry["idleReason"].asString())

        assertEquals(listOf("started CC1AD845", "load", "pause", "seek 42.5", "play", "stop"), player.calls)
    }

    @Test
    fun theDurationTheAppLearnsIsReportedToSenders() {
        load()
        receiver.updateMedia(CastPlayerState.PLAYING, positionSeconds = 0.0, durationSeconds = 120.0)
        val media = assertNotNull(mediaEntry())["media"].asObject()!!
        assertEquals(120.0, media["duration"].asDouble())
    }

    @Test
    fun anUnchangedPositionIsNotBroadcast() {
        load()
        receiver.updateMedia(CastPlayerState.PLAYING, positionSeconds = 5.0, durationSeconds = 60.0)
        sender.take()
        now += 1_000
        // Exactly where extrapolation puts it: nothing new for the sender.
        receiver.updateMedia(CastPlayerState.PLAYING, positionSeconds = 6.0)
        assertTrue(sender.sent.isEmpty())
        // Far from it: a status goes out.
        receiver.updateMedia(CastPlayerState.PLAYING, positionSeconds = 30.0)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun mediaCommandsWithoutASessionAreInvalid() {
        send(CastReceiver.NS_RECEIVER, """{"type":"STOP","requestId":9}""")
        assertTrue(player.calls.contains("ended"))
        sender.take()
        send(CastReceiver.NS_MEDIA, """{"type":"PLAY","requestId":10}""", destination = transport)
        assertEquals("INVALID_REQUEST", sender.last(CastReceiver.NS_MEDIA)["type"].asString())
    }

    @Test
    fun stopEndsTheSessionAndClosesItsVirtualConnection() {
        load()
        sender.take()
        send(CastReceiver.NS_RECEIVER, """{"type":"STOP","requestId":11}""")
        assertTrue("ended" in player.calls)
        val close = sender.sent.first { it.namespace == CastReceiver.NS_CONNECTION }
        assertEquals(transport, close.sourceId)
        val apps = sender.last(CastReceiver.NS_RECEIVER)["status"].asObject()!!["applications"].asArray()!!
        assertTrue(apps.isEmpty())
    }

    @Test
    fun launchingAnotherAppReplacesTheSession() {
        send(CastReceiver.NS_RECEIVER, """{"type":"LAUNCH","appId":"233637DE","requestId":12}""")
        assertEquals(listOf("started CC1AD845", "ended", "started 233637DE"), player.calls)
    }

    @Test
    fun relaunchingTheRunningAppJoinsIt() {
        send(CastReceiver.NS_RECEIVER, """{"type":"LAUNCH","appId":"CC1AD845","requestId":13}""")
        assertEquals(listOf("started CC1AD845"), player.calls)
    }

    @Test
    fun otherSendersSeeStatusChanges() {
        val watcher = RecordingChannel()
        send(CastReceiver.NS_CONNECTION, """{"type":"CONNECT"}""", destination = transport, channel = watcher, source = "sender-1")
        load()
        val broadcast = watcher.sent.last { it.namespace == CastReceiver.NS_MEDIA }
        assertEquals(CastReceiver.BROADCAST_ID, broadcast.destinationId)
        assertEquals("BUFFERING", mediaEntry(watcher)!!["playerState"].asString())
    }

    @Test
    fun endingFromTheAppDoesNotCallThePlayerBack() {
        load()
        receiver.endSession()
        assertTrue("ended" !in player.calls)
        assertTrue(!receiver.sessionActive)
    }

    @Test
    fun aClosedChannelIsNoLongerBroadcastTo() {
        load()
        receiver.onChannelClosed(sender)
        sender.take()
        // BUFFERING -> PLAYING would be broadcast to every connected sender.
        receiver.updateMedia(CastPlayerState.PLAYING)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun theLastSenderLeavingEndsAnIdleSession() {
        receiver.onChannelClosed(sender)
        assertTrue("ended" in player.calls)
        assertTrue(!receiver.sessionActive)
    }

    @Test
    fun playingMediaOutlivesItsSender() {
        load()
        receiver.onChannelClosed(sender)
        assertTrue(receiver.sessionActive, "the media plays on with nobody connected")
        // ...until it ends by itself.
        receiver.updateMedia(CastPlayerState.IDLE, idleReason = CastIdleReason.FINISHED)
        assertTrue(!receiver.sessionActive)
        assertTrue("ended" in player.calls)
    }

    @Test
    fun anotherSenderKeepsTheSessionAlive() {
        val other = RecordingChannel()
        send(CastReceiver.NS_CONNECTION, """{"type":"CONNECT"}""", channel = other, source = "sender-1")
        receiver.onChannelClosed(sender)
        assertTrue(receiver.sessionActive)
    }

    @Test
    fun txtRecordCarriesTheKeysSendersFilterOn() {
        val txt = CastDeviceInfo(id = "0123456789abcdef0123456789abcdef", friendlyName = "Phone").txtRecord()
        assertEquals("0123456789abcdef0123456789abcdef", txt["id"])
        assertEquals("Phone", txt["fn"])
        // Bit 0: video out. VLC lists a renderer as video-capable only with it.
        assertEquals(1, txt["ca"]!!.toInt() and 1)
    }

    private companion object {
        /** RSA key generation is the slow part; one identity serves every test. */
        val identity = CastIdentity.generate("test")
    }
}
