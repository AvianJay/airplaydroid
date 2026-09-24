package tw.avianjay.airplaydroid.protocol

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
    pairs.associate { (k, v) -> k to v?.toByteArray(Charsets.UTF_8) }

private fun device(
    name: String = "Receiver",
    airPlay: Map<String, ByteArray?>? = null,
    endpoint: Endpoint? = Endpoint("192.168.1.40", 7000),
) = AirPlayDevice(
    key = "K",
    displayName = name,
    airPlayEndpoint = endpoint,
    airPlayTxt = airPlay?.let { AirPlayTxt.parse(it) },
)

class VideoHandoffTest {

    @Test
    fun `an AirPlay 1 video receiver is accepted`() {
        // Apple TV 3 class: bit 0 set, no AirPlay 2 bits.
        val atv3 = device(airPlay = txt("features" to "0x5A7FFFF7", "flags" to "0x4"))

        assertNull(VideoHandoff.refusalFor(atv3))
        assertTrue(VideoHandoff.isSupported(atv3))
    }

    @Test
    fun `an AirPlay 2 receiver is NOT refused`() {
        // HARDWARE-VERIFIED: an Apple TV 4K with feature bits 38 and 48 set
        // accepted a bare POST /play over HTTP Digest, with no HomeKit pairing.
        // Refusing AirPlay 2 outright would reject every modern receiver for a
        // reason that does not hold.
        val appleTv4k = device("305", txt("features" to "0x4A7FDFD5,0x3C177FDE"))

        assertNull(VideoHandoff.refusalFor(appleTv4k))
    }

    @Test
    fun `restricted access control is still refused - that one is real`() {
        val restricted = device("Someone's Mac", txt("features" to "0x0,0x10000", "act" to "2"))

        assertTrue(VideoHandoff.refusalFor(restricted) is ConnectionRefusal.PairingBlocked)
    }

    @Test
    fun `a password-protected receiver is NOT refused - the session answers the challenge`() {
        val protectedAtv = device(airPlay = txt("features" to "0x5A7FFFF7", "flags" to "0xc4"))

        assertNull(VideoHandoff.refusalFor(protectedAtv))
    }

    @Test
    fun `an audio-only receiver is refused`() {
        // Feature bit 9 only: audio, no video of either version.
        val speaker = device("Speaker", txt("features" to "0x200"))

        assertTrue(VideoHandoff.refusalFor(speaker) is ConnectionRefusal.NoVideoSupport)
    }

    @Test
    fun `a bit-49-only receiver is accepted`() {
        // Modern third-party TVs set SupportsAirPlayVideoV2 and CLEAR bit 0.
        // Gating on bit 0 alone would wrongly refuse these.
        val tv = device("Samsung TV", txt("features" to "0x0,0x20000"))

        assertNull(VideoHandoff.refusalFor(tv))
    }

    @Test
    fun `an unresolved device is refused for want of an endpoint`() {
        val unresolved = device(airPlay = txt("features" to "0x5A7FFFF7"), endpoint = null)

        assertTrue(VideoHandoff.refusalFor(unresolved) is ConnectionRefusal.NoEndpoint)
    }

    @Test
    fun `a raop-only device with no AirPlay TXT is not blocked on feature bits`() {
        // Nothing is known about it; the endpoint check is what decides.
        val raopOnly = AirPlayDevice(
            key = "K",
            displayName = "AirPort Express",
            raopEndpoint = Endpoint("192.168.1.50", 5000),
        )

        assertTrue(VideoHandoff.refusalFor(raopOnly) is ConnectionRefusal.NoEndpoint)
    }
}
