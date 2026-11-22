// SPDX-License-Identifier: GPL-3.0-or-later
package nz.co.chordalicious.app.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Audio maths for the on-device chord engine: the pieces librosa / scipy / numpy gave the server.
 * A line-by-line port of dsp_numpy.py (which was checked against librosa 1.0.0).
 * Pure Kotlin, no Android classes, so it also runs in plain JVM tests.
 */
object Dsp {
    const val TINY32 = 1.17549435E-38f   // np.finfo(float32).tiny

    // ------------------------------------------------------------ windows
    /** scipy get_window('hann', n, fftbins=True): the periodic Hann window librosa uses. */
    fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }

    /** np.hanning(n): the symmetric one. */
    fun hanningSym(n: Int) = DoubleArray(n) { if (n == 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }

    // ------------------------------------------------------------ STFT
    fun nFrames(len: Int, hop: Int) = 1 + len / hop

    /** Frame t of the centre-padded signal (zeros outside), windowed, into re (im zeroed). */
    private fun loadFrame(y: FloatArray, nFft: Int, hop: Int, t: Int, win: DoubleArray, re: DoubleArray, off: Int = 0) {
        val start = t * hop - nFft / 2
        for (i in 0 until nFft) {
            val s = start + i
            re[off + i] = if (s < 0 || s >= y.size) 0.0 else y[s] * win[i]
        }
    }

    /**
     * Calls fn(t, re, im) for every frame with bins 0..nFft/2 of that frame's spectrum (valid until fn returns).
     * Two frames share one complex FFT.
     */
    fun stftFrames(y: FloatArray, nFft: Int, hop: Int, fn: (Int, DoubleArray, DoubleArray) -> Unit) {
        val fft = Fft.of(nFft)
        val win = hann(nFft)
        val nf = nFrames(y.size, hop)
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        val h = nFft / 2
        val aRe = DoubleArray(h + 1); val aIm = DoubleArray(h + 1); val bRe = DoubleArray(h + 1); val bIm = DoubleArray(h + 1)
        var t = 0
        while (t < nf) {
            if (t + 1 < nf) {
                loadFrame(y, nFft, hop, t, win, re); loadFrame(y, nFft, hop, t + 1, win, im)
                fft.twoReal(re, im, aRe, aIm, bRe, bIm)
                fn(t, aRe, aIm); fn(t + 1, bRe, bIm)
                t += 2
            } else {
                loadFrame(y, nFft, hop, t, win, re); java.util.Arrays.fill(im, 0.0)
                fft.transform(re, im)
                fn(t, re, im)        // only bins 0..h are read by callers
                t += 1
            }
        }
    }

    /** |STFT| for bins binLo until binHi, as [bin - binLo][frame]. */
    fun magnitudes(y: FloatArray, nFft: Int, hop: Int, binLo: Int, binHi: Int): Array<FloatArray> {
        val nf = nFrames(y.size, hop)
        val out = Array(binHi - binLo) { FloatArray(nf) }
        stftFrames(y, nFft, hop) { t, re, im ->
            for (k in binLo until binHi) out[k - binLo][t] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------ median filters
    private fun refl(i: Int, n: Int): Int {          // scipy 'reflect' = numpy 'symmetric': d c b a | a b c d | d c b a
        var j = i
        while (j < 0 || j >= n) { if (j < 0) j = -j - 1 else j = 2 * n - j - 1 }
        return j
    }

    /**
     * Running median (odd window `size`) of x over positions from until to, written to out[outOff + (i - from)].
     * Edges of the WHOLE array x are mirrored. Keeps the window sorted; one insert and one remove per step.
     */
    fun medianRange(x: FloatArray, size: Int, from: Int, to: Int, out: FloatArray, outOff: Int, tmp: FloatArray = FloatArray(size)) {
        val n = x.size
        val pad = size / 2
        val w = tmp
        for (k in 0 until size) w[k] = x[refl(from - pad + k, n)]
        java.util.Arrays.sort(w, 0, size)
        out[outOff] = w[pad]
        for (i in from + 1 until to) {
            val outV = x[refl(i - 1 - pad, n)]
            val inV = x[refl(i + pad, n)]
            if (outV != inV) {
                // remove outV (binary search), insert inV keeping order
                var p = java.util.Arrays.binarySearch(w, 0, size, outV)
                if (p < 0) p = 0     // cannot happen (value is in the window); keeps us safe
                // shift to fill the hole, then insert
                if (inV > outV) {
                    var q = p
                    while (q + 1 < size && w[q + 1] < inV) { w[q] = w[q + 1]; q++ }
                    w[q] = inV
                } else {
                    var q = p
                    while (q - 1 >= 0 && w[q - 1] > inV) { w[q] = w[q - 1]; q-- }
                    w[q] = inV
                }
            }
            out[outOff + (i - from)] = w[pad]
        }
    }

    // ------------------------------------------------------------ percussive part (librosa.effects.percussive)
    /** Median-filter HPSS with a soft mask, back to a waveform. margin 3, kernel 31, n_fft 2048, hop 512. Works in blocks to keep memory low. */
    fun percussive(y: FloatArray, margin: Float = 3.0f, kernel: Int = 31, nFft: Int = 2048): FloatArray {
        val hop = nFft / 4
        val nf = nFrames(y.size, hop)
        val nb = nFft / 2 + 1
        val S = magnitudes(y, nFft, hop, 0, nb)
        val fft = Fft.of(nFft)
        val win = hann(nFft)
        val total = nFft + hop * (nf - 1)
        val acc = FloatArray(total)
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        val BLOCK = 512
        val harm = Array(nb) { FloatArray(BLOCK) }
        val col = FloatArray(nb); val perc = FloatArray(nb)
        val tmp = FloatArray(kernel)
        var a = 0
        while (a < nf) {
            val b = min(nf, a + BLOCK)
            for (f in 0 until nb) medianRange(S[f], kernel, a, b, harm[f], 0, tmp)
            for (t in a until b) {
                for (f in 0 until nb) col[f] = S[f][t]
                medianRange(col, kernel, 0, nb, perc, 0, tmp)
                // spectrum of this frame again, then the soft mask
                loadFrame(y, nFft, hop, t, win, re); java.util.Arrays.fill(im, 0.0)
                fft.transform(re, im)
                for (f in 0 until nb) {
                    val p = perc[f]; val hh = harm[f][t - a] * margin
                    val z0 = if (p > hh) p else hh
                    val bad = z0 < TINY32
                    val z = if (bad) 1.0f else z0
                    val m: Float
                    if (bad) m = 0.0f else {
                        val mp = (p / z) * (p / z); val rf = (hh / z) * (hh / z)
                        m = mp / (rf + mp)
                    }
                    re[f] *= m; im[f] *= m
                }
                for (f in 1 until nFft / 2) { re[nFft - f] = re[f]; im[nFft - f] = -im[f] }     // Hermitian
                im[0] = 0.0; im[nFft / 2] = 0.0
                fft.transform(re, im, inverse = true)
                val base = t * hop
                for (i in 0 until nFft) acc[base + i] += (re[i] * win[i]).toFloat()
            }
            a = b
        }
        // divide by the summed squared window, trim the centre padding
        val w2 = DoubleArray(nFft) { win[it] * win[it] }
        val out = FloatArray(y.size)
        for (s in 0 until y.size) {
            val p = s + nFft / 2
            var ws = 0.0
            var t = (p - nFft + 1 + hop - 1).let { Math.floorDiv(it, hop) }.coerceAtLeast(0)
            while (t < nf && t * hop <= p) { ws += w2[p - t * hop]; t++ }
            out[s] = if (ws > 1e-10) (acc[p] / ws).toFloat() else acc[p]
        }
        return out
    }

    // ------------------------------------------------------------ mel + onset strength
    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3; val minLogHz = 1000.0; val logstep = ln(6.4) / 27.0; val minLogMel = minLogHz / fSp
        return if (f >= minLogHz) minLogMel + ln(max(f, 1e-9) / minLogHz) / logstep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3; val minLogHz = 1000.0; val logstep = ln(6.4) / 27.0; val minLogMel = minLogHz / fSp
        return if (m >= minLogMel) minLogHz * exp(logstep * (m - minLogMel)) else fSp * m
    }

    /** librosa.filters.mel (Slaney scale, area normalised) as sparse rows: start bin, weights. */
    class Mel(val start: IntArray, val w: Array<FloatArray>)

    fun melFilterbank(sr: Int, nFft: Int, nMels: Int = 128): Mel {
        val nb = 1 + nFft / 2
        val fmax = sr / 2.0
        val fft = DoubleArray(nb) { fmax * it / (nb - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(fmax)
        val melF = DoubleArray(nMels + 2) { melToHz(lo + (hi - lo) * it / (nMels + 1)) }
        val start = IntArray(nMels); val rows = Array(nMels) { FloatArray(0) }
        for (i in 0 until nMels) {
            val fd0 = melF[i + 1] - melF[i]; val fd1 = melF[i + 2] - melF[i + 1]
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            val vals = DoubleArray(nb)
            var first = -1; var last = -1
            for (k in 0 until nb) {
                val lower = -(melF[i] - fft[k]) / fd0
                val upper = (melF[i + 2] - fft[k]) / fd1
                val v = max(0.0, min(lower, upper)) * enorm
                vals[k] = v
                if (v > 0) { if (first < 0) first = k; last = k }
            }
            if (first < 0) { start[i] = 0; rows[i] = FloatArray(0) } else { start[i] = first; rows[i] = FloatArray(last - first + 1) { vals[first + it].toFloat() } }
        }
        return Mel(start, rows)
    }

    /** librosa.onset.onset_strength: mel power in dB, positive frame difference, median (beat tracker) or mean over the bands. */
    fun onsetStrength(y: FloatArray, sr: Int, hop: Int = 512, nFft: Int = 2048, nMels: Int = 128, median: Boolean = false): DoubleArray {
        val mel = melFilterbank(sr, nFft, nMels)
        val nf = nFrames(y.size, hop)
        val db = FloatArray(nf * nMels)
        var gmax = -Float.MAX_VALUE
        val p = FloatArray(nFft / 2 + 1)
        stftFrames(y, nFft, hop) { t, re, im ->
            for (k in 0..nFft / 2) { val m = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat(); p[k] = m * m }
            for (b in 0 until nMels) {
                val w = mel.w[b]; val s0 = mel.start[b]
                var acc = 0.0f
                for (j in w.indices) acc += w[j] * p[s0 + j]
                val v = (10.0 * log10(max(1e-10f, acc).toDouble())).toFloat()
                db[t * nMels + b] = v
                if (v > gmax) gmax = v
            }
        }
        val floor = gmax - 80.0f
        for (i in db.indices) if (db[i] < floor) db[i] = floor
        val flux = DoubleArray(max(0, nf - 1))
        val d = FloatArray(nMels)
        for (t in 1 until nf) {
            for (b in 0 until nMels) { val v = db[t * nMels + b] - db[(t - 1) * nMels + b]; d[b] = if (v > 0f) v else 0f }
            if (median) { val s = d.copyOf(); java.util.Arrays.sort(s); flux[t - 1] = (s[nMels / 2 - 1] + s[nMels / 2]) / 2.0 }
            else { var a = 0.0; for (b in 0 until nMels) a += d[b]; flux[t - 1] = a / nMels }
        }
        val pad = 1 + nFft / (2 * hop)
        val out = DoubleArray(nf)
        for (i in 0 until nf) { val j = i - pad; out[i] = if (j in flux.indices) flux[j] else 0.0 }
        return out
    }

    // ------------------------------------------------------------ autocorrelation, tempo, beats
    /** librosa.autocorrelate (1-D): FFT based, zero padded; first maxSize lags. */
    fun autocorrelate(x: DoubleArray, maxSize: Int = x.size): DoubleArray {
        val n = x.size
        val ms = min(maxSize, n)
        var npad = 1; while (npad < 2 * n - 1) npad = npad shl 1
        val fft = Fft.of(npad)
        val re = DoubleArray(npad); val im = DoubleArray(npad)
        System.arraycopy(x, 0, re, 0, n)
        fft.transform(re, im)
        for (k in 0 until npad) { re[k] = re[k] * re[k] + im[k] * im[k]; im[k] = 0.0 }
        fft.transform(re, im, inverse = true)
        return re.copyOf(ms)
    }

    /** librosa.feature.rhythm.tempo: mean autocorrelation tempogram weighted by a log-normal prior on 120 BPM. */
    fun tempoEstimate(env: DoubleArray, sr: Int, hop: Int = 512, startBpm: Double = 120.0, stdBpm: Double = 1.0, maxTempo: Double = 320.0, winLength: Int = 384): Double {
        val n = env.size
        val half = winLength / 2
        val padded = DoubleArray(half + n + half)
        for (i in 0 until half) padded[i] = env[0] * i / half                               // linspace(0, env[0], half, endpoint=False)
        System.arraycopy(env, 0, padded, half, n)
        for (i in 0 until half) padded[half + n + i] = env[n - 1] * (1.0 - (i + 1).toDouble() / half)     // linspace(env[-1], 0, half+1)[1:]
        val win = hann(winLength)
        var npad = 1; while (npad < 2 * winLength - 1) npad = npad shl 1
        val fft = Fft.of(npad)
        val re = DoubleArray(npad); val im = DoubleArray(npad)
        val tg = DoubleArray(winLength)
        for (t in 0 until n) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until winLength) re[i] = padded[t + i] * win[i]
            fft.transform(re, im)
            for (k in 0 until npad) { re[k] = re[k] * re[k] + im[k] * im[k]; im[k] = 0.0 }
            fft.transform(re, im, inverse = true)
            var peak = 0.0
            for (i in 0 until winLength) peak = max(peak, abs(re[i]))
            if (peak == 0.0) peak = 1.0
            for (i in 0 until winLength) tg[i] += re[i] / peak
        }
        for (i in 0 until winLength) tg[i] /= n
        var best = 0; var bestV = Double.NEGATIVE_INFINITY
        for (lag in 0 until winLength) {
            val bpm = if (lag == 0) Double.POSITIVE_INFINITY else 60.0 * sr / (hop * lag.toDouble())
            val prior = if (lag == 0 || bpm > maxTempo) Double.NEGATIVE_INFINITY else -0.5 * ((log2(bpm) - log2(startBpm)) / stdBpm).pow(2)
            val v = ln(1.0 + 1e6 * tg[lag]) + prior
            if (v.isNaN()) { best = lag; break }                  // numpy argmax returns the first NaN
            if (v > bestV) { bestV = v; best = lag }
        }
        return if (best == 0) Double.POSITIVE_INFINITY else 60.0 * sr / (hop * best.toDouble())
    }

    private fun localMax(x: DoubleArray): BooleanArray {
        val m = BooleanArray(x.size)
        for (i in 1 until x.size - 1) m[i] = x[i] > x[i - 1] && x[i] >= x[i + 1]
        if (x.size >= 2) m[x.size - 1] = x[x.size - 1] > x[x.size - 2]
        return m
    }

    fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        val s = a.copyOf(); s.sort()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    class Beats(val bpm: Double, val frames: IntArray)

    /** librosa.beat.beat_track: Ellis (2007) dynamic-programming beat tracker on the onset envelope of the percussive signal. */
    fun beatTrack(yPerc: FloatArray, sr: Int, hop: Int = 512, tightness: Double = 100.0, trim: Boolean = true): Beats {
        val env = onsetStrength(yPerc, sr, hop, median = true)
        if (env.none { it != 0.0 }) return Beats(0.0, IntArray(0))
        val bpm = tempoEstimate(env, sr, hop)
        val fpb = Math.rint(sr / hop.toDouble() * 60.0 / bpm)
        val n = env.size
        // oenv = env / (std(ddof=1) + tiny)
        var mean = 0.0; for (v in env) mean += v; mean /= n
        var ss = 0.0; for (v in env) ss += (v - mean) * (v - mean)
        val std = sqrt(ss / (n - 1))
        val oenv = DoubleArray(n) { env[it] / (std + TINY32) }
        val fp = fpb.toInt()
        val window = DoubleArray(2 * fp + 1) { exp(-0.5 * ((it - fp) * 32.0 / fpb).pow(2)) }
        val local = DoubleArray(n)
        for (i in 0 until n) {                                  // np.convolve(oenv, window, 'same'); the window is symmetric
            var s = 0.0
            for (k in window.indices) { val j = i - fp + k; if (j in 0 until n) s += window[k] * oenv[j] }
            local[i] = s
        }
        val backlink = IntArray(n) { -1 }
        val cum = DoubleArray(n)
        cum[0] = local[0]
        var lmax = Double.NEGATIVE_INFINITY; for (v in local) if (v > lmax) lmax = v
        val thresh = 0.01 * lmax
        var first = true
        val loOff = Math.rint(fpb / 2).toInt(); val hiOff = (2 * fpb).toInt()
        val lfpb = ln(fpb)
        for (i in 0 until n) {
            var best = Double.NEGATIVE_INFINITY; var locBest = -1
            var loc = i - loOff
            while (loc > i - hiOff - 1) {
                if (loc < 0) break
                val d = ln((i - loc).toDouble()) - lfpb
                val sc = cum[loc] - tightness * d * d
                if (sc > best) { best = sc; locBest = loc }
                loc--
            }
            cum[i] = if (locBest >= 0) local[i] + best else local[i]
            if (first && local[i] < thresh) backlink[i] = -1 else { backlink[i] = locBest; first = false }
        }
        val mx = localMax(cum)
        val maxima = ArrayList<Double>(); for (i in 0 until n) if (mx[i]) maxima.add(cum[i])
        val thr = if (maxima.isNotEmpty()) 0.5 * median(maxima.toDoubleArray()) else 0.0
        var tail = n - 1
        var k = n - 1
        while (k >= 0) { if (mx[k] && cum[k] >= thr) { tail = k; break }; k-- }
        val isBeat = BooleanArray(n)
        k = tail
        while (k >= 0) { isBeat[k] = true; k = backlink[k] }
        // trim spurious beats at both ends
        val w = hanningSym(5)
        val lb = ArrayList<Double>(); for (i in 0 until n) if (isBeat[i]) lb.add(local[i])
        val full = DoubleArray(lb.size + w.size - 1)
        for (i in lb.indices) for (j in w.indices) full[i + j] += lb[i] * w[j]
        val from = w.size / 2; val to = min(full.size, n + w.size / 2)
        var sq = 0.0; var cnt = 0
        for (i in from until to) { sq += full[i] * full[i]; cnt++ }
        val thrT = if (trim && cnt > 0) 0.5 * sqrt(sq / cnt) else 0.0
        val out = isBeat.copyOf()
        k = 0; while (k < n && local[k] <= thrT) { out[k] = false; k++ }
        k = n - 1; while (k >= 0 && local[k] <= thrT) { out[k] = false; k-- }
        return Beats(bpm, (0 until n).filter { out[it] }.toIntArray())
    }
}
