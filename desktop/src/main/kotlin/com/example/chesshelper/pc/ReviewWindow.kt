package com.example.chesshelper.pc

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.math.atan2

/**
 * the review of a finished game: your accuracy, your mistakes and blunders, and for every move the board with what
 * you played and what stockfish liked better. stockfish goes over the game in the background while it opens
 */
class ReviewWindow(private val game: GameRecord, private val enginePath: String) {
    private val felt = Color(0x0F2E25)
    private val cell = Color(0x173F33)
    private val ivory = Color(0xF0E8D5)
    private val dim = Color(0xADB3A6)
    private val brass = Color(0xD2AD5B)

    private val frame = JFrame("Game review")
    private val board = BoardView()
    private val moveLine = JLabel(" ")
    private val progress = JProgressBar(0, 100)
    private val accMe = JLabel("…")
    private val accThem = JLabel(" ")
    private val counts = JLabel(" ")
    private val list = JList<MoveReview>()
    private var result: ReviewResult? = null
    @Volatile private var closed = false

    fun show() {
        val root = JPanel(BorderLayout(16, 0))
        root.background = felt
        root.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        // ---- left: the board and the move under it
        val left = JPanel(BorderLayout(0, 10))
        left.isOpaque = false
        board.preferredSize = Dimension(440, 440)
        left.add(board, BorderLayout.CENTER)
        val under = JPanel()
        under.layout = BoxLayout(under, BoxLayout.Y_AXIS)
        under.isOpaque = false
        moveLine.foreground = ivory
        moveLine.font = Font("SansSerif", Font.PLAIN, 14)
        under.add(leftAlign(moveLine))
        under.add(Box.createVerticalStrut(8))
        val nav = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        nav.isOpaque = false
        val prev = button("◀  back") { step(-1) }
        val next = button("next  ▶") { step(1) }
        val nextMistake = button("next mistake") { nextBad() }
        nav.add(prev); nav.add(Box.createHorizontalStrut(6)); nav.add(next)
        nav.add(Box.createHorizontalStrut(6)); nav.add(nextMistake)
        under.add(leftAlign(nav))
        left.add(under, BorderLayout.SOUTH)
        root.add(left, BorderLayout.CENTER)

        // ---- right: the scores and the move list
        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.isOpaque = false
        right.preferredSize = Dimension(290, 440)
        val title = JLabel("Game review")
        title.font = Font("Georgia", Font.BOLD, 24)
        title.foreground = ivory
        right.add(leftAlign(title))
        right.add(Box.createVerticalStrut(10))
        val accTitle = JLabel("your accuracy")
        accTitle.foreground = dim
        right.add(leftAlign(accTitle))
        accMe.font = Font("Georgia", Font.BOLD, 44)
        accMe.foreground = brass
        right.add(leftAlign(accMe))
        accThem.foreground = dim
        right.add(leftAlign(accThem))
        right.add(Box.createVerticalStrut(8))
        counts.foreground = ivory
        right.add(leftAlign(counts))
        right.add(Box.createVerticalStrut(10))
        progress.isStringPainted = true
        progress.string = "stockfish is going over the game..."
        right.add(leftAlign(progress))
        right.add(Box.createVerticalStrut(10))

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = cell
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, sel: Boolean, foc: Boolean): Component {
                val c = super.getListCellRendererComponent(l, v, i, sel, foc) as JLabel
                val m = v as MoveReview
                val no = moveNumber(m.ply)
                val who = if (m.mine) "" else "   "
                val flag = when (m.kind) {
                    Kind.BEST, Kind.GOOD -> ""
                    else -> "   ${m.kind.label}" + (m.bestSan?.let { ", best $it" } ?: "")
                }
                c.text = "$who$no ${m.san}$flag"
                c.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                c.font = Font("SansSerif", if (m.mine) Font.BOLD else Font.PLAIN, 13)
                if (!sel) {
                    c.background = cell
                    c.foreground = if (m.kind == Kind.BEST || m.kind == Kind.GOOD) (if (m.mine) ivory else dim) else Color(m.kind.rgb)
                }
                return c
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        val scroll = JScrollPane(list)
        scroll.border = null
        scroll.alignmentX = Component.LEFT_ALIGNMENT
        right.add(scroll)
        root.add(right, BorderLayout.EAST)

        // arrow keys step through the game
        val im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "back")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next")
        root.actionMap.put("back", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = step(-1) })
        root.actionMap.put("next", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = step(1) })

        frame.contentPane.add(root)
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) { closed = true }
        })
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        // the board reader never mistakes this window's board for the real one
        Win32.hideFromCapture(frame)

        board.fen = game.fens.last()
        moveLine.text = "${game.moves.size} moves · saved in ${Store.games.path}"
        analyse()
    }

    private fun analyse() {
        Thread {
            val r = try {
                Reviewer.run(game, enginePath, 250) { i, n ->
                    if (closed) throw InterruptedException()
                    SwingUtilities.invokeLater { progress.value = i * 100 / n.coerceAtLeast(1) }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { progress.string = "the review didn't work: ${e.message ?: e::class.java.simpleName}" }
                return@Thread
            }
            SwingUtilities.invokeLater { showResult(r) }
        }.apply { isDaemon = true; start() }
    }

    private fun showResult(r: ReviewResult) {
        result = r
        progress.isVisible = false
        accMe.text = String.format(java.util.Locale.US, "%.1f", r.accuracy(true))
        accThem.text = String.format(java.util.Locale.US, "opponent %.1f", r.accuracy(false))
        fun n(k: Kind, one: String, many: String): String {
            val c = r.count(true, k)
            return "<span style='color:#${hex(k)}'>$c ${if (c == 1) one else many}</span>"
        }
        counts.text = "<html>${n(Kind.BLUNDER, "blunder", "blunders")} · ${n(Kind.MISTAKE, "mistake", "mistakes")} · " +
            "${n(Kind.INACCURACY, "inaccuracy", "inaccuracies")}</html>"
        list.setListData(r.moves.toTypedArray())
        // start at your first big mistake (or the first move when there is none)
        val first = r.moves.indexOfFirst { it.mine && (it.kind == Kind.BLUNDER || it.kind == Kind.MISTAKE) }
        list.selectedIndex = if (first >= 0) first else 0
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun hex(k: Kind) = String.format("%06X", k.rgb)

    private fun moveNumber(ply: Int): String {
        val blackFirst = game.startFen.split(" ").getOrNull(1) == "b"
        val firstNo = game.startFen.split(" ").getOrNull(5)?.toIntOrNull() ?: 1
        val p = ply + if (blackFirst) 1 else 0
        return "${firstNo + p / 2}." + if (p % 2 == 1) ".." else ""
    }

    private fun step(d: Int) {
        val r = result ?: return
        val i = (list.selectedIndex + d).coerceIn(0, r.moves.size - 1)
        list.selectedIndex = i
        list.ensureIndexIsVisible(i)
    }

    private fun nextBad() {
        val r = result ?: return
        val from = list.selectedIndex + 1
        val bad = (from until r.moves.size).firstOrNull { r.moves[it].mine && r.moves[it].kind.ordinal >= Kind.INACCURACY.ordinal }
            ?: (0 until from).firstOrNull { r.moves[it].mine && r.moves[it].kind.ordinal >= Kind.INACCURACY.ordinal }
        if (bad != null) {
            list.selectedIndex = bad
            list.ensureIndexIsVisible(bad)
        }
    }

    private fun showSelected() {
        val m = list.selectedValue ?: return
        // the position before the move, with what you played and what was best drawn on it
        board.fen = game.fens[m.ply]
        board.played = m.uci
        board.best = if (m.best != null && m.best != m.uci) m.best else null
        board.playedColor = Color(m.kind.rgb)
        board.repaint()
        val who = if (m.mine) "you" else "opponent"
        moveLine.text = "<html>${moveNumber(m.ply)} ${m.san} ($who) · <span style='color:#${hex(m.kind)}'>${m.kind.label}</span>" +
            (if (board.best != null) " · best was <b>${m.bestSan}</b>" else "") + " · eval after ${m.evalAfter}</html>"
    }

    private fun button(text: String, act: () -> Unit): JButton {
        val b = JButton(text)
        b.isFocusPainted = false
        b.isFocusable = false
        b.addActionListener { act() }
        return b
    }

    private fun leftAlign(c: JComponent): JComponent {
        c.alignmentX = Component.LEFT_ALIGNMENT
        return c
    }

    /** a plain board: the position, the move played (its square tint shows how good it was), the best move's arrow */
    private inner class BoardView : JPanel() {
        var fen: String = Board().fen
        var played: String? = null
        var best: String? = null
        var playedColor: Color = brass
        private val light = Color(0xE6DABD)
        private val dark = Color(0x3E7A5D)
        private val pieceFont = Font("Segoe UI Symbol", Font.PLAIN, 10)

        init { isOpaque = false }

        private fun cellOf(sq: String, size: Int): Pair<Int, Int> {
            val f = sq[0] - 'a'
            val r = sq[1] - '1'
            return if (game.meWhite) Pair(f * size, (7 - r) * size) else Pair((7 - f) * size, r * size)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val size = minOf(width, height) / 8
            for (r in 0 until 8) for (f in 0 until 8) {
                g.color = if ((r + f) % 2 == 0) light else dark
                g.fillRect(f * size, r * size, size, size)
            }
            played?.let { mv ->
                if (mv.length >= 4) for (sq in listOf(mv.substring(0, 2), mv.substring(2, 4))) {
                    val (x, y) = cellOf(sq, size)
                    g.color = Color(playedColor.red, playedColor.green, playedColor.blue, 130)
                    g.fillRect(x, y, size, size)
                }
            }
            val shownFen = fen
            val b = Board().apply { try { loadFromFen(shownFen) } catch (_: Exception) {} }
            val font = pieceFont.deriveFont(size * 0.78f)
            for (sq in Square.values()) {
                if (sq == Square.NONE) continue
                val p = b.getPiece(sq)
                if (p == Piece.NONE) continue
                val (x, y) = cellOf(sq.value().lowercase(), size)
                val glyph = when (p.pieceType) {
                    PieceType.KING -> "♚"; PieceType.QUEEN -> "♛"; PieceType.ROOK -> "♜"
                    PieceType.BISHOP -> "♝"; PieceType.KNIGHT -> "♞"; else -> "♟"
                }
                val gv = font.createGlyphVector(g.fontRenderContext, glyph)
                val bounds = gv.visualBounds
                val shape = gv.getOutline((x + (size - bounds.width) / 2 - bounds.x).toFloat(),
                    (y + (size - bounds.height) / 2 - bounds.y).toFloat())
                val white = p.pieceSide == Side.WHITE
                g.color = if (white) Color(0xFAF6EC) else Color(0x1B1B1B)
                g.fill(shape)
                g.stroke = BasicStroke(size / 28f)
                g.color = if (white) Color(0x2A2A2A) else Color(0xD8D0BC)
                g.draw(shape)
            }
            best?.let { mv -> if (mv.length >= 4) arrow(g, mv.substring(0, 2), mv.substring(2, 4), size, Color(0x5CC26B)) }
        }

        private fun arrow(g: Graphics2D, a: String, b: String, size: Int, c: Color) {
            val (x1, y1) = cellOf(a, size)
            val (x2, y2) = cellOf(b, size)
            val sx = x1 + size / 2.0; val sy = y1 + size / 2.0
            val ex = x2 + size / 2.0; val ey = y2 + size / 2.0
            val ang = atan2(ey - sy, ex - sx)
            val head = size * 0.42
            val w = size * 0.16
            val len = Math.hypot(ex - sx, ey - sy) - head
            val p = Path2D.Double()
            p.moveTo(0.0, -w / 2); p.lineTo(len, -w / 2); p.lineTo(len, -head / 2); p.lineTo(len + head, 0.0)
            p.lineTo(len, head / 2); p.lineTo(len, w / 2); p.lineTo(0.0, w / 2); p.closePath()
            val t = AffineTransform()
            t.translate(sx, sy)
            t.rotate(ang)
            g.color = Color(c.red, c.green, c.blue, 200)
            g.fill(t.createTransformedShape(p))
        }
    }
}
