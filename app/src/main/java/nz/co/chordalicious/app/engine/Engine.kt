// SPDX-License-Identifier: GPL-3.0-or-later
package nz.co.chordalicious.app.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The Chord-A-Licious analysis engine, on the phone. A line-by-line port of the server's transcribe()
 * (via the NumPy mirror in dev/engine_mirror, which matched librosa 1.0.0 on the test songs).
 * Input: mono 22050 Hz float samples. Output: the same bars / chords / key / tempo the server returns.
 */
class ChordSeg(val start: Double, val end: Double, val chord: String)
class PartOut(val chord: String, val start: Double, val end: Double)
class DetailOut(val chord: String, val also: List<String>, val parts: List<PartOut>)
class MeasureOut(val num: Int, val start: Double, val end: Double, val chord: String, val also: List<String>, val parts: List<PartOut>, var detail: DetailOut? = null)

class Analysis(
    val bpm: Double, val key: String, val beatsPerBar: Int,
    val chords: List<ChordSeg>, val measures: List<MeasureOut>,
    val chordSeconds: Map<String, Double>, val peaks: List<Double>, val duration: Double,
) {
    /** Same shape as the server's /predict-chords reply (the app's parser reads this). */
    fun toJson(title: String = "", artist: String = ""): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun parts(l: List<PartOut>) = l.joinToString(",", "[", "]") { "{\"chord\":${q(it.chord)},\"start\":${it.start},\"end\":${it.end}}" }
        val sb = StringBuilder("{")
        sb.append("\"filename\":${q(title)},\"title\":${q(title)},\"artist\":${q(artist)},\"total_chords\":${chords.size},")
        sb.append("\"bpm\":$bpm,\"key\":${q(key)},\"beats_per_bar\":$beatsPerBar,\"duration\":$duration,")
        sb.append("\"chords\":").append(chords.joinToString(",", "[", "]") { "{\"start\":${it.start},\"end\":${it.end},\"chord\":${q(it.chord)}}" }).append(",")
        sb.append("\"chord_seconds\":").append(chordSeconds.entries.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }).append(",")
        sb.append("\"measures\":").append(measures.joinToString(",", "[", "]") { m ->
            val d = m.detail?.let { ",\"detail\":{\"chord\":${q(it.chord)},\"also\":${it.also.joinToString(",", "[", "]") { a -> q(a) }},\"parts\":${parts(it.parts)}}" } ?: ""
            "{\"num\":${m.num},\"start\":${m.start},\"end\":${m.end},\"chord\":${q(m.chord)},\"also\":${m.also.joinToString(",", "[", "]") { q(it) }},\"parts\":${parts(m.parts)}$d}"
        }).append(",")
        sb.append("\"peaks\":").append(peaks.joinToString(",", "[", "]")).append("}")
        return sb.toString()
    }
}

class AnalysisCancelled : RuntimeException("Cancelled")

class Engine(private val progress: (Double) -> Unit = {}, private val cancelled: () -> Boolean = { false }) {
    companion object {
        const val SR = 22050
        const val HOP = 512
        const val CH_NFFT = 8192
        const val CH_HOP = 2048
        const val CHROMA_FULL_FMIN = 100.0
        const val LOG_GAMMA = 100.0
        const val TRANSITION_SELF = 0.80
        const val EMISSION_TEMP = 0.08
        const val BASS_ROOT_FLOOR = 1.0
        const val BASS_WEIGHT = 0.15
        const val SCALE_THRESHOLD = 0.35
        const val SCALE_GAMMA = 2.0
        const val SCALE_FLOOR = 0.05
        const val MIN_CONFIDENCE = 0.55
        const val MAX_ABSORB_S = 1.3
        const val REF_STEP_S = 0.45
        const val SILENCE_RATIO = 0.04
        const val HARMONIC_DECAY = 0.6
        const val SEVENTH_THRESHOLD = 0.90
        const val MIN_CHORD_SHARE = 0.015
        const val RARE_CHORD_PENALTY = 0.001
        const val MAX_STEP_S = 0.55
        const val SLASH_MIN_SHARE = 0.40
        const val SLASH_OVER_ROOT = 1.30
        const val SLASH_MIN_S = 0.25
        const val SLASH_STRICT_SHARE = 0.60
        const val SLASH_STRICT_OVER_ROOT = 2.0
        const val SLASH_STRICT_MIN_S = 0.70
        const val SEVENTH_MIN_SHARE = 0.50
        const val SLASH_MIN_RUNS = 2
        const val SLASH_MIN_TOTAL_S = 3.0
        const val VARIANT_MIN_SHARE = 0.30
        const val VARIANT_MIN_S = 10.0
        const val SUS_MARGIN = 0.12
        const val SUS_MARGIN_SHORT = 0.15
        const val SUS_MIN_BEATS = 3
        const val SUS_THIRD_MAX = 0.90
        const val HALF_TIME_MARGIN = 1.10
        const val BASS_WIN = 16384
        const val BASS_NFFT = 32768
        const val BASS_STEP = 2048
        const val BASS_F_LO = 38.0
        const val BASS_F_HI = 200.0

        const val N_CHORD_STATES = 24
        const val N_STATE = 24
        const val N_STATES = 25
        val NAMES_FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
        val KS_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val KS_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        private val TRIADS = arrayOf(intArrayOf(0, 4, 7), intArrayOf(0, 3, 7))
        private val HARMONIC_OFFSETS = IntArray(6) { Math.floorMod(Math.rint(12 * log2((it + 1).toDouble())).toInt(), 12) }

        private fun template(ivs: IntArray, root: Int): DoubleArray {
            val v = DoubleArray(12)
            for (iv in ivs) for (h in HARMONIC_OFFSETS.indices) v[(root + iv + HARMONIC_OFFSETS[h]) % 12] += Math.pow(HARMONIC_DECAY, h.toDouble())
            val nrm = sqrt(v.sumOf { it * it })
            for (i in 0 until 12) v[i] /= nrm
            return v
        }
        val TEMPLATES: Array<DoubleArray> = Array(N_CHORD_STATES) { template(TRIADS[it % 2], it / 2) }
        val SUS_TEMPLATES: Map<Int, Array<DoubleArray>> = mapOf(2 to Array(12) { template(intArrayOf(0, 2, 7), it) }, 4 to Array(12) { template(intArrayOf(0, 5, 7), it) })
        val ROOTS = IntArray(N_CHORD_STATES) { it / 2 }
    }

    private fun check() { if (cancelled()) throw AnalysisCancelled() }

    // ---------------------------------------------------------------- small helpers
    private fun rint(x: Double) = Math.rint(x)
    private fun r2(x: Double) = Math.round(x * 100.0) / 100.0
    private fun r1(x: Double) = Math.round(x * 10.0) / 10.0

    private fun fixFrames(frames: List<Int>, xMax: Int): IntArray {
        val s = java.util.TreeSet<Int>()
        for (f in frames) if (f in 0..xMax) s.add(f)
        s.add(0); s.add(xMax)
        return s.toIntArray()
    }

    /** Mean of columns a until b for each row (or the column at a when b <= a). */
    private fun syncMean(data: Array<DoubleArray>, idx: IntArray): Array<DoubleArray> {
        val nb = idx.size - 1
        val nCols = data[0].size
        return Array(data.size) { r ->
            DoubleArray(nb) { k ->
                val a = idx[k]; val b = idx[k + 1]
                if (b > a) { var s = 0.0; for (c in a until b) s += data[r][c]; s / (b - a) } else data[r][min(a, nCols - 1)]
            }
        }
    }

    private fun l2Columns(x: Array<DoubleArray>) {
        val n = x[0].size
        for (c in 0 until n) {
            var s = 0.0; for (r in x.indices) s += x[r][c] * x[r][c]
            var nrm = sqrt(s); if (nrm == 0.0) nrm = 1.0
            for (r in x.indices) x[r][c] /= nrm
        }
    }

    private fun viterbi(prob: Array<DoubleArray>, transition: Array<DoubleArray>): IntArray {
        val tiny = java.lang.Double.MIN_NORMAL
        val n = prob.size; val T = prob[0].size
        val lt = Array(n) { i -> DoubleArray(n) { j -> ln(transition[i][j] + tiny) } }
        val lp = Array(n) { i -> DoubleArray(T) { t -> ln(prob[i][t] + tiny) } }
        val value = Array(n) { DoubleArray(T) }
        val ptr = Array(n) { IntArray(T) }
        for (s in 0 until n) value[s][0] = ln(1.0 / n) + lp[s][0]
        for (t in 1 until T) {
            for (j in 0 until n) {
                var best = Double.NEGATIVE_INFINITY; var bi = 0
                for (i in 0 until n) { val m = value[i][t - 1] + lt[i][j]; if (m > best) { best = m; bi = i } }
                ptr[j][t] = bi; value[j][t] = best + lp[j][t]
            }
        }
        val path = IntArray(T)
        var bv = Double.NEGATIVE_INFINITY; var bs = 0
        for (s in 0 until n) if (value[s][T - 1] > bv) { bv = value[s][T - 1]; bs = s }
        path[T - 1] = bs
        for (t in T - 2 downTo 0) path[t] = ptr[path[t + 1]][t + 1]
        return path
    }

    // ---------------------------------------------------------------- key, prior, sevenths
    private fun estimateKey(chroma: DoubleArray): Pair<Int, Boolean> {
        val mean = chroma.average()
        var sd = 0.0; for (v in chroma) sd += (v - mean) * (v - mean); sd = sqrt(sd / 12)
        if (sd < 1e-9) return Pair(0, false)
        var best = Pair(0, false); var bestR = -2.0
        for (tonic in 0 until 12) for (minor in booleanArrayOf(false, true)) {
            val prof = if (minor) KS_MINOR else KS_MAJOR
            val rolled = DoubleArray(12) { prof[Math.floorMod(it - tonic, 12)] }
            val pm = rolled.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in 0 until 12) { val a = chroma[i] - mean; val b = rolled[i] - pm; sxy += a * b; sxx += a * a; syy += b * b }
            val r = sxy / sqrt(sxx * syy)
            if (r > bestR) { bestR = r; best = Pair(tonic, minor) }
        }
        return best
    }

    private fun scalePrior(g0: DoubleArray): DoubleArray {
        val srt = g0.sortedArray()
        val ref = (srt[9] + srt[10] + srt[11]) / 3.0
        val g = if (ref > 0) DoubleArray(12) { g0[it] / ref } else g0
        val prior = DoubleArray(N_STATES)
        for (root in 0 until 12) for (q in 0 until 2) {
            var p = 1.0
            for (iv in TRIADS[q]) p *= Math.pow(min(1.0, g[(root + iv) % 12] / SCALE_THRESHOLD), SCALE_GAMMA)
            prior[root * 2 + q] = max(SCALE_FLOOR, p)
        }
        prior[N_STATE] = 1.0
        return prior
    }

    private fun spellingForKey(tonic: Int, minor: Boolean): Array<String> {
        val mt = if (minor) (tonic + 3) % 12 else tonic
        val flatKeys = intArrayOf(5, 10, 3, 8, 1)
        val n = NAMES_FLAT.copyOf()
        n[1] = if (mt in intArrayOf(2, 9, 4, 11, 6)) "C#" else "Db"
        n[3] = if (mt in intArrayOf(11, 6)) "D#" else "Eb"
        n[6] = if (mt in flatKeys) "Gb" else "F#"
        n[8] = if (mt in intArrayOf(9, 4, 11, 6)) "G#" else "Ab"
        n[10] = if (mt == 6) "A#" else "Bb"
        return n
    }

    private fun seventhSuffix(c: DoubleArray, root: Int, minor: Boolean): String {
        val third = if (minor) 3 else 4
        val tri = (c[root % 12] + c[(root + third) % 12] + c[(root + 7) % 12]) / 3.0
        if (tri <= 1e-9) return if (minor) "m" else ""
        val b7 = c[(root + 10) % 12] / tri
        val maj7 = c[(root + 11) % 12] / tri
        if (b7 >= SEVENTH_THRESHOLD && b7 > 1.2 * maj7) return if (minor) "m7" else "7"
        if (!minor && maj7 >= SEVENTH_THRESHOLD && maj7 > 1.2 * b7) return "maj7"
        return if (minor) "m" else ""
    }

    private fun meanCols(m: Array<DoubleArray>, a: Int, b: Int) = DoubleArray(12) { r -> var s = 0.0; for (c in a..b) s += m[r][c]; s / (b - a + 1) }

    // ---------------------------------------------------------------- sus chords
    private fun detectSus(full: Array<DoubleArray>, bass: Array<DoubleArray>, path: IntArray, triScore: DoubleArray): IntArray {
        val T = full[0].size
        val sus = IntArray(T); val margin = DoubleArray(T)
        val c = DoubleArray(12)
        for (k in 0 until T) {
            val st = path[k]
            if (st >= N_CHORD_STATES) continue
            val r = st / 2
            for (i in 0 until 12) c[i] = full[i][k]
            val third = max(c[(r + 3) % 12], c[(r + 4) % 12])
            val core = (c[r] + c[(r + 7) % 12]) / 2.0
            if (third >= SUS_THIRD_MAX * core) continue
            var s2 = 0.0; var s4 = 0.0
            for (i in 0 until 12) { s2 += SUS_TEMPLATES[2]!![r][i] * c[i]; s4 += SUS_TEMPLATES[4]!![r][i] * c[i] }
            s2 += BASS_WEIGHT * bass[r][k]; s4 += BASS_WEIGHT * bass[r][k]
            val (sc, kind) = if (s2 >= s4) Pair(s2, 2) else Pair(s4, 4)
            val d = sc - triScore[k]
            if (d >= SUS_MARGIN) { sus[k] = kind; margin[k] = d }
        }
        val out = IntArray(T)
        var i = 0
        while (i < T) {
            var j = i
            while (j + 1 < T && sus[j + 1] == sus[i] && path[j + 1] / 2 == path[i] / 2) j++
            val n = j - i + 1
            if (sus[i] != 0) {
                var mm = 0.0; for (q in i..j) mm += margin[q]; mm /= n
                if (n >= SUS_MIN_BEATS || (n == 2 && mm >= SUS_MARGIN_SHORT)) for (q in i..j) out[q] = sus[i]
            }
            i = j + 1
        }
        return out
    }

    // ---------------------------------------------------------------- bass line
    class BassTrack(val times: DoubleArray, val pcs: IntArray, val weight: DoubleArray)

    private fun percentile(a: DoubleArray, p: Double): Double {
        val s = a.sortedArray(); val pos = (s.size - 1) * p / 100.0
        val lo = floor(pos).toInt(); val hi = min(s.size - 1, lo + 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    private fun bassPitchTrack(y: FloatArray, sr: Int): BassTrack? {
        val n = BASS_WIN; val nfft = BASS_NFFT
        if (y.size < n + BASS_STEP) return null
        val df = sr.toDouble() / nfft
        val nf = ((y.size - n) + BASS_STEP - 1) / BASS_STEP                       // np.arange(0, len(y)-n, step)
        val nFund = ceil((BASS_F_HI - BASS_F_LO) / 0.25).toInt()
        val fund = DoubleArray(nFund) { BASS_F_LO + 0.25 * it }
        val idxs = Array(4) { h -> IntArray(nFund) { rint(fund[it] * (h + 1) / df).toInt() } }
        val wts = doubleArrayOf(1.0, 0.8, 0.6, 0.45)
        val win = Dsp.hanningSym(n)
        val times = DoubleArray(nf) { (it * BASS_STEP + n / 2.0) / sr }
        val bestF = DoubleArray(nf); val weight = DoubleArray(nf); val midi = DoubleArray(nf)
        val fft = Fft.of(nfft)
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        val h = nfft / 2
        val aRe = DoubleArray(h + 1); val aIm = DoubleArray(h + 1); val bRe = DoubleArray(h + 1); val bIm = DoubleArray(h + 1)
        val sal = DoubleArray(nFund)
        fun frameOut(f: Int, sr_: DoubleArray, si_: DoubleArray) {
            for (k in 0 until nFund) {
                var s = 0.0
                for (q in 0 until 4) { val b = idxs[q][k]; val mag = sqrt(sr_[b] * sr_[b] + si_[b] * si_[b]); s += wts[q] * ln1p(200.0 * mag / (n / 4.0)) }
                sal[k] = s
            }
            var kb = 0; var top = Double.NEGATIVE_INFINITY
            for (k in 0 until nFund) if (sal[k] > top) { top = sal[k]; kb = k }
            val floorV = Dsp.median(sal)
            bestF[f] = fund[kb]
            weight[f] = max(top - floorV, 0.0)
            midi[f] = 69 + 12 * log2(fund[kb] / 440.0)
        }
        var f = 0
        while (f < nf) {
            check()
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val a0 = f * BASS_STEP
            for (i in 0 until n) re[i] = y[a0 + i] * win[i]
            val two = f + 1 < nf
            if (two) { val b0 = (f + 1) * BASS_STEP; for (i in 0 until n) im[i] = y[b0 + i] * win[i] }
            fft.twoReal(re, im, aRe, aIm, bRe, bIm)
            frameOut(f, aRe, aIm)
            if (two) frameOut(f + 1, bRe, bIm)
            f += 2
        }
        val thr = percentile(weight, 60.0)
        val dev = ArrayList<Double>()
        for (i in 0 until nf) if (weight[i] > thr) dev.add(midi[i] - rint(midi[i]))
        val tuning = if (dev.isEmpty()) 0.0 else Dsp.median(dev.toDoubleArray()).coerceIn(-0.5, 0.5)
        val pcs = IntArray(nf) { Math.floorMod(rint(midi[it] - tuning).toInt(), 12) }
        return BassTrack(times, pcs, weight)
    }

    private fun slashBassBeat(tr: BassTrack, t0: Double, t1: Double, root: Int, ivs: List<Int>): Int? {
        if (tr.times.isEmpty()) return null
        val hist = DoubleArray(12); var cnt = 0
        for (i in tr.times.indices) if (tr.times[i] >= t0 && tr.times[i] < t1) { hist[tr.pcs[i]] += tr.weight[i]; cnt++ }
        if (cnt < 2) return null
        val total = hist.sum()
        if (total <= 0) return null
        var b = 0; var bv = -1.0
        for (i in 0 until 12) if (hist[i] / total > bv) { bv = hist[i] / total; b = i }
        val allowed = ivs.map { (root + it) % 12 }.toSet() - (root % 12)
        if (b == root % 12 || b !in allowed) return null
        val third = Math.floorMod(b - root, 12) in intArrayOf(3, 4)
        val needShare = if (third) SLASH_MIN_SHARE else SLASH_STRICT_SHARE
        val needRatio = if (third) SLASH_OVER_ROOT else SLASH_STRICT_OVER_ROOT
        val share = hist[b] / total; val rootShare = hist[root % 12] / total
        if (share < needShare || share < needRatio * rootShare) return null
        return b
    }

    private fun slashBeats(tr: BassTrack, syncTimes: DoubleArray, path: IntArray, sus: IntArray, ext: IntArray?): IntArray {
        val T = path.size
        val cand = IntArray(T) { -1 }
        for (k in 0 until T) {
            val st = path[k]
            if (st >= N_CHORD_STATES) continue
            val root = ROOTS[st]; val minor = st % 2 == 1
            val sk = sus[k]
            var ivs: List<Int> = if (sk != 0) (if (sk == 2) listOf(0, 2, 7) else listOf(0, 5, 7)) else (if (minor) listOf(0, 3, 7) else listOf(0, 4, 7))
            if (ext != null && ext[k] != 0) ivs = ivs + ext[k]
            val b = slashBassBeat(tr, syncTimes[k], syncTimes[k + 1], root, ivs)
            if (b != null) cand[k] = b
        }
        val out = IntArray(T) { -1 }
        var i = 0
        while (i < T) {
            var j = i
            while (j + 1 < T && cand[j + 1] == cand[i] && path[j + 1] == path[i]) j++
            if (cand[i] >= 0) {
                val root = ROOTS[path[i]]
                val third = Math.floorMod(cand[i] - root, 12) in intArrayOf(3, 4)
                val need = if (third) SLASH_MIN_S else SLASH_STRICT_MIN_S
                if (syncTimes[j + 1] - syncTimes[i] >= need) for (q in i..j) out[q] = cand[i]
            }
            i = j + 1
        }
        return out
    }

    // ---------------------------------------------------------------- chroma
    class Chroma(val full: Array<DoubleArray>, val bass: Array<DoubleArray>)

    private fun estimateTuning(freqs: DoubleArray, sh: Array<FloatArray>, binLo: Int, selLo: Int, selHi: Int, nf: Int): Double {
        // sh[bin - binLo][frame]; bins selLo..selHi inclusive are the 100-2000 Hz range
        val devs = ArrayList<Double>(); val wts = ArrayList<Double>()
        val col = DoubleArray(selHi - selLo + 1)
        var j = 0
        while (j < nf) {
            var mx = 0.0
            for (k in selLo..selHi) { col[k - selLo] = sh[k - binLo][j].toDouble(); if (col[k - selLo] > mx) mx = col[k - selLo] }
            if (mx > 0) {
                val h = mx * 0.1
                for (i in 1 until col.size - 1) {
                    if (col[i] > col[i - 1] && col[i] >= col[i + 1] && col[i] >= h) {
                        val m = 69 + 12 * log2(freqs[selLo + i] / 440.0)
                        devs.add(m - rint(m)); wts.add(col[i])
                    }
                }
            }
            j += 2
        }
        if (devs.isEmpty()) return 0.0
        val bins = 50; val first = -0.5; val last = 0.5
        val edges = DoubleArray(bins + 1) { first + (last - first) * it / bins }
        val hist = DoubleArray(bins)
        for (i in devs.indices) {
            val x = devs[i]
            if (x < first || x > last) continue
            var idx = ((x - first) * (bins / (last - first))).toInt()
            if (idx == bins) idx -= 1
            if (x < edges[idx]) idx -= 1
            if (idx != bins - 1 && x >= edges[idx + 1]) idx += 1
            hist[idx] += wts[i]
        }
        var bi = 0; for (i in 1 until bins) if (hist[i] > hist[bi]) bi = i
        return (edges[bi] + edges[bi + 1]) / 2.0
    }

    private fun computeChroma(y: FloatArray, sr: Int): Chroma {
        val nf = Dsp.nFrames(y.size, CH_HOP)
        val binLo = 6; val binHi = 792                      // only the bins the chroma maps (40-2100 Hz) and the filters need
        val S = Dsp.magnitudes(y, CH_NFFT, CH_HOP, binLo, binHi)
        val nb = binHi - binLo
        for (r in S) for (t in 0 until nf) r[t] = r[t] / (CH_NFFT / 2.0f)
        check()
        val horiz = Array(nb) { FloatArray(nf) }
        val tmp9 = FloatArray(9); val tmp17 = FloatArray(17)
        for (f in 0 until nb) Dsp.medianRange(S[f], 9, 0, nf, horiz[f], 0, tmp9)
        val col = FloatArray(nb); val vert = FloatArray(nb)
        for (t in 0 until nf) {
            for (f in 0 until nb) col[f] = S[f][t]
            Dsp.medianRange(col, 17, 0, nb, vert, 0, tmp17)
            for (f in 0 until nb) horiz[f][t] = if (horiz[f][t] > 3.0f * vert[f]) S[f][t] else 0.0f      // S_h reuses horiz
        }
        val sh = horiz
        check()
        val freqs = DoubleArray(CH_NFFT / 2 + 1) { it * (sr / 2.0) / (CH_NFFT / 2) }
        var selLo = -1; var selHi = -1
        for (k in freqs.indices) if (freqs[k] > 100 && freqs[k] < 2000) { if (selLo < 0) selLo = k; selHi = k }
        val tuning = estimateTuning(freqs, sh, binLo, selLo, selHi, nf)
        fun build(fmin: Double, fmax: Double): Array<DoubleArray> {
            val out = Array(12) { DoubleArray(nf) }
            for (k in binLo until binHi) {
                val fq = freqs[k]
                if (fq < fmin || fq > fmax) continue
                val m = 69 + 12 * log2(fq / 440.0) - tuning
                val nearest = rint(m)
                if (abs(m - nearest) > 0.35) continue
                val pc = Math.floorMod(nearest.toInt(), 12)
                val row = sh[k - binLo]
                for (t in 0 until nf) out[pc][t] += ln1p(LOG_GAMMA * row[t])
            }
            return out
        }
        return Chroma(build(CHROMA_FULL_FMIN, 2100.0), build(40.0, 260.0))
    }

    // ---------------------------------------------------------------- half-time check, bar refinement
    private fun feltHalfTime(y: FloatArray, sr: Int, bpm: Double): Boolean {
        try {
            val env0 = Dsp.onsetStrength(y, sr, HOP)
            val mean = env0.average()
            val env = DoubleArray(env0.size) { env0[it] - mean }
            val ac = Dsp.autocorrelate(env, (sr / HOP * 60.0 / 40.0).toInt() + 8)
            if (ac[0] <= 0) return false
            for (i in ac.indices.reversed()) ac[i] /= ac[0]
            val fps = sr.toDouble() / HOP
            fun pulse(b: Double): Double {
                val lo = rint(fps * 60.0 / (b * 1.03)).toInt(); val hi = rint(fps * 60.0 / (b / 1.03)).toInt()
                var m = Double.NEGATIVE_INFINITY
                for (i in max(lo, 1)..min(hi, ac.size - 1)) m = max(m, ac[i])
                if (m == Double.NEGATIVE_INFINITY) throw IllegalStateException("empty")
                return m
            }
            val full = pulse(bpm); val half = pulse(bpm / 2.0)
            return half > full * HALF_TIME_MARGIN
        } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { return false }
    }

    private fun pymod(a: Double, m: Double) = ((a % m) + m) % m

    private fun refineBars(beatTimes: DoubleArray, barLen: Double, bpb: Int, syncTimes: DoubleArray, labels: List<String>): Pair<Double, Double> {
        val bt = beatTimes
        if (bt.size < 16) return Pair(barLen, 0.0)
        val t0 = barLen / bpb
        val idx = DoubleArray(bt.size)
        for (i in 1 until bt.size) idx[i] = idx[i - 1] + max(rint((bt[i] - bt[i - 1]) / t0), 1.0)
        var keep = BooleanArray(bt.size) { true }
        var slope = 0.0; var icpt = 0.0
        for (rep in 0 until 3) {
            var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in bt.indices) if (keep[i]) { n++; sx += idx[i]; sy += bt[i]; sxx += idx[i] * idx[i]; sxy += idx[i] * bt[i] }
            slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
            icpt = (sy - slope * sx) / n
            keep = BooleanArray(bt.size) { abs(bt[it] - (icpt + slope * idx[it])) < 0.25 * slope }
            if (keep.count { it } < 12) return Pair(barLen, 0.0)
        }
        if (abs(slope / t0 - 1.0) > 0.04) return Pair(barLen, 0.0)
        val newLen = slope * bpb
        val changes = ArrayList<Double>()
        for (k in 1 until labels.size) if (labels[k] != labels[k - 1] && labels[k] != "N.C." && labels[k - 1] != "N.C." && k < syncTimes.size) changes.add(syncTimes[k])
        if (changes.size < 4) return Pair(newLen, 0.0)
        var best = 0.0; var bestScore = -1.0
        for (j in 0 until bpb) {
            val ph = pymod(icpt + j * slope, newLen)
            var score = 0.0
            for (c in changes) { val d = abs(pymod((c - ph) / newLen + 0.5, 1.0) - 0.5); score += exp(-(d / 0.04) * (d / 0.04)) }
            if (score > bestScore + 1e-9) { best = ph; bestScore = score }
        }
        return Pair(newLen, if (best > 0.05) best - newLen else best)
    }

    // ---------------------------------------------------------------- the whole analysis
    fun transcribe(y: FloatArray, sr: Int = SR, sevenths: Boolean = true): Analysis {
        val totalDur = y.size.toDouble() / sr
        progress(0.02)

        // 1. beats from the percussive part
        val yPerc = Dsp.percussive(y)
        check(); progress(0.15)
        val bt = Dsp.beatTrack(yPerc, sr, HOP)
        var bpm = bt.bpm
        check(); progress(0.25)
        val nBeatFrames = 1 + y.size / HOP
        var beats = bt.frames.filter { it > 0 && it < nBeatFrames }.toIntArray()
        if (beats.size < 4) {
            val step = max(1, (0.5 * sr / HOP).toInt())
            beats = (step until nBeatFrames step step).toList().toIntArray()
        }
        var grid = fixFrames(beats.toList(), nBeatFrames)
        while (grid.size > 2) {
            val d = DoubleArray(grid.size - 1) { (grid[it + 1] - grid[it]).toDouble() }
            if (Dsp.median(d) * HOP / sr > MAX_STEP_S) {
                val extra = ArrayList<Int>(); for (i in 0 until grid.size - 1) extra.add(Math.floorDiv(grid[i] + grid[i + 1], 2))
                val s = java.util.TreeSet<Int>(); grid.forEach { s.add(it) }; s.addAll(extra); grid = s.toIntArray()
            } else break
        }
        val gridTimes = DoubleArray(grid.size) { grid[it].toDouble() * HOP / sr }

        // 2. chroma + bass
        val ch = computeChroma(y, sr)
        check(); progress(0.55)
        val bass: BassTrack? = try { bassPitchTrack(y, sr) } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { null }
        progress(0.85)
        val nCf = ch.full[0].size

        // 3. beat synchronisation
        val idxL = ArrayList<Int>()
        for (g in gridTimes) { val v = rint(g * sr / CH_HOP).toInt(); if (v > 0 && v < nCf) idxL.add(v) }
        val syncFrames = fixFrames(idxL, nCf)
        val syncTimes = DoubleArray(syncFrames.size) { syncFrames[it].toDouble() * CH_HOP / sr }
        syncTimes[syncTimes.size - 1] = totalDur
        val fullBeat = syncMean(ch.full, syncFrames); l2Columns(fullBeat)
        val bassBeat = syncMean(ch.bass, syncFrames); l2Columns(bassBeat)
        val nBeats = fullBeat[0].size

        // 6. key
        val fullMean = DoubleArray(12) { r -> fullBeat[r].average() }
        val (tonic, isMinorKey) = estimateKey(fullMean)
        val names = spellingForKey(tonic, isMinorKey)
        val keyName = "${names[tonic]} ${if (isMinorKey) "Minor" else "Major"}"

        // 7. emissions
        val simsCos = Array(N_CHORD_STATES) { s -> DoubleArray(nBeats) { k -> var a = 0.0; for (i in 0 until 12) a += TEMPLATES[s][i] * fullBeat[i][k]; a } }
        val sims = Array(N_CHORD_STATES) { s -> DoubleArray(nBeats) { k -> simsCos[s][k] + BASS_WEIGHT * bassBeat[ROOTS[s]][k] } }
        val basePrior = scalePrior(fullMean)
        val bassRootFactor = Array(N_CHORD_STATES) { s -> DoubleArray(nBeats) { k ->
            var mx = 1e-9; for (r in 0 until 12) if (bassBeat[r][k] > mx) mx = bassBeat[r][k]
            BASS_ROOT_FLOOR + (1.0 - BASS_ROOT_FLOOR) * sqrt(bassBeat[ROOTS[s]][k] / mx) } }

        var rms = rmsFrames(y, CH_HOP)
        if (rms.size < nCf) rms = rms + DoubleArray(nCf - rms.size) { rms.last() }
        rms = rms.copyOf(nCf)
        val rmsBeat = syncMean(arrayOf(rms), syncFrames)[0]
        val meanRms = rmsBeat.average()
        val silent = BooleanArray(nBeats) { rmsBeat[it] < SILENCE_RATIO * meanRms }
        val stepDur = DoubleArray(nBeats) { syncTimes[it + 1] - syncTimes[it] }
        val stepMed = if (stepDur.isNotEmpty()) Dsp.median(stepDur) else REF_STEP_S
        val selfP = Math.pow(TRANSITION_SELF, stepMed / REF_STEP_S).coerceIn(0.05, 0.995)
        val maxAbsorb = max(1, rint(MAX_ABSORB_S / max(stepMed, 1e-3)).toInt())
        val transition = Array(N_STATES) { i -> DoubleArray(N_STATES) { j -> if (i == j) selfP else (1.0 - selfP) / (N_STATES - 1) } }

        fun decode(w: DoubleArray): IntArray {
            val emis = Array(N_STATES) { DoubleArray(nBeats) }
            for (k in 0 until nBeats) {
                var mx = Double.NEGATIVE_INFINITY; for (s in 0 until N_CHORD_STATES) if (sims[s][k] > mx) mx = sims[s][k]
                val e = DoubleArray(N_CHORD_STATES)
                var sum = 0.0
                for (s in 0 until N_CHORD_STATES) { e[s] = exp((sims[s][k] - mx) / EMISSION_TEMP) * (basePrior[s] * w[s]) * bassRootFactor[s][k]; sum += e[s] }
                for (s in 0 until N_CHORD_STATES) emis[s][k] = e[s] / sum * (1.0 - 1e-3)
                emis[N_STATE][k] = 1e-3
                if (silent[k]) { for (s in 0 until N_CHORD_STATES) emis[s][k] = 1e-4; emis[N_STATE][k] = 1.0 }
                var tot = 0.0; for (s in 0 until N_STATES) tot += emis[s][k]
                for (s in 0 until N_STATES) emis[s][k] /= tot
            }
            return viterbi(emis, transition)
        }

        var path = decode(DoubleArray(N_CHORD_STATES) { 1.0 })
        check()
        run {
            val secs = DoubleArray(N_CHORD_STATES)
            for (k in 0 until nBeats) if (!silent[k] && path[k] < N_CHORD_STATES) secs[path[k]] += stepDur[k]
            val total = secs.sum()
            if (total > 0) path = decode(DoubleArray(N_CHORD_STATES) { if (secs[it] / total >= MIN_CHORD_SHARE) 1.0 else RARE_CHORD_PENALTY })
        }
        // absorb short, low-confidence runs
        val conf = DoubleArray(nBeats) { if (path[it] < N_CHORD_STATES) simsCos[path[it]][it] else 1.0 }
        val runs = ArrayList<IntArray>()
        run { var i = 0; while (i < nBeats) { var j = i; while (j + 1 < nBeats && path[j + 1] == path[i]) j++; runs.add(intArrayOf(i, j)); i = j + 1 } }
        for ((k, ab) in runs.withIndex()) {
            val a = ab[0]; val b = ab[1]
            if (runs.size > 1 && path[a] != N_STATE && (b - a + 1) <= maxAbsorb) {
                var cm = 0.0; for (q in a..b) cm += conf[q]; cm /= (b - a + 1)
                if (cm < MIN_CONFIDENCE) { val nb = if (k > 0) runs[k - 1][0] else runs[k + 1][0]; val v = path[nb]; for (q in a..b) path[q] = v }
            }
        }
        check()

        // 8b. sus2 / sus4
        var susArr = IntArray(nBeats)
        try {
            val tri = DoubleArray(nBeats) { if (path[it] < N_CHORD_STATES) sims[path[it]][it] else 0.0 }
            susArr = detectSus(fullBeat, bassBeat, path, tri)
        } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { }
        var slashArr = IntArray(nBeats) { -1 }
        if (bass != null) {
            try {
                var ext: IntArray? = null
                if (sevenths) {
                    ext = IntArray(nBeats)
                    var a0 = 0
                    while (a0 < nBeats) {
                        var b0 = a0
                        while (b0 + 1 < nBeats && path[b0 + 1] == path[a0] && susArr[b0 + 1] == susArr[a0]) b0++
                        if (path[a0] < N_CHORD_STATES && susArr[a0] == 0) {
                            val sfx = seventhSuffix(meanCols(fullBeat, a0, b0), ROOTS[path[a0]], path[a0] % 2 == 1)
                            val v = if (sfx == "7" || sfx == "m7") 10 else if (sfx == "maj7") 11 else 0
                            for (q in a0..b0) ext[q] = v
                        }
                        a0 = b0 + 1
                    }
                }
                slashArr = slashBeats(bass, syncTimes, path, susArr, ext)
            } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { }
        }
        // 8c. song-level clean-up
        var strip7 = HashSet<Int>()
        try {
            if (sevenths) {
                val tot = HashMap<Int, Double>(); val sev = HashMap<Int, Double>()
                var a0 = 0
                while (a0 < nBeats) {
                    var b0 = a0
                    while (b0 + 1 < nBeats && path[b0 + 1] == path[a0] && susArr[b0 + 1] == susArr[a0]) b0++
                    val st0 = path[a0]
                    if (st0 < N_CHORD_STATES && susArr[a0] == 0) {
                        val d0 = syncTimes[b0 + 1] - syncTimes[a0]
                        val sfx0 = seventhSuffix(meanCols(fullBeat, a0, b0), ROOTS[st0], st0 % 2 == 1)
                        tot[st0] = (tot[st0] ?: 0.0) + d0
                        if (sfx0 != "" && sfx0 != "m") sev[st0] = (sev[st0] ?: 0.0) + d0
                    }
                    a0 = b0 + 1
                }
                strip7 = tot.filter { (sev[it.key] ?: 0.0) < SEVENTH_MIN_SHARE * it.value }.keys.toHashSet()
            }
            val pairRuns = HashMap<Pair<Int, Int>, Int>(); val pairDur = HashMap<Pair<Int, Int>, Double>()
            var a0 = 0
            while (a0 < nBeats) {
                var b0 = a0
                while (b0 + 1 < nBeats && path[b0 + 1] == path[a0] && susArr[b0 + 1] == susArr[a0] && slashArr[b0 + 1] == slashArr[a0]) b0++
                if (slashArr[a0] >= 0) {
                    val kk = Pair(path[a0], slashArr[a0])
                    pairRuns[kk] = (pairRuns[kk] ?: 0) + 1
                    pairDur[kk] = (pairDur[kk] ?: 0.0) + (syncTimes[b0 + 1] - syncTimes[a0])
                }
                a0 = b0 + 1
            }
            for (kk in pairRuns.keys) if ((pairRuns[kk] ?: 0) < SLASH_MIN_RUNS || (pairDur[kk] ?: 0.0) < SLASH_MIN_TOTAL_S)
                for (k in 0 until nBeats) if (path[k] == kk.first && slashArr[k] == kk.second) slashArr[k] = -1
        } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { }
        val susD = susArr.copyOf(); val slashD = slashArr.copyOf()
        // 8d. fold rare variants into the plain chord
        try {
            val fam = HashMap<Int, Double>(); val vr = HashMap<Triple<Int, Int, Int>, Double>()
            for (k in 0 until nBeats) {
                val st0 = path[k]; if (st0 >= N_CHORD_STATES) continue
                val d0 = stepDur[k]
                fam[st0] = (fam[st0] ?: 0.0) + d0
                val vk = Triple(st0, susArr[k], slashArr[k]); vr[vk] = (vr[vk] ?: 0.0) + d0
            }
            val fold = vr.filter { (vk, d0) -> (vk.second != 0 || vk.third >= 0) && (d0 < VARIANT_MIN_SHARE * fam[vk.first]!! || d0 < VARIANT_MIN_S) }.keys
            if (fold.isNotEmpty()) for (k in 0 until nBeats) if (Triple(path[k], susArr[k], slashArr[k]) in fold) { susArr[k] = 0; slashArr[k] = -1 }
        } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { }

        // 9. labels
        fun labels(susA: IntArray, slashA: IntArray, strip: Set<Int>): Pair<List<String>, List<ChordSeg>> {
            val bl = Array(nBeats) { "" }; val segs = ArrayList<ChordSeg>()
            var i = 0
            while (i < nBeats) {
                var j = i
                while (j + 1 < nBeats && path[j + 1] == path[i] && susA[j + 1] == susA[i] && slashA[j + 1] == slashA[i]) j++
                val state = path[i]; val sk = susA[i]
                val label: String
                if (state == N_STATE) label = "N.C." else {
                    val root = ROOTS[state]; val minor = state % 2 == 1
                    val suffix = if (sk != 0) "sus$sk" else if (sevenths && state !in strip) seventhSuffix(meanCols(fullBeat, i, j), root, minor) else if (minor) "m" else ""
                    var l = "${names[root]}$suffix"
                    if (slashA[i] >= 0) l = "$l/${names[slashA[i]]}"
                    label = l
                }
                for (k in i..j) bl[k] = label
                segs.add(ChordSeg(r2(syncTimes[i]), r2(syncTimes[j + 1]), label))
                i = j + 1
            }
            return Pair(bl.toList(), segs)
        }
        val (beatLabels, segments) = labels(susArr, slashArr, strip7)
        var beatLabelsD: List<String>? = null
        try { val (bd, _) = labels(susD, slashD, emptySet()); if (bd != beatLabels) beatLabelsD = bd } catch (e: Exception) { }

        // 10. bars
        val halfFelt = bpm >= 110 && bpm < 150 && feltHalfTime(y, sr, bpm)
        var beatsPerBar = if (bpm >= 150 || halfFelt) 8 else (if (bpm > 0 && bpm <= 65) 2 else 4)
        var barLen = if (bpm > 0) beatsPerBar * 60.0 / bpm else 2.0
        val bpbTracker = beatsPerBar
        if (bpm >= 150 || halfFelt) { bpm /= 2.0; beatsPerBar = 4 }
        var origin = 0.0
        try {
            val oldLen = barLen
            val bts = DoubleArray(beats.size) { beats[it].toDouble() * HOP / sr }
            val (nl, og) = refineBars(bts, barLen, bpbTracker, syncTimes, beatLabels)
            barLen = nl; origin = og; bpm *= oldLen / barLen
        } catch (e: Exception) { origin = 0.0 }
        val stepStart = DoubleArray(nBeats) { syncTimes[it] }; val stepEnd = DoubleArray(nBeats) { syncTimes[it + 1] }

        fun bars(bl: List<String>): List<MeasureOut> {
            val ms = ArrayList<MeasureOut>()
            val nBars = ceil((totalDur - origin) / barLen).toInt()
            for (num in 0 until nBars) {
                val s0 = max(0.0, origin + num * barLen); val s1 = min(origin + (num + 1) * barLen, totalDur)
                if (s1 - s0 < 0.2) continue
                val dur = LinkedHashMap<String, Double>()
                val ovl = ArrayList<Int>()
                for (k in 0 until nBeats) {
                    val o = max(0.0, min(stepEnd[k], s1) - max(stepStart[k], s0))
                    if (o > 0) { ovl.add(k); dur[bl[k]] = (dur[bl[k]] ?: 0.0) + o }
                }
                if (dur.isEmpty()) continue
                var main = ""; var mv = -1.0
                for ((c, d) in dur) if (d > mv) { mv = d; main = c }
                val also = dur.entries.sortedByDescending { it.value }.filter { it.key != main && it.key != "N.C." && it.value >= 0.25 * (s1 - s0) }.map { it.key }
                var runs2 = ArrayList<DoubleArray>(); val runLab = ArrayList<String>()
                for (k in ovl) {
                    val a0 = max(stepStart[k], s0); val b0 = min(stepEnd[k], s1)
                    if (runLab.isNotEmpty() && runLab.last() == bl[k]) runs2.last()[1] = b0 else { runLab.add(bl[k]); runs2.add(doubleArrayOf(a0, b0)) }
                }
                while (runs2.size > 1) {
                    var short = 0; var sd = Double.MAX_VALUE
                    for (q in runs2.indices) { val d = runs2[q][1] - runs2[q][0]; if (d < sd) { sd = d; short = q } }
                    if (runs2[short][1] - runs2[short][0] >= 0.15 * (s1 - s0)) break
                    val nb = if (short > 0) short - 1 else short + 1
                    runs2[nb][0] = min(runs2[nb][0], runs2[short][0]); runs2[nb][1] = max(runs2[nb][1], runs2[short][1])
                    runs2.removeAt(short); runLab.removeAt(short)
                    val mR = ArrayList<DoubleArray>(); val mL = ArrayList<String>()
                    for (q in runs2.indices) { if (mL.isNotEmpty() && mL.last() == runLab[q]) mR.last()[1] = runs2[q][1] else { mL.add(runLab[q]); mR.add(runs2[q]) } }
                    runs2 = mR; runLab.clear(); runLab.addAll(mL)
                }
                val parts = if (runs2.size > 1) runs2.indices.map { PartOut(runLab[it], r2(runs2[it][0]), r2(runs2[it][1])) } else emptyList()
                ms.add(MeasureOut(ms.size + 1, r2(s0), r2(s1), main, also, parts))
            }
            return ms
        }
        val measures = bars(beatLabels)
        if (beatLabelsD != null) try {
            val md = bars(beatLabelsD)
            if (md.size == measures.size) for ((m, d) in measures.zip(md)) {
                if (d.chord != m.chord || d.also != m.also || d.parts.map { it.chord } != m.parts.map { it.chord }) m.detail = DetailOut(d.chord, d.also, d.parts)
            }
        } catch (e: Exception) { }
        progress(1.0)

        val cs = LinkedHashMap<String, Double>()
        for (sg in segments) cs[sg.chord] = (cs[sg.chord] ?: 0.0) + (sg.end - sg.start)
        val chordSeconds = LinkedHashMap<String, Double>()
        for ((k, v) in cs.entries.sortedByDescending { it.value }) chordSeconds[k] = r1(v)
        return Analysis(r1(bpm), keyName, beatsPerBar, segments, measures, chordSeconds, peaks(y), y.size.toDouble() / sr)
    }

    private fun rmsFrames(y: FloatArray, hop: Int, frame: Int = 2048): DoubleArray {
        val n = 1 + y.size / hop
        return DoubleArray(n) { t ->
            val a = t * hop - frame / 2
            var s = 0.0; var c = 0
            for (i in 0 until frame) { val p = a + i; if (p >= y.size + frame / 2) break; if (p >= y.size) { c++; continue }; c++; if (p >= 0) s += y[p].toDouble() * y[p] }
            sqrt(s / c)
        }
    }

    private fun peaks(y: FloatArray, bins: Int = 800): List<Double> {
        val p = DoubleArray(bins)
        for (b in 0 until bins) {
            val a = (y.size.toDouble() * b / bins).toLong().toInt(); val e = (y.size.toDouble() * (b + 1) / bins).toLong().toInt()
            var m = 0.0; for (i in a until e) m = max(m, abs(y[i].toDouble())); p[b] = m
        }
        val mx = p.max()
        return p.map { Math.round((if (mx > 0) it / mx else it) * 1000.0) / 1000.0 }
    }
}
