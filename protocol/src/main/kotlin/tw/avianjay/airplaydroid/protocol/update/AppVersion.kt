package tw.avianjay.airplaydroid.protocol.update

/**
 * A dotted numeric version -- the shape this project's `versionName` takes:
 * `0.1.0`, `v1.2.3`, or a nightly's `0.1.0-nightly.42.03a7e72`.
 *
 * Only the **leading dotted run of digits** is significant. Everything from the
 * first character that is not a digit or a dot is dropped, so a pre-release
 * suffix, a build tag or a commit hash never changes the ordering. That is
 * deliberate: `0.2.0-nightly.7` and `0.2.0` compare equal here, because which of
 * the two is newer is a question about *build numbers*, not about names -- see
 * [UpdateSelector], which answers it with `versionCode`.
 *
 * Trailing zeros are dropped, so `1.2` and `1.2.0` are the same version. A
 * receiver that compared them as different would offer an update that changes
 * nothing.
 *
 * There is no pre-release ordering (no `1.0.0-rc1 < 1.0.0`). Semver's rule is
 * the opposite of what an updater wants here: an APK is only installable when
 * its `versionCode` is higher, and that is the number this class is a fallback
 * for.
 */
@ConsistentCopyVisibility
data class AppVersion private constructor(val numbers: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until width) {
            val mine = numbers.getOrElse(index) { 0 }
            val theirs = other.numbers.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = numbers.joinToString(".")

    companion object {

        /** The version everything else is newer than; used as a sort fallback. */
        val ZERO = AppVersion(listOf(0))

        /**
         * Parses [text], or returns null when it carries no leading dotted
         * number at all -- `"nightly"` is a tag, not a version, and pretending
         * it is `0` would make every nightly look older than every release.
         */
        fun parse(text: String?): AppVersion? {
            val trimmed = text?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            val leading = LEADING_NUMBER.find(trimmed)?.value ?: return null

            val numbers = leading.split('.')
                .map { part -> part.toIntOrNull() ?: return null }
            if (numbers.isEmpty()) return null

            // Drop trailing zeros so `1.2` == `1.2.0`, but never drop the last
            // one: an empty component list would have no version to report.
            var end = numbers.size
            while (end > 1 && numbers[end - 1] == 0) end--
            return AppVersion(numbers.subList(0, end))
        }

        private val LEADING_NUMBER = Regex("""^\d+(?:\.\d+)*""")
    }
}
