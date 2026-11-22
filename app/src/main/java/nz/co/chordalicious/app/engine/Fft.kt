// SPDX-License-Identifier: GPL-3.0-or-later
package nz.co.chordalicious.app.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 complex FFT (size must be a power of two). Twiddles and the bit-reversal table are built once per size. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In-place transform. Forward uses e^(-i...), inverse uses e^(+i...) and divides by n (numpy's convention). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sgn = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sgn * sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val s = 1.0 / n
            for (i in 0 until n) { re[i] *= s; im[i] *= s }
        }
    }

    /**
     * Forward FFT of TWO real signals at once (a in the real part, b in the imaginary part of one complex transform).
     * Fills outAre/outAim with bins 0..n/2 of a, outBre/outBim with bins 0..n/2 of b.
     * work arrays re/im (size n) hold the input and are overwritten.
     */
    fun twoReal(re: DoubleArray, im: DoubleArray, aRe: DoubleArray, aIm: DoubleArray, bRe: DoubleArray, bIm: DoubleArray) {
        transform(re, im)
        val h = n / 2
        for (k in 0..h) {
            val k2 = if (k == 0) 0 else n - k
            val zr = re[k]; val zi = im[k]; val cr = re[k2]; val ci = -im[k2]      // Z[k] and conj(Z[n-k])
            aRe[k] = 0.5 * (zr + cr); aIm[k] = 0.5 * (zi + ci)
            // (Z[k] - conj(Z[n-k])) / (2i)
            val dr = zr - cr; val di = zi - ci
            bRe[k] = 0.5 * di; bIm[k] = -0.5 * dr
        }
    }

    companion object {
        private val cache = HashMap<Int, Fft>()
        @Synchronized fun of(n: Int): Fft = cache.getOrPut(n) { Fft(n) }
    }
}
