package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest reader.
 *
 * A tolerant parser is the wrong tool here: silently accepting a malformed
 * manifest means an updater that quietly offers nothing, which looks identical
 * to "you are up to date" and is therefore never reported as a bug.
 */
class UpdateManifestTest {

    private val goodManifest = """
        {
          "schema": 1,
          "releases": [
            {
              "versionName": "0.2.0",
              "versionCode": 42,
              "prerelease": false,
              "apkUrl": "https://github.com/AvianJay/airplaydroid/releases/download/v0.2.0/AirPlayDroid-0.2.0.apk",
              "sha256": "B60F13946C5DB783B18DB5E1A003E1F0A4A41BC761B81392E982AC008DC9BD9B",
              "sizeBytes": 10846878,
              "notes": "feat: add an updater",
              "publishedAt": "2026-09-25T17:33:52Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsEveryField() {
        val releases = UpdateManifest.parse(goodManifest)
        assertEquals(1, releases.size)

        val release = releases.single()
        assertEquals("0.2.0", release.versionName)
        assertEquals(42, release.versionCode)
        assertEquals(false, release.prerelease)
        assertTrue(release.apkUrl.startsWith("https://"))
        assertEquals("feat: add an updater", release.notes)
        assertEquals("2026-09-25T17:33:52Z", release.publishedAt)
        assertEquals(10846878L, release.sizeBytes)
    }

    @Test
    fun lowercasesTheDigest() {
        // The workflow writes uppercase hex from `sha256sum`; a comparison done
        // without normalising would reject every correct download.
        assertEquals(
            "b60f13946c5db783b18db5e1a003e1f0a4a41bc761b81392e982ac008dc9bd9b",
            UpdateManifest.parse(goodManifest).single().sha256,
        )
    }

    @Test
    fun readsTheVersionCodeAsAnExactInteger() {
        // 16777217 is the first integer a Double cannot represent. A versionCode
        // routed through Double would come back as 16777216 and compare as older
        // than the installed build.
        val releases = UpdateManifest.parse(
            """{"releases":[{"versionName":"1.0.0","versionCode":16777217,
               "apkUrl":"https://example.com/a.apk"}]}"""
        )
        assertEquals(16777217, releases.single().versionCode)
    }

    @Test
    fun rejectsANonObjectRoot() {
        assertFailsWith<UpdateManifest.FormatException> { UpdateManifest.parse("[]") }
        assertFailsWith<UpdateManifest.FormatException> { UpdateManifest.parse("\"nope\"") }
    }

    @Test
    fun rejectsAMissingReleasesArray() {
        assertFailsWith<UpdateManifest.FormatException> { UpdateManifest.parse("""{"schema":1}""") }
    }

    @Test
    fun rejectsInvalidJson() {
        // The failure mode this guards: a truncated download served as HTML by a
        // proxy must not read as "no releases available".
        assertFailsWith<UpdateManifest.FormatException> { UpdateManifest.parse("<html>404</html>") }
        assertFailsWith<UpdateManifest.FormatException> { UpdateManifest.parse("""{"releases":[""") }
    }

    @Test
    fun skipsUninstallableEntriesInsteadOfFailingTheWholeManifest() {
        // One bad entry must not hide the good ones.
        val releases = UpdateManifest.parse(
            """
            {"releases":[
              {"versionName":"0.2.0","versionCode":42,"apkUrl":"https://example.com/a.apk"},
              {"versionName":"0.3.0"},
              {"versionCode":9,"apkUrl":"https://example.com/b.apk"},
              {"versionName":"0.4.0","versionCode":44,"apkUrl":"http://example.com/c.apk"}
            ]}
            """
        )
        assertEquals(listOf("0.2.0"), releases.map { it.versionName })
    }

    @Test
    fun refusesACleartextApkUrl() {
        // An updater that fetches an APK over http:// can be pointed at a
        // different binary by anyone on the network path.
        val releases = UpdateManifest.parse(
            """{"releases":[{"versionName":"1.0.0","apkUrl":"http://example.com/a.apk"}]}"""
        )
        assertTrue(releases.isEmpty())
    }

    @Test
    fun defaultsPrereleaseToFalseAndLeavesOptionalFieldsNull() {
        val release = UpdateManifest.parse(
            """{"releases":[{"versionName":"1.0.0","apkUrl":"https://example.com/a.apk"}]}"""
        ).single()

        // Defaulting to "stable" is the safe direction: it offers an update that
        // exists rather than hiding one.
        assertEquals(false, release.prerelease)
        assertNull(release.versionCode)
        assertNull(release.sha256)
        assertNull(release.sizeBytes)
        assertNull(release.notes)
    }

    @Test
    fun dropsADigestThatIsNotSixtyFourHexCharacters() {
        val release = UpdateManifest.parse(
            """{"releases":[{"versionName":"1.0.0","apkUrl":"https://example.com/a.apk",
               "sha256":"abc"}]}"""
        ).single()
        assertNull(release.sha256)
    }

    @Test
    fun anEmptyReleaseListIsValidAndMeansNothingToOffer() {
        assertTrue(UpdateManifest.parse("""{"releases":[]}""").isEmpty())
    }

    @Test
    fun readsUnicodeEscapesAndNestedBracesInNotes() {
        val release = UpdateManifest.parse(
            """{"releases":[{"versionName":"1.0.0","apkUrl":"https://example.com/a.apk",
               "notes":"a } brace, a \"quote\" and \u00e9"}]}"""
        ).single()
        assertEquals("a } brace, a \"quote\" and é", release.notes)
    }

    @Test
    fun formatsSizesForDisplay() {
        fun label(bytes: Long) = UpdateRelease(
            versionName = "1.0.0", versionCode = 1, prerelease = false,
            apkUrl = "https://example.com/a.apk", sizeBytes = bytes,
        ).sizeLabel()

        assertEquals("512 B", label(512))
        assertEquals("2 kB", label(2048))
        assertEquals("10.3 MB", label(10846878))
        assertEquals("1.5 GB", label(1610612736))
        assertNull(label(0))
        assertNull(
            UpdateRelease("1.0.0", 1, false, "https://example.com/a.apk").sizeLabel()
        )
    }
}
