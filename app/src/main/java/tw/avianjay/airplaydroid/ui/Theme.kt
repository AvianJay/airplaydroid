package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF2F82B8)
private val AccentDark = Color(0xFF8FC7E8)

// Containers are set too, not just primary: the stock M3 ones are lavender,
// which clashes with the blue accent on the mirroring bar and the highlighted
// row. Approximate M3 tones of the accent.
private val LightColors = lightColorScheme(
    primary = Accent,
    primaryContainer = Color(0xFFCCE5FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF003351),
    primaryContainer = Color(0xFF004A73),
    onPrimaryContainer = Color(0xFFCCE5FF),
    secondaryContainer = Color(0xFF374955),
    onSecondaryContainer = Color(0xFFD2E5F5),
)

@Composable
fun AirPlayDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
