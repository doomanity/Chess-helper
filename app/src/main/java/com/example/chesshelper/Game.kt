package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/** chess bookkeeping: build positions, and follow the game by matching which squares have pieces */
object Game {

    /** checks that a piece placement string looks like a real position */
    fun isValidPlacement(p: String): Boolean {
        val ranks = p.split("/")
        if (ranks.size != 8) return false
        var wk = 0
        var bk = 0
        var wp = 0
        var bp = 0
        var white = 0
        var black = 0
        for ((i, r) in ranks.withIndex()) {
            var n = 0
            for (c in r) {
                if (c in '1'..'8') {
                    n += c - '0'
                } else if ("prnbqkPRNBQK".indexOf(c) >= 0) {
                    n += 1
                    if (c.isUpperCase()) white++ else black++
                    if (c == 'K') wk++
                    if (c == 'k') bk++
                    if (c == 'P') { wp++; if (i == 0 || i == 7) return false }
                    if (c == 'p') { bp++; if (i == 0 || i == 7) return false }
                } else {
                    return false
                }
            }
            if (n != 8) return false
        }
        return wk == 1 && bk == 1 && wp <= 8 && bp <= 8 && white <= 16 && black <= 16
    }

    private fun grid(p: String): List<String> = p.split("/").map { r ->
        buildString {
            for (c in r) {
                if (c in '1'..'8') repeat(c - '0') { append('.') } else append(c)
            }
        }
    }

    /** a screenshot can't show castling rights, so guess: king and rook still on their home squares */
    fun guessCastling(p: String): String {
        val g = grid(p)
        var s = ""
        if (g[7][4] == 'K') {
            if (g[7][7] == 'R') s += "K"
            if (g[7][0] == 'R') s += "Q"
        }
        if (g[0][4] == 'k') {
            if (g[0][7] == 'r') s += "k"
            if (g[0][0] == 'r') s += "q"
        }
        return if (s.isEmpty()) "-" else s
    }

    fun newBoard(placement: String, whiteToMove: Boolean): Board {
        val b = Board()
        val side = if (whiteToMove) "w" else "b"
        b.loadFromFen("$placement $side ${guessCastling(placement)} - 0 1")
        return b
    }

    /** a move in the usual short notation (Nf3, exd5, O-O). falls back to the plain move like g1f3 */
    fun san(fen: String, uci: String): String {
        return try {
            val b = Board()
            b.loadFromFen(fen)
            val mv = b.legalMoves().firstOrNull { it.toString() == uci } ?: return uci
            val list = com.github.bhlangonijr.chesslib.move.MoveList(fen)
            list.add(mv)
            list.toSanArray()[0]
        } catch (e: Exception) {
            uci
        }
    }

    /** the position after a move like "e2e4" (fen), or null if it isn't a legal move there */
    fun after(fen: String, uci: String): String? {
        return try {
            val b = Board()
            b.loadFromFen(fen)
            val mv = b.legalMoves().firstOrNull { it.toString() == uci } ?: return null
            b.doMove(mv)
            b.fen
        } catch (e: Exception) {
            null
        }
    }

    /** the part of a fen that says what the position is (pieces, side to move, castling, en passant) */
    /**
     * the same fen with the piece on square sq (0 = a1 .. 63 = h8) changed to another type (2 knight, 3 bishop,
     * 4 rook, 5 queen), same color. null if there is no piece there
     */
    fun withPieceType(fen: String, sq: Int, type: Int): String? {
        val parts = fen.split(" ")
        val rows = parts[0].split("/").map { r ->
            val sb = StringBuilder()
            for (ch in r) if (ch.isDigit()) repeat(ch - '0') { sb.append('.') } else sb.append(ch)
            sb
        }
        if (rows.size != 8) return null
        val row = rows[7 - sq / 8]
        val file = sq % 8
        if (file >= row.length) return null
        val old = row[file]
        if (old == '.') return null
        val ch = " pnbrqk"[type]
        row.setCharAt(file, if (old.isUpperCase()) ch.uppercaseChar() else ch)
        val placement = rows.joinToString("/") { compress(it.toString()) }
        return (listOf(placement) + parts.drop(1)).joinToString(" ")
    }

    fun posKey(fen: String): String = fen.split(" ").take(4).joinToString(" ")

    /** every legal move in this position, like "e2e4" or "e7e8q" (empty if the FEN is broken) */
    fun legalUci(fen: String): Set<String> = try {
        val b = Board()
        b.loadFromFen(fen)
        b.legalMoves().map { it.toString() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** true when every move of the answer is legal in this position (a saved answer must fit where we are) */
    fun fits(fen: String, a: Advice): Boolean {
        val legal = legalUci(fen)
        return a.cands.isNotEmpty() && a.cands.all { it.move in legal }
    }

    fun placement(b: Board): String = b.fen.substringBefore(' ')

    // ------------------------------------------------------------------ occupancy (which squares have a white / black piece)

    /** 64 numbers, index = rank * 8 + file (a1 = 0, h1 = 7, a2 = 8 ...): 0 empty, 1 white piece, 2 black piece */
    fun occOf(b: Board): IntArray {
        val o = IntArray(64)
        for (i in 0 until 64) {
            val p = b.getPiece(Square.squareAt(i))
            o[i] = when {
                p == Piece.NONE -> BoardVision.EMPTY
                p.pieceSide == Side.WHITE -> BoardVision.WHITE
                else -> BoardVision.BLACK
            }
        }
        return o
    }

    fun mismatch(a: IntArray, b: IntArray): Int {
        var n = 0
        for (i in 0 until 64) if (a[i] != b[i]) n++
        return n
    }

    /** turns what we see on the screen (row 0 = top) into the same layout as occOf */
    fun screenToChess(screen: IntArray, whiteBottom: Boolean): IntArray {
        val o = IntArray(64)
        for (r in 0..7) for (c in 0..7) {
            val file = if (whiteBottom) c else 7 - c
            val rank = if (whiteBottom) 7 - r else r
            o[rank * 8 + file] = screen[r * 8 + c]
        }
        return o
    }

    /** index (rank * 8 + file) of the square that is at this place on the screen (row * 8 + column) */
    fun chessIndex(screenIdx: Int, whiteBottom: Boolean): Int {
        val r = screenIdx / 8
        val c = screenIdx % 8
        val file = if (whiteBottom) c else 7 - c
        val rank = if (whiteBottom) 7 - r else r
        return rank * 8 + file
    }

    /** 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king, 0 nothing */
    fun typeCode(p: Piece): Int = when (p.pieceType) {
        PieceType.PAWN -> 1
        PieceType.KNIGHT -> 2
        PieceType.BISHOP -> 3
        PieceType.ROOK -> 4
        PieceType.QUEEN -> 5
        PieceType.KING -> 6
        else -> 0
    }

    /** piece types of a board, same layout as occOf */
    fun typesOf(b: Board): IntArray = IntArray(64) { typeCode(b.getPiece(Square.squareAt(it))) }

    /** how many squares have a piece of a different type than the screen shows (squares we can't tell don't count) */
    fun typeMismatch(b: Board, seen: IntArray): Int {
        var n = 0
        for (i in 0 until 64) {
            if (seen[i] == 0) continue
            val t = typeCode(b.getPiece(Square.squareAt(i)))
            if (t != 0 && t != seen[i]) n++
        }
        return n
    }

    fun startOcc(): IntArray = occOf(Board())

    /** occupancy (screen layout) of 8 rows of text like "rnbqkbnr", top of the screen first */
    fun gridOcc(grid: List<String>): IntArray {
        val o = IntArray(64)
        for (r in 0..7) for (c in 0..7) {
            val ch = grid[r][c]
            o[r * 8 + c] = when {
                ch == '.' -> BoardVision.EMPTY
                ch.isUpperCase() -> BoardVision.WHITE
                else -> BoardVision.BLACK
            }
        }
        return o
    }

    private fun compress(row: String): String {
        val sb = StringBuilder()
        var n = 0
        for (c in row) {
            if (c == '.') {
                n++
            } else {
                if (n > 0) { sb.append(n); n = 0 }
                sb.append(c)
            }
        }
        if (n > 0) sb.append(n)
        return sb.toString()
    }

    /** a FEN piece placement from what we see: colors (1 white, 2 black) and piece types, both in the occOf layout */
    fun placementFromTypes(colors: IntArray, types: IntArray): String {
        val letters = "?pnbrqk"
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val i = rank * 8 + file
                if (colors[i] == BoardVision.EMPTY) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    val ch = letters[types[i]]
                    sb.append(if (colors[i] == BoardVision.WHITE) ch.uppercaseChar() else ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        return sb.toString()
    }

    /** whose turn it is, if the position tells: the side that is in check has to move. null if it can't tell */
    fun turnFromChecks(placement: String): Boolean? {
        return try {
            val w = newBoard(placement, true)
            val bl = newBoard(placement, false)
            val whiteInCheck = w.isKingAttacked
            val blackInCheck = bl.isKingAttacked
            when {
                whiteInCheck && !blackInCheck -> true
                blackInCheck && !whiteInCheck -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** piece placement for a FEN from 8 screen rows. a board seen from Black's side is turned around */
    fun placementFromGrid(grid: List<String>, whiteBottom: Boolean): String {
        val ordered = if (whiteBottom) grid else grid.reversed().map { it.reversed() }
        return ordered.joinToString("/") { compress(it) }
    }

    // ------------------------------------------------------------------ following the game

    /** one way to get from a position to what the screen shows: the moves, and how many squares still differ (0 or 1) */
    class Hit(val path: List<Move>, val mis: Int)

    private const val EMPTY_OCC = 0

    /** for a castling move just played on b: the rook's from and to squares (chess indexes), else null */
    private fun castleRook(m: Move, b: Board): Pair<Int, Int>? {
        val king = b.getPiece(m.to)
        if (king.pieceType != PieceType.KING) return null
        val df = m.to.file.ordinal - m.from.file.ordinal
        if (Math.abs(df) != 2) return null
        val rank = m.from.rank.ordinal
        return if (df > 0) Pair(rank * 8 + 7, rank * 8 + 5) else Pair(rank * 8 + 0, rank * 8 + 3)
    }

    private fun collect(b: Board, occ: IntArray, depth: Int, path: ArrayList<Move>, out: ArrayList<Hit>) {
        if (depth == 0) {
            val cur = occOf(b)
            var m = mismatch(cur, occ)
            // a castle shown with only the king moved yet (a castling premove, or the rook still sliding over):
            // the rook's two squares may still show the old picture
            if (m in 1..2) {
                val last = path.lastOrNull()
                val castle = last?.let { castleRook(it, b) }
                if (castle != null) {
                    val (rFrom, rTo) = castle
                    var rest = 0
                    for (i in 0 until 64) if (cur[i] != occ[i] && i != rFrom && i != rTo) rest++
                    val rookStill = occ[rFrom] == cur[last.to.ordinal] && occ[rTo] == EMPTY_OCC
                    if (rest == 0 && rookStill) m = 0
                }
            }
            if (m <= 1) out.add(Hit(ArrayList(path), m))
            return
        }
        // with the wrong side to move a "legal" move could take a king, which breaks the move generator
        val moves = try { b.legalMoves() } catch (e: Exception) { return }
        for (mv in moves) {
            if (b.getPiece(mv.to).pieceType == PieceType.KING) continue
            b.doMove(mv)
            path.add(mv)
            collect(b, occ, depth - 1, path, out)
            path.removeAt(path.size - 1)
            b.undoMove()
        }
    }

    /** all move sequences of exactly this many moves that end (almost) at what the screen shows. b is left unchanged */
    fun hitsAt(b: Board, occ: IntArray, depth: Int): List<Hit> {
        val hits = ArrayList<Hit>()
        collect(b, occ, depth, ArrayList(), hits)
        return hits
    }

    fun hasPromotion(h: Hit): Boolean = h.path.any { it.promotion != Piece.NONE }

    private fun underpromotions(h: Hit): Int =
        h.path.count { it.promotion != Piece.NONE && it.promotion.pieceType != PieceType.QUEEN }

    /**
     * several hits can look the same on screen (a pawn promoting to any piece). if we can see the piece types
     * (typesSeen), the one that fits them best wins, otherwise take the queen
     */
    fun pick(hits: List<Hit>, b: Board? = null, typesSeen: IntArray? = null): Hit {
        if (hits.size == 1) return hits[0]
        if (b != null && typesSeen != null) {
            val scored = hits.map { h ->
                for (m in h.path) b.doMove(m)
                val mis = typeMismatch(b, typesSeen)
                for (m in h.path) b.undoMove()
                Pair(h, mis)
            }
            val best = scored.minOf { it.second }
            return scored.filter { it.second == best }.map { it.first }.minByOrNull { underpromotions(it) }!!
        }
        return hits.minByOrNull { underpromotions(it) }!!
    }

    fun play(b: Board, h: Hit) {
        for (m in h.path) b.doMove(m)
    }
}
