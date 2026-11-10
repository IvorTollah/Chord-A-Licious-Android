// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.absoluteValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.withContext

private fun clock(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
private val PIE = listOf(Color(0xFF38BDF8), Color(0xFF19FF5C), Color(0xFFFBBF24), Color(0xFFA78BFA), Color(0xFFFB7185), Color(0xFF2DD4BF))

class Group(val first: Int, val last: Int, val label: String, val start: Double, val end: Double) { var span = 1; var keyTag: String? = null }

private class Slice(val name: String, val secs: Double, val plays: Int, val color: Color, val count: Int = 0)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(api: Api, song: Song, full: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // "Add chords" shares one measure out between several chords: original bar index -> the chords (un-transposed) now in it
    val splits = remember { mutableStateMapOf<Int, List<String>>() }
    val bars: List<Bar> = remember(splits.toMap()) {
        val out = ArrayList<Bar>()
        song.bars.forEachIndexed { o, b ->
            val seq = splits[o]
            if (seq == null || seq.size < 2) { out += if (seq?.size == 1) b.copy(chord = seq[0], detail = seq[0]) else b; return@forEachIndexed }
            val end = if (b.end > b.start) b.end else song.bars.getOrNull(o + 1)?.start ?: b.start
            val len = (end - b.start) / seq.size
            seq.forEachIndexed { k, c -> out += Bar(b.num, c, c, b.start + k * len, b.start + (k + 1) * len) }
        }
        out
    }
    val origOf: List<Int> = remember(splits.toMap()) {
        val out = ArrayList<Int>()
        song.bars.forEachIndexed { o, _ -> repeat(maxOf(1, splits[o]?.size ?: 1)) { out += o } }
        out
    }
    // ---- playback
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0.0) }
    var dur by remember { mutableStateOf(song.duration) }
    var dragging by remember { mutableStateOf(false) }
    var audioMsg by remember { mutableStateOf<String?>(if (song.audioUrl.isBlank()) (if (song.linkUrl.isNotBlank()) "No audio in the app for this song. Play it on YouTube and follow the chords." else "No audio to play for this song.") else "Loading the audio…") }
    var loadedOnce by remember { mutableStateOf(false) }
    var artBig by remember { mutableStateOf(false) }
    var counting by remember { mutableIntStateOf(0) }
    var bigShow by remember { mutableStateOf(false) }           // the big orange count-in number
    var countJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // ---- settings
    var speed by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var shift by remember { mutableIntStateOf(0) }
    var capo by remember { mutableIntStateOf(0) }
    var left by remember { mutableStateOf(false) }
    var phrasing by remember { mutableStateOf(Phrasing.Open) }
    var countIn by remember { mutableStateOf(false) }
    var countBeats by remember { mutableIntStateOf(4) }       // 1..12
    var detailed by remember { mutableStateOf(false) }
    var simplify by remember { mutableStateOf(!full) }          // Free: always on
    var hideLyrics by remember { mutableStateOf(false) }
    var favId by remember { mutableStateOf(song.favId) }           // set once this song is one of the person's Favourites
    var favCat by remember { mutableStateOf(song.favCat) }
    var titleOv by remember { mutableStateOf<String?>(null) }
    var saveDlg by remember { mutableStateOf(false) }
    val templates = remember { mutableStateMapOf<String, Tpl>() }     // Pro: "use this fingering for every future song"
    var showTpl by remember { mutableStateOf(false) }
    LaunchedEffect(full) { if (full) try { templates.clear(); templates.putAll(api.templates()) } catch (_: Exception) {} }
    var printDlg by remember { mutableStateOf(false) }
    var printDiagrams by remember { mutableStateOf(true) }
    var printLyrics by remember { mutableStateOf(true) }
    var printCondensed by remember { mutableStateOf(false) }
    var printCols by remember { mutableIntStateOf(4) }
    var printing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var instrument by remember { mutableStateOf("Guitar") }        // Pro: Bass, Ukulele, Banjo, Mandolin, Keys
    var tuning by remember { mutableStateOf("Standard") }          // Pro: the website's ten tunings
    var buskerShape by remember { mutableIntStateOf(0) }          // the chord shape under the busker grid: 0 = full, 1 = minimised, 2 = removed
    var busker by remember { mutableStateOf(false) }               // Busker mode: just the grid and a control bar
    var muted by remember { mutableStateOf(false) }
    // metronome (Pro)
    var metroOn by remember { mutableStateOf(false) }
    var metroCustom by remember { mutableStateOf<Int?>(null) }     // null = follow the song's own measures
    var metroVol by remember { mutableFloatStateOf(0.7f) }
    // Drag & Loop (Pro)
    var loopArmed by remember { mutableStateOf(false) }            // picking measures
    var loopAnchor by remember { mutableStateOf<Int?>(null) }      // first tapped box, waiting for the last
    var loopRange by remember { mutableStateOf<IntRange?>(null) }  // the boxes picked (box indexes)
    var loopOn by remember { mutableStateOf(false) }
    var loopStart by remember { mutableDoubleStateOf(0.0) }
    var loopEnd by remember { mutableDoubleStateOf(0.0) }
    val boxRects = remember { HashMap<Int, androidx.compose.ui.geometry.Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val slow = remember { mutableStateMapOf<Int, Float>() }      // first bar of a measure box -> slow factor
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var slowFor by remember { mutableStateOf<Group?>(null) }
    var slowVal by remember { mutableFloatStateOf(0.6f) }
    var art by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    // ---- edits (stored as the un-transposed chord name)
    val edits = remember { mutableStateMapOf<Int, String>() }
    class Swap(val mode: Phrasing, val i: Int)
    val swapAll = remember { mutableStateMapOf<String, Swap>() }      // chord (un-transposed) -> the fingering chosen for every use of it
    val swapBar = remember { mutableStateMapOf<String, Swap>() }      // "measure|chord" -> the fingering chosen for that measure only
    var summaryEdits by remember { mutableIntStateOf(0) }
    var freeEdits by remember { mutableIntStateOf(0) }
    var editBar by remember { mutableStateOf<Int?>(null) }
    var editSound by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    LaunchedEffect(song.artUrl) { if (song.artUrl.isNotBlank()) art = api.loadArt(song.artUrl) }

    fun baseOf(i: Int): String { val b = edits[i] ?: (if (detailed) bars[i].detail ?: bars[i].chord else bars[i].chord); return if (simplify) b.substringBefore("/") else b }
    fun sound(i: Int) = transposeChord(baseOf(i), shift)
    val webInst = if (instrument == "Keys") "Keyboard" else instrument
    val tunName = if (instrument == "Guitar") tuning else "Standard"
    fun tplKey(base: String): String {                              // the same key the website uses, so templates follow the account
        val p = parseChord(base)
        val c = if (p == null) base else "${p.root}|" + (when (p.suffix) { "maj" -> ""; "min" -> "m"; else -> p.suffix }) + (p.bass?.let { "|$it" } ?: "")
        return "$webInst|$tunName|$c"
    }
    fun sigOf(sh: Shape) = (if (sh.keys) "K" else "F") + sh.frets.joinToString(",")
    fun saveTpl() { scope.launch { try { api.saveTemplates(templates.toMap()) } catch (e: ApiError) { note = e.msg } catch (_: Exception) {} } }
    fun label(i: Int) = transposeChord(sound(i), -capo, preferFlat = false)
    // a fingering the player has chosen (for one measure, for every use of the chord, or a saved template)
    fun pinnedAt(i: Int, lab: String): Shape? {
        val k = baseOf(i); val sp = swapBar["${origOf.getOrElse(i) { i }}|$k"] ?: swapAll[k]
        if (sp != null) optionsIn(lab, tuning, instrument).firstOrNull { it.mode == sp.mode && it.i == sp.i }?.let { return it.shape }
        if (full) templates[tplKey(k)]?.let { t ->
            val os = optionsIn(lab, tuning, instrument)
            (os.firstOrNull { sigOf(it.shape) == t.sig } ?: os.firstOrNull { it.mode.name == t.mode && it.i == t.i })?.let { return it.shape }
        }
        return null
    }
    // the other fingerings are chosen so the hand moves as little as possible from chord to chord (the website's "shortest hop every time")
    val labelList = remember(bars, shift, capo, simplify, detailed, edits.entries.sumOf { it.key * 31 + it.value.hashCode() }) { bars.indices.map { label(it) } }
    val plan = remember(labelList, phrasing, tuning, instrument, swapBar.toMap(), swapAll.toMap(), templates.toMap(), full) {
        planShapes(labelList, phrasing, tuning, instrument) { i -> pinnedAt(i, labelList[i]) }
    }
    fun shapeAt(i: Int, lab: String): Shape? {
        pinnedAt(i, lab)?.let { return it }
        if (labelList.getOrNull(i) == lab) plan.getOrNull(i)?.let { return it }
        return shapeIn(lab, phrasing, tuning, instrument)
    }
    fun needPro(what: String) { note = "$what is part of Pro." }

    // ---- audio (reloaded when the key is shifted: the server makes the shifted copy)
    LaunchedEffect(song.audioUrl, shift) {
        if (song.audioUrl.isBlank()) return@LaunchedEffect
        val wasPlaying = playing; val at = pos
        try { player?.release() } catch (_: Exception) {}
        player = null; playing = false
        audioMsg = if (loadedOnce) "Shifting the audio to the new key..." else "Loading the audio…"
        try {
            val f = api.downloadAudio(song.audioUrl + if (shift != 0) "?shift=$shift" else "")
            val mp = withContext(Dispatchers.IO) { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }
            mp.setOnCompletionListener { playing = false; pos = 0.0; mp.seekTo(0) }
            if (mp.duration > 0) dur = mp.duration / 1000.0
            mp.setVolume(volume, volume)
            if (at > 0) mp.seekTo((at * 1000).toInt())
            player = mp; audioMsg = null; loadedOnce = true
            if (wasPlaying) { mp.start(); try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) {}; playing = true }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e                  // a newer key change replaced this load: not an error
        } catch (e: ApiError) { audioMsg = e.msg; if (shift != 0) shift = 0 }
        catch (e: Exception) { audioMsg = "Couldn't load the audio for playback." }
    }
    DisposableEffect(Unit) { onDispose { try { player?.release() } catch (_: Exception) {} } }
    LaunchedEffect(playing) {
        while (playing) {
            if (!dragging) {
                pos = (player?.currentPosition ?: 0) / 1000.0
                if (loopOn && loopEnd > loopStart && pos >= loopEnd) { player?.seekTo((loopStart * 1000).toInt()); pos = loopStart }   // back to the start of the loop
            }
            delay(if (loopOn) 40 else 100)
        }
    }
    LaunchedEffect(muted, volume, player) { val v = if (muted) 0f else volume; try { player?.setVolume(v, v) } catch (_: Exception) {} }
    val view = LocalView.current
    DisposableEffect(busker) { view.keepScreenOn = busker; onDispose { view.keepScreenOn = false } }

    val cur = bars.indexOfLast { it.start <= pos + 0.05 }.coerceAtLeast(0)   // the measure the song will play from, even before Play
    val keySegs = remember(edits.toMap(), detailed, simplify, bars) { keySegments(bars.indices.map { baseOf(it) }, song.key) }
    val keyStarts = keySegs.drop(1).map { it.start }.toSet()      // a key change always starts a new box, as on the website
    // repeated identical chords in a row share one box
    val groups = remember(edits.toMap(), shift, capo, detailed, simplify, bars, keySegs) {
        val out = ArrayList<Group>(); var i = 0
        while (i < bars.size) {
            val l = label(i); var j = i
            while (j + 1 < bars.size && label(j + 1) == l && (j + 1) !in keyStarts) j++
            val end = if (bars[j].end > bars[j].start) bars[j].end else bars.getOrNull(j + 1)?.start ?: bars[j].start
            out += Group(i, j, l, bars[i].start, end).also { g -> if (i in keyStarts) keySegs.firstOrNull { it.start == i }?.let { g.keyTag = "Key → " + keyName(it, shift) } }; i = j + 1
        }
        var col = 0
        out.forEach { g -> var sp = minOf(g.last - g.first + 1, 4); if (sp > 4 - col) sp = 4 - col; g.span = sp; col = (col + sp) % 4 }
        out
    }
    val curG = if (cur < 0) -1 else groups.indexOfFirst { cur in it.first..it.last }
    // keep the measure being played fully in view: with a tall grid show a little history above it, with a short one (landscape busker) put it at the top
    suspend fun showCurrent(animate: Boolean) {
        if (curG < 0) return
        val info = grid.layoutInfo
        val vis = info.visibleItemsInfo
        val sz = vis.firstOrNull()?.size?.height ?: 0
        val top = if (sz > 0 && info.viewportSize.height >= sz * 3) (curG - 3).coerceAtLeast(0) else curG
        val me = vis.firstOrNull { it.index == curG }
        val fine = me != null && me.offset.y >= 0 && me.offset.y + me.size.height <= info.viewportEndOffset - info.afterContentPadding
        if (animate) { if (!fine || top != grid.firstVisibleItemIndex) grid.animateScrollToItem(top) } else if (!fine) grid.scrollToItem(top)
    }
    LaunchedEffect(curG) { if (playing && curG >= 0) showCurrent(true) }
    val orient = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orient, busker, buskerShape) { delay(250); showCurrent(false) }          // after a turn of the phone, re-centre on the measure being played
    // the key playing right now (it changes where the song modulates); amber when it differs from the starting key
    val keyIdx = keySegs.indexOfLast { it.start <= maxOf(cur, 0) }.coerceAtLeast(0)
    val keyNow = keySegs.getOrNull(keyIdx)?.let { keyName(it, shift) } ?: if (song.key.isNotBlank()) transposeKey(song.key, shift) else ""
    val keyChanged = keySegs.size > 1 && keySegs[keyIdx].let { it.tonic != keySegs[0].tonic || it.minor != keySegs[0].minor }
    val effSpeed = speed * (slow[groups.getOrNull(curG)?.first] ?: 1f)
    // beats until the chord changes
    val beatsLeft: Int? = run {
        if (!playing || cur < 0 || song.bpm <= 0) return@run null
        val l = label(cur); var j = cur + 1
        while (j < bars.size && label(j) == l) j++
        val next = if (j < bars.size) bars[j].start else dur
        Math.ceil((next - pos) * song.bpm / 60.0).toInt().coerceAtLeast(1)
    }
    val lyricNow = if (pos <= 0.0 && !playing) -1 else song.lyrics.indexOfLast { it.t <= pos }

    fun seek(sec: Double) {
        pos = sec.coerceIn(0.0, dur); player?.seekTo((pos * 1000).toInt())
        val gi = groups.indexOfLast { it.start <= pos + 0.05 }.coerceAtLeast(0)
        scope.launch { grid.animateScrollToItem((gi - 3).coerceAtLeast(0)) }
    }
    fun applySpeed() { player?.let { try { it.playbackParams = it.playbackParams.setSpeed(effSpeed) } catch (_: Exception) {} } }
    LaunchedEffect(effSpeed) { if (playing) applySpeed() }

    // ---- the Tasty-O-Meter / chord summary (seconds per chord, as on the website)
    val slices = remember(edits.toMap(), shift, detailed, simplify, bars) {
        val secs = LinkedHashMap<String, Double>(); val plays = HashMap<String, Int>(); var prev: String? = null
        for (i in bars.indices) {
            val n = sound(i); if (n == "N.C." || n.isBlank()) continue
            if (n != prev) plays[n] = (plays[n] ?: 0) + 1
            prev = n
            val d = (bars[i].end - bars[i].start).coerceAtLeast(0.0)
            secs[n] = (secs[n] ?: 0.0) + d
        }
        var rows = secs.entries.sortedByDescending { it.value }.map { Triple(it.key, it.value, plays[it.key] ?: 0) }
        val out = ArrayList<Slice>(); var pi = 0
        if (rows.size > 6) {
            rows.take(5).forEach { out += Slice(it.first, it.second, it.third, PIE[pi++ % PIE.size]) }
            val rest = rows.drop(5); out += Slice("Other", rest.sumOf { it.second }, rest.sumOf { it.third }, Color(0xFF475569), rest.size)
        } else rows.forEach { out += Slice(it.first, it.second, it.third, PIE[pi++ % PIE.size]) }
        out
    }

    fun editGroup(g: Group, name: String) {
        for (i in g.first..g.last) edits[i] = transposeChord(name, -shift)
        freeEdits++
    }
    fun setSlow(g: Group, f: Float?) {
        if (f == null) { slow.remove(g.first); return }
        if (!full && slow.isNotEmpty() && g.first !in slow) { needPro("Slowing more than one measure per song"); return }
        slow[g.first] = f
    }
    fun prevBar() { seek(bars[((if (cur < 0) 0 else cur) - 1).coerceAtLeast(0)].start) }
    fun nextBar() { seek(bars[((if (cur < 0) -1 else cur) + 1).coerceAtMost(bars.size - 1)].start) }
    fun click() = Thread {                     // the website's metronome click: 1175 Hz square wave, 45 ms
        try {
            val sr = 44100; val n = sr * 60 / 1000
            val buf = ShortArray(n) { i -> val t = i.toDouble() / sr; val env = if (t < 0.002) t / 0.002 else Math.exp(-(t - 0.002) / 0.0095)
                ((if (Math.sin(2 * Math.PI * 1175 * t) >= 0) 1.0 else -1.0) * env * 0.6 * 32767 * 0.5).toInt().toShort() }
            val tr = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
            tr.write(buf, 0, n); tr.play(); Thread.sleep(120); tr.release()
        } catch (_: Exception) {}
    }.start()
    // ---- metronome: the website's click, locked to the song's playback position
    val metroTrack = remember {
        try {
            val sr = 44100; val n = sr * 60 / 1000
            val buf = ShortArray(n) { i -> val t = i.toDouble() / sr; val env = if (t < 0.002) t / 0.002 else Math.exp(-(t - 0.002) / 0.0095)
                ((if (Math.sin(2 * Math.PI * 1175 * t) >= 0) 1.0 else -1.0) * env * 0.6 * 32767 * 0.5).toInt().toShort() }
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build().also { it.write(buf, 0, n) }
        } catch (_: Exception) { null }
    }
    DisposableEffect(Unit) { onDispose { try { metroTrack?.release() } catch (_: Exception) {} } }
    fun metroPlay() { try { metroTrack?.let { it.setVolume(metroVol); it.stop(); it.reloadStaticData(); it.play() } } catch (_: Exception) {} }
    val beatTimes: DoubleArray = remember(song.bars, metroCustom, dur) {
        val out = ArrayList<Double>()
        if (song.bars.isEmpty() || song.bpm <= 0) return@remember DoubleArray(0)
        val cb = metroCustom
        if (cb == null) song.bars.forEachIndexed { o, b ->
            val end = if (b.end > b.start) b.end else song.bars.getOrNull(o + 1)?.start ?: dur
            val d = end - b.start
            if (d > 0) { val nb = Math.round(d * song.bpm / 60.0).toInt().coerceIn(1, 12); val len = d / nb; for (q in 0 until nb) out += b.start + q * len }
        } else { val a0 = song.bars[0].start; val step = 60.0 / cb; var k = 0; while (a0 + k * step < dur) { out += a0 + k * step; k++ } }
        out.toDoubleArray()
    }
    LaunchedEffect(playing, metroOn, beatTimes, effSpeed) {
        if (!playing || !metroOn) return@LaunchedEffect
        var last = (player?.currentPosition ?: 0) / 1000.0 - 0.02
        while (true) {
            val mp = player ?: break
            val t = mp.currentPosition / 1000.0
            if (t < last - 0.3) last = t - 0.02                       // the song was moved: start again from here
            val upto = t + 0.03 * effSpeed
            for (b in beatTimes) if (b > last + 1e-6 && b <= upto) metroPlay()
            if (upto > last) last = upto
            delay(8)
        }
    }
    fun toggleLoop() {
        if (loopOn || loopArmed) { loopOn = false; loopArmed = false; loopAnchor = null; loopRange = null }
        else { loopArmed = true; loopAnchor = null; loopRange = null }
    }
    fun togglePlay() {
        val mp = player ?: return
        if (counting > 0) { countJob?.cancel(); counting = 0; bigShow = false }
        else if (playing) { mp.pause(); playing = false }
        else countJob = scope.launch {
            if (countIn && song.bpm > 0) {
                val gap = (60000.0 / (song.bpm * speed)).toLong()
                for (n in 1..countBeats) { counting = n; bigShow = true; click(); delay(minOf(400L, (gap * 0.7).toLong())); bigShow = false; delay((gap - minOf(400L, (gap * 0.7).toLong())).coerceAtLeast(0)) }
                counting = 0
            }
            mp.start(); applySpeed(); playing = true
        }
    }

    val headerBlock: @Composable (Boolean) -> Unit = { compact ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                art?.let { Image(it.asImageBitmap(), "Album art", Modifier.size(if (compact) 32.dp else 38.dp).clip(RoundedCornerShape(8.dp)).clickable { artBig = true }) }
                Column(Modifier.weight(1f)) {
                    Text(titleOv ?: song.title, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (song.artist.isNotBlank()) Text(song.artist, color = Cal.muted, fontSize = 12.sp, maxLines = 1)
                }
                if (keyNow.isNotBlank()) KeyBadge(keyNow, keyChanged)
            }
    }
    val lyricsBlock: @Composable (Boolean, Boolean) -> Unit = { compact, fill ->
        Row(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Box(if (fill) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f)) {
            // lyrics stay stuck at the top, as on the website
            if (!hideLyrics) Panel(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(min = if (compact) 36.dp else 40.dp), pad = 7.dp, space = 1.dp) {
                if (counting > 0) Text("$counting", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Cal.steel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                else if (song.lyrics.isNotEmpty()) {
                    AutoFitLine(if (lyricNow >= 0) song.lyrics[lyricNow].text else "♪", if (compact) 13f else 14f, minSp = 8f)
                    Text(song.lyrics.getOrNull(lyricNow + 1)?.text ?: "", fontSize = if (compact) 10.sp else 11.sp, color = Cal.muted, maxLines = 1)
                } else Text("No synced lyrics for this song.", color = Cal.muted, fontSize = 13.sp)
            } else if (counting > 0) Text("$counting", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Cal.steel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            // open another song, tucked under the key shield
            ImportFill(onBack, Modifier.width(104.dp).fillMaxHeight())
        }
    }
    val chordsBlock: @Composable (Boolean) -> Unit = { compact ->
        // chord carousel: a pager of the song's chords. The playing one is big, in the middle, with a silver glow; the ones either side
        // are smaller. Swipe, or tap a neighbour, to go there (the song follows); while it plays the carousel glides along by itself.
        val segs = ArrayList<Int>().also { l -> var last = ""; for (i in bars.indices) { val n = label(i); if (i == 0 || n != last) l.add(i); last = n } }
        val playSeg = segs.indexOfLast { it <= cur }.coerceAtLeast(0)
        if (segs.isNotEmpty()) {
                        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = playSeg, pageCount = { segs.size })
            val dragged by pager.interactionSource.collectIsDraggedAsState()
            var userMoved by remember { mutableStateOf(false) }
            LaunchedEffect(dragged) { if (dragged) userMoved = true }
            // the song moved on (playing, or a measure was tapped): glide the carousel to the chord now playing
            LaunchedEffect(playSeg) {
                if (!pager.isScrollInProgress && pager.currentPage != playSeg) pager.animateScrollToPage(playSeg, animationSpec = tween(450, easing = FastOutSlowInEasing))
            }
            // the user settled on another chord: the song follows
            LaunchedEffect(pager.settledPage) {
                if (userMoved) {
                    userMoved = false
                    val t = segs.getOrNull(pager.settledPage)
                    if (t != null && pager.settledPage != playSeg) seek(bars[t].start)
                }
            }
            val frame = RoundedCornerShape(12.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = if (compact) 700.dp else 372.dp).shadow(10.dp, frame, ambientColor = Color(0xFFCBD5E1), spotColor = Color(0xFFCBD5E1)).clip(frame)
                .border(1.5.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFE2E8F0), Color(0xFF7F8EA6), Color(0xFFCBD5E1))), frame).padding(vertical = if (compact) 2.dp else 4.dp)) {
                val cw = if (compact) ((maxWidth - 8.dp) / 3).coerceAtMost(150.dp) else 116.dp          // landscape: exactly three chords across the width of the plates below
                val side = ((maxWidth - cw) / 2).coerceAtLeast(0.dp)
                androidx.compose.foundation.pager.HorizontalPager(pager, contentPadding = PaddingValues(horizontal = side),
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(cw), key = { it }, verticalAlignment = Alignment.Bottom) { page ->
                    val first = segs[page]; val isNow = page == playSeg
                    val bi = if (isNow) cur else first
                    val name = label(bi)
                    val gs = RoundedCornerShape(12.dp)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceAtMost(1f)
                            val ahead = page > playSeg                                     // the chord coming up stays big and bright so it can be learnt in advance
                            val sc = 1f - (if (ahead) 0.12f else 0.3f) * off; scaleX = sc; scaleY = sc; alpha = 1f - (if (ahead) 0.05f else 0.45f) * off
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                        .drawBehind {                                        // the silver frame fades in as this page reaches the middle
                            val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceAtMost(1f)
                            val a = 1f - off; val r = CornerRadius(12.dp.toPx())
                            drawRoundRect(Color(0x14E2E8F0), cornerRadius = r, alpha = a)
                            drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFE2E8F0), Color(0xFF8A99B0), Color(0xFFCBD5E1))),
                                cornerRadius = r, alpha = a, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        }
                        .clip(gs)
                        .combinedClickable(onClick = { if (!isNow) seek(bars[first].start) }, onLongClick = { editBar = bi })
                        .padding(horizontal = 6.dp, vertical = 3.dp), Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (page == playSeg + 1) Text("NEXT", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Cal.steel, letterSpacing = 1.sp)
                            ChordDiagram(name, shapeAt(bi, name), left, width = cw - 20.dp, highlight = isNow, beats = if (isNow) beatsLeft else null, capo = capo, dim = page < playSeg)
                        }
                    }
                }
            }
        }
    }
    val gridBlock: @Composable (Modifier) -> Unit = { gm ->
            LazyVerticalGrid(GridCells.Fixed(4), state = grid, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = gm.onGloballyPositioned { gridOrigin = it.positionInRoot() }.pointerInput(loopArmed, groups) {
                    if (!loopArmed) return@pointerInput
                    fun hit(p: Offset): Int { val r = p + gridOrigin; return boxRects.entries.firstOrNull { it.value.contains(r) }?.key ?: -1 }
                    awaitEachGesture {                                         // drag across the measures to loop them, or tap the first and the last
                        val down = awaitFirstDown(pass = PointerEventPass.Initial); down.consume()
                        val a = hit(down.position); var b = a
                        if (a >= 0) loopRange = a..a
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial); val ch = ev.changes.first(); ch.consume()
                            val h = hit(ch.position); if (h >= 0) b = h
                            if (a >= 0 && b >= 0) loopRange = minOf(a, b)..maxOf(a, b)
                            if (!ch.pressed) break
                        }
                        if (a < 0) return@awaitEachGesture
                        var lo = minOf(a, b); var hi = maxOf(a, b)
                        val anchor = loopAnchor
                        if (lo == hi) {                                            // a tap: the first one marks the start, the second one the end (the same box twice loops that box)
                            if (anchor == null) { loopAnchor = a; loopRange = a..a; note = "Now tap the last measure of the loop."; return@awaitEachGesture }
                            lo = minOf(anchor, a); hi = maxOf(anchor, a)
                        }
                        if (groups[hi].last - groups[lo].first + 1 < 2) { loopRange = lo..lo; loopAnchor = a; note = "Pick at least two measures."; return@awaitEachGesture }
                        loopStart = groups[lo].start; loopEnd = groups[hi].end; loopRange = lo..hi; loopOn = true; loopArmed = false; loopAnchor = null; note = null
                        seek(loopStart)
                    }
                }) {
                items(groups.size, span = { gi -> GridItemSpan(groups[gi].span) }) { gi ->
                    val g = groups[gi]; val shape = RoundedCornerShape(10.dp); val on = gi == curG
                    val inLoop = loopRange?.let { gi in it } == true
                    DisposableEffect(gi) { onDispose { boxRects.remove(gi) } }
                    val edited = (g.first..g.last).any { it in edits }
                    val frac = if (on) ((pos - g.start) / (g.end - g.start).coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { boxRects[gi] = it.boundsInRoot() }.clip(shape).background(if (inLoop) Color(0xFF3A2F12) else Cal.card)
                        .then(if (on) Modifier.drawBehind {            // playing: the silver wipe and ONE silver border, both over the whole box
                            val x = size.width * frac
                            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0x22D7DEE8), Color(0x66D7DEE8)), 0f, maxOf(x, 1f)), Offset.Zero, Size(x, size.height))
                            drawLine(Color(0x33CBD5E1), Offset(x, 0f), Offset(x, size.height), 14f)
                            drawLine(Color(0xFFCBD5E1), Offset(x, 0f), Offset(x, size.height), 3f)
                            val spread = size.width * 0.5f; val w = 2.dp.toPx()
                            val glow = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF8A99B0), Color(0xFFF8FAFC), Color(0xFF8A99B0)), x - spread, x + spread)
                            drawRoundRect(glow, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(10.dp.toPx() - w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                        } else Modifier.border(if (inLoop || g.keyTag != null) 2.dp else 1.dp, if (inLoop) Color(0xFFFBBF24) else if (g.keyTag != null) Color(0xFFFB923C) else if (edited) Cal.good else Cal.border, shape))
                        .combinedClickable(onClick = { if (player != null) seek(g.start) }, onLongClick = { menuFor = gi })
                        .padding(vertical = if (busker) 22.dp else 10.dp, horizontal = 4.dp), Alignment.Center) {
                        Text(if (g.first == g.last) "${bars[g.first].num}" else "${bars[g.first].num}–${bars[g.last].num}", fontSize = if (busker) 13.sp else 10.sp, color = Cal.muted, modifier = Modifier.align(Alignment.TopStart))
                        if (busker && on && beatsLeft != null) Text("$beatsLeft", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFB923C), modifier = Modifier.align(if (slow[g.first] != null) Alignment.BottomEnd else Alignment.TopEnd).padding(end = 2.dp))
                        slow[g.first]?.let { Text("${(it * 100).toInt()}%", fontSize = if (busker) 13.sp else 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF3B3B), modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp)) }
                        FitText(g.label, if (busker) 34f else 19f, Modifier.padding(top = if (busker) 14.dp else 10.dp))        // long names (C#m, Am/C, F#m7) shrink to sit inside the box
                        DropdownMenu(expanded = menuFor == gi, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Edit chord") }, onClick = { menuFor = null; editBar = g.first })
                            DropdownMenuItem(text = { Text("Delete chord") }, onClick = { menuFor = null; editGroup(g, "N.C.") })
                            DropdownMenuItem(text = { Text("Slow measure…") }, onClick = { menuFor = null; slowVal = slow[g.first] ?: 0.6f; slowFor = g })
                        }
                    }
                }
            }
    }
    val controlsBlock: @Composable () -> Unit = {
            Panel(Modifier.fillMaxWidth(), pad = 8.dp, space = 4.dp) {
                note?.let { Text(it, color = if (it.startsWith("Now tap")) Color(0xFFFBBF24) else Cal.bad, fontSize = 12.sp, modifier = Modifier.clickable { note = null }) }
                audioMsg?.takeIf { !(it.startsWith("Shifting") || it.startsWith("Loading")) }?.let { Text(it, color = Cal.muted, fontSize = 12.sp) }
                if (song.audioUrl.isBlank() && song.linkUrl.isNotBlank()) {
                    val linkCtx = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(onClick = { try { linkCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(song.linkUrl))) } catch (_: Exception) {} }, modifier = Modifier.fillMaxWidth().height(36.dp)) { Text("Play on YouTube", fontSize = 13.sp) }
                }
                if (loopArmed && note == null) Text("Drag across the measures to loop them, or tap the first and the last.", color = Color(0xFFFBBF24), fontSize = 12.sp)
                val pillB = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFF1F5F9), Color(0xFF8A99B0), Color(0xFFE2E8F0)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).border(1.dp, pillB, RoundedCornerShape(22.dp)).padding(start = 24.dp, end = 24.dp, top = 3.dp, bottom = 4.dp)) {
                        Text("Volume", fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                        SilverProgress(volume, { volume = it; player?.setVolume(it, it) }, {}, Modifier.height(22.dp))
                    }
                    Column(Modifier.weight(1f).border(1.dp, pillB, RoundedCornerShape(22.dp)).padding(start = 24.dp, end = 24.dp, top = 3.dp, bottom = 4.dp)) {
                        Row { Text("Tempo", fontWeight = FontWeight.SemiBold, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text("${(speed * 100).toInt()}%", color = Cal.steel, fontSize = 10.sp) }
                        SilverProgress(((speed - 0.5f) / 0.75f).coerceIn(0f, 1f), { speed = (Math.round((0.5f + it * 0.75f) * 20) / 20f); if (playing) applySpeed() }, {}, Modifier.height(22.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val nb = Modifier.height(36.dp)
                    NavBtn(onClick = { seek(0.0) }, modifier = nb.weight(1f)) { c -> Text("⏮", color = c, fontSize = 15.sp) }
                    NavBtn(onClick = { prevBar() }, modifier = nb.weight(1f)) { c -> Text("◀", color = c, fontSize = 14.sp) }
                    NavBtn(onClick = { togglePlay() }, modifier = nb.weight(2f), enabled = player != null, strong = true) { c -> Text(if (playing) "❚❚" else if (counting > 0) "✕" else "▶", color = c, fontSize = 16.sp) }
                    NavBtn(onClick = { countJob?.cancel(); counting = 0; bigShow = false; player?.pause(); playing = false; seek(0.0) }, modifier = nb.weight(1f)) { c -> Text("■", color = c, fontSize = 14.sp) }
                    NavBtn(onClick = { nextBar() }, modifier = nb.weight(1f)) { c -> Text("▶", color = c, fontSize = 14.sp) }
                    NavBtn(onClick = { seek(bars.last().start) }, modifier = nb.weight(1f)) { c -> Text("⏭", color = c, fontSize = 15.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${clock(pos)} / ${clock(dur)}", color = Cal.muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    val loopLive = loopOn || loopArmed
                    val sm = Modifier.height(30.dp)
                    NavBtn(onClick = { toggleLoop() }, modifier = sm, active = if (loopLive) Color(0xFFFBBF24) else null, padH = 12.dp) { c ->
                        Text(if (loopOn) "Loop ✕" else if (loopArmed) "Pick…" else "Loop" + if (!full) " 🔒" else "", color = c, fontSize = 12.sp) }
                    NavBtn(onClick = { loopOn = false; loopArmed = false; loopAnchor = null; loopRange = null; busker = true }, modifier = sm, padH = 12.dp) { c -> Text("Busker", color = c, fontSize = 12.sp) }
                    NavBtn(onClick = { showOptions = true }, modifier = sm, padH = 12.dp) { c -> Text("Options ▲", color = c, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(14.dp))
                Waveform(song.peaks, (pos / dur.coerceAtLeast(1.0)).toFloat(), onChange = { dragging = true; pos = it * dur }, onDone = { dragging = false; seek(pos) }, modifier = Modifier.height(30.dp), enabled = player != null)
            }
    }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val pdfPicker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        printing = true; saveMsg = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val info = listOfNotNull(keyNow.takeIf { it.isNotBlank() }?.let { "Key $it" }, song.bpm.takeIf { it > 0 }?.let { "${it.toInt()} bpm" }, if (capo > 0) "Capo $capo" else null, if (instrument != "Guitar") instrument else if (tuning != "Standard") "$tuning tuning" else null).joinToString("  ·  ")
                    val diagrams = groups.map { it.label }.distinct().map { l -> val gi = groups.first { it.label == l }; PrintDiagram(l, shapeAt(gi.first, l)) }
                    ctx.contentResolver.openOutputStream(uri)?.use { writeChordSheetPdf(it, titleOv ?: song.title, song.artist, info, groups, bars, song.lyrics, diagrams, left, capo, printDiagrams, printLyrics, printCondensed, printCols) }
                }
                saveMsg = "PDF saved."
            } catch (e: Exception) { saveMsg = "Couldn't make the PDF (" + (e.message ?: e.javaClass.simpleName) + ")." }
            printing = false
        }
    }
    if (printDlg) {
        AlertDialog(onDismissRequest = { printDlg = false }, containerColor = Cal.panel, title = { Text("Print / Save PDF") },
            text = { Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!printCondensed, { printCondensed = false }, label = { Text("Full chords") }); Spacer(Modifier.width(8.dp))
                    FilterChip(printCondensed, { printCondensed = true }, label = { Text("Condensed") }) }
                if (printCondensed) Text("Bars in a line, with long runs of one chord shown as a single bar (for example Em ×16).", color = Cal.muted, fontSize = 12.sp)
                else Row(verticalAlignment = Alignment.CenterVertically) { Text("Measures per row", modifier = Modifier.weight(1f)); for (n in 3..6) { FilterChip(printCols == n, { printCols = n }, label = { Text("$n") }); Spacer(Modifier.width(4.dp)) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(printDiagrams, { printDiagrams = it }); Text("Chord diagrams at the top") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(printLyrics, { printLyrics = it }, enabled = song.lyrics.isNotEmpty()); Text(if (song.lyrics.isEmpty()) "Lyrics (none found for this song)" else "Lyrics under the measures") }
                Text("You choose where the PDF is saved; open it from there to print.", color = Cal.muted, fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = {
                printDlg = false
                scope.launch {
                    try {
                        val no = if (full) null else api.usePrint()
                        if (no != null) saveMsg = no else pdfPicker.launch(song.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "chords" } + " - chords.pdf")
                    } catch (e: ApiError) { saveMsg = e.msg } catch (e: Exception) { saveMsg = "Couldn't reach the server." }
                }
            }) { Text("Make PDF") } },
            dismissButton = { TextButton(onClick = { printDlg = false }) { Text("Cancel") } })
    }
    if (showTpl) {
        val mine = templates.entries.filter { it.value.inst == webInst && it.value.tun == tunName }.sortedBy { it.value.name }
        AlertDialog(onDismissRequest = { showTpl = false }, containerColor = Cal.panel, title = { Text("My chord templates") },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("For $webInst" + if (instrument == "Guitar") ", $tunName tuning" else "", color = Cal.muted, fontSize = 12.sp)
                if (mine.isEmpty()) Text("No templates yet. Tick the box under a chord's fingerings to make one.")
                mine.forEach { e -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.value.name.ifBlank { e.key }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(e.value.mode, color = Cal.muted, fontSize = 12.sp)
                    TextButton(onClick = { templates.remove(e.key); saveTpl() }) { Text("Remove") } } }
            } },
            confirmButton = { TextButton(onClick = { showTpl = false }) { Text("Done") } },
            dismissButton = { if (mine.isNotEmpty()) TextButton(onClick = { mine.forEach { templates.remove(it.key) }; saveTpl() }) { Text("Remove all", color = Cal.bad) } })
    }
    if (saveDlg) {
        var cat by remember { mutableStateOf(favCat) }
        var ttl by remember { mutableStateOf(titleOv ?: song.title) }
        var shareDb by remember { mutableStateOf(false) }
        var oldCats by remember { mutableStateOf(listOf<String>()) }
        LaunchedEffect(Unit) { try { oldCats = api.favourites().map { it.category.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() } } catch (_: Exception) {} }
        AlertDialog(onDismissRequest = { saveDlg = false }, containerColor = Cal.panel,
            title = { Text(if (favId.isNotEmpty()) "Save changes" else "Save to Favourites") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your edits, key and chords are saved with the song" + if (song.audioUrl.isNotBlank()) " and a copy of the audio." else ".", color = Cal.muted, fontSize = 13.sp)
                OutlinedTextField(ttl, { ttl = it }, label = { Text("Song title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cat, { cat = it }, label = { Text("Category (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (oldCats.isNotEmpty()) {
                    Text("Or pick one you already use:", color = Cal.muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(oldCats.size) { ci ->
                            val c = oldCats[ci]
                            FilterChip(selected = cat.trim().equals(c, ignoreCase = true), onClick = { cat = c }, label = { Text(c) })
                        }
                    }
                }
                if (song.audioUrl.isNotBlank() && !song.recording)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(shareDb, { shareDb = it })
                        Text("Add this song to the identification database so other Chord-ers can find it", fontSize = 12.sp, color = Cal.muted, modifier = Modifier.weight(1f))
                    } } },
            confirmButton = { TextButton(onClick = {
                saveDlg = false; saving = true; saveMsg = null
                scope.launch {
                    try {
                        val id = api.saveFavourite(song.copy(title = ttl.trim().ifBlank { song.title }), bars, bars.indices.map { edits[it] ?: bars[it].chord }, edits.keys.toSet(), cat.trim(), favId, shareDb)
                        favId = id; favCat = cat.trim(); titleOv = ttl.trim().ifBlank { null }; saveMsg = "Saved to Favourites."
                    } catch (e: ApiError) { saveMsg = e.msg } catch (e: Exception) { saveMsg = "Couldn't save (" + (e.message ?: e.javaClass.simpleName) + ")." }
                    saving = false
                }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveDlg = false }) { Text("Cancel") } })
    }
    androidx.activity.compose.BackHandler(enabled = !showOptions && editBar == null && editSound == null && slowFor == null) { onBack() }
    androidx.activity.compose.BackHandler(enabled = busker && !showOptions && editBar == null && editSound == null && slowFor == null) { busker = false }
    val buskerView: @Composable () -> Unit = {
        val sideBySide = landscape && buskerShape == 0
        val hideBtn: @Composable (Modifier) -> Unit = { m -> OutlinedButton(onClick = { hideLyrics = !hideLyrics }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = m) { Text(if (hideLyrics) "Show lyrics" else "Hide lyrics", fontSize = 12.sp, maxLines = 1, softWrap = false) } }
        val titleRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(titleOv ?: song.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = !sideBySide))   // wide screen: the buttons sit close to the title
                if (speed != 1f) Pill("${(speed * 100).toInt()}%", Cal.steel)
                if (keyNow.isNotBlank()) Pill("Key $keyNow", if (keyChanged) Color(0xFFFBBF24) else Cal.muted)
                if (sideBySide) hideBtn(Modifier.height(34.dp))
                OutlinedButton(onClick = { busker = false }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(34.dp)) { Text("✕ Exit") }
            }
        }
        val lyricBar: @Composable (Modifier) -> Unit = { m ->
            Panel(m, pad = 10.dp) { AutoFitLine(if (hideLyrics) " " else (song.lyrics.getOrNull(lyricNow)?.text ?: " "), 18f) }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!sideBySide) {
                titleRow()
                if (buskerShape == 2) NavBtn(onClick = { buskerShape = 0 }, modifier = Modifier.height(34.dp), padH = 10.dp) { c -> Text("Chord shape", color = c, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    lyricBar(Modifier.weight(1f))
                    hideBtn(Modifier.height(44.dp).width(104.dp))
                }
            }
            // the shape of the chord being played, and the one coming up (it can be made small, or taken away, to give the grid its room back)
            val shapePanel: @Composable (Modifier, Boolean) -> Unit = { mod, side ->
                val nowLab = label(cur)
                var ni = -1; var kk = cur + 1
                while (kk < bars.size) { if (label(kk) != nowLab) { ni = kk; break }; kk++ }
                BoxWithConstraints(mod) {
                // wide screen: the diagrams grow to use the whole height of the panel (as far as the width allows)
                val nowW = if (side) ((maxHeight - 110.dp) / 1.2f).coerceAtMost((maxWidth - 28.dp) / 1.8f).coerceIn(110.dp, 220.dp) else 128.dp
                val nextW = nowW * 0.78f
                Panel(if (side) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), pad = 6.dp, space = 2.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (buskerShape == 1) {
                            Text(nowLab, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Cal.steel)
                            if (ni >= 0) Text("→  ${label(ni)}", fontSize = 15.sp, color = Cal.muted)
                        } else Text("CHORD SHAPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Cal.muted, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        NavBtn(onClick = { buskerShape = if (buskerShape == 0) 1 else 0 }, modifier = Modifier.size(34.dp, 30.dp)) { c -> Text(if (buskerShape == 0) "▾" else "▴", color = c, fontSize = 14.sp) }
                        NavBtn(onClick = { buskerShape = 2 }, modifier = Modifier.size(34.dp, 30.dp)) { c -> Text("✕", color = c, fontSize = 13.sp) }
                    }
                    if (buskerShape == 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        ChordDiagram(nowLab, shapeAt(cur, nowLab), left, width = nowW, highlight = true, beats = beatsLeft, capo = capo)
                        if (ni >= 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("NEXT", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Cal.steel, letterSpacing = 1.sp)
                            ChordDiagram(label(ni), shapeAt(ni, label(ni)), left, width = if (side) nextW else 100.dp, capo = capo)
                        }
                    }
                }
                }
            }
            if (sideBySide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {      // title, lyrics and grid share the width; the shape panel gets the whole height beside them
                    titleRow(); lyricBar(Modifier.fillMaxWidth()); gridBlock(Modifier.weight(1f))
                }
                shapePanel(Modifier.width(340.dp).fillMaxHeight(), true)
            } else {
                gridBlock(Modifier.weight(1f))
                if (buskerShape != 2) shapePanel(Modifier.fillMaxWidth(), false)
            }
            note?.let { Text(it, color = if (it.startsWith("Now tap")) Color(0xFFFBBF24) else Cal.bad, fontSize = 12.sp, modifier = Modifier.clickable { note = null }) }
            // one slim row of symbols, so the chords get the room
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                val nb = Modifier.height(40.dp).weight(1f)
                val zero = PaddingValues(0.dp)
                NavBtn(onClick = { seek(0.0) }, modifier = nb) { c -> Text("⏮", color = c, fontSize = 15.sp) }
                NavBtn(onClick = { prevBar() }, modifier = nb) { c -> Text("◀", color = c, fontSize = 14.sp) }
                NavBtn(onClick = { togglePlay() }, modifier = Modifier.height(40.dp).weight(1.3f), enabled = player != null, strong = true) { c -> Text(if (playing) "❚❚" else if (counting > 0) "✕" else "▶", color = c, fontSize = 16.sp) }
                NavBtn(onClick = { nextBar() }, modifier = nb) { c -> Text("▶", color = c, fontSize = 14.sp) }
                NavBtn(onClick = { seek(bars.last().start) }, modifier = nb) { c -> Text("⏭", color = c, fontSize = 15.sp) }
                NavBtn(onClick = { muted = !muted }, modifier = nb) { c -> EarGlyph(if (muted) Cal.muted else c, muted, 22.dp) }
                NavBtn(onClick = { speed = (Math.round((speed - 0.05f) * 20) / 20f).coerceAtLeast(0.5f); if (playing) applySpeed() }, modifier = nb) { c -> Text("−", color = c, fontSize = 16.sp) }
                NavBtn(onClick = { speed = (Math.round((speed + 0.05f) * 20) / 20f).coerceAtMost(1.25f); if (playing) applySpeed() }, modifier = nb) { c -> Text("+", color = c, fontSize = 16.sp) }
                NavBtn(onClick = { showOptions = true }, modifier = nb) { c -> Text("⚙", color = c, fontSize = 16.sp) }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (busker) buskerView()
        else if (!landscape) Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            headerBlock(false); Spacer(Modifier.height(6.dp)); lyricsBlock(false, false); Spacer(Modifier.height(4.dp)); chordsBlock(false); Spacer(Modifier.height(4.dp))
            gridBlock(Modifier.weight(1f)); Spacer(Modifier.height(4.dp)); controlsBlock()
        } else Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.15f).fillMaxHeight()) { chordsBlock(true); Spacer(Modifier.height(6.dp)); headerBlock(true); Spacer(Modifier.height(4.dp)); Box(Modifier.weight(1f).fillMaxWidth()) { lyricsBlock(true, true) } }
            Column(Modifier.weight(1f)) { gridBlock(Modifier.weight(1f)); Spacer(Modifier.height(4.dp)); controlsBlock() }
        }
        val working = audioMsg?.let { it.startsWith("Shifting") || it.startsWith("Loading") } == true
        if (working) Box(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
            .shadow(18.dp, RoundedCornerShape(22.dp), ambientColor = Color(0xFF22C55E), spotColor = Color(0xFF22C55E)).clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF22C55E)).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (audioMsg!!.startsWith("Shifting")) "Adjusting the audio to the new key…" else "Loading the audio…", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF04200F), textAlign = TextAlign.Center, lineHeight = 29.sp)
                Text("Nothing is wrong. This only takes a few seconds.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF064E24), textAlign = TextAlign.Center)
                LinearProgressIndicator(Modifier.fillMaxWidth(0.8f), color = Color(0xFF04200F), trackColor = Color(0x5504200F))
            }
        }
        if (bigShow && counting > 0) Text("$counting", fontSize = 150.sp, fontWeight = FontWeight.ExtraBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            color = Color(0xFFFBBF24).copy(alpha = 0.92f), modifier = Modifier.align(Alignment.Center).offset(y = (-40).dp))
    }

    // ---------------------------------------------------------------- bottom Options sheet
    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }, containerColor = Cal.panel, contentColor = Cal.text) {
            TabRow(selectedTabIndex = tab, containerColor = Cal.panel, contentColor = Cal.steel) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Play settings") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Song info") })
            }
            if (tab == 0) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // key change: arrows icon and a short label (the one title that stays as words)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SvgIcon(ICON_KEYSHIFT, 24f, 24f, 26.dp, Cal.steel); Text("Change key", fontWeight = FontWeight.SemiBold) }
                    NavBtn(onClick = { if (shift > -6) shift-- }, modifier = Modifier.size(44.dp)) { c -> Text("−", color = c, fontSize = 20.sp) }
                    Text(if (shift > 0) "+$shift st" else "$shift st", modifier = Modifier.width(76.dp), textAlign = TextAlign.Center, color = Cal.steel, fontWeight = FontWeight.SemiBold)
                    NavBtn(onClick = { if (shift < 6) shift++ }, modifier = Modifier.size(44.dp)) { c -> Text("+", color = c, fontSize = 20.sp) }
                }
                IconStepper(ICON_CAPO, 32f, 32f, "Apply Capo", if (capo == 0) "off" else "fret $capo", locked = !full, onMinus = { if (!full) needPro("The capo") else if (capo > 0) capo-- },
                    onPlus = { if (!full) needPro("The capo") else if (capo < 9) capo++ })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SvgIcon(ICON_INSTRUMENT, 24f, 24f, 30.dp, Cal.steel); Text("Instrument", fontWeight = FontWeight.SemiBold); if (!full) Text("🔒") }
                    var instMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { instMenu = true }) { Text("$instrument ▾", fontSize = 13.sp) }
                        DropdownMenu(expanded = instMenu, onDismissRequest = { instMenu = false }) {
                            for ((nm, sp) in INSTRUMENTS) DropdownMenuItem(
                                text = { Column { Text(nm + if (nm == instrument) "  ✓" else "", fontWeight = FontWeight.SemiBold); Text(if (nm == "Guitar") "6 strings, ten tunings" else sp.notes, fontSize = 12.sp, color = Cal.muted) } },
                                onClick = { instMenu = false; if (!full && nm != "Guitar") needPro("Other instruments") else { instrument = nm; tuning = "Standard"; swapAll.clear(); swapBar.clear() } })
                        }
                    }
                }
                if (instrument != "Guitar") Text("Strings: ${INSTRUMENTS[instrument]?.notes ?: ""}", color = Cal.muted, fontSize = 13.sp)
                if (instrument == "Guitar") Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SvgIcon(ICON_TUNING, 24f, 24f, 30.dp, Cal.steel); Text("Tuning", fontWeight = FontWeight.SemiBold); if (!full) Text("🔒") }
                    var tuneMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { tuneMenu = true }) { Text("$tuning · ${TUNING_NOTES[tuning] ?: ""} ▾", fontSize = 13.sp) }
                        DropdownMenu(expanded = tuneMenu, onDismissRequest = { tuneMenu = false }) {
                            for ((tn, notes) in TUNING_NOTES) DropdownMenuItem(
                                text = { Column { Text(tn + if (tn == tuning) "  ✓" else "", fontWeight = FontWeight.SemiBold); Text(notes, fontSize = 12.sp, color = Cal.muted) } },
                                onClick = { tuneMenu = false; if (!full && tn != "Standard") needPro("Other tunings") else { tuning = tn; swapAll.clear(); swapBar.clear() } })
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().border(1.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFF1F5F9), Color(0xFF8A99B0), Color(0xFFE2E8F0))), RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chord phrasing", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    for (p in Phrasing.values()) {
                        val pro = p == Phrasing.Triad || p == Phrasing.Power
                        val sel = phrasing == p
                        FilterChip(selected = sel, onClick = { if (!full && pro) needPro("This chord phrasing") else phrasing = p },
                            colors = if (p == Phrasing.Power) FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE100), selectedLabelColor = Color(0xFF2B2300))
                                     else FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF19FF5C), selectedLabelColor = Color(0xFF02210A)),
                            label = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (p == Phrasing.Power) Bolt(14.dp, if (sel) Color(0xFF2B2300) else Color(0xFFFBBF24))
                                Text(p.name + if (!full && pro) " 🔒" else "", color = if (sel) (if (p == Phrasing.Power) Color(0xFF2B2300) else Color(0xFF02210A)) else Color.Unspecified) } })
                    }
                }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SvgIcon(ICON_LEFTY, 32f, 32f, 28.dp, Cal.steel); Text("Left handed", modifier = Modifier.weight(1f)); Switch(left, { left = it }, colors = silverSwitch) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SvgIcon(ICON_METRO, 24f, 24f, 28.dp, Cal.steel); Text("Metronome" + if (!full) " 🔒" else "", modifier = Modifier.weight(1f))
                    Switch(metroOn, { if (!full) needPro("The metronome") else metroOn = it }, colors = silverSwitch) }
                if (metroOn) {
                    val bpmNow = metroCustom ?: song.bpm.toInt()
                    Stepper("Metronome BPM", "$bpmNow" + if (metroCustom == null) " (song)" else "", locked = false,
                        onMinus = { metroCustom = (bpmNow - 1).coerceAtLeast(30) }, onPlus = { metroCustom = (bpmNow + 1).coerceAtMost(300) })
                    if (metroCustom != null) TextButton(onClick = { metroCustom = null }) { Text("Follow the song's tempo again") }
                    Row { Text("Metronome volume", fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("${(metroVol * 100).toInt()}%", color = Cal.steel) }
                    SilverProgress(((metroVol - 0.1f) / 0.9f).coerceIn(0f, 1f), { metroVol = 0.1f + it * 0.9f }, {})
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SvgIcon(ICON_COUNTIN, 48f, 40f, 34.dp); Text("Count-in", modifier = Modifier.weight(1f)); Switch(countIn, { countIn = it }, colors = silverSwitch) }
                if (countIn) Stepper("Count-in beats", "$countBeats", locked = false, onMinus = { if (countBeats > 1) countBeats-- }, onPlus = { if (countBeats < 12) countBeats++ })
                // one View button holds the lyrics and chord-detail options, as on the website
                var viewMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { viewMenu = true }, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        SvgIcon(ICON_EYE, 24f, 24f, 22.dp, Cal.steel); Spacer(Modifier.width(8.dp)); Text("View ▾", fontSize = 15.sp) }
                    DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                        DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hideLyrics, null); Spacer(Modifier.width(10.dp)); Text("Hide lyrics") } },
                            onClick = { hideLyrics = !hideLyrics })
                        DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(simplify, null, enabled = full); Spacer(Modifier.width(10.dp)); Text("Simplify" + if (!full) " 🔒" else "") } },
                            onClick = { if (!full) needPro("Switching Simplify off") else { simplify = !simplify; if (simplify) detailed = false } })
                        if (full && song.bars.any { it.detail != null }) DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(detailed, null); Spacer(Modifier.width(10.dp)); Text("Detailed chords") } },
                            onClick = { detailed = !detailed; if (detailed) simplify = false })
                    }
                }
                note?.let { Text(it, color = Cal.bad, fontSize = 13.sp) }
                Spacer(Modifier.height(24.dp))
            } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(ICON_STAR, if (saving) "Saving…" else if (favId.isNotEmpty()) "Save changes" else "Favourites", Modifier.weight(1f), enabled = !saving) { saveDlg = true }
                    ActionTile(ICON_PRINT, if (printing) "Making PDF…" else "Print / PDF", Modifier.weight(1f), enabled = !printing) { printDlg = true }
                }
                saveMsg?.let { Text(it, color = if (it.startsWith("Saved") || it.startsWith("PDF")) Cal.good else Cal.bad, fontSize = 13.sp) }
                Box(Modifier.fillMaxWidth()) {
                    if (song.bpm > 0) Pill("${song.bpm.toInt()} bpm" + if (effSpeed != 1f) " · now ${(song.bpm * effSpeed).toInt()}" else "", modifier = Modifier.align(Alignment.CenterStart))
                    if (keyNow.isNotBlank()) Box(Modifier.align(Alignment.Center)) { KeyBadge(keyNow, keyChanged) }
                }
                Text("Key sections: " + keySegs.joinToString("  ·  ") { "${keyName(it, shift)} from ${bars.getOrNull(it.start)?.num ?: it.start + 1}" } + "  (${bars.size} measures)", color = Cal.muted, fontSize = 12.sp)
                val total = slices.sumOf { it.secs }
                if (total > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Canvas(Modifier.size(150.dp)) {
                            var a = -90f
                            val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = 13.sp.toPx(); isFakeBoldText = true; color = android.graphics.Color.rgb(9, 14, 23) }
                            for (sl in slices) {
                                val sw = (sl.secs / total * 360.0).toFloat()
                                drawArc(sl.color, a, sw, true, Offset.Zero, Size(size.width, size.height), style = Fill)
                                if (sl.count == 0 && sw >= 24f) {
                                    val mid = Math.toRadians((a + sw / 2).toDouble()); val r = size.width / 2 * (if (sw > 72f) 0.6f else 0.68f)
                                    drawContext.canvas.nativeCanvas.drawText(sl.name, size.width / 2 + (r * Math.cos(mid)).toFloat(), size.height / 2 + (r * Math.sin(mid)).toFloat() + paint.textSize / 3f, paint)
                                }
                                a += sw
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (s in slices) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(s.color)); Spacer(Modifier.width(8.dp))
                                Text(if (s.count > 0) "Other (${s.count} chords)" else s.name, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp)); Text("${Math.round(s.secs / total * 100)}%", fontSize = 13.sp, color = Cal.muted)
                            }
                        }
                    }
                }
                Text("Chord summary — long-press a chord to edit it everywhere it appears", fontSize = 13.sp, color = Cal.muted)
                for (s in slices) if (s.count == 0) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Cal.card)
                        .combinedClickable(onClick = {}, onLongClick = { editSound = s.name }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                        Text("${"%.1f".format(s.secs)} s · played ${s.plays}×", color = Cal.muted, fontSize = 13.sp)
                    }
                }
                note?.let { Text(it, color = Cal.bad, fontSize = 13.sp) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---------------------------------------------------------------- chord editing (one bar, or every bar with a chord)
    val barTarget = editBar; val allTarget = editSound
    if (barTarget != null || allTarget != null) {
        val tg = if (barTarget != null) groups.firstOrNull { barTarget in it.first..it.last } else null
        val start = if (barTarget != null) sound(barTarget) else allTarget!!
        val refIdx = barTarget ?: bars.indices.firstOrNull { sound(it) == allTarget } ?: 0
        val baseKey = baseOf(refIdx)
        val oIdx = origOf.getOrElse(refIdx) { refIdx }
        val startLab = transposeChord(start, -capo, preferFlat = false)
        val total = bars.indices.count { sound(it) == start }
        var typed by remember(barTarget, allTarget) { mutableStateOf(start) }
        var scopeAll by remember(barTarget, allTarget) { mutableStateOf(barTarget == null) }
        var addText by remember(barTarget, allTarget) { mutableStateOf("") }
        var addKeep by remember(barTarget, allTarget) { mutableStateOf(true) }
        var addErr by remember(barTarget, allTarget) { mutableStateOf("") }
        val baseOpts = remember(startLab, tuning, instrument) { optionsIn(startLab, tuning, instrument) }
        val curShape = shapeAt(refIdx, startLab)
        val curFrets = curShape?.frets
        // the shape shown in the grid always comes first; if the planner chose one that isn't in the list, it is added at the front
        val opts = if (curShape != null && baseOpts.none { it.shape.frets.contentEquals(curFrets!!) }) listOf(Opt(phrasing, -1, "Current", curShape)) + baseOpts else baseOpts
        val curAt = if (curFrets == null) -1 else opts.indexOfFirst { it.shape.frets.contentEquals(curFrets) }
        var optPos by remember(barTarget, allTarget) { mutableIntStateOf(if (curAt >= 0) curAt else 0) }
        val parsed = parseChord(typed)
        val valid = typed.trim() == "N.C." || parsed != null
        val hasSwap = swapBar.containsKey("$oIdx|$baseKey") || swapAll.containsKey(baseKey)
        fun close() { editBar = null; editSound = null }
        fun useShape() {
            val o = opts.getOrNull(optPos) ?: return
            if (o.i < 0) { close(); return }                                    // the shape already on the grid
            if (!full && (o.mode == Phrasing.Triad || o.mode == Phrasing.Power)) { needPro("Triad and Power shapes"); return }
            if (scopeAll) { swapAll[baseKey] = Swap(o.mode, o.i); swapBar.keys.filter { it.endsWith("|$baseKey") }.forEach { swapBar.remove(it) } }
            else swapBar["$oIdx|$baseKey"] = Swap(o.mode, o.i)
            close()
        }
        fun addChords() {
            val names = addText.split(Regex("[,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) { addErr = "Type the new chords with a comma between each, e.g. D, C#, F#"; return }
            val added = ArrayList<String>()
            for (n in names) {
                if (n != "N.C." && parseChord(n) == null) { addErr = "\"$n\" is not a chord I recognise (try Am, F#m7 or Bb)."; return }
                added += if (n == "N.C.") n else transposeChord(n, -shift)
            }
            val idxs = bars.indices.filter { origOf[it] == oIdx }
            val existing = if (addKeep) idxs.map { baseOf(it) }.filter { it != "N.C." }.fold(ArrayList<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc } else ArrayList()
            val seq = existing + added
            val ob = song.bars[oIdx]
            val endT = if (ob.end > ob.start) ob.end else song.bars.getOrNull(oIdx + 1)?.start ?: ob.start
            val max = if (song.bpm > 0) maxOf(1, Math.round((endT - ob.start) * song.bpm / 60.0).toInt()) else 4
            if (seq.size > max) { addErr = "This measure has $max beats, so it can hold at most $max chords (you have ${seq.size})."; return }
            val first = idxs.first(); val oldCount = idxs.size; val delta = seq.size - oldCount
            val oe = edits.toMap(); edits.clear()
            oe.forEach { (k, v) -> if (k < first) edits[k] = v else if (k >= first + oldCount) edits[k + delta] = v }
            val os = slow.toMap(); slow.clear()
            os.forEach { (k, v) -> if (k < first) slow[k] = v else if (k >= first + oldCount) slow[k + delta] = v }
            splits[oIdx] = seq; freeEdits++; close()
        }
        AlertDialog(onDismissRequest = { close() }, containerColor = Cal.panel, titleContentColor = Cal.text, textContentColor = Cal.text,
            title = { Text(if (barTarget != null) (if (tg != null && tg.last > tg.first) "Edit bars ${bars[tg.first].num}–${bars[tg.last].num}" else "Edit bar ${bars[barTarget].num}") else "Edit every $start") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (barTarget != null) {
                        Text("APPLY CHANGE TO", fontSize = 11.sp, color = Cal.muted, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scopeAll = false }) { RadioButton(!scopeAll, { scopeAll = false }); Text("This measure only") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scopeAll = true }) { RadioButton(scopeAll, { scopeAll = true }); Text("All $start in the song ($total)") }
                    }
                    if (opts.size > 1) {
                        val o = opts[optPos.coerceIn(0, opts.size - 1)]
                        val proLocked = !full && (o.mode == Phrasing.Triad || o.mode == Phrasing.Power)
                        Text("SWAP THE FINGERING OF $startLab", fontSize = 11.sp, color = Cal.muted, fontWeight = FontWeight.Bold)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Cal.border, RoundedCornerShape(10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton({ optPos = (optPos - 1 + opts.size) % opts.size }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("‹", fontSize = 20.sp) }
                                Text("${o.label} · ${optPos + 1} of ${opts.size}${if (optPos == curAt) " (in use)" else ""}" + if (!full && proLocked) " 🔒" else "",
                                    Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                OutlinedButton({ optPos = (optPos + 1) % opts.size }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("›", fontSize = 20.sp) }
                            }
                            ChordDiagram(startLab, o.shape, left, width = 110.dp, capo = capo)
                            Button(onClick = { useShape() }) { Text(if (proLocked) "Use this shape (Pro)" else if (optPos == curAt) "Keep this shape" else "Use this shape") }
                            val tplNow = templates[tplKey(baseKey)]
                            val isTpl = tplNow != null && (tplNow.sig == sigOf(o.shape) || (tplNow.mode == o.mode.name && tplNow.i == o.i))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                if (!full) { needPro("Chord templates") }
                                else if (isTpl) { templates.remove(tplKey(baseKey)); saveTpl(); note = "$startLab: template removed" }
                                else {
                                    templates[tplKey(baseKey)] = Tpl(o.mode.name, o.i, sigOf(o.shape), webInst, tunName, startLab); saveTpl()
                                    if (optPos != curAt) { swapAll[baseKey] = Swap(o.mode, o.i); swapBar.keys.filter { it.endsWith("|$baseKey") }.forEach { swapBar.remove(it) } }
                                    note = "$startLab: every $startLab in your future songs will use this shape"
                                }
                            }) {
                                Checkbox(isTpl, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Set as my template for all future $startLab chords" + if (!full) " 🔒" else "", fontSize = 13.sp)
                            }
                            if (templates.isNotEmpty()) TextButton(onClick = { showTpl = true }) { Text("My chord templates (${templates.size})") }
                            if (hasSwap) TextButton(onClick = {
                                if (scopeAll) { swapAll.remove(baseKey); swapBar.keys.filter { it.endsWith("|$baseKey") }.forEach { swapBar.remove(it) } } else swapBar.remove("$oIdx|$baseKey")
                                close() }) { Text("Back to the song's phrasing") }
                        }
                    }
                    Text("ENTER A DIFFERENT CHORD", fontSize = 11.sp, color = Cal.muted, fontWeight = FontWeight.Bold)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, isError = !valid && typed.isNotBlank(),
                        label = { Text("Type a chord, e.g. Cadd9 or F#m7b5") }, modifier = Modifier.fillMaxWidth())
                    if (!valid && typed.isNotBlank()) Text("That isn't a chord name I understand.", color = Cal.bad, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(ROOT_CHOICES) { r ->
                        FilterChip(selected = parsed != null && parsed.root == parseChord(r)?.root, onClick = { typed = r + (parsed?.suffix ?: "") }, label = { Text(r) }) } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(QUALITY_CHOICES) { q ->
                        FilterChip(selected = parsed != null && parsed.suffix == q, onClick = { typed = (Regex("^[A-G][#b]?").find(typed)?.value ?: "C") + q }, label = { Text(if (q.isEmpty()) "major" else q) }) } }
                    FilterChip(selected = typed.trim() == "N.C.", onClick = { typed = "N.C." }, label = { Text("No chord") })
                    if (barTarget != null) {
                        Text("+ ADD CHORDS (PUT A \",\" BETWEEN EACH NEW CHORD)", fontSize = 11.sp, color = Cal.muted, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(addText, { addText = it; addErr = "" }, singleLine = true, placeholder = { Text("e.g. D, C#, F#") }, modifier = Modifier.weight(1f))
                            OutlinedButton({ addChords() }) { Text("Add") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { addKeep = true }) { RadioButton(addKeep, { addKeep = true }); Text("Keep the current chord and add these after it", fontSize = 14.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { addKeep = false }) { RadioButton(!addKeep, { addKeep = false }); Text("Replace the current chord with these chords", fontSize = 14.sp) }
                        if (addErr.isNotBlank()) Text(addErr, color = Cal.bad, fontSize = 12.sp)
                    }
                    if (!full) Text("Free: Open and Barre shapes. Triad and Power shapes are part of Pro.", fontSize = 12.sp, color = Cal.muted)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val name = typed.trim()
                    if (barTarget != null && !scopeAll) { if (tg != null) editGroup(tg, name) }
                    else { for (i in bars.indices) if (sound(i) == start) edits[i] = transposeChord(name, -shift); summaryEdits++ }
                    close()
                }) { Text("Apply") }
            },
            dismissButton = {
                Row {
                    if (barTarget != null && tg != null) { TextButton(onClick = { editGroup(tg, "N.C."); close() }) { Text("Delete") }
                        if ((tg.first..tg.last).any { it in edits }) TextButton(onClick = { for (i in tg.first..tg.last) edits.remove(i); close() }) { Text("Undo") } }
                    TextButton(onClick = { close() }) { Text("Cancel") }
                }
            })
    }

    // enlarged album art, tap anywhere to close (as on the website)
    if (artBig) art?.let { bmp ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { artBig = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Image(bmp.asImageBitmap(), "Album art, enlarged", Modifier.fillMaxWidth(0.9f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF3A5679), RoundedCornerShape(12.dp)).clickable { artBig = false })
        }
    }

    // ---------------------------------------------------------------- slow measure (as on the website)
    slowFor?.let { g ->
        val red = Color(0xFFF87171)
        AlertDialog(onDismissRequest = { slowFor = null }, containerColor = Cal.panel, titleContentColor = Cal.text, textContentColor = Cal.text,
            title = { Text(androidx.compose.ui.text.buildAnnotatedString { append("Slow measure speed "); pushStyle(androidx.compose.ui.text.SpanStyle(color = red)); append("(M${bars[g.first].num})"); pop() }, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(slowVal, { slowVal = Math.round(it * 20) / 20f }, valueRange = 0.3f..0.95f, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = red, activeTrackColor = red))
                        Text("${(slowVal * 100).toInt()}%", color = red, fontWeight = FontWeight.Bold)
                    }
                    Text("Plays at this fraction of the current tempo. Press Switch off to go back to normal speed.", fontSize = 12.sp, color = Cal.muted)
                }
            },
            confirmButton = { TextButton(onClick = { setSlow(g, slowVal); slowFor = null }) { Text("OK") } },
            dismissButton = { Row {
                if (slow[g.first] != null) TextButton(onClick = { setSlow(g, null); slowFor = null }) { Text("Switch off") }
                TextButton(onClick = { slowFor = null }) { Text("Cancel") }
            } })
    }
}

@Composable
private fun IconStepper(icon: List<IconOp>, vw: Float, vh: Float, desc: String, value: String, locked: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SvgIcon(icon, vw, vh, 30.dp, Cal.steel); Text(desc, fontWeight = FontWeight.SemiBold); if (locked) Text("🔒") }
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", fontSize = 20.sp) }
        Text(value, modifier = Modifier.width(76.dp), textAlign = TextAlign.Center, color = Cal.steel, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("+", fontSize = 20.sp) }
    }
}

private val silverSwitch @Composable get() = SwitchDefaults.colors(
    uncheckedTrackColor = Color(0xFFCBD5E1), uncheckedThumbColor = Color(0xFF475569), uncheckedBorderColor = Color(0xFF94A3B8))

@Composable
private fun Stepper(title: String, value: String, locked: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title + if (locked) " 🔒" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", fontSize = 20.sp) }
        Text(value, modifier = Modifier.width(76.dp), textAlign = TextAlign.Center, color = Cal.steel, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("+", fontSize = 20.sp) }
    }
}
