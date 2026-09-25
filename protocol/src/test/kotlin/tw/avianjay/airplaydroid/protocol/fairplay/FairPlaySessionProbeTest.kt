package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.security.SecureRandom

/**
 * Diagnostic: after a successful FairPlay handshake, which mirroring path does
 * the receiver actually want?
 *
 * `legacy-airplay-notes.md` lists this as the top open question. There are two
 * candidate paths and they are not interchangeable:
 *
 *  - **RTSP `SETUP`, stream type 110** on the same port as `/fp-setup`
 *  - **port-7100 `/stream.xml` + `POST /stream`**, a separate HTTP server
 *
 * This probe does the handshake, then asks for both, and prints what comes back.
 * It asserts nothing: it is an instrument, enabled with
 * `-Dairplay.probe=true -Dairplay.host=... -Dairplay.port=...`.
 */
class FairPlaySessionProbeTest {

    private val host: String? = System.getProperty("airplay.host")
    private val port: Int = System.getProperty("airplay.port")?.toIntOrNull() ?: 0

    private fun endpoint(): Endpoint {
        val h = requireNotNull(host) { "set -Dairplay.host" }
        require(port > 0) { "set -Dairplay.port" }
        return Endpoint(h, port)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    @EnabledIfSystemProperty(named = "airplay.probe", matches = "true")
    fun `probe which mirroring path the receiver wants`() {
        val endpoint = endpoint()

        // Every step is wrapped: a receiver that closes the connection after the
        // handshake is itself the finding, and must not abort the probe.
        val control = runCatching { SocketAirPlayConnection(endpoint) }
            .getOrElse { error("could not connect to $endpoint: ${it.message}") }

        try {
            // 1. FairPlay first: a mirroring SETUP usually wants the session key.
            val session = runCatching {
                FairPlaySapSession(control, FairPlayResponderImpl, SecureRandom()).handshake()
            }
            println("FairPlay handshake -> " + session.fold(
                onSuccess = { "OK (mode ${it.mode.value})" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
            if (session.isFailure) return

            // Does the receiver keep the control connection open afterwards?
            // Many receivers do not, and that changes what can be asked next.
            val options = runCatching {
                control.exchange(
                    AirPlayRequest(
                        method = "OPTIONS",
                        uri = "*",
                        protocol = AirPlayRequest.RTSP_1_0,
                        headers = listOf("CSeq" to "2", "User-Agent" to FairPlaySapSession.USER_AGENT),
                        body = null,
                    )
                )
            }
            println("OPTIONS after handshake -> " + options.fold(
                onSuccess = { "${it.status} ${it.reason}; Public: ${it.header("Public") ?: "(none)"}" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
            if (options.isFailure) {
                println(
                    "NOTE: the receiver did not keep the control connection open after the m4. " +
                        "A separate connection is needed for any further request."
                )
                return
            }

            // 3. Does it want the RTSP SETUP type-110 path?
            val setup = runCatching {
                control.exchange(
                    AirPlayRequest(
                        method = "SETUP",
                        uri = "rtsp://${endpoint.host}/1",
                        protocol = AirPlayRequest.RTSP_1_0,
                        headers = listOf(
                            "CSeq" to "3",
                            "User-Agent" to FairPlaySapSession.USER_AGENT,
                            "Content-Type" to "application/x-apple-binary-plist",
                            "X-Apple-Device-ID" to "0x271F67BA55C9",
                        ),
                        body = streamSetupBody(),
                    )
                )
            }
            println("SETUP type 110 -> " + setup.fold(
                onSuccess = { "${it.status} ${it.reason}, body ${it.body.size} bytes" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
            setup.getOrNull()?.let { r ->
                println("  Transport: ${r.header("Transport")}")
                if (r.body.isNotEmpty()) {
                    println("  body[0:64]: ${r.body.copyOf(minOf(64, r.body.size)).toHex()}")
                }
            }
        } finally {
            runCatching { control.close() }
        }
    }

    /** The minimal type-110 mirroring SETUP body: one stream, one timing entry. */
    private fun streamSetupBody(): ByteArray =
        tw.avianjay.airplaydroid.protocol.plist.BinaryPlist.encode(
            tw.avianjay.airplaydroid.protocol.plist.PlistValue.PDict(
                linkedMapOf(
                    "streams" to tw.avianjay.airplaydroid.protocol.plist.PlistValue.PArray(
                        listOf(
                            tw.avianjay.airplaydroid.protocol.plist.PlistValue.PDict(
                                linkedMapOf(
                                    "type" to tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt(110),
                                    "streamConnectionID" to
                                        tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt(0x1122334455667788L),
                                )
                            )
                        )
                    ),
                    "deviceID" to tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt(0x271F67BA55C9L),
                    "sessionUUID" to tw.avianjay.airplaydroid.protocol.plist.PlistValue.PString(
                        "1bd6ceeb-fffd-456c-a09c-996053a7a08c"
                    ),
                    "timingPort" to tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt(7010),
                )
            )
        )
}
