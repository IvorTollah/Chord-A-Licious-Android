// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

class PrintDiagram(val name: String, val shape: Shape?)

/** One chord sheet as a PDF: title, the chord diagrams used, then the measures in rows of four with the lyrics underneath. */
fun writeChordSheetPdf(out: OutputStream, title: String, artist: String, info: String, groups: List<Group>, bars: List<Bar>, lyrics: List<Lyric>,
                       diagrams: List<PrintDiagram>, left: Boolean, capo: Int, withDiagrams: Boolean, withLyrics: Boolean,
                       condensed: Boolean = false, cols: Int = 4) {
    val doc = PdfDocument()
    val pw = 595; val ph = 842; val m = 36f; val cw = pw - 2 * m
    var pageNo = 0
    // PdfDocument lets only one page be open at a time, so each page is recorded first and written out at the end (when the page total is known too)
    val pages = ArrayList<android.graphics.Picture>()
    lateinit var c: android.graphics.Canvas
    var y = 0f
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 100, 100) }
    fun newPage() {
        pageNo++
        if (pages.isNotEmpty()) pages.last().endRecording()
        val pic = android.graphics.Picture(); pages += pic; c = pic.beginRecording(pw, ph); y = m + 8f
    }
    fun text(s: String, x: Float, yy: Float, size: Float, bold: Boolean = false, p: Paint = ink, maxW: Float = cw) {
        p.textSize = size; p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        var t = s
        if (p.measureText(t) > maxW) { val n = p.breakText(t, true, maxW - p.measureText("…"), null); t = t.take(n) + "…" }
        c.drawText(t, x, yy, p)
    }
    newPage()
    text(title, m, y + 20, 22f, true); y += 28
    if (artist.isNotBlank()) { text(artist, m, y + 12, 13f, false, grey); y += 18 }
    if (info.isNotBlank()) { text(info, m, y + 11, 10.5f, false, grey); y += 16 }
    y += 8

    fun diagram(d: PrintDiagram, x: Float, top: Float, w: Float) {
        text(d.name, x, top + 10, 11f, true, ink, w)
        val s = d.shape ?: return
        if (s.keys) {                                                   // keyboard chord: the notes as a row of keys
            val lo = s.frets.min(); val start = (lo / 12) * 12; val octs = if (s.frets.max() - start >= 12) 2 else 1
            val white = listOf(0, 2, 4, 5, 7, 9, 11); val nw = octs * 7 + 1; val kw = (w - 4) / nw; var wi = 0
            val fillP = Paint().apply { color = Color.BLACK }; val lineP = Paint().apply { color = Color.rgb(90, 90, 90); style = Paint.Style.STROKE; strokeWidth = 0.7f }
            val xs = HashMap<Int, Float>()
            for (mm in start..start + octs * 12) if (mm % 12 in white) { xs[mm] = x + 2 + wi * kw; wi++; if (mm in s.frets) c.drawRect(xs[mm]!!, top + 16, xs[mm]!! + kw, top + 58, fillP) else c.drawRect(xs[mm]!!, top + 16, xs[mm]!! + kw, top + 58, lineP) }
            for (mm in start..start + octs * 12) if (mm % 12 !in white) { val bx = (xs[mm - 1] ?: x) + kw * 0.68f; c.drawRect(bx, top + 16, bx + kw * 0.64f, top + 40, if (mm in s.frets) fillP else Paint().apply { color = Color.rgb(160, 160, 160) }) }
            return
        }
        val nStr = s.frets.size
        val gx = x + 8; val gy = top + 24; val gw = w - 16; val sx = gw / (nStr - 1).toFloat(); val fy = 11f
        val pos = s.frets.filter { it > 0 }
        val base = if (pos.isEmpty() || pos.max() <= 5) 1 else pos.min()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 90); strokeWidth = 0.8f }
        val thick = Paint(line).apply { color = Color.BLACK; strokeWidth = if (base == 1) 3f else 0.8f }
        for (i in 0 until nStr) c.drawLine(gx + i * sx, gy, gx + i * sx, gy + 5 * fy, line)
        for (r in 0..5) c.drawLine(gx, gy + r * fy, gx + gw, gy + r * fy, if (r == 0) thick else line)
        fun xOf(i: Int) = gx + (if (left) nStr - 1 - i else i) * sx
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
        for (i in 0 until nStr) {
            val fr = s.frets[i]; val x = xOf(i)
            when {
                fr < 0 -> { c.drawLine(x - 2.5f, gy - 9f, x + 2.5f, gy - 4f, line); c.drawLine(x - 2.5f, gy - 4f, x + 2.5f, gy - 9f, line) }
                fr == 0 -> c.drawCircle(x, gy - 6.5f, 2.6f, ring)
                else -> { val row = fr - base; if (row in 0..4) c.drawCircle(x, gy + (row + 0.5f) * fy, 3.6f, dot) }
            }
        }
        if (s.barre && pos.isNotEmpty()) {
            val mn = pos.min(); val ss = s.frets.indices.filter { s.frets[it] == mn }
            if (ss.size >= 2) { val yy = gy + (mn - base + 0.5f) * fy; val p = Paint(dot).apply { strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(minOf(xOf(ss.min()), xOf(ss.max())), yy, maxOf(xOf(ss.min()), xOf(ss.max())), yy, p) }
        }
        val cap = if (base > 1) "fret $base" else if (capo > 0) "capo $capo" else ""
        if (cap.isNotEmpty()) text(cap, x, gy + 5 * fy + 11, 8f, false, grey, w)
    }
    if (withDiagrams && diagrams.isNotEmpty()) {
        val per = 7; val dw = cw / per
        diagrams.chunked(per).forEach { row ->
            if (y + 92 > ph - m - 8f) newPage()
            row.forEachIndexed { k, d -> diagram(d, m + k * dw, y, dw - 6) }
            y += 92
        }
        y += 6
        c.drawLine(m, y, m + cw, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f }); y += 14
    }
    // lyrics are wrapped, never cut off
    fun wrap(t: String, size: Float, maxW: Float, maxLines: Int = 12): List<String> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.DEFAULT }
        val out = ArrayList<String>(); var line = ""
        for (w in t.split(" ").filter { it.isNotEmpty() }) {
            val tryL = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(tryL) <= maxW) line = tryL else { if (line.isNotEmpty()) out += line; line = w }
        }
        if (line.isNotEmpty()) out += line
        return if (out.size > maxLines) out.take(maxLines - 1) + (out[maxLines - 1] + " …") else out
    }
    fun lyricFor(g: Group) = if (!withLyrics) "" else lyrics.filter { it.t >= g.start - 0.02 && it.t < g.end - 0.02 }.joinToString(" / ") { it.text }
    val gap = 6f

    if (!condensed) {
        // full chords: boxes, wider where a chord repeats; the lyrics sit under the box they start in
        val n = cols.coerceIn(3, 6); val colW = (cw - (n - 1) * gap) / n; val boxH = 40f
        val spans = IntArray(groups.size); run { var col = 0; groups.forEachIndexed { k, g -> var sp = minOf(g.last - g.first + 1, n); if (sp > n - col) sp = n - col; spans[k] = sp; col = (col + sp) % n } }
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 60, 60); style = Paint.Style.STROKE; strokeWidth = 0.9f }
        var i = 0
        while (i < groups.size) {
            var col = 0; val row = ArrayList<Int>()
            while (i < groups.size && col < n) { row += i; col += spans[i]; i++ }
            val xs = ArrayList<Float>(); val ws = ArrayList<Float>(); run { var px = m; row.forEach { k -> xs += px; val w = colW * spans[k] + gap * (spans[k] - 1); ws += w; px += w + gap } }
            val ly = row.map { lyricFor(groups[it]) }
            val lines = row.indices.map { k ->
                if (ly[k].isBlank()) emptyList() else {
                    val nx = (k + 1 until row.size).firstOrNull { ly[it].isNotBlank() }?.let { xs[it] } ?: (m + cw)
                    wrap(ly[k], 8.5f, (nx - xs[k] - 5f).coerceAtLeast(40f))
                }
            }
            val h = boxH + (lines.maxOfOrNull { it.size } ?: 0).let { if (it > 0) it * 10.5f + 5f else 0f }
            if (y + h + 6 > ph - m - 8f) newPage()
            row.forEachIndexed { k, gi ->
                val g = groups[gi]; val cx = xs[k]; val w = ws[k]
                c.drawRoundRect(cx, y, cx + w, y + boxH, 6f, 6f, box)
                val num = if (g.first == g.last) "${bars[g.first].num}" else "${bars[g.first].num}–${bars[g.last].num}"
                text(num, cx + 4, y + 10, 7.5f, false, grey, w - 8)
                ink.textSize = 17f; ink.typeface = Typeface.DEFAULT_BOLD
                val tw = ink.measureText(g.label); c.drawText(g.label, cx + (w - tw) / 2, y + 30, ink)
                lines[k].forEachIndexed { li, t -> text(t, cx, y + boxH + 11 + li * 10.5f, 8.5f, false, ink, 1000f) }
            }
            y += h + 6
        }
    } else {
        // condensed: one line of bars per row, six to a row. Long runs of the same chord become one wide bar marked "x N bars"
        class Cell(val label: String?, val units: Int, val repeat: Boolean, val bars: Int, val firstBar: Int, val tag: Boolean)
        val cells = ArrayList<Cell>()
        groups.forEach { g ->
            val n = g.last - g.first + 1
            if (n >= 4) cells += Cell(g.label, 2, true, n, g.first, g.keyTag != null)
            else for (q in 0 until n) cells += Cell(if (q == 0) g.label else null, 1, false, 1, g.first + q, q == 0 && g.keyTag != null)
        }
        val per = 6; val staffH = 30f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); strokeWidth = 1f }
        val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); strokeWidth = 1.6f }
        var i = 0
        while (i < cells.size) {
            var used = 0; val row = ArrayList<Cell>()
            while (i < cells.size && used + cells[i].units <= per) { row += cells[i]; used += cells[i].units; i++ }
            if (row.isEmpty()) { row += cells[i]; used = cells[i].units; i++ }
            // lyrics of this row, in order, wrapped across the full width under it
            val gFirst = row.first().firstBar; val gLast = row.last().firstBar + row.last().bars - 1
            val ly = if (!withLyrics) "" else lyrics.filter { l -> val bi = bars.indexOfLast { it.start <= l.t + 0.02 }.coerceAtLeast(0); bi in gFirst..gLast }.joinToString(" / ") { it.text }
            val lines = if (ly.isBlank()) emptyList() else wrap(ly, 8.5f, cw - 12f)
            val h = 16f + staffH + (if (lines.isNotEmpty()) lines.size * 10.5f + 6f else 0f) + 8f
            if (y + h > ph - m - 8f) newPage()
            val top = y + 16f
            val lastRow = i >= cells.size
            val cellW = if (lastRow) cw / per else cw / used
            val rowW = cellW * used
            c.drawLine(m, top, m + rowW, top, line); c.drawLine(m, top + staffH, m + rowW, top + staffH, line)
            text("${bars[gFirst].num}", 6f, top + staffH / 2 + 3f, 7.5f, false, grey, 28f)
            var cx = m
            c.drawLine(cx, top, cx, top + staffH, line)
            row.forEach { cell ->
                val w = cellW * cell.units
                cell.label?.let { text(it, cx + 3, top - 5f, 11.5f, true, ink, w - 4) }
                if (cell.tag) text("▲", cx + w - 10, top - 5f, 7f, true, grey, 10f)
                if (cell.repeat) {
                    ink.textSize = 18f; ink.typeface = Typeface.DEFAULT_BOLD
                    val t = "% ×${cell.bars}"; val tw = ink.measureText(t); c.drawText(t, cx + (w - tw) / 2, top + staffH / 2 + 6f, ink)
                    text("bars ${bars[cell.firstBar].num}–${bars[cell.firstBar + cell.bars - 1].num}", cx + 4, top + staffH - 3, 6.5f, false, grey, w - 8)
                } else if (cell.label != null) {
                    val cxm = cx + w / 2
                    for (k in -1..2) { val sx = cx + w * (0.2f + 0.2f * (k + 1)); c.drawLine(sx - 3.5f, top + staffH * 0.75f, sx + 3.5f, top + staffH * 0.25f, slash) }
                } else {
                    ink.textSize = 18f; ink.typeface = Typeface.DEFAULT_BOLD
                    val tw = ink.measureText("%"); c.drawText("%", cx + (w - tw) / 2, top + staffH / 2 + 6f, ink)
                }
                cx += w; c.drawLine(cx, top, cx, top + staffH, line)
            }
            lines.forEachIndexed { li, t -> text(t, m + 2, top + staffH + 12f + li * 10.5f, 8.5f, false, ink, 1000f) }
            y += h
        }
    }
    // header (song title) and footer (page n of N) on every page; the total is only known now
    val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 110, 110); textSize = 9f; typeface = Typeface.DEFAULT }
    val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.6f }
    if (pages.isNotEmpty()) pages.last().endRecording()
    pages.forEachIndexed { k, pic ->
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, k + 1).create())
        val cv = pg.canvas
        cv.drawPicture(pic)
        var t = title; val maxT = cw - 10f
        if (hp.measureText(t) > maxT) t = t.take(hp.breakText(t, true, maxT - hp.measureText("…"), null)) + "…"
        cv.drawText(t, m, 22f, hp); cv.drawLine(m, 27f, m + cw, 27f, rule)
        val f = "Page ${k + 1} of ${pages.size}"
        cv.drawLine(m, ph - 26f, m + cw, ph - 26f, rule)
        cv.drawText(f, m + cw - hp.measureText(f), ph - 14f, hp)
        doc.finishPage(pg)
    }
    doc.writeTo(out)
    doc.close()
}
