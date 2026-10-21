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
            list.card({ howTo() }) {
                heading("How to use", 21f)
                body("Step by step for each tool, and what the results mean.", 14f, dim = true)
            }
            list.body("Nothing leaves the phone. No account, no ads, no internet permission.", 13f, dim = true)
            addView(ScrollView(context).apply { addView(list) })
        }
    }

    private fun howTo() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("How to use")
            .setMessage(
                "WI-FI WALK\n" +
                "1. Stand next to the router, open Wi-Fi Walk, tap \"I'm at the router\".\n" +
                "2. Tap Start walk. Hold the phone in front of you like you're reading it.\n" +
                "3. Walk slowly through every room and back. Turn corners squarely. On phones that can't sense turning, tap Turned left / Turned right at each corner.\n" +
                "4. Tap Done. Green is good, red is weak. The cross is the weakest spot, the arrow says where to move the router, the star is where an extender should go.\n" +
                "5. Share map sends the picture and advice to whoever moves the router.\n\n" +
                "DRILL SAFE\n" +
                "1. Hold the phone in the air for two seconds while it calibrates.\n" +
                "2. Put it flat on the wall, screen towards you, and slide slowly. Lead with the top edge, where the compass is.\n" +
                "3. Green: clear. Amber: something metal nearby. Red: metal here, don't drill. It clicks faster as you get closer.\n" +
                "4. It finds metal, not electricity. Wires usually run straight up or down from switches and sockets, so never drill in line with them. Use a tester pen for live wires.\n\n" +
                "MOSQUITO RADAR\n" +
                "1. Switch off the fan and AC. Stay quiet for three seconds while it learns the room.\n" +
                "2. Hold the phone out with the bottom edge (the microphone) leading and sweep slowly round the room.\n" +
                "3. It says Warmer or Colder and pulses faster as the buzz gets louder. Works best within about a metre.\n" +
                "4. Re-learn the room's noise if you move to another room."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun android.widget.LinearLayout.tool(name: String, what: String, cls: Class<*>) {
        card({ startActivity(Intent(this@MainActivity, cls)) }) {
            heading(name, 21f)
            body(what, 14f, dim = true)
        }
    }
}
