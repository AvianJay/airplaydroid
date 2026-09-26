package tw.avianjay.airplaydroid.update

import tw.avianjay.airplaydroid.protocol.update.UpdateChannel
import tw.avianjay.airplaydroid.protocol.update.UpdateManifest
import tw.avianjay.airplaydroid.protocol.update.UpdateRelease
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the update manifest, and nothing else.
 *
 * Deliberately a manifest and not the GitHub Releases API. The API would work
 * unauthenticated, but it is rate limited to 60 requests an hour **per IP**, and
 * a phone behind a carrier NAT shares that budget with every other app on the
 * network. A static `update.json` attached to the release has no limit and no
 * token, and it is written by the same workflow that builds the APK, so it can
 * never describe a build that does not exist.
 *
 * The two channels use **different URL shapes**, and this is not cosmetic:
 *
 *  - stable is `releases/latest/download/update.json`, where GitHub resolves
 *    "latest" itself -- to the newest release that is *not* a pre-release;
 *  - nightly is `releases/download/nightly/update.json`, naming the rolling tag
 *    explicitly.
 *
 * There is no `releases/download/latest/...`; that path is a 404. And the
 * obvious single-URL approach does not work either: `releases/latest/download`
 * **excludes pre-releases**, so a nightly published as one is invisible through
 * it. Verified against this repository, where the only release is a pre-release
 * and the `latest` URL answers 404 while the explicit tag serves the asset.
 */
class UpdateClient(
    private val repository: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) {

    class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * The channel has published nothing yet.
     *
     * Separate from [Failure] because it is not an error: a repository whose
     * first stable release has not been tagged answers 404 on the `latest` URL,
     * and the nightly tag does not exist until the first nightly run. The UI
     * reports those as "nothing published", not as a broken check.
     */
    class NotPublished(url: String) : IOException("nothing has been published at $url")

    /**
     * The manifest URL for [channel].
     *
     * [UpdateChannel.NIGHTLY] reads the rolling `nightly` tag by name; anything
     * else reads GitHub's own `latest`, which already means "newest stable".
     */
    fun manifestUrl(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.NIGHTLY -> "https://github.com/$repository/releases/download/$NIGHTLY_TAG/$MANIFEST_NAME"
        else -> "https://github.com/$repository/releases/latest/download/$MANIFEST_NAME"
    }

    fun fetch(channel: UpdateChannel): List<UpdateRelease> {
        val url = manifestUrl(channel)
        val body = get(url)
        return try {
            UpdateManifest.parse(body)
        } catch (e: UpdateManifest.FormatException) {
            throw Failure("the update manifest could not be read (${e.message})", e)
        }
    }

    /**
     * A small text document over HTTPS.
     *
     * `Accept-Encoding: identity` is set on purpose. Android's
     * `HttpURLConnection` does not transparently gunzip, so asking for gzip would
     * hand this method compressed bytes to parse as JSON -- a failure that looks
     * like a malformed manifest rather than a transport mistake.
     */
    private fun get(url: String): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure("could not reach $url (${e.message})", e)
        }

        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                // No release on this channel yet -- see NotPublished.
                throw NotPublished(url)
            }
            if (status !in 200..299) {
                throw Failure("$url answered HTTP $status")
            }

            val stream = connection.inputStream
            stream.bufferedReader().use { it.readText() }
        } catch (e: Failure) {
            throw e
        } catch (e: NotPublished) {
            // Caught before IOException, or the generic handler below would
            // rewrap it as a Failure and the UI would report a broken check.
            throw e
        } catch (e: IOException) {
            throw Failure("could not read $url (${e.message})", e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** The rolling pre-release tag the nightly channel reads. */
        const val NIGHTLY_TAG = "nightly"

        const val MANIFEST_NAME = "update.json"

        /**
         * GitHub rejects requests without one. A plain curl-style agent is
         * honest about what this is and does not pretend to be a browser.
         */
        const val USER_AGENT = "AirPlayDroid-Updater"
    }
}
