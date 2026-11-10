// Chord-A-Licious for Android - GPL-3.0-or-later
// The home screen's brand pill, the "Chord of the moment" carousel and the "listening to your song" overlay.
package nz.co.chordalicious.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private val GREEN = Color(0xFF19FF5C)

/** The logo in a large glowing pill outline, as on the brochures and social cards. */
@Composable
fun BrandPill(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth().shadow(14.dp, shape, ambientColor = GREEN.copy(alpha = 0.5f), spotColor = Cal.steel.copy(alpha = 0.5f))
        .clip(shape).background(Brush.horizontalGradient(listOf(Color(0xFF0A1A2A), Color(0xFF0B2420))))
        .border(2.5.dp, Brush.horizontalGradient(listOf(GREEN, Cal.steel)), shape).padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.badge), contentDescription = "Chord-A-Licious", modifier = Modifier.size(68.dp))
        Text("Chord-A-Licious", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Cal.text, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

/** Swipe left or right through "Chord of the moment" cards; they also turn over by themselves. */
@Composable
fun ChordOfTheMoment(modifier: Modifier = Modifier) {
    val order = remember { CHORD_FACTS.indices.shuffled() }
    val n = order.size
    val count = 100000                                       // endless in both directions, so it never has to scroll all the way back
    val pager = rememberPagerState(initialPage = count / 2 - (count / 2) % n, pageCount = { count })
    // keyed on the settled page: the timer restarts only after a page has come to rest (keying on currentPage cancelled the animation half way)
    LaunchedEffect(pager.settledPage) {
        delay(9000)
        if (!pager.isScrollInProgress) pager.animateScrollToPage(pager.settledPage + 1, animationSpec = tween(900, easing = FastOutSlowInEasing))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CHORD OF THE MOMENT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cal.muted, letterSpacing = 1.6.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 4.dp))
        HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp) { p ->
            val c = CHORD_FACTS[order[p % n]]
            val shape = remember(c.name) { shapeFor(c.name.replace("♯", "#").replace("♭", "b"), Phrasing.Open) }
            val card = RoundedCornerShape(18.dp)
            Box(Modifier.fillMaxWidth(), Alignment.Center) {                      // a playing card: portrait, bright silver edge, the name in two corners
                Box(Modifier.width(236.dp).height(330.dp).shadow(14.dp, card, ambientColor = Color(0xFFE2E8F0), spotColor = Color(0xFFE2E8F0)).clip(card)
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFF0F182B))))
                    .border(2.5.dp, Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFA9B8CF), Color(0xFFF1F5F9))), card)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        if (shape != null) ChordDiagram(c.name, shape, false, width = 110.dp, highlight = true)
                        else Text(c.name, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Cal.steel, modifier = Modifier.padding(vertical = 24.dp))
                        Text(c.fact, fontSize = 14.sp, color = Cal.text, textAlign = TextAlign.Center, lineHeight = 19.sp)
                        if (c.made) Text("* OK we made this up", fontSize = 11.sp, color = Cal.muted)
                    }
                }
            }
        }
        Text("‹  swipe for another chord  ›", fontSize = 12.sp, color = Cal.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A full-screen "listening" scene: a pulsing badge, dancing equaliser bars and the rock-and-roll quips, with a Cancel button. */
@Composable
fun ListeningOverlay(quip: String, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        val t = rememberInfiniteTransition(label = "listen")
        val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
        val pulse by t.animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ring")
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF2060B14), Color(0xF20B1D2E), Color(0xF2060B14)))).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(220.dp), Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (k in 0..2) {                                   // rings that spread out from the badge and fade
                        val f = (ring + k / 3f) % 1f
                        drawCircle((if (k == 1) GREEN else Cal.steel).copy(alpha = (1f - f) * 0.5f), size.minDimension / 2 * (0.5f + 0.5f * f), center, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    }
                }
                Image(painterResource(R.drawable.badge), contentDescription = null, modifier = Modifier.size(112.dp).scale(pulse))
            }
            Spacer(Modifier.height(20.dp))
            Text("Listening to your song…", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Cal.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {              // an old-school hi-fi graphic equaliser: stacked silver segments, each column dancing at its own pace
                val n = 18; val gap = 7.dp.toPx(); val bw = (size.width - gap * (n - 1)) / n
                val seg = 5.dp.toPx(); val sgap = 2.5.dp.toPx(); val rows = (size.height / (seg + sgap)).toInt()
                for (i in 0 until n) {
                    val k = 1 + i % 3
                    val lvl = 0.12f + 0.88f * Math.abs(Math.sin((phase * k + i * 0.7f).toDouble())).toFloat()
                    val lit = (lvl * rows).toInt().coerceIn(1, rows)
                    val peak = ((lvl + 0.06f) * rows).toInt().coerceIn(1, rows)
                    val x = i * (bw + gap)
                    for (r in 0 until rows) {
                        val y = size.height - (r + 1) * (seg + sgap) + sgap
                        val f = r / (rows - 1f)                                  // 0 at the bottom, 1 at the top
                        val on = r < lit || r == peak - 1
                        val c = if (on) Color(0xFF7F8EA6).let { lo -> Color(lo.red + (0.97f - lo.red) * f, lo.green + (0.98f - lo.green) * f, lo.blue + (0.99f - lo.blue) * f) } else Color(0x1ACBD5E1)
                        drawRoundRect(c, Offset(x, y), Size(bw, seg), CornerRadius(1.5.dp.toPx()))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(quip, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Cal.steel, textAlign = TextAlign.Center, minLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(26.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.width(180.dp).height(48.dp)) { Text("Cancel", fontSize = 16.sp) }
        }
    }
}
