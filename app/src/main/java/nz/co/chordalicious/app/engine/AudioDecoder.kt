// SPDX-License-Identifier: GPL-3.0-or-later
package nz.co.chordalicious.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class DecodeError(msg: String) : Exception(msg)

/** Decodes any audio file Android can play to mono float samples at 22050 Hz (what the engine expects). */
object AudioDecoder {
    const val TARGET = 22050

    /** maxSeconds: refuse longer songs (the caller then uses the server). */
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int, isCancelled: () -> Boolean): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw DecodeError("No audio track")
            if (fmt.containsKey(MediaFormat.KEY_DURATION) && fmt.getLong(MediaFormat.KEY_DURATION) > maxSeconds * 1_000_000L) throw DecodeError("Too long for on-device analysis")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floatPcm = false
                var out = FloatArray(max(1, (rate * 60.0).toInt()))
                var n = 0
                val info = MediaCodec.BufferInfo()
                var inputDone = false; var outputDone = false
                var idle = 0
                while (!outputDone) {
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("Cancelled")
                    if (!inputDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            val sz = ex.readSampleData(buf, 0)
                            if (sz < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(ib, 0, sz, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4   // ENCODING_PCM_FLOAT
                        }
                        ob >= 0 -> {
                            idle = 0
                            val buf = codec.getOutputBuffer(ob)!!
                            buf.order(ByteOrder.nativeOrder())
                            if (info.size > 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                                if (n + frames > out.size) out = out.copyOf(max(out.size * 2, n + frames))
                                if (n + frames > maxSeconds * rate.toLong() + rate) throw DecodeError("Too long for on-device analysis")
                                if (floatPcm) {
                                    val fb = buf.asFloatBuffer()
                                    for (f in 0 until frames) { var s = 0f; for (c in 0 until channels) s += fb.get(); out[n++] = s / channels }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    for (f in 0 until frames) { var s = 0; for (c in 0 until channels) s += sb.get(); out[n++] = s / (32768f * channels) }
                                }
                            }
                            codec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        else -> { if (inputDone && ++idle > 500) throw DecodeError("Decoder stalled") }
                    }
                }
                if (n < rate) throw DecodeError("Audio is shorter than 1 second")
                return resample(out, n, rate, TARGET)
            } finally { try { codec.stop() } catch (_: Exception) {}; codec.release() }
        } finally { ex.release() }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun bessel0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (k < 40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; k++ }; return s }

    /** Polyphase windowed-sinc (Kaiser) rational resampler, as good as the converter the server used for its own analysis. */
    fun resample(x: FloatArray, n: Int, src: Int, dst: Int): FloatArray {
        if (src == dst) return x.copyOf(n)
        val g = gcd(src, dst)
        val up = dst / g; val down = src / g                    // output sample j sits at input position j * down / up
        val cutoff = 0.97 * min(1.0, dst.toDouble() / src)       // fraction of the input Nyquist
        val zeros = 16                                           // zero crossings each side (at the lower of the two rates)
        val halfW = ceil(zeros / cutoff).toInt()                 // input samples each side
        val taps = 2 * halfW + 1
        val beta = 8.6; val i0b = bessel0(beta)
        // filter per phase: phase p = (j*down) mod up -> fractional input offset p/up
        val table = Array(up) { FloatArray(taps) }
        for (p in 0 until up) {
            val frac = p.toDouble() / up
            var sum = 0.0
            for (t in 0 until taps) {
                val d = (t - halfW) - frac                       // distance (in input samples) from the output position
                val sinc = if (d == 0.0) cutoff else sin(PI * cutoff * d) / (PI * d)
                val r = d / halfW
                val w = if (r > 1 || r < -1) 0.0 else bessel0(beta * Math.sqrt(1 - r * r)) / i0b
                val v = sinc * w
                table[p][t] = v.toFloat(); sum += v
            }
            for (t in 0 until taps) table[p][t] = (table[p][t] / sum).toFloat()     // unity gain at DC
        }
        val outLen = (n.toLong() * up / down).toInt()
        val out = FloatArray(outLen)
        for (j in 0 until outLen) {
            val pos = j.toLong() * down
            val i0 = (pos / up).toInt(); val p = (pos % up).toInt()
            val f = table[p]
            var s = 0f
            val from = i0 - halfW
            for (t in 0 until taps) { val i = from + t; if (i in 0 until n) s += f[t] * x[i] }
            out[j] = s
        }
        return out
    }
}
