package io.github.markalexanderhub.homesense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pedestrian dead reckoning plus Wi-Fi signal. Steps come from the accelerometer, direction from
 * the rotation sensor, and each step is stamped with the signal strength of the network you're on.
 * Positions are in metres; y grows downwards so the map can be drawn as-is.
 */
class WalkTracker(context: Context, private val onChange: () -> Unit) : SensorEventListener {
    data class Pt(val x: Float, val y: Float, val rssi: Int)

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())

    val points = ArrayList<Pt>()
    var router: Pt? = null
    var stride = 0.70f
    var snapCorners = true
    var walking = false; private set
    var steps = 0; private set
    var rssi = NO_SIGNAL; private set
    var ssid: String? = null; private set
    var x = 0f; private set
    var y = 0f; private set
    /** How this phone knows which way you're facing; MANUAL means Left/Right buttons at corners. */
    val turnSource: String

    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var gyroAt = 0L
    private var manualTurn = 0.0
    private val rot = FloatArray(9)
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var haveGeomag = false
    private var heading = 0.0
    private var headingRef: Double? = null

    // Step detector: a low-passed |acceleration| that has to rise above HIGH and drop below LOW.
    private var lp = SensorManager.GRAVITY_EARTH
    private var armed = true
    private var lastStepAt = 0L
    private val samples = ArrayList<Int>()

    init {
        turnSource = when {
            rotation != null -> "rotation sensor"
            gyro != null -> "gyroscope"
            magnet != null -> "compass"
            else -> MANUAL
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            readWifi()
            main.postDelayed(this, 300)
        }
    }

    fun listen() {
        accel?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        when {
            rotation != null -> sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
            gyro != null -> sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            magnet != null -> sensors.registerListener(this, magnet, SensorManager.SENSOR_DELAY_GAME)
        }
        main.post(poll)
    }

    fun stopListening() {
        sensors.unregisterListener(this)
        main.removeCallbacks(poll)
    }

    fun start() {
        if (points.isEmpty()) {
            x = 0f; y = 0f; steps = 0
            headingRef = heading
            points += Pt(0f, 0f, currentRssi())
        }
        walking = true
        onChange()
    }

    fun stop() {
        walking = false
        onChange()
    }

    fun clear() {
        points.clear(); router = null; steps = 0; x = 0f; y = 0f; walking = false; manualTurn = 0.0
        onChange()
    }

    /** A made-up two-bedroom walk, for screenshots and for trying the map without walking. */
    fun loadDemo() {
        clear()
        val legs = listOf(0.0 to 8, PI / 2 to 9, PI to 5, -PI / 2 to 4, PI to 4, PI / 2 to 4, 0.0 to 9)
        var px = 0f; var py = 0f
        router = Pt(0f, 0f, -38)
        points += Pt(0f, 0f, -38)
        var i = 0
        for ((dir, n) in legs) repeat(n) {
            px += (stride * sin(dir)).toFloat(); py -= (stride * cos(dir)).toFloat()
            val d = sqrt(px * px + py * py)
            val walls = if (px > 4f) 14 else if (py > 1.5f) 6 else 0
            val r = (-38 - 22 * kotlin.math.log10(d + 1.0) - walls - (i % 3)).roundToInt()
            points += Pt(px, py, r); i++
        }
        x = px; y = py; steps = points.size - 1
        onChange()
    }

    fun markRouter() {
        router = Pt(x, y, currentRssi())
        onChange()
    }

    @Suppress("DEPRECATION") // still the only way to read the connected network's RSSI on every version
    private fun readWifi() {
        val info = wifi.connectionInfo
        val r = info?.rssi ?: NO_SIGNAL
        val connected = info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED && r > -127 && r < 0
        rssi = if (connected) r else NO_SIGNAL
        if (connected) samples += r
        ssid = info?.ssid?.trim('"')?.takeIf { connected && it.isNotBlank() && it != "<unknown ssid>" }
        if (!walking) onChange()
    }

    private fun currentRssi(): Int =
        if (samples.isNotEmpty()) samples.average().roundToInt().also { samples.clear() } else rssi

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                updateHeading()
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Gyroscope only: add up turning around the vertical axis (gravity tells which axis that is).
                if (gyroAt != 0L) {
                    val dt = (e.timestamp - gyroAt) / 1e9
                    val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
                    if (g > 1f && dt < 0.5) {
                        val yaw = (e.values[0] * gravity[0] + e.values[1] * gravity[1] + e.values[2] * gravity[2]) / g
                        heading -= yaw * dt
                    }
                }
                gyroAt = e.timestamp
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, geomag, 0, 3); haveGeomag = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(e.values, 0, gravity, 0, 3)
                if (rotation == null && gyro == null && haveGeomag && SensorManager.getRotationMatrix(rot, null, gravity, geomag)) updateHeading()
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                lp = lp * 0.75f + m * 0.25f
                val now = SystemClock.elapsedRealtime()
                if (armed && lp > HIGH && now - lastStepAt > 300) {
                    armed = false; lastStepAt = now
                    if (walking) step()
                } else if (!armed && lp < LOW) armed = true
            }
        }
    }

    // Heading of "forward" whether the phone is held flat or upright: the phone's top edge plus the
    // direction the back camera faces. One of them is always horizontal enough to give a direction.
    private fun updateHeading() {
        val fx = rot[1] - rot[2]
        val fy = rot[4] - rot[5]
        if (abs(fx) + abs(fy) > 0.2f) heading = atan2(fx.toDouble(), fy.toDouble())
    }

    /** Left/Right buttons, for phones that can't sense turning: one tap is one square corner. */
    fun turn(right: Boolean) {
        manualTurn += if (right) PI / 2 else -PI / 2
        onChange()
    }

    private fun step() {
        steps++
        var rel = if (turnSource == MANUAL) manualTurn else heading - (headingRef ?: heading)
        if (snapCorners) {
            // Rooms are mostly square: a direction within 20 degrees of a wall's is taken as the wall's.
            val q = (rel / (PI / 2)).roundToInt() * (PI / 2)
            if (abs(norm(rel - q)) < SNAP) rel = q
        }
        x += (stride * sin(rel)).toFloat()
        y -= (stride * cos(rel)).toFloat()
        points += Pt(x, y, currentRssi())
        onChange()
    }

    private fun norm(a: Double): Double {
        var v = a
        while (v > PI) v -= 2 * PI
        while (v < -PI) v += 2 * PI
        return v
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val NO_SIGNAL = -127
        const val MANUAL = "manual"
        private const val HIGH = 10.9f
        private const val LOW = 9.5f
        private const val SNAP = 20 * PI / 180
    }
}
