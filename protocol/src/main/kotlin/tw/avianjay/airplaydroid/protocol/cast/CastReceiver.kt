package tw.avianjay.airplaydroid.protocol.cast

import tw.avianjay.airplaydroid.protocol.update.Json
import tw.avianjay.airplaydroid.protocol.update.JsonValue
import tw.avianjay.airplaydroid.protocol.update.asArray
import tw.avianjay.airplaydroid.protocol.update.asBool
import tw.avianjay.airplaydroid.protocol.update.asDouble
import tw.avianjay.airplaydroid.protocol.update.asInt
import tw.avianjay.airplaydroid.protocol.update.asLong
import tw.avianjay.airplaydroid.protocol.update.asObject
import tw.avianjay.airplaydroid.protocol.update.asString
import java.util.UUID

/** Who the receiver is, as a sender's device list shows it. */
data class CastDeviceInfo(
    /** 32 hex digits, stable across restarts: senders key their device lists on it. */
    val id: String,
    val friendlyName: String,
    val model: String = "Chromecast",
) {
    /** The mDNS instance name. Real devices use `Chromecast-<id>`; the id is what makes it unique. */
    val serviceName: String get() = "AirPlayDroid-$id"

    /**
     * The `_googlecast._tcp` TXT record, in the keys a real Chromecast publishes.
     *
     * `ca` 4101 is the capability word of a video Chromecast (video out, audio
     * out); VLC lists a renderer as video-capable only from bit 0 of it. `st` 0
     * means idle. `fn` is what senders show, cut to fit a TXT string.
     */
    fun txtRecord(): Map<String, String> = linkedMapOf(
        "id" to id,
        "cd" to id.uppercase(),
        "rm" to "",
        "ve" to "05",
        "md" to model,
        "ic" to "/setup/icon.png",
        "fn" to friendlyName.take(MAX_NAME_BYTES_APPROX),
        "ca" to "4101",
        "st" to "0",
        "bs" to "FA8F" + id.take(8).uppercase(),
        "nf" to "1",
        "rs" to "",
    )

    companion object {
        /** A TXT string holds 255 bytes including `fn=`; this leaves room for multi-byte names. */
        private const val MAX_NAME_BYTES_APPROX = 60

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

/** What a sender asked to play, reduced to what an AirPlay receiver can use. */
data class CastLoadRequest(
    /** Always http or https: anything else is refused before it gets here. */
    val url: String,
    val contentType: String?,
    /** `BUFFERED`, `LIVE` or `NONE`, as the sender declared it. */
    val streamType: String?,
    val title: String?,
    val subtitle: String?,
    val startSeconds: Double,
    val autoplay: Boolean,
    val durationSeconds: Double?,
)

enum class CastPlayerState { IDLE, BUFFERING, PLAYING, PAUSED }

enum class CastIdleReason { CANCELLED, INTERRUPTED, FINISHED, ERROR }

/**
 * The app's half of a cast: what to do with each command a sender sends.
 *
 * Called on a connection's reader thread, never while the receiver holds its
 * lock, so an implementation may call straight back into [CastReceiver]. It
 * must not block for long: the same thread answers that sender's heartbeat.
 */
interface CastPlayer {
    /** A sender launched a receiver app: a cast is starting, nothing to play yet. */
    fun onSessionStarted(appId: String)
    fun onLoad(request: CastLoadRequest)
    fun onPlay()
    fun onPause()
    fun onSeek(positionSeconds: Double)
    /** The media stopped; the session stays, and another LOAD may follow. */
    fun onStop()
    /** The whole session ended: a sender stopped it, or another app replaced it. */
    fun onSessionEnded()
    fun onVolume(level: Double, muted: Boolean) = Unit
}

/** One sender's TCP connection, as the receiver writes to it. Implementations serialise [send]. */
interface CastChannel {
    fun send(message: CastMessage)
}

/**
 * The Cast v2 receiver protocol: the platform side (`receiver-0`) and one media
 * app, without the sockets.
 *
 * It speaks what a sender needs to cast a URL to a Chromecast -- the
 * connection, heartbeat, device-auth, receiver and media namespaces -- and
 * hands each media command to a [CastPlayer]. The app side reports playback
 * back through [updateMedia], which is turned into `MEDIA_STATUS` for every
 * connected sender.
 *
 * Any receiver app a sender launches is accepted and run as a media player:
 * most senders launch the Default Media Receiver (`CC1AD845`), and apps with
 * their own receiver still load media over the same media namespace. What
 * cannot be played -- a LOAD whose content is not an http(s) URL, the usual
 * sign of an app with its own protocol -- is refused with `LOAD_FAILED`.
 *
 * Thread-safe. Each message is decided under one lock; replies are sent and
 * [CastPlayer] is called after it is released, so a slow socket or a player
 * that calls back in cannot deadlock the receiver.
 */
class CastReceiver(
    private val identity: CastIdentity,
    private val player: CastPlayer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    private class Session(
        val appId: String,
        val sessionId: String,
        val transportId: String,
    ) {
        val displayName: String get() = KNOWN_APPS[appId] ?: appId
    }

    private class Media(
        val mediaSessionId: Int,
        /** The sender's own `media` object, echoed back in every status as real receivers do. */
        val description: JsonValue.Obj,
        val title: String?,
        var state: CastPlayerState,
        var position: Double,
        var positionAt: Long,
        var duration: Double?,
        var idleReason: CastIdleReason? = null,
    )

    private val lock = Any()

    /** Every virtual connection a sender opened with CONNECT: (sender id, receiver-side id). */
    private val connections = HashMap<CastChannel, MutableSet<Pair<String, String>>>()

    private var session: Session? = null
    private var media: Media? = null
    private var volumeLevel = 1.0
    private var muted = false
    private var nextTransport = 1
    private var nextMediaSessionId = 1

    /** Work decided under [lock] and carried out after it is released. */
    private class Effects {
        val messages = ArrayList<Pair<CastChannel, CastMessage>>()
        val calls = ArrayList<() -> Unit>()
    }

    val sessionActive: Boolean get() = synchronized(lock) { session != null }

    fun onMessage(channel: CastChannel, message: CastMessage) {
        val effects = Effects()
        synchronized(lock) {
            when (message.namespace) {
                NS_CONNECTION -> onConnection(channel, message)
                NS_HEARTBEAT -> onHeartbeat(channel, message, effects)
                NS_DEVICE_AUTH -> onDeviceAuth(channel, message, effects)
                NS_RECEIVER -> json(message)?.let { onReceiver(channel, message, it, effects) }
                NS_MEDIA -> json(message)?.let { onMedia(channel, message, it, effects) }
                // Custom namespaces of apps with their own receiver: nothing here speaks them.
                else -> log("ignored ${message.namespace} from ${message.sourceId}")
            }
        }
        run(effects)
    }

    /**
     * A sender's connection is gone. When it was the last one and nothing is
     * playing, the session ends with it: a sender that was killed or left the
     * network never sends STOP, and a receiver app with no media and nobody to
     * control it has no reason to stay up. Media that is playing keeps the
     * session, as it does on a Chromecast, until it ends by itself.
     */
    fun onChannelClosed(channel: CastChannel) {
        val effects = Effects()
        synchronized(lock) {
            connections.remove(channel)
            endIfAbandoned(effects)
        }
        run(effects)
    }

    /**
     * Playback as the app sees it. Broadcast only when it tells a sender
     * something new -- a state change, a first duration, or a position that has
     * drifted from where the sender's own extrapolation would put it -- because
     * senders interpolate between statuses and real receivers stay quiet too.
     */
    fun updateMedia(
        state: CastPlayerState,
        positionSeconds: Double? = null,
        durationSeconds: Double? = null,
        idleReason: CastIdleReason? = null,
    ) {
        val effects = Effects()
        synchronized(lock) {
            val current = media ?: return
            val now = clock()
            val expected = positionOf(current, now)
            val changed = state != current.state ||
                (durationSeconds != null && current.duration == null) ||
                (positionSeconds != null && kotlin.math.abs(positionSeconds - expected) > DRIFT_SECONDS)
            current.state = state
            current.idleReason = idleReason.takeIf { state == CastPlayerState.IDLE }
            if (positionSeconds != null) {
                current.position = positionSeconds
                current.positionAt = now
            } else if (state != CastPlayerState.PLAYING) {
                current.position = expected
                current.positionAt = now
            }
            if (durationSeconds != null && durationSeconds > 0) current.duration = durationSeconds
            if (changed) broadcastMediaStatus(effects)
            if (state == CastPlayerState.IDLE) {
                media = null
                endIfAbandoned(effects)
            }
        }
        run(effects)
    }

    /**
     * Ends the session from the app's side -- the user cancelled the cast.
     * [CastPlayer.onSessionEnded] is not called: the app is the one ending it.
     */
    fun endSession() {
        val effects = Effects()
        synchronized(lock) { endSessionLocked(effects, CastIdleReason.CANCELLED) }
        run(effects)
    }

    // ------------------------------------------------------------ namespaces

    private fun onConnection(channel: CastChannel, message: CastMessage) {
        val type = json(message)?.get("type").asString()
        val link = message.sourceId to message.destinationId
        when (type) {
            "CONNECT" -> connections.getOrPut(channel) { HashSet() } += link
            "CLOSE" -> connections[channel]?.remove(link)
        }
    }

    private fun onHeartbeat(channel: CastChannel, message: CastMessage, effects: Effects) {
        if (json(message)?.get("type").asString() == "PING") {
            effects.messages += channel to reply(message, NS_HEARTBEAT, Json.obj("type" to "PONG"))
        }
    }

    private fun onDeviceAuth(channel: CastChannel, message: CastMessage, effects: Effects) {
        val challenge = message.payloadBinary?.let { runCatching { DeviceAuth.parseChallenge(it) }.getOrNull() }
            ?: return log("device auth: not a challenge")
        val certificate = identity.certificateDer
        val signature = identity.sign(DeviceAuth.signedData(challenge, certificate), challenge.hashAlgorithm)
        effects.messages += channel to CastMessage(
            sourceId = message.destinationId,
            destinationId = message.sourceId,
            namespace = NS_DEVICE_AUTH,
            payloadBinary = DeviceAuth.response(challenge, signature, certificate),
        )
    }

    private fun onReceiver(channel: CastChannel, message: CastMessage, payload: JsonValue.Obj, effects: Effects) {
        val requestId = payload["requestId"].asLong() ?: 0
        when (val type = payload["type"].asString()) {
            "GET_STATUS" -> effects.messages += channel to reply(message, NS_RECEIVER, receiverStatus(requestId))

            "LAUNCH" -> {
                val appId = payload["appId"].asString()
                if (appId == null) {
                    effects.messages += channel to reply(
                        message, NS_RECEIVER,
                        Json.obj("type" to "LAUNCH_ERROR", "requestId" to requestId, "reason" to "NOT_FOUND"),
                    )
                    return
                }
                // Launching the app that already runs joins its session, as on a
                // real receiver; any other app replaces it.
                if (session?.appId != appId) {
                    endSessionLocked(effects, CastIdleReason.INTERRUPTED, notifyPlayer = session != null)
                    val started = Session(appId, UUID.randomUUID().toString(), "web-${nextTransport++}")
                    session = started
                    log("launch $appId as ${started.transportId}")
                    effects.calls += { player.onSessionStarted(appId) }
                }
                effects.messages += channel to reply(message, NS_RECEIVER, receiverStatus(requestId))
                broadcastReceiverStatus(effects, except = channel)
            }

            "STOP" -> {
                val requested = payload["sessionId"].asString()
                if (session != null && (requested == null || requested == session?.sessionId)) {
                    endSessionLocked(effects, CastIdleReason.CANCELLED, notifyPlayer = true)
                }
                effects.messages += channel to reply(message, NS_RECEIVER, receiverStatus(requestId))
            }

            "SET_VOLUME" -> {
                applyVolume(payload, effects)
                effects.messages += channel to reply(message, NS_RECEIVER, receiverStatus(requestId))
                broadcastReceiverStatus(effects, except = channel)
            }

            "GET_APP_AVAILABILITY" -> {
                val apps = payload["appId"].asArray().orEmpty().mapNotNull { it.asString() }
                effects.messages += channel to reply(
                    message, NS_RECEIVER,
                    Json.obj(
                        "requestId" to requestId,
                        "responseType" to "GET_APP_AVAILABILITY",
                        "availability" to apps.associateWith { "APP_AVAILABLE" },
                    ),
                )
            }

            else -> effects.messages += channel to invalid(message, NS_RECEIVER, requestId, type)
        }
    }

    private fun onMedia(channel: CastChannel, message: CastMessage, payload: JsonValue.Obj, effects: Effects) {
        val requestId = payload["requestId"].asLong() ?: 0
        val type = payload["type"].asString()
        val current = session
        if (current == null || message.destinationId != current.transportId) {
            effects.messages += channel to invalid(message, NS_MEDIA, requestId, type, "INVALID_PLAYER_STATE")
            return
        }
        when (type) {
            "GET_STATUS" -> effects.messages += channel to reply(message, NS_MEDIA, mediaStatus(requestId))

            "LOAD" -> load(channel, message, requestId, payload["media"].asObject(), payload, effects)

            // A queue of one is what most senders mean by it; later items are not played.
            "QUEUE_LOAD" -> {
                val items = payload["items"].asArray().orEmpty()
                val start = payload["startIndex"].asInt()?.coerceIn(0, (items.size - 1).coerceAtLeast(0)) ?: 0
                val item = items.getOrNull(start).asObject()
                load(channel, message, requestId, item?.get("media").asObject(), item ?: payload, effects)
            }

            "PLAY", "PAUSE", "SEEK", "STOP" -> {
                val playing = media
                if (playing == null) {
                    effects.messages += channel to invalid(message, NS_MEDIA, requestId, type, "INVALID_MEDIA_SESSION_ID")
                    return
                }
                val now = clock()
                when (type) {
                    "PLAY" -> {
                        playing.position = positionOf(playing, now)
                        playing.positionAt = now
                        if (playing.state == CastPlayerState.PAUSED) playing.state = CastPlayerState.PLAYING
                        effects.calls += player::onPlay
                    }
                    "PAUSE" -> {
                        playing.position = positionOf(playing, now)
                        playing.positionAt = now
                        if (playing.state == CastPlayerState.PLAYING) playing.state = CastPlayerState.PAUSED
                        effects.calls += player::onPause
                    }
                    "SEEK" -> {
                        val target = payload["currentTime"].asDouble()
                            ?: payload["relativeTime"].asDouble()?.let { positionOf(playing, now) + it }
                            ?: positionOf(playing, now)
                        playing.position = target.coerceAtLeast(0.0)
                        playing.positionAt = now
                        val seconds = playing.position
                        effects.calls += { player.onSeek(seconds) }
                    }
                    "STOP" -> {
                        playing.state = CastPlayerState.IDLE
                        playing.idleReason = CastIdleReason.CANCELLED
                        effects.calls += player::onStop
                    }
                }
                effects.messages += channel to reply(message, NS_MEDIA, mediaStatus(requestId))
                broadcastMediaStatus(effects, except = channel)
                if (playing.state == CastPlayerState.IDLE) media = null
            }

            "SET_VOLUME" -> {
                applyVolume(payload, effects)
                effects.messages += channel to reply(message, NS_MEDIA, mediaStatus(requestId))
            }

            else -> effects.messages += channel to invalid(message, NS_MEDIA, requestId, type)
        }
    }

    private fun load(
        channel: CastChannel,
        message: CastMessage,
        requestId: Long,
        description: JsonValue.Obj?,
        request: JsonValue.Obj,
        effects: Effects,
    ) {
        // contentUrl is the newer field; contentId is often the URL too, but for
        // an app with its own receiver it is an id only that app understands.
        val url = listOf(description?.get("contentUrl").asString(), description?.get("contentId").asString())
            .firstOrNull { it != null && isHttpUrl(it) }
        if (description == null || url == null) {
            log("load refused: no http(s) content in ${description?.let(Json::write)}")
            effects.messages += channel to reply(
                message, NS_MEDIA,
                Json.obj("type" to "LOAD_FAILED", "requestId" to requestId, "reason" to "INVALID_REQUEST"),
            )
            return
        }
        val metadata = description["metadata"].asObject()
        val start = request["currentTime"].asDouble() ?: request["startTime"].asDouble() ?: 0.0
        val duration = description["duration"].asDouble()?.takeIf { it > 0 }
        val loaded = Media(
            mediaSessionId = nextMediaSessionId++,
            description = description,
            title = metadata?.get("title").asString(),
            state = CastPlayerState.BUFFERING,
            position = start.coerceAtLeast(0.0),
            positionAt = clock(),
            duration = duration,
        )
        media = loaded
        val parsed = CastLoadRequest(
            url = url,
            contentType = description["contentType"].asString(),
            streamType = description["streamType"].asString(),
            title = loaded.title,
            subtitle = metadata?.get("subtitle").asString() ?: metadata?.get("artist").asString(),
            startSeconds = loaded.position,
            autoplay = request["autoplay"].asBool() ?: true,
            durationSeconds = duration,
        )
        log("load $url (${parsed.contentType})")
        effects.calls += { player.onLoad(parsed) }
        effects.messages += channel to reply(message, NS_MEDIA, mediaStatus(requestId))
        broadcastMediaStatus(effects, except = channel)
        broadcastReceiverStatus(effects)
    }

    private fun applyVolume(payload: JsonValue.Obj, effects: Effects) {
        val volume = payload["volume"].asObject() ?: return
        volume["level"].asDouble()?.let { volumeLevel = it.coerceIn(0.0, 1.0) }
        volume["muted"].asBool()?.let { muted = it }
        val level = volumeLevel
        val mute = muted
        effects.calls += { player.onVolume(level, mute) }
    }

    private fun endIfAbandoned(effects: Effects) {
        if (session != null && media == null && connections.values.all { it.isEmpty() }) {
            log("no sender left and nothing playing")
            endSessionLocked(effects, CastIdleReason.CANCELLED, notifyPlayer = true)
        }
    }

    /** Ends the session under [lock]: tells senders, and the player if [notifyPlayer]. */
    private fun endSessionLocked(effects: Effects, reason: CastIdleReason, notifyPlayer: Boolean = false) {
        val ended = session ?: return
        media?.let {
            it.state = CastPlayerState.IDLE
            it.idleReason = reason
            broadcastMediaStatus(effects)
        }
        media = null
        session = null
        log("session ${ended.transportId} ended ($reason)")
        // The app's virtual connections close with it, the way a real receiver
        // tells senders their app is gone.
        connections.forEach { (channel, links) ->
            links.filter { it.second == ended.transportId }.forEach { (sender, transport) ->
                effects.messages += channel to CastMessage(
                    transport, sender, NS_CONNECTION, payloadUtf8 = Json.write(Json.obj("type" to "CLOSE")),
                )
            }
            links.removeAll { it.second == ended.transportId }
        }
        broadcastReceiverStatus(effects)
        if (notifyPlayer) effects.calls += player::onSessionEnded
    }

    // -------------------------------------------------------------- statuses

    private fun receiverStatus(requestId: Long): JsonValue {
        val app = session?.let { current ->
            Json.obj(
                "appId" to current.appId,
                "universalAppId" to current.appId,
                "appType" to "WEB",
                "displayName" to current.displayName,
                "iconUrl" to "",
                "isIdleScreen" to false,
                "launchedFromCloud" to false,
                "namespaces" to listOf(Json.obj("name" to NS_MEDIA)),
                "sessionId" to current.sessionId,
                "statusText" to (media?.title ?: current.displayName),
                "transportId" to current.transportId,
            )
        }
        return Json.obj(
            "type" to "RECEIVER_STATUS",
            "requestId" to requestId,
            "status" to Json.obj(
                "applications" to listOfNotNull(app),
                "isActiveInput" to true,
                "isStandBy" to false,
                "volume" to Json.obj(
                    "controlType" to "attenuation",
                    "level" to volumeLevel,
                    "muted" to muted,
                    "stepInterval" to 0.05,
                ),
            ),
        )
    }

    private fun mediaStatus(requestId: Long): JsonValue {
        val entry = media?.let { current ->
            val now = clock()
            val description = current.duration?.let { duration ->
                JsonValue.Obj(current.description.entries + ("duration" to Json.number(duration)))
            } ?: current.description
            Json.obj(
                "mediaSessionId" to current.mediaSessionId,
                "playbackRate" to 1,
                "playerState" to current.state.name,
                "currentTime" to positionOf(current, now),
                "supportedMediaCommands" to SUPPORTED_MEDIA_COMMANDS,
                "volume" to Json.obj("level" to volumeLevel, "muted" to muted),
                "media" to description,
                "currentItemId" to 1,
                "repeatMode" to "REPEAT_OFF",
                "idleReason" to current.idleReason?.name,
            )
        }
        return Json.obj("type" to "MEDIA_STATUS", "requestId" to requestId, "status" to listOfNotNull(entry))
    }

    private fun positionOf(current: Media, now: Long): Double {
        if (current.state != CastPlayerState.PLAYING) return current.position
        val position = current.position + (now - current.positionAt).coerceAtLeast(0) / 1000.0
        return current.duration?.let { position.coerceAtMost(it) } ?: position
    }

    private fun broadcastReceiverStatus(effects: Effects, except: CastChannel? = null) =
        broadcast(effects, RECEIVER_ID, NS_RECEIVER, receiverStatus(0), except)

    private fun broadcastMediaStatus(effects: Effects, except: CastChannel? = null) {
        val transport = session?.transportId ?: return
        broadcast(effects, transport, NS_MEDIA, mediaStatus(0), except)
    }

    /** To every sender with a virtual connection to [from], addressed to all (`*`) as real receivers do. */
    private fun broadcast(effects: Effects, from: String, namespace: String, payload: JsonValue, except: CastChannel?) {
        val text = Json.write(payload)
        connections.forEach { (channel, links) ->
            if (channel !== except && links.any { it.second == from }) {
                effects.messages += channel to CastMessage(from, BROADCAST_ID, namespace, payloadUtf8 = text)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun run(effects: Effects) {
        effects.messages.forEach { (channel, message) ->
            runCatching { channel.send(message) }.onFailure { log("send failed: $it") }
        }
        effects.calls.forEach { call ->
            runCatching(call).onFailure { log("player failed: $it") }
        }
    }

    private fun reply(request: CastMessage, namespace: String, payload: JsonValue) = CastMessage(
        sourceId = request.destinationId,
        destinationId = request.sourceId,
        namespace = namespace,
        payloadUtf8 = Json.write(payload),
    )

    private fun invalid(
        request: CastMessage,
        namespace: String,
        requestId: Long,
        type: String?,
        reason: String = "INVALID_COMMAND",
    ): CastMessage {
        log("invalid request $type on $namespace ($reason)")
        return reply(
            request, namespace,
            Json.obj("type" to "INVALID_REQUEST", "requestId" to requestId, "reason" to reason),
        )
    }

    private fun json(message: CastMessage): JsonValue.Obj? {
        val text = message.payloadUtf8 ?: return null
        return runCatching { Json.parse(text).asObject() }
            .onFailure { log("unparseable payload on ${message.namespace}: $it") }
            .getOrNull()
    }

    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_DEVICE_AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        const val RECEIVER_ID = "receiver-0"
        const val BROADCAST_ID = "*"

        /** The app nearly every URL sender launches. */
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

        private val KNOWN_APPS = mapOf(DEFAULT_MEDIA_RECEIVER to "Default Media Receiver")

        /** PAUSE (1) | SEEK (2). Volume is not offered: an AirPlay video session has no volume to set. */
        private const val SUPPORTED_MEDIA_COMMANDS = 3

        /** How far a reported position may stray from the extrapolated one before senders are told. */
        private const val DRIFT_SECONDS = 2.0

        fun isHttpUrl(text: String): Boolean =
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }
}
