package tw.avianjay.airplaydroid.protocol.update

/** The build currently installed, as `PackageInfo` reports it. */
data class InstalledVersion(
    val versionCode: Int,
    val versionName: String?,
) {
    val version: AppVersion? get() = AppVersion.parse(versionName)
}

/** Why [UpdateSelector.select] returned the release it did -- or nothing. */
enum class UpdateOutcome {
    /** A newer, installable build was found. */
    AVAILABLE,

    /** The channel is OFF, or the manifest listed nothing at all. */
    DISABLED_OR_EMPTY,

    /** Builds were listed, but none is newer than what is installed. */
    UP_TO_DATE,

    /** Only pre-releases were published, and this channel excludes them. */
    STABLE_ONLY_PRERELEASES,

    /**
     * Builds were listed and some look newer, but none carries a version the
     * package manager could install over this one. Reached when a manifest
     * omits `versionCode` *and* its `versionName` cannot be parsed, so there is
     * nothing to compare. Offering such a build would fail at install time.
     */
    NO_INSTALLABLE_BUILD,
}

data class UpdateDecision(
    val release: UpdateRelease?,
    val outcome: UpdateOutcome,
) {
    val available: Boolean get() = release != null
}

/**
 * Picks the one release an install should move to, from everything the manifest
 * lists.
 *
 * Two rules carry all the weight, and both exist because getting them wrong
 * produces an update that *looks* fine and then fails at install time:
 *
 * 1. **`versionCode` decides "newer", not the name.** Android refuses to
 *    install an APK whose `versionCode` is not higher than the installed one,
 *    so a comparison based on `versionName` can offer an update that the package
 *    manager then rejects with `INSTALL_FAILED_VERSION_DOWNGRADE`. The name is
 *    only a fallback for a manifest that carries no code at all.
 *
 * 2. **A pre-release is never offered on the stable channel.** The rolling
 *    `nightly` release is a pre-release, so `STABLE` skips it even though its
 *    `versionCode` is higher -- which is exactly the point of having channels.
 *
 * Ties are resolved toward the higher name, so a manifest listing two builds
 * under one code (a re-cut of the same version) still yields a stable answer
 * rather than whichever happened to come first in the list.
 */
object UpdateSelector {

    fun select(
        channel: UpdateChannel,
        installed: InstalledVersion,
        releases: List<UpdateRelease>,
    ): UpdateDecision {
        if (channel.isOff) return UpdateDecision(null, UpdateOutcome.DISABLED_OR_EMPTY)
        if (releases.isEmpty()) return UpdateDecision(null, UpdateOutcome.DISABLED_OR_EMPTY)

        val eligible = releases.filter { channel == UpdateChannel.NIGHTLY || !it.prerelease }
        if (eligible.isEmpty()) return UpdateDecision(null, UpdateOutcome.STABLE_ONLY_PRERELEASES)

        val newer = eligible.filter { isNewer(it, installed) }
        if (newer.isEmpty()) {
            // "Nothing newer" and "nothing comparable" are different problems for
            // the user: the first needs no action, the second needs the publisher
            // to fix the manifest.
            val comparable = eligible.any { isComparable(it, installed) }
            return UpdateDecision(
                null,
                if (comparable) UpdateOutcome.UP_TO_DATE else UpdateOutcome.NO_INSTALLABLE_BUILD,
            )
        }

        return UpdateDecision(newer.sortedWith(byNewest).first(), UpdateOutcome.AVAILABLE)
    }

    /**
     * Whether [candidate] is a build the package manager would accept as an
     * upgrade of [installed].
     *
     * When the candidate carries a code, the code is the entire answer. Note the
     * asymmetry: equal codes are **not** newer. Re-installing the same build
     * number is refused by Android, so offering it would only produce a failed
     * install the user cannot act on.
     */
    fun isNewer(candidate: UpdateRelease, installed: InstalledVersion): Boolean {
        val candidateCode = candidate.versionCode
        if (candidateCode != null) return candidateCode > installed.versionCode

        // No code published: fall back to the name. Deliberately strict, because
        // an unparseable name on either side means "cannot tell", and the safe
        // answer to that is "do not offer an update".
        val candidateVersion = candidate.version ?: return false
        val installedVersion = installed.version ?: return false
        return candidateVersion > installedVersion
    }

    /** Whether a comparison was possible at all, used only to word the outcome. */
    private fun isComparable(candidate: UpdateRelease, installed: InstalledVersion): Boolean =
        candidate.versionCode != null || (candidate.version != null && installed.version != null)

    /**
     * Newest first. Code when both have one, then name, then the version string
     * itself so the order is total and two identical entries cannot swap places
     * between runs (which would make the UI flicker between two URLs).
     */
    private val byNewest = compareByDescending<UpdateRelease> { it.versionCode ?: Int.MIN_VALUE }
        .thenByDescending { it.version ?: AppVersion.ZERO }
        .thenByDescending { it.versionName }
}
