package tw.avianjay.airplaydroid.protocol.http

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AirPlayHttpTest {

    @Test
    fun `request encodes an HTTP 1_1 request line with CRLF framing`() {
        val encoded = AirPlayRequest(
            method = "POST",
            uri = "/rate?value=1.000000",
            headers = listOf("User-Agent" to "MediaControl/1.0"),
        ).encode()

        val text = String(encoded, Charsets.UTF_8)
        assertTrue(text.startsWith("POST /rate?value=1.000000 HTTP/1.1\r\n"), text)
        assertTrue(text.contains("User-Agent: MediaControl/1.0\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `Content-Length is added automatically for a body`() {
        val encoded = AirPlayRequest(
            method = "POST",
            uri = "/play",
            body = byteArrayOf(1, 2, 3, 4, 5),
        ).encode()

        assertTrue(String(encoded, Charsets.UTF_8).contains("Content-Length: 5\r\n"))
        assertEquals(5, encoded.size - String(encoded, Charsets.UTF_8).indexOf("\r\n\r\n") - 4)
    }

    @Test
    fun `an explicit Content-Length is not duplicated`() {
        val text = String(
            AirPlayRequest(
                method = "POST",
                uri = "/play",
                headers = listOf("Content-Length" to "5"),
                body = byteArrayOf(1, 2, 3, 4, 5),
            ).encode(),
            Charsets.UTF_8,
        )

        assertEquals(1, Regex("Content-Length", RegexOption.IGNORE_CASE).findAll(text).count())
    }

    @Test
    fun `RTSP request lines are supported for the audio milestones`() {
        val text = String(
            AirPlayRequest(
                method = "OPTIONS",
                uri = "*",
                protocol = AirPlayRequest.RTSP_1_0,
            ).encode(),
            Charsets.UTF_8,
        )

        assertTrue(text.startsWith("OPTIONS * RTSP/1.0\r\n"), text)
    }

    @Test
    fun `equals is symmetric for an empty versus absent body`() {
        val none = AirPlayRequest(method = "POST", uri = "/stop")
        val empty = AirPlayRequest(method = "POST", uri = "/stop", body = ByteArray(0))

        assertEquals(none == empty, empty == none)
        assertTrue(none != empty)
        assertEquals(none, AirPlayRequest(method = "POST", uri = "/stop"))
    }

    @Test
    fun `parses a response with headers and a body`() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n" +
            "Content-Length: 4\r\n" +
            "\r\n" +
            "abcd"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals(200, response.status)
        assertEquals("OK", response.reason)
        assertTrue(response.isSuccess)
        assertEquals("application/x-apple-binary-plist", response.contentType)
        assertEquals("abcd", String(response.body))
    }

    @Test
    fun `header lookup is case-insensitive`() {
        val raw = "HTTP/1.1 200 OK\r\nCoNtEnT-tYpE: text/parameters\r\nContent-Length: 0\r\n\r\n"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals("text/parameters", response.header("content-type"))
    }

    @Test
    fun `a bodyless response is fine`() {
        val raw = "HTTP/1.1 200 OK\r\n\r\n"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals(200, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun `an RTSP status line parses`() {
        val raw = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals("RTSP/1.0", response.protocol)
        assertEquals("1", response.header("CSeq"))
    }

    @Test
    fun `error statuses are reported, not thrown`() {
        val raw = "HTTP/1.1 453 Not Enough Bandwidth\r\nContent-Length: 0\r\n\r\n"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals(453, response.status)
        assertEquals("Not Enough Bandwidth", response.reason)
        assertTrue(!response.isSuccess)
    }

    @Test
    fun `bare LF line endings are tolerated`() {
        val raw = "HTTP/1.1 200 OK\nContent-Length: 2\n\nhi"

        val response = AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

        assertEquals(200, response.status)
        assertEquals("hi", String(response.body))
    }

    @Test
    fun `garbage is rejected as a protocol error`() {
        assertFailsWith<AirPlayProtocolException> {
            AirPlayHttp.readResponse(ByteArrayInputStream("hello there\r\n\r\n".toByteArray()))
        }
        assertFailsWith<AirPlayProtocolException> {
            AirPlayHttp.readResponse(ByteArrayInputStream("HTTP/1.1 abc OK\r\n\r\n".toByteArray()))
        }
    }

    @Test
    fun `a truncated body is an EOF, not a silent short read`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort"

        assertFailsWith<java.io.EOFException> {
            AirPlayHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))
        }
    }
}
