package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The contract between the release workflow and the app.
 *
 * These fixtures are **transcriptions of what `.github/workflows/build.yml` and
 * `release.yml` actually emit**, not hand-written JSON. That distinction is the
 * whole point: the workflow builds its manifest with a shell heredoc, so a
 * quoting mistake there produces a manifest that looks fine in the Actions log
 * and is unreadable on the phone -- an updater that silently offers nothing,
 * which is indistinguishable from "you are up to date" and would never be
 * reported as a bug.
 *
 * If the workflow's heredoc changes, this test must change with it.
 */
class WorkflowManifestContractTest {

    /** What `build.yml`'s "Write the update manifest" step produces for a nightly. */
    private val nightlyManifest = """
        {
          "schema": 1,
          "releases": [
            {
              "versionName": "0.1.0-nightly.29.03a7e72",
              "versionCode": 29,
              "prerelease": true,
              "apkUrl": "https://github.com/AvianJay/airplaydroid/releases/download/nightly/AirPlayDroid-nightly.apk",
              "sha256": "b60f13946c5db783b18db5e1a003e1f0a4a41bc761b81392e982ac008dc9bd9b",
              "sizeBytes": 10846878,
              "notes": "feat: add an in-app updater",
              "publishedAt": "2026-09-25T17:33:52Z"
            }
          ]
        }
    """.trimIndent()

    /** What `release.yml` produces for a tagged release. */
    private val stableManifest = """
        {
          "schema": 1,
          "releases": [
            {
              "versionName": "0.2.0",
              "versionCode": 41,
              "prerelease": false,
              "apkUrl": "https://github.com/AvianJay/airplaydroid/releases/download/v0.2.0/AirPlayDroid-0.2.0.apk",
              "sha256": "0000000000000000000000000000000000000000000000000000000000000001",
              "sizeBytes": 10900000,
              "notes": "release: 0.2.0",
              "publishedAt": "2026-10-01T00:00:00Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun theNightlyManifestIsReadableAndIsAPreRelease() {
        val release = UpdateManifest.parse(nightlyManifest).single()
        assertEquals("0.1.0-nightly.29.03a7e72", release.versionName)
        assertEquals(29, release.versionCode)
        assertTrue(release.prerelease, "the nightly must be flagged as a pre-release")
        assertNotNull(release.sha256)
    }

    @Test
    fun theStableManifestIsReadableAndIsNotAPreRelease() {
        val release = UpdateManifest.parse(stableManifest).single()
        assertEquals("0.2.0", release.versionName)
        assertEquals(41, release.versionCode)
        assertTrue(!release.prerelease)
    }

    @Test
    fun aNightlyIsOfferedOnlyOnTheNightlyChannel() {
        val nightly = UpdateManifest.parse(nightlyManifest)

        // Installed is an older build on the same shared commit-count sequence.
        val installed = InstalledVersion(versionCode = 20, versionName = "0.1.0")

        val onNightly = UpdateSelector.select(UpdateChannel.NIGHTLY, installed, nightly)
        assertTrue(onNightly.available, "the nightly channel must offer the nightly")

        val onStable = UpdateSelector.select(UpdateChannel.STABLE, installed, nightly)
        assertTrue(!onStable.available, "the stable channel must not offer a pre-release")
        assertEquals(UpdateOutcome.STABLE_ONLY_PRERELEASES, onStable.outcome)
    }

    @Test
    fun theStableReleaseIsOfferedOnBothChannels() {
        val stable = UpdateManifest.parse(stableManifest)
        val installed = InstalledVersion(versionCode = 20, versionName = "0.1.0")

        assertTrue(UpdateSelector.select(UpdateChannel.STABLE, installed, stable).available)
        assertTrue(UpdateSelector.select(UpdateChannel.NIGHTLY, installed, stable).available)
    }

    /**
     * The reason both workflows derive `versionCode` from the commit count: it is
     * one shared sequence, so a user can move between channels in either
     * direction without ever being handed a build Android refuses to install.
     *
     * A scheme where a release's code came from its name (0.2.0 -> 10200) would
     * fail this: someone on nightly build 20000 would be told 0.2.0 is *older*.
     */
    @Test
    fun movingBetweenChannelsIsAlwaysAnUpgrade() {
        val nightlyAt = { commits: Int ->
            UpdateRelease(
                versionName = "0.1.0-nightly.$commits.abcdef0",
                versionCode = commits,
                prerelease = true,
                apkUrl = "https://example.com/nightly.apk",
            )
        }
        val stableAt = { commits: Int ->
            UpdateRelease(
                versionName = "0.2.0",
                versionCode = commits,
                prerelease = false,
                apkUrl = "https://example.com/stable.apk",
            )
        }

        // A nightly from a later commit than the release: offered as an upgrade
        // even though its *name* sorts below "0.2.0".
        assertTrue(UpdateSelector.isNewer(nightlyAt(50), InstalledVersion(41, "0.2.0")))

        // And the other way: the release cut after that nightly is an upgrade.
        assertTrue(UpdateSelector.isNewer(stableAt(60), InstalledVersion(50, "0.1.0-nightly.50.abcdef0")))
    }

    @Test
    fun aBuildFromTheSameCommitIsNotAnUpgradeOnEitherChannel() {
        // The exact commit is both a nightly and (potentially) the release cut
        // from it. Re-installing it would fail, so neither channel offers it.
        val installed = InstalledVersion(versionCode = 41, versionName = "0.2.0")
        assertEquals(false, UpdateSelector.isNewer(UpdateManifest.parse(nightlyManifest).single().copy(versionCode = 41), installed))
        assertEquals(false, UpdateSelector.isNewer(UpdateManifest.parse(stableManifest).single(), installed))
    }
}
