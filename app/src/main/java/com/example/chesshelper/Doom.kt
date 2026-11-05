package com.example.chesshelper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.sin

/**
 * the blunder alert: a square turned dark red with a big skull over it.
 * - danger(): on your turn, a square where every move of yours that goes there is a blunder. calm (no animation)
 * - landed(): a move that was just played and was a blunder. the skull drops in, shakes, its eyes glow
 * everything stays inside the square (the live board reader only knows to ignore that square)
 */
object Doom {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Path()
    private val holes = Path()

    val BLOOD = Color.rgb(110, 0, 12)
    private val BONE = Color.rgb(240, 232, 220)
    private val EMBER = Color.rgb(255, 40, 40)

    private fun a(c: Int, alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** a square your pieces should not go to: dark red with a pale skull */
    fun danger(c: Canvas, r: RectF) {
        c.save()
        c.clipRect(r)
        tint(c, r, 0.46f)
        skull(c, r.centerX(), r.centerY() + r.height() * 0.02f, r.width() * 0.34f, 0.55f, 0f)
        c.restore()
    }

    /** a blunder that was just played, t seconds ago. true while it still moves */
    fun landed(c: Canvas, r: RectF, t: Float, animate: Boolean): Boolean {
        val sq = r.width()
        val drop = if (animate) (t / 0.32f).coerceIn(0f, 1f) else 1f
        val shake = if (animate && t in 0.32f..0.9f) sin(t * 70f) * sq * 0.035f * (0.9f - t) / 0.58f else 0f
        val flash = if (animate && t < 0.6f) 1f - t / 0.6f else 0f
        c.save()
        c.clipRect(r)
        tint(c, r, 0.62f + 0.25f * flash)
        // the skull falls in from big and lands with a shake
        val scale = 1.5f - 0.5f * drop * drop
        val alpha = 0.35f + 0.55f * drop
        val eyes = if (animate) 0.55f + 0.45f * sin(t * 6f) else 0.7f
        skull(c, r.centerX() + shake, r.centerY() + sq * 0.02f, sq * 0.4f * scale, alpha, eyes)
        // a hard red frame
        line.color = a(EMBER, 0.55f + 0.4f * flash)
        line.strokeWidth = sq * 0.05f
        c.drawRect(r.left + sq * 0.025f, r.top + sq * 0.025f, r.right - sq * 0.025f, r.bottom - sq * 0.025f, line)
        c.restore()
        return animate && t < 1.8f
    }

    /** dark red, darker at the edges */
    private fun tint(c: Canvas, r: RectF, alpha: Float) {
        shade.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.75f,
            intArrayOf(a(BLOOD, alpha * 0.75f), a(Color.rgb(50, 0, 6), alpha)), null, Shader.TileMode.CLAMP)
        c.drawRect(r, shade)
        shade.shader = null
    }

    /** a skull of radius s centered on (cx, cy). eyes > 0 makes the eye sockets glow red */
    private fun skull(c: Canvas, cx: Float, cy: Float, s: Float, alpha: Float, eyes: Float) {
        head.reset()
        // the head and the jaw
        head.addCircle(cx, cy - s * 0.18f, s * 0.78f, Path.Direction.CW)
        head.addRoundRect(RectF(cx - s * 0.46f, cy + s * 0.3f, cx + s * 0.46f, cy + s * 0.92f), s * 0.16f, s * 0.16f, Path.Direction.CW)
        holes.reset()
        holes.addOval(RectF(cx - s * 0.5f, cy - s * 0.32f, cx - s * 0.1f, cy + s * 0.1f), Path.Direction.CW)
        holes.addOval(RectF(cx + s * 0.1f, cy - s * 0.32f, cx + s * 0.5f, cy + s * 0.1f), Path.Direction.CW)
        holes.moveTo(cx, cy + s * 0.12f)
        holes.lineTo(cx - s * 0.12f, cy + s * 0.34f)
        holes.lineTo(cx + s * 0.12f, cy + s * 0.34f)
        holes.close()
        head.op(holes, Path.Op.DIFFERENCE)
        // a soft dark shadow, then the bone
        fill.color = a(Color.BLACK, alpha * 0.45f)
        c.save()
        c.translate(s * 0.05f, s * 0.07f)
        c.drawPath(head, fill)
        c.restore()
        fill.color = a(BONE, alpha)
        c.drawPath(head, fill)
        // teeth
        line.color = a(BLOOD, alpha)
        line.strokeWidth = max(1f, s * 0.07f)
        for (k in -1..1) c.drawLine(cx + k * s * 0.17f, cy + s * 0.56f, cx + k * s * 0.17f, cy + s * 0.88f, line)
        // glowing eyes
        if (eyes > 0f) {
            for (side in listOf(-1f, 1f)) {
                val ex = cx + side * s * 0.3f
                val ey = cy - s * 0.11f
                fill.shader = RadialGradient(ex, ey, s * 0.22f, intArrayOf(a(Color.rgb(255, 200, 160), eyes * alpha), a(EMBER, eyes * alpha), a(EMBER, 0f)),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(ex, ey, s * 0.22f, fill)
                fill.shader = null
            }
        }
    }
}
