// SPDX-License-Identifier: GPL-3.0-or-later
package nz.co.chordalicious.app.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import org.junit.Test

/** Runs the engine on raw float32 mono 22050 Hz files (ENGINE_AUDIO=dir:name,name) and writes kt_<name>.json next to them. */
class EngineTest {
    @Test fun fft_roundtrip() {
        val f = Fft.of(1024)
        val re = DoubleArray(1024) { Math.sin(it * 0.1) + 0.3 * Math.cos(it * 0.37) }
        val im = DoubleArray(1024)
        val orig = re.copyOf()
        f.transform(re, im); f.transform(re, im, inverse = true)
        for (i in 0 until 1024) check(Math.abs(re[i] - orig[i]) < 1e-9) { "roundtrip $i" }
        // two real signals at once
        val a = DoubleArray(1024) { Math.sin(it * 0.2) }; val b = DoubleArray(1024) { Math.cos(it * 0.05) * 0.5 }
        val ar = DoubleArray(513); val ai = DoubleArray(513); val br = DoubleArray(513); val bi = DoubleArray(513)
        f.twoReal(a.copyOf(), b.copyOf(), ar, ai, br, bi)
        val x = a.copyOf(); val xi = DoubleArray(1024); f.transform(x, xi)
        val y = b.copyOf(); val yi = DoubleArray(1024); f.transform(y, yi)
        for (k in 0..512) check(Math.abs(ar[k] - x[k]) < 1e-8 && Math.abs(ai[k] - xi[k]) < 1e-8 && Math.abs(br[k] - y[k]) < 1e-8 && Math.abs(bi[k] - yi[k]) < 1e-8) { "twoReal $k" }
    }

    @Test fun songs() {
        val spec = System.getenv("ENGINE_AUDIO") ?: return
        val dir = spec.substringBefore(":"); val names = spec.substringAfter(":").split(",").filter { it.isNotBlank() }
        for (n in names) {
            val bytes = File("$dir/$n.f32").readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val y = FloatArray(fb.remaining()); fb.get(y)
            val t0 = System.nanoTime()
            val res = Engine().transcribe(y, 22050, true)
            val sec = (System.nanoTime() - t0) / 1e9
            File("$dir/kt_$n.json").writeText(res.toJson())
            println("KT $n: ${"%.1f".format(sec)} s, bars ${res.measures.size}, bpm ${res.bpm}, ${res.key}")
        }
    }

    /** 22050 -> 48000 -> 22050 (and via 44100): the resampler must not change what the engine hears. */
    @Test fun resample_roundtrip() {
        val spec = System.getenv("ENGINE_AUDIO") ?: return
        val dir = spec.substringBefore(":"); val n = spec.substringAfter(":").split(",").first()
        val bytes = File("$dir/$n.f32").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val y = FloatArray(fb.remaining()); fb.get(y)
        for (mid in intArrayOf(48000, 44100)) {
            val t0 = System.nanoTime()
            val up = AudioDecoder.resample(y, y.size, 22050, mid)
            val back = AudioDecoder.resample(up, up.size, mid, 22050)
            val sec = (System.nanoTime() - t0) / 1e9
            var err = 0.0; var ref = 0.0
            for (i in 2000 until min(back.size, y.size) - 2000) { err += (back[i] - y[i]).toDouble().let { it * it }; ref += y[i].toDouble() * y[i] }
            println("RS $mid: roundtrip ${"%.2f".format(sec)} s, length ${y.size} -> ${up.size} -> ${back.size}, error ${"%.1f".format(10 * Math.log10(err / ref))} dB")
            val res = Engine().transcribe(back, 22050, true)
            File("$dir/kt_${n}_rs$mid.json").writeText(res.toJson())
            println("RS $mid: bars ${res.measures.size}, bpm ${res.bpm}, ${res.key}")
        }
    }
}
