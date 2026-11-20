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
class Helper(
    private val st: Settings, private val overlay: Overlay, private val report: (String) -> Unit,
    // where the screen pictures come from (the real screen; a test feeds recorded frames)
    private val source: (() -> Triple<IntArray, Int, Int>)? = null
) {
    private fun status(t: String) {
        lastStatus = t
        report(t)
    }

    private val robot by lazy { Robot() }
    private val screen: Rectangle by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds }
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
    @Volatile private var lastFrame: Triple<IntArray, Int, Int>? = null
    @Volatile private var lastStatus = ""
    private var lostSince = 0L
    private var savedLost = false

    /**
     * saves the last screen picture the helper took, with what it found and read, in %APPDATA%\ChessHelper\debug
     * (to check what went wrong). returns the folder
     */
    fun saveDebug(why: String): File? {
        val (px, w, h) = lastFrame ?: return null
        return try {
            val dir = File(Store.dir, "debug").also { it.mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            javax.imageio.ImageIO.write(img, "png", File(dir, "screen-$stamp.png"))
            val g = geom ?: lastGeom
            val score = g?.let { BoardVision.scoreGeom(px, w, h, it) }
            val info = buildString {
                appendLine("why: $why")
                appendLine("status: $lastStatus")
                appendLine("screen: ${w}x$h  board: $g  score there: $score  last board: $lastGeom")
                appendLine("found fresh: ${BoardVision.findBoard(px, w, h, null)}")
                appendLine("position: ${tracker.board?.fen}")
                appendLine("moves: ${tracker.playedMoves.takeLast(12)}")
                appendLine("shapes: ${tracker.templates.known}/12  overlay hidden from capture: ${overlay.hiddenFromCapture}")
            }
            File(dir, "screen-$stamp.txt").writeText(info)
            dir
        } catch (_: Exception) {
            null
        }
    }

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

    // the position on screen right now (for redrawing when a hint starts or ends)
    private class Ctx(val b: Board, val g: BoardGeom, val wb: Boolean, val my: Int, val myTurn: Boolean)
    @Volatile private var ctx: Ctx? = null
    // hint: the best moves show until this time, even with the arrows hidden
    @Volatile private var hintUntil = 0L
    // hint asked for on the bot's turn: it shows as soon as it's your turn
    @Volatile private var hintPending = false

    /** a finished game (or the one on screen) for the review: called when a game ends on the board or a new one starts */
    var onGameOver: ((GameRecord, Boolean) -> Unit)? = null
    private var reviewedKey = ""
    @Volatile var stockfishPath: String? = null
        private set

    /** the game on screen so far (null before 2 moves) */
    fun currentGame(): GameRecord? {
        val moves = ArrayList(tracker.playedMoves)
        if (moves.size < 2 || tracker.startFen.isEmpty()) return null
        return GameRecord(tracker.startFen, moves, tracker.meWhite())
    }

    /** the hint: your best moves for 10 seconds (on the bot's turn, as soon as it's your turn) */
    fun hint() {
        val c = ctx
        if (c == null) {
            status("no position yet: the hint works once the board is read")
            return
        }
        if (!c.myTurn) {
            hintPending = true
            status("hint ready for your turn")
            return
        }
        startHint(c)
    }

    private fun hintOn() = System.currentTimeMillis() < hintUntil

    private fun startHint(c: Ctx) {
        hintPending = false
        hintUntil = System.currentTimeMillis() + HINT_MS
        showMine(c.b, c.g, c.wb, c.my)
        // the countdown on the card, then the arrows go away again
        Thread {
            while (gen.get() == c.my && hintOn()) {
                Thread.sleep(1000)
                if (gen.get() == c.my) showMine(c.b, c.g, c.wb, c.my)
            }
        }.apply { isDaemon = true; start() }
    }

    fun start() {
        Thread({ run() }, "chess helper").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        sf?.close(); answer?.close(); ill?.close()
    }

    /** settings changed: think again about the position on screen */
    fun settingsChanged() {
        if (st.engine != "illusion" && !st.alwaysBrilliant) { ill?.stop() }
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
        stockfishPath = used
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
                status("error: ${e::class.java.simpleName} ${e.message ?: ""}")
                e.printStackTrace()
                Thread.sleep(500)
            }
        }
    }

    private fun grab(): Triple<IntArray, Int, Int> {
        source?.let { return it() }
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
        lastFrame = Triple(px.copyOf(), w, h)
        var g = findBoard(px, w, h)
        val now0 = System.currentTimeMillis()
        if (g == null) {
            if (lostSince == 0L) lostSince = now0
            // a moment without the board (a highlight, a dragged piece, a popup): keep the last one for 1.5 s
            val keep = lastGeom
            if (keep != null && now0 - lostSince < 1500) {
                g = keep
            } else {
                status("looking for the chess board...")
                // save what it saw once, so it can be checked (one picture per lost board)
                if (!savedLost) { savedLost = true; saveDebug("lost the board") }
                overlay.clear()
                lastFen = null
                Thread.sleep(300)
                return
            }
        } else {
            lostSince = 0L
            savedLost = false
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
        var fresh = BoardVision.findBoard(px, w, h, null)
        if (fresh != null) {
            // found again a pixel or two off: the same board (a new spot each frame would look like constant motion)
            val prev = lastGeom
            if (prev != null && kotlin.math.abs(prev.left - fresh.left) <= 4 && kotlin.math.abs(prev.top - fresh.top) <= 4 &&
                kotlin.math.abs(prev.size - fresh.size) <= 6) fresh = prev
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
        // the game so far, in case this read starts a new one (then the old one is ready for its review)
        val prevStart = tracker.startFen
        val prevMoves = ArrayList(tracker.playedMoves)
        val prevWhite = tracker.meWhite()
        val step = tracker.update(cls, reads, now)
        lastStep = step
        if (step == Tracker.Step.STARTED && prevMoves.size >= 4 && prevStart.isNotEmpty()) {
            val key = "$prevStart ${prevMoves.joinToString(" ")}"
            if (key != reviewedKey) {
                reviewedKey = key
                gameOver(GameRecord(prevStart, prevMoves, prevWhite), false)
            }
        }
        if (tracker.templates.version != savedShapes) {
            savedShapes = tracker.templates.version
            try { Store.shapes.writeText(tracker.templates.encode()) } catch (_: Exception) {}
        }
        val b = tracker.board
        if (step == Tracker.Step.UNREADABLE || step == Tracker.Step.RESYNC) {
            // lost track: old arrows would point at a position that isn't there any more, take them off
            gen.incrementAndGet()
            sf?.stop()
            ill?.stop()
            overlay.clear()
            lastFen = null
            status("lost track of the moves, reading the board again · shapes ${tracker.templates.known}/12")
            return
        }
        if (b == null) {
            overlay.clear()
            status(if (!tracker.templates.complete) "start a game from the start position once, so I learn this board's pieces"
                else "reading the board...")
            return
        }
        if (b.fen != lastFen) {
            lastFen = b.fen
            onPosition(Board().apply { loadFromFen(b.fen) }, g, tracker.whiteBottom ?: true)
            // checkmate, stalemate or a dead draw on the board: the game is over, open its review
            val over = try { b.isMated || b.isStaleMate || b.isInsufficientMaterial } catch (_: Exception) { false }
            if (over) currentGame()?.let { rec ->
                val key = "${rec.startFen} ${rec.moves.joinToString(" ")}"
                if (key != reviewedKey) {
                    reviewedKey = key
                    gameOver(rec, true)
                }
            }
        }
        status("${step.name.lowercase().replace('_', ' ')} · shapes ${tracker.templates.known}/12 · ${engineLabel()}")
    }

    private fun gameOver(rec: GameRecord, ended: Boolean) {
        try { rec.savePgn() } catch (_: Exception) {}
        onGameOver?.invoke(rec, ended)
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
        ctx = Ctx(b, g, wb, my, myTurn)
        // a new position ends the hint (you moved, or the bot did)
        hintUntil = 0L
        overlay.update(View(g, wb, title = if (st.hideArrows) hiddenTitle(myTurn) else if (myTurn) "thinking..." else "bot's turn"))
        val engine = sf ?: return
        if (myTurn && hintPending) startHint(ctx!!)
        if (myTurn) {
            // with the arrows hidden the hint shows a few moves, so stockfish keeps at least 3 lines
            engine.analyze(fen, if (st.hideArrows) maxOf(st.lines, 3) else st.lines) { snap ->
                if (gen.get() != my) return@analyze
                sfSnap = snap
                showMine(b, g, wb, my)
            }
            // illusion runs for your arrows, or just to spot brilliant moves ("always show brilliant moves")
            if (st.engine == "illusion" || st.alwaysBrilliant) illusion()?.let { e ->
                if (st.humanMode || st.engine != "illusion") {
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

    private fun hiddenTitle(myTurn: Boolean) =
        if (myTurn) (if (hintPending) "hint coming..." else "arrows hidden · F2 for a hint") else "bot's turn · arrows hidden"

    private fun showMine(b: Board, g: BoardGeom, wb: Boolean, my: Int) {
        val hint = hintOn()
        val pick = illPick
        val brilliantPick = st.alwaysBrilliant && illBrilliant && pick != null && pick.length >= 4
        if (st.hideArrows && !hint) {
            if (gen.get() != my) return
            // arrows hidden, but a brilliant move still shows (its blue "!!" arrow alone)
            val bl = brilliantArrow(b, pick)
            if (brilliantPick && bl != null) overlay.update(View(g, wb, mine = listOf(bl), title = "brilliant move !!"))
            else overlay.update(View(g, wb, title = hiddenTitle(true)))
            return
        }
        val shown = if (hint && st.hideArrows) maxOf(st.lines, 3) else st.lines
        val snap = sfSnap
        val fen = b.fen
        val lines: List<Line> = snap?.lines ?: emptyList()
        val best = lines.firstOrNull()?.score
        val arrows = ArrayList<MoveArrow>()
        // illusion's pick first (with stockfish's grade when stockfish has a line for it). with stockfish as the
        // engine, illusion only adds its pick when it's a brilliant move
        if (pick != null && pick.length >= 4 && (st.engine == "illusion" || brilliantPick)) {
            val l = lines.firstOrNull { it.move == pick }
            val grade = if (l != null && best != null) Grade.of(best - l.score) else Grade.DECENT
            val eval = l?.let { Cand(it.move, it.score, it.mate, 0, 0).text } ?: "…"
            arrows.add(MoveArrow(pick.substring(0, 2), pick.substring(2, 4), grade, 1, Game.san(fen, pick), eval,
                tag = if (illBrilliant) "" else if (illTrap) " trap" else " *", brilliant = illBrilliant && grade != Grade.BLUNDER))
        }
        for (l in lines) {
            if (arrows.size >= shown) break
            if (l.move == pick) continue
            val grade = if (best != null) Grade.of(best - l.score) else Grade.BEST
            arrows.add(MoveArrow(l.move.substring(0, 2), l.move.substring(2, 4), grade, arrows.size + 1,
                Game.san(fen, l.move), Cand(l.move, l.score, l.mate, grade, 0).text))
        }
        if (gen.get() != my) return
        val (share, text) = evalOf(b, lines.firstOrNull())
        val left = ((hintUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        val title = (if (hint) "hint · ${left}s · " else "") +
            (if (st.engine == "illusion") "illusion" else "stockfish") + (snap?.let { " · depth ${it.depth}" } ?: "")
        overlay.update(View(g, wb, mine = arrows, title = title, evalShare = share, evalText = text))
    }

    /** illusion's brilliant pick as a blue "!!" arrow (null when stockfish thinks it's a blunder) */
    private fun brilliantArrow(b: Board, pick: String?): MoveArrow? {
        if (pick == null || pick.length < 4) return null
        val lines = sfSnap?.lines ?: emptyList()
        val best = lines.firstOrNull()?.score
        val l = lines.firstOrNull { it.move == pick }
        val grade = if (l != null && best != null) Grade.of(best - l.score) else Grade.DECENT
        if (grade == Grade.BLUNDER) return null
        val eval = l?.let { Cand(it.move, it.score, it.mate, 0, 0).text } ?: "…"
        return MoveArrow(pick.substring(0, 2), pick.substring(2, 4), grade, 1, Game.san(b.fen, pick), eval, brilliant = true)
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
        if (st.hideArrows) {
            overlay.update(View(g, wb, title = hiddenTitle(false)))
            return
        }
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
        const val HINT_MS = 10_000L

        /** the folder the app runs from (the engines folder sits next to the app's files) */
        fun appDir(): File {
            System.getProperty("chesshelper.dir")?.let { return File(it) }
            val loc = File(Helper::class.java.protectionDomain.codeSource.location.toURI())
            return if (loc.isFile) loc.parentFile else loc
        }
    }
}
