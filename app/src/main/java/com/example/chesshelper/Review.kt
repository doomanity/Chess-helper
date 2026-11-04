package com.example.chesshelper

import kotlin.math.exp

/*
 * game review: the labels chess.com shows after a game (brilliant, great, best, ... blunder).
 * this file has no android and no chess library in it, only numbers, so it can be tested on a computer.
 * the review only looks at moves that were already played. it never picks or changes a move.
 */

/** the label of one played move. the color is the one the badge on the board uses */
enum class Label(val text: String, val symbol: String, val color: Int) {
    BRILLIANT("brilliant", "!!", 0xFF1BACA6.toInt()),
    GREAT("great", "!", 0xFF5C8BB0.toInt()),
    BEST("best", "★", 0xFF96BC4B.toInt()),
    EXCELLENT("excellent", "✓", 0xFF96BC4B.toInt()),
    GOOD("good", "✓", 0xFF9FB88A.toInt()),
    BOOK("book", "B", 0xFFA88865.toInt()),
    INACCURACY("inaccuracy", "?!", 0xFFF7C631.toInt()),
    MISTAKE("mistake", "?", 0xFFE58F2A.toInt()),
    MISS("miss", "✕", 0xFFFF7769.toInt()),
    BLUNDER("blunder", "??", 0xFFCA3431.toInt());

    /** true for the labels that glow on the board */
    val glows: Boolean get() = this == BRILLIANT || this == GREAT
}

/** everything the labeler needs to know about one move. scores are centipawns from the mover's side (a mate is a huge number) */
class PlyInput(
    val playedUci: String,
    val bestUci: String,
    val bestScore: Int,          // the engine's best move, before the move was played
    val secondScore: Int?,       // the engine's second best move (null if there was only one legal move)
    val afterScore: Int,         // the move that was played (the same as bestScore when it was the best move)
    val isBook: Boolean,         // still inside the opening book
    val isRecapture: Boolean,    // takes back on the square the opponent just took on
    val sacrifice: Boolean,      // gives up material that does not come back in the next few moves
    val materialLead: Int,       // the mover's material minus the opponent's, before the move (centipawns)
    val prevOppLabel: Label?     // how the opponent's last move was labeled
)

/**
 * chess.com's way of judging a move: how many "expected points" (0 = lost, 1 = won, a draw is 0.5) the move gave away
 * compared to the best move. the thresholds are the ones chess.com publishes. brilliant, great and miss have no
 * published formula, so they are close copies of how chess.com describes them.
 */
object Labeler {
    // expected points lost
    const val BEST_MAX = 0.003
    const val EXCELLENT_MAX = 0.02
    const val GOOD_MAX = 0.05
    const val INACCURACY_MAX = 0.10
    const val MISTAKE_MAX = 0.20

    const val GREAT_GAP = 0.15        // the second best move has to be this much worse than the best one
    const val BRILLIANT_AFTER = -50   // after a brilliant move you are at least equal (centipawns)
    const val BRILLIANT_BEFORE = 550  // and you were not already winning by a lot
    const val BRILLIANT_LEAD = 1000   // (a queen up is already crushing)
    const val SACRIFICE_MIN = 200     // gives up at least this much material (centipawns)

    /**
     * how likely you are to score at this evaluation, 0..1. chess.com's curve depends on the rating (it is kinder
     * to lower rated players: the same evaluation is less decisive), so the curve gets flatter for a lower rating
     */
    fun ep(cp: Int, rating: Int): Double {
        if (cp >= 20000) return 1.0 // a mate
        if (cp <= -20000) return 0.0
        val c = cp.coerceIn(-1000, 1000)
        val k = 0.00368208 * ratingScale(rating)
        return 1.0 / (1.0 + exp(-k * c))
    }

    /** 0.55 at rating 400 up to 1.0 at rating 2400 and above */
    fun ratingScale(rating: Int): Double {
        val t = ((rating - 400) / 2000.0).coerceIn(0.0, 1.0)
        return 0.55 + 0.45 * t
    }

    /** equal (1), clearly winning (2) or clearly losing (0) */
    private fun group(ep: Double): Int = if (ep >= 0.65) 2 else if (ep <= 0.35) 0 else 1

    fun loss(p: PlyInput, rating: Int): Double {
        if (p.playedUci == p.bestUci) return 0.0
        return maxOf(0.0, ep(p.bestScore, rating) - ep(p.afterScore, rating))
    }

    fun label(p: PlyInput, rating: Int = 1500): Label {
        if (p.isBook) return Label.BOOK
        val epBest = ep(p.bestScore, rating)
        val lost = loss(p, rating)
        val bestLike = lost <= BEST_MAX

        // brilliant: a good piece sacrifice. you stay at least equal, and were not winning big already
        if (p.sacrifice && lost <= EXCELLENT_MAX && p.afterScore >= BRILLIANT_AFTER &&
            p.bestScore < BRILLIANT_BEFORE && p.materialLead < BRILLIANT_LEAD) {
            return Label.BRILLIANT
        }
        // great: the only move that wins (equal -> winning) or saves (losing -> equal)
        if (bestLike && !p.isRecapture && p.secondScore != null) {
            val epSecond = ep(p.secondScore, rating)
            if (epBest - epSecond >= GREAT_GAP && group(epBest) > group(epSecond)) return Label.GREAT
        }
        if (bestLike) return Label.BEST
        if (lost <= EXCELLENT_MAX) return Label.EXCELLENT
        if (lost <= GOOD_MAX) return Label.GOOD
        if (lost > MISTAKE_MAX) return Label.BLUNDER
        // a small slip right after the opponent messed up, with a real advantage on the board = a missed chance
        val oppSlipped = p.prevOppLabel == Label.MISTAKE || p.prevOppLabel == Label.BLUNDER
        if (oppSlipped && epBest >= 0.6) return Label.MISS
        return if (lost <= INACCURACY_MAX) Label.INACCURACY else Label.MISTAKE
    }
}

/** one move of the game with its label and everything the screen shows about it */
class ReviewedMove(
    val ply: Int,                 // 1 = white's first move
    val uci: String,
    val san: String,
    val moverWhite: Boolean,
    val label: Label,
    val bestUci: String,
    val bestSan: String,
    val bestScoreWhite: Int,      // what the best move would have given, from white's side
    val afterScoreWhite: Int,     // what the move gave, from white's side
    val accuracy: Double,         // 0..100, same formula as the live accuracy
    val opening: String?          // the opening name, for book moves
)

/** the whole review so far: the positions of the game, the reviewed moves, and the score of each position (white's side) */
class ReviewResult(
    val fens: List<String>,
    val moves: List<ReviewedMove>,
    val evalsWhite: List<Int>,
    val note: String?             // a warning, like a move that could not be read
)

class SideSummary(val accuracy: Int?, val counts: IntArray) {
    fun count(l: Label): Int = counts[l.ordinal]
}

object Summary {
    fun of(moves: List<ReviewedMove>, white: Boolean): SideSummary {
        val mine = moves.filter { it.moverWhite == white }
        val counts = IntArray(Label.values().size)
        for (m in mine) counts[m.label.ordinal]++
        return SideSummary(Accuracy.score(mine.map { it.accuracy }), counts)
    }
}

object ReviewFmt {
    /** a score from white's side as text: +0.3, -1.2, M3 (white mates in 3) or -M2 */
    fun evalText(scoreWhite: Int): String {
        if (scoreWhite >= 20000) return "M${(30000 - scoreWhite) / 10}"
        if (scoreWhite <= -20000) return "-M${(30000 + scoreWhite) / 10}"
        if (kotlin.math.abs(scoreWhite) < 5) return "0.0"
        return String.format(java.util.Locale.US, "%+.1f", scoreWhite / 100.0)
    }

    /** 0..1: how much of the eval bar is white */
    fun whiteShare(scoreWhite: Int): Float {
        if (scoreWhite >= 20000) return 1f
        if (scoreWhite <= -20000) return 0f
        val x = scoreWhite.coerceIn(-1500, 1500) / 400.0
        return (1.0 / (1.0 + exp(-x))).toFloat()
    }
}

/**
 * the opening book: which positions are known opening theory (and what the opening is called).
 * positions are stored as a 32 bit hash of "pieces side castling" (made by tools/build_openings.py)
 */
class OpeningBook(private val names: Map<Int, String>) {
    val size: Int get() = names.size

    /** the opening this position belongs to, or null if it is not in the book */
    fun name(fen: String): String? = names[key(fen)]

    companion object {
        fun key(fen: String): Int {
            val parts = fen.trim().split(" ")
            val s = parts.take(3).joinToString(" ")
            var h = 0x811C9DC5.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = (h xor (b.toInt() and 0xff)) * 16777619
            }
            return h
        }

        fun parse(lines: Sequence<String>): OpeningBook {
            val map = HashMap<Int, String>()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab != 8) continue
                val h = line.substring(0, 8).toLongOrNull(16) ?: continue
                map[h.toInt()] = line.substring(tab + 1).trim()
            }
            return OpeningBook(map)
        }
    }
}

/** turns the text of a pgn into a start position and a list of move texts (Nf3, exd5, O-O, e8=Q) */
object PgnText {
    class Parsed(val fen: String?, val tokens: List<String>)

    private val numberPrefix = Regex("^\\d+\\.*")

    fun parse(text: String): Parsed {
        var fen: String? = null
        val sb = StringBuilder()
        val n = text.length
        var i = 0
        var depth = 0 // inside a variation (...)
        while (i < n) {
            val c = text[i]
            if (depth > 0) {
                if (c == '(') depth++ else if (c == ')') depth--
                i++
            } else if (c == '{') {
                val e = text.indexOf('}', i)
                i = if (e < 0) n else e + 1
                sb.append(' ')
            } else if (c == ';') {
                val e = text.indexOf('\n', i)
                i = if (e < 0) n else e
            } else if (c == '(') {
                depth = 1
                i++
            } else if (c == '[') {
                val e = text.indexOf(']', i)
                val header = text.substring(i + 1, if (e < 0) n else e).trim()
                if (header.startsWith("FEN", ignoreCase = true)) {
                    val q1 = header.indexOf('"')
                    val q2 = header.lastIndexOf('"')
                    if (q1 >= 0 && q2 > q1) fen = header.substring(q1 + 1, q2).trim()
                }
                i = if (e < 0) n else e + 1
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        val out = ArrayList<String>()
        for (raw in sb.toString().split(Regex("\\s+"))) {
            var t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("$")) continue                       // a symbol like $1
            if (t == "1-0" || t == "0-1" || t == "1/2-1/2" || t == "*") continue
            if (t.startsWith("0-0")) t = t.replace('0', 'O')      // castling written with zeros
            t = numberPrefix.replace(t, "")                       // 12. or 12... in front of the move
            if (t.isEmpty() || t.all { it == '.' }) continue
            t = t.replace("e.p.", "").trimEnd('+', '#', '!', '?')
            if (t.isEmpty()) continue
            out.add(t)
        }
        return Parsed(fen, out)
    }
}
