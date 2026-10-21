package io.github.markalexanderhub.homesense

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen {
            heading("Home Sense", 30f)
            body("Three tools that use the sensors already in your phone.", dim = true)
            val list = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            list.setPadding(0, dp(12), 0, 0)
            list.tool("Wi-Fi Walk", "Walk around your home. It draws the floor plan as you go, colours it by Wi-Fi signal, and tells you where the router or an extender should go.", WifiWalkActivity::class.java)
            val compass = (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager)
                .getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD) != null
            list.tool("Drill Safe", "Slide the phone across a wall before you drill. It warns about steel bars, nails, screws and metal pipes." +
                if (compass) "" else " (Needs a compass, and this phone has none.)", DrillSafeActivity::class.java)
            list.tool("Mosquito Radar", "Switch the fan off, walk slowly, and it ticks faster as you get closer to the whine.", MosquitoActivity::class.java)
            list.body("Nothing leaves the phone. No account, no ads, no internet permission.", 13f, dim = true)
            addView(ScrollView(context).apply { addView(list) })
        }
    }

    private fun android.widget.LinearLayout.tool(name: String, what: String, cls: Class<*>) {
        card({ startActivity(Intent(this@MainActivity, cls)) }) {
            heading(name, 21f)
            body(what, 14f, dim = true)
        }
    }
}
