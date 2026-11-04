package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

const val STANDARD_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
const val START_FEN = "$STANDARD_PLACEMENT w KQkq - 0 1"

/** a game to review: where it starts (a fen) and the moves played from there like "e2e4" */
class ReviewGame(val startFen: String, val moves: List<String>, val note: String? = null) {
    fun same(o: ReviewGame?): Boolean = o != null && o.startFen == startFen && o.moves == moves
}

/** reads a pgn (or just a list of moves like "1. e4 e5 2. Nf3") */
object PgnReader {
    /** null if the text has no start position that makes sense. a game that stops early has a note saying where */
    fun read(text: String): ReviewGame? {
        val parsed = PgnText.parse(text)
        val start = parsed.fen ?: START_FEN
        val b = Board()
        try {
            b.loadFromFen(start)
        } catch (e: Exception) {
            return null
        }
        val moves = ArrayList<String>()
        var note: String? = null
        for ((i, san) in parsed.tokens.withIndex()) {
            val mv = resolve(b, san)
            if (mv == null) {
                note = "stopped at move ${i / 2 + 1}: can't read \"$san\""
                break
            }
            moves.add(mv.toString())
            b.doMove(mv)
        }
        return ReviewGame(start, moves, note)
    }

    private fun pieceOf(c: Char?): PieceType? = when (c?.uppercaseChar()) {
        'K' -> PieceType.KING
        'Q' -> PieceType.QUEEN
        'R' -> PieceType.ROOK
        'B' -> PieceType.BISHOP
        'N' -> PieceType.KNIGHT
        else -> null
    }

    /** the legal move that a text like Nbd7, exd5 or e8=Q means. null if there is none */
    private fun resolve(b: Board, san: String): Move? {
        val legal = try { b.legalMoves() } catch (e: Exception) { return null }
        if (san == "O-O" || san == "O-O-O") {
            val want = if (san == "O-O") 2 else -2
            return legal.firstOrNull { mv ->
                b.getPiece(mv.from).pieceType == PieceType.KING && (mv.to.name[0] - mv.from.name[0]) == want
            }
        }
        var s = san
        var promo: PieceType? = null
        val eq = s.indexOf('=')
        if (eq >= 0) {
            promo = pieceOf(s.getOrNull(eq + 1))
            s = s.substring(0, eq)
        } else if (s.length >= 3 && s.last() in "QRBN" && s[s.length - 2] in '1'..'8') {
            promo = pieceOf(s.last())
            s = s.dropLast(1)
        }
        var type = PieceType.PAWN
        if (s.isNotEmpty() && s[0] in "KQRBN") {
            type = pieceOf(s[0]) ?: return null
            s = s.substring(1)
        }
        s = s.replace("x", "").replace("-", "")
        if (s.length < 2) return null
        val dest = s.substring(s.length - 2).uppercase()
        val dis = s.substring(0, s.length - 2)
        val wanted = promo
        return legal.firstOrNull { mv ->
            mv.to.name == dest &&
                b.getPiece(mv.from).pieceType == type &&
                (if (wanted != null) mv.promotion != Piece.NONE && mv.promotion.pieceType == wanted else mv.promotion == Piece.NONE) &&
                dis.all { ch ->
                    if (ch in 'a'..'h') mv.from.name[0].lowercaseChar() == ch
                    else if (ch in '1'..'8') mv.from.name[1] == ch
                    else true
                }
        }
    }
}

/** a game played out: the position before every move (and after the last one), and facts about each move */
class Replayed(
    val fens: List<String>,       // size = moves + 1
    val ucis: List<String>,
    val captures: List<Boolean>,  // the move took a piece
    val tos: List<String>,        // the square the move goes to, like "e4"
    val note: String?
)

/**
 * Reviews a finished (or still running) game with stockfish at full power: looks at every position, labels every
 * move chess.com style and scores it. Analysis only: nothing here picks a move for anybody.
 * Positions are remembered, so reviewing the same game again (or a game that grew by a move) costs almost nothing.
 */
class Analyzer(private val engine: UciEngine, private val book: OpeningBook?) {
    private class PosEval(val lines: List<Line>, val terminal: Int?) {
        /** for the side to move */
        val score: Int get() = terminal ?: lines.firstOrNull()?.score ?: 0
    }

    private val cache = HashMap<String, PosEval>()
    private var cacheMs = 0

    private fun eval(fen: String, ms: Int): PosEval {
        val key = Game.posKey(fen)
        cache[key]?.let { return it }
        val b = Board()
        b.loadFromFen(fen)
        val hasMoves = try { b.legalMoves().isNotEmpty() } catch (e: Exception) { false }
        val pe = if (!hasMoves) {
            // checkmate (the side to move lost) or stalemate
            PosEval(emptyList(), if (b.isKingAttacked) -30000 else 0)
        } else {
            PosEval(engine.analyse(fen, ms, 2), null)
        }
        cache[key] = pe
        return pe
    }

    private fun whiteScore(pe: PosEval, fen: String): Int {
        val whiteToMove = fen.split(" ").getOrNull(1) != "b"
        return if (whiteToMove) pe.score else -pe.score
    }

    /**
     * reviews every move. progress gets the review so far after every move (so the screen can fill up while it works).
     * cancel is asked between moves. returns the finished review, or null if the game can't be set up
     */
    fun analyze(game: ReviewGame, ms: Int, rating: Int, cancel: () -> Boolean, progress: (ReviewResult) -> Unit): ReviewResult? {
        if (ms != cacheMs) {
            cache.clear()
            cacheMs = ms
        }
        val rep = replay(game) ?: return null
        val n = rep.ucis.size
        val moves = ArrayList<ReviewedMove>()
        val evals = ArrayList<Int>()
        val startParts = rep.fens[0].split(" ")
        var inBook = book != null && startParts[0] == STANDARD_PLACEMENT && startParts.getOrNull(1) == "w"
        var prevLabel: Label? = null

        fun snapshot() = ReviewResult(rep.fens, ArrayList(moves), ArrayList(evals), rep.note)

        var pe0 = eval(rep.fens[0], ms)
        evals.add(whiteScore(pe0, rep.fens[0]))
        progress(snapshot())

        for (i in 0 until n) {
            if (cancel()) return snapshot()
            val fen = rep.fens[i]
            val next = rep.fens[i + 1]
            val played = rep.ucis[i]
            val moverWhite = fen.split(" ").getOrNull(1) != "b"
            val pe1 = eval(next, ms)

            val bestLine = pe0.lines.firstOrNull()
            val bestUci = bestLine?.move ?: played
            // scores for the player who moved. the move that was played is judged by the same search as the best
            // move when it was one of the two best, otherwise by the search of the position after it
            val afterOwn = -pe1.score
            val bestScore = bestLine?.score ?: afterOwn
            val second = pe0.lines.getOrNull(1)?.score
            val afterScore = if (played == bestUci) bestScore
                else pe0.lines.firstOrNull { it.move == played }?.score ?: afterOwn

            val bookName = if (inBook && book != null) book.name(next) else null
            if (bookName == null) inBook = false

            val sac = if (bookName == null) sacrificeOf(fen, played, pe1.lines.firstOrNull()?.pv ?: emptyList(), moverWhite) else Pair(false, 0)
            val recapture = i > 0 && rep.captures[i - 1] && rep.tos[i] == rep.tos[i - 1]
            val input = PlyInput(
                playedUci = played, bestUci = bestUci, bestScore = bestScore, secondScore = second, afterScore = afterScore,
                isBook = bookName != null, isRecapture = recapture, sacrifice = sac.first, materialLead = sac.second,
                prevOppLabel = prevLabel
            )
            val label = Labeler.label(input, rating)
            prevLabel = label

            val beforeWhite = if (moverWhite) bestScore else -bestScore
            val afterWhite = if (moverWhite) afterScore else -afterScore
            moves.add(
                ReviewedMove(
                    ply = i + 1, uci = played, san = Game.san(fen, played), moverWhite = moverWhite, label = label,
                    bestUci = bestUci, bestSan = Game.san(fen, bestUci),
                    bestScoreWhite = beforeWhite, afterScoreWhite = afterWhite,
                    accuracy = Accuracy.moveAccuracy(moverWhite, beforeWhite, afterWhite), opening = bookName
                )
            )
            evals.add(whiteScore(pe1, next))
            pe0 = pe1
            progress(snapshot())
        }
        return snapshot()
    }

    // ------------------------------------------------------------------ chess helpers

    /** play the moves from the start position of the game. a move that isn't legal ends the game there (with a note) */
    private fun replay(game: ReviewGame): Replayed? {
        val b = Board()
        try {
            b.loadFromFen(game.startFen)
        } catch (e: Exception) {
            return null
        }
        val fens = ArrayList<String>()
        val ucis = ArrayList<String>()
        val captures = ArrayList<Boolean>()
        val tos = ArrayList<String>()
        fens.add(b.fen)
        var note = game.note
        for ((i, uci) in game.moves.withIndex()) {
            val mv = moveOf(b, uci)
            if (mv == null) {
                note = "stopped at move ${i / 2 + 1}: $uci is not a legal move"
                break
            }
            val mover = b.getPiece(mv.from)
            val capture = b.getPiece(mv.to) != Piece.NONE ||
                (mover.pieceType == PieceType.PAWN && mv.from.name[0] != mv.to.name[0])
            b.doMove(mv)
            fens.add(b.fen)
            ucis.add(uci)
            captures.add(capture)
            tos.add(mv.to.name.lowercase())
        }
        return Replayed(fens, ucis, captures, tos, note)
    }

    private fun moveOf(b: Board, uci: String): Move? =
        try { b.legalMoves().firstOrNull { it.toString() == uci } } catch (e: Exception) { null }

    private fun value(t: PieceType): Int = when (t) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 300
        PieceType.BISHOP -> 300
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        else -> 0
    }

    /** the pieces of one side, in centipawns (pawn 1, minor piece 3, rook 5, queen 9) */
    private fun material(b: Board, white: Boolean): Int {
        var sum = 0
        for (i in 0 until 64) {
            val p = b.getPiece(Square.squareAt(i))
            if (p == Piece.NONE) continue
            if ((p.pieceSide == Side.WHITE) != white) continue
            sum += value(p.pieceType)
        }
        return sum
    }

    /**
     * did this move give up material for nothing in return (for now)? plays the move and the engine's best answers
     * (up to 6 half-moves) and looks at what the mover has left after the last answer. returns that and the
     * mover's material lead before the move
     */
    private fun sacrificeOf(fen: String, played: String, pv: List<String>, moverWhite: Boolean): Pair<Boolean, Int> {
        return try {
            val b = Board()
            b.loadFromFen(fen)
            val before = material(b, moverWhite) - material(b, !moverWhite)
            var after = before
            var answered = false
            val line = ArrayList<String>()
            line.add(played)
            line.addAll(pv.take(5))
            for ((k, uci) in line.withIndex()) {
                val mv = moveOf(b, uci) ?: break
                b.doMove(mv)
                if (k % 2 == 1) { // the opponent has answered
                    after = material(b, moverWhite) - material(b, !moverWhite)
                    answered = true
                }
            }
            Pair(answered && before - after >= Labeler.SACRIFICE_MIN, before)
        } catch (e: Exception) {
            Pair(false, 0)
        }
    }
}
