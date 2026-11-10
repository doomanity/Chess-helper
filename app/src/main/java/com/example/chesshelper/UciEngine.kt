package com.example.chesshelper

/** a move for the side to play, and how good stockfish thinks it is for that side (like +0.3) */
class Suggestion(val move: String, val eval: String)

/** something that can pick a move for a position */
interface MoveEngine {
    fun bestMove(fen: String, ms: Int): Suggestion?
    fun close()
}

/** a list of moves an engine likes, best first (maia3: the most likely human moves) */
interface MoveLister {
    fun topMoves(fen: String, n: Int): List<String>
}

/** talks to the stockfish binary over the UCI text protocol */
class UciEngine(path: String) : MoveEngine {
    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val writer = proc.outputStream.bufferedWriter()
    private val reader = proc.inputStream.bufferedReader()

    init {
        send("uci")
        waitFor("uciok")
    }

    private fun send(cmd: String) {
        writer.write(cmd)
        writer.newLine()
        writer.flush()
    }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            if (line.startsWith(prefix)) return line
        }
    }

    /** the difficulty the user picked. below MAX_ELO the engine plays weaker on purpose */
    var elo = MAX_ELO
        private set
    private var limited = false

    /** limit true = weak play on purpose (the picked elo), false = full power. no-op if already so */
    private fun setStrength(limit: Boolean) {
        if (limit && elo < MAX_ELO && elo >= MIN_ELO) {
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value $elo")
            limited = true
        } else {
            send("setoption name UCI_LimitStrength value false")
            limited = false
        }
    }

    private fun setLines(n: Int) {
        send("setoption name MultiPV value $n")
    }

    private fun sync() {
        send("isready")
        waitFor("readyok")
    }

    fun configure(elo: Int) {
        this.elo = elo
        send("setoption name Threads value 2")
        send("setoption name Hash value 32")
        setStrength(true)
        setLines(1)
        sync()
    }

    /** true if the difficulty is below full power (the engine's own pick is deliberately weaker) */
    val isLimited: Boolean get() = elo < MAX_ELO

    /** the move the difficulty setting would play (weaker than the best one when elo is low) */
    fun playedMove(fen: String, ms: Int): String? {
        setStrength(true)
        setLines(1)
        sync()
        return bestMove(fen, ms)?.move
    }

    /** full strength look at the position: the best [lines] moves with their scores, best first */
    fun analyse(fen: String, ms: Int, lines: Int): List<Line> {
        setStrength(false)
        setLines(lines)
        sync()
        send("position fen $fen")
        send("go movetime $ms")
        val last = HashMap<Int, Line>()
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            UciInfo.parse(line)?.let { last[it.multipv] = it }
            if (line.startsWith("bestmove")) break
        }
        setLines(1)
        return last.values.sortedBy { it.multipv }
    }

    /** how good one particular move is (full strength, short look). null if it isn't a legal move */
    fun evalMove(fen: String, move: String, ms: Int): Line? {
        setStrength(false)
        setLines(1)
        sync()
        send("position fen $fen")
        send("go movetime $ms searchmoves $move")
        var best: Line? = null
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            UciInfo.parse(line)?.let { best = it }
            if (line.startsWith("bestmove")) break
        }
        return best
    }

    /** returns the best move like "e2e4" plus a short evaluation, or null if there is no move */
    override fun bestMove(fen: String, ms: Int): Suggestion? {
        send("position fen $fen")
        send("go movetime $ms")
        var eval = ""
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            if (line.startsWith("info ") && " score " in line && "bound" !in line) {
                val t = line.split(" ")
                val i = t.indexOf("score")
                if (i >= 0 && i + 2 < t.size) {
                    val v = t[i + 2].toIntOrNull()
                    if (v != null) {
                        eval = if (t[i + 1] == "cp") String.format(java.util.Locale.US, "%+.1f", v / 100.0)
                        else if (t[i + 1] == "mate") (if (v > 0) "mate in $v" else "mated in ${-v}") else eval
                    }
                }
            }
            if (line.startsWith("bestmove")) {
                val move = line.split(" ").getOrNull(1)
                return if (move == null || move == "(none)") null else Suggestion(move, eval)
            }
        }
    }

    override fun close() {
        try { send("quit") } catch (_: Exception) {}
        proc.destroy()
    }

    /** false once the engine program has died (android can kill it in the background, or it crashed) */
    val alive: Boolean get() = proc.isAlive

    companion object {
        const val MIN_ELO = 1320
        const val MAX_ELO = 3190
    }
}
