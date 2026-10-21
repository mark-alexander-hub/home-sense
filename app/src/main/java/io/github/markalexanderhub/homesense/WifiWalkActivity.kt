package io.github.markalexanderhub.homesense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File

class WifiWalkActivity : AppCompatActivity() {
    private lateinit var tracker: WalkTracker
    private lateinit var map: WalkMapView
    private lateinit var status: TextView
    private lateinit var tip: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var strideLabel: TextView

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tracker = WalkTracker(this) { refresh() }
        map = WalkMapView(this, tracker)
        screen {
            heading("Wi-Fi Walk")
            status = mono("", 14f)
            tip = body(TIP, 14f, dim = true).apply {
                maxLines = 7
                movementMethod = android.text.method.ScrollingMovementMethod()
            }
            fill(map)
            if (tracker.turnSource == WalkTracker.MANUAL) row {
                button("Turned left", outlined = true) { tracker.turn(false) }
                button("Turned right", outlined = true) { tracker.turn(true) }
            }
            row {
                startBtn = button("Start walk") { toggle() }
                button("I'm at the router", outlined = true) { tracker.markRouter() }
            }
            row {
                button("Stride -", outlined = true) { stride(-0.05f) }
                strideLabel = body("", 14f).apply { setPadding(dp(4), 0, dp(10), 0) }
                button("Stride +", outlined = true) { stride(0.05f) }
            }
            row {
                button("Share map", outlined = true) { share() }
                button("Start over", outlined = true) { tracker.clear(); map.advice = null }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        if (tracker.turnSource == WalkTracker.MANUAL) tip.text = "$TIP\n\nThis phone can't sense turning, so tap Turned left or Turned right at each corner."
        stride(0f)
        if (intent.getBooleanExtra("demo", false)) {
            tracker.loadDemo()
            map.advice = advise(tracker.points, tracker.router).also { tip.text = it.text }
        }
        refresh()
    }

    override fun onResume() { super.onResume(); tracker.listen() }
    override fun onPause() { super.onPause(); tracker.stopListening() }

    private fun toggle() {
        if (tracker.walking) {
            tracker.stop()
            val a = advise(tracker.points, tracker.router)
            map.advice = a
            tip.text = a.text
        } else {
            map.advice = null
            tip.text = "Walking. Go slowly through each room, turn corners squarely, and come back to where you started."
            tracker.start()
        }
    }

    private fun stride(d: Float) {
        tracker.stride = (tracker.stride + d).coerceIn(0.4f, 1.0f)
        strideLabel.text = "%.2f m".format(tracker.stride)
    }

    private fun refresh() {
        val net = tracker.ssid ?: if (tracker.rssi == WalkTracker.NO_SIGNAL) "No Wi-Fi" else "Wi-Fi"
        val sig = if (tracker.rssi == WalkTracker.NO_SIGNAL) "not connected" else "${tracker.rssi} dBm, ${Signal.words(tracker.rssi)}"
        val walked = "%.1f".format(tracker.steps * tracker.stride)
        status.text = "$net: $sig\n${tracker.steps} steps, about $walked m"
        startBtn.text = if (tracker.walking) "Done" else if (tracker.points.isEmpty()) "Start walk" else "Keep walking"
        map.invalidate()
    }

    private fun share() {
        if (map.width == 0 || tracker.points.size < 2) return
        val bmp = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(col(R.color.paper))
        map.draw(c)
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "wifi-walk.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, map.advice?.text ?: "My home's Wi-Fi, mapped with Home Sense.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share the map"))
    }

    companion object {
        const val TIP = "Hold the phone in front of you like you're reading it. If you can, start at the router and tap \"I'm at the router\". Then tap Start and walk slowly through every room."
    }
}
