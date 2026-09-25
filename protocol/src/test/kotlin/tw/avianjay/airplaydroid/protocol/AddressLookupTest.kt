package tw.avianjay.airplaydroid.protocol

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AddressLookup] turns an `/info` reply into the device discovery would have found. */
class AddressLookupTest {

    @Test
    fun `a LonelyScreen-shaped info is a legacy receiver`() {
        // The fields LonelyScreen 1.x answered on 2026-09-25 (it ignores the qualifier).
        val info = PlistValue.dict(
            "name" to PlistValue.PString("Apple TV"),
            "model" to PlistValue.PString("AppleTV3,2"),
            "deviceID" to PlistValue.PString("35:24:68:45:21:44"),
            "sourceVersion" to PlistValue.PString("220.68"),
            "features" to PlistValue.PInt(130367356919L),
            "statusFlags" to PlistValue.PInt(68),
            "pk" to PlistValue.PData(ByteArray(32) { it.toByte() }),
        )
        val device = AddressLookup.fromInfo("10.0.2.2", 7000, info)

        assertEquals("352468452144", device.key)
        assertEquals("Apple TV", device.displayName)
        assertEquals(Endpoint("10.0.2.2", 7000), device.videoEndpoint)
        assertEquals(0x1E5A7FFFF7uL, device.airPlayTxt!!.features.raw)
        assertEquals(MirrorTransport.LEGACY, MirrorTransport.forDevice(device))
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", device.airPlayTxt!!.publicKey)
    }

    @Test
    fun `an Apple TV info without the qualifier still maps to the HAP transport`() {
        val info = BinaryPlist.decode(javaClass.getResourceAsStream("/appletv_info.bplist")!!.readBytes()) as PlistValue.PDict
        val device = AddressLookup.fromInfo("100.104.157.66", 7000, info)

        assertEquals("EEC4975A3BD0", device.key)
        assertEquals(MirrorTransport.HAP, MirrorTransport.forDevice(device))
        assertTrue(device.airPlayTxt!!.passwordRequired, "statusFlags 0xc4 has bit 7, a password")
    }

    @Test
    fun `a txtAirPlay record wins over the dictionary`() {
        val txt = ByteArrayOutputStream().apply {
            for (entry in listOf("deviceid=AA:BB:CC:DD:EE:FF", "features=0x4A7FDFD5,0x3C177FDE", "flags=0x84", "model=AppleTV6,2", "pw")) {
                write(entry.length)
                write(entry.toByteArray())
            }
        }.toByteArray()
        val info = PlistValue.dict(
            "name" to PlistValue.PString("Living Room"),
            "features" to PlistValue.PInt(0),
            "txtAirPlay" to PlistValue.PData(txt),
        )
        val device = AddressLookup.fromInfo("h", 7000, info)

        assertEquals("AABBCCDDEEFF", device.key)
        assertEquals(MirrorTransport.HAP, MirrorTransport.forDevice(device))
        assertTrue(device.airPlayTxt!!.passwordRequired, "flags bit 7, from the TXT record rather than the dictionary")
    }

    @Test
    fun `the TXT parser keeps valueless keys and survives a truncated record`() {
        val parsed = AddressLookup.parseTxt(byteArrayOf(2, 'p'.code.toByte(), 'w'.code.toByte(), 9, 'a'.code.toByte()))
        assertTrue(parsed.containsKey("pw"))
        assertNull(parsed["pw"])
        assertEquals(setOf("pw", "a"), parsed.keys)
    }
}
