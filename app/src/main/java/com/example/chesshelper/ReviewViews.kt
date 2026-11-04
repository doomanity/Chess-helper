package com.example.chesshelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun alpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

/**
 * the board of the review: a position, the last move, the label badge, and an eval bar on the left.
 * a brilliant move glows bright blue, a great move deep blue: a shimmer sweeps over the piece (like an enchanted item)
 * and little blue squares float up from it.
 */
class ReviewBoardView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = Array(8) { CharArray(8) { '.' } } // row 0 = rank 8
    private var fromIdx = -1 // row * 8 + file, row 0 = rank 8
    private var toIdx = -1
    private var label: Label? = null
    private var share = 0.5f
    private var startedAt = 0L

    var flipped = false
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightSq = Color.rgb(235, 218, 188)
    private val darkSq = Color.rgb(173, 138, 108)

    /** shows a position. uci = the move that led to it (or null), animate = play the glow of a brilliant / great move */
    fun show(fen: String, uci: String?, lab: Label?, whiteShare: Float, animate: Boolean) {
        val rows = fen.substringBefore(' ').split("/")
        for (r in 0 until 8) {
            var c = 0
            for (ch in rows.getOrElse(r) { "" }) {
                if (ch.isDigit()) {
                    for (k in 0 until (ch - '0')) if (c < 8) grid[r][c++] = '.'
                } else if (c < 8) {
                    grid[r][c++] = ch
                }
            }
            while (c < 8) grid[r][c++] = '.'
        }
        fromIdx = -1
        toIdx = -1
        if (uci != null && uci.length >= 4) {
            val f1 = uci[0] - 'a'
            val r1 = uci[1] - '1'
            val f2 = uci[2] - 'a'
            val r2 = uci[3] - '1'
            if (f1 in 0..7 && r1 in 0..7 && f2 in 0..7 && r2 in 0..7) {
                fromIdx = (7 - r1) * 8 + f1
                toIdx = (7 - r2) * 8 + f2
            }
        }
        label = lab
        share = whiteShare
        if (animate) startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w - BAR * d).toInt())
    }

    private fun glyph(ch: Char): String {
        val g = when (ch.lowercaseChar()) {
            'k' -> "♚"
            'q' -> "♛"
            'r' -> "♜"
            'b' -> "♝"
            'n' -> "♞"
            else -> "♟"
        }
        return g + "︎" // text style, not the emoji picture
    }

    private fun drawPiece(c: Canvas, ch: Char, cx: Float, cy: Float, sq: Float, paint: Paint?) {
        text.textSize = sq * 0.86f
        text.typeface = Typeface.DEFAULT
        val fm = text.fontMetrics
        val y = cy - (fm.ascent + fm.descent) / 2f
        val g = glyph(ch)
        if (paint != null) {
            paint.textSize = text.textSize
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            c.drawText(g, cx, y, paint)
            return
        }
        val white = ch.isUpperCase()
        stroke.textSize = text.textSize
        stroke.textAlign = Paint.Align.CENTER
        stroke.typeface = Typeface.DEFAULT
        stroke.strokeWidth = sq * 0.035f
        stroke.color = if (white) Color.rgb(25, 25, 25) else Color.rgb(240, 240, 240)
        text.color = if (white) Color.rgb(252, 252, 252) else Color.rgb(38, 38, 44)
        c.drawText(g, cx, y, text)
        c.drawText(g, cx, y, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        val bar = BAR * d
        val bs = width - bar // size of the board
        val sq = bs / 8f
        if (bs <= 0f) return

        // eval bar
        fill.color = Color.rgb(44, 44, 52)
        canvas.drawRect(0f, 0f, bar - 3f * d, bs, fill)
        fill.color = Color.WHITE
        val wh = bs * share
        if (!flipped) canvas.drawRect(0f, bs - wh, bar - 3f * d, bs, fill) else canvas.drawRect(0f, 0f, bar - 3f * d, wh, fill)

        canvas.save()
        canvas.translate(bar, 0f)

        val lab = label
        val tint = lab?.color ?: Color.rgb(246, 220, 80)
        for (sr in 0 until 8) for (sc in 0 until 8) {
            val row = if (flipped) 7 - sr else sr
            val col = if (flipped) 7 - sc else sc
            val x = sc * sq
            val y = sr * sq
            fill.color = if ((row + col) % 2 == 0) lightSq else darkSq
            canvas.drawRect(x, y, x + sq + 1f, y + sq + 1f, fill)
            val idx = row * 8 + col
            if (idx == fromIdx || idx == toIdx) {
                fill.color = alpha(tint, 105)
                canvas.drawRect(x, y, x + sq + 1f, y + sq + 1f, fill)
            }
        }
        // coordinates
        text.textSize = sq * 0.17f
        text.typeface = Typeface.DEFAULT_BOLD
        for (k in 0 until 8) {
            val fileCol = if (flipped) 7 - k else k            // the file written under column k
            val bottomRow = if (flipped) 0 else 7
            text.color = if ((bottomRow + fileCol) % 2 == 0) darkSq else lightSq
            canvas.drawText(('a' + fileCol).toString(), k * sq + sq * 0.86f, bs - sq * 0.07f, text)
            val rankRow = if (flipped) 7 - k else k            // the rank written next to screen row k
            val leftCol = if (flipped) 7 else 0
            text.color = if ((rankRow + leftCol) % 2 == 0) darkSq else lightSq
            canvas.drawText((8 - rankRow).toString(), sq * 0.13f, k * sq + sq * 0.22f, text)
        }
        // pieces
        for (sr in 0 until 8) for (sc in 0 until 8) {
            val row = if (flipped) 7 - sr else sr
            val col = if (flipped) 7 - sc else sc
            val ch = grid[row][col]
            if (ch == '.') continue
            drawPiece(canvas, ch, sc * sq + sq / 2f, sr * sq + sq / 2f, sq, null)
        }

        var animating = false
        if (lab != null && toIdx >= 0) {
            val trow = toIdx / 8
            val tcol = toIdx % 8
            val sr = if (flipped) 7 - trow else trow
            val sc = if (flipped) 7 - tcol else tcol
            val left = sc * sq
            val top = sr * sq
            if (lab.glows) animating = enchant(canvas, lab, grid[trow][tcol], left, top, sq)
            badge(canvas, lab, left + sq, top, sq)
        }
        canvas.restore()
        if (animating) postInvalidateOnAnimation()
    }

    /** the glow on the piece that made a brilliant (bright blue) or great (deep blue) move. true while it is still moving */
    private fun enchant(c: Canvas, lab: Label, piece: Char, left: Float, top: Float, sq: Float): Boolean {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val bright = if (lab == Label.BRILLIANT) Color.rgb(0, 229, 255) else Color.rgb(68, 98, 255)
        val deep = if (lab == Label.BRILLIANT) Color.rgb(30, 110, 255) else Color.rgb(24, 32, 150)
        val fade = if (t < 2.6f) 1f else max(0.4f, 1f - (t - 2.6f) / 0.8f)
        val pulse = if (t < 3.4f) 0.6f + 0.4f * sin(t * 5.5f) else 0.6f
        val cx = left + sq / 2f
        val cy = top + sq / 2f

        // a soft glow around the square
        glow.shader = RadialGradient(cx, cy, sq * 0.95f, intArrayOf(alpha(bright, (170 * pulse * fade).toInt()), alpha(bright, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, sq * 0.95f, glow)
        glow.shader = null

        if (piece != '.') {
            // the piece gets a blue tint, and a bright band sweeps over it from corner to corner
            fill.color = alpha(deep, (120 * pulse * fade).toInt())
            drawPiece(c, piece, cx, cy, sq, fill)
            val sweep = (t * 0.85f) % 1.4f
            val x0 = left - sq * 0.5f + sweep * sq * 1.7f
            val band = Paint(Paint.ANTI_ALIAS_FLAG)
            band.shader = LinearGradient(
                x0, top, x0 + sq * 0.5f, top + sq * 0.5f,
                intArrayOf(alpha(bright, 0), alpha(Color.WHITE, (235 * fade).toInt()), alpha(bright, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            drawPiece(c, piece, cx, cy, sq, band)
        }

        // little squares floating up, like the particles around an enchanting table
        for (k in 0 until 9) {
            val ph = (t * 0.55f + k * 0.113f) % 1f
            val px = cx + sin(k * 2.4f + t * 1.7f) * sq * 0.5f
            val py = top + sq * 1.05f - ph * sq * 1.5f
            val a = (1f - ph) * fade
            val s = sq * 0.065f * (1f - ph * 0.5f)
            fill.color = alpha(if (k % 2 == 0) bright else Color.WHITE, (235 * a).toInt())
            c.drawRect(px - s, py - s, px + s, py + s, fill)
        }
        return t < 3.4f
    }

    /** the round badge in the corner of the square the piece went to */
    private fun badge(c: Canvas, lab: Label, right: Float, top: Float, sq: Float) {
        val r = sq * 0.25f
        val cx = right - r * 0.8f
        val cy = top + r * 0.8f
        fill.color = Color.argb(70, 0, 0, 0)
        c.drawCircle(cx + 1.5f * d, cy + 1.5f * d, r, fill)
        fill.color = lab.color
        c.drawCircle(cx, cy, r, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = max(1f, sq * 0.02f)
        c.drawCircle(cx, cy, r, stroke)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = r * (if (lab.symbol.length > 1) 1.05f else 1.25f)
        text.color = if (lab == Label.INACCURACY) Color.rgb(60, 50, 0) else Color.WHITE
        val fm = text.fontMetrics
        c.drawText(lab.symbol, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    companion object {
        const val BAR = 12f // width of the eval bar, in dp
    }
}

/** the eval graph: the white area is how well white is doing over the game. tap or drag to jump to a move */
class EvalGraphView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var scores: List<Int> = emptyList()
    private var labels: List<Label> = emptyList()
    private var current = 0
    var accent = Color.rgb(0, 229, 255)
    var onSeek: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()

    /** scores = the evaluation of every position (white's side). labels = the label of every move (ply 1 first) */
    fun set(scores: List<Int>, labels: List<Label>, current: Int) {
        this.scores = scores
        this.labels = labels
        this.current = current
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (84 * d).toInt())
    }

    private fun xOf(i: Int, w: Float): Float {
        val total = max(1, scores.size - 1)
        return PAD * d + (w - 2 * PAD * d) * i / total
    }

    private fun yOf(score: Int, h: Float): Float = h * (1f - ReviewFmt.whiteShare(score))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 12f * d, 12f * d, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = Color.rgb(48, 46, 54)
        canvas.drawRect(0f, 0f, w, h, fill)
        if (scores.size >= 2) {
            val area = Path()
            area.moveTo(xOf(0, w), h)
            area.lineTo(xOf(0, w), yOf(scores[0], h))
            for (i in 1 until scores.size) area.lineTo(xOf(i, w), yOf(scores[i], h))
            area.lineTo(xOf(scores.size - 1, w), h)
            area.close()
            fill.color = Color.rgb(236, 236, 240)
            canvas.drawPath(area, fill)
            line.color = Color.argb(90, 120, 120, 130)
            line.strokeWidth = 1f * d
            canvas.drawLine(0f, h / 2f, w, h / 2f, line)
            // dots for the moves worth looking at
            for ((k, lab) in labels.withIndex()) {
                val i = k + 1
                if (i >= scores.size) break
                if (lab == Label.BRILLIANT || lab == Label.GREAT || lab == Label.MISTAKE || lab == Label.MISS || lab == Label.BLUNDER) {
                    fill.color = lab.color
                    canvas.drawCircle(xOf(i, w), yOf(scores[i], h), 3.6f * d, fill)
                }
            }
            // where we are
            line.color = accent
            line.strokeWidth = 2f * d
            val cx = xOf(current.coerceIn(0, scores.size - 1), w)
            canvas.drawLine(cx, 0f, cx, h, line)
        } else {
            fill.color = Color.argb(120, 255, 255, 255)
            fill.textSize = 13f * d
            fill.textAlign = Paint.Align.CENTER
            canvas.drawText("the graph shows up once the game is analyzed", w / 2f, h / 2f, fill)
            fill.textAlign = Paint.Align.LEFT
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scores.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width.toFloat()
                val total = scores.size - 1
                val t = ((e.x - PAD * d) / max(1f, w - 2 * PAD * d)).coerceIn(0f, 1f)
                val idx = min(total, max(0, (t * total).roundToInt()))
                if (idx != current) {
                    current = idx
                    invalidate()
                    onSeek?.invoke(idx)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    companion object {
        const val PAD = 6f
    }
}
