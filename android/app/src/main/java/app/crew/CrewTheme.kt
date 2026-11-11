package app.crew

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val CrewShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colours the wallpaper palette has no slot for: the amber of "needs you" and the green of "online". */
@Immutable
data class CrewColors(val needs: Color, val onNeeds: Color, val needsContainer: Color, val ok: Color)

private val DarkCrew = CrewColors(needs = Color(0xFFFFCB6B), onNeeds = Color(0xFF2A2008), needsContainer = Color(0xFF3D3013), ok = Color(0xFF7EE2A8))
private val LightCrew = CrewColors(needs = Color(0xFFE9A426), onNeeds = Color(0xFF2A1C00), needsContainer = Color(0xFFFFE7B8), ok = Color(0xFF1E9E5A))

val LocalCrewColors = staticCompositionLocalOf { DarkCrew }

/**
 * Roboto Flex, a variable font, for display type only (the "2 need you" headline, the wordmark, times).
 * Wide and heavy is the Material 3 Expressive voice; body text stays on the system font.
 */
@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, width: Float) = FontFamily(
    Font(
        R.font.roboto_flex,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(width),
            FontVariation.Setting("opsz", 144f),
        ),
    ),
)

object CrewType {
    val hero = flex(860, 132f)
    val wordmark = flex(850, 130f)
    val number = flex(800, 125f)
}

/** Material You: wallpaper palette and system light/dark, plus Crew's own amber and green. */
@Composable
fun CrewTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    androidx.compose.runtime.CompositionLocalProvider(LocalCrewColors provides if (dark) DarkCrew else LightCrew) {
        MaterialTheme(colorScheme = colorScheme, shapes = CrewShapes, content = content)
    }
}
