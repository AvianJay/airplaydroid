package tw.avianjay.airplaydroid.mirror

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import java.io.File

/**
 * HomeKit pairing credentials per receiver, plus the receiver's AirPlay
 * password, which a password-protected receiver demands as HTTP Digest on every
 * SETUP even after pairing.
 *
 * Kept in [Context.getNoBackupFilesDir]: the files hold our Ed25519 private key
 * and a password, and a pairing restored onto another phone would impersonate
 * this one. App-private storage is the protection here; nothing is encrypted at
 * rest beyond what the OS provides.
 */
class PairingStore(context: Context) {

    class Entry(val credentials: HomeKitPairing.Credentials, val password: String?)

    /** What the picker may know about a pairing, never the secrets. */
    data class Summary(val hasPassword: Boolean)

    private val dir = File(context.noBackupFilesDir, "pairings")

    fun load(deviceKey: String): Entry? {
        val file = fileFor(deviceKey)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val password = text.lines().firstOrNull { it.startsWith(PASSWORD_PREFIX) }
                ?.removePrefix(PASSWORD_PREFIX)
                ?.let { hexDecode(it) }
            Entry(HomeKitPairing.Credentials.decode(text), password)
        }.getOrNull()
    }

    /**
     * Null when there is no usable pairing. Goes through [load] rather than a
     * file-exists check so a corrupt file reads as "not paired" -- which is also
     * what MirrorService concludes, before pairing again over it.
     */
    fun summary(deviceKey: String): Summary? = load(deviceKey)?.let { Summary(it.password != null) }

    fun save(deviceKey: String, entry: Entry) {
        dir.mkdirs()
        val text = entry.credentials.encode() +
            (entry.password?.let { PASSWORD_PREFIX + hexEncode(it) + "\n" } ?: "")
        val tmp = File(dir, fileFor(deviceKey).name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(fileFor(deviceKey))) {
            tmp.copyTo(fileFor(deviceKey), overwrite = true)
            tmp.delete()
        }
        _revision.update { it + 1 }
    }

    /**
     * Keeps the pairing but drops the password, for when the receiver rejected
     * it: the next tap then asks for the current one instead of failing with the
     * stale one every time.
     */
    fun clearPassword(deviceKey: String) {
        val entry = load(deviceKey) ?: return
        if (entry.password != null) save(deviceKey, Entry(entry.credentials, null))
    }

    fun forget(deviceKey: String) {
        fileFor(deviceKey).delete()
        _revision.update { it + 1 }
    }

    private fun fileFor(deviceKey: String) =
        File(dir, deviceKey.replace(Regex("[^A-Za-z0-9]"), "_") + ".creds")

    // Hex so a password containing '=' or a newline cannot corrupt the file.
    private fun hexEncode(s: String) = s.toByteArray().joinToString("") { "%02x".format(it) }

    private fun hexDecode(s: String) =
        String(ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    companion object {
        private const val PASSWORD_PREFIX = "password="

        private val _revision = MutableStateFlow(0)

        /**
         * Bumped on every write, so the picker re-reads what it shows instead of
         * polling the disk. Process-wide rather than per instance: MirrorService
         * writes through its own store.
         */
        val revision: StateFlow<Int> = _revision.asStateFlow()
    }
}
