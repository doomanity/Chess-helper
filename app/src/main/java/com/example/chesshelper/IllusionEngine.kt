package com.example.chesshelper

/**
 * Brody's own engine, Illusion (github.com/doomanity/Illusion), built for the phone and shipped as libillusion.so,
 * run as a separate program next to stockfish. it plays aggressively, hunts brilliant moves, and in trap mode
 * ("Swindle") picks moves that are likely to fool a player of the given rating.
 *
 * it shows one line (no MultiPV), so as a "lister" it adds its one pick and stockfish fills the rest. stockfish
 * still grades every move (the numbers on the card are always stockfish's), so the engines stay comparable.
 * it uses its hand made evaluation for now (no trained net loaded).
 */
class IllusionEngine(path: String) : MoveLister {
    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val writer = proc.outputStream.bufferedWriter()
    private val reader = proc.inputStream.bufferedReader()
    private var swindle = false
    private var oppElo = -1

    /** trap mode for the lister: on = picks traps for a player of this rating */
    @Volatile var trapMode = false
    @Volatile var trapElo = 1500
    /** how long it thinks for one pick (ms) */
    @Volatile var thinkMs = 500

    /** its last pick and whether that was a trap-mode pick (to tag it on the card) */
    @Volatile var lastPick: String? = null
        private set
    @Volatile var lastWasTrap = false
        private set

    init {
        send("uci")
        waitFor("uciok")
        send("setoption name Hash value 32")
        send("setoption name OwnBook value true")
        send("isready")
        waitFor("readyok")
    }

    private fun send(cmd: String) {
        writer.write(cmd)
        writer.newLine()
        writer.flush()
    }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("illusion stopped")
            if (line.startsWith(prefix)) return line
        }
    }

    private fun setTrap(on: Boolean, elo: Int) {
        if (on && elo != oppElo) {
            send("setoption name OpponentElo value ${elo.coerceIn(400, 2800)}")
            oppElo = elo
        }
        if (on != swindle) {
            send("setoption name Swindle value $on")
            swindle = on
        }
    }

    /**
     * its best move for a position (side to move's view), in trap mode or not. the score comes from its own search
     * (null for an opening book move, which answers without searching)
     */
    @Synchronized
    fun best(fen: String, ms: Int, trap: Boolean = false, elo: Int = 1500): Line? {
        setTrap(trap, elo)
        send("position fen $fen")
        send("go movetime $ms")
        var last: Line? = null
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("illusion stopped")
            if (line.startsWith("bestmove")) {
                val got = last
                val mv = line.split(" ").getOrNull(1) ?: return got
                if (mv == "(none)" || mv == "0000") return null
                return if (got != null && got.move == mv) got else Line(1, mv, got?.score ?: 0, got?.mate, listOf(mv))
            }
            val info = UciInfo.parse(line)
            if (info != null) last = info
        }
    }

    /** as the engine for your moves: its one pick (a trap in trap mode), stockfish adds the rest */
    override fun topMoves(fen: String, n: Int): List<String> {
        val trap = trapMode
        val l = try {
            best(fen, thinkMs, trap, trapElo)
        } catch (e: IllegalStateException) {
            throw java.io.IOException("illusion stopped")
        }
        lastPick = l?.move
        lastWasTrap = trap
        return listOfNotNull(l?.move)
    }

    fun close() {
        try {
            send("quit")
            proc.destroy()
        } catch (e: Exception) {
        }
    }
}
