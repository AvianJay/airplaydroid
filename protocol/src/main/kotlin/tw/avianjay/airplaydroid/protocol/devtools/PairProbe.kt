package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import java.io.File

/**
 * Pairing probe for a JVM on the receiver's network:
 *
 *   PairProbe <host> <port> transient <password>
 *   PairProbe <host> <port> persistent <password> [credentials-file]
 *   PairProbe <host> <port> verify <credentials-file>
 *
 * Persistent mode runs M1 -> M6 and, given a file, writes the credentials to it
 * as `key=hex` lines. That file holds a private key: keep it out of the repo.
 *
 * One attempt per run on purpose: repeated failures trigger the receiver's
 * pair-setup backoff.
 */
object PairProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 4..5) {
            "usage: PairProbe <host> <port> <transient|persistent> <password> [credentials-file]; " +
                "       PairProbe <host> <port> verify <credentials-file>"
        }
        val (host, port, mode, password) = args
        if (mode == "verify") return verify(host, port.toInt(), File(args[3]))

        SocketAirPlayConnection(Endpoint(host, port.toInt())).use { connection ->
            val pairing = HomeKitPairing(connection)
            try {
                when (mode) {
                    "transient" -> {
                        val k = pairing.pairSetup(HomeKitPairing.Mode.TRANSIENT, password)
                        println("M4 OK: SRP proof verified, K=${k.size} bytes")
                    }
                    "persistent" -> {
                        val c = pairing.pair(password)
                        println("M6 OK: receiver id=${String(c.receiverId)} ltpk=${c.receiverPublicKey.hex()}")
                        args.getOrNull(4)?.let { path ->
                            File(path).writeText(
                                "clientId=${c.clientId}\n" +
                                    "clientSeed=${c.clientSeed.hex()}\n" +
                                    "receiverId=${c.receiverId.hex()}\n" +
                                    "receiverPublicKey=${c.receiverPublicKey.hex()}\n"
                            )
                            println("credentials written to $path")
                        }
                    }
                    else -> error("unknown mode $mode")
                }
            } catch (e: HomeKitPairing.Failure) {
                println("FAILED: ${e.message}")
            }
        }
    }

    /** Reads a credentials file written by persistent mode. */
    fun readCredentials(file: File): HomeKitPairing.Credentials {
        val fields = file.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return HomeKitPairing.Credentials(
            clientId = fields.getValue("clientId"),
            clientSeed = fields.getValue("clientSeed").unhex(),
            receiverId = fields.getValue("receiverId").unhex(),
            receiverPublicKey = fields.getValue("receiverPublicKey").unhex(),
        )
    }

    private fun verify(host: String, port: Int, file: File) {
        val credentials = readCredentials(file)

        SocketAirPlayConnection(Endpoint(host, port)).use { connection ->
            try {
                val session = HomeKitPairing(connection, clientId = credentials.clientId).verify(credentials)
                println("verify OK: shared secret ${session.sharedSecret.size} bytes")
                connection.enableEncryption(session.controlWriteKey, session.controlReadKey)

                val info = connection.exchange(
                    AirPlayRequest(
                        method = "GET",
                        uri = "/info",
                        protocol = AirPlayRequest.RTSP_1_0,
                        headers = listOf("CSeq" to "2", "User-Agent" to "AirPlay/960.13.1"),
                        body = null,
                    )
                )
                println("encrypted GET /info -> ${info.status} ${info.reason}, ${info.body.size} bytes")
            } catch (e: HomeKitPairing.Failure) {
                println("FAILED: ${e.message}")
            }
        }
    }

    private fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
