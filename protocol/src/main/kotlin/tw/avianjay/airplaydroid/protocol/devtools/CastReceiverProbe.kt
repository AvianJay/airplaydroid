package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.cast.CastDeviceInfo
import tw.avianjay.airplaydroid.protocol.cast.CastIdentitySource
import tw.avianjay.airplaydroid.protocol.cast.CastLoadRequest
import tw.avianjay.airplaydroid.protocol.cast.CastPlayer
import tw.avianjay.airplaydroid.protocol.cast.CastPlayerState
import tw.avianjay.airplaydroid.protocol.cast.CastReceiver
import tw.avianjay.airplaydroid.protocol.cast.CastServer
import tw.avianjay.airplaydroid.protocol.cast.LocalAddresses
import kotlin.concurrent.thread

/**
 * The Chromecast receiver from a desktop JVM, for checking it against a real
 * sender without a phone.
 *
 *   CastReceiverProbe [port] [--local-only]
 *
 * Every command a sender sends is printed. A LOAD is "played" by a fake player
 * that reports BUFFERING, then PLAYING a second later with a 60 s duration, so
 * a sender sees the same status sequence the app produces.
 *
 * It does not advertise over mDNS; point the sender at the host directly, e.g.
 * `pychromecast.get_chromecast_from_host(("127.0.0.1", 8009, uuid, "Probe", "Chromecast"))`.
 */
object CastReceiverProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull { !it.startsWith("--") }?.toIntOrNull() ?: CastServer.DEFAULT_PORT
        val localOnly = "--local-only" in args

        val device = CastDeviceInfo(id = CastDeviceInfo.newId(), friendlyName = "AirPlayDroid probe")
        lateinit var receiver: CastReceiver
        val player = object : CastPlayer {
            override fun onSessionStarted(appId: String) = println("PLAYER session started: $appId")
            override fun onLoad(request: CastLoadRequest) {
                println("PLAYER load: $request")
                thread(isDaemon = true) {
                    Thread.sleep(1_000)
                    receiver.updateMedia(CastPlayerState.PLAYING, request.startSeconds, 60.0)
                }
            }
            override fun onPlay() {
                println("PLAYER play")
                receiver.updateMedia(CastPlayerState.PLAYING)
            }
            override fun onPause() {
                println("PLAYER pause")
                receiver.updateMedia(CastPlayerState.PAUSED)
            }
            override fun onSeek(positionSeconds: Double) = println("PLAYER seek $positionSeconds")
            override fun onStop() = println("PLAYER stop")
            override fun onSessionEnded() = println("PLAYER session ended")
            override fun onVolume(level: Double, muted: Boolean) = println("PLAYER volume $level muted=$muted")
        }
        // Each connection signs device auth with the identity its handshake used.
        val identities = CastIdentitySource(device.friendlyName)
        receiver = CastReceiver(identities.prepare(), player, log = { println("RECV $it") })
        val server = CastServer(
            receiver = receiver,
            identities = identities::current,
            accept = { address -> !localOnly || LocalAddresses.isOwnAddress(address) },
            log = { println("SERVER $it") },
        )
        val bound = server.start(port)
        println("listening on $bound as ${device.id}" + if (localOnly) " (local senders only)" else "")
        Thread.currentThread().join()
    }
}
