package com.example.chesshelper

/** how good a move is compared to the best one: 0 best, 1 decent, 2 acceptable, 3 blunder */
object Grade {
    const val BEST = 0
    const val DECENT = 1
    const val OK = 2
    const val BLUNDER = 3

    // centipawns (hundredths of a pawn) lost compared to the best move
    const val BEST_MAX = 20
    const val DECENT_MAX = 70
    const val OK_MAX = 160

    fun of(lossCp: Int): Int = when {
        lossCp <= BEST_MAX -> BEST
        lossCp <= DECENT_MAX -> DECENT
        lossCp <= OK_MAX -> OK
        else -> BLUNDER
    }
}

/** one score line from the engine: first move of the line and how good it is for the side to move */
class Line(val multipv: Int, val move: String, val cp: Int, val mate: Int?, val pv: List<String> = listOf(move)) {
    /** one number for comparing, a mate counts as a huge advantage */
    val score: Int get() = scoreOf(cp, mate)

    companion object {
        fun scoreOf(cp: Int, mate: Int?): Int =
            if (mate == null) cp else if (mate > 0) 30000 - mate * 10 else -30000 - mate * 10
    }
}

/** a move to show the user */
class Cand(val move: String, val score: Int, val mate: Int?, val grade: Int, val rank: Int, val star: Boolean = false) {
    /** like +0.3, M3 (mate in 3) or -M2 */
    val text: String
        get() = when {
            mate != null && mate > 0 -> "M$mate"
            mate != null && mate < 0 -> "-M${-mate}"
            kotlin.math.abs(score) < 5 -> "+0.0"
            else -> String.format(java.util.Locale.US, "%+.1f", score / 100.0)
        }
}

object UciInfo {
    /** reads an "info ... multipv N score cp X ... pv e2e4 ..." line. null if it isn't a plain score line */
    fun parse(line: String): Line? {
        if (!line.startsWith("info ") || " score " !in line || " pv " !in line) return null
        if (" lowerbound" in line || " upperbound" in line) return null
        val t = line.trim().split(" ").filter { it.isNotEmpty() }
        val si = t.indexOf("score")
        val pi = t.indexOf("pv")
        if (si < 0 || si + 2 >= t.size || pi < 0 || pi + 1 >= t.size) return null
        val v = t[si + 2].toIntOrNull() ?: return null
        val mi = t.indexOf("multipv")
        val mpv = if (mi >= 0 && mi + 1 < t.size) t[mi + 1].toIntOrNull() ?: 1 else 1
        val pv = t.subList(pi + 1, t.size).filter { it.length in 4..5 }
        return when (t[si + 1]) {
            "cp" -> Line(mpv, t[pi + 1], v, null, pv)
            "mate" -> Line(mpv, t[pi + 1], 0, v, pv)
            else -> null
        }
    }
}

/** what the opponent would like to play if it were their turn again */
class Threat(val move: String, val gain: Int)

/** everything to show for one position */
class Advice(
    val cands: List<Cand>,
    val evalWhite: Int,         // who is better, from white's side (centipawns, a mate is a huge number)
    val mateWhite: Int?,        // mate in N from white's side (negative: black mates), or null
    val threat: Threat?,        // the opponent's threat, if there is a real one
    val line: List<String>,     // the moves after the best move in the main line (opponent, us, opponent)
    val lineFor: String = "",   // the best move that line starts with
    val bestScore: Int? = null, // the best move's score, side to move's view
    val secondScore: Int? = null, // the second best move's score (null: only one legal move)
    val plan: List<String> = emptyList() // the opponent's plan: their next moves if we passed (theirs only)
) {
    /** 0..1: how much of the eval bar is white */
    val whiteShare: Float
        get() {
            if (mateWhite != null) return if (mateWhite > 0) 1f else 0f
            val x = evalWhite.coerceIn(-1500, 1500) / 400.0
            return (1.0 / (1.0 + Math.exp(-x))).toFloat()
        }

    /** like +1.3 or M3 for the eval bar, from white's side */
    val evalText: String
        get() = when {
            mateWhite != null -> if (mateWhite > 0) "M$mateWhite" else "-M${-mateWhite}"
            kotlin.math.abs(evalWhite) < 5 -> "0.0"
            else -> String.format(java.util.Locale.US, "%+.1f", evalWhite / 100.0)
        }
}
