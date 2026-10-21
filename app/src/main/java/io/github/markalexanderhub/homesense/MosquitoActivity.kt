package io.github.markalexanderhub.homesense

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A mosquito's wings beat 300 to 1000 times a second, which makes a thin, steady tone with a clear
 * second harmonic. Every ~50 ms this looks for that shape in the microphone signal, compared with
 * the room's own background noise. Audio is analysed in memory and thrown away; nothing is recorded.
 */
class MosquitoActivity : AppCompatActivity() {
    private lateinit var radar: Radar
    private lateinit var verdict: TextView
    private lateinit var detail: TextView
    @Volatile private var running = false
    private var worker: Thread? = null

    // Shared with the UI thread
    @Volatile private var score = 0f          // smoothed dB above background at the buzz
    @Volatile private var freq = 0f
    @Volatile private var calibrating = true
    private val spectrum = FloatArray(BANDS)
    private var lastTick = 0L
    private val trend = ArrayDeque<Float>()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else {
            verdict.text = "Needs the microphone"
            detail.text = "Mosquito Radar listens for the buzz. Nothing is recorded or saved."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        radar = Radar(this)
        screen {
            heading("Mosquito Radar")
            body("Switch off the fan and AC. Hold the phone out with the bottom edge (the microphone) leading, and sweep slowly round the room. It pulses faster as the buzz gets louder.", 14f, dim = true)
            fill(radar)
            verdict = heading("", 22f)
            detail = body("", 14f, dim = true)
            row { button("Re-learn the room's noise", outlined = true) { relearn = true } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        super.onPause()
        running = false
        worker?.join(500); worker = null
    }

    @Volatile private var relearn = false

    private fun startListening() {
        if (running) return
        running = true
        worker = Thread({ listen() }, "mosquito").also { it.start() }
    }

    private fun source(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The raw microphone, without noise suppression that would filter out a steady tone.
        val raw = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    @SuppressLint("MissingPermission") // checked before startListening()
    private fun listen() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(source(), RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, N * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread { verdict.text = "Microphone busy"; detail.text = "Another app is using the microphone." }
            running = false; return
        }
        val fft = Fft(N)
        val ring = ShortArray(N)
        val hop = ShortArray(HOP)
        val re = DoubleArray(N); val im = DoubleArray(N)
        val power = DoubleArray(N / 2)
        val noise = DoubleArray(N / 2)
        var learned = 0
        val recent = ArrayDeque<Int>()
        val learnFrames = RATE * 3 / HOP
        val binHz = RATE.toDouble() / N
        val lo = (LOW_HZ / binHz).toInt(); val hi = (HIGH_HZ / binHz).toInt()
        rec.startRecording()
        try {
            while (running) {
                var got = 0
                while (got < HOP && running) {
                    val r = rec.read(hop, got, HOP - got); if (r <= 0) break; got += r
                }
                System.arraycopy(ring, HOP, ring, 0, N - HOP)
                System.arraycopy(hop, 0, ring, N - HOP, HOP)
                for (i in 0 until N) { re[i] = ring[i] * fft.window[i]; im[i] = 0.0 }
                fft.transform(re, im)
                for (k in 0 until N / 2) power[k] = re[k] * re[k] + im[k] * im[k] + 1e-3

                if (relearn) { relearn = false; learned = 0; recent.clear() }
                if (learned < learnFrames) {
                    for (k in noise.indices) noise[k] = if (learned == 0) power[k] else noise[k] + (power[k] - noise[k]) / (learned + 1)
                    learned++
                    calibrating = true
                    publish(0f, 0f, power, noise, binHz)
                    continue
                }
                calibrating = false

                // Loudest bin above the background, inside the wingbeat band.
                var best = lo; var bestSnr = 0.0
                for (k in lo..hi) { val s = power[k] / noise[k]; if (s > bestSnr) { bestSnr = s; best = k } }
                // A buzz is narrow: much louder than the bins around it, not a wide rumble.
                val around = (max(1, best - 10)..min(N / 2 - 1, best + 10)).filter { kotlin.math.abs(it - best) > 2 }.map { power[it] }.sorted()
                val tonal = power[best] / around[around.size / 2] > 8.0
                // ...and has a second harmonic.
                val h2 = (2 * best - 2..2 * best + 2).filter { it < N / 2 }.maxOfOrNull { power[it] / noise[it] } ?: 0.0
                // ...and is the lowest note: voices and music have their root below the band, so their
                // overtones land in it with a strong partner at half or a third of the frequency.
                val sub = listOf(best / 2, best / 3).flatMap { it - 1..it + 1 }.filter { it > 0 }.maxOf { power[it] / noise[it] }
                val candidate = tonal && bestSnr > 4.0 && h2 > 2.0 && sub < bestSnr * 0.25
                // ...and holds steady: a wingbeat stays within a few hertz for half a second, speech wanders.
                recent.addLast(if (candidate) best else -1)
                while (recent.size > 8) recent.removeFirst()
                val steady = candidate && recent.count { it >= 0 && kotlin.math.abs(it - best) <= 3 } >= 5
                val db = if (steady) (10 * log10(bestSnr)).toFloat() else 0f
                publish(db, (best * binHz).toFloat(), power, noise, binHz)

                // Keep learning the room slowly while nothing is buzzing, so a moving phone doesn't drift.
                if (db == 0f) for (k in noise.indices) noise[k] += (power[k] - noise[k]) * 0.01
            }
        } finally {
            rec.stop(); rec.release()
        }
    }

    private fun publish(db: Float, hz: Float, power: DoubleArray, noise: DoubleArray, binHz: Double) {
        score = if (db > score) score * 0.5f + db * 0.5f else score * 0.85f + db * 0.15f
        if (db > 0f) freq = hz
        // Spectrum strip for the screen: 200-1200 Hz in BANDS bars, dB above background.
        for (b in 0 until BANDS) {
            val k0 = ((200 + b * 1000.0 / BANDS) / binHz).toInt()
            val k1 = ((200 + (b + 1) * 1000.0 / BANDS) / binHz).toInt()
            var mx = 0.0
            for (k in k0 until max(k0 + 1, k1)) mx = max(mx, power[k] / noise[k])
            spectrum[b] = (10 * log10(mx)).toFloat().coerceIn(0f, 30f)
        }
        runOnUiThread { update() }
    }

    private fun update() {
        if (calibrating) {
            verdict.text = "Listening to the room..."
            detail.text = "Stay still and quiet for three seconds."
        } else {
            trend.addLast(score); while (trend.size > 30) trend.removeFirst()
            val was = trend.first()
            verdict.text = when {
                score < 3f -> "Nothing buzzing nearby"
                score > was + 2f -> "Warmer..."
                score < was - 2f -> "Colder"
                score > 15f -> "Very close!"
                else -> "Buzz heard"
            }
            detail.text = if (score >= 3f) "Buzz at %.0f Hz, %.0f dB over the room".format(freq, score) else "Sweep slowly. Fans, AC and the fridge hide the buzz."
            val now = SystemClock.elapsedRealtime()
            val gap = (1200 - score * 60).toLong().coerceIn(120, 1200)
            if (score >= 3f && now - lastTick > gap) { lastTick = now; tick() }
        }
        radar.invalidate()
    }

    private fun tick() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        radar.ping = SystemClock.elapsedRealtime()
    }

    inner class Radar(ctx: Context) : View(ctx) {
        var ping = 0L
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accent = ctx.col(R.color.accent)

        override fun onDraw(c: Canvas) {
            val specH = height * 0.22f
            val cx = width / 2f; val cy = (height - specH) / 2f
            val r = min(width.toFloat(), height - specH) * 0.45f
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(1).toFloat()
            p.color = context.col(R.color.dim) and 0x55FFFFFF
            for (i in 1..4) c.drawCircle(cx, cy, r * i / 4, p)
            // Expanding ring after each tick
            val age = (SystemClock.elapsedRealtime() - ping) / 700f
            if (age < 1f) {
                p.color = accent; p.alpha = ((1 - age) * 255).toInt(); p.strokeWidth = context.dp(4).toFloat()
                c.drawCircle(cx, cy, r * age, p)
                p.alpha = 255
                postInvalidateOnAnimation()
            }
            // Core grows with the score
            p.style = Paint.Style.FILL; p.color = accent
            val core = r * (0.08f + 0.5f * min(score / 25f, 1f))
            c.drawCircle(cx, cy, core + (if (score > 3f) sin(SystemClock.elapsedRealtime() / 60.0).toFloat() * 3f else 0f), p)
            if (score > 3f) postInvalidateOnAnimation()

            // Spectrum strip
            val top = height - specH + context.dp(10); val bottom = height.toFloat()
            val w = width.toFloat() / BANDS
            for (b in 0 until BANDS) {
                val h = (bottom - top) * spectrum[b] / 30f
                p.color = if (spectrum[b] > 6f) accent else context.col(R.color.dim) and 0x88FFFFFF.toInt()
                c.drawRect(b * w + 1, bottom - h, (b + 1) * w - 1, bottom, p)
            }
            p.color = context.col(R.color.dim); p.textSize = context.dp(11).toFloat()
            p.textAlign = Paint.Align.LEFT; c.drawText("200 Hz", 0f, top - 2, p)
            p.textAlign = Paint.Align.RIGHT; c.drawText("1200 Hz", width.toFloat(), top - 2, p)
        }
    }

    companion object {
        private const val RATE = 44100
        private const val N = 4096
        private const val HOP = 2048
        private const val LOW_HZ = 300.0
        private const val HIGH_HZ = 1000.0
        private const val BANDS = 50
    }
}
