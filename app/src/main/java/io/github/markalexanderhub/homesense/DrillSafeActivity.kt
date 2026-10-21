package io.github.markalexanderhub.homesense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Steel bars and nails bend the earth's magnetic field; a wire carrying current makes its own field
 * that flickers at 50 Hz. The compass chip sees both. Plain copper with nothing switched on, and
 * plastic pipes, are invisible to it, and the screen says so.
 */
class DrillSafeActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private var mag: Sensor? = null
    private lateinit var gauge: Gauge
    private lateinit var verdict: TextView
    private lateinit var detail: TextView
    private lateinit var soundBtn: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var sound = true

    // Field state
    private var baseline = Float.NaN
    private val calib = ArrayList<Float>()
    private var calibUntil = 0L
    private var avg = Float.NaN
    private var dev = 0f
    private var flick = 0f
    private var flickNoise = Float.NaN
    private val flickWin = ArrayDeque<Float>()
    private var samples = 0
    private var rateFrom = 0L
    private var rate = 0f
    private var lastLevel = 0
    val history = ArrayDeque<Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mag = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gauge = Gauge(this)
        screen {
            heading("Drill Safe")
            body("Hold the phone flat against the wall, screen facing you, and slide it slowly. The compass sits near the top of most phones, so lead with the top edge.", 14f, dim = true)
            fill(gauge)
            verdict = heading("", 22f)
            detail = body("", 14f, dim = true)
            row {
                button("Recalibrate", outlined = true) { calibrate() }
                soundBtn = button("Sound on", outlined = true) { sound = !sound; soundBtn.text = if (sound) "Sound on" else "Sound off" }
            }
            body("It can't see plastic pipes, or copper wire with nothing switched on. Switch on the lights and sockets on that wall first, and when in doubt, don't drill.", 12f, dim = true)
        }
        if (mag == null) {
            verdict.text = "This phone has no compass"
            detail.text = "Drill Safe needs a magnetic sensor (the one compass apps use), and this phone was built without one. Wi-Fi Walk and Mosquito Radar still work."
            gauge.visibility = View.INVISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (mag == null) return
        sensors.registerListener(this, mag, SensorManager.SENSOR_DELAY_FASTEST)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        calibrate()
        main.post(clicker)
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        main.removeCallbacksAndMessages(null)
        tone?.release(); tone = null
    }

    private fun calibrate() {
        if (mag == null) return
        calib.clear(); baseline = Float.NaN; flickNoise = Float.NaN
        calibUntil = SystemClock.elapsedRealtime() + 2000
        verdict.text = "Calibrating..."
        detail.text = "Hold the phone in the air, away from the wall and anything metal, for two seconds."
    }

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val now = SystemClock.elapsedRealtime()
        if (rateFrom == 0L) rateFrom = now
        samples++
        if (now - rateFrom >= 2000) { rate = samples * 1000f / (now - rateFrom); samples = 0; rateFrom = now }

        // Flicker: how much the field wobbles around its own short average. Mains current makes it shake.
        avg = if (avg.isNaN()) m else avg * 0.9f + m * 0.1f
        flickWin.addLast((m - avg) * (m - avg))
        while (flickWin.size > 40) flickWin.removeFirst()
        flick = sqrt(flickWin.average().toFloat())

        if (now < calibUntil) { calib += m; return }
        if (baseline.isNaN()) {
            if (calib.isEmpty()) return
            baseline = calib.sorted()[calib.size / 2]
            flickNoise = max(flick, 0.05f)
        }
        dev = abs(m - baseline)
        history.addLast(dev)
        while (history.size > 400) history.removeFirst()
        show()
    }

    private val liveCapable get() = rate >= 80f
    private val live get() = liveCapable && !flickNoise.isNaN() && flick > flickNoise * 4f && flick > 0.3f

    private fun level(): Int = when {
        live || dev >= 12f -> 2
        dev >= 4f -> 1
        else -> 0
    }

    private fun show() {
        val lv = level()
        verdict.text = when {
            live -> "Live wire likely. Don't drill here."
            lv == 2 -> "Metal here. Don't drill."
            lv == 1 -> "Something metal nearby. Go slow."
            else -> "Clear"
        }
        val liveNote = if (liveCapable) "live-wire check on" else "this phone's compass is too slow to spot live wires (${rate.toInt()} Hz)"
        detail.text = "%.1f µT off normal · %s".format(dev, liveNote)
        if (lv == 2 && lastLevel < 2) vibrate()
        lastLevel = lv
        gauge.level = lv
        gauge.invalidate()
    }

    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Geiger-counter clicks: faster the stronger the disturbance.
    private val clicker = object : Runnable {
        override fun run() {
            var next = 250L
            if (sound && !baseline.isNaN() && dev > 2.5f) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
                next = (900 - dev * 45).toLong().coerceIn(60, 900)
            }
            main.postDelayed(this, next)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Big coloured disc for the verdict and a scrolling trace of the last few seconds underneath. */
    inner class Gauge(ctx: Context) : View(ctx) {
        var level = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(0xFF2E9E5B.toInt(), 0xFFE8B931.toInt(), 0xFFC8382B.toInt())

        override fun onDraw(c: Canvas) {
            val discH = height * 0.62f
            val r = min(width, discH.toInt()) * 0.42f
            val cx = width / 2f; val cy = discH / 2f
            p.style = Paint.Style.FILL
            p.color = colors[level] and 0x33FFFFFF
            c.drawCircle(cx, cy, r, p)
            p.color = colors[level]
            c.drawCircle(cx, cy, r * (0.55f + 0.45f * min(dev / 20f, 1f)), p)
            p.color = context.col(R.color.paper)
            p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.42f
            p.isFakeBoldText = true
            c.drawText(if (this@DrillSafeActivity.baseline.isNaN()) "..." else "%.0f".format(dev), cx, cy + p.textSize * 0.35f, p)
            p.isFakeBoldText = false
            p.textSize = r * 0.14f
            c.drawText("µT", cx, cy + r * 0.45f, p)

            // Trace: the last ~4 s, with the 4 and 12 µT lines.
            val top = discH + context.dp(8); val bottom = height - context.dp(4).toFloat()
            val span = bottom - top
            val full = 25f
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(1).toFloat()
            p.color = context.col(R.color.dim) and 0x66FFFFFF
            for (t in listOf(4f, 12f)) { val y = bottom - span * t / full; c.drawLine(0f, y, width.toFloat(), y, p) }
            if (history.size > 1) {
                val path = Path()
                val step = width.toFloat() / 400
                val start = 400 - history.size
                history.forEachIndexed { i, v ->
                    val x = (start + i) * step; val y = bottom - span * min(v, full) / full
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                p.color = context.col(R.color.ink); p.strokeWidth = context.dp(2).toFloat()
                c.drawPath(path, p)
            }
        }
    }
}
