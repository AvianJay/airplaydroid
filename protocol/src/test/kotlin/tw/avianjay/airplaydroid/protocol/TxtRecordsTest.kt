package tw.avianjay.airplaydroid.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
    pairs.associate { (k, v) -> k to v?.toByteArray(Charsets.UTF_8) }

class TxtRecordsTest {

    @Test
    fun `reads values as UTF-8 and is case-insensitive on keys`() {
        val attrs = txt("deviceid" to "58:55:CA:1A:E2:88", "MODEL" to "AppleTV5,3")

        assertEquals("58:55:CA:1A:E2:88", TxtRecords.text(attrs, "deviceid"))
        assertEquals("AppleTV5,3", TxtRecords.text(attrs, "model"))
        assertNull(TxtRecords.text(attrs, "absent"))
    }

    @Test
    fun `valueless and empty TXT keys read as null`() {
        val attrs = txt("pw" to null, "am" to "")

        assertNull(TxtRecords.text(attrs, "pw"))
        assertNull(TxtRecords.text(attrs, "am"))
    }

    @Test
    fun `device keys normalise across the two advertisement styles`() {
        assertEquals("5855CA1AE288", TxtRecords.normalizeDeviceKey("58:55:CA:1A:E2:88"))
        assertEquals("5855CA1AE288", TxtRecords.normalizeDeviceKey("5855ca1ae288"))
    }

    @Test
    fun `raop instance name yields both the merge key and the friendly name`() {
        val parsed = RaopInstanceName.parse("5855CA1AE288@Living Room")

        assertEquals("5855CA1AE288", parsed.deviceKey)
        assertEquals("Living Room", parsed.displayName)
    }

    @Test
    fun `a non-MAC prefix is not treated as a separator`() {
        // "Bob@Home" is a name, not MACHEX@Name -- keep the whole string.
        val parsed = RaopInstanceName.parse("Bob@Home")

        assertNull(parsed.deviceKey)
        assertEquals("Bob@Home", parsed.displayName)
    }

    @Test
    fun `raop instance name without the MAC prefix still yields a display name`() {
        val parsed = RaopInstanceName.parse("Kitchen Speaker")

        assertNull(parsed.deviceKey)
        assertEquals("Kitchen Speaker", parsed.displayName)
    }

    @Test
    fun `airplay TXT parses into typed fields`() {
        val parsed = AirPlayTxt.parse(
            txt(
                "deviceid" to "58:55:CA:1A:E2:88",
                "features" to "0x7F8AD0,0x38BCB46",
                "flags" to "0x4",
                "model" to "AppleTV5,3",
                "srcvers" to "220.68",
                "pi" to "b08f5a79-db29-4384-b456-a4784d9e6055",
            )
        )

        assertEquals("AppleTV5,3", parsed.model)
        assertEquals(0x038BCB46007F8AD0uL, parsed.features.raw)
        assertEquals(0x4L, parsed.flags.raw)
        assertEquals("220.68", parsed.sourceVersion)
        assertEquals("b08f5a79-db29-4384-b456-a4784d9e6055", parsed.pairingId)
    }

    @Test
    fun `access control that blocks pairing is detected`() {
        // There is no flag bit or feature bit for this -- acl/act are the only signal.
        assertTrue(AirPlayTxt.parse(txt("acl" to "1")).pairingBlocked)
        assertTrue(AirPlayTxt.parse(txt("act" to "2")).pairingBlocked)
        assertFalse(AirPlayTxt.parse(txt("acl" to "0", "act" to "1")).pairingBlocked)
        assertFalse(AirPlayTxt.parse(txt("model" to "AppleTV5,3")).pairingBlocked)
    }

    @Test
    fun `password is signalled by pw OR status bit 7`() {
        assertTrue(AirPlayTxt.parse(txt("pw" to "1")).passwordRequired)
        assertTrue(AirPlayTxt.parse(txt("pw" to "true")).passwordRequired)
        // Bit 7 alone, with no pw key at all.
        assertTrue(AirPlayTxt.parse(txt("flags" to "0xc4")).passwordRequired)
        assertFalse(AirPlayTxt.parse(txt("pw" to "false")).passwordRequired)
        assertFalse(AirPlayTxt.parse(txt("flags" to "0x4")).passwordRequired)
    }

    @Test
    fun `raop TXT decodes the et cn and md code lists`() {
        val parsed = RaopTxt.parse(
            txt("et" to "0,1,3", "cn" to "0,1", "md" to "0,1,2", "am" to "AppleTV5,3")
        )

        assertEquals(listOf(0, 1, 3), parsed.encryptionTypes)
        assertEquals(listOf(0, 1), parsed.audioCodecs)
        assertEquals(listOf(0, 1, 2), parsed.metadataTypes)
        assertTrue(parsed.supportsAlac)
    }

    @Test
    fun `audio format falls back to the sender defaults when absent`() {
        // These three values feed the ANNOUNCE fmtp line directly, so a missing
        // ss must not silently become 0.
        val bare = RaopTxt.parse(txt("am" to "AirPort4,107"))

        assertEquals(44100, bare.sampleRate)
        assertEquals(2, bare.channels)
        assertEquals(16, bare.sampleSizeBits)

        val explicit = RaopTxt.parse(txt("sr" to "48000", "ch" to "1", "ss" to "24"))
        assertEquals(48000, explicit.sampleRate)
        assertEquals(1, explicit.channels)
        assertEquals(24, explicit.sampleSizeBits)
    }

    @Test
    fun `ek not et decides stream encryption`() {
        assertTrue(RaopTxt.parse(txt("ek" to "1", "et" to "0,1")).encryptStream)
        assertFalse(RaopTxt.parse(txt("et" to "0,3,5")).encryptStream)
    }

    @Test
    fun `auth-setup requirement is keyed on et containing 4`() {
        assertTrue(RaopTxt.parse(txt("et" to "0,4")).requiresAuthSetup)
        assertFalse(RaopTxt.parse(txt("et" to "0,3,5")).requiresAuthSetup)
    }

    @Test
    fun `every real receiver advertises an unencrypted option`() {
        // This is why "FairPlay only" was never a real device state: HomePod
        // and every Apple TV generation advertise et containing 0.
        assertTrue(RaopTxt.parse(txt("et" to "0,3,5")).supportsUnencrypted)  // HomePod
        assertTrue(RaopTxt.parse(txt("et" to "0,1")).supportsUnencrypted)    // AirPort Express 2
        assertTrue(RaopTxt.parse(txt("et" to "0,4")).supportsUnencrypted)    // Sony
        assertTrue(RaopTxt.parse(txt("am" to "Speaker")).supportsUnencrypted) // no et at all
    }

    @Test
    fun `capability badges reflect what a sender branches on`() {
        val airPlay = AirPlayTxt.parse(txt("features" to "0x5A7FFFF7", "flags" to "0x4"))
        val raop = RaopTxt.parse(txt("et" to "0,1", "cn" to "0,1"))

        val badges = Capabilities.resolve(airPlay, raop)

        assertTrue(badges.contains(DeviceCapability.Video))
        assertTrue(badges.contains(DeviceCapability.Audio))
        assertFalse(badges.contains(DeviceCapability.AirPlay2))
        assertFalse(badges.contains(DeviceCapability.PairingBlocked))
    }

    @Test
    fun `a raop-only device still gets the Audio badge`() {
        // Classic AirPlay 1 RAOP records carry no ft key, so gating on feature
        // bit 9 would hide every AirPort Express.
        val badges = Capabilities.resolve(null, RaopTxt.parse(txt("et" to "0,1")))

        assertTrue(badges.contains(DeviceCapability.Audio))
    }

    @Test
    fun `pairing-blocked and AirPlay 2 badges fire together on a restricted ATV`() {
        val airPlay = AirPlayTxt.parse(
            txt("features" to "0x0,0x10000", "acl" to "1", "flags" to "0x244")
        )

        val badges = Capabilities.resolve(airPlay, null)

        assertTrue(badges.contains(DeviceCapability.AirPlay2))
        assertTrue(badges.contains(DeviceCapability.PairingBlocked))
    }
}

class AirPlayDeviceTest {

    @Test
    fun `merging the two service types keeps one device with both endpoints`() {
        val fromAirPlay = AirPlayDevice(
            key = "5855CA1AE288",
            displayName = "Living Room",
            airPlayEndpoint = Endpoint("192.168.1.40", 7000),
            airPlayTxt = AirPlayTxt.parse(txt("features" to "0x5A7FFFF7")),
        )
        val fromRaop = AirPlayDevice(
            key = "5855CA1AE288",
            displayName = "Living Room",
            raopEndpoint = Endpoint("192.168.1.40", 5000),
            raopTxt = RaopTxt.parse(txt("et" to "0,1", "cn" to "0,1")),
        )

        val merged = fromAirPlay.mergeWith(fromRaop)

        assertEquals("192.168.1.40", merged.airPlayEndpoint?.host)
        assertEquals(7000, merged.airPlayEndpoint?.port)
        assertEquals(5000, merged.raopEndpoint?.port)
        assertTrue(merged.airPlayTxt != null)
        assertTrue(merged.raopTxt != null)
    }

    @Test
    fun `audio goes to the RAOP port and video to the AirPlay port`() {
        // The AirPlay 2 audio path does NOT move to the _airplay._tcp port.
        // Getting this backwards would dial the wrong port on any device that
        // advertises both services.
        val device = AirPlayDevice(
            key = "5855CA1AE288",
            displayName = "Living Room",
            airPlayEndpoint = Endpoint("192.168.1.40", 7000),
            raopEndpoint = Endpoint("192.168.1.40", 5000),
        )

        assertEquals(5000, device.audioEndpoint?.port)
        assertEquals(7000, device.videoEndpoint?.port)
    }

    @Test
    fun `audio falls back to the AirPlay port when there is no separate RAOP service`() {
        val unified = AirPlayDevice(
            key = "AABBCCDDEEFF",
            displayName = "Unified",
            airPlayEndpoint = Endpoint("192.168.1.50", 7000),
        )

        assertEquals(7000, unified.audioEndpoint?.port)
        assertNull(unified.raopEndpoint)
    }
}
