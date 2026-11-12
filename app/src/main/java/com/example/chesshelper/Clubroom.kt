package com.example.chesshelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

/**
 * the clubroom look's own pieces: a small chess board in a brass frame (the main screen's hero shows the last
 * game you played on it) and the hero card around it
 */
class MiniBoard(ctx: Context, private val ui: Ios, fen: String, private val whiteBottom: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val board = Board().apply { try { loadFromFen(fen) } catch (_: Exception) {} }

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val frame = ui.px(5f)
        // the brass frame with a darker inner line
        p.color = ui.brassDeep
        r.set(0f, 0f, w, w)
        c.drawRoundRect(r, ui.px(9f), ui.px(9f), p)
        p.color = ui.brass
        r.set(ui.px(1.5f), ui.px(1.5f), w - ui.px(1.5f), w - ui.px(1.5f))
        c.drawRoundRect(r, ui.px(8f), ui.px(8f), p)
        val inner = w - 2 * frame
        val sq = inner / 8f
        c.save()
        r.set(frame, frame, w - frame, w - frame)
        val path = android.graphics.Path().apply { addRoundRect(r, ui.px(4f), ui.px(4f), android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        for (row in 0 until 8) for (col in 0 until 8) {
            val light = (row + col) % 2 == 0
            p.color = if (light) LIGHT_SQ else DARK_SQ
            val x = frame + col * sq
            val y = frame + row * sq
            c.drawRect(x, y, x + sq, y + sq, p)
            val file = if (whiteBottom) col else 7 - col
            val rank = if (whiteBottom) 7 - row else row
            val piece = board.getPiece(Square.squareAt(rank * 8 + file))
            if (piece != Piece.NONE) drawPiece(c, piece, x + sq / 2, y + sq / 2, sq)
        }
        c.restore()
    }

    /** a piece as a filled glyph: ivory with an ink edge for white, ink with a soft light edge for black */
    private fun drawPiece(c: Canvas, piece: Piece, cx: Float, cy: Float, sq: Float) {
        val ch = when (piece.pieceType) {
            com.github.bhlangonijr.chesslib.PieceType.KING -> "♚"
            com.github.bhlangonijr.chesslib.PieceType.QUEEN -> "♛"
            com.github.bhlangonijr.chesslib.PieceType.ROOK -> "♜"
            com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "♝"
            com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "♞"
            else -> "♟"
        }
        glyph.textSize = sq * 0.86f
        val y = cy + sq * 0.30f
        val white = piece.pieceSide == Side.WHITE
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = sq * 0.07f
        glyph.color = if (white) ui.ink else 0x66F0E8D5
        c.drawText(ch, cx, y, glyph)
        glyph.style = Paint.Style.FILL
        glyph.color = if (white) ui.ivory else ui.ink
        c.drawText(ch, cx, y, glyph)
    }

    companion object {
        val LIGHT_SQ = Color.rgb(233, 223, 198)
        val DARK_SQ = Color.rgb(64, 120, 94)
    }
}

/**
 * the main screen's hero: the board of your last game on the left, what the helper is doing on the right, and the
 * big start (or stop) plate under them
 */
object Hero {
    fun build(ctx: Context, ui: Ios, c: Cfg, running: Boolean, onStart: () -> Unit, onStop: () -> Unit): View {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(ui.dp(16f), ui.dp(16f), ui.dp(16f), ui.dp(16f))
        card.background = ui.round(ui.cell, 22f, Theme.withAlpha(ui.brass, 70))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val last = try { GameHistory.all(ctx).maxByOrNull { it.updated } } catch (_: Exception) { null }
        val fen = last?.let { finalFen(it) } ?: Board().fen
        val bottomWhite = last?.meWhite ?: true
        top.addView(MiniBoard(ctx, ui, fen, bottomWhite), LinearLayout.LayoutParams(ui.dp(124f), ui.dp(124f)))

        val info = LinearLayout(ctx)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(ui.dp(16f), 0, 0, 0)
        val status = LinearLayout(ctx)
        status.orientation = LinearLayout.HORIZONTAL
        status.gravity = Gravity.CENTER_VERTICAL
        val dot = View(ctx)
        dot.background = ui.round(if (running) ui.green else ui.label3, 100f)
        status.addView(dot, LinearLayout.LayoutParams(ui.dp(8f), ui.dp(8f)).apply { rightMargin = ui.dp(7f) })
        status.addView(ui.text(if (running) "helping now" else "ready when you are", 13f, if (running) ui.green else ui.label2, ui.medium))
        info.addView(status)

        val engine = if (c.useMaia) "maia3" else if (c.useIllusion) "illusion" else "stockfish"
        val title = ui.text(engine.replaceFirstChar { it.uppercase() }, 26f, ui.label, ui.serif)
        title.setPadding(0, ui.dp(6f), 0, ui.dp(4f))
        info.addView(title)
        val side = when (c.colorMode) { "white" -> "you play white"; "black" -> "you play black"; else -> "side found by itself" }
        val strength = if (c.useIllusion) (if (c.illusionHuman) "human mode" else "pure engine")
            else if (c.useMaia) "plays like ${c.maiaElo}"
            else if (c.elo >= UciEngine.MAX_ELO) "full power" else "elo ${c.elo}"
        info.addView(ui.text("$side\n$strength", 14f, ui.label2))
        if (last != null) {
            val res = when (last.shownResult) { "win" -> "won"; "loss" -> "lost"; "draw" -> "drew"; else -> "played" }
            val l = ui.text("last game: $res${last.opening?.let { " · $it" } ?: ""}", 12f, ui.label3)
            l.setPadding(0, ui.dp(8f), 0, 0)
            l.maxLines = 2
            info.addView(l)
        }
        top.addView(info, ui.weight())
        card.addView(top)

        val btn = if (running) ui.bigButton("stop helping", ui.red, filled = false) { onStop() }
            else ui.bigButton("start helping", ui.brass) { onStart() }
        card.addView(btn, ui.full(top = 16f))
        return card
    }

    private fun finalFen(g: SavedGame): String? = try {
        val b = Board()
        b.loadFromFen(g.startFen)
        for (m in g.moves) b.doMove(com.github.bhlangonijr.chesslib.move.Move(m, b.sideToMove))
        b.fen
    } catch (_: Exception) {
        null
    }
}
