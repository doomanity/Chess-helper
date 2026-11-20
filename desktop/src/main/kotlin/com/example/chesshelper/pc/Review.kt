package com.example.chesshelper.pc

import com.example.chesshelper.Game
import com.example.chesshelper.UciEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.exp

/** one game: where it started, the moves played (like e2e4) and which side you were */
class GameRecord(val startFen: String, val moves: List<String>, val meWhite: Boolean, val date: Date = Date()) {
    /** the position before each move, and after the last one (fens.size == moves.size + 1) */
    val fens: List<String> by lazy {
        val out = ArrayList<String>()
        val b = Board()
        b.loadFromFen(startFen)
        out.add(b.fen)
        for (m in moves) {
            try { b.doMove(Move(m, b.sideToMove)) } catch (_: Exception) { break }
            out.add(b.fen)
        }
        out
    }

    val sans: List<String> by lazy { moves.indices.filter { it < fens.size - 1 }.map { Game.san(fens[it], moves[it]) } }

    /** saves the game as a pgn file in %APPDATA%\ChessHelper\games (to open in any chess site or app later) */
    fun savePgn(): File {
        Store.games.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(date)
        val f = File(Store.games, "game-$stamp.pgn")
        val start = Board().fen
        val sb = StringBuilder()
        sb.append("[Event \"chess helper game\"]\n")
        sb.append("[Date \"${SimpleDateFormat("yyyy.MM.dd").format(date)}\"]\n")
        sb.append("[White \"${if (meWhite) "me" else "opponent"}\"]\n")
        sb.append("[Black \"${if (meWhite) "opponent" else "me"}\"]\n")
        sb.append("[Result \"*\"]\n")
        if (startFen != start) sb.append("[SetUp \"1\"]\n[FEN \"$startFen\"]\n")
        sb.append("\n")
        val blackFirst = startFen.split(" ").getOrNull(1) == "b"
        val firstNo = startFen.split(" ").getOrNull(5)?.toIntOrNull() ?: 1
        for ((i, san) in sans.withIndex()) {
            val ply = i + if (blackFirst) 1 else 0
            val no = firstNo + ply / 2
            if (ply % 2 == 0) sb.append("$no. ") else if (i == 0) sb.append("$no... ")
            sb.append(san).append(' ')
        }
        sb.append("*\n")
        f.writeText(sb.toString())
        return f
    }
}

/** how good a move was, from the win chance it gave away (like the big chess sites) */
enum class Kind(val label: String, val rgb: Int) {
    BEST("best", 0x6FBF73),
    GOOD("good", 0x9FBF8F),
    INACCURACY("inaccuracy", 0xE3C15A),
    MISTAKE("mistake", 0xE8913A),
    BLUNDER("blunder", 0xD9534F),
}

/** one move of the game, looked at by stockfish */
class MoveReview(
    val ply: Int, val white: Boolean, val uci: String, val san: String,
    val best: String?, val bestSan: String?, val kind: Kind,
    val accuracy: Double, val evalAfter: String, val mine: Boolean
)

class ReviewResult(val game: GameRecord, val moves: List<MoveReview>) {
    private fun side(mine: Boolean) = moves.filter { it.mine == mine }
    fun accuracy(mine: Boolean): Double = side(mine).map { it.accuracy }.average().let { if (it.isNaN()) 0.0 else it }
    fun count(mine: Boolean, k: Kind) = side(mine).count { it.kind == k }
}

/** goes over a finished game with stockfish: the eval before and after every move, what was best, how much it cost */
object Reviewer {
    /** win chance (0..100) for white from a score in white's view */
    private fun winWhite(score: Int, mate: Int?): Double {
        if (mate != null) return if (mate > 0) 100.0 else 0.0
        val cp = score.coerceIn(-2000, 2000)
        return 50 + 50 * (2 / (1 + exp(-0.00368208 * cp)) - 1)
    }

    private class Eval(val cpWhite: Int, val mateWhite: Int?, val best: String?, val mated: Boolean = false)

    private fun evalOf(eng: UciEngine, fen: String, ms: Int): Eval {
        val b = Board().apply { loadFromFen(fen) }
        val sign = if (b.sideToMove == Side.WHITE) 1 else -1
        if (b.legalMoves().isEmpty()) {
            // checkmate (the side to move lost) or stalemate
            return if (b.isKingAttacked) Eval(-sign * 30000, null, null, mated = true) else Eval(0, null, null)
        }
        val l = eng.analyse(fen, ms, 1).firstOrNull() ?: return Eval(0, null, null)
        return Eval(l.cp * sign, l.mate?.let { it * sign }, l.move)
    }

    private fun text(e: Eval): String = when {
        e.mated -> "#"
        e.mateWhite != null && e.mateWhite > 0 -> "M${e.mateWhite}"
        e.mateWhite != null && e.mateWhite < 0 -> "-M${-e.mateWhite}"
        else -> String.format(java.util.Locale.US, "%+.1f", e.cpWhite / 100.0)
    }

    fun run(game: GameRecord, enginePath: String, msPerMove: Int, progress: (Int, Int) -> Unit): ReviewResult {
        val eng = UciEngine(enginePath)
        try {
            val fens = game.fens
            val n = fens.size - 1
            val evals = ArrayList<Eval>()
            for ((i, fen) in fens.withIndex()) {
                progress(i, fens.size)
                evals.add(evalOf(eng, fen, msPerMove))
            }
            progress(fens.size, fens.size)
            val out = ArrayList<MoveReview>()
            for (k in 0 until n) {
                val white = Board().apply { loadFromFen(fens[k]) }.sideToMove == Side.WHITE
                val before = winWhite(evals[k].cpWhite, evals[k].mateWhite)
                val after = winWhite(evals[k + 1].cpWhite, evals[k + 1].mateWhite)
                val wb = if (white) before else 100 - before
                val wa = if (white) after else 100 - after
                val drop = (wb - wa).coerceAtLeast(0.0)
                val acc = (103.1668 * exp(-0.04354 * drop) - 3.1669).coerceIn(0.0, 100.0)
                val best = evals[k].best
                val kind = when {
                    best != null && best == game.moves[k] -> Kind.BEST
                    drop >= 20 -> Kind.BLUNDER
                    drop >= 10 -> Kind.MISTAKE
                    drop >= 5 -> Kind.INACCURACY
                    drop < 1.5 -> Kind.BEST
                    else -> Kind.GOOD
                }
                out.add(MoveReview(k, white, game.moves[k], game.sans[k], best, best?.let { Game.san(fens[k], it) },
                    kind, acc, text(evals[k + 1]), mine = white == game.meWhite))
            }
            return ReviewResult(game, out)
        } finally {
            eng.close()
        }
    }
}
