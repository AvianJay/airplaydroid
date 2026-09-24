package tw.avianjay.airplaydroid.protocol.plist

import tw.avianjay.airplaydroid.protocol.PlaybackInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The exact shape UxPlay emits from create_playback_info_plist_xml(), including
 * the DOCTYPE that points at apple.com and `readyToPlay` written as an integer
 * rather than <true/>.
 */
private val PLAYBACK_INFO_XML = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>duration</key>
	<real>596.473</real>
	<key>position</key>
	<real>12.5</real>
	<key>rate</key>
	<real>1</real>
	<key>readyToPlay</key>
	<integer>1</integer>
	<key>playbackBufferEmpty</key>
	<true/>
	<key>playbackBufferFull</key>
	<false/>
</dict>
</plist>
""".trimIndent().toByteArray(Charsets.UTF_8)

class XmlPlistTest {

    @Test
    fun `parses the playback-info XML a real receiver sends`() {
        val dict = XmlPlist.decode(PLAYBACK_INFO_XML) as PlistValue.PDict

        assertEquals(596.473, dict.number("duration"))
        assertEquals(12.5, dict.number("position"))
        assertEquals(1.0, dict.number("rate"))
        assertEquals(true, dict.bool("playbackBufferEmpty"))
        assertEquals(false, dict.bool("playbackBufferFull"))
    }

    @Test
    fun `readyToPlay written as an integer still reads as a boolean`() {
        // UxPlay uses plist_new_uint for this key, so a PBool-only accessor
        // would report false forever.
        val dict = XmlPlist.decode(PLAYBACK_INFO_XML) as PlistValue.PDict

        assertEquals(true, dict.bool("readyToPlay"))
    }

    @Test
    fun `the whole PlaybackInfo maps correctly from XML`() {
        // This is the end-to-end regression: before the XML path existed this
        // returned EMPTY, which made pause impossible on every real receiver.
        val info = PlaybackInfo.fromPlist(XmlPlist.decode(PLAYBACK_INFO_XML))

        assertEquals(596.473, info.durationSeconds)
        assertEquals(12.5, info.positionSeconds)
        assertTrue(info.isPlaying)
        assertTrue(info.readyToPlay)
    }

    @Test
    fun `the apple_com DOCTYPE is not fetched`() {
        // If external DTD loading were enabled this would attempt a network
        // request. It completing offline is the assertion.
        val dict = XmlPlist.decode(PLAYBACK_INFO_XML) as PlistValue.PDict
        assertTrue(dict.entries.isNotEmpty())
    }

    @Test
    fun `an external entity cannot read a local file`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE plist [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <plist version="1.0"><dict><key>a</key><string>&xxe;</string></dict></plist>
        """.trimIndent().toByteArray()

        // Either it refuses outright, or the entity expands to nothing. What it
        // must never do is return file contents.
        val result = runCatching { XmlPlist.decode(xxe) }
        val leaked = (result.getOrNull() as? PlistValue.PDict)?.string("a").orEmpty()
        assertTrue(leaked.isEmpty(), "entity expanded to: " + leaked)
    }

    @Test
    fun `arrays, data and nesting round-trip`() {
        val xml = """
            <?xml version="1.0"?>
            <plist version="1.0">
            <dict>
              <key>list</key>
              <array><string>a</string><integer>2</integer></array>
              <key>blob</key>
              <data>AQID</data>
              <key>nested</key>
              <dict><key>deep</key><real>0.5</real></dict>
            </dict>
            </plist>
        """.trimIndent().toByteArray()

        val dict = XmlPlist.decode(xml) as PlistValue.PDict
        val list = dict["list"] as PlistValue.PArray
        val nested = dict["nested"] as PlistValue.PDict

        assertEquals(2, list.values.size)
        assertEquals("a", (list.values[0] as PlistValue.PString).value)
        assertContentEquals(byteArrayOf(1, 2, 3), (dict["blob"] as PlistValue.PData).value)
        assertEquals(0.5, nested.number("deep"))
    }

    @Test
    fun `malformed XML is rejected`() {
        assertFailsWith<XmlPlist.FormatException> {
            XmlPlist.decode("<plist><dict><key>a</key>".toByteArray())
        }
    }
}

class PlistsSniffingTest {

    @Test
    fun `binary bodies go to the binary decoder`() {
        val binary = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PString("b")))

        assertTrue(Plists.looksBinary(binary))
        assertEquals("b", (Plists.decode(binary) as PlistValue.PDict).string("a"))
    }

    @Test
    fun `XML bodies go to the XML decoder`() {
        assertTrue(!Plists.looksBinary(PLAYBACK_INFO_XML))

        val dict = Plists.decode(PLAYBACK_INFO_XML) as PlistValue.PDict
        assertEquals(12.5, dict.number("position"))
    }

    @Test
    fun `a short body is not mistaken for binary`() {
        assertTrue(!Plists.looksBinary(byteArrayOf(1, 2)))
    }
}
