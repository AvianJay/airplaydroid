package tw.avianjay.airplaydroid.protocol.cast

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The TLS listener in front of a [CastReceiver]: accepts senders, frames their
 * messages and hands each one to the receiver.
 *
 * [accept] is asked about every connection's remote address *before* the TLS
 * handshake, so a sender the app does not allow -- anything but this phone,
 * when the receiver is limited to local senders -- is dropped without ever
 * reaching the protocol. See [LocalAddresses.isOwnAddress].
 *
 * Each connection is handed the identity [identities] gives at that moment, and
 * keeps it: the source rotates the short-lived certificate senders insist on,
 * and a session must not change certificates halfway.
 *
 * One thread per sender, blocking I/O. A handful of senders is the most a
 * receiver ever sees, and the protocol is request/response with a heartbeat
 * every few seconds, so threads are simpler than anything asynchronous and cost
 * nothing that matters.
 *
 * Refusals and failed handshakes are logged once per address a minute, not
 * every time: on a big shared network, every phone that sees the advertisement
 * probes the receiver, some every few seconds, and a line each would push
 * everything else out of a size-capped log.
 */
class CastServer(
    private val receiver: CastReceiver,
    private val identities: () -> CastIdentity,
    private val accept: (InetAddress) -> Boolean = { true },
    private val log: (String) -> Unit = {},
) : Closeable {

    private var server: ServerSocket? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false

    /** (what, address) -> when it was last logged and how many were left out since. */
    private val quiet = ConcurrentHashMap<String, LongArray>()

    /**
     * Listens on [port], or on any free port if that one is taken, and returns
     * the port in use -- which is what the mDNS advertisement must carry.
     * Senders connect to the advertised port, so 8009 is a courtesy to tools
     * that assume it, not a requirement.
     */
    fun start(port: Int = DEFAULT_PORT, bindAddress: InetAddress? = null): Int {
        check(server == null) { "already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(bindAddress, port))
        } catch (e: BindException) {
            if (port == 0) throw e
            log("port $port is taken, using any free port")
            socket.bind(InetSocketAddress(bindAddress, 0))
        }
        server = socket
        thread(name = "cast-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    val port: Int get() = server?.localPort ?: -1

    override fun close() {
        closed = true
        runCatching { server?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!closed) {
            val socket = try {
                listener.accept()
            } catch (e: IOException) {
                if (!closed) log("accept failed: $e")
                return
            }
            val remote = socket.inetAddress
            if (!accept(remote)) {
                logQuietly("refused", remote.hostAddress) { "refused a sender at ${remote.hostAddress}" }
                runCatching { socket.close() }
                continue
            }
            sockets += socket
            thread(name = "cast-sender", isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(plain: Socket) {
        val remote = plain.inetAddress.hostAddress
        val identity = try {
            identities()
        } catch (e: Exception) {
            log("no identity to present: $e")
            sockets -= plain
            runCatching { plain.close() }
            return
        }
        val tls = try {
            (identity.sslContext.socketFactory.createSocket(plain, remote, plain.port, true) as SSLSocket).apply {
                useClientMode = false
                soTimeout = READ_TIMEOUT_MS
                startHandshake()
            }
        } catch (e: IOException) {
            logQuietly("handshake", remote) { "TLS handshake with $remote failed: $e" }
            sockets -= plain
            runCatching { plain.close() }
            return
        }
        sockets += tls
        val channel = SocketChannel(tls.outputStream, identity)
        log("sender connected from $remote (${tls.session.protocol}, ${tls.session.cipherSuite})")
        try {
            val input = BufferedInputStream(tls.inputStream)
            while (!closed) {
                receiver.onMessage(channel, CastMessage.read(input))
            }
        } catch (_: EOFException) {
            // The sender hung up.
        } catch (e: SocketException) {
            if (!closed) log("sender $remote: $e")
        } catch (e: IOException) {
            // Includes a read timeout: a sender silent for that long has gone.
            if (!closed) log("sender $remote: $e")
        } finally {
            receiver.onChannelClosed(channel)
            sockets -= tls
            sockets -= plain
            runCatching { tls.close() }
            log("sender $remote disconnected")
        }
    }

    /** Logs [message] unless the same kind of event from [address] was logged in the last minute. */
    private fun logQuietly(kind: String, address: String, message: () -> String) {
        val now = System.currentTimeMillis()
        val entry = quiet.getOrPut("$kind $address") { longArrayOf(0, 0) }
        synchronized(entry) {
            if (now - entry[0] < QUIET_MS) {
                entry[1]++
                return
            }
            val skipped = entry[1]
            entry[0] = now
            entry[1] = 0
            log(message() + if (skipped > 0) " ($skipped more like it in the last minute)" else "")
        }
    }

    private class SocketChannel(
        private val output: OutputStream,
        override val tlsIdentity: CastIdentity,
    ) : CastChannel {
        override fun send(message: CastMessage) {
            synchronized(this) { CastMessage.write(output, message) }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8009

        /** Senders ping every 5 s; three missed pings is a sender that is gone. */
        private const val READ_TIMEOUT_MS = 20_000

        private const val QUIET_MS = 60_000L
    }
}
