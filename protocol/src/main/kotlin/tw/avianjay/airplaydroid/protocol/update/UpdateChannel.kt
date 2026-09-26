package tw.avianjay.airplaydroid.protocol.update

/**
 * Which GitHub releases the updater is allowed to offer.
 *
 * `OFF` is a member rather than a nullable setting so the settings screen can
 * render the choice as one radio group and the store has one type to persist.
 * [UpdateSelector.select] treats it as "never offer anything", which is the
 * whole of its behaviour.
 *
 * [STABLE] and [NIGHTLY] differ only in whether a release flagged as a
 * pre-release is eligible. They do **not** differ in how "newer" is decided:
 * both compare `versionCode`, so switching channels is always a move forward,
 * never a surprise downgrade that Android refuses to install.
 */
enum class UpdateChannel {
    OFF,
    STABLE,
    NIGHTLY,
    ;

    val isOff: Boolean get() = this == OFF

    /** The value written to `update.json`, for the workflows that publish it. */
    val manifestName: String get() = name.lowercase()
}
