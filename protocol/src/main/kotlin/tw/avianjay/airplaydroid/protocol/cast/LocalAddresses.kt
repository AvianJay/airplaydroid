package tw.avianjay.airplaydroid.protocol.cast

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Which addresses are this device's own, and which one a given peer can reach. */
object LocalAddresses {

    /**
     * Whether [address] belongs to this device: loopback, or bound to one of its
     * interfaces.
     *
     * Loopback alone is not enough to mean "an app on this phone". A sender on
     * the same phone finds the receiver through mDNS, which advertises the
     * Wi-Fi address, so its connection arrives *from* that Wi-Fi address -- the
     * kernel picks the source address matching the destination. Checking the
     * interfaces is what lets a local sender in while a laptop on the same
     * Wi-Fi, whose address is bound to no interface here, is kept out.
     */
    fun isOwnAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        return runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)
    }

    /**
     * This device's address on the route to [host], for a URL [host] will fetch
     * from us. A connected UDP socket sends nothing; connecting it only makes the
     * kernel choose the outgoing interface and source address, which is exactly
     * the question. Null when there is no route.
     */
    fun facing(host: String, port: Int = 9): InetAddress? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port))
            socket.localAddress?.takeUnless { it.isAnyLocalAddress }
        }
    }.getOrNull()

    /** [address] as a URL host: IPv6 literals are bracketed and lose their zone. */
    fun urlHost(address: InetAddress): String {
        val text = address.hostAddress.substringBefore('%')
        return if (':' in text) "[$text]" else text
    }
}
