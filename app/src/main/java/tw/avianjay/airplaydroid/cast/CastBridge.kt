package tw.avianjay.airplaydroid.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.discovery.NsdDeviceDiscovery
import tw.avianjay.airplaydroid.mirror.MirrorLog
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.playback.PlaybackUiState
import tw.avianjay.airplaydroid.protocol.AddressLookup
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.cast.CastIdleReason
import tw.avianjay.airplaydroid.protocol.cast.CastLoadRequest
import tw.avianjay.airplaydroid.protocol.cast.CastPlayer
import tw.avianjay.airplaydroid.protocol.cast.CastPlayerState
import tw.avianjay.airplaydroid.protocol.cast.CastReceiver
import tw.avianjay.airplaydroid.protocol.cast.LocalAddresses
import tw.avianjay.airplaydroid.protocol.media.AirPlayFormats
import tw.avianjay.airplaydroid.protocol.media.MediaHttpServer
import tw.avianjay.airplaydroid.settings.CastTarget
import tw.avianjay.airplaydroid.settings.SettingsStore

/** What the chooser and the notification show about the cast in progress. */
data class CastUiState(
    /** A sender has launched an app on this receiver. */
    val sessionActive: Boolean = false,
    /** Waiting for the user to pick the AirPlay receiver. */
    val choosing: Boolean = false,
    val title: String? = null,
    /** Where casts in this session go, once chosen. */
    val target: AirPlayDevice? = null,
    /** A LOAD is waiting for [target] to be chosen. */
    val mediaPending: Boolean = false,
    /** The media is being converted on the phone. */
    val converting: Boolean = false,
    /** The media is on the receiver. */
    val playing: Boolean = false,
    val error: String? = null,
)

/**
 * Turns what a Cast sender asks of this phone into AirPlay: the other half of
 * [CastReceiver], which speaks the Cast protocol and calls this for every media
 * command.
 *
 * A LOAD is sent one of three ways, decided by [AirPlayFormats.route]:
 *
 *  - **directly**: the AirPlay receiver fetches the sender's URL itself;
 *  - **through the phone's proxy**: the URL names this phone's loopback, which
 *    the receiver cannot reach, so [MediaHttpServer] relays it;
 *  - **converted**: the format is one AirPlay does not play, and a
 *    [MediaConverter] turns it into HLS that [MediaHttpServer] serves.
 *
 * The AirPlay side is [PlaybackController], the same session the picker's
 * **Play video URL…** drives, and playback status flows back from it to the
 * sender. Process-scoped, like the controllers, because the service that owns
 * the receiver and the chooser activity both reach it.
 *
 * Every step runs on one serial dispatcher, so the session's fields need no
 * lock; blocking work (opening a source, discovery) runs elsewhere and comes
 * back, checked against [generation] in case the session moved on meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("StaticFieldLeak")
object CastBridge : CastPlayer {

    /** What the service does for the bridge: the parts that need a Service or an Activity. */
    interface Host {
        /** Ask the user to pick a receiver: the chooser, or a notification when it cannot be shown. */
        fun askForTarget()
        /** The session or its playback changed: refresh the notification, maybe stop the service. */
        fun onCastChanged()
    }

    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    /**
     * The application context, never an activity's or the service's: [attach]
     * stores `applicationContext`, which lives as long as the process anyway --
     * hence the StaticFieldLeak suppression on this object. Kept rather than
     * passed in because the Cast callbacks arrive from a socket thread with no
     * context of their own.
     */
    private var context: Context? = null
    private var receiver: CastReceiver? = null
    private var media: MediaHttpServer? = null
    private var host: Host? = null
    private var watchJob: Job? = null

    /** Bumped when a session starts or ends; work from an older one is dropped. */
    private var generation = 0

    /**
     * Bumped by every hand-off and every stop. Opening a source to convert it
     * suspends, and a seek or a STOP can arrive meanwhile; the attempt that
     * resumes afterwards must see it was overtaken.
     */
    private var attempt = 0
    /** A hand-off reached [PlaybackController] and has not been stopped since. */
    private var handedOff = false

    private var target: AirPlayDevice? = null
    private var password: String? = null
    private var pendingLoad: CastLoadRequest? = null
    private var currentLoad: CastLoadRequest? = null

    private var converter: MediaConverter? = null
    private var publishedPath: String? = null
    /** Where the converted stream starts in the source, added to the receiver's position. */
    private var offsetSeconds = 0.0
    /** The URL handed to [PlaybackController], which is how its state is recognised as ours. */
    private var playbackUrl: String? = null
    private var sawPlayback = false
    private var lastPosition = 0.0
    private var lastDuration: Double? = null

    /** True while a cast needs the service to stay up, even with the app closed. */
    val busy: Boolean get() = _state.value.sessionActive || _state.value.playing

    // ------------------------------------------------------------- lifecycle

    fun attach(context: Context, receiver: CastReceiver, media: MediaHttpServer, host: Host) {
        scope.launch {
            this@CastBridge.context = context.applicationContext
            this@CastBridge.receiver = receiver
            this@CastBridge.media = media
            this@CastBridge.host = host
            MirrorLog.init(context)
            watchJob?.cancel()
            watchJob = scope.launch { PlaybackController.state.collect(::onPlayback) }
        }
    }

    fun detach() {
        scope.launch {
            endLocally()
            watchJob?.cancel()
            watchJob = null
            receiver = null
            media = null
            host = null
        }
    }

    // ------------------------------------------------------------ CastPlayer

    override fun onSessionStarted(appId: String) {
        scope.launch {
            val ctx = context ?: return@launch
            stopPlayback()
            val mine = ++generation
            target = null
            password = null
            pendingLoad = null
            currentLoad = null
            _state.value = CastUiState(sessionActive = true)
            log("session started by app $appId")
            host?.onCastChanged()

            val settings = SettingsStore(ctx).state.value.cast
            val saved = SettingsStore(ctx).castTarget()
            if (settings.askForDevice || saved == null) {
                askUser()
                return@launch
            }
            // Popup off: the last receiver, if it can be found in time.
            val device = resolveTarget(ctx, saved)
            if (mine != generation) return@launch
            if (device == null) {
                log("last receiver ${saved.name} not found; asking")
                _state.update { it.copy(error = ctx.getString(R.string.cast_error_target_missing, saved.name)) }
                askUser()
            } else {
                adopt(ctx, device, PairingStore(ctx).load(device.key)?.password)
            }
        }
    }

    override fun onLoad(request: CastLoadRequest) {
        scope.launch {
            pendingLoad = request
            _state.update { it.copy(title = request.title ?: it.title, mediaPending = target == null) }
            val ctx = context ?: return@launch
            if (target != null) startPlayback(ctx, request)
        }
    }

    override fun onPlay() {
        scope.launch { context?.let { PlaybackController.setPlaying(it, true) } }
    }

    override fun onPause() {
        scope.launch { context?.let { PlaybackController.setPlaying(it, false) } }
    }

    override fun onSeek(positionSeconds: Double) {
        scope.launch {
            val ctx = context ?: return@launch
            val load = currentLoad ?: return@launch
            // Not `converter != null`: a second seek can land while the first
            // one's converter is still opening its source.
            if (_state.value.converting) {
                // A converted stream is live, so the receiver cannot seek in it:
                // convert again from the new position.
                log("seek to $positionSeconds s: converting again from there")
                startPlayback(ctx, load, fromSeconds = positionSeconds)
            } else {
                PlaybackController.seekTo(ctx, positionSeconds)
            }
        }
    }

    override fun onStop() {
        scope.launch {
            stopPlayback()
            host?.onCastChanged()
        }
    }

    override fun onSessionEnded() {
        scope.launch {
            log("session ended by the sender")
            endLocally()
        }
    }

    // ----------------------------------------------------------- the chooser

    /** The user picked [device] in the chooser. [typedPassword] is its AirPlay password, if one was asked for. */
    fun chooseTarget(context: Context, device: AirPlayDevice, typedPassword: String?) {
        val appContext = context.applicationContext
        scope.launch {
            if (!_state.value.sessionActive) return@launch
            val secret = typedPassword?.takeIf { it.isNotEmpty() } ?: PairingStore(appContext).load(device.key)?.password
            adopt(appContext, device, secret)
        }
    }

    /** The user cancelled the cast: the sender is told its session is over. */
    fun cancel() {
        scope.launch {
            log("cancelled from the phone")
            receiver?.endSession()
            endLocally()
        }
    }

    // --------------------------------------------------------------- inside

    private suspend fun adopt(context: Context, device: AirPlayDevice, secret: String?) {
        target = device
        password = secret
        SettingsStore(context).setCastTarget(
            CastTarget(device.key, device.displayName, device.videoEndpoint?.host, device.videoEndpoint?.port)
        )
        // playing in the same step as the target: the chooser closes on a target
        // with nothing playing, and must not see the moment in between.
        _state.update {
            it.copy(choosing = false, target = device, error = null, mediaPending = false, playing = pendingLoad != null)
        }
        log("playing on ${device.displayName}")
        host?.onCastChanged()
        pendingLoad?.let { startPlayback(context, it) }
    }

    private fun askUser() {
        _state.update { it.copy(choosing = true) }
        host?.askForTarget()
    }

    /**
     * Hands [load] to the target, the way [AirPlayFormats.route] says.
     * [fromSeconds] overrides the LOAD's start, for a seek in a converted stream.
     */
    private suspend fun startPlayback(context: Context, load: CastLoadRequest, fromSeconds: Double? = null) {
        val device = target ?: return
        val mine = ++attempt
        stopMedia()
        // The previous hand-off's state is not ours any more: its converter is
        // gone, and the errors that follow from that must not end this one.
        playbackUrl = null
        currentLoad = load
        pendingLoad = null
        sawPlayback = false
        lastPosition = 0.0
        lastDuration = load.durationSeconds

        val settings = SettingsStore(context).state.value.cast
        val route = AirPlayFormats.route(load.url, load.contentType, settings.convertFormats)
        val start = fromSeconds ?: load.startSeconds
        log("load ${load.url} (${load.contentType}) -> $route")

        val url: String
        var startAt = start
        when (route) {
            AirPlayFormats.Route.DIRECT, AirPlayFormats.Route.UNSUPPORTED -> {
                url = load.url
                offsetSeconds = 0.0
            }
            AirPlayFormats.Route.PROXY -> {
                val server = media ?: return
                val path = server.publishProxy(load.url)
                publishedPath = path
                url = baseUrl(device, server) + path
                offsetSeconds = 0.0
            }
            AirPlayFormats.Route.CONVERT -> {
                val server = media ?: return
                _state.update { it.copy(converting = true, error = null) }
                val made = MediaConverter(load.url, (start * 1_000_000).toLong()) { log(it) }
                val opened = runCatching { withContext(Dispatchers.IO) { made.prepare() } }
                if (mine != attempt) {
                    made.stop()
                    return
                }
                val stream = opened.getOrElse { e ->
                    fail(context.getString(R.string.cast_error_convert, e.message ?: e.javaClass.simpleName))
                    return
                }
                converter = made
                lastDuration = made.durationSeconds ?: load.durationSeconds
                val path = server.publishHls(stream)
                publishedPath = path
                made.start { reason ->
                    scope.launch {
                        if (converter === made) fail(context.getString(R.string.cast_error_convert, reason))
                    }
                }
                url = baseUrl(device, server) + path
                offsetSeconds = start
                // The stream itself begins at the requested position.
                startAt = 0.0
            }
        }
        playbackUrl = url
        handedOff = true
        _state.update { it.copy(converting = route == AirPlayFormats.Route.CONVERT, playing = true, error = null) }
        host?.onCastChanged()
        PlaybackController.play(context, device, url, password, startAt)
    }

    /** Where [device] reaches this phone's media server: the address on the route to it. */
    private fun baseUrl(device: AirPlayDevice, server: MediaHttpServer): String {
        val local = device.videoEndpoint?.host?.let { LocalAddresses.facing(it) }
        val host = local?.let(LocalAddresses::urlHost) ?: "127.0.0.1"
        return "http://$host:${server.port}"
    }

    /** PlaybackController's state, turned into MEDIA_STATUS for the sender. */
    private fun onPlayback(playback: PlaybackUiState) {
        val cast = receiver ?: return
        val url = playbackUrl ?: return
        val ours = playback.url == url && playback.device?.key == target?.key
        if (!ours) {
            // Stopped from the picker, or replaced by another handoff there.
            if (sawPlayback && playback.device == null) {
                log("playback stopped outside the cast")
                cast.updateMedia(CastPlayerState.IDLE, idleReason = CastIdleReason.CANCELLED)
                stopPlayback()
                host?.onCastChanged()
            }
            return
        }
        playback.error?.let { message ->
            fail(message)
            return
        }
        if (playback.connecting || playback.awaitingPin) {
            cast.updateMedia(CastPlayerState.BUFFERING)
            return
        }
        if (!playback.connected) return
        sawPlayback = true
        val info = playback.info
        val position = info.positionSeconds?.let { offsetSeconds + it }
        val duration = if (converter != null) lastDuration else info.durationSeconds ?: lastDuration
        // An AirPlay receiver drops back to an empty /playback-info once the media ends.
        val ended = info.positionSeconds == null && info.durationSeconds == null &&
            lastDuration?.let { lastPosition >= it - END_TOLERANCE_S } == true
        if (ended) {
            log("media finished")
            cast.updateMedia(CastPlayerState.IDLE, idleReason = CastIdleReason.FINISHED)
            stopPlayback()
            host?.onCastChanged()
            return
        }
        position?.let { lastPosition = it }
        if (duration != null && duration > 0) lastDuration = duration
        val state = when {
            info.isPlaying && info.readyToPlay -> CastPlayerState.PLAYING
            info.readyToPlay -> CastPlayerState.PAUSED
            else -> CastPlayerState.BUFFERING
        }
        cast.updateMedia(state, position, duration?.takeIf { it > 0 })
    }

    private fun fail(message: String) {
        log("failed: $message")
        receiver?.updateMedia(CastPlayerState.IDLE, idleReason = CastIdleReason.ERROR)
        stopPlayback()
        _state.update { it.copy(error = message) }
        host?.onCastChanged()
    }

    /** Ends this phone's side of the session without telling the sender (it either knows, or [cancel] told it). */
    private fun endLocally() {
        generation++
        stopPlayback()
        target = null
        password = null
        pendingLoad = null
        _state.value = CastUiState()
        host?.onCastChanged()
    }

    private fun stopPlayback() {
        attempt++
        stopMedia()
        playbackUrl = null
        currentLoad = null
        sawPlayback = false
        if (handedOff) PlaybackController.stop()
        handedOff = false
        _state.update { it.copy(playing = false, converting = false) }
    }

    private fun stopMedia() {
        converter?.stop()
        converter = null
        publishedPath?.let { path -> media?.unpublish(path) }
        publishedPath = null
    }

    /**
     * The remembered receiver, found again: already listed, then a discovery
     * round, then the address it was last reached at (a receiver added by
     * address is never discovered).
     */
    private suspend fun resolveTarget(context: Context, saved: CastTarget): AirPlayDevice? {
        fun listed() = DiscoveryRepository.state.value.devices.firstOrNull { it.key == saved.key && it.videoEndpoint != null }
        listed()?.let { return it }
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = withTimeoutOrNull(DISCOVERY_WAIT_MS) {
            coroutineScope {
                val browse = nsd?.let { manager ->
                    launch(Dispatchers.IO) { DiscoveryRepository.collectFrom(NsdDeviceDiscovery(manager)) }
                }
                val device = DiscoveryRepository.state
                    .map { state -> state.devices.firstOrNull { it.key == saved.key && it.videoEndpoint != null } }
                    .filterNotNull()
                    .first()
                browse?.cancel()
                device
            }
        }
        if (found != null) return found
        val host = saved.host ?: return null
        val port = saved.port ?: return null
        return runCatching { withContext(Dispatchers.IO) { AddressLookup.lookup(host, port) } }
            .getOrNull()
            ?.also(DiscoveryRepository::addManual)
    }

    private fun log(message: String) = MirrorLog.write("cast: $message")

    private const val DISCOVERY_WAIT_MS = 12_000L
    /** How close to the end the last position must be for an empty status to mean "finished". */
    private const val END_TOLERANCE_S = 3.0
}
