package io.github.markalexanderhub.homesense

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

// The screens are small enough that building them in code reads better than four layout files.
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(col(R.color.paper))
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        build()
    }
    // Android 15 draws apps under the status and navigation bars; keep the content clear of them.
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val pad = dp(16)
        v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
        insets
    }
    setContentView(root)
    return root
}

fun ViewGroup.heading(text: String, size: Float = 26f): TextView = TextView(context).apply {
    this.text = text
    textSize = size
    setTypeface(Typeface.create("serif", Typeface.BOLD))
    setTextColor(context.col(R.color.ink))
}.also { addView(it) }

fun ViewGroup.body(text: String, size: Float = 15f, dim: Boolean = false): TextView = TextView(context).apply {
    this.text = text
    textSize = size
    setLineSpacing(0f, 1.15f)
    setTextColor(context.col(if (dim) R.color.dim else R.color.ink))
    setPadding(0, context.dp(4), 0, context.dp(4))
}.also { addView(it) }

fun ViewGroup.mono(text: String, size: Float = 15f): TextView = body(text, size).apply {
    typeface = Typeface.MONOSPACE
}

fun ViewGroup.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    build()
}.also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

fun ViewGroup.button(text: String, outlined: Boolean = false, onClick: (View) -> Unit): MaterialButton {
    val b = if (outlined) MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(context)
    b.text = text
    b.isAllCaps = false
    b.setOnClickListener(onClick)
    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    lp.marginEnd = context.dp(6)
    addView(b, lp)
    return b
}

fun ViewGroup.card(onClick: (() -> Unit)? = null, build: LinearLayout.() -> Unit): MaterialCardView {
    val c = MaterialCardView(context).apply {
        radius = context.dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = context.dp(1)
        strokeColor = context.col(R.color.dim) and 0x40FFFFFF
        setCardBackgroundColor(context.col(R.color.card))
        onClick?.let { f -> setOnClickListener { f() } }
    }
    val inner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(16)
        setPadding(p, p, p, p)
        build()
    }
    c.addView(inner)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = context.dp(12)
    addView(c, lp)
    return c
}

fun ViewGroup.fill(v: View) {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
}
