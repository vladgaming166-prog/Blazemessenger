package ro.blazemessenger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF1A120E)
private val Ember = Color(0xFFE25B1A)
private val EmberDeep = Color(0xFF7A2E0E)
private val Gold = Color(0xFFC9892D)
private val Paper = Color(0xFFFBF6F1)
private val Cream = Color(0xFFFFFCF9)

val LightColors = lightColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3B1400),
    secondary = Color(0xFF5C4033),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E6DA),
    onSecondaryContainer = Ink,
    tertiary = Gold,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E6DA),
    onSurfaceVariant = Color(0xFF51443C),
    outline = Color(0xFFD9C6B8),
    error = Color(0xFFB42318),
    onError = Color.White,
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A4C),
    onPrimary = Color(0xFF3B1400),
    primaryContainer = EmberDeep,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE7BFA8),
    onSecondary = Color(0xFF2A1812),
    secondaryContainer = Color(0xFF2C241E),
    onSecondaryContainer = Color(0xFFF6EDE6),
    tertiary = Color(0xFFE6B15A),
    onTertiary = Color(0xFF2A1C00),
    background = Color(0xFF100E0C),
    onBackground = Color(0xFFF6EDE6),
    surface = Color(0xFF1A1614),
    onSurface = Color(0xFFF6EDE6),
    surfaceVariant = Color(0xFF2C241E),
    onSurfaceVariant = Color(0xFFD8C4B6),
    outline = Color(0xFF5A463C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val BlazeTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

val BlazeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun BlazeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BlazeTypography,
        shapes = BlazeShapes,
        content = content,
    )
}
