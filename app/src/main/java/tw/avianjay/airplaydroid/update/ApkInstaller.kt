package tw.avianjay.airplaydroid.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a downloaded APK to the system package installer.
 *
 * This app never installs anything itself. Since Android 8 the installer is a
 * separate system app, and the only way in is an `ACTION_VIEW` intent for the
 * `application/vnd.android.package-archive` MIME type. Two consequences shape
 * everything below:
 *
 *  - **`REQUEST_INSTALL_PACKAGES` is required**, and from Android 8 it is a
 *    per-app *special* permission the user grants in Settings, not a runtime
 *    dialog this app can raise. [canInstallPackages] reports whether it has been
 *    granted so the UI can offer [installPermissionIntent] instead of failing.
 *  - **The file must be exposed through a `FileProvider`.** Passing a `file://`
 *    URI would throw `FileUriExposedException`, and the installer cannot read
 *    app-private storage directly. The provider is declared in the manifest and
 *    its paths in `res/xml/file_paths.xml`.
 */
object ApkInstaller {

    /**
     * Whether this app may launch an install at all.
     *
     * No `SDK_INT` guard: `minSdk` is 26, which *is* Android 8, so the
     * permission exists on every device this app runs on. An earlier version
     * checked for it and lint correctly reported the branch as dead.
     */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * The Settings page where "install unknown apps" is granted for this app.
     *
     * Sent to the *app-specific* page rather than the global one: the global list
     * makes the user find this app among every other, and the specific page has
     * the toggle already on screen.
     */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens the system installer on [apk].
     *
     * @throws IllegalStateException when the grant is missing. Callers are
     *   expected to check [canInstallPackages] first and route to Settings; this
     *   is the backstop, because an install intent without the grant is dropped
     *   silently by some OEM builds rather than raising anything.
     */
    fun install(context: Context, apk: File) {
        check(canInstallPackages(context)) { "not allowed to install packages" }
        check(apk.isFile) { "no APK at ${apk.absolutePath}" }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            // The installer runs as a different app, so the read grant must be
            // explicit. Without it the installer cannot open the content URI and
            // fails with a permission error the user cannot act on.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Started from a background coroutine, so it needs its own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
