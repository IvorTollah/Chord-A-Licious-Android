// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One drawn piece of an icon: an SVG path, filled and/or stroked (neither colour given = stroked in the tint). */
class IconOp(val d: String, val fill: Color? = null, val stroke: Color? = null, val sw: Float = 1.8f)

private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0 z"
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    val a = "a$rx $rx 0 0 1"
    return "M${x + rx} $y h${w - 2 * rx} $a $rx $rx v${h - 2 * rx} $a ${-rx} $rx h${-(w - 2 * rx)} $a ${-rx} ${-rx} v${-(h - 2 * rx)} $a $rx ${-rx} z"
}

/** Draws SVG-path icons (the website's own icons) at the given width; vw x vh is the SVG viewBox. */
@Composable
fun SvgIcon(ops: List<IconOp>, vw: Float, vh: Float, width: Dp, tint: Color = Cal.muted, modifier: Modifier = Modifier) {
    val paths = remember(ops) { ops.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(modifier.size(width, width * vh / vw)) {
        scale(size.width / vw, size.height / vh, pivot = Offset.Zero) {
            ops.forEachIndexed { i, o ->
                val f = o.fill
                if (f != null) drawPath(paths[i], f, style = Fill)
                if (o.stroke != null || f == null) drawPath(paths[i], o.stroke ?: tint, style = Stroke(o.sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private val RED = Color(0xFFFF0000)
private val WHITE = Color.White

val ICON_INSTRUMENT = listOf(IconOp("M19 3l2 2-6.2 6.2M14.8 11.2a3.2 3.2 0 0 0-4.4.4C9.3 12.7 9 14 8.1 14.6c-2.1 1.4-4.1 1.2-4.9 3.4-.5 1.5.3 3.3 1.9 3.8 1.5.4 2.6-.2 3.7-.9 1.5-1 2.2-3.3 3.7-4 1.3-.6 2.5-1.2 2.9-2.3"))
val ICON_CAPO = listOf(
    IconOp(rect(8f, 3f, 16f, 26f, 1.5f), stroke = Color(0xFF94A3B8), sw = 1.6f),
    IconOp("M8 10.5h16M8 20.5h16M13.3 3v26M18.7 3v26", stroke = Color(0xFF64748B), sw = 1.2f),
    IconOp(rect(4.5f, 12.5f, 23f, 6.5f, 3.2f), fill = Color(0xFFB45309)),
    IconOp(rect(4.5f, 12.5f, 23f, 6.5f, 3.2f), stroke = Color(0xFFFBBF24), sw = 1.6f))
val ICON_LEFTY = listOf(
    IconOp("M11 18V9.5a1.6 1.6 0 0 1 3.2 0V15M14.2 14V6.5a1.6 1.6 0 0 1 3.2 0V14M17.4 14V7.5a1.6 1.6 0 0 1 3.2 0V15M20.6 15v-4.5a1.6 1.6 0 0 1 3.2 0V19c0 5-3 8-7 8h-1c-3 0-5-1.5-7-4l-3.2-4.6a1.7 1.7 0 0 1 2.7-2L11 19.5", sw = 1.7f),
    IconOp("M5 6.5q-2.2 3 0 6M27.5 4.5q2.6 3.2 0 7", sw = 1.5f))
val ICON_METRO = listOf(IconOp("M8 21 10 3h4l2 18z", sw = 1.7f), IconOp("M12 17 16.5 6.5", sw = 1.7f), IconOp(circ(16.5f, 6.5f, 1.4f), fill = Color(0xFFCBD5E1)), IconOp("M6 21h12", sw = 1.7f))
val ICON_COUNTIN = listOf(
    IconOp("M16 25 L31 7", stroke = Color(0xFFDBEAFE), sw = 2f), IconOp("M32 25 L17 7", stroke = Color(0xFFDBEAFE), sw = 2f),
    IconOp(circ(31.6f, 6.3f, 2.1f), fill = Color(0xFFFBBF24)), IconOp(circ(16.4f, 6.3f, 2.1f), fill = Color(0xFFFBBF24)),
    IconOp("M3 29.5 Q3 24.5 8 24.5 H17 Q22 24.5 22 29.5 V35 Q22 38.5 18.5 38.5 H6.5 Q3 38.5 3 35 Z", fill = Color(0xFF0B1220)),
    IconOp("M3 29.5 Q3 24.5 8 24.5 H17 Q22 24.5 22 29.5 V35 Q22 38.5 18.5 38.5 H6.5 Q3 38.5 3 35 Z", stroke = Cal.steel, sw = 1.6f),
    IconOp("M8.2 24.5 V31 M12.5 24.5 V31 M16.8 24.5 V31", stroke = Cal.steel, sw = 1.2f),
    IconOp("M26 29.5 Q26 24.5 31 24.5 H40 Q45 24.5 45 29.5 V35 Q45 38.5 41.5 38.5 H29.5 Q26 38.5 26 35 Z", fill = Color(0xFF0B1220)),
    IconOp("M26 29.5 Q26 24.5 31 24.5 H40 Q45 24.5 45 29.5 V35 Q45 38.5 41.5 38.5 H29.5 Q26 38.5 26 35 Z", stroke = Cal.steel, sw = 1.6f),
    IconOp("M31.2 24.5 V31 M35.5 24.5 V31 M39.8 24.5 V31", stroke = Cal.steel, sw = 1.2f))
val ICON_EYE = listOf(IconOp("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", sw = 1.6f), IconOp(circ(12f, 12f, 3f), sw = 1.6f))
val ICON_STAR = listOf(IconOp("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z", stroke = Color(0xFFFBBF24)))
val ICON_PRINT = listOf(IconOp("M7 9V3h10v6M7 17H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2", stroke = Color(0xFF7DD3FC)), IconOp(rect(7f, 14f, 10f, 7f, 1f), stroke = Color(0xFF7DD3FC)))
/** A guitar headstock with its tuning pegs. */
val ICON_TUNING = listOf(
    IconOp(rect(8f, 2.5f, 8f, 13f, 2f), sw = 1.6f), IconOp("M10 15.5v6M14 15.5v6", sw = 1.6f),
    IconOp("M8 6H4.5M8 9.5H4.5M8 13H4.5M16 6h3.5M16 9.5h3.5M16 13h3.5", sw = 1.6f),
    IconOp(circ(3.4f, 6f, 1.3f), fill = Color(0xFFCBD5E1)), IconOp(circ(3.4f, 9.5f, 1.3f), fill = Color(0xFFCBD5E1)), IconOp(circ(3.4f, 13f, 1.3f), fill = Color(0xFFCBD5E1)),
    IconOp(circ(20.6f, 6f, 1.3f), fill = Color(0xFFCBD5E1)), IconOp(circ(20.6f, 9.5f, 1.3f), fill = Color(0xFFCBD5E1)), IconOp(circ(20.6f, 13f, 1.3f), fill = Color(0xFFCBD5E1)))
/** Up and down arrows: move the key up or down. */
val ICON_KEYSHIFT = listOf(IconOp("M7 20V4M3.5 7.5 7 4l3.5 3.5M17 4v16M13.5 16.5 17 20l3.5-3.5", sw = 1.9f))

private val TRI_VID = listOf(IconOp(rect(1f, 1f, 34f, 24f, 7f), fill = WHITE), IconOp("M14.5 7.5v11l9.5-5.5z", fill = RED))
private val TRI_MUS = listOf(IconOp(circ(13f, 13f, 12.2f), fill = WHITE),
    IconOp("M6 9.6c4.7-1.4 10-1 14.4 1.4", stroke = RED, sw = 2.1f), IconOp("M6.9 13.7c3.9-1.1 8-.7 11.5 1.1", stroke = RED, sw = 2.1f), IconOp("M7.8 17.4c3.1-.8 6.2-.5 8.9 1", stroke = RED, sw = 2.1f))
private val TRI_FIL = listOf(IconOp("M1.5 5.5a2.5 2.5 0 0 1 2.5-2.5h6.5l2.6 3h10.9A2.5 2.5 0 0 1 26.5 8.5v13A2.5 2.5 0 0 1 24 24H4a2.5 2.5 0 0 1-2.5-2.5z", fill = WHITE),
    IconOp("M14 10v8m-4-4h8", stroke = RED, sw = 2.1f))

/** The website's "Import a Song" icon: a red pill holding YouTube, Spotify and a folder with a plus. */
@Composable
fun ImportTriptych(k: Float = 1f) {
    Row(Modifier.padding(horizontal = (11 * k).dp, vertical = (7 * k).dp), horizontalArrangement = Arrangement.spacedBy((9 * k).dp), verticalAlignment = Alignment.CenterVertically) {
        for ((ops, vw) in listOf(TRI_VID to 36f, TRI_MUS to 26f, TRI_FIL to 28f)) GlowOutline(ops, vw, 26f, (vw * k).dp)
    }
}

private val SILVER_HI = Color(0xFFF1F5F9)
/** An icon redrawn as a glowing silver outline (a soft wide stroke under a crisp thin one). */
@Composable
fun GlowOutline(ops: List<IconOp>, vw: Float, vh: Float, width: Dp) {
    val glow = remember(ops) { ops.map { IconOp(it.d, stroke = SILVER_HI.copy(alpha = 0.16f), sw = 3.6f) } }
    val sharp = remember(ops) { ops.map { IconOp(it.d, stroke = Color(0xFFF8FAFC), sw = 2.1f) } }
    Box(contentAlignment = Alignment.Center) { SvgIcon(glow, vw, vh, width); SvgIcon(sharp, vw, vh, width) }
}

/** A glyph in glowing silver: a soft translucent copy, a little larger, under the crisp one. */
@Composable
fun GlowIcon(size: Dp = 30.dp, glyph: @Composable (Color, Dp) -> Unit) {
    Box(contentAlignment = Alignment.Center) { glyph(SILVER_HI.copy(alpha = 0.14f), size + 5.dp); glyph(Color(0xFFF8FAFC), size) }
}

/** A tall square-ish button with an icon over a small label, like the website's Favourites and Print / PDF buttons. */
@Composable
fun ActionTile(icon: List<IconOp>, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Color(0xFF16233A)).border(1.dp, Cal.border, shape).clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SvgIcon(icon, 24f, 24f, 30.dp)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cal.muted, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Lettering in glowing silver: a white-to-steel gradient with a soft halo. */
@Composable
fun GlowText(text: String, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Bold, align: TextAlign? = null) {
    Text(text, modifier, fontSize = size, fontWeight = weight, textAlign = align,
        style = androidx.compose.ui.text.TextStyle(brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD5DCE6), Color(0xFF9AA8BC))),
            shadow = androidx.compose.ui.graphics.Shadow(Color(0x77E2E8F0), Offset.Zero, 5f)))
}

/** The big picture on an Import choice: kind 0 = a folder with a plus (audio file), 1 = the YouTube play button (link). */
@Composable
fun ImportChoiceIcon(kind: Int, k: Float = 2f) {
    val shape = RoundedCornerShape(50)
    if (kind == 0) Box(Modifier.shadow(10.dp, shape, ambientColor = Cal.steel, spotColor = Cal.steel).clip(shape)
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF19B86B)))).padding((14 * k / 2).dp), Alignment.Center) { SvgIcon(TRI_FIL, 28f, 26f, (28 * k).dp) }
    else Box(Modifier.shadow(10.dp, shape, ambientColor = RED, spotColor = RED).clip(shape).background(RED).padding(horizontal = (16 * k / 2).dp, vertical = (12 * k / 2).dp), Alignment.Center) { SvgIcon(TRI_VID, 36f, 26f, (36 * k).dp) }
}


/** A transport / action button that shows bright silver while it is pressed (no blue ripple). [strong] is the filled Play button; [active] gives a toggled colour. */
@Composable
fun NavBtn(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, strong: Boolean = false, active: Color? = null,
           padH: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable (Color) -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    val dark = Color(0xFF0F172A)
    val bg = if (down) Color(0xFFF8FAFC) else active ?: if (strong) Color(0xFF94A3B8) else Color.Transparent
    val fg = if (down || strong || active != null) dark else Color(0xFFE2E8F0)
    Box(modifier.alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg)
        .border(1.dp, if (down) Color.White else Color(0xFF8A99B0), shape)
        .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() }.padding(horizontal = padH), Alignment.Center) { content(fg) }
}

/** An icon that scales (keeping its shape) to fill whatever room it is given. */
@Composable
fun SvgIconFit(ops: List<IconOp>, vw: Float, vh: Float, modifier: Modifier = Modifier, tint: Color = Cal.muted) {
    val paths = remember(ops) { ops.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(modifier) {
        val k = minOf(size.width / vw, size.height / vh)
        translate((size.width - vw * k) / 2, (size.height - vh * k) / 2) {
            scale(k, k, pivot = Offset.Zero) {
                ops.forEachIndexed { i, o ->
                    val f = o.fill
                    if (f != null) drawPath(paths[i], f, style = Fill)
                    if (o.stroke != null || f == null) drawPath(paths[i], o.stroke ?: tint, style = Stroke(o.sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** The red import button: the three icons fill the whole button. */
@Composable
fun ImportFill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.shadow(8.dp, shape, ambientColor = RED, spotColor = RED).clip(shape).background(RED).clickable { onClick() }.padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIconFit(TRI_VID, 36f, 26f, Modifier.weight(36f).fillMaxHeight()); SvgIconFit(TRI_MUS, 26f, 26f, Modifier.weight(26f).fillMaxHeight()); SvgIconFit(TRI_FIL, 28f, 26f, Modifier.weight(28f).fillMaxHeight())
    }
}


/** A raised, 3D-looking tile: a glossy face sitting on a dark ledge. It sinks onto the ledge while it is pressed. */
@Composable
fun Tile3D(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, radius: androidx.compose.ui.unit.Dp = 18.dp,
           content: @Composable ColumnScope.() -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val sink by androidx.compose.animation.core.animateDpAsState(if (down) 5.dp else 0.dp, label = "sink")
    val shape = RoundedCornerShape(radius)
    val silver = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFF9FB0C8), Color(0xFF6F819C)))
    Box(modifier.padding(bottom = 6.dp).alpha(if (enabled) 1f else 0.5f)) {
        Box(Modifier.matchParentSize().offset(y = 6.dp).shadow(if (down) 1.dp else 6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape).background(Color(0xFF070C16)))
        Column(Modifier.fillMaxSize().offset(y = sink).clip(shape)
            .background(Color(0xFF1B2A47))                                                      // the panel's own blue, raised on a darker ledge
            .drawBehind {                                                                       // a faint light along the top edge
                drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x18FFFFFF), Color(0x00FFFFFF)), 0f, size.height * 0.4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()))
            }
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
    }
}


/** One line of text that shrinks until it fits the room it has (chord names in the measure boxes). */
@Composable
fun FitText(text: String, maxSp: Float, modifier: Modifier = Modifier, color: Color = Cal.steel, minSp: Float = 10f) {
    var size by remember(text, maxSp) { androidx.compose.runtime.mutableFloatStateOf(maxSp) }
    Text(text, modifier.fillMaxWidth(), fontSize = size.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center,
        onTextLayout = { r -> if (r.didOverflowWidth && size > minSp) size = maxOf(minSp, size * 0.9f) })
}

/** One line of text that shrinks until it fits its box (restarts at full size for each new line). */
@Composable
fun AutoFitLine(text: String, maxSp: Float, modifier: Modifier = Modifier, color: Color = Color.Unspecified, minSp: Float = 9f) {
    var size by remember(text, maxSp) { androidx.compose.runtime.mutableFloatStateOf(maxSp) }
    Text(text, modifier.fillMaxWidth(), fontSize = size.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = color,
        onTextLayout = { r -> if (r.didOverflowWidth && size > minSp) size = maxOf(minSp, size * 0.92f) })
}
