package tw.avianjay.airplaydroid.protocol

import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.Plists
import java.io.Closeable
import java.util.Locale
import java.util.UUID

/** What `GET /playback-info` reports. All fields are absent until playback starts. */
data class PlaybackInfo(
    val durationSeconds: Double?,
    val positionSeconds: Double?,
    val rate: Double?,
    val readyToPlay: Boolean,
) {
    val isPlaying: Boolean get() = (rate ?: 0.0) > 0.0

    companion object {
        val EMPTY = PlaybackInfo(null, null, null, readyToPlay = false)

        fun fromPlist(value: PlistValue): PlaybackInfo {
            val dict = value as? PlistValue.PDict ?: return EMPTY
            return PlaybackInfo(
                durationSeconds = dict.number("duration"),
                positionSeconds = dict.number("position"),
                rate = dict.number("rate"),
                readyToPlay = dict.bool("readyToPlay") ?: false,
            )
        }
    }
}

/** A receiver refused a request. Carries the status so callers can react to 403/453. */
class AirPlayRequestFailed(
    val request: String,
    val status: Int,
    val reason: String,
) : Exception("$request failed: $status $reason")

/**
 * AirPlay 1 video/photo URL handoff.
 *
 * The sender never carries pixels: it hands the receiver a URL and then drives
 * transport controls. This is the only AirPlay feature that needs no crypto at
 * all beyond an optional HTTP Digest password.
 *
 * It is NOT limited to AirPlay 1 receivers: an Apple TV 4K on tvOS 26.6, with
 * feature bits 38 and 48 set, accepts a bare POST /play once it is given a
 * Digest Authorization header. HomeKit pairing is a separate mechanism that
 * this path does not need.
 *
 * Three details that are easy to get wrong and fail obscurely:
 *
 *  - A receiver with `flags` bit 7 answers 401 with a Digest challenge and a
 *    FRESH nonce every time, so the retry must use that response's nonce.
 *
 *  - The request line is **HTTP/1.1**, not RTSP/1.0. Receivers route /play only
 *    under the HTTP token.
 *  - The session id must be sent BOTH as the `X-Apple-Session-ID` header and as
 *    a `uuid` key in the body plist. Receivers disagree about which they require
 *    (UxPlay rejects a missing body `uuid` with 400 and a missing header with
 *    400), so we send the union.
 *
 * Blocking; call from an IO dispatcher. Not safe for concurrent use -- the
 * underlying connection serialises, but interleaved transport commands would
 * still race logically.
 */
class AirPlayV1Session(
    private val connection: AirPlayConnection,
    val sessionId: String = UUID.randomUUID().toString().uppercase(),
    /** Set when the receiver advertises `flags` bit 7. See [DigestAuth]. */
    private val password: String? = null,
    /**
     * The reverse-HTTP event channel, opened on its own socket BEFORE any
     * control request and carrying this same [sessionId]. Modern receivers
     * register the playback session on it; without one they accept /play with
     * 200 and then report no session at all.
     */
    private val eventChannel: Closeable? = null,
) : Closeable {

    /**
     * Starts playback of [url] at [startPositionSeconds].
     *
     * Two spellings of the start position go out together, because receivers
     * read different ones: `Start-Position-Seconds` is in SECONDS (UxPlay reads
     * only this and logs a warning when it is absent), while the older
     * `Start-Position` is a FRACTION of the duration. They are only
     * interchangeable at zero, so the fraction is sent only when starting from
     * the beginning -- seeking elsewhere is done with [scrub] once playback has
     * started and the duration is known.
     */
    fun play(url: String, startPositionSeconds: Double = 0.0) {
        val entries = linkedMapOf<String, PlistValue>(
            "Content-Location" to PlistValue.PString(url),
            "Start-Position-Seconds" to PlistValue.PReal(startPositionSeconds),
            "uuid" to PlistValue.PString(sessionId),
        )
        if (startPositionSeconds == 0.0) {
            entries["Start-Position"] = PlistValue.PReal(0.0)
        }
        val body = BinaryPlist.encode(PlistValue.PDict(entries))

        send("POST", "/play", listOf("Content-Type" to CONTENT_TYPE_BPLIST), body)
            .requireSuccess("POST /play")
    }

    /**
     * Sets the playback rate: 1.0 plays, 0.0 pauses.
     *
     * The value is formatted with [Locale.ROOT] on purpose -- on a device set to
     * a comma-decimal locale, a default-locale format would emit `1,000000` and
     * the receiver would reject it.
     */
    fun rate(value: Double) {
        send("POST", "/rate?value=" + formatDecimal(value)).requireSuccess("POST /rate")
    }

    /** Seeks to an absolute position in seconds. */
    fun scrub(positionSeconds: Double) {
        send("POST", "/scrub?position=" + formatDecimal(positionSeconds)).requireSuccess("POST /scrub")
    }

    fun playbackInfo(): PlaybackInfo {
        val response = send("GET", "/playback-info")
        response.requireSuccess("GET /playback-info")

        if (response.body.isEmpty()) return PlaybackInfo.EMPTY
        // NOTE: this endpoint answers with an XML plist
        // (Content-Type: text/x-apple-plist+xml), NOT the binary plist that
        // /play takes. Decoding only bplist here silently yields EMPTY forever,
        // which makes position, duration and pause all inoperable.
        return runCatching { PlaybackInfo.fromPlist(Plists.decode(response.body)) }
            .getOrDefault(PlaybackInfo.EMPTY)
    }

    fun stop() {
        send("POST", "/stop").requireSuccess("POST /stop")
    }

    /** `GET /server-info` -- a cheap reachability and capability probe. */
    fun serverInfo(): PlistValue? {
        val response = send("GET", "/server-info")
        if (!response.isSuccess || response.body.isEmpty()) return null
        return runCatching { Plists.decode(response.body) }.getOrNull()
    }

    override fun close() {
        runCatching { eventChannel?.close() }
        connection.close()
    }

    /**
     * Sends a request, transparently answering a `401` Digest challenge when a
     * password is configured. The receiver issues a fresh nonce with each
     * challenge, so the retry must use the nonce from THIS response.
     */
    private fun send(
        method: String,
        uri: String,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): AirPlayResponse {
        fun request(auth: String?) = AirPlayRequest(
            method = method,
            uri = uri,
            protocol = AirPlayRequest.HTTP_1_1,
            headers = commonHeaders() + extraHeaders +
                (auth?.let { listOf("Authorization" to it) } ?: emptyList()),
            body = body,
        )

        val first = connection.exchange(request(null))
        if (first.status != 401) return first

        val secret = password ?: return first
        val challenge = DigestAuth.Challenge.parse(first.header("WWW-Authenticate")) ?: return first

        return connection.exchange(
            request(DigestAuth.authorization(challenge, method, uri, secret))
        )
    }

    private fun commonHeaders(): List<Pair<String, String>> = listOf(
        "User-Agent" to USER_AGENT,
        "X-Apple-Session-ID" to sessionId,
    )

    private fun AirPlayResponse.requireSuccess(what: String) {
        if (!isSuccess) throw AirPlayRequestFailed(what, status, reason)
    }

    companion object {
        const val USER_AGENT = "MediaControl/1.0"
        const val CONTENT_TYPE_BPLIST = "application/x-apple-binary-plist"

        internal fun formatDecimal(value: Double): String =
            String.format(Locale.ROOT, "%.6f", value)
    }
}
