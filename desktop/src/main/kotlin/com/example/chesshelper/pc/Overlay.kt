package com.example.chesshelper.pc

import com.example.chesshelper.BoardGeom
import com.example.chesshelper.Grade
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** one of your moves to draw */
class MoveArrow(
    val from: String, val to: String, val grade: Int, val rank: Int, val san: String, val eval: String,
    val tag: String = "", val brilliant: Boolean = false
)

/** one of the bot's likely moves, with your premove answer to it (same color, same number) */
class GuessArrow(
    val from: String, val to: String, val san: String, val prob: Float?, val index: Int,
    val ansFrom: String? = null, val ansTo: String? = null, val ansSan: String? = null
)

/** everything the overlay shows */
class View(
    val geom: BoardGeom? = null,
    val whiteBottom: Boolean = true,
    val mine: List<MoveArrow> = emptyList(),
    val guesses: List<GuessArrow> = emptyList(),
    val title: String = "",
    val evalShare: Float? = null,
    val evalText: String? = null
)

/**
 * the see-through window over the whole screen: the arrows sit right on the real board, clicks go through to the
 * game, and screen capture skips it (on windows 10 2004+), so reading the board never sees our own drawing
 */
class Overlay(
    private val bounds: Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
) {
    private val window by lazy { JWindow() }
    @Volatile private var view = View()

    /** true when screen capture can't see the overlay (else it is hidden for a moment while the board is read) */
    var hiddenFromCapture = false
        private set

    private val panel by lazy { object : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            draw(g2, view)
        }
    } }

    fun show() {
        SwingUtilities.invokeAndWait {
            window.background = Color(0, 0, 0, 0)
            window.contentPane = panel
            window.bounds = bounds
            window.isAlwaysOnTop = true
            window.focusableWindowState = false
            window.isVisible = true
            hiddenFromCapture = Win32.makeOverlay(window)
        }
    }

    fun update(v: View) {
        view = v
        panel.repaint()
    }

    fun clear() = update(View())

    /** hides the drawing for a moment (only needed when capture can see it) */
    fun setHidden(hidden: Boolean) {
        if (hiddenFromCapture) return
        SwingUtilities.invokeAndWait { window.isVisible = !hidden }
    }

    /** draws a view onto any picture (used to check the drawing on screenshots) */
    fun paintInto(g2: Graphics2D, v: View) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        draw(g2, v)
    }

    // ------------------------------------------------------------------ drawing

    private fun center(g: BoardGeom, wb: Boolean, sq: String): Pair<Float, Float> {
        val file = sq[0] - 'a'
        val rank = sq[1] - '1'
        val c = if (wb) file else 7 - file
        val r = if (wb) 7 - rank else rank
        val s = g.size / 8f
        // the overlay window starts at the screen's corner
        return Pair(g.left - bounds.x + (c + 0.5f) * s, g.top - bounds.y + (r + 0.5f) * s)
    }

    private fun draw(g2: Graphics2D, v: View) {
        val g = v.geom ?: return
        val s = g.size / 8f
        // your moves, worst first so the best is on top
        for (m in v.mine.sortedByDescending { it.rank }) {
            val (x1, y1) = center(g, v.whiteBottom, m.from)
            val (x2, y2) = center(g, v.whiteBottom, m.to)
            val col = if (m.brilliant) BRILLIANT else gradeColor(m.grade)
            arrow(g2, x1, y1, x2, y2, withAlpha(col, if (m.rank == 1) 225 else 170), s * (if (m.rank == 1) 0.20f else 0.14f), s)
            badge(g2, x2 + s * 0.30f, y2 - s * 0.30f, s * 0.15f, col, if (m.brilliant) "!!" else "${m.rank}")
        }
        // the bot's likely moves (dashed) and your answers to them (solid, same color and number)
        for (gs in v.guesses) {
            val col = GUESS[gs.index % GUESS.size]
            val (x1, y1) = center(g, v.whiteBottom, gs.from)
            val (x2, y2) = center(g, v.whiteBottom, gs.to)
            arrow(g2, x1, y1, x2, y2, withAlpha(col, 190), s * 0.10f, s, dashed = true)
            val label = "${gs.index + 1}" + (gs.prob?.let { " ${(it * 100).toInt()}%" } ?: "")
            tag(g2, x2, y2 - s * 0.36f, s * 0.20f, col, label)
            if (gs.ansFrom != null && gs.ansTo != null) {
                val off = (gs.index - 1) * s * 0.08f // answers that share squares sit side by side
                val (a1, b1) = center(g, v.whiteBottom, gs.ansFrom)
                val (a2, b2) = center(g, v.whiteBottom, gs.ansTo)
                arrow(g2, a1 + off, b1 + off, a2 + off, b2 + off, withAlpha(col, 235), s * 0.12f, s)
                badge(g2, a2 + off + s * 0.30f, b2 + off + s * 0.30f, s * 0.14f, col, "${gs.index + 1}")
            }
        }
        evalBar(g2, g, v)
        card(g2, g, v)
    }

    private fun arrow(g2: Graphics2D, x1: Float, y1: Float, x2: Float, y2: Float, col: Color, width: Float, s: Float, dashed: Boolean = false) {
        val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val head = (s * 0.42f).toDouble().coerceAtMost(len * 0.6)
        val ex = x2 - (cos(ang) * head * 0.8).toFloat()
        val ey = y2 - (sin(ang) * head * 0.8).toFloat()
        g2.color = col
        g2.stroke = if (dashed) BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(width * 1.6f, width * 1.4f), 0f)
            else BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(java.awt.geom.Line2D.Float(x1, y1, ex, ey))
        val p = Path2D.Float()
        val hw = head * 0.55
        p.moveTo(x2.toDouble(), y2.toDouble())
        p.lineTo(x2 - cos(ang) * head + cos(ang + Math.PI / 2) * hw, y2 - sin(ang) * head + sin(ang + Math.PI / 2) * hw)
        p.lineTo(x2 - cos(ang) * head - cos(ang + Math.PI / 2) * hw, y2 - sin(ang) * head - sin(ang + Math.PI / 2) * hw)
        p.closePath()
        g2.fill(p)
    }

    private fun badge(g2: Graphics2D, x: Float, y: Float, r: Float, col: Color, text: String) {
        g2.color = col
        g2.fill(java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
        g2.color = Color(0, 0, 0, 120)
        g2.stroke = BasicStroke(r * 0.12f)
        g2.draw(java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
        g2.font = Font("SansSerif", Font.BOLD, (r * 1.2f).toInt().coerceAtLeast(9))
        val fm = g2.fontMetrics
        g2.color = if (lum(col) > 0.55) INK else IVORY
        g2.drawString(text, x - fm.stringWidth(text) / 2f, y + fm.ascent * 0.38f)
    }

    private fun tag(g2: Graphics2D, x: Float, y: Float, h: Float, col: Color, text: String) {
        g2.font = Font("SansSerif", Font.BOLD, (h * 0.7f).toInt().coerceAtLeast(9))
        val fm = g2.fontMetrics
        val w = fm.stringWidth(text) + h * 0.6f
        g2.color = withAlpha(col, 230)
        g2.fill(RoundRectangle2D.Float(x - w / 2, y - h / 2, w, h, h, h))
        g2.color = INK
        g2.drawString(text, x - fm.stringWidth(text) / 2f, y + fm.ascent * 0.36f)
    }

    private fun evalBar(g2: Graphics2D, g: BoardGeom, v: View) {
        val share = v.evalShare ?: return
        val bw = (g.size / 8f * 0.14f).coerceAtLeast(7f)
        val left = g.left - bounds.x
        val x = if (left > bw + 6) left - bw - 4f else left + 2f
        val top = (g.top - bounds.y).toFloat()
        val h = g.size.toFloat()
        g2.color = Color(30, 30, 30, 220)
        g2.fill(RoundRectangle2D.Float(x, top, bw, h, bw, bw))
        // white's part at white's side of the board
        val wh = h * share
        g2.color = Color(240, 236, 226, 235)
        if (v.whiteBottom) g2.fill(RoundRectangle2D.Float(x, top + h - wh, bw, wh, bw, bw))
        else g2.fill(RoundRectangle2D.Float(x, top, bw, wh, bw, bw))
    }

    private fun card(g2: Graphics2D, g: BoardGeom, v: View) {
        if (v.mine.isEmpty() && v.guesses.isEmpty() && v.title.isEmpty()) return
        val s = g.size / 8f
        val w = (s * 2.6f).coerceIn(190f, 320f)
        val rowH = (s * 0.42f).coerceIn(22f, 34f)
        val rows = if (v.mine.isNotEmpty()) v.mine.size else v.guesses.size
        val h = rowH * (rows + 1) + 14f
        val left = g.left - bounds.x
        val right = left + g.size
        // beside the board where there's room, else over its top left corner
        val x = when {
            right + w + 16 < bounds.width -> right + 12f
            left > w + 28 -> left - w - 24f
            else -> left + 6f
        }
        val y = (g.top - bounds.y).toFloat()
        g2.color = CARD
        g2.fill(RoundRectangle2D.Float(x, y, w, h, 16f, 16f))
        g2.color = BRASS
        g2.stroke = BasicStroke(1.2f)
        g2.draw(RoundRectangle2D.Float(x, y, w, h, 16f, 16f))
        g2.font = Font("Serif", Font.BOLD, (rowH * 0.55f).toInt())
        g2.color = IVORY
        g2.drawString(v.title, x + 12f, y + rowH * 0.78f)
        var ry = y + rowH * 1.1f
        g2.font = Font("SansSerif", Font.PLAIN, (rowH * 0.5f).toInt())
        if (v.mine.isNotEmpty()) for (m in v.mine) {
            val col = if (m.brilliant) BRILLIANT else gradeColor(m.grade)
            badge(g2, x + 22f, ry + rowH / 2, rowH * 0.34f, col, if (m.brilliant) "!!" else "${m.rank}")
            g2.font = Font("SansSerif", Font.BOLD, (rowH * 0.5f).toInt())
            g2.color = IVORY
            g2.drawString(m.san + m.tag, x + 42f, ry + rowH * 0.66f)
            g2.font = Font("SansSerif", Font.PLAIN, (rowH * 0.48f).toInt())
            g2.color = DIM
            val fm = g2.fontMetrics
            g2.drawString(m.eval, x + w - 12f - fm.stringWidth(m.eval), ry + rowH * 0.66f)
            ry += rowH
        } else for (gs in v.guesses) {
            val col = GUESS[gs.index % GUESS.size]
            badge(g2, x + 22f, ry + rowH / 2, rowH * 0.34f, col, "${gs.index + 1}")
            g2.font = Font("SansSerif", Font.BOLD, (rowH * 0.48f).toInt())
            g2.color = IVORY
            val txt = gs.san + (gs.prob?.let { "  ${(it * 100).toInt()}%" } ?: "") + (gs.ansSan?.let { "  → $it" } ?: "")
            g2.drawString(txt, x + 42f, ry + rowH * 0.66f)
            ry += rowH
        }
    }

    companion object {
        val IVORY = Color(240, 232, 213)
        val DIM = Color(240, 232, 213, 176)
        val INK = Color(22, 38, 31)
        val CARD = Color(15, 46, 37, 236)
        val BRASS = Color(210, 173, 91)
        val BRILLIANT = Color(30, 167, 255)
        val GUESS = listOf(Color(90, 200, 250), Color(255, 159, 10), Color(191, 90, 242))

        fun gradeColor(grade: Int): Color = when (grade) {
            Grade.BEST -> Color(92, 200, 120)
            Grade.DECENT -> Color(170, 214, 92)
            Grade.OK -> Color(240, 196, 64)
            else -> Color(226, 90, 70)
        }

        fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)
        fun lum(c: Color) = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0
    }
}
