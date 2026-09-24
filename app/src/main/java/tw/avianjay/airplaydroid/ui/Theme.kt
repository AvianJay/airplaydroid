package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF2F82B8)
private val AccentDark = Color(0xFF8FC7E8)

private val LightColors = lightColorScheme(
    primary = Accent,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
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
