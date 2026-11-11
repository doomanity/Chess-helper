package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import java.io.IOException

/**
 * Picks the moves to show and grades every one of them with the phone's stockfish at full power:
 * the best moves of the engine you chose (stockfish at your difficulty, or the likely moves of maia3),
 * each marked best / decent / acceptable / blunder by how much worse it is than the best move.
 */
class Advisor(private val sf: UciEngine, private val lister: MoveLister?) {
    /** set when the maia3 pc could not be reached for the last call (we used stockfish instead) */
    var listerError: String? = null
        private set

    fun advise(fen: String, n: Int, ms: Int = 700): List<Cand> = full(fen, n, ms, threat = false).cands

    /**
     * the moves to show plus the extras: eval bar, the opponent's threat, the main line.
     * maxPower = a "save": one move from stockfish at full power with a longer think, whatever the difficulty
     */
    fun full(fen: String, n: Int, ms: Int = 700, threat: Boolean = true, maxPower: Boolean = false): Advice {
        listerError = null
        // the other engine (illusion, maia3) thinks at the same time as stockfish, not after it
        val picked = ArrayList<String>()
        var pickErr: String? = null
        val pickThread = if (lister != null && !maxPower) Thread {
            try {
                picked.addAll(lister.topMoves(fen, n))
            } catch (e: IOException) {
                pickErr = e.message ?: "not reachable"
            } catch (e: Exception) {
                pickErr = e.message ?: "failed"
            }
        }.also { it.start() } else null
        val lines = try {
            sf.analyse(fen, if (maxPower) maxOf(ms, 2500) else ms, if (maxPower) 1 else maxOf(8, n))
        } finally {
            pickThread?.join()
        }
        if (lines.isEmpty()) return Advice(emptyList(), 0, null, null, emptyList())
        val top = lines[0]
        val whiteToMove = fen.split(" ").getOrNull(1) != "b"
        val evalWhite = if (whiteToMove) top.score else -top.score
        val mateWhite = top.mate?.let { if (whiteToMove) it else -it }
        val (th, plan) = if (threat) threatAndPlan(fen, top.score) else Pair(null, emptyList())
        val line = top.pv.drop(1).take(3)
        val second = lines.getOrNull(1)?.score

        if (maxPower) {
            return Advice(listOf(Cand(top.move, top.score, top.mate, Grade.BEST, 1, star = true)), evalWhite, mateWhite, th, line, top.move,
                top.score, null, plan)
        }

        val picks = ArrayList<String>()
        picks.addAll(picked)
        listerError = pickErr
        if (picks.isEmpty() && sf.isLimited) {
            // the difficulty setting makes stockfish play worse on purpose: its own pick goes first
            sf.playedMove(fen, ms)?.let { picks.add(it) }
        }
        for (l in lines) picks.add(l.move) // fill up with the real best moves
        val chosen = picks.distinct().take(n)

        val best = top.score
        val out = ArrayList<Cand>()
        for ((i, mv) in chosen.withIndex()) {
            val l = lines.firstOrNull { it.move == mv } ?: sf.evalMove(fen, mv, 250) ?: continue
            val loss = maxOf(0, best - l.score)
            out.add(Cand(mv, l.score, l.mate, Grade.of(loss), i + 1))
        }
        return Advice(out, evalWhite, mateWhite, th, line, top.move, top.score, second, plan)
    }

    /**
     * what the opponent threatens: let them move twice in a row (we "pass") and see if they win something.
     * score = our current score (side to move). null when there is no real threat or we are in check
     */
    fun threatOf(fen: String, score: Int, ms: Int = 300): Threat? = threatAndPlan(fen, score, ms).first

    /**
     * the threat (see threatOf) and the opponent's plan: their moves in the same "we pass" search, up to 3 of them
     * (the line goes theirs, ours, theirs, ours, theirs: we keep theirs)
     */
    fun threatAndPlan(fen: String, score: Int, ms: Int = 300): Pair<Threat?, List<String>> {
        val none = Pair<Threat?, List<String>>(null, emptyList())
        val parts = fen.split(" ").toMutableList()
        if (parts.size < 4) return none
        try {
            val b = Board()
            b.loadFromFen(fen)
            if (b.isKingAttacked) return none // in check we can't pass
        } catch (e: Exception) {
            return none
        }
        parts[1] = if (parts[1] == "w") "b" else "w"
        parts[3] = "-"
        val passed = parts.joinToString(" ")
        val l = sf.analyse(passed, ms, 1).firstOrNull() ?: return none
        val plan = l.pv.filterIndexed { i, _ -> i % 2 == 0 }.take(3)
        val gain = l.score + score // their score after our pass, compared to their score now (-score)
        if (l.mate != null && l.mate > 0) return Pair(Threat(l.move, gain), plan)
        // moving twice is always worth a bit (a free tempo), so a plain developing move is no threat.
        // a capture of one of our pieces has to win something real, any other move (a fork...) a lot
        val capture = try {
            val pb = Board()
            pb.loadFromFen(passed)
            pb.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(l.move.substring(2, 4).uppercase())) != com.github.bhlangonijr.chesslib.Piece.NONE
        } catch (e: Exception) {
            false
        }
        val need = if (capture) THREAT_MIN else THREAT_BIG
        return Pair(if (gain >= need) Threat(l.move, gain) else null, plan)
    }

    companion object {
        const val THREAT_MIN = 150 // a capture threat has to win at least this much (centipawns) to be shown
        const val THREAT_BIG = 300 // any other threat (a fork, a mating attack) at least this much
    }
}
