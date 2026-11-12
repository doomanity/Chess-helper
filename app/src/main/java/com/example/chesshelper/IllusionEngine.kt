package com.example.chesshelper

/**
 * Brody's own engine, Illusion (github.com/doomanity/Illusion), built for the phone and shipped as libillusion.so,
 * run as a separate program next to stockfish. in human mode (HumanMode) it plays with its personality: attacks
 * the king (Aggression), avoids draws (Contempt), trades when ahead, hunts sound sacrifices and sets traps for a
 * player of the given rating (OpponentElo, SwindleTolerance = how much a trap may cost). off = pure engine.
 * it says "info string trap ..." when it picks a trap and "info string brilliant move !!" for a real sacrifice.
 *
 * it shows one line (no MultiPV), so as a "lister" it adds its one pick and stockfish fills the rest. stockfish
 * still grades every move (the numbers on the card are always stockfish's), so the engines stay comparable.
 * it uses its hand made evaluation for now (no trained net loaded).
 */
class IllusionEngine(path: String) : MoveLister {
    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val writer = proc.outputStream.bufferedWriter()
    private val reader = proc.inputStream.bufferedReader()
    // what was last sent, so options only go out when they change
    private var sentHuman: Boolean? = null
    private var sentElo = -1
    private var sentAggression = -1
    private var sentContempt = -1
    private var sentTolerance = -1
    private var sentHunt = -1

    /** human mode for its picks, and its personality settings */
    @Volatile var humanMode = false
    @Volatile var trapElo = 1500
    @Volatile var aggression = 50
    @Volatile var contempt = 25
    @Volatile var tolerance = 60
    /** how much a brilliant sacrifice may cost vs the best move (centipawns). a sac that mates always counts */
    @Volatile var hunt = 30

    /** what the last search said about its move: a trap, a real sacrifice (its own "brilliant move !!") */
    @Volatile var lastFlaggedTrap = false
        private set
    @Volatile var lastFlaggedBrilliant = false
        private set
    /** how long it thinks for one pick (ms) */
    @Volatile var thinkMs = 500

    /** its last pick and whether that was a trap-mode pick (to tag it on the card) */
    @Volatile var lastPick: String? = null
        private set
    @Volatile var lastWasTrap = false
        private set
    @Volatile var lastWasBrilliant = false
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

    private fun configure(human: Boolean, elo: Int) {
        if (human) {
            if (elo != sentElo) { send("setoption name OpponentElo value ${elo.coerceIn(400, 2800)}"); sentElo = elo }
            if (aggression != sentAggression) { send("setoption name Aggression value ${aggression.coerceIn(0, 100)}"); sentAggression = aggression }
            if (contempt != sentContempt) { send("setoption name Contempt value ${contempt.coerceIn(0, 200)}"); sentContempt = contempt }
            if (tolerance != sentTolerance) { send("setoption name SwindleTolerance value ${tolerance.coerceIn(0, 300)}"); sentTolerance = tolerance }
            if (hunt != sentHunt) { send("setoption name BrilliantHunt value ${hunt.coerceIn(0, 100)}"); sentHunt = hunt }
        }
        if (human != sentHuman) {
            send("setoption name HumanMode value $human")
            sentHuman = human
        }
    }

    /**
     * its best move for a position (side to move's view), in trap mode or not. the score comes from its own search
     * (null for an opening book move, which answers without searching)
     */
    @Synchronized
    fun best(fen: String, ms: Int, human: Boolean = false, elo: Int = 1500): Line? {
        configure(human, elo)
        send("position fen $fen")
        send("go movetime $ms")
        lastFlaggedTrap = false
        lastFlaggedBrilliant = false
        var last: Line? = null
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("illusion stopped")
            if (line.startsWith("bestmove")) {
                val got = last
                val mv = line.split(" ").getOrNull(1) ?: return got
                if (mv == "(none)" || mv == "0000") return null
                return if (got != null && got.move == mv) got else Line(1, mv, got?.score ?: 0, got?.mate, listOf(mv))
            }
            if (line.startsWith("info string trap")) lastFlaggedTrap = true
            if (line.startsWith("info string brilliant move")) lastFlaggedBrilliant = true
            val info = UciInfo.parse(line)
            if (info != null) last = info
        }
    }

    /** as the engine for your moves: its one pick (human mode: with its personality and traps), stockfish adds the rest */
    override fun topMoves(fen: String, n: Int): List<String> {
        val l = try {
            best(fen, thinkMs, humanMode, trapElo)
        } catch (e: IllegalStateException) {
            throw java.io.IOException("illusion stopped")
        }
        lastPick = l?.move
        lastWasTrap = lastFlaggedTrap
        lastWasBrilliant = lastFlaggedBrilliant
        return listOfNotNull(l?.move)
    }

    /** false once the program has died */
    val alive: Boolean get() = proc.isAlive

    fun close() {
        try {
            send("quit")
            proc.destroy()
        } catch (e: Exception) {
        }
    }
}
