package tw.avianjay.airplaydroid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.MirrorUiState

/**
 * The Quick Settings tile: one tap opens the casting popup
 * ([CastPopupActivity]), collapsing the panel on the way.
 *
 * Why a tile that opens an activity, rather than a toggle that mirrors straight
 * away: the session needs the user's screen-capture consent, which is a
 * foreground activity's dialog, and a receiver must be chosen first. Both are
 * things a service cannot do for the user. So the tile's job is to get the popup
 * in front of them with as few gestures as the platform allows.
 *
 * The tile also mirrors the session's state back: it lights up while mirroring
 * and says so, which is the one place the state is visible without pulling the
 * notification down.
 */
class CastTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val popup = CastPopupActivity.intent(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From Android 14 the Intent overload throws UnsupportedOperationException
            // for an app targeting 34+, so the PendingIntent one is not merely
            // preferred -- it is the only form that works. FLAG_IMMUTABLE is
            // required from API 31 and this PendingIntent is never mutated.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_CODE,
                    popup,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            // Not a fallback for tidiness: the PendingIntent overload does not
            // exist below API 34, so calling it here would be a NoSuchMethodError
            // on exactly the releases this branch serves. Lint's advice -- always
            // use the PendingIntent form -- cannot be followed on API 26..33.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(popup)
        }
    }

    /**
     * Lights the tile while a session runs.
     *
     * Read from [MirrorController] rather than from a service of our own: the
     * state is process-scoped, and a tile that listened for updates would need
     * [requestListeningState] from somewhere that knows a session started.
     * `onStartListening` is the moment the panel is opening, which is exactly when
     * the state needs to be right.
     *
     * [Tile.setSubtitle] and [Tile.setStateDescription] are API 29 and 30, and
     * minSdk here is 26, so each is guarded: on an older release the tile simply
     * shows the label and the active/inactive colour, which is the whole of what
     * the tile is for. `stateDescription` is what TalkBack reads, so it matters
     * most where it exists.
     */
    private fun updateTile() {
        val tile = qsTile ?: return
        val mirror = MirrorController.state.value

        tile.state = if (mirror.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_cast)
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(R.string.tile_label)

        val subtitle = when (mirror.phase) {
            MirrorUiState.Phase.Idle -> getString(R.string.tile_subtitle_idle)
            else -> mirror.device?.displayName ?: getString(R.string.tile_subtitle_active)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = subtitle
        }
        tile.updateTile()
    }

    private companion object {
        /**
         * Fixed rather than unique: the popup is a singleton, so every tile tap
         * should resolve to the same PendingIntent and `FLAG_UPDATE_CURRENT`
         * should replace its extras rather than accumulate them.
         */
        const val REQUEST_CODE = 1
    }
}
