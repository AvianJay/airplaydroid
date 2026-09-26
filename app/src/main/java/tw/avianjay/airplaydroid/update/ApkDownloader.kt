package tw.avianjay.airplaydroid.update

import tw.avianjay.airplaydroid.protocol.update.UpdateRelease
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads an APK and checks it before anything is allowed to install it.
 *
 * Three rules, each of which exists because the alternative is a real failure:
 *
 *  - **The digest is checked when the manifest carries one.** A truncated
 *    download or a captive-portal HTML page saved as an APK would otherwise be
 *    handed to the installer, which reports it as a corrupt package -- a
 *    message that points at the app rather than at the download.
 *  - **The file is written next to its final name and renamed on success.** A
 *    half-written `AirPlayDroid.apk` left behind by a cancelled download would
 *    be offered to the installer on the next attempt.
 *  - **`Content-Length` is checked when the server sends one.** The digest
 *    covers this, but a size mismatch is a much clearer message.
 */
class ApkDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Downloads [release] into [directory].
     *
     * @param onProgress bytes written so far, and the total when the server
     *   stated one. Called on the calling thread, so callers that need the main
     *   thread must marshal it themselves.
     * @return the verified APK, ready to be handed to the installer.
     */
    fun download(
        release: UpdateRelease,
        directory: File,
        onProgress: (written: Long, total: Long?) -> Unit = { _, _ -> },
    ): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw Failure("could not create ${directory.absolutePath}")
        }

        // The name comes from the version, not the URL, so a manifest cannot
        // steer the write outside [directory] with a path in the file name.
        val target = File(directory, fileNameFor(release))
        val partial = File(directory, target.name + ".part")
        partial.delete()

        val connection = try {
            URL(release.apkUrl).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure("could not reach the download (${e.message})", e)
        }

        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            if (status !in 200..299) throw Failure("the download answered HTTP $status")

            val declaredLength = connection.contentLengthLong.takeIf { it > 0 }
            release.sizeBytes?.let { expected ->
                if (declaredLength != null && declaredLength != expected) {
                    throw Failure("the download is $declaredLength bytes, expected $expected")
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L

            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written, declaredLength)
                    }
                }
            }

            if (written == 0L) throw Failure("the download was empty")

            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    // Do not leave a bad file where the next attempt might find it.
                    partial.delete()
                    throw Failure("the downloaded file failed its checksum")
                }
            }

            // Atomic-ish: the final name only ever exists once the bytes are
            // complete, so an interrupted run cannot be mistaken for a good one.
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw Failure("could not save the download to ${target.absolutePath}")
            }
            return target
        } catch (e: Failure) {
            partial.delete()
            throw e
        } catch (e: IOException) {
            partial.delete()
            throw Failure("the download failed (${e.message})", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A stable file name for [release].
     *
     * Only the last path segment of the URL is used, and anything that is not a
     * safe character is replaced, so a manifest cannot escape [download]'s
     * directory with `../` in a URL. Public so a caller can prune the downloads
     * directory without re-deriving the name and getting it subtly wrong.
     */
    fun fileNameFor(release: UpdateRelease): String {
        val fromUrl = release.apkUrl.substringAfterLast('/').substringBefore('?')
        val candidate = fromUrl.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "AirPlayDroid-${release.versionName}.apk"
        return candidate.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("")
            .take(96)
    }

    private companion object {
        const val USER_AGENT = "AirPlayDroid-Updater"
    }
}
