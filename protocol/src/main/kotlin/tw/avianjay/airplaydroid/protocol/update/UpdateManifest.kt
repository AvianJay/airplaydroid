package tw.avianjay.airplaydroid.protocol.update

/**
 * One installable build, as the release workflow publishes it.
 *
 * [versionCode] is what decides "newer", and it is nullable only so a
 * hand-written manifest still parses; a manifest written by this project's own
 * workflow always carries it. When it is absent, [UpdateSelector] falls back to
 * [versionName] rather than refusing to update.
 *
 * [sha256] is checked against the downloaded file before the installer is shown.
 * It is optional because a manifest may omit it, but when present a mismatch is
 * fatal: a corrupt or substituted APK must never reach the installer.
 */
data class UpdateRelease(
    val versionName: String,
    val versionCode: Int?,
    val prerelease: Boolean,
    val apkUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val notes: String? = null,
    val publishedAt: String? = null,
) {
    /** For sorting, and for the "installed vs available" line in the UI. */
    val version: AppVersion? get() = AppVersion.parse(versionName)

    /**
     * A human-readable size, or null when the manifest did not state one. Kept
     * here rather than in the UI so the update row and the download progress
     * cannot format the same number two different ways.
     */
    fun sizeLabel(): String? = sizeBytes?.takeIf { it > 0 }?.let { bytes ->
        when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "%.0f kB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * The `update.json` a release carries.
 *
 * Shape, as written by `.github/workflows/build.yml` and `release.yml`:
 *
 * ```json
 * {
 *   "schema": 1,
 *   "releases": [
 *     {
 *       "versionName": "0.2.0",
 *       "versionCode": 42,
 *       "prerelease": false,
 *       "apkUrl": "https://github.com/.../AirPlayDroid-0.2.0.apk",
 *       "sha256": "…64 hex…",
 *       "sizeBytes": 10846878,
 *       "notes": "feat: add an updater",
 *       "publishedAt": "2026-09-25T17:33:52Z"
 *     }
 *   ]
 * }
 * ```
 *
 * It is a *list* even though each release currently publishes only itself. That
 * keeps the door open for a channel manifest that aggregates several builds
 * without a format change, and [UpdateSelector] already picks the newest rather
 * than trusting the order.
 */
object UpdateManifest {

    class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @throws FormatException when [text] is not JSON, is not an object, or has
     *   no `releases` array. A single malformed *entry* is skipped instead: one
     *   bad build in a list of good ones must not hide all of them.
     */
    fun parse(text: String): List<UpdateRelease> {
        val root = try {
            Json.parse(text)
        } catch (e: Json.FormatException) {
            throw FormatException("update manifest is not valid JSON: ${e.message}", e)
        }

        val obj = root.asObject()
            ?: throw FormatException("update manifest must be a JSON object")

        val releases = obj["releases"]?.asArray()
            ?: throw FormatException("update manifest has no \"releases\" array")

        return releases.mapNotNull(::readRelease)
    }

    /** Null for an entry that could never be installed, so it is dropped. */
    private fun readRelease(value: JsonValue): UpdateRelease? {
        val entry = value.asObject() ?: return null
        val versionName = entry["versionName"].asString() ?: return null
        val apkUrl = entry["apkUrl"].asString() ?: return null
        // Only https: an updater that would fetch an APK over cleartext can be
        // pointed at a different binary by anyone on the network path.
        if (!apkUrl.startsWith("https://")) return null

        return UpdateRelease(
            versionName = versionName,
            versionCode = entry["versionCode"].asInt(),
            // Defaults to "not a pre-release", so a manifest that forgets the
            // flag does not hide a stable build from the stable channel. The
            // failure mode is the safe one: offering an update that exists.
            prerelease = entry["prerelease"].asBool() ?: false,
            apkUrl = apkUrl,
            sha256 = entry["sha256"].asString()?.lowercase()?.takeIf { it.length == 64 },
            sizeBytes = entry["sizeBytes"].asLong()?.takeIf { it > 0 },
            notes = entry["notes"].asString(),
            publishedAt = entry["publishedAt"].asString(),
        )
    }
}
