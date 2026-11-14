package com.example.chesshelper.pc

import com.example.chesshelper.BoardGeom
import com.example.chesshelper.BoardVision
import com.example.chesshelper.Cand
import com.example.chesshelper.Game
import com.example.chesshelper.Grade
import com.example.chesshelper.Line
import com.example.chesshelper.RemoteEngine
import com.example.chesshelper.Tracker
import com.example.chesshelper.UciEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * the pc helper's work: grab the screen, find the board, read it once it stops changing (the phone app's reader
 * and move follower), and keep the engines thinking about the position on screen. your turn: your best moves
 * stream in live. the bot's turn: its likely moves stream in, then your premove answer to each
 */
class Helper(private val st: Settings, private val overlay: Overlay, private val status: (String) -> Unit) {
    private val robot = Robot()
    private val screen: Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    private val engineDir: File = appDir()

    @Volatile var paused = false
    @Volatile private var running = true
    @Volatile private var wantNewGame = false

    private var tracker = newTracker()
    private var geom: BoardGeom? = null
    private var lastGeom: BoardGeom? = null
    private var prevThumb: IntArray? = null
    private var moving = false
    private var lastRead = 0L
    private var lastStep = Tracker.Step.NO_CHANGE
    private var lastFen: String? = null
    private var savedShapes = -1
    private val gen = AtomicInteger()

    // engines: stockfish thinking live, stockfish for quick premove answers, illusion (optional)
    private var sf: LiveEngine? = null
    private var answer: UciEngine? = null
    private var ill: LiveEngine? = null

    // what is being shown for the current position
    @Volatile private var sfSnap: Snapshot? = null
    @Volatile private var illPick: String? = null
    @Volatile private var illTrap = false
    @Volatile private var illBrilliant = false
    @Volatile private var guesses: List<Pair<String, Float?>> = emptyList()
    @Volatile private var answers: Map<String, String> = emptyMap()

    fun start() {
        Thread({ run() }, "chess helper").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        sf?.close(); answer?.close(); ill?.close()
    }

    /** settings changed: think again about the position on screen */
    fun settingsChanged() {
        if (st.engine != "illusion") { ill?.stop() }
        tracker.iAmWhite = st.colorMode != "black"
        tracker.autoColor = st.colorMode == "auto"
        lastFen = null
    }

    fun newGame() { wantNewGame = true }

    private fun newTracker() = Tracker(st.colorMode != "black", true).also {
        it.autoColor = st.colorMode == "auto"
        if (Store.shapes.exists()) try { it.templates.decode(Store.shapes.readText()) } catch (_: Exception) {}
    }

    private fun startEngines() {
        val cores = Runtime.getRuntime().availableProcessors()
        val sfPaths = listOf("stockfish.exe", "stockfish-compat.exe", "stockfish").map { File(engineDir, "engines/$it").path }
        sf = LiveEngine.startFirst(sfPaths, "stockfish")?.also {
            it.option("Threads", (cores / 2).coerceIn(1, 8))
            it.option("Hash", 256)
        }
        val used = sfPaths.firstOrNull { File(it).exists() && sf != null }
        answer = try { used?.let { UciEngine(it) } } catch (_: Exception) { null }
        if (sf == null) status("stockfish didn't start: engines folder missing next to the app")
    }

    private fun illusion(): LiveEngine? {
        if (ill?.alive == true) return ill
        val paths = listOf("illusion.exe", "illusion").map { File(engineDir, "engines/$it").path }
        ill = LiveEngine.startFirst(paths, "illusion")?.also {
            it.option("Hash", 64)
            it.option("OwnBook", true)
        }
        return ill
    }

    // ------------------------------------------------------------------ the loop

    private fun run() {
        startEngines()
        while (running) {
            try {
                if (paused) {
                    overlay.clear()
                    Thread.sleep(150)
                    continue
                }
                if (wantNewGame) {
                    wantNewGame = false
                    tracker = newTracker()
                    lastFen = null
                    overlay.clear()
                }
                tick()
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                status("error: ${e.message}")
                Thread.sleep(500)
            }
        }
    }

    private fun grab(): Triple<IntArray, Int, Int> {
        val img: BufferedImage = robot.createScreenCapture(screen)
        val w = img.width
        val h = img.height
        val px = if (img.type == BufferedImage.TYPE_INT_RGB || img.type == BufferedImage.TYPE_INT_ARGB) {
            (img.raster.dataBuffer as DataBufferInt).data
        } else img.getRGB(0, 0, w, h, null, 0, w)
        return Triple(px, w, h)
    }

    private fun tick() {
        val (px, w, h) = grab()
        val g = findBoard(px, w, h)
        if (g == null) {
            status("looking for the chess board...")
            overlay.clear()
            lastFen = null
            Thread.sleep(300)
            return
        }
        // read once the board holds still (a move animation, a dragged piece)
        val thumb = BoardVision.thumb(px, w, h, g)
        val changed = prevThumb == null || BoardVision.changed(prevThumb!!, thumb)
        prevThumb = thumb
        val now = System.currentTimeMillis()
        if (changed) {
            moving = true
            Thread.sleep(25)
            return
        }
        val retry = when (lastStep) {
            Tracker.Step.UNREADABLE, Tracker.Step.NEED_AI, Tracker.Step.RESYNC -> 600L
            Tracker.Step.HOLD -> 1000L
            else -> 2500L
        }
        if (!moving && now - lastRead < retry) {
            Thread.sleep(35)
            return
        }
        moving = false
        lastRead = now
        if (!overlay.hiddenFromCapture) {
            // older windows: capture sees our drawing, take the picture without it
            overlay.setHidden(true)
            Thread.sleep(40)
            val again = grab()
            overlay.setHidden(false)
            read(again.first, again.second, g, now)
        } else read(px, w, g, now)
    }

    private fun findBoard(px: IntArray, w: Int, h: Int): BoardGeom? {
        geom?.let { if (BoardVision.scoreGeom(px, w, h, it) >= 0.6) return it }
        val fresh = BoardVision.findBoard(px, w, h, null)
        if (fresh != null) {
            geom = fresh
            lastGeom = fresh
            return fresh
        }
        // the board doesn't move during a game: its last place still counts when it looks fairly board-like
        val keep = lastGeom ?: return null
        if (BoardVision.scoreGeom(px, w, h, keep) >= 0.5) {
            geom = keep
            return keep
        }
        geom = null
        return null
    }

    private fun read(px: IntArray, w: Int, g: BoardGeom, now: Long) {
        val reads = BoardVision.readSquares(px, w, g)
        val cls = BoardVision.classify(reads)
        if (cls == null) {
            lastStep = Tracker.Step.UNREADABLE
            status("can't tell the pieces apart on this board")
            return
        }
        val step = tracker.update(cls, reads, now)
        lastStep = step
        if (tracker.templates.version != savedShapes) {
            savedShapes = tracker.templates.version
            try { Store.shapes.writeText(tracker.templates.encode()) } catch (_: Exception) {}
        }
        val b = tracker.board
        if (b == null) {
            overlay.clear()
            status(if (!tracker.templates.complete) "start a game from the start position once, so I learn this board's pieces"
                else "reading the board...")
            return
        }
        if (b.fen != lastFen) {
            lastFen = b.fen
            onPosition(Board().apply { loadFromFen(b.fen) }, g, tracker.whiteBottom ?: true)
        }
        status("${step.name.lowercase().replace('_', ' ')} · shapes ${tracker.templates.known}/12 · ${engineLabel()}")
    }

    private fun engineLabel() = if (st.engine == "illusion") "illusion" + (if (st.humanMode) " · human vs ${st.oppElo}" else "") else "stockfish"

    // ------------------------------------------------------------------ thinking

    private fun onPosition(b: Board, g: BoardGeom, wb: Boolean) {
        val my = gen.incrementAndGet()
        val fen = b.fen
        val myTurn = (b.sideToMove == Side.WHITE) == tracker.meWhite()
        sfSnap = null
        illPick = null
        illTrap = false
        illBrilliant = false
        guesses = emptyList()
        answers = emptyMap()
        ill?.stop()
        overlay.update(View(g, wb, title = if (myTurn) "thinking..." else "bot's turn"))
        val engine = sf ?: return
        if (myTurn) {
            engine.analyze(fen, st.lines) { snap ->
                if (gen.get() != my) return@analyze
                sfSnap = snap
                showMine(b, g, wb, my)
            }
            if (st.engine == "illusion") illusion()?.let { e ->
                if (st.humanMode) {
                    e.option("HumanMode", true)
                    e.option("OpponentElo", st.oppElo.coerceIn(400, 2800))
                    e.option("Aggression", st.aggression.coerceIn(0, 100))
                    e.option("Contempt", st.contempt.coerceIn(0, 200))
                    e.option("SwindleTolerance", st.tolerance.coerceIn(0, 300))
                    e.option("BrilliantHunt", st.hunt.coerceIn(0, 100))
                    // traps and sacrifices need a clock: a short timed think, its pick joins the live arrows
                    e.analyze(fen, 1, st.illusionMs) { snap ->
                        if (gen.get() != my || !snap.done) return@analyze
                        illPick = snap.lines.firstOrNull()?.move
                        illTrap = snap.trap
                        illBrilliant = snap.brilliant
                        showMine(b, g, wb, my)
                    }
                } else {
                    e.option("HumanMode", false)
                    e.analyze(fen, 1) { snap ->
                        if (gen.get() != my) return@analyze
                        illPick = snap.lines.firstOrNull()?.move
                        showMine(b, g, wb, my)
                    }
                }
            }
        } else if (st.guess) {
            if (st.guessWith == "maia3") {
                // maia3 on this pc: the bot's most likely human-like moves, with their odds
                Thread {
                    val gs = try {
                        RemoteEngine(st.maiaHost, "23m", st.botElo).opponentGuesses(fen, st.botElo, 3)
                    } catch (_: Exception) { emptyList() }
                    if (gen.get() == my && gs.isNotEmpty()) {
                        guesses = gs
                        showGuesses(b, g, wb, my)
                        findAnswers(b, gs.map { it.first }, g, wb, my)
                    }
                }.apply { isDaemon = true; start() }
            }
            // stockfish's view of the bot's options streams in (and gives the guesses unless maia3 does)
            var answeredFor: List<String> = emptyList()
            engine.analyze(fen, 3) { snap ->
                if (gen.get() != my) return@analyze
                sfSnap = snap
                if (st.guessWith != "maia3") {
                    val top = snap.lines.map { it.move }
                    guesses = top.map { Pair(it, null) }
                    showGuesses(b, g, wb, my)
                    // answers once the guesses settle a bit (depth 10), again if they change before depth 18
                    if (snap.depth >= 10 && top.size == 3.coerceAtMost(legalCount(b)) && top != answeredFor && snap.depth <= 18) {
                        answeredFor = top
                        findAnswers(b, top, g, wb, my)
                    }
                }
            }
        }
    }

    private fun legalCount(b: Board) = try { b.legalMoves().size } catch (_: Exception) { 3 }

    /** your premove answer to each guess: a quick search of the position after it (stockfish, 120 ms each) */
    private fun findAnswers(b: Board, moves: List<String>, g: BoardGeom, wb: Boolean, my: Int) {
        val eng = answer ?: return
        Thread {
            val got = HashMap<String, String>()
            for (mv in moves) {
                if (gen.get() != my) return@Thread
                val after = try {
                    Board().apply { loadFromFen(b.fen); doMove(com.github.bhlangonijr.chesslib.move.Move(mv, b.sideToMove)) }.fen
                } catch (_: Exception) { continue }
                val ans = synchronized(eng) { try { eng.analyse(after, 120, 1).firstOrNull()?.move } catch (_: Exception) { null } }
                if (ans != null) got[mv] = ans
                if (gen.get() == my) {
                    answers = HashMap(got)
                    showGuesses(b, g, wb, my)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun showMine(b: Board, g: BoardGeom, wb: Boolean, my: Int) {
        val snap = sfSnap
        val fen = b.fen
        val lines: List<Line> = snap?.lines ?: emptyList()
        val best = lines.firstOrNull()?.score
        val arrows = ArrayList<MoveArrow>()
        val pick = illPick
        // illusion's pick first (with stockfish's grade when stockfish has a line for it)
        if (pick != null && pick.length >= 4) {
            val l = lines.firstOrNull { it.move == pick }
            val grade = if (l != null && best != null) Grade.of(best - l.score) else Grade.DECENT
            val eval = l?.let { Cand(it.move, it.score, it.mate, 0, 0).text } ?: "…"
            arrows.add(MoveArrow(pick.substring(0, 2), pick.substring(2, 4), grade, 1, Game.san(fen, pick), eval,
                tag = if (illBrilliant) "" else if (illTrap) " trap" else " *", brilliant = illBrilliant && grade != Grade.BLUNDER))
        }
        for (l in lines) {
            if (arrows.size >= st.lines) break
            if (l.move == pick) continue
            val grade = if (best != null) Grade.of(best - l.score) else Grade.BEST
            arrows.add(MoveArrow(l.move.substring(0, 2), l.move.substring(2, 4), grade, arrows.size + 1,
                Game.san(fen, l.move), Cand(l.move, l.score, l.mate, grade, 0).text))
        }
        if (gen.get() != my) return
        val (share, text) = evalOf(b, lines.firstOrNull())
        val title = (if (st.engine == "illusion") "illusion" else "stockfish") + (snap?.let { " · depth ${it.depth}" } ?: "")
        overlay.update(View(g, wb, mine = arrows, title = title, evalShare = share, evalText = text))
    }

    private fun showGuesses(b: Board, g: BoardGeom, wb: Boolean, my: Int) {
        val fen = b.fen
        val ans = answers
        val list = guesses.take(3).mapIndexedNotNull { i, (mv, p) ->
            if (mv.length < 4) return@mapIndexedNotNull null
            val a = ans[mv]
            val after = try {
                Board().apply { loadFromFen(fen); doMove(com.github.bhlangonijr.chesslib.move.Move(mv, b.sideToMove)) }.fen
            } catch (_: Exception) { null }
            GuessArrow(mv.substring(0, 2), mv.substring(2, 4), Game.san(fen, mv), p, i,
                a?.substring(0, 2), a?.substring(2, 4), if (a != null && after != null) Game.san(after, a) else null)
        }
        if (gen.get() != my) return
        val (share, text) = evalOf(b, sfSnap?.lines?.firstOrNull())
        overlay.update(View(g, wb, guesses = list, title = "bot's turn", evalShare = share, evalText = text))
    }

    /** the eval bar: white's share (0..1) and the number, from the best line (side to move's view) */
    private fun evalOf(b: Board, l: Line?): Pair<Float?, String?> {
        l ?: return Pair(null, null)
        val sign = if (b.sideToMove == Side.WHITE) 1 else -1
        val mateW = l.mate?.let { it * sign }
        val cpW = l.cp * sign
        val share = if (mateW != null) (if (mateW > 0) 1f else 0f)
            else (1.0 / (1.0 + Math.exp(-cpW.coerceIn(-1500, 1500) / 400.0))).toFloat()
        val text = if (mateW != null) (if (mateW > 0) "M$mateW" else "-M${-mateW}") else String.format(java.util.Locale.US, "%+.1f", cpW / 100.0)
        return Pair(share, text)
    }

    companion object {
        /** the folder the app runs from (the engines folder sits next to the app's files) */
        fun appDir(): File {
            System.getProperty("chesshelper.dir")?.let { return File(it) }
            val loc = File(Helper::class.java.protectionDomain.codeSource.location.toURI())
            return if (loc.isFile) loc.parentFile else loc
        }
    }
}
