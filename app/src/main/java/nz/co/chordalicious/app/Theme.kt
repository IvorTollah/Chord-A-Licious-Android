// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The same palette as the website.
object Cal {
    val bg = Color(0xFF090E17)
    val panel = Color(0xFF111A2E)
    val card = Color(0xFF131E2B)
    val cardActive = Color(0xFF1E3254)
    val border = Color(0xFF233654)
    val steel = Color(0xFF38BDF8)
    val steelDark = Color(0xFF0284C7)
    val highlight = Color(0xFF60A5FA)
    val text = Color(0xFFF1F5F9)
    val muted = Color(0xFF94A3B8)
    val good = Color(0xFF34D399)
    val bad = Color(0xFFF87171)
}

private val scheme = darkColorScheme(
    primary = Cal.steel, onPrimary = Cal.bg,
    secondary = Cal.highlight, onSecondary = Cal.bg,
    background = Cal.bg, onBackground = Cal.text,
    surface = Cal.bg, onSurface = Cal.text,
    surfaceVariant = Cal.card, onSurfaceVariant = Cal.muted,
    outline = Cal.border, error = Cal.bad, onError = Cal.bg,
    secondaryContainer = Cal.cardActive, onSecondaryContainer = Cal.text,
)

/** Poppins: rounder and livelier than the system font. Each weight is its own file, so bold is never faked. */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal), Font(R.font.poppins_medium, FontWeight.Medium), Font(R.font.poppins_medium, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold), Font(R.font.poppins_bold, FontWeight.ExtraBold), Font(R.font.poppins_bold, FontWeight.Black))

private fun poppinsTypography(): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = Poppins), displayMedium = b.displayMedium.copy(fontFamily = Poppins), displaySmall = b.displaySmall.copy(fontFamily = Poppins),
        headlineLarge = b.headlineLarge.copy(fontFamily = Poppins), headlineMedium = b.headlineMedium.copy(fontFamily = Poppins), headlineSmall = b.headlineSmall.copy(fontFamily = Poppins),
        titleLarge = b.titleLarge.copy(fontFamily = Poppins), titleMedium = b.titleMedium.copy(fontFamily = Poppins), titleSmall = b.titleSmall.copy(fontFamily = Poppins),
        bodyLarge = b.bodyLarge.copy(fontFamily = Poppins), bodyMedium = b.bodyMedium.copy(fontFamily = Poppins), bodySmall = b.bodySmall.copy(fontFamily = Poppins),
        labelLarge = b.labelLarge.copy(fontFamily = Poppins), labelMedium = b.labelMedium.copy(fontFamily = Poppins), labelSmall = b.labelSmall.copy(fontFamily = Poppins))
}

@Composable
fun CalTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = poppinsTypography(), content = content)

/** A panel with the website's rounded, thin-bordered look. */
@Composable
fun Panel(modifier: Modifier = Modifier, pad: androidx.compose.ui.unit.Dp = 16.dp, space: androidx.compose.ui.unit.Dp = 10.dp, glow: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (glow) {                      // a bright, glowing bezel that pops off the dark page
        val shape = RoundedCornerShape(24.dp)
        Column(modifier.shadow(18.dp, shape, ambientColor = Color(0xFF19FF5C), spotColor = Cal.steel).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF17264A), Color(0xFF0E182D))))
            .border(2.5.dp, Brush.linearGradient(listOf(Color(0xFF19FF5C), Cal.steel, Color(0xFFA78BFA))), shape).padding(pad),
            verticalArrangement = Arrangement.spacedBy(space), content = content)
        return
    }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Cal.panel).border(1.dp, Cal.border, shape).padding(pad), verticalArrangement = Arrangement.spacedBy(space), content = content)
}
