package tw.avianjay.airplaydroid.protocol.media

import tw.avianjay.airplaydroid.protocol.media.AirPlayFormats.Route
import tw.avianjay.airplaydroid.protocol.cast.LocalAddresses
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirPlayFormatsTest {

    @Test
    fun nativeFormatsGoStraightToTheReceiver() {
        assertEquals(Route.DIRECT, AirPlayFormats.route("https://h/v.mp4", "video/mp4", convert = true))
        assertEquals(Route.DIRECT, AirPlayFormats.route("https://h/live/index.m3u8", null, convert = true))
        assertEquals(Route.DIRECT, AirPlayFormats.route("https://h/a", "application/x-mpegURL", convert = true))
    }

    @Test
    fun foreignFormatsAreConvertedOnlyWhenAskedTo() {
        assertEquals(Route.CONVERT, AirPlayFormats.route("https://h/v.webm", null, convert = true))
        assertEquals(Route.CONVERT, AirPlayFormats.route("https://h/stream", "video/x-matroska", convert = true))
        assertEquals(Route.CONVERT, AirPlayFormats.route("http://127.0.0.1:8080/v.mkv", null, convert = true))
        assertEquals(Route.DIRECT, AirPlayFormats.route("https://h/v.webm", null, convert = false))
    }

    @Test
    fun theContentTypeWinsOverAMisleadingExtension() {
        // Both are what they claim: the type is the sender's own statement.
        assertEquals(AirPlayFormats.Kind.FOREIGN, AirPlayFormats.classify("https://h/play.php", "video/webm; codecs=vp9"))
        assertEquals(AirPlayFormats.Kind.NATIVE, AirPlayFormats.classify("https://h/v.webm?x", "video/mp4"))
    }

    @Test
    fun loopbackUrlsAreRelayed() {
        assertEquals(Route.PROXY, AirPlayFormats.route("http://127.0.0.1:1234/v.mp4", "video/mp4", convert = true))
        assertEquals(Route.PROXY, AirPlayFormats.route("http://localhost:1234/v.mp4", null, convert = false))
        assertEquals(Route.PROXY, AirPlayFormats.route("http://[::1]:1234/v.mp4", null, convert = false))
        assertFalse(AirPlayFormats.isLoopbackUrl("http://example.com/v.mp4"))
        assertFalse(AirPlayFormats.isLoopbackUrl("http://192.168.1.2/v.mp4"))
    }

    @Test
    fun dashCannotBeConverted() {
        assertEquals(Route.UNSUPPORTED, AirPlayFormats.route("https://h/manifest.mpd", null, convert = true))
        assertEquals(Route.UNSUPPORTED, AirPlayFormats.route("https://h/x", "application/dash+xml", convert = true))
    }

    @Test
    fun ownAddressesAreThisMachinesAndNoOneElses() {
        assertTrue(LocalAddresses.isOwnAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LocalAddresses.isOwnAddress(InetAddress.getByName("::1")))
        // An address bound to one of this machine's interfaces, as a local
        // sender's connection arrives from it.
        val own = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress }
        own?.let { assertTrue(LocalAddresses.isOwnAddress(it)) }
        // TEST-NET-3: routable in form, bound to no machine.
        assertFalse(LocalAddresses.isOwnAddress(InetAddress.getByName("203.0.113.7")))
    }

    @Test
    fun urlHostsBracketIpv6() {
        // Java spells IPv6 out in full; any spelling is a valid URL host once bracketed.
        assertEquals("[fe80:0:0:0:0:0:0:1]", LocalAddresses.urlHost(InetAddress.getByName("fe80::1%1")))
        assertEquals("10.0.0.2", LocalAddresses.urlHost(InetAddress.getByName("10.0.0.2")))
    }
}
