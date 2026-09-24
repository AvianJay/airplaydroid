package tw.avianjay.airplaydroid.playback

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.AirPlayRequestFailed
import tw.avianjay.airplaydroid.protocol.AirPlayV1Session
import tw.avianjay.airplaydroid.protocol.PlaybackInfo
import tw.avianjay.airplaydroid.protocol.StatusFlags
import tw.avianjay.airplaydroid.protocol.VideoHandoff
import tw.avianjay.airplaydroid.protocol.pairing.LegacyPairing
import tw.avianjay.airplaydroid.protocol.pairing.Srp6aClient
import tw.avianjay.airplaydroid.protocol.http.AirPlayEventChannel
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.util.UUID

data class PlaybackUiState(
    val device: AirPlayDevice? = null,
    val url: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val info: PlaybackInfo = PlaybackInfo.EMPTY,
    val error: String? = null,
    /** The receiver is showing a PIN and the UI must collect it. */
    val awaitingPin: Boolean = false,
) {
    val isPlaying: Boolean get() = info.isPlaying
}

/**
 * Owns the AirPlay 1 video-URL session.
 *
 * Process-scoped for the same reason [tw.avianjay.airplaydroid.discovery.DiscoveryRepository]
 * is: the session must outlive activity recreation. All socket work runs on
 * [Dispatchers.IO] -- [AirPlayV1Session] is deliberately blocking.
 *
 * Concurrency rules, all of which earned their place:
 *  - Every mutation of [session]/[pollJob] happens under [lock], and every
 *    command runs inside a coroutine, never partly on the caller's thread.
 *    Tearing down on the caller thread cannot work: the session it means to
 *    close is assigned later, inside the launched coroutine.
 *  - Each attempt carries a [generation]. A superseded attempt must not write
 *    state or close the successor's session.
 *  - State is published with [MutableStateFlow.update] so concurrent
 *    read-modify-writes from the main and IO threads cannot lose one.
 */
object PlaybackController {

    private const val TAG = "PlaybackController"
    private const val POLL_INTERVAL_MS = 1_000L
    private const val MAX_CONSECUTIVE_POLL_FAILURES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var session: AirPlayV1Session? = null
    private var pollJob: Job? = null
    private var generation = 0

    // Held across the PIN prompt: the PIN only appears once pairing has started,
    // and the receiver keeps its SRP state against this very connection.
    private var pendingConnection: SocketAirPlayConnection? = null
    private var pendingPairing: LegacyPairing? = null
    private var pendingChallenge: LegacyPairing.Challenge? = null
    private var pendingDevice: AirPlayDevice? = null
    private var pendingUrl: String = ""
    private var pendingEvents: AirPlayEventChannel? = null
    private var pendingSessionId: String = ""

    fun play(device: AirPlayDevice, url: String, password: String? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Enter a video URL first.") }
            return
        }
        VideoHandoff.refusalFor(device)?.let { refusal ->
            _state.value = PlaybackUiState(device = device, url = trimmed, error = refusal.message)
            return
        }
        val endpoint = device.videoEndpoint ?: return

        scope.launch {
            // Supersede any in-flight attempt *before* touching shared state, so
            // two rapid taps cannot both open a socket.
            val (mine, previous) = supersede()
            runCatching { previous?.close() }
            _state.value = PlaybackUiState(device = device, url = trimmed, connecting = true)

            var opened: AirPlayV1Session? = null
            try {
                val secret = password?.takeIf { it.isNotEmpty() }
                val sid = UUID.randomUUID().toString().uppercase()

                // Order matters: the event channel must exist, on its own
                // socket and with this session id, before /play is sent.
                val events = AirPlayEventChannel.open(endpoint, sid, secret) { line, eventBody ->
                    val text = runCatching {
                        tw.avianjay.airplaydroid.protocol.plist.Plists.decode(eventBody).toString()
                    }.getOrElse { String(eventBody).take(400) }
                    Log.i(TAG, "EVENT " + line + " :: " + text.take(700))
                }
                Log.d(TAG, "event channel " + (if (events != null) "established" else "declined"))

                // ONE connection for the whole session. Pairing keeps its SRP
                // state against the TCP connection, so pairing and everything
                // after it must share this socket.
                val control = SocketAirPlayConnection(endpoint)

                val flags = device.airPlayTxt?.flags ?: StatusFlags.NONE
                if (flags.pairingRequired || flags.passwordRequired) {
                    // Both modes run the SAME legacy SRP exchange; only the
                    // credential differs. A password-protected receiver already
                    // knows its secret, so it shows no PIN and must not be asked
                    // to -- /pair-pin-start is for the pairing-required case.
                    val pairing = LegacyPairing(control)
                    if (flags.pairingRequired) pairing.startPin()
                    val challenge = pairing.begin()
                    Log.i(TAG, "pairing started, awaiting PIN (salt=" +
                        challenge.salt.size + "B pk=" + challenge.serverPublicKey.size + "B)")

                    pendingConnection = control
                    pendingPairing = pairing
                    pendingChallenge = challenge
                    pendingDevice = device
                    pendingUrl = trimmed
                    pendingEvents = events
                    pendingSessionId = sid

                    if (flags.passwordRequired && !secret.isNullOrEmpty()) {
                        // Credential already supplied in the play dialog.
                        submitPin(secret)
                        return@launch
                    }
                    _state.value = PlaybackUiState(
                        device = device, url = trimmed, awaitingPin = true,
                    )
                    return@launch
                }

                opened = AirPlayV1Session(
                    connection = control,
                    sessionId = sid,
                    password = secret,
                    eventChannel = events,
                )
                ensureActive()

                opened.play(trimmed)
                // Real senders send /rate unconditionally after /play: several
                // receivers load the URL but stay paused until told to play.
                opened.rate(1.0)
                ensureActive()

                val adopted = lock.withLock {
                    if (generation != mine) false else { session = opened; true }
                }
                if (!adopted) {
                    // A newer attempt won while we were connecting.
                    runCatching { opened.close() }
                    return@launch
                }

                _state.update { it.copy(connecting = false, connected = true) }
                startPolling(mine)
            } catch (t: Throwable) {
                runCatching { opened?.close() }
                // Only the current attempt may report failure; a superseded one
                // would otherwise overwrite the winner's state.
                if (lock.withLock { generation == mine }) {
                    Log.w(TAG, "play failed", t)
                    val message = when {
                        t is AirPlayRequestFailed && t.status == 401 && password.isNullOrEmpty() ->
                            device.displayName + " needs a password."
                        t is AirPlayRequestFailed && t.status == 401 ->
                            "That password was not accepted by " + device.displayName + "."
                        else -> t.message ?: "Could not start playback."
                    }
                    _state.value = PlaybackUiState(device = device, url = trimmed, error = message)
                }
            }
        }
    }

    fun togglePlayPause() {
        val resume = !_state.value.isPlaying
        runCommand("Could not change playback state.") { it.rate(if (resume) 1.0 else 0.0) }
    }

    fun seekTo(positionSeconds: Double) {
        runCommand("Could not seek.") { it.scrub(positionSeconds) }
    }

    fun stop() {
        scope.launch {
            // Supersede cancels an in-flight play too, so Stop pressed during
            // "connecting" really stops instead of leaving an orphaned session
            // that nothing holds a reference to.
            val (_, previous) = supersede()
            runCatching { previous?.stop() }
            runCatching { previous?.close() }
            _state.value = PlaybackUiState()
        }
    }

    /** Completes pairing with the PIN the receiver is displaying, then plays. */
    fun submitPin(pin: String) {
        val control = pendingConnection ?: return
        val pairing = pendingPairing ?: return
        val challenge = pendingChallenge ?: return
        val device = pendingDevice ?: return
        val url = pendingUrl
        val events = pendingEvents
        val sid = pendingSessionId
        clearPending()

        _state.value = PlaybackUiState(device = device, url = url, connecting = true)
        scope.launch {
            val mine = lock.withLock { generation }
            try {
                val pr0 = pairing
                val creds = pr0.complete(challenge, pin.trim())
                val k: ByteArray = creds.sessionKey
                val conn = control
                Log.i(TAG, "PAIRED. shared secret " + (k?.size ?: 0) + " bytes")

                val opened = AirPlayV1Session(conn, sid, null, events)
                opened.play(url)
                opened.rate(1.0)

                val adopted = lock.withLock {
                    if (generation != mine) false else { session = opened; true }
                }
                if (!adopted) { runCatching { opened.close() }; return@launch }
                _state.update { it.copy(connecting = false, connected = true) }
                startPolling(mine)
            } catch (t: Throwable) {
                Log.w(TAG, "pairing or playback failed", t)
                runCatching { control.close() }
                runCatching { events?.close() }
                _state.value = PlaybackUiState(
                    device = device, url = url,
                    error = t.message ?: "Pairing failed.",
                )
            }
        }
    }

    fun cancelPin() {
        val c = pendingConnection; val e = pendingEvents
        clearPending()
        scope.launch { runCatching { c?.close() }; runCatching { e?.close() } }
        _state.value = PlaybackUiState()
    }

    private fun endpointOf(device: AirPlayDevice) =
        requireNotNull(device.videoEndpoint) { "device has no endpoint" }

    private fun clearPending() {
        pendingConnection = null; pendingPairing = null; pendingChallenge = null
        pendingDevice = null; pendingUrl = ""; pendingEvents = null; pendingSessionId = ""
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Surfaces a locally-decided refusal (no socket involved) through the same channel. */
    fun reportRefusal(message: String) {
        _state.update { it.copy(error = message) }
    }

    /**
     * Invalidates every in-flight attempt, cancels polling, and detaches the
     * current session WITHOUT closing it -- the caller decides whether it needs
     * a graceful /stop first. Returns the new generation token and the detached
     * session.
     */
    private suspend fun supersede(): Pair<Int, AirPlayV1Session?> {
        val previousPoll = lock.withLock {
            generation++
            pollJob.also { pollJob = null }
        }
        runCatching { previousPoll?.cancelAndJoin() }

        return lock.withLock { generation to session.also { session = null } }
    }

    private fun runCommand(fallback: String, block: (AirPlayV1Session) -> Unit) {
        scope.launch {
            val mine = lock.withLock { generation }
            val current = lock.withLock { session } ?: return@launch
            try {
                block(current)
            } catch (t: Throwable) {
                if (lock.withLock { generation == mine }) reportFailure(t, fallback)
            }
        }
    }

    private suspend fun startPolling(mine: Int) {
        val job = scope.launch {
            var failures = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val current = lock.withLock { if (generation == mine) session else null } ?: break

                val info = runCatching { current.playbackInfo() }
                if (info.isSuccess) {
                    failures = 0
                    _state.update { if (it.connected) it.copy(info = info.getOrThrow()) else it }
                    continue
                }

                // Receivers answer /playback-info erratically while a stream is
                // loading, so one failure is not fatal -- but a dead control
                // connection must not leave the UI claiming it is connected.
                failures++
                if (failures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    Log.w(TAG, "playback polling gave up", info.exceptionOrNull())
                    val stale = lock.withLock {
                        if (generation != mine) null else session.also { session = null }
                    }
                    runCatching { stale?.close() }
                    if (stale != null) {
                        _state.update {
                            it.copy(
                                connecting = false,
                                connected = false,
                                error = "Lost the connection to the receiver.",
                            )
                        }
                    }
                    break
                }
            }
        }
        lock.withLock { if (generation == mine) pollJob = job else job.cancel() }
    }

    private fun reportFailure(t: Throwable, fallback: String) {
        Log.w(TAG, fallback, t)
        _state.update { it.copy(error = t.message ?: fallback) }
    }
}
