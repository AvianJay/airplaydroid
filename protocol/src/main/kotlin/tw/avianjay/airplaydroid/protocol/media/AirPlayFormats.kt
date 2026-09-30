package tw.avianjay.airplaydroid.protocol.media

import java.net.InetAddress
import java.net.URI

/**
 * Which media an AirPlay receiver can fetch and play by itself, and what has to
 * go through the phone first.
 *
 * AirPlay video is AVFoundation's player: MP4/MOV/M4V with H.264 or HEVC, HLS,
 * MPEG-TS, and AAC/MP3/ALAC audio. A Chromecast plays a good deal more --
 * WebM with VP8/VP9, Matroska, Ogg, FLAC, DASH -- and Cast senders send those
 * freely, because the Cast receiver they expect would play them.
 */
object AirPlayFormats {

    enum class Route {
        /** Hand the URL to the receiver as it is. */
        DIRECT,
        /** The receiver cannot reach the URL (it names this phone's loopback); relay it unchanged. */
        PROXY,
        /** A format AirPlay does not play: convert it on the phone. */
        CONVERT,
        /** Adaptive formats the phone cannot convert either; handed over as they are, to likely fail. */
        UNSUPPORTED,
    }

    /**
     * Where [url] should go. [convert] is the user's switch: off, everything the
     * receiver can reach goes straight to it and the receiver decides.
     */
    fun route(url: String, contentType: String?, convert: Boolean): Route {
        val loopback = isLoopbackUrl(url)
        val kind = classify(url, contentType)
        return when {
            kind == Kind.ADAPTIVE_OTHER -> if (loopback) Route.PROXY else Route.UNSUPPORTED
            kind == Kind.FOREIGN && convert -> Route.CONVERT
            loopback -> Route.PROXY
            else -> Route.DIRECT
        }
    }

    enum class Kind { NATIVE, FOREIGN, ADAPTIVE_OTHER, UNKNOWN }

    fun classify(url: String, contentType: String?): Kind {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = runCatching { URI(url).path }.getOrNull().orEmpty()
            .substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when {
            type in ADAPTIVE_TYPES || extension in ADAPTIVE_EXTENSIONS -> Kind.ADAPTIVE_OTHER
            type in NATIVE_TYPES || extension in NATIVE_EXTENSIONS -> Kind.NATIVE
            type in FOREIGN_TYPES || extension in FOREIGN_EXTENSIONS -> Kind.FOREIGN
            // A type we do not recognise by name, but whose family AirPlay lacks.
            type.startsWith("video/") || type.startsWith("audio/") -> Kind.FOREIGN
            else -> Kind.UNKNOWN
        }
    }

    /** Whether [url] names this device's loopback, which no other device can fetch. */
    fun isLoopbackUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        // Only literal addresses: resolving a name here would be a DNS lookup on the caller's thread.
        if (!host.first().isDigit() && ':' !in host) return false
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    private val NATIVE_TYPES = setOf(
        "video/mp4", "video/quicktime", "video/x-m4v", "video/mp2t", "video/mpeg",
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac", "audio/x-m4a", "audio/x-aac", "audio/aacp",
    )
    private val NATIVE_EXTENSIONS = setOf("mp4", "m4v", "mov", "m3u8", "ts", "mp3", "m4a", "aac")

    private val FOREIGN_TYPES = setOf(
        "video/webm", "audio/webm", "video/x-matroska", "audio/x-matroska", "video/ogg", "audio/ogg",
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/opus", "video/x-msvideo", "video/3gpp",
    )
    private val FOREIGN_EXTENSIONS = setOf("webm", "mkv", "mka", "ogg", "oga", "ogv", "opus", "flac", "wav", "avi", "3gp")

    private val ADAPTIVE_TYPES = setOf("application/dash+xml", "application/vnd.ms-sstr+xml")
    private val ADAPTIVE_EXTENSIONS = setOf("mpd", "ism", "isml")
}
