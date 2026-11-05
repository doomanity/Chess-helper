package com.example.chesshelper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * the glow of a brilliant or great move, like an enchanted item in minecraft:
 * - a shock ring when it lands
 * - the "enchantment glint": two layers of diagonal stripes sliding over the piece in opposite directions
 * - runes from the enchanting table (the standard galactic alphabet) floating up and fading
 * - little four point sparkles twinkling around it
 * brilliant = teal and cyan, great = blue and violet. used by the live overlay and the review board.
 * it never reaches past the squares right next to the piece (the live board reader leaves those alone for a moment)
 */
object Enchant {
    /** how long the full show runs (seconds). after that only a calm glint stays (when loop is on) */
    const val SHOW = 3.6f

    // the enchanting table's runes (the letters minecraft uses), with plain look-alikes any phone font has
    private val RUNES = arrayOf("ᔑ", "ᓵ", "ᒷ", "ᒲ", "ᓭ", "↸", "⎓", "⊣", "⍑", "╎", "ℸ", "∷", "⚍", "∴", "リ", "ʖ")

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rune = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val star = Path()

    private fun a(c: Int, alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** the two colors of the glow: bright and deep */
    fun colors(lab: Label): Pair<Int, Int> =
        if (lab == Label.BRILLIANT) Pair(Color.rgb(64, 240, 230), Color.rgb(140, 120, 255))
        else Pair(Color.rgb(96, 140, 255), Color.rgb(150, 80, 255))

    /**
     * draws the effect on the square rect, t seconds after the move. piece: draws the piece with a given paint
     * (so the glint lands on the piece itself), or null to glint the whole square (the live overlay can't redraw
     * the chess app's piece). loop: keep a calm glint going after the show. returns true while it still moves
     */
    fun draw(c: Canvas, rect: RectF, lab: Label, t: Float, piece: ((Paint) -> Unit)?, loop: Boolean): Boolean {
        val sq = rect.width()
        val (bright, deep) = colors(lab)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val inShow = t < SHOW
        val fade = when {
            t < SHOW - 0.8f -> 1f
            t < SHOW -> 1f - (t - (SHOW - 0.8f)) / 0.8f * 0.65f
            else -> 0.35f
        }
        if (!inShow && !loop) {
            // the show is over: a faint steady tint stays on the square
            fill.color = a(deep, 0.16f)
            c.drawRect(rect, fill)
            return false
        }
        val pulse = 0.65f + 0.35f * sin(t * 5f)

        // 1. a soft glow that breathes
        val gr = sq * (0.75f + 0.12f * pulse)
        glow.shader = RadialGradient(cx, cy, gr, intArrayOf(a(bright, 0.55f * pulse * fade), a(deep, 0.22f * fade), a(deep, 0f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, glow)
        glow.shader = null

        // 2. shock rings when it lands
        if (t < 0.9f) {
            for (k in 0..1) {
                val p = ((t - k * 0.18f) / 0.7f).coerceIn(0f, 1f)
                if (p <= 0f || p >= 1f) continue
                ring.strokeWidth = sq * 0.06f * (1f - p)
                ring.color = a(if (k == 0) Color.WHITE else bright, 0.9f * (1f - p))
                c.drawCircle(cx, cy, sq * (0.25f + 0.62f * p), ring)
            }
        }

        // 3. the glint: two stripe layers sliding diagonally in opposite directions, like minecraft's enchanted items
        val period = sq * 0.9f
        glintA.shader = LinearGradient(0f, 0f, period, period * 0.35f,
            intArrayOf(a(deep, 0f), a(bright, 0.85f * fade), a(Color.WHITE, 0.95f * fade), a(bright, 0.85f * fade), a(deep, 0f), a(deep, 0f)),
            floatArrayOf(0f, 0.12f, 0.18f, 0.24f, 0.4f, 1f), Shader.TileMode.REPEAT)
        m.reset()
        m.setRotate(-35f)
        m.postTranslate(rect.left + (t * sq * 0.55f) % period, rect.top)
        glintA.shader.setLocalMatrix(m)
        glintB.shader = LinearGradient(0f, 0f, period * 0.7f, 0f,
            intArrayOf(a(deep, 0f), a(deep, 0.7f * fade), a(bright, 0.6f * fade), a(deep, 0f), a(deep, 0f)),
            floatArrayOf(0f, 0.15f, 0.25f, 0.4f, 1f), Shader.TileMode.REPEAT)
        m.reset()
        m.setRotate(25f)
        m.postTranslate(rect.left - (t * sq * 0.35f) % (period * 0.7f), rect.top)
        glintB.shader.setLocalMatrix(m)
        if (piece != null) {
            fill.color = a(deep, 0.45f * fade)
            piece(fill)
            piece(glintB)
            piece(glintA)
        } else {
            c.save()
            c.clipRect(rect)
            fill.color = a(deep, 0.22f * fade)
            c.drawRect(rect, fill)
            glintB.alpha = (glintB.alpha * 0.55f).toInt()
            c.drawRect(rect, glintB)
            glintA.alpha = (glintA.alpha * 0.6f).toInt()
            c.drawRect(rect, glintA)
            glintA.alpha = 255
            glintB.alpha = 255
            c.restore()
        }
        glintA.shader = null
        glintB.shader = null
        if (!inShow) return true // looping: only the glint keeps moving

        // 4. runes from the enchanting table floating up, swaying a little, fading out
        rune.textSize = sq * 0.22f
        for (k in 0 until 8) {
            val ph = (t * 0.42f + k * 0.125f) % 1f
            val life = min(1f, t / 0.35f) // they appear just after the landing
            val sx = cx + sin(k * 2.1f + t * 1.3f) * sq * 0.42f
            val sy = rect.bottom + sq * 0.05f - ph * sq * 1.55f
            val al = sin(ph * PI.toFloat()) * fade * life
            if (al <= 0.02f) continue
            rune.color = a(if (k % 3 == 0) Color.WHITE else bright, al)
            rune.setShadowLayer(sq * 0.08f, 0f, 0f, a(deep, al))
            c.drawText(RUNES[(k * 5 + (t * 0.42f + k * 0.125f).toInt() * 3) % RUNES.size], sx, sy, rune)
        }
        rune.clearShadowLayer()

        // 5. four point sparkles twinkling around the piece
        for (k in 0 until 6) {
            val tw = sin(t * 4.2f + k * 1.7f)
            if (tw <= 0f) continue
            val ang = k * 1.05f + t * 0.3f
            val rad = sq * (0.42f + 0.1f * ((k * 37) % 5) / 5f)
            val sx = cx + cos(ang) * rad
            val sy = cy + sin(ang) * rad
            sparkle(c, sx, sy, sq * 0.09f * tw, a(if (k % 2 == 0) Color.WHITE else bright, tw * fade))
        }
        return true
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        if (r < 0.5f) return
        val w = r * 0.28f
        star.reset()
        star.moveTo(x, y - r)
        star.quadTo(x + w * 0.4f, y - w * 0.4f, x + r, y)
        star.quadTo(x + w * 0.4f, y + w * 0.4f, x, y + r)
        star.quadTo(x - w * 0.4f, y + w * 0.4f, x - r, y)
        star.quadTo(x - w * 0.4f, y - w * 0.4f, x, y - r)
        star.close()
        fill.color = col
        c.drawPath(star, fill)
    }

    /** a badge ring with a little shine for brilliant / great (the label circle itself is drawn by the caller) */
    fun badgeShine(c: Canvas, cx: Float, cy: Float, r: Float, lab: Label, t: Float) {
        val (bright, _) = colors(lab)
        val p = (t % 2.2f) / 2.2f
        ring.strokeWidth = max(1f, r * 0.14f)
        ring.color = a(bright, 0.9f * (1f - p))
        c.drawCircle(cx, cy, r * (1.05f + 0.45f * p), ring)
    }
}
