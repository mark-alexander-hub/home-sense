package io.github.markalexanderhub.homesense

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.roundToInt

object Signal {
    private val stops = listOf(
        -50 to Color.rgb(46, 158, 91),
        -60 to Color.rgb(141, 191, 58),
        -67 to Color.rgb(232, 185, 49),
        -75 to Color.rgb(224, 122, 46),
        -85 to Color.rgb(200, 56, 43),
    )

    fun color(rssi: Float): Int {
        if (rssi >= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (a, ca) = stops[i - 1]
            val (b, cb) = stops[i]
            if (rssi >= b) {
                val t = (a - rssi) / (a - b)
                return Color.rgb(
                    (Color.red(ca) + (Color.red(cb) - Color.red(ca)) * t).roundToInt(),
                    (Color.green(ca) + (Color.green(cb) - Color.green(ca)) * t).roundToInt(),
                    (Color.blue(ca) + (Color.blue(cb) - Color.blue(ca)) * t).roundToInt(),
                )
            }
        }
        return stops.last().second
    }

    fun words(rssi: Int): String = when {
        rssi == WalkTracker.NO_SIGNAL -> "not connected"
        rssi >= -55 -> "excellent"
        rssi >= -62 -> "good"
        rssi >= -67 -> "fine for video"
        rssi >= -75 -> "weak"
        else -> "nearly dead"
    }
}

/** What to do about the walk, in plain words, plus where to draw the suggestions on the map. */
class Advice(val text: String, val extender: PointF?, val routerTo: PointF?, val worst: PointF? = null)

fun advise(points: List<WalkTracker.Pt>, router: WalkTracker.Pt?): Advice {
    val pts = points.filter { it.rssi != WalkTracker.NO_SIGNAL }
    if (pts.size < 12) return Advice("Walk a bit more: through every room and back, at least 12 steps.", null, null)

    // Smooth over neighbours: one bad reading isn't a dead spot.
    val smooth = pts.indices.map { i ->
        val win = pts.subList(maxOf(0, i - 2), minOf(pts.size, i + 3))
        win.map { it.rssi }.average().toFloat()
    }
    val good = smooth.count { it >= -67 } * 100 / smooth.size
    val worstI = smooth.indices.minBy { smooth[it] }
    val worst = pts[worstI]
    val worstDb = smooth[worstI].roundToInt()
    val lines = ArrayList<String>()
    lines += "$good% of the places you walked are fine for video calls. Weakest spot: $worstDb dBm (${Signal.words(worstDb)}), marked with a cross."

    if (worstDb >= -67) {
        lines += "Nothing to fix. Every spot you walked is usable."
        return Advice(lines.joinToString("\n\n"), null, null, PointF(worst.x, worst.y))
    }

    val cx = pts.map { it.x }.average().toFloat()
    val cy = pts.map { it.y }.average().toFloat()
    var routerTo: PointF? = null
    if (router != null) {
        val d = hypot(cx - router.x, cy - router.y)
        if (d > 2.5f) {
            val move = minOf(d - 1f, d * 0.6f)
            routerTo = PointF(router.x + (cx - router.x) / d * move, router.y + (cy - router.y) / d * move)
            lines += "Your router is about ${"%.0f".format(d)} m from the middle of your home. Moving it about ${"%.0f".format(move)} m along the arrow, higher up and out in the open, is the free fix."
        } else {
            lines += "Your router is already near the middle. Lift it higher (on a shelf, not the floor or a cupboard) and keep it away from the fridge and mirrors."
        }
    } else {
        lines += "Tap \"I'm at the router\" when you pass it next time and I can say where to move it."
    }

    // Extender: the spot nearest the dead zone where the signal is still good, so it has something to repeat.
    val ext = pts.indices.filter { smooth[it] in -64f..-55f }
        .minByOrNull { hypot(pts[it].x - worst.x, pts[it].y - worst.y) }
        ?.let { PointF(pts[it].x, pts[it].y) }
    if (ext != null) {
        val d = hypot(ext.x - worst.x, ext.y - worst.y)
        lines += "If the router can't move, put an extender or mesh unit at the star: the signal is still good there and it's ${"%.0f".format(d)} m from the weak spot."
    } else {
        lines += "No spot on your walk is strong enough to feed an extender well. A mesh system or a wired access point is the fix here."
    }
    return Advice(lines.joinToString("\n\n"), ext, routerTo, PointF(worst.x, worst.y))
}
