package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import java.security.SecureRandom

/**
 * Instrument: which mirroring path does a receiver actually want?
 *
 * `legacy-airplay-notes.md` lists this as the top open question. There are two
 * candidate paths and they are not interchangeable:
 *
 *  - **RTSP `SETUP`, stream type 110** on the same port as `/fp-setup`
 *  - **port-7100 `/stream.xml` + `POST /stream`**, a separate HTTP server
 *
 * This asserts nothing. It is a diagnostic, enabled with
 * `-Dairplay.probe=true -Dairplay.host=... -Dairplay.port=...`, and it prints what
 * the receiver answers so the question can be settled from evidence rather than
 * assumption.
 *
 * ### Why it tries several SETUP shapes
 *
 * A type-110 SETUP is not one fixed request. The AirPlay 2 path in
 * [tw.avianjay.airplaydroid.protocol.mirror.MirrorSession] — which is verified
 * against real Apple hardware — sends `shk`/`shiv` and a `timestampInfo` array,
 * while the older reference shape sends `timingPort` and no keys. A receiver that
 * wants the first does not answer the second, and the symptom is a timeout rather
 * than an error. So this tries the shapes from most- to least-likely.
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

        // --- does it serve the port-7100 endpoint at all? (asked first: it is a
        // different port, so nothing here can depend on the RTSP session.)
        probeStreamXml()

        val control = runCatching { SocketAirPlayConnection(endpoint) }
            .getOrElse { error("could not connect to $endpoint: ${it.message}") }

        try {
            val session = runCatching {
                FairPlaySapSession(control, FairPlayResponderImpl, SecureRandom()).handshake()
            }
            println("FairPlay handshake -> " + session.fold(
                onSuccess = { "OK (mode ${it.mode.value})" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
            if (session.isFailure) return

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
            println("OPTIONS -> " + options.fold(
                onSuccess = { "${it.status} ${it.reason}; Public: ${it.header("Public") ?: "(none)"}" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
            if (options.isFailure) {
                println("NOTE: the receiver did not keep the control connection open after the m4.")
                return
            }

            for ((name, body) in setupShapes()) {
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
                            body = body,
                        )
                    )
                }
                println("SETUP [$name] -> " + setup.fold(
                    onSuccess = { "${it.status} ${it.reason}, body ${it.body.size} bytes" },
                    onFailure = { "${it::class.simpleName}: ${it.message}" },
                ))
                setup.getOrNull()?.let { r ->
                    println("  Transport: ${r.header("Transport")}")
                    if (r.body.isNotEmpty()) {
                        println("  body[0:48]: ${r.body.copyOf(minOf(48, r.body.size)).toHex()}")
                    }
                }
                // A receiver that answered is the answer; stop asking.
                if (setup.getOrNull()?.isSuccess == true) return
            }
        } finally {
            runCatching { control.close() }
        }
    }

    /** Tries `GET /stream.xml` on the conventional mirroring port. */
    private fun probeStreamXml() {
        val base = endpoint()
        for (p in listOf(7100, 7000, base.port)) {
            val outcome = runCatching {
                SocketAirPlayConnection(Endpoint(base.host, p), readTimeoutMs = 3_000).use { c ->
                    c.exchange(
                        AirPlayRequest(
                            method = "GET",
                            uri = "/stream.xml",
                            protocol = AirPlayRequest.HTTP_1_1,
                            headers = listOf("User-Agent" to FairPlaySapSession.USER_AGENT),
                            body = null,
                        )
                    )
                }
            }
            println("GET /stream.xml on $p -> " + outcome.fold(
                onSuccess = { "${it.status} ${it.reason}, body ${it.body.size} bytes; " +
                    "first: ${String(it.body.copyOf(minOf(80, it.body.size))) .replace("\n", " ")}" },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            ))
        }
    }

    /**
     * SETUP bodies to try, most-likely first.
     *
     * Shape A mirrors the verified AirPlay 2 request (keys + timestampInfo).
     * Shape B is the nto/legacy reference (timingPort, no keys).
     * Shape C is minimal, to see whether the receiver objects to extra fields.
     */
    private fun setupShapes(): List<Pair<String, ByteArray>> {
        val connectionId = 0x1122334455667788L
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val shapeA = PDict(
            linkedMapOf(
                "streams" to PArray(
                    listOf(
                        PDict(
                            linkedMapOf(
                                "type" to PInt(110),
                                "streamConnectionID" to PInt(connectionId),
                                "latencyMs" to PInt(100),
                                "timestampInfo" to PArray(
                                    listOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc").map {
                                        PDict(linkedMapOf("name" to PString(it)))
                                    }
                                ),
                                "shk" to PData(key),
                                "shiv" to PData(key),
                            )
                        )
                    )
                )
            )
        )

        val shapeB = PDict(
            linkedMapOf(
                "streams" to PArray(
                    listOf(
                        PDict(
                            linkedMapOf(
                                "type" to PInt(110),
                                "streamConnectionID" to PInt(connectionId),
                            )
                        )
                    )
                ),
                "deviceID" to PInt(0x271F67BA55C9L),
                "sessionUUID" to PString("1bd6ceeb-fffd-456c-a09c-996053a7a08c"),
                "timingPort" to PInt(7010),
            )
        )

        val shapeC = PDict(
            linkedMapOf(
                "streams" to PArray(
                    listOf(PDict(linkedMapOf("type" to PInt(110), "streamConnectionID" to PInt(connectionId))))
                )
            )
        )

        return listOf(
            "A: ap2-shape (shk/shiv + timestampInfo)" to BinaryPlist.encode(shapeA),
            "B: nto legacy (timingPort)" to BinaryPlist.encode(shapeB),
            "C: minimal" to BinaryPlist.encode(shapeC),
        )
    }

}

private typealias PDict = PlistValue.PDict
private typealias PArray = PlistValue.PArray
private typealias PInt = PlistValue.PInt
private typealias PData = PlistValue.PData
private typealias PString = PlistValue.PString
