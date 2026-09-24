package tw.avianjay.airplaydroid.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirPlayFeaturesTest {

    @Test
    fun `two-word form recombines with the LOW word first`() {
        // Real sample. low=0x7F8AD0, high=0x38BCB46 -> 0x038BCB46_007F8AD0
        val features = AirPlayFeatures.parse("0x7F8AD0,0x38BCB46")

        assertEquals(0x038BCB46007F8AD0uL, features.raw)
    }

    @Test
    fun `word order is not symmetric - swapping produces a different value`() {
        // Guards the single most damaging parsing mistake in the whole protocol:
        // reading the pair high-word-first silently misreads every capability.
        val correct = AirPlayFeatures.parse("0x7F8AD0,0x38BCB46")
        val swapped = AirPlayFeatures.parse("0x38BCB46,0x7F8AD0")

        assertTrue(correct.raw != swapped.raw)
    }

    @Test
    fun `single-word form leaves the high word zero`() {
        val features = AirPlayFeatures.parse("0x5A7FFFF7")

        assertEquals(0x5A7FFFF7uL, features.raw)
        assertEquals(0uL, features.raw shr 32)
    }

    @Test
    fun `bit accessors read the low word correctly`() {
        // 0x7F8AD0 -> nibbles 0,D,A,8,F,7 -> bit0=0, bit1=0, bit4=1, bit9=1
        val features = AirPlayFeatures.parse("0x7F8AD0")

        assertFalse(features.supportsAirPlayVideoV1) // bit 0
        assertFalse(features.supportsPhoto)          // bit 1
        assertTrue(features.supportsHls)             // bit 4
        assertTrue(features.supportsAudio)           // bit 9
    }

    @Test
    fun `supportsPhoto reads bit 1, not the unassigned bit 6`() {
        assertTrue(AirPlayFeatures.parse("0x2").supportsPhoto)
        assertFalse(AirPlayFeatures.parse("0x40").supportsPhoto)
    }

    @Test
    fun `video-URL support accepts either V1 or V2`() {
        // Modern third-party AirPlay 2 TVs set bit 49 and CLEAR bit 0. Testing
        // bit 0 alone would hide them from the Video badge and from milestone 3.
        val v1Only = AirPlayFeatures.parse("0x1")
        val v2Only = AirPlayFeatures.parse("0x0,0x20000") // bit 49 = high word bit 17
        val neither = AirPlayFeatures.parse("0x200")      // audio only

        assertTrue(v1Only.supportsVideoUrl)
        assertTrue(v2Only.supportsAirPlayVideoV2)
        assertTrue(v2Only.supportsVideoUrl)
        assertFalse(neither.supportsVideoUrl)
    }

    @Test
    fun `AirPlay 2 is indicated by bit 38 or bit 48`() {
        val bit38 = AirPlayFeatures.parse("0x0,0x40")    // high word bit 6  = bit 38
        val bit48 = AirPlayFeatures.parse("0x0,0x10000") // high word bit 16 = bit 48
        val ap1 = AirPlayFeatures.parse("0x5A7FFFF7")

        assertTrue(bit38.isAirPlay2)
        assertTrue(bit48.isAirPlay2)
        assertFalse(ap1.isAirPlay2)
    }

    @Test
    fun `bit accessors reach into the high word`() {
        // bit 46 lives in the high word: 1 shl (46-32) = 0x4000
        val features = AirPlayFeatures.parse("0x0,0x4000")

        assertTrue(features.hasBit(46))
        assertTrue(features.supportsHomeKitPairing)
        assertFalse(features.hasBit(45))
    }

    @Test
    fun `prefixes are optional and case-insensitive`() {
        assertEquals(AirPlayFeatures.parse("0x7F8AD0").raw, AirPlayFeatures.parse("7F8AD0").raw)
        assertEquals(AirPlayFeatures.parse("0X7f8ad0").raw, AirPlayFeatures.parse("0x7F8AD0").raw)
    }

    @Test
    fun `malformed or absent input degrades to NONE instead of throwing`() {
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse(null))
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse(""))
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse("   "))
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse("not-hex"))
        // A malformed second word invalidates the whole value rather than
        // silently yielding a half-parsed one.
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse("0x7F8AD0,garbage"))
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse("0x1,0x2,0x3"))
    }

    @Test
    fun `over-long words are rejected rather than masked`() {
        // Masking a 9-digit word down to 32 bits would turn a malformed value
        // into a plausible-looking one that then drives wrong badges.
        assertEquals(AirPlayFeatures.NONE, AirPlayFeatures.parse("0x1234567890"))
        assertEquals(0xFFFFFFFFuL, AirPlayFeatures.parse("0xFFFFFFFF").raw)
    }
}

class StatusFlagsTest {

    @Test
    fun `parses hex and exposes named bits`() {
        val flags = StatusFlags.parse("0x288") // bits 3, 7, 9

        assertTrue(flags.pinRequired)
        assertTrue(flags.passwordRequired)
        assertTrue(flags.pairingRequired)
        assertFalse(flags.problemsExist)
        assertFalse(flags.busy)
    }

    @Test
    fun `sf 0x4 is the universal default, not meaningful state`() {
        val flags = StatusFlags.parse("0x4")

        assertTrue(flags.audioCableAttached)
        assertFalse(flags.pinRequired)
        assertFalse(flags.passwordRequired)
        assertFalse(flags.pairingRequired)
    }

    @Test
    fun `real captured values decode as expected`() {
        // Apple TV 4 flags=0x244 -> pairing mandatory
        assertTrue(StatusFlags.parse("0x244").pairingRequired)
        // Apple TV 3 flags=0xc4 -> password
        assertTrue(StatusFlags.parse("0xc4").passwordRequired)
    }

    @Test
    fun `absent input is empty rather than an error`() {
        assertEquals(StatusFlags.NONE, StatusFlags.parse(null))
        assertEquals(StatusFlags.NONE, StatusFlags.parse("nonsense"))
        assertTrue(StatusFlags.parse("0x0").isEmpty)
    }
}
