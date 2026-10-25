package mu.propertyfinder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF0E6B66)
val Ink = Color(0xFF12201F)
val Muted = Color(0xFF4F5B58)
val Ground = Color(0xFFF6F7F5)
val EligibleBg = Color(0xFFDDF0EC)
val EligibleFg = Color(0xFF0B5E58)
val DropBg = Color(0xFFFBE9DF)
val DropFg = Color(0xFF9A3F0E)
val WarnBg = Color(0xFFFFF1CC)
val WarnFg = Color(0xFF6E4B00)

private val colors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Ink,
    // Selected chips and the navigation bar indicator.
    secondaryContainer = EligibleBg,
    onSecondaryContainer = EligibleFg,
    background = Ground,
    surface = Color.White,
    onSurface = Ink,
    onSurfaceVariant = Muted,
)

@Composable
fun PropertyFinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
