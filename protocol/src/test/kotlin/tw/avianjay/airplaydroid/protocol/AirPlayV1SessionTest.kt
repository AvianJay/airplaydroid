package tw.avianjay.airplaydroid.protocol

import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ok(body: ByteArray = ByteArray(0)) =
    AirPlayResponse("HTTP/1.1", 200, "OK", emptyList(), body)

private class FakeConnection(vararg responses: AirPlayResponse) : AirPlayConnection {
    private val queued = ArrayDeque(responses.toList())
    val requests = mutableListOf<AirPlayRequest>()
    var closed = false

    override fun exchange(request: AirPlayRequest): AirPlayResponse {
        requests += request
        return if (queued.isEmpty()) ok() else queued.removeFirst()
    }

    override fun close() {
        closed = true
    }
}

class AirPlayV1SessionTest {

    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `play posts to slash play over HTTP 1_1, never RTSP`() {
        val connection = FakeConnection()
        val session = AirPlayV1Session(connection, sessionId = "SESSION-1")

        session.play("http://example.com/video.mp4")

        val request = connection.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/play", request.uri)
        assertEquals(AirPlayRequest.HTTP_1_1, request.protocol)
    }

    @Test
    fun `play sends the session id BOTH as a header and as a body uuid`() {
        // Receivers disagree about which they require, so we send the union.
        // Omitting either gets a 400 from UxPlay.
        val connection = FakeConnection()
        val session = AirPlayV1Session(connection, sessionId = "SESSION-1")

        session.play("http://example.com/video.mp4")

        val request = connection.requests.single()
        assertEquals(
            "SESSION-1",
            request.headers.first { it.first == "X-Apple-Session-ID" }.second,
        )

        val body = BinaryPlist.decode(request.body!!) as PlistValue.PDict
        assertEquals("SESSION-1", body.string("uuid"))
        assertEquals("http://example.com/video.mp4", body.string("Content-Location"))
    }

    @Test
    fun `play sends the start position in seconds, the spelling receivers read`() {
        // UxPlay reads only Start-Position-Seconds and warns when it is absent;
        // the legacy Start-Position is a FRACTION, so it is only safe at zero.
        val fromStart = FakeConnection()
        AirPlayV1Session(fromStart, "S").play("http://example.com/a.mp4")
        val startBody = BinaryPlist.decode(fromStart.requests.single().body!!) as PlistValue.PDict
        assertEquals(0.0, startBody.number("Start-Position-Seconds"))
        assertEquals(0.0, startBody.number("Start-Position"))

        val resumed = FakeConnection()
        AirPlayV1Session(resumed, "S").play("http://example.com/a.mp4", startPositionSeconds = 90.0)
        val resumeBody = BinaryPlist.decode(resumed.requests.single().body!!) as PlistValue.PDict
        assertEquals(90.0, resumeBody.number("Start-Position-Seconds"))
        // The fraction spelling must NOT be sent as if it were seconds.
        assertNull(resumeBody.number("Start-Position"))
    }

    @Test
    fun `playback-info accepts the XML plist real receivers send`() {
        // Regression: this endpoint answers text/x-apple-plist+xml, not bplist.
        // Decoding only binary here yielded EMPTY forever and made pause impossible.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>duration</key><real>300.0</real>
              <key>position</key><real>42.0</real>
              <key>rate</key><real>1</real>
              <key>readyToPlay</key><integer>1</integer>
            </dict>
            </plist>
        """.trimIndent().toByteArray()

        val info = AirPlayV1Session(FakeConnection(ok(xml)), "S").playbackInfo()

        assertEquals(300.0, info.durationSeconds)
        assertEquals(42.0, info.positionSeconds)
        assertTrue(info.isPlaying)
        assertTrue(info.readyToPlay)
    }

    @Test
    fun `play declares the binary plist content type`() {
        val connection = FakeConnection()
        AirPlayV1Session(connection, "S").play("http://example.com/a.mp4")

        val request = connection.requests.single()
        assertEquals(
            AirPlayV1Session.CONTENT_TYPE_BPLIST,
            request.headers.first { it.first == "Content-Type" }.second,
        )
    }

    @Test
    fun `rate formats with a dot even on a comma-decimal locale`() {
        // A default-locale format would emit "1,000000" in de/tr/fr and the
        // receiver would reject it. This is the regression guard.
        Locale.setDefault(Locale.GERMANY)
        val connection = FakeConnection()

        AirPlayV1Session(connection, "S").rate(1.0)

        assertEquals("/rate?value=1.000000", connection.requests.single().uri)
    }

    @Test
    fun `scrub formats the position the same way`() {
        Locale.setDefault(Locale.GERMANY)
        val connection = FakeConnection()

        AirPlayV1Session(connection, "S").scrub(12.5)

        assertEquals("/scrub?position=12.500000", connection.requests.single().uri)
    }

    @Test
    fun `pause is rate zero`() {
        val connection = FakeConnection()

        AirPlayV1Session(connection, "S").rate(0.0)

        assertEquals("/rate?value=0.000000", connection.requests.single().uri)
    }

    @Test
    fun `playback-info parses the receiver plist`() {
        val body = BinaryPlist.encode(
            PlistValue.dict(
                "duration" to PlistValue.PReal(120.0),
                "position" to PlistValue.PReal(30.0),
                "rate" to PlistValue.PReal(1.0),
                "readyToPlay" to PlistValue.PBool(true),
            )
        )
        val session = AirPlayV1Session(FakeConnection(ok(body)), "S")

        val info = session.playbackInfo()

        assertEquals(120.0, info.durationSeconds)
        assertEquals(30.0, info.positionSeconds)
        assertTrue(info.isPlaying)
        assertTrue(info.readyToPlay)
    }

    @Test
    fun `an empty playback-info body means nothing is loaded yet`() {
        val session = AirPlayV1Session(FakeConnection(ok()), "S")

        assertEquals(PlaybackInfo.EMPTY, session.playbackInfo())
    }

    @Test
    fun `a non-plist playback-info body degrades instead of throwing`() {
        val session = AirPlayV1Session(FakeConnection(ok("<html>nope</html>".toByteArray())), "S")

        assertEquals(PlaybackInfo.EMPTY, session.playbackInfo())
    }

    @Test
    fun `a refused request surfaces the status`() {
        val connection = FakeConnection(
            AirPlayResponse("HTTP/1.1", 403, "Forbidden", emptyList(), ByteArray(0))
        )
        val session = AirPlayV1Session(connection, "S")

        val error = assertFailsWith<AirPlayRequestFailed> {
            session.play("http://example.com/a.mp4")
        }

        assertEquals(403, error.status)
        assertTrue(error.message!!.contains("/play"))
    }

    @Test
    fun `stop posts to slash stop and close releases the connection`() {
        val connection = FakeConnection()
        val session = AirPlayV1Session(connection, "S")

        session.stop()
        session.close()

        assertEquals("/stop", connection.requests.single().uri)
        assertTrue(connection.closed)
    }

    @Test
    fun `every request carries the MediaControl user agent`() {
        val connection = FakeConnection(ok(), ok(), ok())
        val session = AirPlayV1Session(connection, "S")

        session.play("http://example.com/a.mp4")
        session.rate(1.0)
        session.stop()

        assertEquals(3, connection.requests.size)
        assertTrue(
            connection.requests.all { req ->
                req.headers.any { it.first == "User-Agent" && it.second == "MediaControl/1.0" }
            }
        )
    }
}

/** The 401 -> Digest -> retry path, which is what a password-protected receiver forces. */
class AirPlayV1SessionAuthTest {

    private fun challenge401() = AirPlayResponse(
        "HTTP/1.1", 401, "Unauthorized",
        listOf("WWW-Authenticate" to """Digest realm="airplay", nonce="NONCE-1""""),
        ByteArray(0),
    )

    @Test
    fun `a 401 is answered with a Digest Authorization and retried`() {
        val connection = FakeConnection(challenge401(), ok())
        val session = AirPlayV1Session(connection, "S", password = "testpw")

        session.play("http://example.com/a.mp4")

        assertEquals(2, connection.requests.size)
        // First attempt carries no credentials at all.
        assertTrue(connection.requests[0].headers.none { it.first == "Authorization" })
        // The retry does, derived from THAT response's nonce.
        val auth = connection.requests[1].headers.first { it.first == "Authorization" }.second
        assertTrue(auth.startsWith("Digest "), auth)
        assertTrue(auth.contains("""nonce="NONCE-1""""), auth)
        // And it is otherwise the same request.
        assertEquals("/play", connection.requests[1].uri)
        assertEquals(connection.requests[0].body?.size, connection.requests[1].body?.size)
    }

    @Test
    fun `without a password the 401 surfaces instead of looping`() {
        val connection = FakeConnection(challenge401())
        val session = AirPlayV1Session(connection, "S", password = null)

        val error = assertFailsWith<AirPlayRequestFailed> {
            session.play("http://example.com/a.mp4")
        }

        assertEquals(401, error.status)
        assertEquals(1, connection.requests.size)
    }

    @Test
    fun `an unauthenticated receiver is never sent credentials`() {
        val connection = FakeConnection(ok())
        AirPlayV1Session(connection, "S", password = "testpw").play("http://example.com/a.mp4")

        assertEquals(1, connection.requests.size)
        assertTrue(connection.requests[0].headers.none { it.first == "Authorization" })
    }
}
