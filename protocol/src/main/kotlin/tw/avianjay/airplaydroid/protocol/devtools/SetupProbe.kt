package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.HapCrypto
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PArray
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PBool
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PData
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PDict
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PReal
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PString
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID

/**
 * The FairPlay question, asked directly:
 *
 *   SetupProbe <host> <port> <credentials-file> [password]
 *
 * pair-verify, encrypted control channel, a control-only SETUP, then a SETUP
 * for a type-110 screen stream -- and never /fp-setup. What the receiver answers
 * to the second SETUP is the result. Nothing is streamed.
 *
 * A receiver with `flags` bit 7 also demands HTTP Digest on SETUP, on top of
 * pairing: an Apple TV 4K on tvOS 26.6 answers the first SETUP 401 inside the
 * already-encrypted channel. [password] answers that challenge.
 */
object SetupProbe {

    private const val SOURCE_VERSION = "980.71.1"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 3..4) { "usage: SetupProbe <host> <port> <credentials-file> [password]" }
        val password = args.getOrNull(3)
        val host = args[0]
        val port = args[1].toInt()
        val credentials = PairProbe.readCredentials(File(args[2]))

        SocketAirPlayConnection(Endpoint(host, port)).use { connection ->
            val session = HomeKitPairing(connection, clientId = credentials.clientId).verify(credentials)
            connection.enableEncryption(session.controlWriteKey, session.controlReadKey)
            println("pair-verify OK, control channel encrypted")

            val deviceId = "02:AD:D5:00:00:01"
            val sessionUuid = UUID.randomUUID().toString().uppercase()
            val localAddress = localAddressTowards(host)
            var cseq = 10

            var challenge: DigestAuth.Challenge? = null

            fun setup(streamConnectionId: Long, body: PDict): AirPlayResponse {
                val uri = "rtsp://$host:$port/$streamConnectionId"
                fun send(): AirPlayResponse = connection.exchange(
                    AirPlayRequest(
                        method = "SETUP",
                        uri = uri,
                        protocol = AirPlayRequest.RTSP_1_0,
                        headers = buildList {
                            add("CSeq" to (cseq++).toString())
                            add("User-Agent" to "AirPlay/$SOURCE_VERSION")
                            add("Content-Type" to "application/x-apple-binary-plist")
                            val c = challenge
                            if (c != null && password != null) {
                                add("Authorization" to DigestAuth.authorization(c, "SETUP", uri, password))
                            }
                        },
                        body = BinaryPlist.encode(body),
                    )
                )
                var response = send()
                if (response.status == 401 && password != null) {
                    challenge = DigestAuth.Challenge.parse(response.header("WWW-Authenticate"))
                    println("  -> 401, retrying with Digest (${response.header("WWW-Authenticate")})")
                    if (challenge != null) response = send()
                }
                println("  -> ${response.status} ${response.reason}")
                if (response.body.isNotEmpty()) {
                    val decoded = runCatching { BinaryPlist.decode(response.body) }.getOrNull()
                    if (decoded != null) dump(decoded, "     ") else println("     body: ${String(response.body).take(300)}")
                }
                return response
            }

            // PTP rather than NTP: with NTP the receiver probes our timing port
            // before answering, and nothing is listening here.
            val timingPeer = PDict(
                mapOf(
                    "ID" to PString(UUID.randomUUID().toString().uppercase()),
                    "SupportsClockPortMatchingOverride" to PBool(true),
                    "DeviceType" to PInt(0),
                    "Addresses" to PArray(listOf(PString(localAddress))),
                )
            )
            val controlId = System.nanoTime() and Long.MAX_VALUE
            println("SETUP #1 (control, PTP peer $localAddress)")
            val first = setup(
                controlId,
                PDict(
                    mapOf(
                        "deviceID" to PString(deviceId),
                        "macAddress" to PString(deviceId),
                        "sessionUUID" to PString(sessionUuid),
                        "sourceVersion" to PString(SOURCE_VERSION),
                        "isScreenMirroringSession" to PBool(true),
                        "timingProtocol" to PString("PTP"),
                        "timingPeerInfo" to timingPeer,
                        "timingPeerList" to PArray(listOf(timingPeer)),
                        "osBuildVersion" to PString("13F69"),
                        "model" to PString("AirPlayDroid"),
                        "name" to PString("AirPlayDroid"),
                        "updateSessionRequest" to PBool(false),
                        "combinedGetInfoWithControlSetup" to PBool(true),
                    )
                ),
            )
            if (!first.isSuccess) return

            // With HAP pairing the video key is derived from the pair-verify
            // secret, not carried in shk; shk/shiv are sent the way a modern
            // sender does, as 16 bytes of the control keys.
            val videoId = (System.nanoTime() + 1) and Long.MAX_VALUE
            val videoKey = HapCrypto.hkdf("DataStream-Salt$videoId", "DataStream-Output-Encryption-Key", session.sharedSecret)
            println("SETUP #2 (type 110 screen stream, id=$videoId, no /fp-setup ever sent)")
            println("  derived DataStream key: ${videoKey.size} bytes")
            setup(
                videoId,
                PDict(
                    mapOf(
                        "streams" to PArray(
                            listOf(
                                PDict(
                                    mapOf(
                                        "type" to PInt(110),
                                        "streamConnectionID" to PInt(videoId),
                                        "latencyMs" to PInt(100),
                                        "timestampInfo" to PArray(
                                            listOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc").map {
                                                PDict(mapOf("name" to PString(it)))
                                            }
                                        ),
                                        "shk" to PData(session.controlWriteKey.copyOf(16)),
                                        "shiv" to PData(session.controlReadKey.copyOf(16)),
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        }
    }

    /** The local address the OS would use to reach [host], for the PTP peer entry. */
    private fun localAddressTowards(host: String): String =
        DatagramSocket().use {
            it.connect(InetSocketAddress(host, 9))
            it.localAddress.hostAddress
        }

    private fun dump(value: PlistValue, indent: String) {
        when (value) {
            is PDict -> value.entries.forEach { (k, v) ->
                if (v is PDict || v is PArray) {
                    println("$indent$k:")
                    dump(v, "$indent  ")
                } else {
                    println("$indent$k = ${scalar(v)}")
                }
            }
            is PArray -> value.values.forEachIndexed { i, v ->
                if (v is PDict || v is PArray) {
                    println("$indent[$i]")
                    dump(v, "$indent  ")
                } else {
                    println("$indent[$i] ${scalar(v)}")
                }
            }
            else -> println("$indent${scalar(value)}")
        }
    }

    private fun scalar(v: PlistValue): String = when (v) {
        is PString -> "\"${v.value}\""
        is PInt -> v.value.toString()
        is PReal -> v.value.toString()
        is PBool -> v.value.toString()
        is PData -> "<${v.value.size} bytes>"
        else -> v.toString()
    }
}
