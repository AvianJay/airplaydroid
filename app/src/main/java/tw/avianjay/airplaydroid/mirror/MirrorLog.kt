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
    private const val FILE_NAME = "mirror-log.txt"

    @Volatile private var file: File? = null
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file == null) file = fileFor(context)
    }

    /**
     * The log file for [context], whether or not it exists yet. The settings
     * screen's diagnostics row uses this rather than a [file] accessor: [init]
     * only runs when a session starts, so anything tied to it would report "no
     * log" for one written by an earlier run.
     */
    fun fileFor(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

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
