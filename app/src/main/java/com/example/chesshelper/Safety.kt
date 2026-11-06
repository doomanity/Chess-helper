package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

/**
 * quick piece safety checks (no engine, just who attacks what), for the live board:
 * - hanging(): your pieces the opponent can take for free (or with a cheaper piece)
 * - targets(): when you touch a piece, which of its squares are safe and where it would get taken
 */
object Safety {
    private fun value(t: PieceType?): Int = when (t) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT, PieceType.BISHOP -> 300
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        else -> 0
    }

    private fun sq(name: String): Square = Square.valueOf(name.uppercase())

    /** the cheapest piece of side that attacks square s, or 0 when none does */
    private fun cheapestAttacker(b: Board, s: Square, side: Side): Int {
        val bits = b.squareAttackedBy(s, side)
        if (bits == 0L) return 0
        var best = Int.MAX_VALUE
        for (i in 0 until 64) {
            if (bits and (1L shl i) == 0L) continue
            val p = b.getPiece(Square.squareAt(i))
            val v = if (p.pieceType == PieceType.KING) 10000 else value(p.pieceType)
            if (v < best) best = v
        }
        return best
    }

    /**
     * how much the opponent (side `by`) wins by taking the piece on s right now: the piece's value if nothing
     * defends it, the difference if the cheapest taker is worth less, else 0. a king can only take an undefended piece
     */
    private fun loss(b: Board, s: Square, by: Side): Int {
        val p = b.getPiece(s)
        if (p == Piece.NONE || p.pieceType == PieceType.KING) return 0
        val taker = cheapestAttacker(b, s, by)
        if (taker == 0) return 0
        val defended = b.squareAttackedBy(s, p.pieceSide) != 0L
        if (taker == 10000) return if (defended) 0 else value(p.pieceType) // only the king attacks it
        return if (!defended) value(p.pieceType) else maxOf(0, value(p.pieceType) - taker)
    }

    /** the side to move's pieces (not pawns, not the king) that hang: the opponent wins material taking them */
    fun hanging(fen: String): List<String> {
        val b = Board()
        b.loadFromFen(fen)
        val me = b.sideToMove
        val out = ArrayList<String>()
        for (i in 0 until 64) {
            val s = Square.squareAt(i)
            val p = b.getPiece(s)
            if (p == Piece.NONE || p.pieceSide != me || p.pieceType == PieceType.PAWN || p.pieceType == PieceType.KING) continue
            if (loss(b, s, me.flip()) >= 100) out.add(s.name.lowercase())
        }
        return out
    }

    /**
     * every square the piece on `from` can go to, and whether it is safe there (true) or the opponent wins material
     * by taking it there (false, counting what the move itself took). empty if it isn't the side to move's piece
     */
    fun targets(fen: String, from: String): List<Pair<String, Boolean>> {
        val b = Board()
        b.loadFromFen(fen)
        val me = b.sideToMove
        val f = sq(from)
        val piece = b.getPiece(f)
        if (piece == Piece.NONE || piece.pieceSide != me) return emptyList()
        val seen = LinkedHashMap<String, Boolean>()
        for (m in b.legalMoves()) {
            if (m.from != f) continue
            val to = m.to.name.lowercase()
            val took = value(b.getPiece(m.to).pieceType)
            b.doMove(m)
            val risk = loss(b, m.to, me.flip()) - took
            b.undoMove()
            val safe = risk < 100
            // a promotion has several moves to the same square: safe if any of them is
            seen[to] = (seen[to] ?: false) || safe
        }
        return seen.map { Pair(it.key, it.value) }
    }

    /** the squares (like "e4") the piece on `from` can move to */
    fun moveSquares(fen: String, from: String): Set<String> {
        val b = Board()
        b.loadFromFen(fen)
        val f = sq(from)
        return b.legalMoves().filter { it.from == f }.map { it.to.name.lowercase() }.toSet()
    }
}
