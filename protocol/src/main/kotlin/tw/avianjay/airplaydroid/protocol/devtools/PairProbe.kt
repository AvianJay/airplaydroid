package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing

/**
 * On-device pairing probe, run with app_process so it sits on the receiver's LAN:
 *
 *   app_process -cp /data/local/tmp/probe.dex / \
 *     tw.avianjay.airplaydroid.protocol.devtools.PairProbe <host> <port> <mode> <password>
 *
 * One attempt per run on purpose: repeated failures trigger the receiver's
 * pair-setup backoff.
 */
object PairProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "usage: PairProbe <host> <port> <transient|persistent> <password>" }
        val (host, port, modeArg, password) = args
        val mode = when (modeArg) {
            "transient" -> HomeKitPairing.Mode.TRANSIENT
            "persistent" -> HomeKitPairing.Mode.PERSISTENT
            else -> error("unknown mode $modeArg")
        }

        SocketAirPlayConnection(Endpoint(host, port.toInt())).use { connection ->
            try {
                val k = HomeKitPairing(connection).pairSetup(mode, password)
                println("M4 OK: SRP proof verified, K=${k.size} bytes")
            } catch (e: HomeKitPairing.Failure) {
                println("FAILED: ${e.message}")
            }
        }
    }
}
