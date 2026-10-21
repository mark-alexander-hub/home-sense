package io.github.markalexanderhub.homesense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the walk as a floor plan: every spot within about a metre of where you walked is "floor",
 * coloured by the signal measured nearby. The walked line, you, the router and the suggestions sit on top.
 */
class WalkMapView(context: Context, private val t: WalkTracker) : View(context) {
    var advice: Advice? = null
        set(v) { field = v; invalidate() }

    private val ink = context.col(R.color.ink)
    private val dim = context.col(R.color.dim)
    private val paper = context.col(R.color.paper)
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(13).toFloat(); color = dim }
    private var cache: Bitmap? = null
    private var cacheKey = ""

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private fun sx(x: Float) = ox + x * scale
    private fun sy(y: Float) = oy + y * scale

    override fun onDraw(c: Canvas) {
        val pts = t.points
        if (pts.size < 2) {
            text.textAlign = Paint.Align.CENTER
            c.drawText(if (t.walking) "Start walking..." else "Your floor plan appears here as you walk", width / 2f, height / 2f, text)
            return
        }
        fit(pts)
        val key = "${pts.size}:$width:$height:${t.router}"
        if (key != cacheKey || cache == null) {
            cache = render(pts); cacheKey = key
        }
        c.drawBitmap(cache!!, 0f, 0f, null)

        // Walked line
        line.color = ink and 0x55FFFFFF; line.strokeWidth = context.dp(2).toFloat()
        val p = Path()
        pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(sx(pt.x), sy(pt.y)) else p.lineTo(sx(pt.x), sy(pt.y)) }
        c.drawPath(p, line)

        advice?.routerTo?.let { to -> t.router?.let { arrow(c, PointF(it.x, it.y), to) } }
        advice?.worst?.let { cross(c, it) }
        advice?.extender?.let { star(c, it) }
        t.router?.let { routerIcon(c, it.x, it.y) }

        // Start and you
        dot.color = ink
        c.drawRect(sx(pts[0].x) - 8, sy(pts[0].y) - 8, sx(pts[0].x) + 8, sy(pts[0].y) + 8, dot)
        if (t.walking) {
            dot.color = paper; c.drawCircle(sx(t.x), sy(t.y), context.dp(9).toFloat(), dot)
            dot.color = ink; c.drawCircle(sx(t.x), sy(t.y), context.dp(6).toFloat(), dot)
        }

        scaleBar(c)
    }

    private fun fit(pts: List<WalkTracker.Pt>) {
        var x0 = pts.minOf { it.x }; var x1 = pts.maxOf { it.x }
        var y0 = pts.minOf { it.y }; var y1 = pts.maxOf { it.y }
        t.router?.let { x0 = min(x0, it.x); x1 = max(x1, it.x); y0 = min(y0, it.y); y1 = max(y1, it.y) }
        val pad = 1.5f
        val w = max(x1 - x0 + 2 * pad, 5f)
        val h = max(y1 - y0 + 2 * pad, 5f)
        scale = min(width / w, height / h)
        ox = width / 2f - (x0 + x1) / 2f * scale
        oy = height / 2f - (y0 + y1) / 2f * scale
    }

    private fun render(pts: List<WalkTracker.Pt>): Bitmap {
        val bmp = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val good = pts.filter { it.rssi != WalkTracker.NO_SIGNAL }
        val cell = 0.35f
        val reach = 1.0f
        val x0 = pts.minOf { it.x } - reach; val x1 = pts.maxOf { it.x } + reach
        val y0 = pts.minOf { it.y } - reach; val y1 = pts.maxOf { it.y } + reach
        var gy = y0
        while (gy < y1) {
            var gx = x0
            while (gx < x1) {
                val cx = gx + cell / 2; val cy = gy + cell / 2
                var near = Float.MAX_VALUE
                for (pt in pts) near = min(near, hypot(pt.x - cx, pt.y - cy))
                if (near <= reach) {
                    // Inverse-distance blend of the readings within 3 m.
                    var sw = 0f; var sv = 0f
                    for (pt in good) {
                        val d = hypot(pt.x - cx, pt.y - cy)
                        if (d < 3f) { val w = 1f / (d * d + 0.15f); sw += w; sv += w * pt.rssi }
                    }
                    fill.color = if (sw > 0) Signal.color(sv / sw) else Color.GRAY
                    fill.alpha = if (near > reach * 0.75f) 120 else 200
                    c.drawRect(RectF(sx(gx), sy(gy), sx(gx + cell) + 1, sy(gy + cell) + 1), fill)
                }
                gx += cell
            }
            gy += cell
        }
        return bmp
    }

    private fun routerIcon(c: Canvas, x: Float, y: Float) {
        // A little router: box with two aerials.
        val r = context.dp(12).toFloat()
        val bx = sx(x); val by = sy(y) + r * 0.3f
        line.color = ink; line.strokeWidth = context.dp(3).toFloat()
        c.drawLine(bx - r * 0.6f, by - r * 0.4f, bx - r * 0.8f, by - r * 1.3f, line)
        c.drawLine(bx + r * 0.6f, by - r * 0.4f, bx + r * 0.8f, by - r * 1.3f, line)
        dot.color = ink; c.drawRoundRect(bx - r, by - r * 0.5f, bx + r, by + r * 0.5f, 8f, 8f, dot)
        dot.color = paper
        for (k in -1..1) c.drawCircle(bx + k * r * 0.45f, by, r * 0.12f, dot)
    }

    private fun cross(c: Canvas, p: PointF) {
        val r = context.dp(8).toFloat()
        line.color = ink; line.strokeWidth = context.dp(3).toFloat()
        c.drawLine(sx(p.x) - r, sy(p.y) - r, sx(p.x) + r, sy(p.y) + r, line)
        c.drawLine(sx(p.x) - r, sy(p.y) + r, sx(p.x) + r, sy(p.y) - r, line)
    }

    private fun star(c: Canvas, p: PointF) {
        val path = Path()
        val ro = context.dp(13).toFloat(); val ri = ro * 0.45f
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) ro else ri
            val a = Math.PI / 5 * i - Math.PI / 2
            val px = sx(p.x) + (r * cos(a)).toFloat(); val py = sy(p.y) + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        dot.color = paper; c.drawPath(path, dot)
        line.color = ink; line.strokeWidth = context.dp(2).toFloat(); c.drawPath(path, line)
    }

    private fun arrow(c: Canvas, from: PointF, to: PointF) {
        line.color = ink; line.strokeWidth = context.dp(3).toFloat()
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
        c.drawLine(sx(from.x), sy(from.y), sx(to.x), sy(to.y), line)
        line.pathEffect = null
        val a = atan2(sy(to.y) - sy(from.y), sx(to.x) - sx(from.x))
        val h = context.dp(14).toFloat()
        for (s in listOf(-0.5f, 0.5f)) {
            c.drawLine(sx(to.x), sy(to.y), sx(to.x) - h * cos(a + s), sy(to.y) - h * sin(a + s), line)
        }
    }

    private fun scaleBar(c: Canvas) {
        val m = if (scale * 1f > width / 6f) 1f else if (scale * 2f > width / 6f) 2f else 5f
        val x = context.dp(8).toFloat(); val y = height - context.dp(10).toFloat()
        line.color = dim; line.strokeWidth = context.dp(2).toFloat()
        c.drawLine(x, y, x + m * scale, y, line)
        text.textAlign = Paint.Align.LEFT
        c.drawText("${m.toInt()} m", x, y - context.dp(5), text)
    }
}
