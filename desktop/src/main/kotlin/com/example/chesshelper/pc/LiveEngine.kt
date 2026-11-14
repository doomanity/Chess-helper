package com.example.chesshelper.pc

import com.example.chesshelper.Line
import com.example.chesshelper.UciInfo
import java.io.BufferedWriter

/** what the engine thinks right now about one position: its lines, best first, and how deep it has looked */
class Snapshot(
    val fen: String,
    val depth: Int,
    val lines: List<Line>,
    val done: Boolean,          // the search ended by itself (a timed think, a book move)
    val trap: Boolean = false,  // illusion said its move is a trap
    val brilliant: Boolean = false // illusion said its move is a real sacrifice ("brilliant move !!")
)

/**
 * a UCI engine that keeps thinking and reports as it goes: every new line it prints (each depth, each of its
 * MultiPV lines) becomes a snapshot right away, so the arrows show within milliseconds and get better as it
 * looks deeper. analyze() moves it to a new position at once (the old search is stopped, its last words ignored).
 * runs stockfish or illusion
 */
class LiveEngine(private val path: String, val name: String) {
    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val out: BufferedWriter = proc.outputStream.bufferedWriter()
    private val lock = Object()

    // the search being reported, and how many stopped searches still owe their "bestmove"
    private var fen = ""
    private var multi = 1
    private var searching = false
    private var stale = 0
    private var listener: ((Snapshot) -> Unit)? = null
    private val lines = HashMap<Int, Line>()
    private var depth = 0
    private var trap = false
    private var brilliant = false

    val alive: Boolean get() = proc.isAlive

    init {
        send("uci")
        val r = proc.inputStream.bufferedReader()
        while (true) {
            val l = r.readLine() ?: throw IllegalStateException("$name didn't start")
            if (l.startsWith("uciok")) break
        }
        Thread({ readLoop(r) }, "$name reader").apply { isDaemon = true; start() }
    }

    private fun send(cmd: String) {
        synchronized(out) {
            out.write(cmd)
            out.newLine()
            out.flush()
        }
    }

    fun option(name: String, value: Any) = send("setoption name $name value $value")

    /** think about this position until told otherwise (ms = 0) or for ms milliseconds, reporting as it goes */
    fun analyze(fen: String, multipv: Int, ms: Int = 0, onUpdate: (Snapshot) -> Unit) {
        synchronized(lock) {
            if (searching) {
                send("stop")
                stale++
            }
            this.fen = fen
            multi = multipv
            listener = onUpdate
            lines.clear()
            depth = 0
            trap = false
            brilliant = false
            searching = true
        }
        if (name.startsWith("stockfish")) send("setoption name MultiPV value $multipv")
        send("position fen $fen")
        send(if (ms > 0) "go movetime $ms" else "go infinite")
    }

    /** stop thinking (nothing more is reported) */
    fun stop() {
        synchronized(lock) {
            listener = null
            if (searching) {
                send("stop")
                stale++
                searching = false
            }
        }
    }

    private fun readLoop(r: java.io.BufferedReader) {
        while (true) {
            val l = try { r.readLine() } catch (_: Exception) { null } ?: return
            var emit: Snapshot? = null
            var to: ((Snapshot) -> Unit)? = null
            synchronized(lock) {
                when {
                    l.startsWith("bestmove") -> {
                        if (stale > 0) {
                            stale-- // a stopped search's last word
                        } else if (searching) {
                            searching = false
                            // a search that ended by itself; a book move comes with no lines, use the move itself
                            if (lines.isEmpty()) {
                                val mv = l.split(" ").getOrNull(1)
                                if (mv != null && mv != "(none)" && mv != "0000") lines[1] = Line(1, mv, 0, null)
                            }
                            emit = snap(true)
                            to = listener
                        }
                    }
                    stale > 0 -> {} // still the old search talking
                    l.startsWith("info string trap") -> trap = true
                    l.startsWith("info string brilliant move") -> brilliant = true
                    l.startsWith("info ") && searching -> {
                        val info = UciInfo.parse(l)
                        if (info != null && info.multipv <= multi) {
                            val d = depthOf(l)
                            if (d > depth) depth = d
                            lines[info.multipv] = info
                            emit = snap(false)
                            to = listener
                        }
                    }
                }
            }
            val e = emit
            val t = to
            if (e != null && t != null) try { t(e) } catch (_: Exception) {}
        }
    }

    private fun snap(done: Boolean) = Snapshot(
        fen, depth, (1..multi).mapNotNull { lines[it] }, done, trap, brilliant
    )

    private fun depthOf(l: String): Int {
        val t = l.split(" ")
        val i = t.indexOf("depth")
        return if (i >= 0 && i + 1 < t.size) t[i + 1].toIntOrNull() ?: 0 else 0
    }

    fun close() {
        try {
            send("quit")
            proc.destroy()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** starts the first of these engine files that runs (a faster build that this cpu can't run is skipped) */
        fun startFirst(paths: List<String>, name: String): LiveEngine? {
            for (p in paths) {
                if (!java.io.File(p).exists()) continue
                try {
                    return LiveEngine(p, name)
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
