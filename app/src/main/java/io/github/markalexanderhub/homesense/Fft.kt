package io.github.markalexanderhub.homesense

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// In-place radix-2 FFT. n must be a power of two. Small and fast enough for 4096 points at 10 Hz.
class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val c = cosT[k * step]; val s = sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
