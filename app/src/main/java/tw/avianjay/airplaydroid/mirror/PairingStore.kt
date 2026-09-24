package tw.avianjay.airplaydroid.mirror

import android.content.Context
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
    }

    fun forget(deviceKey: String) {
        fileFor(deviceKey).delete()
    }

    fun has(deviceKey: String): Boolean = fileFor(deviceKey).isFile

    private fun fileFor(deviceKey: String) =
        File(dir, deviceKey.replace(Regex("[^A-Za-z0-9]"), "_") + ".creds")

    // Hex so a password containing '=' or a newline cannot corrupt the file.
    private fun hexEncode(s: String) = s.toByteArray().joinToString("") { "%02x".format(it) }

    private fun hexDecode(s: String) =
        String(ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    private companion object {
        const val PASSWORD_PREFIX = "password="
    }
}
