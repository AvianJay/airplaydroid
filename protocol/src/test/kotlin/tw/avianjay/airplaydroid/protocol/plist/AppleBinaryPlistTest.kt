package tw.avianjay.airplaydroid.protocol.plist

import tw.avianjay.airplaydroid.protocol.AirPlayFeatures
import tw.avianjay.airplaydroid.protocol.StatusFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes a **real** binary plist produced by Apple's own encoder -- the body of a
 * `GET /info` response captured from an Apple TV 4K (AppleTV6,2) on tvOS 26.6.
 *
 * This matters more than the round-trip tests: those only prove the decoder can
 * read what our encoder writes, which would still pass if both shared a
 * misunderstanding of the format. Apple's encoder exercises things ours never
 * emits -- 26 top-level keys, nested dictionaries, a negative 64-bit integer,
 * object deduplication, and mixed integer widths.
 *
 * NOTE: the fixture contains that receiver's advertised identifiers (name,
 * deviceID, MAC, public key). They are broadcast openly on the LAN and are not
 * secrets, but they do identify a specific device -- worth sanitising before
 * publishing this repository.
 */
class AppleBinaryPlistTest {

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/appletv_info.bplist")) {
            "missing test fixture appletv_info.bplist"
        }.use { it.readBytes() }

    private fun info(): PlistValue.PDict =
        BinaryPlist.decode(fixture()) as PlistValue.PDict

    @Test
    fun `decodes a real Apple-encoded binary plist`() {
        val info = info()

        assertEquals(26, info.entries.size)
        assertEquals("305", info.string("name"))
        assertEquals("AppleTV6,2", info.string("model"))
        assertEquals("960.13.1", info.string("sourceVersion"))
        assertEquals("1.1", info.string("protocolVersion"))
    }

    @Test
    fun `the sniffing entry point routes it to the binary decoder`() {
        assertTrue(Plists.looksBinary(fixture()))
        assertEquals("305", (Plists.decode(fixture()) as PlistValue.PDict).string("name"))
    }

    /**
     * The decisive one. The TXT record advertised
     * `features=0x4A7FDFD5,0x3C177FDE` -- two 32-bit words, LOW WORD FIRST --
     * while this plist carries the same capability set as a single integer.
     * Apple's own two encodings agreeing is proof the recombination is correct;
     * reading the pair high-word-first would silently misread every capability.
     */
    @Test
    fun `the features integer equals the low-word-first recombination of the TXT pair`() {
        val fromTxt = AirPlayFeatures.parse("0x4A7FDFD5,0x3C177FDE")
        val fromInfo = (info()["features"] as PlistValue.PInt).value

        assertEquals(fromInfo.toULong(), fromTxt.raw)
        assertEquals(0x3C177FDE4A7FDFD5uL, fromTxt.raw)
    }

    @Test
    fun `capability bits decoded from the real device match what it is`() {
        val features = AirPlayFeatures((info()["features"] as PlistValue.PInt).value.toULong())

        // An Apple TV 4K on tvOS 26: video, audio and AirPlay 2, but no photo
        // support -- tvOS 14 dropped it, which is why supportsPhoto must read
        // bit 1 and not bit 6. Bit 6 IS set on this device.
        assertTrue(features.supportsAirPlayVideoV1)
        assertTrue(features.supportsAirPlayVideoV2)
        assertTrue(features.supportsVideoUrl)
        assertTrue(features.supportsAudio)
        assertTrue(features.isAirPlay2)
        assertTrue(features.supportsHomeKitPairing)
        assertTrue(!features.supportsPhoto)
        assertTrue(features.hasBit(6), "bit 6 is set, which is why it must not drive supportsPhoto")
    }

    /**
     * `statusFlags` in /info is the authoritative copy of the TXT `flags` field.
     * This device advertised flags=0xc4 and its owner confirmed "Require
     * Password" is switched on, which is what pins bit 7 to PASSWORD.
     */
    @Test
    fun `statusFlags agrees with the TXT flags value and decodes to password`() {
        val flags = StatusFlags((info()["statusFlags"] as PlistValue.PInt).value)

        assertEquals(0xC4L, flags.raw)
        assertEquals(StatusFlags.parse("0xc4").raw, flags.raw)

        assertTrue(flags.passwordRequired)      // bit 7 - confirmed on the device
        assertTrue(flags.audioCableAttached)    // bit 2 - universal default
        assertTrue(flags.supportsFromCloud)     // bit 6
        assertTrue(!flags.pinRequired)          // bit 3
        assertTrue(!flags.pairingRequired)      // bit 9 - password flow, not persistent
    }

    @Test
    fun `nested dictionaries and a negative 64-bit integer survive`() {
        // Apple emits bufferStream as a negative int; our own encoder never
        // produces one, so the round-trip tests cannot cover this path.
        val formats = info()["supportedFormats"] as PlistValue.PDict

        assertEquals(-577021992844656640.0, formats.number("bufferStream"))
        assertEquals(21235712.0, formats.number("screenStream"))
        assertEquals(21235712.0, formats.number("audioStream"))
    }

    @Test
    fun `booleans and reals decode alongside strings`() {
        val info = info()

        assertEquals(false, info.bool("screenDemoMode"))
        assertEquals(true, info.bool("keepAliveSendStatsAsBody"))
        assertEquals(0.0, info.number("initialVolume"))

        // Worth noting for pairing: /info carries pk as 32 RAW BYTES, while the
        // mDNS TXT record carries the same key hex-encoded as 64 characters.
        val pk = info["pk"] as PlistValue.PData
        assertEquals(32, pk.value.size)
    }

    /**
     * Recorded because it bears directly on whether screen mirroring is
     * reachable: this receiver declares it accepts a screen stream.
     */
    @Test
    fun `the receiver advertises screen mirroring support`() {
        val info = info()
        val formats = info["supportedFormats"] as PlistValue.PDict

        assertEquals(true, info.bool("hasUDPMirroringSupport"))
        assertNotNull(formats.number("screenStream"))
    }
}
