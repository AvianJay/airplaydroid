package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which release gets offered.
 *
 * Every rule tested here fails in a way that is invisible without a test:
 * offering a downgrade makes Android refuse the install with an error the user
 * cannot act on, and offering a nightly on the stable channel defeats the point
 * of having a channel setting at all.
 */
class UpdateSelectorTest {

    private fun release(
        name: String,
        code: Int?,
        prerelease: Boolean = false,
        url: String = "https://example.com/${name}.apk",
    ) = UpdateRelease(
        versionName = name,
        versionCode = code,
        prerelease = prerelease,
        apkUrl = url,
    )

    private fun installed(code: Int, name: String? = null) = InstalledVersion(code, name)

    @Test
    fun offNeverOffersAnything() {
        val decision = UpdateSelector.select(
            UpdateChannel.OFF,
            installed(1),
            listOf(release("9.9.9", 999)),
        )
        assertNull(decision.release)
        assertFalse(decision.available)
        assertEquals(UpdateOutcome.DISABLED_OR_EMPTY, decision.outcome)
    }

    @Test
    fun anEmptyManifestOffersNothing() {
        val decision = UpdateSelector.select(UpdateChannel.STABLE, installed(1), emptyList())
        assertNull(decision.release)
        assertEquals(UpdateOutcome.DISABLED_OR_EMPTY, decision.outcome)
    }

    @Test
    fun picksTheHighestVersionCode() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(1),
            listOf(release("0.2.0", 2), release("0.4.0", 4), release("0.3.0", 3)),
        )
        assertEquals("0.4.0", decision.release?.versionName)
        assertEquals(UpdateOutcome.AVAILABLE, decision.outcome)
    }

    @Test
    fun theListOrderDoesNotMatter() {
        // The workflow writes newest-first, but a hand-written or re-sorted
        // manifest must not change which build is chosen.
        val ascending = listOf(release("0.2.0", 2), release("0.3.0", 3), release("0.4.0", 4))
        val descending = ascending.reversed()

        assertEquals(
            UpdateSelector.select(UpdateChannel.STABLE, installed(1), ascending).release,
            UpdateSelector.select(UpdateChannel.STABLE, installed(1), descending).release,
        )
    }

    @Test
    fun upToDateWhenNothingIsNewer() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(5, "0.5.0"),
            listOf(release("0.5.0", 5), release("0.4.0", 4)),
        )
        assertNull(decision.release)
        assertEquals(UpdateOutcome.UP_TO_DATE, decision.outcome)
    }

    @Test
    fun equalVersionCodesAreNotAnUpgrade() {
        // Android refuses to re-install the same versionCode, so offering it
        // would produce a failed install the user cannot act on.
        assertFalse(UpdateSelector.isNewer(release("0.5.0", 5), installed(5)))
        assertTrue(UpdateSelector.isNewer(release("0.5.1", 6), installed(5)))
        assertFalse(UpdateSelector.isNewer(release("0.4.0", 4), installed(5)))
    }

    @Test
    fun theVersionCodeBeatsAMisleadingName() {
        // The case that motivates the whole rule: a nightly whose name sorts
        // *below* the installed release but whose code is higher. It is a real
        // upgrade and must be offered on the nightly channel.
        val nightly = release("0.1.0-nightly.9", 9, prerelease = true)
        val decision = UpdateSelector.select(
            UpdateChannel.NIGHTLY,
            installed(5, "0.5.0"),
            listOf(nightly),
        )
        assertEquals(nightly, decision.release)
    }

    @Test
    fun aHigherNameWithALowerCodeIsNeverOffered() {
        // The mirror image, and the reason the name is not the primary key:
        // installing this would fail with INSTALL_FAILED_VERSION_DOWNGRADE.
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(5, "0.5.0"),
            listOf(release("9.9.9", 4)),
        )
        assertNull(decision.release)
        assertEquals(UpdateOutcome.UP_TO_DATE, decision.outcome)
    }

    @Test
    fun theStableChannelSkipsPrereleases() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(1),
            listOf(
                release("0.3.0-nightly.3", 3, prerelease = true),
                release("0.2.0", 2, prerelease = false),
            ),
        )
        assertEquals("0.2.0", decision.release?.versionName)
    }

    @Test
    fun theNightlyChannelTakesTheHighestCodeIncludingPrereleases() {
        val decision = UpdateSelector.select(
            UpdateChannel.NIGHTLY,
            installed(1),
            listOf(
                release("0.3.0-nightly.3", 3, prerelease = true),
                release("0.2.0", 2, prerelease = false),
            ),
        )
        assertEquals("0.3.0-nightly.3", decision.release?.versionName)
    }

    @Test
    fun aStableChannelWithOnlyPrereleasesSaysSo() {
        // Distinct from "up to date": the user asked for stable builds and there
        // are none, which is worth saying rather than implying they are current.
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(1),
            listOf(release("0.3.0-nightly.3", 3, prerelease = true)),
        )
        assertNull(decision.release)
        assertEquals(UpdateOutcome.STABLE_ONLY_PRERELEASES, decision.outcome)
    }

    @Test
    fun fallsBackToTheNameWhenNoCodeIsPublished() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(0, "0.1.0"),
            listOf(release("0.2.0", null)),
        )
        assertEquals("0.2.0", decision.release?.versionName)
    }

    @Test
    fun refusesToGuessWhenTheNameIsNotAVersion() {
        // No code and an unparseable name means "cannot tell". Guessing here
        // would offer an install that the package manager then rejects.
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(0, "nightly"),
            listOf(release("nightly", null)),
        )
        assertNull(decision.release)
        assertEquals(UpdateOutcome.NO_INSTALLABLE_BUILD, decision.outcome)
    }

    @Test
    fun anUnparseableCandidateAgainstAParseableInstalledIsNotInstallable() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(0, "0.1.0"),
            listOf(release("nightly", null)),
        )
        assertNull(decision.release)
        assertEquals(UpdateOutcome.NO_INSTALLABLE_BUILD, decision.outcome)
    }

    @Test
    fun tiesOnVersionCodeAreBrokenByNameAndAreStable() {
        // Two builds re-cut under one code. The answer must be the same on every
        // run, or the UI flickers between two download URLs.
        val a = release("0.2.0", 2, url = "https://example.com/a.apk")
        val b = release("0.2.1", 2, url = "https://example.com/b.apk")

        val forward = UpdateSelector.select(UpdateChannel.STABLE, installed(1), listOf(a, b))
        val backward = UpdateSelector.select(UpdateChannel.STABLE, installed(1), listOf(b, a))
        assertEquals(forward.release, backward.release)
        assertEquals("0.2.1", forward.release?.versionName)
    }

    @Test
    fun aManifestWithAMixOfGoodAndBadEntriesStillOffersTheBestGoodOne() {
        val decision = UpdateSelector.select(
            UpdateChannel.STABLE,
            installed(1),
            listOf(
                release("nightly", null),
                release("0.2.0", 2),
                release("0.1.0-nightly.5", 5, prerelease = true),
            ),
        )
        assertEquals("0.2.0", decision.release?.versionName)
    }
}
