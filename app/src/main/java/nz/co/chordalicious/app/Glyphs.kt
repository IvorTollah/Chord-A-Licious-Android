// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small drawn icons for the home screen (no icon library needed). */
@Composable
fun FolderGlyph(color: Color, size: Dp = 24.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    val p = Path().apply {
        moveTo(w * 0.06f, h * 0.22f); lineTo(w * 0.38f, h * 0.22f); lineTo(w * 0.48f, h * 0.34f); lineTo(w * 0.94f, h * 0.34f)
        lineTo(w * 0.94f, h * 0.80f); lineTo(w * 0.06f, h * 0.80f); close()
    }
    drawPath(p, color)
    drawLine(Color(0x55000000), Offset(w * 0.06f, h * 0.46f), Offset(w * 0.94f, h * 0.46f), 2f)
}

@Composable
fun MicGlyph(color: Color, size: Dp = 24.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    drawRoundRect(color, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.50f), CornerRadius(w * 0.14f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.22f, h * 0.24f), Size(w * 0.56f, h * 0.50f), style = Stroke(w * 0.07f, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), w * 0.07f, StrokeCap.Round)
    drawLine(color, Offset(w * 0.34f, h * 0.93f), Offset(w * 0.66f, h * 0.93f), w * 0.07f, StrokeCap.Round)
}

@Composable
fun HeartGlyph(color: Color, size: Dp = 24.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        cubicTo(w * 0.05f, h * 0.55f, w * 0.08f, h * 0.12f, w * 0.30f, h * 0.12f)
        cubicTo(w * 0.42f, h * 0.12f, w * 0.48f, h * 0.20f, w * 0.5f, h * 0.28f)
        cubicTo(w * 0.52f, h * 0.20f, w * 0.58f, h * 0.12f, w * 0.70f, h * 0.12f)
        cubicTo(w * 0.92f, h * 0.12f, w * 0.95f, h * 0.55f, w * 0.5f, h * 0.88f)
        close()
    }
    drawPath(p, color)
}

/** An ear; with a line through it when the sound is muted. */
@Composable
fun EarGlyph(color: Color, muted: Boolean, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height; val sw = w * 0.085f
    val outer = Path().apply {
        moveTo(w * 0.42f, h * 0.93f); cubicTo(w * 0.72f, h * 0.82f, w * 0.88f, h * 0.55f, w * 0.76f, h * 0.30f)
        cubicTo(w * 0.64f, h * 0.04f, w * 0.22f, h * 0.06f, w * 0.20f, h * 0.38f)
    }
    val inner = Path().apply {
        moveTo(w * 0.40f, h * 0.46f); cubicTo(w * 0.40f, h * 0.28f, w * 0.60f, h * 0.28f, w * 0.60f, h * 0.45f)
        cubicTo(w * 0.60f, h * 0.60f, w * 0.46f, h * 0.62f, w * 0.46f, h * 0.78f)
    }
    drawPath(outer, color, style = Stroke(sw, cap = StrokeCap.Round)); drawPath(inner, color, style = Stroke(sw, cap = StrokeCap.Round))
    if (muted) drawLine(Color(0xFFF87171), Offset(w * 0.08f, h * 0.10f), Offset(w * 0.92f, h * 0.92f), sw * 1.2f, StrokeCap.Round)
}
