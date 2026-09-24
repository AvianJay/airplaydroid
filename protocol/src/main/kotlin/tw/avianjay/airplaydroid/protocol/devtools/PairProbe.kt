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
 *   PairProbe <host> <port> pin <pin-file> <credentials-file>
 *
 * `pin` asks the receiver to show a PIN, waits (up to 3 minutes) for
 * <pin-file> to contain it, then pairs persistently on the same connection.
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
        if (mode == "pin") return pinPair(host, port.toInt(), File(args[3]), File(args[4]))

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
                            File(path).writeText(c.encode())
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

    private fun pinPair(host: String, port: Int, pinFile: File, out: File) {
        pinFile.delete()
        SocketAirPlayConnection(Endpoint(host, port), readTimeoutMs = 200_000).use { connection ->
            val pairing = HomeKitPairing(connection)
            try {
                pairing.startPin()
                println("PIN requested: the receiver should now show a code. Waiting for ${pinFile.path}")
                val deadline = System.currentTimeMillis() + 180_000
                var pin: String? = null
                while (pin == null && System.currentTimeMillis() < deadline) {
                    pin = pinFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                    if (pin == null) Thread.sleep(500)
                }
                if (pin == null) return println("FAILED: no PIN within 3 minutes")
                val c = pairing.pair(pin)
                out.writeText(c.encode())
                println("M6 OK with PIN: receiver id=${String(c.receiverId)}; credentials written to ${out.path}")
            } catch (e: HomeKitPairing.Failure) {
                println("FAILED: ${e.message}")
            }
        }
    }

    /** Reads a credentials file written by persistent mode. */
    fun readCredentials(file: File): HomeKitPairing.Credentials = HomeKitPairing.Credentials.decode(file.readText())

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

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
