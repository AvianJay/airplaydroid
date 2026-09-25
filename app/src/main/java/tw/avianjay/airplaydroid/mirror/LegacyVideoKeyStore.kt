package tw.avianjay.airplaydroid.mirror

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed
import java.io.File
import java.util.UUID

/**
 * Per-receiver choice of legacy video key seed, and this install's legacy
 * sender id.
 *
 * Legacy receivers disagree on how the FairPlay key seeds the video key, and
 * nothing they advertise says which they want ([KeySeed]). [KeySeed.AUTO] is
 * right for every receiver measured so far, but it is a heuristic; when a
 * receiver connects and shows nothing, the user switches it here from the row's
 * menu, and the choice sticks for that receiver.
 *
 * A receiver with no choice of its own falls back to [default], which is the
 * app-wide setting: that is the one to change when *every* legacy receiver
 * misbehaves, and the per-receiver override stays for the one that differs.
 */
class LegacyVideoKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("legacy_video_key", Context.MODE_PRIVATE)
    private val idFile = File(context.noBackupFilesDir, "legacy-device-id")

    /** This receiver's own choice, or [default] when it has none. */
    fun get(deviceKey: String, default: KeySeed = KeySeed.AUTO): KeySeed =
        prefs.getString(deviceKey, null)?.let { runCatching { KeySeed.valueOf(it) }.getOrNull() } ?: default

    /**
     * Automatic -> raw -> mixed -> automatic. Returns the new choice.
     *
     * [default] is what AUTO resolves to, so the cycle starts from what the user
     * actually sees rather than from a fixed AUTO.
     */
    fun cycle(deviceKey: String, default: KeySeed = KeySeed.AUTO): KeySeed {
        val next = when (get(deviceKey, default)) {
            KeySeed.AUTO -> KeySeed.RAW
            KeySeed.RAW -> KeySeed.MIXED
            KeySeed.MIXED -> KeySeed.AUTO
        }
        // Storing AUTO as an absent key lets a later change to the app-wide
        // default reach this receiver again.
        prefs.edit { if (next == KeySeed.AUTO) remove(deviceKey) else putString(deviceKey, next.name) }
        _revision.update { it + 1 }
        return next
    }

    /**
     * The `deviceID` this install presents to legacy receivers, made once and
     * kept. A fresh one per session looks like a new sender every time, and
     * iPhoneMirror asks the user to set up each sender it has not seen.
     */
    fun deviceId(): String {
        runCatching { idFile.readText().trim() }.getOrNull()?.takeIf { MAC.matches(it) }?.let { return it }
        val bits = UUID.randomUUID().mostSignificantBits
        val bytes = ByteArray(6) { (bits ushr (8 * it)).toByte() }
        // Locally administered, unicast.
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it) }.also { id -> runCatching { idFile.writeText(id) } }
    }

    companion object {
        private val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

        private val _revision = MutableStateFlow(0)

        /** Bumped on every change, so the picker re-reads the labels. */
        val revision: StateFlow<Int> = _revision.asStateFlow()
    }
}
