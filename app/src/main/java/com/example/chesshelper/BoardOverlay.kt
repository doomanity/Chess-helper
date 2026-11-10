package com.example.chesshelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** one suggested move to draw: where it goes (screen pixel rectangles), how good it is, and what the list says */
class DrawMove(
    val from: RectF, val to: RectF, val grade: Int, val rank: Int, val label: String, val eval: String,
    val star: Boolean = false
)

/** one move of the main line after the best move (n = 1 is the opponent's reply, then us, then them) */
class LineStep(val from: RectF, val to: RectF, val n: Int, val mine: Boolean)

/**
 * one guess of the bot's next move while it thinks: where it goes, how likely it is (maia3's odds, null with
 * stockfish) and your answer to it once that is worked out (null while still thinking)
 */
class GuessDraw(
    val from: RectF, val to: RectF, val san: String, val prob: Float?, val answer: String?,
    val ansFrom: RectF? = null, val ansTo: RectF? = null   // your premove for this guess, drawn on the board
)

/** everything to draw for one position */
class OverlayModel(
    val moves: List<DrawMove>,
    val board: RectF,
    val whiteBottom: Boolean,
    val evalShare: Float? = null,     // 0..1 white part of the eval bar, null = no bar
    val evalText: String? = null,
    val threatFrom: RectF? = null,
    val threatTo: RectF? = null,
    val line: List<LineStep> = emptyList(),
    val title: String = "",
    val accuracy: String? = null,     // like "you 87% · bot 72%"
    val guesses: List<GuessDraw> = emptyList(), // the bot's likely next moves, most likely first, each with your premove
    val danger: List<RectF> = emptyList(), // blunder alert: squares where every move of yours that goes there blunders
    val hanging: List<RectF> = emptyList(), // your pieces the opponent can take for free (yellow ring)
    val plan: List<LineStep> = emptyList(), // the bot's plan: its next moves if you did nothing
    val alert: String? = null,         // a critical moment: shown on the card, which pulses
    val alertColor: Int = 0,
    val brilFrom: RectF? = null,       // the brilliant hunter found a brilliant move: a teal arrow with "!!"
    val brilTo: RectF? = null
) {
    private fun copy(
        guesses: List<GuessDraw> = this.guesses, danger: List<RectF> = this.danger, alert: String? = this.alert,
        alertColor: Int = this.alertColor, brilFrom: RectF? = this.brilFrom, brilTo: RectF? = this.brilTo
    ) = OverlayModel(
        moves, board, whiteBottom, evalShare, evalText, threatFrom, threatTo, line, title, accuracy,
        guesses, danger, hanging, plan, alert, alertColor, brilFrom, brilTo
    )

    /** the same picture with the brilliant move the hunter found (the card says so, unless it shows a critical moment) */
    fun withBrilliant(from: RectF, to: RectF, san: String) =
        copy(brilFrom = from, brilTo = to, alert = alert ?: "!! brilliant move: $san", alertColor = if (alert != null) alertColor else Label.BRILLIANT.color)

    /** the same picture with new guesses (answers filled in) */
    fun withGuesses(gs: List<GuessDraw>) = copy(guesses = gs)

    /** the same picture with the blunder alert squares */
    fun withDanger(sq: List<RectF>) = copy(danger = sq)
}

/** one square the touched piece can go to: safe (green) or where it gets taken (red) */
class SafeMark(val rect: RectF, val safe: Boolean)

/** the label of the move that was just played (brilliant, blunder...), drawn on the square it went to */
class BadgeModel(val rect: RectF, val label: Label)

/** how to draw: style, colors, animation, sizes */
class OverlayLook(
    val style: String, val accent: Int, val anim: Boolean, val speed: Float,
    val arrowSize: Float, val cardSize: Float, val opacity: Float
) {
    /** how long the entrance animation runs (the board reader waits this long before it looks again) */
    val totalMs: Long get() = if (anim) ((DRAW_MS + GLOW_MS) / speed).toLong() else 0L

    companion object {
        const val DRAW_MS = 380f
        const val GLOW_MS = 900f
    }
}

/** transparent full-screen layer that draws the suggested moves on top of the chess app */
class BoardOverlay(ctx: Context) : View(ctx) {
    companion object {
        val PREDICT = Color.rgb(200, 206, 222) // the bot's guessed move: soft grey-white
        const val ALERT_SECONDS = 2.2f

        /** guess 1, 2, 3 and the premove that answers it share a color (and a number) */
        val GUESS_COLORS = intArrayOf(Color.rgb(90, 200, 250), Color.rgb(255, 159, 10), Color.rgb(191, 90, 242))
        fun guessColor(i: Int) = GUESS_COLORS[i.coerceIn(0, GUESS_COLORS.size - 1)]

        val HANG = Color.rgb(255, 214, 10)  // a hanging piece: yellow
        val SAFE = Color.rgb(48, 209, 88)   // a safe square for the touched piece: green
        val PLAN_COLORS = intArrayOf(Color.rgb(255, 69, 58), Color.rgb(255, 120, 60), Color.rgb(255, 170, 90)) // the bot's plan, 1 2 3

        /** critical moment colors on the card */
        val ALERT_CHANCE = Color.rgb(255, 204, 0)
        val ALERT_ONLY = Color.rgb(255, 120, 40)
    }

    private var model: OverlayModel? = null
    private var badge: BadgeModel? = null
    private var safety: List<SafeMark> = emptyList()
    private var badgeStart = 0L
    private var look = OverlayLook("neon", Color.rgb(0, 229, 255), true, 1f, 1f, 1f, 0.95f)
    private var start = 0L
    private var lastShare = 0.5f
    private var debug = ""
    private val where = IntArray(2)
    private val d = ctx.resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun gradeColor(m: DrawMove): Int = if (m.star) Theme.GOLD else gradeColor(m.grade)

    private fun gradeColor(grade: Int): Int = when (grade) {
        Grade.BEST -> Color.rgb(0, 200, 83)        // dark green
        Grade.DECENT -> Color.rgb(140, 230, 90)    // light green
        Grade.OK -> Color.rgb(255, 214, 0)         // yellow
        else -> Color.rgb(255, 45, 70)             // red
    }

    fun setLook(l: OverlayLook) {
        look = l
        alpha = l.opacity
        invalidate()
    }

    fun setModel(m: OverlayModel) {
        model?.let { old -> old.evalShare?.let { lastShare = it } }
        model = m
        start = SystemClock.uptimeMillis()
        invalidate()
    }

    /** swaps what is drawn without playing the entrance animation again (a premove showing up next to the guess) */
    fun updateModel(m: OverlayModel) {
        model = m
        invalidate()
    }

    /** the label of the move that was just played. it stays until the board changes (clearMove) */
    fun setBadge(b: BadgeModel?) {
        badge = b
        badgeStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun clearMove() {
        if (model == null && badge == null && safety.isEmpty()) return
        model?.evalShare?.let { lastShare = it }
        model = null
        badge = null
        safety = emptyList()
        invalidate()
    }

    /** the safe / unsafe squares of the piece you touched (empty list = take them away) */
    fun setSafety(marks: List<SafeMark>) {
        safety = marks
        invalidate()
    }

    /** a small status line at the bottom of the screen, so a screenshot tells what the app thinks */
    fun setDebug(t: String) {
        if (t == debug) return
        debug = t
        invalidate()
    }

    // ------------------------------------------------------------------ animation

    private fun ease(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** 0..1 how far the arrows are drawn in */
    private var drawP = 1f

    /** extra glow while the new moves pulse, 1 = normal */
    private var glow = 1f

    override fun onDraw(canvas: Canvas) {
        // some phones put this window a little lower than the top of the screen (status bar, camera cutout).
        // ask where we really are and move the drawing back so highlights land on the right squares.
        getLocationOnScreen(where)
        canvas.translate(-where[0].toFloat(), -where[1].toFloat())
        val screenW = (width + where[0]).toFloat()

        val m = model
        if (m != null) {
            val t = (SystemClock.uptimeMillis() - start) * look.speed
            if (look.anim) {
                drawP = ease(t / OverlayLook.DRAW_MS)
                val g = (t - OverlayLook.DRAW_MS) / OverlayLook.GLOW_MS
                glow = if (g in 0f..1f) 1f + 0.45f * sin(g * 2f * PI.toFloat()).let { it * it } else 1f
                if (t < OverlayLook.DRAW_MS + OverlayLook.GLOW_MS) postInvalidateOnAnimation()
            } else {
                drawP = 1f
                glow = 1f
            }

            if (m.evalShare != null) drawEvalBar(canvas, m, screenW)
            // the blunder alert squares first, so the suggested moves are drawn over them
            for (r in m.danger) Doom.danger(canvas, r)
            for (r in m.hanging) drawHanging(canvas, r)
            for (s in m.line) drawLineStep(canvas, s)
            for (s in m.plan.reversed()) drawPlanStep(canvas, s)
            if (m.brilFrom != null && m.brilTo != null) drawBrilliant(canvas, m.brilFrom, m.brilTo)
            if (m.threatFrom != null && m.threatTo != null) drawThreat(canvas, m.threatFrom, m.threatTo)
            // the less likely guesses first, so the most likely one is drawn on top
            for (i in m.guesses.indices.reversed()) drawPrediction(canvas, m.guesses[i], i)
            drawPremoves(canvas, m.guesses)
            // the worst first, so the best move is drawn on top
            for (mv in m.moves.sortedByDescending { it.rank }) drawMove(canvas, mv)
            if (m.moves.isNotEmpty() || m.guesses.isNotEmpty()) drawCard(canvas, m, screenW)
        }
        for (mk in safety) drawSafeMark(canvas, mk)
        badge?.let { drawLabelBadge(canvas, it) }
        if (debug.isNotEmpty()) canvas.drawText(debug, screenW / 2f, (height + where[1]) - 36f * d, debugPaint)
    }

    // ------------------------------------------------------------------ neon helpers

    private val neon get() = look.style == "neon"

    /** a line that glows (neon) or a plain one */
    private fun glowLine(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, col: Int, w: Float, alpha: Float = 1f, dash: Boolean = false) {
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.pathEffect = if (dash) DashPathEffect(floatArrayOf(w * 1.6f, w * 1.4f), 0f) else null
        if (neon) {
            stroke.color = Theme.withAlpha(col, (28 * glow * alpha).toInt())
            stroke.strokeWidth = w * 3.4f
            c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = Theme.withAlpha(col, (70 * glow * alpha).toInt())
            stroke.strokeWidth = w * 2.0f
            c.drawLine(x0, y0, x1, y1, stroke)
        }
        stroke.color = Theme.withAlpha(col, (235 * alpha).toInt())
        stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
        if (neon) {
            stroke.color = Theme.withAlpha(Theme.core(col), (210 * alpha).toInt())
            stroke.strokeWidth = w * 0.38f
            c.drawLine(x0, y0, x1, y1, stroke)
        }
        stroke.pathEffect = null
    }

    private fun glowRing(c: Canvas, r: RectF, col: Int, w: Float, radius: Float) {
        if (neon) {
            stroke.color = Theme.withAlpha(col, (40 * glow).toInt())
            stroke.strokeWidth = w * 3f
            c.drawRoundRect(r, radius, radius, stroke)
        }
        stroke.color = col
        stroke.strokeWidth = w
        c.drawRoundRect(r, radius, radius, stroke)
        if (neon) {
            stroke.color = Theme.withAlpha(Theme.core(col), 200)
            stroke.strokeWidth = w * 0.35f
            c.drawRoundRect(r, radius, radius, stroke)
        }
    }

    private fun glowFillPath(c: Canvas, p: Path, col: Int, alpha: Float = 1f) {
        if (neon) {
            stroke.color = Theme.withAlpha(col, (55 * glow * alpha).toInt())
            stroke.strokeWidth = 10f * d * look.arrowSize
            stroke.strokeJoin = Paint.Join.ROUND
            c.drawPath(p, stroke)
        }
        fill.color = Theme.withAlpha(col, (240 * alpha).toInt())
        c.drawPath(p, fill)
    }

    // ------------------------------------------------------------------ pieces of the drawing

    private fun drawMove(c: Canvas, m: DrawMove) {
        val sq = m.to.width()
        val col = gradeColor(m)
        val rad = sq * 0.12f
        val minimal = look.style == "minimal"

        // the piece to pick up
        if (m.rank == 1) {
            fill.color = Theme.withAlpha(col, (55 * drawP).toInt())
            c.drawRoundRect(m.from, rad, rad, fill)
        }
        glowRing(c, RectF(m.from).apply { inset(sq * 0.05f, sq * 0.05f) }, Theme.withAlpha(col, (230 * drawP).toInt()), sq * 0.045f * look.arrowSize, rad)

        // where it goes
        fill.color = Theme.withAlpha(col, ((if (m.rank == 1) 95 else 70) * drawP).toInt())
        c.drawRoundRect(m.to, rad, rad, fill)
        glowRing(c, RectF(m.to).apply { inset(sq * 0.05f, sq * 0.05f) }, Theme.withAlpha(col, (255 * drawP).toInt()), sq * 0.06f * look.arrowSize, rad)

        if (minimal) {
            fill.color = Theme.withAlpha(col, (230 * drawP).toInt())
            c.drawCircle(m.to.centerX(), m.to.centerY(), sq * 0.13f * look.arrowSize * drawP, fill)
        } else {
            arrow(c, m.from, m.to, col, sq * (if (m.rank == 1) 0.15f else 0.1f) * look.arrowSize, 1f, false)
        }

        // rank badge (top right), skull for a blunder or star for a max power move (bottom right)
        val r = sq * 0.16f * drawP
        if (r > 1f) {
            val bx = m.to.right - sq * 0.19f
            val by = m.to.top + sq * 0.19f
            badge(c, bx, by, r, col)
            text.textSize = r * 1.3f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.color = if (m.grade == Grade.DECENT || m.grade == Grade.OK || m.star) Color.BLACK else Color.WHITE
            c.drawText(if (m.star) "★" else m.rank.toString(), bx, by + r * 0.46f, text)
            text.isFakeBoldText = false
            text.color = Color.WHITE
            if (m.grade == Grade.BLUNDER && !m.star) skull(c, m.to.right - sq * 0.2f, m.to.bottom - sq * 0.2f, r * 1.1f)
        }
    }

    private fun arrow(c: Canvas, from: RectF, to: RectF, col: Int, width: Float, alpha: Float, dash: Boolean) {
        val sq = to.width()
        val x0 = from.centerX()
        val y0 = from.centerY()
        val x1 = to.centerX()
        val y1 = to.centerY()
        val len = hypot(x1 - x0, y1 - y0)
        if (len < sq * 0.4f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val head = maxOf(width * 2.3f, sq * 0.26f)
        val sx = x0 + ux * sq * 0.2f
        val sy = y0 + uy * sq * 0.2f
        val full = len - sq * 0.28f
        val reach = full * drawP
        val ex = sx + ux * reach
        val ey = sy + uy * reach
        val bx = ex - ux * head
        val by = ey - uy * head
        if (reach > head) glowLine(c, sx, sy, bx, by, col, width, alpha, dash)
        val a = atan2(uy, ux)
        val half = head * 0.6f
        val p = Path()
        p.moveTo(ex, ey)
        p.lineTo(bx - sin(a) * half, by + cos(a) * half)
        p.lineTo(bx + sin(a) * half, by - cos(a) * half)
        p.close()
        glowFillPath(c, p, col, alpha)
    }

    private fun drawLineStep(c: Canvas, s: LineStep) {
        val sq = s.to.width()
        val col = if (s.mine) look.accent else Color.rgb(205, 210, 225)
        val w = sq * 0.065f * look.arrowSize
        if (look.style != "minimal") arrow(c, s.from, s.to, col, w, 0.55f, !s.mine)
        val r = sq * 0.12f * drawP
        if (r > 1f) {
            val bx = s.to.left + sq * 0.17f
            val by = s.to.top + sq * 0.17f
            fill.color = Color.argb(200, 15, 20, 32)
            c.drawCircle(bx, by, r * 1.15f, fill)
            stroke.color = Theme.withAlpha(col, 200)
            stroke.strokeWidth = r * 0.18f
            c.drawCircle(bx, by, r, stroke)
            text.textSize = r * 1.15f
            text.textAlign = Paint.Align.CENTER
            text.color = col
            c.drawText((s.n + 1).toString(), bx, by + r * 0.42f, text)
            text.color = Color.WHITE
        }
    }

    private fun drawThreat(c: Canvas, from: RectF, to: RectF) {
        val sq = to.width()
        arrow(c, from, to, Theme.THREAT, sq * 0.08f * look.arrowSize, 0.85f, true)
        val r = sq * 0.14f * drawP
        if (r > 1f) {
            val bx = to.left + sq * 0.18f
            val by = to.bottom - sq * 0.18f
            badge(c, bx, by, r, Theme.THREAT)
            text.textSize = r * 1.4f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            c.drawText("!", bx, by + r * 0.5f, text)
            text.isFakeBoldText = false
        }
    }

    /** the brilliant move the hunter found: a thick teal arrow, the square lit, and a "!!" badge */
    private fun drawBrilliant(c: Canvas, from: RectF, to: RectF) {
        val sq = to.width()
        val teal = Label.BRILLIANT.color
        fill.color = Theme.withAlpha(teal, (70 * drawP).toInt())
        c.drawRoundRect(to, sq * 0.12f, sq * 0.12f, fill)
        arrow(c, from, to, teal, sq * 0.1f * look.arrowSize, 0.95f, false)
        val r = sq * 0.17f * drawP
        if (r > 1f) {
            val bx = to.right - sq * 0.2f
            val by = to.top + sq * 0.2f
            fill.color = Color.argb(160, 0, 0, 0)
            c.drawCircle(bx, by, r * 1.15f, fill)
            fill.color = teal
            c.drawCircle(bx, by, r, fill)
            text.textSize = r * 1.05f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.color = Color.WHITE
            c.drawText("!!", bx, by + r * 0.38f, text)
            text.isFakeBoldText = false
        }
    }

    /** one of your pieces that hangs: a yellow ring around it with a small "!" */
    private fun drawHanging(c: Canvas, r: RectF) {
        val sq = r.width()
        stroke.color = Theme.withAlpha(HANG, (235 * drawP).toInt())
        stroke.strokeWidth = sq * 0.065f
        c.drawCircle(r.centerX(), r.centerY(), sq * 0.42f, stroke)
        val br = sq * 0.13f * drawP
        if (br > 1f) {
            val bx = r.right - sq * 0.17f
            val by = r.bottom - sq * 0.17f
            fill.color = HANG
            c.drawCircle(bx, by, br, fill)
            text.textSize = br * 1.5f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.color = Color.rgb(30, 24, 0)
            c.drawText("!", bx, by + br * 0.52f, text)
            text.isFakeBoldText = false
            text.color = Color.WHITE
        }
    }

    /** one step of the bot's plan: a thin dashed red-orange arrow with its number */
    private fun drawPlanStep(c: Canvas, s: LineStep) {
        val sq = s.to.width()
        val col = PLAN_COLORS[(s.n - 1).coerceIn(0, PLAN_COLORS.size - 1)]
        arrow(c, s.from, s.to, col, sq * 0.05f * look.arrowSize, if (s.n == 1) 0.75f else 0.55f, true)
        val r = sq * 0.12f * drawP
        if (r > 1f) {
            val bx = s.to.right - sq * 0.17f
            val by = s.to.bottom - sq * 0.17f
            fill.color = Color.argb(210, 20, 10, 10)
            c.drawCircle(bx, by, r * 1.15f, fill)
            stroke.color = col
            stroke.strokeWidth = r * 0.2f
            c.drawCircle(bx, by, r, stroke)
            text.textSize = r * 1.15f
            text.textAlign = Paint.Align.CENTER
            text.color = col
            text.isFakeBoldText = true
            c.drawText("${s.n}", bx, by + r * 0.42f, text)
            text.isFakeBoldText = false
            text.color = Color.WHITE
        }
    }

    /** a square the touched piece can go to: a green dot (safe) or a red dot with an x (it gets taken there) */
    private fun drawSafeMark(c: Canvas, mk: SafeMark) {
        val sq = mk.rect.width()
        val cx = mk.rect.centerX()
        val cy = mk.rect.centerY()
        val r = sq * 0.2f
        fill.color = Color.argb(150, 0, 0, 0)
        c.drawCircle(cx, cy, r * 1.18f, fill)
        fill.color = if (mk.safe) SAFE else Theme.THREAT
        c.drawCircle(cx, cy, r, fill)
        if (!mk.safe) {
            stroke.color = Color.WHITE
            stroke.strokeWidth = r * 0.28f
            stroke.strokeCap = Paint.Cap.ROUND
            val k = r * 0.45f
            c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
            c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke)
        }
    }

    /**
     * one of the bot's likely next moves: a thin dashed arrow in its own color, with a tag at its end that says its
     * number and, with maia3, its odds (like "1 52%")
     */
    private fun drawPrediction(c: Canvas, g: GuessDraw, rank: Int) {
        val sq = g.to.width()
        val col = guessColor(rank)
        arrow(c, g.from, g.to, col, sq * 0.055f * look.arrowSize, if (rank == 0) 0.6f else 0.48f, true)
        val tag = g.prob?.let { "${rank + 1}  ${Math.round(it * 100)}%" } ?: "${rank + 1}"
        val h = sq * 0.27f * drawP
        if (h < 2f) return
        text.textSize = h * 0.62f
        text.typeface = medium
        text.textAlign = Paint.Align.CENTER
        val w = maxOf(h, text.measureText(tag) + h * 0.6f)
        val cx = g.to.right - sq * 0.05f - w / 2f
        val cy = g.to.top + sq * 0.05f + h / 2f
        fill.color = Theme.withAlpha(col, 235)
        c.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, h / 2f, h / 2f, fill)
        text.color = Color.rgb(16, 18, 26)
        c.drawText(tag, cx, cy + h * 0.22f, text)
        text.color = Color.WHITE
        text.typeface = regular
    }

    /**
     * your premoves: the answer to each guess, in that guess's color with its number. when two guesses have the same
     * answer, each still gets its own arrow: side by side on the same move, each in its own color
     */
    private fun drawPremoves(c: Canvas, guesses: List<GuessDraw>) {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for ((i, g) in guesses.withIndex()) {
            val f = g.ansFrom ?: continue
            val t = g.ansTo ?: continue
            groups.getOrPut("${f.left},${f.top},${t.left},${t.top}") { ArrayList() }.add(i)
        }
        // the less likely first, so the most likely answer ends up on top
        for (ranks in groups.values.reversed()) {
            val g = guesses[ranks[0]]
            val from = g.ansFrom!!
            val to = g.ansTo!!
            val sq = to.width()
            val dx = to.centerX() - from.centerX()
            val dy = to.centerY() - from.centerY()
            val len = maxOf(1f, hypot(dx, dy))
            // sideways direction, to spread the arrows of one shared move
            val px = -dy / len
            val py = dx / len
            val gap = sq * 0.2f
            for ((j, rank) in ranks.withIndex().reversed()) {
                val off = (j - (ranks.size - 1) / 2f) * gap
                val f2 = RectF(from)
                f2.offset(px * off, py * off)
                val t2 = RectF(to)
                t2.offset(px * off, py * off)
                drawPremove(c, f2, t2, guessColor(rank), "${rank + 1}", tint = j == 0, badgeSlot = j, realTo = to)
            }
        }
    }

    /** one premove arrow. badgeSlot: its number badge goes next to the others of the same move (realTo = the square) */
    private fun drawPremove(c: Canvas, from: RectF, to: RectF, col: Int, label: String, tint: Boolean, badgeSlot: Int, realTo: RectF) {
        val sq = to.width()
        if (tint) {
            fill.color = Theme.withAlpha(col, (55 * drawP).toInt())
            c.drawRoundRect(realTo, sq * 0.12f, sq * 0.12f, fill)
        }
        arrow(c, from, to, col, sq * 0.075f * look.arrowSize, 0.85f, true)
        val r = sq * 0.14f * drawP
        if (r > 1f) {
            text.textSize = r * 1.3f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            val w = r * 2f
            val bx = realTo.left + sq * 0.06f + w / 2f + badgeSlot * (w + sq * 0.03f)
            val by = realTo.top + sq * 0.06f + r
            fill.color = Color.argb(150, 0, 0, 0)
            c.drawRoundRect(bx - w / 2f - 1.5f * d, by - r - 1.5f * d, bx + w / 2f + 1.5f * d, by + r + 1.5f * d, r + 1.5f * d, r + 1.5f * d, fill)
            fill.color = col
            c.drawRoundRect(bx - w / 2f, by - r, bx + w / 2f, by + r, r, r, fill)
            text.color = Color.rgb(12, 14, 22)
            c.drawText(label, bx, by + r * 0.4f, text)
            text.color = Color.WHITE
            text.isFakeBoldText = false
        }
    }

    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * the label of the move that was just played, in the corner of the square it went to.
     * a brilliant move glows bright blue and a great move deep blue: a shimmer sweeps over the piece and little
     * squares float up. everything stays inside the 3x3 squares around it (the board reader knows about those)
     */
    private fun drawLabelBadge(c: Canvas, b: BadgeModel) {
        val sq = b.rect.width()
        val lab = b.label
        val t = (SystemClock.uptimeMillis() - badgeStart) / 1000f
        var moving = false
        if (lab.glows && look.anim) moving = Enchant.draw(c, b.rect, lab, t, null, loop = false)
        if (lab == Label.BLUNDER) moving = Doom.landed(c, b.rect, t, look.anim)

        val pop = ease(t / 0.22f)
        val r = sq * 0.2f * (0.4f + 0.6f * pop)
        val cx = b.rect.left + r * 0.9f
        val cy = b.rect.top + r * 0.9f
        fill.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(cx + 1.5f * d, cy + 1.5f * d, r * 1.1f, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r * 1.1f, fill)
        fill.color = lab.color
        c.drawCircle(cx, cy, r, fill)
        if (lab.glows && look.anim && t < Enchant.SHOW) Enchant.badgeShine(c, cx, cy, r, lab, t)
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = true
        text.textSize = r * (if (lab.symbol.length > 1) 1.05f else 1.3f)
        text.color = if (lab == Label.INACCURACY) Color.rgb(60, 50, 0) else Color.WHITE
        c.drawText(lab.symbol, cx, cy + r * 0.42f, text)
        text.isFakeBoldText = false
        text.color = Color.WHITE
        if (moving || pop < 1f) postInvalidateOnAnimation()
    }

    private fun badge(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        if (neon) {
            fill.color = Theme.withAlpha(col, (60 * glow).toInt())
            c.drawCircle(cx, cy, r * 1.55f, fill)
        }
        fill.color = Color.argb(235, 255, 255, 255)
        c.drawCircle(cx, cy, r * 1.12f, fill)
        fill.color = col
        c.drawCircle(cx, cy, r, fill)
    }

    /** a little skull on a red disc */
    private fun skull(c: Canvas, cx: Float, cy: Float, r: Float) {
        val red = Color.rgb(255, 45, 70)
        if (neon) {
            fill.color = Theme.withAlpha(red, (70 * glow).toInt())
            c.drawCircle(cx, cy, r * 1.6f, fill)
        }
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r * 1.08f, fill)
        fill.color = red
        c.drawCircle(cx, cy, r, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy - r * 0.12f, r * 0.58f, fill)
        c.drawRoundRect(RectF(cx - r * 0.32f, cy + r * 0.2f, cx + r * 0.32f, cy + r * 0.72f), r * 0.1f, r * 0.1f, fill)
        fill.color = red
        c.drawCircle(cx - r * 0.23f, cy - r * 0.1f, r * 0.17f, fill)
        c.drawCircle(cx + r * 0.23f, cy - r * 0.1f, r * 0.17f, fill)
        val nose = Path()
        nose.moveTo(cx, cy + r * 0.04f)
        nose.lineTo(cx - r * 0.09f, cy + r * 0.24f)
        nose.lineTo(cx + r * 0.09f, cy + r * 0.24f)
        nose.close()
        c.drawPath(nose, fill)
        stroke.color = red
        stroke.strokeWidth = r * 0.07f
        stroke.pathEffect = null
        c.drawLine(cx - r * 0.1f, cy + r * 0.45f, cx - r * 0.1f, cy + r * 0.72f, stroke)
        c.drawLine(cx + r * 0.1f, cy + r * 0.45f, cx + r * 0.1f, cy + r * 0.72f, stroke)
    }

    /** who is winning: a bar next to the board (or above it when there is no room on the side) */
    private fun drawEvalBar(c: Canvas, m: OverlayModel, screenW: Float) {
        val share = lastShare + (m.evalShare!! - lastShare) * drawP
        val b = m.board
        val w = 9f * d * look.cardSize
        val gap = 4f * d
        val vertical = b.left >= w + gap * 2
        val rect = if (vertical) RectF(b.left - gap - w, b.top, b.left - gap, b.bottom)
        else RectF(b.left, b.top - gap - w, b.right, b.top - gap)
        val rad = w / 2f
        // outline glow in the accent color
        if (neon) {
            stroke.color = Theme.withAlpha(look.accent, (60 * glow).toInt())
            stroke.strokeWidth = w * 0.9f
            c.drawRoundRect(rect, rad, rad, stroke)
        }
        fill.color = Color.rgb(28, 30, 36)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.color = Color.rgb(240, 242, 245)
        val whiteRect = if (vertical) {
            val hW = rect.height() * share
            if (m.whiteBottom) RectF(rect.left, rect.bottom - hW, rect.right, rect.bottom)
            else RectF(rect.left, rect.top, rect.right, rect.top + hW)
        } else {
            val wW = rect.width() * share
            if (m.whiteBottom) RectF(rect.left, rect.top, rect.left + wW, rect.bottom)
            else RectF(rect.right - wW, rect.top, rect.right, rect.bottom)
        }
        c.save()
        c.clipRect(whiteRect)
        c.drawRoundRect(rect, rad, rad, fill)
        c.restore()
        stroke.color = Theme.withAlpha(look.accent, 220)
        stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(rect, rad, rad, stroke)

        val label = m.evalText ?: return
        text.textSize = 11f * d * look.cardSize
        text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(label) + 10f * d
        val th = 18f * d * look.cardSize
        val whiteWinning = share >= 0.5f
        val (cx, cy) = if (vertical) {
            val atBottom = whiteWinning == m.whiteBottom
            Pair(rect.centerX().coerceAtLeast(tw / 2f + 2f), if (atBottom) rect.bottom + th * 0.75f else rect.top - th * 0.75f)
        } else {
            Pair(rect.centerX(), rect.top - th * 0.75f)
        }
        val pill = RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
        fill.color = if (whiteWinning) Color.rgb(240, 242, 245) else Color.rgb(28, 30, 36)
        c.drawRoundRect(pill, th / 2f, th / 2f, fill)
        stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(pill, th / 2f, th / 2f, stroke)
        text.color = if (whiteWinning) Color.BLACK else Color.WHITE
        c.drawText(label, cx, cy + text.textSize * 0.36f, text)
        text.color = Color.WHITE
    }

    /** the list of moves: rank, move, eval, skull for a blunder. above the board if there is room, else below it */
    private fun drawCard(c: Canvas, m: OverlayModel, screenW: Float) {
        val k = look.cardSize
        val rowH = 34f * d * k
        val pad = 10f * d * k
        val head = if (m.title.isNotEmpty() || m.accuracy != null) 22f * d * k else 0f
        val board = m.board
        val w = minOf(board.width(), screenW - 24f * d).coerceAtLeast(220f * d)
        val guessRows = if (m.moves.isEmpty()) m.guesses.size else 0
        val rows = if (guessRows > 0) guessRows else m.moves.size
        val h = rows * rowH + pad * 2 + head
        val left = (board.left + (board.width() - w) / 2f).coerceIn(8f * d, maxOf(8f * d, screenW - w - 8f * d))
        val evalAbove = m.evalShare != null && board.left < 9f * d * k + 8f * d
        var top = board.top - h - (if (evalAbove) 30f else 14f) * d
        if (top < 56f * d) top = board.bottom + 14f * d
        val slide = (1f - drawP) * 16f * d
        val rect = RectF(left, top + slide, left + w, top + h + slide)
        val radius = 20f * d * k

        // an ios style dark card: soft shadow, near black glass, a hairline edge (and a faint glow for neon)
        if (neon) {
            stroke.color = Theme.withAlpha(look.accent, (34 * glow).toInt())
            stroke.strokeWidth = 6f * d
            c.drawRoundRect(rect, radius, radius, stroke)
        }
        card.color = Color.argb((236 * drawP).toInt(), 28, 28, 30)
        card.setShadowLayer(14f * d, 0f, 4f * d, Color.argb((90 * drawP).toInt(), 0, 0, 0))
        c.drawRoundRect(rect, radius, radius, card)
        card.clearShadowLayer()
        stroke.color = Color.argb((40 * drawP).toInt(), 255, 255, 255)
        stroke.strokeWidth = 0.8f * d
        c.drawRoundRect(rect, radius, radius, stroke)
        if (m.alert != null) {
            // a critical moment: the edge pulses a few times, then stays lit
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val pulse = if (look.anim && t < ALERT_SECONDS) 0.55f + 0.45f * kotlin.math.abs(sin(t * PI.toFloat() * 1.6f)) else 0.75f
            stroke.color = Theme.withAlpha(m.alertColor, (90 * pulse * drawP).toInt())
            stroke.strokeWidth = 7f * d * pulse
            c.drawRoundRect(rect, radius, radius, stroke)
            stroke.color = Theme.withAlpha(m.alertColor, (240 * drawP).toInt())
            stroke.strokeWidth = 2f * d
            c.drawRoundRect(rect, radius, radius, stroke)
            if (look.anim && t < ALERT_SECONDS) postInvalidateOnAnimation()
        }

        var y0 = rect.top + pad
        if (head > 0f) {
            text.textSize = 12.5f * d * k
            text.textAlign = Paint.Align.LEFT
            text.typeface = medium
            text.color = Color.argb(153, 235, 235, 245)
            if (m.alert != null) {
                text.color = m.alertColor
                text.isFakeBoldText = true
                c.drawText(m.alert, rect.left + pad + 4f * d, y0 + head * 0.62f, text)
                text.isFakeBoldText = false
                text.color = Color.argb(153, 235, 235, 245)
            } else {
                c.drawText(m.title, rect.left + pad + 4f * d, y0 + head * 0.62f, text)
            }
            m.accuracy?.let {
                text.textAlign = Paint.Align.RIGHT
                c.drawText(it, rect.right - pad - 4f * d, y0 + head * 0.62f, text)
            }
            text.typeface = regular
            text.color = Color.WHITE
            y0 += head
        }
        // the bot's turn: one row per guess. "Nf6  52%" on the left, your answer to it on the right
        for ((i, gs) in m.guesses.withIndex()) {
            if (guessRows == 0) break
            val cy = y0 + rowH * i + rowH / 2f
            if (i > 0) {
                stroke.color = Color.argb(255, 56, 56, 58)
                stroke.strokeWidth = maxOf(1f, 0.5f * d)
                val ly = y0 + rowH * i
                c.drawLine(rect.left + pad + rowH * 0.95f, ly, rect.right - pad, ly, stroke)
            }
            val dotX = rect.left + pad + rowH * 0.36f
            val gcol = guessColor(i)
            fill.color = gcol
            c.drawCircle(dotX, cy, rowH * 0.3f, fill)
            text.textSize = rowH * 0.4f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.color = Color.rgb(16, 18, 26)
            c.drawText("${i + 1}", dotX, cy + rowH * 0.14f, text)
            text.isFakeBoldText = false
            val tx = rect.left + pad + rowH * 0.95f
            text.textAlign = Paint.Align.LEFT
            text.textSize = rowH * 0.48f
            text.typeface = medium
            text.color = Color.WHITE
            c.drawText(gs.san, tx, cy + rowH * 0.17f, text)
            gs.prob?.let {
                val sx = tx + text.measureText(gs.san) + rowH * 0.25f
                text.typeface = regular
                text.textSize = rowH * 0.4f
                text.color = Color.argb(153, 235, 235, 245)
                c.drawText("${Math.round(it * 100)}%", sx, cy + rowH * 0.15f, text)
            }
            // your premove for this guess, in the same color as its number
            text.typeface = medium
            text.textSize = rowH * 0.46f
            text.textAlign = Paint.Align.RIGHT
            val ans = gs.answer
            if (ans == null) {
                text.color = Color.argb(90, 235, 235, 245)
                c.drawText("…", rect.right - pad - 4f * d, cy + rowH * 0.17f, text)
            } else if (ans.isNotEmpty()) {
                text.color = gcol
                c.drawText("→ $ans", rect.right - pad - 4f * d, cy + rowH * 0.17f, text)
            }
            text.typeface = regular
            text.color = Color.WHITE
        }
        for ((i, mv) in m.moves.sortedBy { it.rank }.withIndex()) {
            val cy = y0 + rowH * i + rowH / 2f
            if (i > 0) {
                // a hairline between rows, starting where the text starts (like an ios list)
                stroke.color = Color.argb(255, 56, 56, 58)
                stroke.strokeWidth = maxOf(1f, 0.5f * d)
                val ly = y0 + rowH * i
                c.drawLine(rect.left + pad + rowH * 0.95f, ly, rect.right - pad, ly, stroke)
            }
            val col = gradeColor(mv)
            val dotX = rect.left + pad + rowH * 0.36f
            if (neon) {
                fill.color = Theme.withAlpha(col, (55 * glow).toInt())
                c.drawCircle(dotX, cy, rowH * 0.42f, fill)
            }
            fill.color = col
            c.drawCircle(dotX, cy, rowH * 0.3f, fill)
            text.textSize = rowH * 0.4f
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = true
            text.color = if (mv.grade == Grade.DECENT || mv.grade == Grade.OK || mv.star) Color.BLACK else Color.WHITE
            c.drawText(if (mv.star) "★" else mv.rank.toString(), dotX, cy + rowH * 0.14f, text)
            text.isFakeBoldText = false
            text.color = Color.WHITE
            text.textSize = rowH * 0.5f
            text.textAlign = Paint.Align.LEFT
            text.typeface = medium
            c.drawText(mv.label, rect.left + pad + rowH * 0.95f, cy + rowH * 0.18f, text)
            text.typeface = regular
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.argb(153, 235, 235, 245)
            val ex = rect.right - pad - (if (mv.grade == Grade.BLUNDER && !mv.star) rowH * 0.9f else 0f)
            c.drawText(mv.eval, ex, cy + rowH * 0.18f, text)
            text.color = Color.WHITE
            if (mv.grade == Grade.BLUNDER && !mv.star) skull(c, rect.right - pad - rowH * 0.34f, cy, rowH * 0.32f)
        }
    }
}
