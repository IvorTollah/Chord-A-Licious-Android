// Chord-A-Licious for Android - GPL-3.0-or-later
// Chord names, transposing, and fingering diagrams for six-string guitar (standard tuning, E A D G B E).
package nz.co.chordalicious.app

private val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
private val ROOT_PC = mapOf("C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6,
    "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11)
val ROOT_CHOICES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
val QUALITY_CHOICES = listOf("", "m", "7", "m7", "maj7", "sus2", "sus4", "dim", "aug", "6", "9", "add9", "5")

data class Parsed(val root: Int, val suffix: String, val bass: Int?, val flat: Boolean)

fun parseChord(s: String): Parsed? {
    val t = s.trim()
    if (t.isEmpty() || t == "N.C.") return null
    val m = Regex("^([A-G][#b]?)(.*?)(?:/([A-G][#b]?))?$").find(t) ?: return null
    val root = ROOT_PC[m.groupValues[1]] ?: return null
    val bass = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ROOT_PC[it] }
    return Parsed(root, m.groupValues[2], bass, m.groupValues[1].endsWith("b"))
}

fun transposeChord(s: String, semis: Int, preferFlat: Boolean = false): String {
    if (semis == 0) return s
    val p = parseChord(s) ?: return s
    val names = if (p.flat || preferFlat) FLAT else SHARP
    fun n(pc: Int) = names[((pc + semis) % 12 + 12) % 12]
    return n(p.root) + p.suffix + (p.bass?.let { "/" + n(it) } ?: "")
}

/** "E Major" -> "F Major" etc. */
fun transposeKey(key: String, semis: Int): String {
    val parts = key.trim().split(" ", limit = 2)
    val root = parts.firstOrNull() ?: return key
    if (parseChord(root) == null) return key
    return transposeChord(root, semis) + (if (parts.size > 1) " " + parts[1] else "")
}

// ---------------------------------------------------------------------------------------------- fingerings
enum class Phrasing { Open, Barre, Triad, Power }

/** frets[0..5] = low E ... high E; -1 = not played, 0 = open. */
class Shape(val frets: IntArray, val barre: Boolean = false, val simplified: Boolean = false, val keys: Boolean = false)  // keys: frets holds MIDI notes for the keyboard

private fun f(s: String) = IntArray(6) { i -> if (s[i] == 'x') -1 else s[i] - '0' }

private val OPEN = mapOf(
    "C|" to f("x32010"), "A|" to f("x02220"), "G|" to f("320003"), "E|" to f("022100"), "D|" to f("xx0232"),
    "A|m" to f("x02210"), "E|m" to f("022000"), "D|m" to f("xx0231"),
    "C|7" to f("x32310"), "A|7" to f("x02020"), "G|7" to f("320001"), "E|7" to f("020100"), "D|7" to f("xx0212"), "B|7" to f("x21202"),
    "A|m7" to f("x02010"), "E|m7" to f("020000"), "D|m7" to f("xx0211"),
    "C|maj7" to f("x32000"), "A|maj7" to f("x02120"), "G|maj7" to f("320002"), "D|maj7" to f("xx0222"), "E|maj7" to f("021100"), "F|maj7" to f("xx3210"),
    "A|sus2" to f("x02200"), "A|sus4" to f("x02230"), "D|sus2" to f("xx0230"), "D|sus4" to f("xx0233"), "E|sus4" to f("022200"),
)
private val E_SHAPE = mapOf(
    "" to intArrayOf(0, 2, 2, 1, 0, 0), "m" to intArrayOf(0, 2, 2, 0, 0, 0), "7" to intArrayOf(0, 2, 0, 1, 0, 0),
    "m7" to intArrayOf(0, 2, 0, 0, 0, 0), "maj7" to intArrayOf(0, 2, 1, 1, 0, 0), "sus4" to intArrayOf(0, 2, 2, 2, 0, 0))
private val A_SHAPE = mapOf(
    "" to intArrayOf(-1, 0, 2, 2, 2, 0), "m" to intArrayOf(-1, 0, 2, 2, 1, 0), "7" to intArrayOf(-1, 0, 2, 0, 2, 0),
    "m7" to intArrayOf(-1, 0, 2, 0, 1, 0), "maj7" to intArrayOf(-1, 0, 2, 1, 2, 0), "sus4" to intArrayOf(-1, 0, 2, 2, 3, 0),
    "sus2" to intArrayOf(-1, 0, 2, 2, 0, 0))

/** (quality key, exactly supported?) */
private fun quality(suffix: String): Pair<String, Boolean> = when {
    suffix == "" || suffix == "maj" -> "" to true
    suffix == "m" || suffix == "min" -> "m" to true
    suffix in setOf("7", "m7", "maj7", "sus4", "sus2") -> suffix to true
    suffix == "5" -> "5" to true
    suffix.startsWith("m") && !suffix.startsWith("maj") -> "m" to false
    else -> "" to false
}

private fun movable(t: IntArray, fret: Int) = IntArray(6) { if (t[it] < 0) -1 else t[it] + fret }

private fun barreShape(root: Int, q: String, approx: Boolean): Shape? {
    val f6 = (root - 4 + 12) % 12
    val f5 = (root - 9 + 12) % 12
    val c = mutableListOf<Pair<Int, IntArray>>()
    E_SHAPE[q]?.let { if (f6 > 0) c += f6 to movable(it, f6) }
    A_SHAPE[q]?.let { if (f5 > 0) c += f5 to movable(it, f5) }
    val best = c.minByOrNull { it.first } ?: return null
    return Shape(best.second, barre = true, simplified = approx)
}

private fun triadShape(root: Int, minor: Boolean, approx: Boolean): Shape {
    val tones = intArrayOf(root, (root + if (minor) 3 else 4) % 12, (root + 7) % 12)
    val opens = intArrayOf(7, 11, 4)                                // G, B, high E strings
    val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
    var best: IntArray? = null; var bestKey = Int.MAX_VALUE
    for (p in perms) {
        val fr = IntArray(3) { ((tones[p[it]] - opens[it]) % 12 + 12) % 12 }
        val key = (fr.max() - fr.min()) * 100 + fr.max()
        if (key < bestKey) { bestKey = key; best = fr }
    }
    val b = best!!
    return Shape(intArrayOf(-1, -1, -1, b[0], b[1], b[2]), simplified = approx)
}

private fun powerShape(root: Int, approx: Boolean): Shape {
    val f6 = (root - 4 + 12) % 12
    return if (f6 <= 8) Shape(intArrayOf(f6, f6 + 2, f6 + 2, -1, -1, -1), simplified = approx)
    else { val f5 = (root - 9 + 12) % 12; Shape(intArrayOf(-1, f5, f5 + 2, f5 + 2, -1, -1), simplified = approx) }
}

/** The fingering to show for a chord name. null = nothing to draw (N.C.). */
fun shapeFor(name: String, phrasing: Phrasing): Shape? {
    val p = parseChord(name) ?: return null
    val (q, exact) = quality(p.suffix)
    val approx = !exact || (p.bass != null && p.bass != p.root)
    val minor = q.startsWith("m") && !q.startsWith("maj")
    return when (phrasing) {
        Phrasing.Power -> powerShape(p.root, approx || q != "5" && q != "" && q != "m")
        Phrasing.Triad -> triadShape(p.root, minor, approx || q !in setOf("", "m"))
        Phrasing.Open -> OPEN["${SHARP[p.root]}|$q"]?.let { Shape(it, simplified = approx) } ?: barreShape(p.root, q, approx)
            ?: barreShape(p.root, if (minor) "m" else "", true)
        Phrasing.Barre -> barreShape(p.root, q, approx) ?: OPEN["${SHARP[p.root]}|$q"]?.let { Shape(it, simplified = approx) }
            ?: barreShape(p.root, if (minor) "m" else "", true)
    }
}

// ---------------------------------------------------------------------------------------------- key changes (same method as the website)
class KeySeg(val start: Int, val tonic: Int, val minor: Boolean)

private val MAJ_SCALE = setOf(0, 2, 4, 5, 7, 9, 11)
private class Tri(val root: Int, val notes: IntArray)

private fun triad(name: String): Tri? {
    val p = parseChord(name) ?: return null
    val q = p.suffix
    val minor = Regex("^(m(?!aj)|min|-)").containsMatchIn(q)
    val dim = Regex("^(dim|o|°)").containsMatchIn(q)
    val third = if (dim || minor) 3 else if (q.startsWith("sus2")) 2 else if (q.startsWith("sus4")) 5 else 4
    return Tri(p.root, intArrayOf(p.root, (p.root + third) % 12, (p.root + if (dim) 6 else 7) % 12))
}

private fun fit(c: Tri?, t: Int): Double {
    if (c == null) return 0.0
    var s = 0.0
    for (x in c.notes) if (((x - t) % 12 + 12) % 12 in MAJ_SCALE) s += 1.0 / 3
    if (c.root == t || c.root == (t + 9) % 12) s += 0.5
    else if (c.root == (t + 7) % 12 || c.root == (t + 5) % 12) s += 0.15
    return s
}

private class Win(val sc: DoubleArray, val n: Int)

/** The keys of the song in order. A window slides along the chords; a new key counts once it has beaten the current one several windows running. */
fun keySegments(chords: List<String>, keyText: String): List<KeySeg> {
    val tri = chords.map { triad(it) }
    val w = 12; val hop = 2; val need = 3
    class B(val at: Int, val from: Int, val to: Int)
    val bounds = ArrayList<B>()
    if (tri.size > w) {
        val starts = ArrayList<Int>(); var a = 0
        while (a + w <= tri.size) { starts += a; a += hop }
        if ((tri.size - w) % hop != 0) starts += tri.size - w
        fun score(at: Int): Win { val sc = DoubleArray(12); var n = 0
            for (i in at until at + w) { val c = tri[i] ?: continue; n++; for (t in 0..11) sc[t] += fit(c, t) }
            return Win(sc, n) }
        val wins = starts.map { score(it) }
        fun best(x: Win): Int { var bi = 0; for (i in 1..11) if (x.sc[i] > x.sc[bi]) bi = i; return bi }
        var cur = best(wins[0]); var streak = 0; var cand = -1; var firstW = 0; var lastAt = 2
        for (k in 1 until wins.size) {
            val s = wins[k]; val b = best(s)
            if (b != cur && s.n > 0 && s.sc[b] - s.sc[cur] >= 0.2 * s.n) {
                if (b == cand) streak++ else { cand = b; streak = 1; firstW = k }
                if (streak >= need) {
                    val est = starts[firstW] + w / 2
                    var bestAt = est; var bestV = -1e9
                    for (at in maxOf(lastAt, est - 6)..minOf(tri.size - 2, est + 6)) {
                        var v = 0.0
                        for (i in at until minOf(tri.size, at + 8)) v += fit(tri[i], b) - fit(tri[i], cur)
                        for (i in maxOf(0, at - 8) until at) v += fit(tri[i], cur) - fit(tri[i], b)
                        if (at >= 1 && chords[at] != chords[at - 1]) v += 0.01
                        if (v > bestV) { bestV = v; bestAt = at }
                    }
                    bounds += B(bestAt, cur, b); lastAt = bestAt + 2; cur = b; streak = 0; cand = -1
                }
            } else { streak = 0; cand = -1 }
        }
    }
    val edges = listOf(0) + bounds.map { it.at } + tri.size
    val scales = listOf<Int?>(if (bounds.isNotEmpty()) bounds[0].from else null) + bounds.map { it.to }
    val km = Regex("([A-G][#b]?)\\s+(Major|Minor)", RegexOption.IGNORE_CASE).find(keyText)
    val segs = ArrayList<KeySeg>()
    for (k in 0 until edges.size - 1) {
        var t = scales[k] ?: run {
            val sc = DoubleArray(12)
            tri.forEach { c -> for (q in 0..11) sc[q] += fit(c, q) }
            var bi = 0; for (i in 1..11) if (sc[i] > sc[bi]) bi = i; bi
        }
        var wMaj = 0; var wMin = 0
        for (i in edges[k] until edges[k + 1]) {
            val c = tri[i] ?: continue
            val isMin = c.notes[1] == (c.root + 3) % 12
            if (c.root == t && !isMin) wMaj++
            if (c.root == (t + 9) % 12 && isMin) wMin++
        }
        var tonic = if (wMin > wMaj) (t + 9) % 12 else t; var minor = wMin > wMaj
        if (k == 0 && km != null) {                      // the first stretch keeps the key the analysis reported, when it agrees
            val kt = ROOT_PC[km.groupValues[1]]; val kmin = km.groupValues[2].equals("minor", true)
            if (kt != null && ((if (kmin) kt + 3 else kt) % 12) == t) { tonic = kt; minor = kmin }
        }
        segs += KeySeg(edges[k], tonic, minor)
    }
    return segs
}

fun keyName(seg: KeySeg, shift: Int): String {
    val p = ((seg.tonic + shift) % 12 + 12) % 12
    val asMajor = if (seg.minor) (p + 3) % 12 else p
    val names = if (asMajor in setOf(5, 10, 3, 8, 1)) FLAT else SHARP
    return "${names[p]} ${if (seg.minor) "Minor" else "Major"}"
}

// ---------------------------------------------------------------------------------------------- alternative fingerings
class Opt(val mode: Phrasing, val i: Int, val label: String, val shape: Shape)

/** Every fingering offered for a chord (the carousel in the chord editor): Open, Barre, Triad and Power shapes. */
fun shapeOptions(name: String): List<Opt> {
    val p = parseChord(name) ?: return emptyList()
    val (q, exact) = quality(p.suffix)
    val approx = !exact || (p.bass != null && p.bass != p.root)
    val minor = q.startsWith("m") && !q.startsWith("maj")
    val out = ArrayList<Opt>(); val seen = HashSet<String>()
    fun add(mode: Phrasing, label: String, sh: Shape?) {
        if (sh == null) return
        if (!seen.add(sh.frets.joinToString(","))) return
        out += Opt(mode, out.count { it.mode == mode }, label, sh)
    }
    OPEN["${SHARP[p.root]}|$q"]?.let { add(Phrasing.Open, "Open", Shape(it, simplified = approx)) }
    val f6 = (p.root - 4 + 12) % 12; val f5 = (p.root - 9 + 12) % 12
    E_SHAPE[q]?.let { if (f6 > 0) add(Phrasing.Barre, "Barre", Shape(movable(it, f6), barre = true, simplified = approx)) }
    A_SHAPE[q]?.let { if (f5 > 0) add(Phrasing.Barre, "Barre", Shape(movable(it, f5), barre = true, simplified = approx)) }
    // close-position triads on three neighbouring strings
    val triApprox = approx || q !in setOf("", "m")
    add(Phrasing.Triad, "Triad", triadShape(p.root, minor, triApprox))
    val tones = intArrayOf(p.root, (p.root + if (minor) 3 else 4) % 12, (p.root + 7) % 12)
    val sets = listOf(intArrayOf(3, 4, 5) to intArrayOf(7, 11, 4), intArrayOf(2, 3, 4) to intArrayOf(2, 7, 11), intArrayOf(1, 2, 3) to intArrayOf(9, 2, 7))
    val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
    val cands = ArrayList<Pair<Int, Shape>>()
    for ((strs, opens) in sets) for (pm in perms) {
        val fr = IntArray(3) { ((tones[pm[it]] - opens[it]) % 12 + 12) % 12 }
        val spread = fr.max() - fr.min()
        if (spread > 4) continue
        val full = IntArray(6) { -1 }; for (k in 0..2) full[strs[k]] = fr[k]
        cands += (spread * 100 + fr.max()) to Shape(full, simplified = triApprox)
    }
    cands.sortedBy { it.first }.forEach { if (out.count { o -> o.mode == Phrasing.Triad } < 4) add(Phrasing.Triad, "Triad", it.second) }
    // power chords: root on the 6th string, or on the 5th
    val powApprox = approx || q != "5" && q != "" && q != "m"
    add(Phrasing.Power, "Power", powerShape(p.root, powApprox))
    if (f5 <= 9) add(Phrasing.Power, "Power", Shape(intArrayOf(-1, f5, f5 + 2, f5 + 2, -1, -1), simplified = powApprox))
    return out
}

// ---------------------------------------------------------------------------------------------- tunings (the website's ten)
val TUNINGS: LinkedHashMap<String, IntArray> = linkedMapOf(
    "Standard" to intArrayOf(4, 9, 2, 7, 11, 4), "Drop D" to intArrayOf(2, 9, 2, 7, 11, 4), "Double Drop D" to intArrayOf(2, 9, 2, 7, 11, 2),
    "DADGAD" to intArrayOf(2, 9, 2, 7, 9, 2), "Open G" to intArrayOf(2, 7, 2, 7, 11, 2), "Open D" to intArrayOf(2, 9, 2, 6, 9, 2),
    "Open E" to intArrayOf(4, 11, 4, 8, 11, 4), "Half step down (Eb)" to intArrayOf(3, 8, 1, 6, 10, 3),
    "Whole step down (D)" to intArrayOf(2, 7, 0, 5, 9, 2), "Drop C" to intArrayOf(0, 7, 0, 5, 9, 2))
val TUNING_NOTES = mapOf(
    "Standard" to "E A D G B E", "Drop D" to "D A D G B E", "Double Drop D" to "D A D G B D", "DADGAD" to "D A D G A D",
    "Open G" to "D G D G B D", "Open D" to "D A D F# A D", "Open E" to "E B E G# B E",
    "Half step down (Eb)" to "Eb Ab Db Gb Bb Eb", "Whole step down (D)" to "D G C F A D", "Drop C" to "C G C F A D")

private fun fingersOf(f: IntArray): Int {
    val pos = f.filter { it > 0 }; if (pos.isEmpty()) return 0
    val m = pos.min(); val n = pos.count { it == m }
    if (n >= 2) {                                                         // a barre: one finger across, but no open string can sit under it
        val a = f.indexOfFirst { it == m }; val b = f.indexOfLast { it == m }
        if ((a..b).any { f[it] == 0 }) return 9                          // impossible to play
    }
    return (pos.size - n) + 1 + (if (n >= 2) 0 else 0)
}

private fun tuneCandidates(t: IntArray, root: Int, q: String, minor: Boolean, approx: Boolean): List<Shape> {
    val third = if (q.startsWith("sus2")) 2 else if (q.startsWith("sus4")) 5 else if (minor) 3 else 4
    val tThird = (root + third) % 12; val tFifth = (root + 7) % 12
    val seventh = when (q) { "7", "m7" -> (root + 10) % 12; "maj7" -> (root + 11) % 12; else -> -1 }
    val tones = HashSet<Int>().apply { add(root); add(tThird); add(tFifth); if (seventh >= 0) add(seventh) }
    val need = ArrayList<Int>().apply { add(root); add(tThird); if (seventh >= 0) add(seventh) else add(tFifth) }
    val out = ArrayList<Pair<Int, Shape>>(); val seen = HashSet<String>()
    for (base in 0..9) {
        val allowed = (0..5).map { s ->
            val l = ArrayList<Int>(); l += -1
            if (base < 3 && t[s] % 12 in tones) l += 0
            for (f in base + 1..base + 4) if ((t[s] + f) % 12 in tones) l += f
            l
        }
        val cur = IntArray(6)
        fun go(s: Int) {
            if (s == 6) {
                val played = cur.count { it >= 0 }; if (played < 4) return
                val lo = cur.indexOfFirst { it >= 0 }; val hi = cur.indexOfLast { it >= 0 }
                if ((lo..hi).any { cur[it] < 0 }) return
                if ((t[lo] + cur[lo]) % 12 != root) return
                val have = (0..5).filter { cur[it] >= 0 }.map { (t[it] + cur[it]) % 12 }.toSet()
                if (!need.all { it in have }) return
                val fing = fingersOf(cur); if (fing > 4) return
                val key = cur.joinToString(","); if (!seen.add(key)) return
                val opens = cur.count { it == 0 }
                val score = (6 - played) * 4 + base * 2 + fing - opens * 2
                val pos = cur.filter { it > 0 }; val bar = pos.isNotEmpty() && pos.count { it == pos.min() } >= 3 && opens == 0
                out += score to Shape(cur.copyOf(), barre = bar, simplified = approx)
                return
            }
            for (f in allowed[s]) { cur[s] = f; go(s + 1) }
        }
        go(0)
    }
    return out.sortedBy { it.first }.map { it.second }
}

private fun tuneTriads(t: IntArray, root: Int, minor: Boolean, approx: Boolean): List<Shape> {
    val tones = intArrayOf(root, (root + if (minor) 3 else 4) % 12, (root + 7) % 12)
    val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
    val out = ArrayList<Pair<Int, Shape>>(); val seen = HashSet<String>()
    for (start in 3 downTo 0) for (p in perms) {
        val fr = IntArray(3) { ((tones[p[it]] - t[start + it]) % 12 + 12) % 12 }
        val spread = fr.max() - fr.min(); if (spread > 4) continue
        val full = IntArray(6) { -1 }; for (k in 0..2) full[start + k] = fr[k]
        if (seen.add(full.joinToString(","))) out += (spread * 100 + fr.max()) to Shape(full, simplified = approx)
    }
    return out.sortedBy { it.first }.map { it.second }
}

private fun tunePower(t: IntArray, root: Int, approx: Boolean): List<Shape> {
    val out = ArrayList<Pair<Int, Shape>>(); val seen = HashSet<String>()
    for (s in 0..4) {
        val f0 = ((root - t[s]) % 12 + 12) % 12; val f1 = ((root + 7 - t[s + 1]) % 12 + 12) % 12
        val two = IntArray(6) { -1 }; two[s] = f0; two[s + 1] = f1
        val three = if (s + 2 <= 5) two.copyOf().also { it[s + 2] = ((root - t[s + 2]) % 12 + 12) % 12 } else null
        for (c in listOfNotNull(three, two)) {
            val fr = c.filter { it >= 0 }; if (fr.max() - fr.min() > 4) continue
            if (seen.add(c.joinToString(","))) out += (fr.max() * 10 + s - fr.size) to Shape(c, simplified = approx)
        }
    }
    return out.sortedBy { it.first }.map { it.second }
}

/** Fingerings for a chord in a tuning other than standard, found by search: the website does the same. */
fun tunedOptions(name: String, tuning: IntArray): List<Opt> {
    val p = parseChord(name) ?: return emptyList()
    val (q, exact) = quality(p.suffix)
    val approx = !exact || (p.bass != null && p.bass != p.root)
    val minor = q.startsWith("m") && !q.startsWith("maj")
    val out = ArrayList<Opt>()
    val full = tuneCandidates(tuning, p.root, q, minor, approx)
    full.filter { s -> s.frets.none { it > 4 } || s.frets.any { it == 0 } }.filter { !it.barre }.take(2).forEachIndexed { i, s -> out += Opt(Phrasing.Open, i, "Open", s) }
    if (out.none { it.mode == Phrasing.Open }) full.take(1).forEach { out += Opt(Phrasing.Open, 0, "Open", it) }
    full.filter { it.barre }.take(2).forEachIndexed { i, s -> out += Opt(Phrasing.Barre, i, "Barre", s) }
    if (out.none { it.mode == Phrasing.Barre }) full.filter { s -> s.frets.none { it == 0 } }.take(1).forEach { out += Opt(Phrasing.Barre, 0, "Barre", it) }
    tuneTriads(tuning, p.root, minor, approx || q !in setOf("", "m")).take(4).forEachIndexed { i, s -> out += Opt(Phrasing.Triad, i, "Triad", s) }
    tunePower(tuning, p.root, approx || q != "5" && q != "" && q != "m").take(2).forEachIndexed { i, s -> out += Opt(Phrasing.Power, i, "Power", s) }
    return out
}

// ---------------------------------------------------------------------------------------------- other instruments
class InstSpec(val tuning: IntArray?, val notes: String, val minPlayed: Int, val bass: Boolean = false, val openOnly: Set<Int> = emptySet(), val keys: Boolean = false)
val INSTRUMENTS: LinkedHashMap<String, InstSpec> = linkedMapOf(
    "Guitar" to InstSpec(null, "E A D G B E", 4),
    "Bass" to InstSpec(intArrayOf(4, 9, 2, 7), "E A D G", 2, bass = true),
    "Ukulele" to InstSpec(intArrayOf(7, 0, 4, 9), "G C E A", 4),
    "Banjo" to InstSpec(intArrayOf(7, 2, 7, 11, 2), "g D G B D (open G)", 4, openOnly = setOf(0)),
    "Mandolin" to InstSpec(intArrayOf(7, 2, 9, 4), "G D A E", 4),
    "Keys" to InstSpec(null, "C4 to B5 shown", 0, keys = true))

private val IVS = mapOf("" to intArrayOf(0, 4, 7), "m" to intArrayOf(0, 3, 7), "7" to intArrayOf(0, 4, 7, 10), "m7" to intArrayOf(0, 3, 7, 10),
    "maj7" to intArrayOf(0, 4, 7, 11), "sus4" to intArrayOf(0, 5, 7), "sus2" to intArrayOf(0, 2, 7), "5" to intArrayOf(0, 7))

/** The website's general chord finder: every sensible fingering (chord notes only, within a four-fret span), ranked by phrasing. */
private fun searchFretted(inst: InstSpec, T: IntArray, root: Int, ivs: IntArray, mode: Phrasing, approx: Boolean): List<Shape> =
    searchFrettedScored(inst, T, root, ivs, mode, approx).map { it.first }

/** The same search, keeping how much worse each fingering scores than the best one (0 = the best). */
private fun searchFrettedScored(inst: InstSpec, T: IntArray, root: Int, ivs: IntArray, mode: Phrasing, approx: Boolean): List<Pair<Shape, Double>> {
    val n = T.size; val tones = ivs.map { (root + it) % 12 }; val nT = tones.size
    val required = if (inst.bass) listOf(root) else if (nT == 4) tones.filterIndexed { i, _ -> ivs[i] != 7 } else tones
    val options = (0 until n).map { s ->
        val l = arrayListOf(-1); val maxF = if (s in inst.openOnly) 0 else 12
        for (f in 0..maxF) if ((T[s] + f) % 12 in tones) l += f
        l
    }
    val found = ArrayList<Triple<Double, IntArray, Boolean>>(); val cur = IntArray(n)
    fun evaluate() {
        val played = (0 until n).filter { cur[it] >= 0 }
        if (played.size < (if (mode == Phrasing.Triad) nT else inst.minPlayed)) return
        val first = played.first(); val last = played.last()
        for (s in first..last) if (cur[s] < 0) return
        val pcs = played.map { (T[it] + cur[it]) % 12 }.toSet()
        if (!required.all { it in pcs }) return
        val bassPc = (T[first] + cur[first]) % 12
        if (inst.bass && bassPc != root) return
        if (mode == Phrasing.Triad && (played.size != nT || pcs.size != nT)) return
        val fing = if (inst.bass) 0 else fingersOf(cur); if (fing > 4) return
        val fretted = cur.filter { it > 0 }
        val lo = fretted.minOrNull() ?: 0; val span = (fretted.maxOrNull() ?: 0) - lo
        // a second finger lying flat across several strings is awkward
        val awkward = if (inst.bass) 0 else fretted.filter { it > lo }.groupBy { it }.count { it.value.size > 1 }
        val maxFret = cur.max(); val opens = cur.count { it == 0 }
        var score = span * 0.3 + (n - played.size) * 1.0 + (if (bassPc == root) 0.0 else 0.6) + awkward * 3
        when (mode) {
            Phrasing.Barre -> { if (opens > 0) return; val hasBarre = inst.bass || fretted.count { it == lo } >= 2; score += lo + (if (hasBarre) 0 else 6) }
            Phrasing.Triad -> score += maxFret + (n - 1 - last) * 0.15
            else -> score += maxFret - opens * 0.7 + (if (maxFret > 5) 4 else 0)
        }
        found += Triple(score, cur.copyOf(), opens == 0)
    }
    fun dfs(s: Int, lo: Int, hi: Int) {
        if (s == n) { evaluate(); return }
        for (f in options[s]) {
            var nlo = lo; var nhi = hi
            if (f > 0) { nlo = minOf(lo, f); nhi = maxOf(hi, f); if (nhi - nlo > 3) continue }
            cur[s] = f; dfs(s + 1, nlo, nhi)
        }
    }
    dfs(0, 99, 0)
    val ranked = found.sortedBy { it.first }.take(16)
    val bestScore = ranked.firstOrNull()?.first ?: 0.0
    return ranked.map { (sc, fr, noOpen) ->
        val pos = fr.filter { it > 0 }
        Shape(fr, barre = !inst.bass && pos.isNotEmpty() && pos.count { it == pos.min() } >= 3 && fr.none { it == 0 }, simplified = approx || (mode == Phrasing.Open && noOpen)) to (sc - bestScore)
    }
}

private fun powerFretted(inst: InstSpec, T: IntArray, root: Int, approx: Boolean): Shape? {
    val n = T.size; val five = (root + 7) % 12
    val mono = true
    val reentrant = inst.tuning != null && (T.contentEquals(INSTRUMENTS["Ukulele"]!!.tuning) || T.contentEquals(INSTRUMENTS["Banjo"]!!.tuning))
    var best: Pair<Double, IntArray>? = null
    for (a in 0 until n - 1) for (len in 2..3) {
        if (a + len > n) continue
        val strs = (0 until len).map { a + it }
        val opts = strs.map { s -> val l = ArrayList<Int>(); val maxF = if (s in inst.openOnly) 0 else 12; for (f in 0..maxF) { val p = (T[s] + f) % 12; if (p == root || p == five) l += f }; l }
        val curF = IntArray(len)
        fun walk(i: Int) {
            if (i == len) {
                val pcs = curF.mapIndexed { q, f -> (T[strs[q]] + f) % 12 }
                if (root !in pcs || five !in pcs) return
                if (!reentrant && (pcs[0] != root || pcs[1] != five || (len == 3 && pcs[2] != root))) return
                if (inst.bass && len > 2) return
                val fr = curF.filter { it > 0 }; val lo = fr.minOrNull() ?: 0; val hi = fr.maxOrNull() ?: 0
                if (fr.isNotEmpty() && hi - lo > 2) return
                val score = hi * 0.5 + (hi - lo) * 0.4 + a * 0.9 - len * 1.3 + (if (curF.any { it == 0 }) -0.3 else 0.0)
                if (best == null || score < best!!.first) { val f = IntArray(n) { -1 }; strs.forEachIndexed { q, st -> f[st] = curF[q] }; best = score to f }
                return
            }
            for (f in opts[i]) { curF[i] = f; walk(i + 1) }
        }
        walk(0)
    }
    return best?.let { Shape(it.second, simplified = approx) }
}

private fun pianoShapes(root: Int, ivs: IntArray, approx: Boolean): List<Shape> {
    val tones = ivs.map { (root + it) % 12 }; val out = ArrayList<Shape>()
    for (r in tones.indices) {
        val order = tones.drop(r) + tones.take(r)
        for (base in 60..77) {
            if (base % 12 != order[0]) continue
            val keys = ArrayList<Int>(); keys += base
            for (k in 1 until order.size) { var m = keys.last() + 1; while (m % 12 != order[k]) m++; keys += m }
            if (keys.last() <= 83) { out += Shape(keys.toIntArray(), simplified = approx, keys = true); break }
        }
    }
    return out
}

private val instCache = HashMap<String, List<Opt>>()

/** Fingerings for a chord on another instrument (or a non-standard guitar tuning). */
fun instOptions(name: String, inst: String, tuningName: String): List<Opt> = instCache.getOrPut("$inst|$tuningName|$name") {
    val spec = INSTRUMENTS[inst] ?: return@getOrPut emptyList()
    val p = parseChord(name) ?: return@getOrPut emptyList()
    val (q, exact) = quality(p.suffix)
    val approx = !exact || (p.bass != null && p.bass != p.root)
    val minor = q.startsWith("m") && !q.startsWith("maj")
    val ivs = IVS[q] ?: if (minor) IVS["m"]!! else IVS[""]!!
    val out = ArrayList<Opt>()
    if (spec.keys) {
        val inv = pianoShapes(p.root, ivs, approx); val names = listOf("Root position", "1st inversion", "2nd inversion", "3rd inversion")
        inv.forEachIndexed { i, sh -> out += Opt(Phrasing.Open, i, names.getOrElse(i) { "Inversion" }, sh) }
        val keysPow = intArrayOf(60 + ((p.root - 60) % 12 + 12) % 12).let { b -> intArrayOf(b[0], b[0] + 7, b[0] + 12) }
        out += Opt(Phrasing.Power, 0, "Power", Shape(keysPow, simplified = approx, keys = true))
        return@getOrPut out
    }
    val T = spec.tuning ?: TUNINGS[tuningName] ?: TUNINGS["Standard"]!!
    searchFretted(spec, T, p.root, ivs, Phrasing.Open, approx).take(3).forEachIndexed { i, s -> out += Opt(Phrasing.Open, i, "Open", s) }
    searchFretted(spec, T, p.root, ivs, Phrasing.Barre, approx).take(2).forEachIndexed { i, s -> out += Opt(Phrasing.Barre, i, "Barre", s) }
    if (!spec.bass) searchFretted(spec, T, p.root, ivs, Phrasing.Triad, approx || ivs.size > 3).take(3).forEachIndexed { i, s -> out += Opt(Phrasing.Triad, i, "Triad", s) }
    powerFretted(spec, T, p.root, approx || (q != "5" && q != "" && q != "m"))?.let { out += Opt(Phrasing.Power, 0, "Power", it) }
    out
}

fun shapeIn(name: String, phrasing: Phrasing, tuningName: String, inst: String = "Guitar"): Shape? {
    if (inst == "Guitar") {
        val t = TUNINGS[tuningName]
        if (t == null || tuningName == "Standard") return shapeFor(name, phrasing)
        val opts = tunedOptions(name, t)
        return opts.firstOrNull { it.mode == phrasing }?.shape ?: opts.firstOrNull()?.shape
    }
    val opts = instOptions(name, inst, tuningName)
    return opts.firstOrNull { it.mode == phrasing }?.shape ?: opts.firstOrNull()?.shape
}
fun optionsIn(name: String, tuningName: String, inst: String = "Guitar"): List<Opt> {
    if (inst != "Guitar") return instOptions(name, inst, tuningName)
    val t = TUNINGS[tuningName]
    return if (t == null || tuningName == "Standard") shapeOptions(name) else tunedOptions(name, t)
}


private class Cand(val shape: Shape, val q: Double)

/** Close-position triads on the top strings, every place on the neck (the website's triad finder), nearest the nut first. */
private fun triadsEverywhere(root: Int, minor: Boolean, approx: Boolean): List<Cand> {
    val tones = intArrayOf(root, (root + if (minor) 3 else 4) % 12, (root + 7) % 12)
    val open = intArrayOf(4, 9, 2, 7, 11, 4)
    val found = ArrayList<Triple<Int, Int, Shape>>()
    for ((setIdx, set) in listOf(intArrayOf(3, 4, 5), intArrayOf(2, 3, 4)).withIndex()) {
        val opts = set.map { st -> (0..12).filter { f -> (open[st] + f) % 12 in tones } }
        for (a in opts[0]) for (b in opts[1]) for (c in opts[2]) {
            val fr = intArrayOf(a, b, c)
            if ((0..2).map { (open[set[it]] + fr[it]) % 12 }.toSet().size != 3) continue
            val fretted = fr.filter { it > 0 }
            val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
            if (span > 3) continue
            val full = IntArray(6) { -1 }; for (k in 0..2) full[set[k]] = fr[k]
            found += Triple(fr.max() * 100 + span * 10 + setIdx, 0, Shape(full, simplified = approx))
        }
    }
    return found.sortedBy { it.first }.take(16).map { Cand(it.third, 0.0) }
}

/** Every fingering worth considering for a chord in the chosen phrasing, with a penalty for the less comfortable ones (as on the website). */
private fun plannerCandidates(name: String, phrasing: Phrasing, tuning: String, inst: String): List<Cand> {
    val p = parseChord(name) ?: return emptyList()
    val (q, exact) = quality(p.suffix)
    val approx = !exact || (p.bass != null && p.bass != p.root)
    val minor = q.startsWith("m") && !q.startsWith("maj")
    val ivs = IVS[q] ?: if (minor) IVS["m"]!! else IVS[""]!!
    val opts = optionsIn(name, tuning, inst)
    fun fromOptions(mode: Phrasing) = opts.filter { it.mode == mode }.mapIndexed { i, o -> Cand(o.shape, i * 0.7) }
    val out: List<Cand> = when {
        inst == "Keys" -> opts.filter { it.mode == phrasing }.map { Cand(it.shape, 0.0) }.ifEmpty { opts.filter { it.mode == Phrasing.Open }.map { Cand(it.shape, 0.0) } }
        inst == "Guitar" && tuning == "Standard" -> when (phrasing) {
            Phrasing.Power -> fromOptions(Phrasing.Power)
            Phrasing.Barre -> fromOptions(Phrasing.Barre)
            Phrasing.Triad -> triadsEverywhere(p.root, minor, approx || q !in setOf("", "m"))
            Phrasing.Open -> {
                val tab = OPEN["${SHARP[p.root]}|$q"]
                val eng = searchFrettedScored(INSTRUMENTS["Guitar"]!!, TUNINGS["Standard"]!!, p.root, ivs, Phrasing.Open, approx).map { Cand(it.first, it.second) }
                if (tab != null) {
                    val fretted = tab.filter { it > 0 }
                    // a table shape with no open string is really a movable barre shape (Gm at the 3rd fret): offer the comfortable low fingerings too
                    if (tab.none { it == 0 } && fretted.isNotEmpty())
                        listOf(Cand(Shape(tab, simplified = approx), if (fretted.min() >= 3) 2.5 else 0.0)) + eng.filter { !it.shape.frets.contentEquals(tab) }
                    else listOf(Cand(Shape(tab, simplified = approx), 0.0))
                } else eng + fromOptions(Phrasing.Barre).map { Cand(it.shape, 3.5) }      // no open shape: every low fingering, then the barre shapes as a last resort
            }
        }
        else -> {
            val spec = INSTRUMENTS[inst]
            val T = if (inst == "Guitar") TUNINGS[tuning] else spec?.tuning ?: TUNINGS[tuning]
            if (spec == null || T == null) fromOptions(phrasing)
            else if (phrasing == Phrasing.Power) fromOptions(Phrasing.Power)
            else if (phrasing == Phrasing.Triad && spec.bass) emptyList()
            else searchFrettedScored(spec, T, p.root, ivs, phrasing, approx || (phrasing == Phrasing.Triad && ivs.size > 3)).map { Cand(it.first, it.second) }
        }
    }
    return out
}

/**
 * Chord shapes chosen so the hand travels as little as possible from one chord to the next, as on the website ("shortest hop every time").
 * Every measure gets the fingering, in the chosen phrasing, that keeps the whole song closest together (a Viterbi search over the song).
 * Returns one shape per measure (null where there is none, e.g. N.C.).
 */
fun planShapes(labels: List<String>, phrasing: Phrasing, tuning: String, inst: String, pinned: (Int) -> Shape? = { null }): Array<Shape?> {
    val out = arrayOfNulls<Shape>(labels.size)
    class Layer(val bar: Int, val cands: List<Cand>, val st: List<DoubleArray>, val cost: DoubleArray, val back: IntArray)
    fun stats(s: Shape): DoubleArray {                                            // [lowest fret, middle of the hand]
        if (s.keys) { val k = s.frets.filter { it >= 0 }; return if (k.isEmpty()) doubleArrayOf(0.0, 0.0) else doubleArrayOf(k.min().toDouble(), k.average()) }
        val fr = s.frets.filter { it > 0 }
        if (fr.isEmpty()) return doubleArrayOf(0.0, 0.0)
        val lo = fr.min(); val hi = fr.max()
        return doubleArrayOf(lo.toDouble(), (lo + hi) / 2.0)
    }
    val cache = HashMap<String, List<Cand>>()
    fun cands(name: String): List<Cand> = cache.getOrPut(name) {
        plannerCandidates(name, phrasing, tuning, inst).ifEmpty { listOfNotNull(shapeIn(name, phrasing, tuning, inst)).map { Cand(it, 0.0) } }
    }
    val low = if (inst == "Keys") 0.004 else 0.04
    val layers = ArrayList<Layer>(); var prev: Layer? = null
    for ((bi, name) in labels.withIndex()) {
        if (name.isBlank() || name == "N.C.") continue
        val pin = pinned(bi)                                                       // a fingering the player chose is fixed; the others plan around it
        val cs = if (pin != null) listOf(Cand(pin, 0.0)) else cands(name); if (cs.isEmpty()) continue
        val st = cs.map { stats(it.shape) }
        val cost = DoubleArray(cs.size); val back = IntArray(cs.size) { -1 }
        for (j in cs.indices) {
            val unary = low * st[j][1] + 0.9 * cs[j].q                              // a little for sitting high on the neck, more for the awkward fingerings
            var best = 0.0; var bk = -1
            val p = prev
            if (p != null) {
                best = Double.MAX_VALUE
                for (i in p.cands.indices) {
                    val c = p.cost[i] + Math.abs(p.st[i][1] - st[j][1]) + 0.5 * Math.abs(p.st[i][0] - st[j][0])
                    if (c < best - 1e-9) { best = c; bk = i }
                }
            }
            cost[j] = best + unary; back[j] = bk
        }
        val l = Layer(bi, cs, st, cost, back); layers += l; prev = l
    }
    if (layers.isEmpty()) return out
    val last = layers.last()
    var j = last.cost.indices.minByOrNull { last.cost[it] } ?: 0
    for (li in layers.indices.reversed()) { val l = layers[li]; out[l.bar] = l.cands[j].shape; j = l.back[j]; if (j < 0) j = 0 }
    return out
}
