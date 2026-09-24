package tw.avianjay.airplaydroid.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.AirPlayTxt
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.RaopInstanceName
import tw.avianjay.airplaydroid.protocol.RaopTxt
import tw.avianjay.airplaydroid.protocol.TxtRecords
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * mDNS discovery via the platform [NsdManager].
 *
 * NsdManager rather than JmDNS on purpose: its multicast socket lives in the
 * system process, so it needs no MulticastLock and is exempt from Android 16's
 * local-network-protection opt-in phase. (JmDNS uses in-process raw multicast
 * sockets with no such exemption, making it a strictly worse fallback.)
 *
 * Platform details that drive the shape of this class:
 *
 *  1. Each service type needs its OWN DiscoveryListener. Reusing one instance
 *     throws IllegalArgumentException("listener already in use").
 *  2. On API 34+ `registerServiceInfoCallback` is a CONTINUOUS subscription, not
 *     a one-shot resolve, and the per-app limit is small. Every exit path must
 *     unregister it, and we do so as soon as the first update arrives.
 *  3. The pre-34 `resolveService` can only service one resolve at a time and
 *     fails with FAILURE_ALREADY_ACTIVE otherwise, so resolves are serialised.
 *  4. Callbacks are dispatched by NsdManager on the shared ConnectivityThread.
 *     We therefore hand it a DIRECT executor rather than one we own and shut
 *     down: NsdManager keeps posting acknowledgements (including
 *     onServiceInfoCallbackUnregistered) after we unregister, and a
 *     RejectedExecutionException thrown from its handler is uncaught and kills
 *     the process. NsdManager itself passes Runnable::run for the same reason.
 */
class NsdDeviceDiscovery(
    private val nsdManager: NsdManager,
    private val types: List<AirPlayServiceType> = AirPlayServiceType.entries.toList(),
) : DeviceDiscovery {

    override fun events(): Flow<DiscoveryEvent> = callbackFlow {
        // Direct executor: never shut down, so a late framework acknowledgement
        // can never hit a rejected-execution crash. Our callbacks only resume a
        // continuation, so running them on ConnectivityThread is cheap and safe.
        val executor = Executor { command -> command.run() }

        // Pre-34 resolveService is strictly serial; the API 34+ callback API has
        // a small per-app registration limit.
        val resolveGate = Semaphore(if (Build.VERSION.SDK_INT >= 34) 3 else 1)

        // serviceName -> merge key, so onServiceLost can report the right device.
        val keysByService = ConcurrentHashMap<String, String>()
        // Resolves currently running, so a goodbye can cancel one mid-flight.
        val inFlight = ConcurrentHashMap<String, Job>()
        // Services that went away while their resolve was still running.
        val lostWhileResolving = ConcurrentHashMap.newKeySet<String>()
        val liveCallbacks = ConcurrentHashMap<String, Any>()

        val started = mutableListOf<NsdManager.DiscoveryListener>()

        types.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(serviceType: String) {
                    trySend(DiscoveryEvent.ScanStarted(type))
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    trySend(DiscoveryEvent.ScanStopped(type))
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    trySend(
                        DiscoveryEvent.Failure(
                            type,
                            "Could not start " + type.label + " discovery (" + errorName(errorCode) + ")",
                        )
                    )
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "stopServiceDiscovery failed for " + type.label + ": " + errorName(errorCode))
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName ?: return
                    val token = cacheKey(type, name)
                    lostWhileResolving.remove(token)

                    val job = launch {
                        resolveGate.withPermit {
                            // Bounded: a resolve that never calls back would
                            // otherwise hold its permit forever and, at one
                            // permit below API 34, deadlock all discovery.
                            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                                resolve(serviceInfo, executor, liveCallbacks)
                            } ?: return@withPermit

                            // The receiver said goodbye while we were resolving.
                            // NsdManager may still answer from cache, and mDNS
                            // will not send a second goodbye, so publishing now
                            // would strand a device that can never be removed.
                            if (lostWhileResolving.remove(token)) return@withPermit

                            val device = toDevice(type, resolved) ?: return@withPermit
                            keysByService[token] = device.key
                            trySend(DiscoveryEvent.ServiceResolved(type, device))
                        }
                    }
                    inFlight[token] = job
                    job.invokeOnCompletion { inFlight.remove(token, job) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName ?: return
                    val token = cacheKey(type, name)

                    inFlight.remove(token)?.let { job ->
                        lostWhileResolving.add(token)
                        job.cancel()
                    }

                    // Only report a loss for something we actually published.
                    // Deriving a key here instead would not match the key the
                    // resolved device was stored under.
                    keysByService.remove(token)?.let { key ->
                        trySend(DiscoveryEvent.ServiceLost(type, key))
                    }
                }
            }

            try {
                nsdManager.discoverServices(type.mdnsType, NsdManager.PROTOCOL_DNS_SD, listener)
                started += listener
            } catch (t: Throwable) {
                trySend(DiscoveryEvent.Failure(type, "Discovery unavailable: " + t.message))
            }
        }

        awaitClose {
            started.forEach { listener ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            liveCallbacks.values.forEach { cb -> unregisterQuietly(cb) }
            liveCallbacks.clear()
            // Deliberately no executor shutdown -- see the class KDoc.
        }
    }

    // ---------------------------------------------------------------- resolve

    private suspend fun resolve(
        serviceInfo: NsdServiceInfo,
        executor: Executor,
        liveCallbacks: ConcurrentHashMap<String, Any>,
    ): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= 34) {
            resolveModern(serviceInfo, executor, liveCallbacks)
        } else {
            resolveLegacy(serviceInfo)
        }

    @RequiresApi(34)
    private suspend fun resolveModern(
        serviceInfo: NsdServiceInfo,
        executor: Executor,
        liveCallbacks: ConcurrentHashMap<String, Any>,
    ): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val token = serviceInfo.serviceName ?: return@suspendCancellableCoroutine cont.resume(null)
        var self: NsdManager.ServiceInfoCallback? = null

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                // Terminal per the platform contract: nothing to unregister.
                liveCallbacks.remove(token)
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                // One-shot: this API is a continuous subscription and the per-app
                // registration limit is small, so release it immediately.
                liveCallbacks.remove(token)
                self?.let { unregisterQuietly(it) }
                if (cont.isActive) cont.resume(updated)
            }

            override fun onServiceLost() {
                // Still a live registration -- releasing it here is what allows
                // the same service to be resolved again if it comes back.
                liveCallbacks.remove(token)
                self?.let { unregisterQuietly(it) }
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

        self = callback
        liveCallbacks[token] = callback

        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
        } catch (t: Throwable) {
            liveCallbacks.remove(token)
            Log.w(TAG, "registerServiceInfoCallback failed for " + token, t)
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            liveCallbacks.remove(token)
            unregisterQuietly(callback)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(
        serviceInfo: NsdServiceInfo,
    ): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for " + failed.serviceName + ": " + errorName(errorCode))
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (cont.isActive) cont.resume(resolved)
            }
        }
        try {
            nsdManager.resolveService(serviceInfo, listener)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun unregisterQuietly(callback: Any) {
        if (Build.VERSION.SDK_INT >= 34 && callback is NsdManager.ServiceInfoCallback) {
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
        }
    }

    // ---------------------------------------------------------------- mapping

    private fun toDevice(type: AirPlayServiceType, info: NsdServiceInfo): AirPlayDevice? {
        val host = info.resolvedHost() ?: return null
        val endpoint = Endpoint(host, info.port)
        val attributes = info.txtAttributes()
        val serviceName = info.serviceName ?: return null

        // Calibration aid: the published feature-bit tables disagree on several
        // bits, and acl/act have no other signal. Dumping the raw TXT of real
        // hardware is the only way to settle them.
        Log.d(
            TAG,
            "TXT " + type.label + " name=" + serviceName + " at " + endpoint +
                " " + TxtRecords.readable(attributes),
        )

        return when (type) {
            AirPlayServiceType.AirPlay -> {
                val txt = AirPlayTxt.parse(attributes)
                AirPlayDevice(
                    key = TxtRecords.normalizeDeviceKey(txt.deviceId) ?: serviceName,
                    displayName = serviceName,
                    airPlayEndpoint = endpoint,
                    airPlayTxt = txt,
                )
            }

            AirPlayServiceType.Raop -> {
                val txt = RaopTxt.parse(attributes)
                val parsedName = RaopInstanceName.parse(serviceName)
                AirPlayDevice(
                    // The instance-name prefix IS the device id, so the merge key
                    // survives even when TXT resolution returns nothing useful.
                    key = parsedName.deviceKey
                        ?: TxtRecords.normalizeDeviceKey(txt.raw["deviceid"])
                        ?: serviceName,
                    displayName = parsedName.displayName,
                    raopEndpoint = endpoint,
                    raopTxt = txt,
                )
            }
        }
    }

    private fun cacheKey(type: AirPlayServiceType, serviceName: String): String =
        type.name + "/" + serviceName

    private companion object {
        const val TAG = "NsdDiscovery"
        const val RESOLVE_TIMEOUT_MS = 8_000L

        fun errorName(code: Int): String = when (code) {
            NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
            NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
            NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
            else -> "error " + code
        }
    }
}

/** NsdServiceInfo.getAttributes() values are nullable at runtime for valueless TXT keys. */
@Suppress("UNCHECKED_CAST")
private fun NsdServiceInfo.txtAttributes(): Map<String, ByteArray?> =
    (attributes as? Map<String, ByteArray?>) ?: emptyMap()

/** Prefers IPv4: receivers commonly advertise both and v4 connects faster in practice. */
private fun NsdServiceInfo.resolvedHost(): String? {
    val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
        hostAddresses
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(host)
    }
    val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    return preferred?.hostAddress
}
