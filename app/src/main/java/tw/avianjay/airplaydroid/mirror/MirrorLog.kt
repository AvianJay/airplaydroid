package tw.avianjay.airplaydroid.mirror

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-disk log of mirroring sessions: when each started, how, and why it
 * ended.
 *
 * Logcat is not enough. ColorOS (at least) drops a third-party app's
 * `android.util.Log` output for long stretches, and the snackbar that shows an
 * end reason is gone after a few seconds. On a debuggable build this file can be
 * read with
 *
 *     adb shell run-as tw.avianjay.airplaydroid cat files/mirror-log.txt
 *
 * It holds no secrets: device names, sizes and reasons only.
 */
object MirrorLog {
    private const val TAG = "MirrorLog"
    private const val MAX_BYTES = 64 * 1024

    @Volatile private var file: File? = null
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "mirror-log.txt")
    }

    @Synchronized
    fun write(message: String) {
        Log.i(TAG, message)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                // Keep the newer half rather than growing without bound.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            f.appendText(format.format(Date()) + "  " + message + "\n")
        }
    }
}
