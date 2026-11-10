// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.StrokeJoin

@Composable
fun ChordDiagram(name: String, shape: Shape?, left: Boolean, width: Dp = 84.dp, highlight: Boolean = false, beats: Int? = null, capo: Int = 0, dim: Boolean = false) {
    Column(Modifier.alpha(if (dim) 0.55f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, fontSize = if (highlight) 18.sp else 14.sp, fontWeight = FontWeight.Bold, color = if (highlight) Cal.text else Cal.muted)
            if (beats != null) Text("$beats", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Cal.bg, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Cal.steel).widthIn(min = 24.dp).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        if (shape == null) { Spacer(Modifier.height(width * 1.0f)); return@Column }
        if (shape.keys) {                                    // keyboard: the pressed keys, lit
            val lo = shape.frets.min(); val start = (lo / 12) * 12
            val octs = if (shape.frets.max() - start >= 12) 2 else 1
            val whites = octs * 7 + 1
            val isWhite = { m: Int -> (m % 12) in setOf(0, 2, 4, 5, 7, 9, 11) }
            Canvas(Modifier.size(width, width * 0.62f)) {
                val kw = size.width / whites; val h = size.height
                var wi = 0
                val xs = HashMap<Int, Float>()
                for (m in start..start + octs * 12) if (isWhite(m)) { xs[m] = wi * kw; wi++ }
                for (m in start..start + octs * 12) if (isWhite(m)) {
                    val x = xs[m]!!; val on = m in shape.frets
                    drawRect(if (on) Cal.steel else Color(0xFFE2E8F0), Offset(x + 0.8f, 0f), androidx.compose.ui.geometry.Size(kw - 1.6f, h))
                    drawRect(Color(0xFF475569), Offset(x, 0f), androidx.compose.ui.geometry.Size(kw, h), style = Stroke(1f))
                }
                for (m in start..start + octs * 12) if (!isWhite(m)) {
                    val x = (xs[m - 1] ?: 0f) + kw * 0.68f; val on = m in shape.frets
                    drawRect(if (on) Cal.steel else Color(0xFF0F172A), Offset(x, 0f), androidx.compose.ui.geometry.Size(kw * 0.64f, h * 0.62f))
                }
            }
            Text(if (shape.simplified) "simplified" else "", fontSize = 10.sp, color = Cal.muted)
            return@Column
        }
        val nStr = shape.frets.size
        val pos = shape.frets.filter { it > 0 }
        val base = if (pos.isEmpty() || pos.max() <= 5) 1 else pos.min()
        Canvas(Modifier.size(width, width * 1.05f)) {
            val padL = size.width * 0.16f; val padR = size.width * 0.08f; val top = size.height * 0.18f; val bottom = size.height * 0.03f
            val gw = size.width - padL - padR; val gh = size.height - top - bottom
            val sx = gw / (nStr - 1).toFloat(); val fy = gh / 5f
            val line = Color(0xFF64748B); val ink = Color(0xFFB4C0D0)
            fun xOf(s: Int) = padL + (if (left) nStr - 1 - s else s) * sx
            for (i in 0 until nStr) drawLine(Color(0xFF94A3B8), Offset(padL + i * sx, top), Offset(padL + i * sx, top + gh), 2.4f)
            for (r in 0..5) drawLine(if (r == 0 && base == 1) Cal.text else line, Offset(padL, top + r * fy), Offset(padL + gw, top + r * fy), if (r == 0 && base == 1) 5f else 2f)
            if (capo > 0) drawLine(Color(0xFFFB923C), Offset(padL - 3f, top), Offset(padL + gw + 3f, top), 9f, StrokeCap.Round)
            val rad = minOf(sx * 0.4f, fy * 0.4f)
            for (s in 0 until nStr) {
                val fr = shape.frets[s]; val x = xOf(s)
                when {
                    fr < 0 -> { val c = top * 0.45f; val d = rad * 0.7f
                        drawLine(ink, Offset(x - d, c - d), Offset(x + d, c + d), 2f); drawLine(ink, Offset(x - d, c + d), Offset(x + d, c - d), 2f) }
                    fr == 0 -> drawCircle(ink, rad * 0.7f, Offset(x, top * 0.45f), style = Stroke(2f))
                    else -> { val row = fr - base
                        if (row in 0..4) drawCircle(Cal.steel, rad, Offset(x, top + (row + 0.5f) * fy)) }
                }
            }
            if (shape.barre && pos.isNotEmpty()) {
                val m = pos.min(); val ss = shape.frets.indices.filter { shape.frets[it] == m }
                if (ss.size >= 2) {
                    val y = top + (m - base + 0.5f) * fy
                    val x1 = minOf(xOf(ss.min()), xOf(ss.max())); val x2 = maxOf(xOf(ss.min()), xOf(ss.max()))
                    drawLine(Cal.steel, Offset(x1, y), Offset(x2, y), rad * 1.9f, StrokeCap.Round)
                }
            }
        }
        Text(if (capo > 0 && base == 1) "capo $capo" else if (base > 1) "fret $base" else if (shape.simplified) "simplified" else "", fontSize = 10.sp, color = if (capo > 0) Color(0xFFFB923C) else Cal.muted)
    }
}

/** A silver, glowing progress bar you can tap or drag. */
@Composable
fun SilverProgress(fraction: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var w by remember { mutableIntStateOf(1) }
    val silver = Color(0xFFD7DEE8)
    Canvas(modifier.fillMaxWidth().height(40.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
        .pointerInput(enabled) { if (enabled) detectTapGestures { o -> onChange((o.x / w).coerceIn(0f, 1f)); onDone() } }
        .pointerInput(enabled) {
            if (enabled) detectHorizontalDragGestures(onDragEnd = { onDone() }, onDragCancel = { onDone() }) { change, _ ->
                change.consume(); onChange((change.position.x / w).coerceIn(0f, 1f))
            }
        }) {
        val cy = size.height / 2; val th = 8.dp.toPx(); val x = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(Color(0xFF1E293B), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        if (x > 0f) {
            for ((k, a) in listOf(16f to 0.06f, 11f to 0.11f, 6f to 0.2f)) {
                val e = k.dp.toPx()
                drawRoundRect(silver.copy(alpha = a), Offset(0f, cy - th / 2 - e / 2), Size(x, th + e), CornerRadius((th + e) / 2))
            }
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF1F5F9), Color(0xFF94A3B8), Color(0xFFE2E8F0)), cy - th / 2, cy + th / 2),
                Offset(0f, cy - th / 2), Size(x, th), CornerRadius(th / 2))
            drawCircle(silver.copy(alpha = 0.12f), 20.dp.toPx(), Offset(x, cy))
            drawCircle(silver.copy(alpha = 0.25f), 13.dp.toPx(), Offset(x, cy))
            drawCircle(Color(0xFFF8FAFC), 7.dp.toPx(), Offset(x, cy))
        }
    }
}

private val SHIELD = "M24 2 C39 2 47 12 45 26 C43 39 31 49 24 50 C17 49 5 39 3 26 C1 12 9 2 24 2 Z"
private val BOLT = "M13.5 2 4.5 13.5H11L9.8 22l9.7-12.2H13z"

/** The website's yellow lightning bolt (power chords). */
@Composable
fun Bolt(size: Dp = 14.dp, color: Color = Color(0xFFFBBF24)) {
    val path = remember { PathParser().parsePathString(BOLT).toPath() }
    Canvas(Modifier.size(size)) { scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) { drawPath(path, color, style = Fill) } }
}

/** The website's "Estimated Key" shield: note, MAJOR/MINOR, and the key signature. */
@Composable
fun KeyBadge(key: String, changed: Boolean = false) {
    val m = Regex("^([A-G][#b]?)\\s+(Major|Minor)$", RegexOption.IGNORE_CASE).find(key.trim())
    val root = m?.groupValues?.get(1) ?: key.trim()
    val minor = m?.groupValues?.get(2)?.lowercase() == "minor"
    val pretty = root.replace("#", "\u266F").replace(Regex("^([A-G])b$"), "$1\u266D")
    val pc = parseChord(root)?.root
    val sig = if (pc == null) "" else {
        val pos = ((((if (minor) pc + 3 else pc) % 12) * 7) % 12)
        var n = if (pos <= 6) pos else pos - 12
        if (pos == 6 && root.endsWith("b")) n = -6
        if (n == 0) "no \u266F \u266D" else "${kotlin.math.abs(n)} ${if (n > 0) "\u266F" else "\u266D"}"
    }
    val path = remember { PathParser().parsePathString(SHIELD).toPath() }
    // amber when the song has moved to a different key from the one it started in
    val top = if (changed) Color(0xFFFBBF24) else if (minor) Color(0xFFA78BFA) else Color(0xFF38BDF8)
    val bot = if (changed) Color(0xFFB45309) else if (minor) Color(0xFF5B21B6) else Color(0xFF0369A1)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp, 47.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                scale(size.width / 48f, size.height / 52f, pivot = Offset.Zero) {
                    drawPath(path, Brush.verticalGradient(listOf(top, bot), 0f, 52f), style = Fill)
                    drawPath(path, Color(0xFFBAE6FD), style = Stroke(1.4f, join = StrokeJoin.Round))
                }
            }
            Text(pretty, fontSize = if (pretty.length > 1) 17.sp else 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (minor) "MINOR" else "MAJOR", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = top)
            if (sig.isNotEmpty()) Text(sig, fontSize = 10.sp, color = Cal.muted)
        }
    }
}

/** Song waveform you can tap or drag to move through the song; the played part is silver. */
@Composable
fun Waveform(peaks: List<Float>, fraction: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (peaks.isEmpty()) { SilverProgress(fraction, onChange, onDone, modifier, enabled); return }
    var w by remember { mutableIntStateOf(1) }
    val silver = Color(0xFFD7DEE8)
    Canvas(modifier.fillMaxWidth().height(52.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
        .pointerInput(enabled) { if (enabled) detectTapGestures { o -> onChange((o.x / w).coerceIn(0f, 1f)); onDone() } }
        .pointerInput(enabled) {
            if (enabled) detectHorizontalDragGestures(onDragEnd = { onDone() }, onDragCancel = { onDone() }) { change, _ ->
                change.consume(); onChange((change.position.x / w).coerceIn(0f, 1f))
            }
        }) {
        val cols = 110; val step = size.width / cols; val cy = size.height / 2; val fx = size.width * fraction.coerceIn(0f, 1f)
        for (i in 0 until cols) {
            val p = peaks[(i * peaks.size / cols).coerceIn(0, peaks.size - 1)].coerceIn(0f, 1f)
            val h = maxOf(3f, p * size.height * 0.92f); val x = i * step + step / 2
            drawLine(if (x <= fx) silver else Color(0xFF2B3E5E), Offset(x, cy - h / 2), Offset(x, cy + h / 2), step * 0.62f, StrokeCap.Round)
        }
        for ((k, a) in listOf(10f to 0.10f, 6f to 0.2f)) drawLine(silver.copy(alpha = a), Offset(fx, 0f), Offset(fx, size.height), k.dp.toPx())
        drawLine(Color.White, Offset(fx, 0f), Offset(fx, size.height), 2.dp.toPx())
    }
}
