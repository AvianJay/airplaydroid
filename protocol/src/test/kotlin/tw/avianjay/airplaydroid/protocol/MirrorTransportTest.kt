package tw.avianjay.airplaydroid.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which transport a receiver gets.
 *
 * Getting this rule wrong sends a legacy receiver down the HAP path (which fails
 * at pair-verify) or a modern Apple TV down the legacy path (which fails at
 * `/fp-setup`), and both failures look like an unreachable device rather than a
 * routing mistake.
 *
 * The feature words below are the **real** ones this project has observed, taken
 * from `AirPlayFeaturesTest` and from the dongle this work targeted. They are not
 * invented: an invented word is exactly how a routing test passes while the rule
 * is wrong.
 */
class MirrorTransportTest {

    private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.associate { (k, v) -> k to v?.toByteArray(Charsets.UTF_8) }

    private fun device(name: String, vararg pairs: Pair<String, String?>): AirPlayDevice =
        AirPlayDevice(
            key = name.lowercase().replace(' ', '-'),
            displayName = name,
            airPlayTxt = AirPlayTxt.parse(txt(*pairs)),
        )

    /**
     * The AS-2112123AG dongle, exactly as it advertised on the test network:
     * `features = 61647880183 = 0xE5A7FFFF7`, i.e. low word `0x5A7FFFF7`, high
     * word `0xE`.
     *
     * Decoded: bit 0 (VideoV1) set, bit 1 (Photo) set, bit 7 (Screen) set,
     * bit 9 (Audio) set, bit 46 (HKPairingAndAccessControl) **clear**. That last
     * one is what makes it legacy.
     *
     * Note this is a *different* word from the `miraAir` fixture in
     * `AirPlayFeaturesTest` (`0x5A7FFFF6,0x1E`), which is a sibling device whose
     * bit 0 is clear. Both are legacy; the one below is the unit actually probed.
     */
    private fun legacyDongle() = device(
        "AS-2112123AG",
        "model" to "AppleTV3,2",
        "features" to "0x5A7FFFF7,0xE",
        "srcvers" to "220.68",
    )

    /** A modern Apple TV 4K on tvOS 26.6: mirrors and advertises HAP pairing. */
    private fun hapAppleTv() = device(
        "305",
        "model" to "AppleTV6,2",
        "features" to "0x4A7FDFD5,0x3C177FDE",
        "srcvers" to "960.13.1",
    )

    @Test
    fun `the legacy dongle routes to the legacy path`() {
        val device = legacyDongle()
        assertEquals(
            MirrorTransport.LEGACY,
            MirrorTransport.forDevice(device),
            "a mirroring receiver without HAP pairing must use the legacy protocol",
        )
        assertTrue(MirrorTransport.isLegacy(device))
    }

    @Test
    fun `a HAP Apple TV routes to the AirPlay 2 path`() {
        val device = hapAppleTv()
        assertEquals(MirrorTransport.HAP, MirrorTransport.forDevice(device))
        assertFalse(MirrorTransport.isLegacy(device))
    }

    @Test
    fun `the model name does not decide the transport`() {
        // Both devices report an "AppleTV" model. Only the feature bits separate
        // them -- which is exactly the mistake this test guards.
        assertTrue(legacyDongle().airPlayTxt!!.model!!.startsWith("AppleTV"))
        assertTrue(hapAppleTv().airPlayTxt!!.model!!.startsWith("AppleTV"))
        assertEquals(MirrorTransport.LEGACY, MirrorTransport.forDevice(legacyDongle()))
        assertEquals(MirrorTransport.HAP, MirrorTransport.forDevice(hapAppleTv()))
    }

    @Test
    fun `the fixtures really do differ only in the pairing bits`() {
        // Guards the fixtures themselves. If someone "fixes" a feature word to
        // make a test pass, this is the test that notices: both must mirror, and
        // they must disagree about HAP pairing.
        val legacy = legacyDongle().airPlayTxt!!.features
        val modern = hapAppleTv().airPlayTxt!!.features

        assertTrue(legacy.supportsScreenMirroring, "the dongle must advertise mirroring")
        assertTrue(modern.supportsScreenMirroring, "the Apple TV must advertise mirroring")
        assertFalse(legacy.supportsHapPairing, "the dongle must not advertise HAP pairing")
        assertTrue(modern.supportsHapPairing, "the Apple TV must advertise HAP pairing")
    }

    @Test
    fun `an audio-only receiver is unsupported`() {
        // Advertises audio (bit 9) but not screen mirroring (bit 7): an AirPort
        // Express, or a speaker. It must not be offered as a mirroring target.
        val device = device("AirPort Express", "model" to "AirPort10,115", "features" to "0x200")

        assertFalse(device.airPlayTxt!!.features.supportsScreenMirroring)
        assertEquals(MirrorTransport.UNSUPPORTED, MirrorTransport.forDevice(device))
        assertFalse(MirrorTransport.isLegacy(device))
    }

    @Test
    fun `an unresolved device is unsupported rather than guessed at`() {
        // No _airplay._tcp record yet. Guessing here would flash a "Legacy" badge
        // and then take the wrong path on the next discovery round.
        val device = AirPlayDevice(key = "unknown", displayName = "unknown")
        assertEquals(MirrorTransport.UNSUPPORTED, MirrorTransport.forDevice(device))
        assertFalse(MirrorTransport.isLegacy(device))
    }

    @Test
    fun `the TXT-only overload agrees with the device overload`() {
        // The badge uses the TXT overload and the service uses the device one;
        // if these ever disagree the UI describes a different device than the one
        // the service drives.
        val devices = listOf(
            legacyDongle(),
            hapAppleTv(),
            AirPlayDevice(key = "unknown", displayName = "unknown"),
        )
        for (device in devices) {
            assertEquals(
                MirrorTransport.forDevice(device),
                MirrorTransport.forAirPlayTxt(device.airPlayTxt),
                "the two overloads must agree for ${device.displayName}",
            )
        }
    }

    @Test
    fun `the legacy badge appears exactly when the legacy path is taken`() {
        // The user-visible consequence of the rule.
        assertTrue(
            DeviceCapability.Legacy in legacyDongle().capabilities,
            "the dongle should carry the Legacy badge",
        )
        assertFalse(
            DeviceCapability.Legacy in hapAppleTv().capabilities,
            "a HAP Apple TV should not carry the Legacy badge",
        )
    }

    @Test
    fun `the legacy dongle shows the badges its advertisement implies`() {
        val caps = legacyDongle().capabilities
        assertTrue(DeviceCapability.Audio in caps, "bit 9 is set")
        assertTrue(DeviceCapability.Video in caps, "bit 0 is set")
        assertFalse(DeviceCapability.AirPlay2 in caps, "a legacy dongle is not AirPlay 2")
    }
}
