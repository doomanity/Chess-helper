package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side

/**
 * Follows the game on the screen. It sees which squares have a white or black piece (and, once it has learned
 * what the pieces look like, which type they are) and finds the moves that explain what changed.
 * The AI is only needed to read a game that doesn't start from the normal position, or if we are completely lost
 * and can't recognize the pieces ourselves.
 */
class Tracker(var iAmWhite: Boolean, private val myTurnAtStart: Boolean) {
    enum class Step { NEED_AI, STARTED, NO_CHANGE, MOVED, UNREADABLE, RESYNC, RESET, HOLD }

    // a picture that only lasts a moment: your premove drawn by the chess app before the bot moved, or a piece you
    // are holding (lifted off its square). we wait it out instead of believing it
    private var holdScreen: IntArray? = null
    // we know for sure whose turn it is: a normal game start, or we have seen a move since. only then can a move of
    // yours on the bot's turn be a premove (right after a guessed start it means our guess was wrong)
    private var turnSure = false
    private var holdSince = 0L
    private var holdCount = 0

    /** true if this screen is the same moment we are already waiting out, for long enough to stop waiting */
    private fun heldTooLong(screen: IntArray, nowMs: Long): Boolean {
        val hs = holdScreen
        if (hs == null || Game.mismatch(hs, screen) > 0) {
            holdScreen = screen.copyOf()
            holdSince = nowMs
            holdCount = 1
            return false
        }
        holdCount++
        return if (nowMs == 0L) holdCount > 3 else nowMs - holdSince > HOLD_MS
    }

    /** auto color: your side is the one at the bottom of the screen (every chess app shows your pieces there) */
    var autoColor = false

    /** which side is yours: the bottom side in auto mode, else the setting */
    fun meWhite(): Boolean = if (autoColor) (whiteBottom ?: iAmWhite) else iAmWhite

    private fun me(wb: Boolean): Boolean = if (autoColor) wb else iAmWhite

    /** what the pieces of this chess app look like, learned from a position we are sure about */
    val templates = PieceTemplates()

    var board: Board? = null
        private set
    var whiteBottom: Boolean? = null
        private set

    /** true after a pawn promoted and we can't tell which piece it became: the AI should look once */
    var needsVerify = false
        private set

    /** for the debug line: how many moves the last change was, how many piece types looked wrong */
    var lastPlies = 0
        private set
    var lastTypeMismatch = 0
        private set

    /**
     * a record of the game for the review screen: the position it starts from and the moves played since (like e2e4).
     * it starts over when the board has to be read again from scratch. logVersion goes up when it changes
     */
    var startFen: String = ""
        private set
    val playedMoves = ArrayList<String>()
    var logVersion = 0
        private set

    /** goes up every time the record starts over (a new game, or the board had to be read again) */
    var logEpoch = 0
        private set

    private fun restartLog(b: Board) {
        turnSure = false
        startFen = b.fen
        playedMoves.clear()
        logVersion++
        logEpoch++
        promo.clear()
    }

    /**
     * pieces that came from a promotion, and how sure we are of their type. right after a promotion the screen is
     * often still animating (or the piece picker is closing), so the piece can be misread. we keep checking what we
     * see on that square for the next reads and fix the piece if it clearly is something else.
     * chess square index -> [which entry of playedMoves made it, times it looked different, times it looked right]
     */
    private val promo = HashMap<Int, IntArray>()
    private var retrying = false

    /** true while a promoted piece still needs a few more looks (the service reads the board again soon) */
    val promoUnsure: Boolean get() = promo.values.any { it[2] < 2 }

    private var misses = 0
    private var missSince = 0L
    private var missScreen: IntArray? = null
    private var suspicious = 0

    companion object {
        const val START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        const val MAX_PLY = 3
        const val REWIND_PLIES = 40  // how far back a takeback can go and still be followed
        const val HOLD_MS = 9000L   // the longest we wait out a premove picture or a lifted piece
        const val LOST_MS = 2500L   // how long a position we can't explain has to stay on screen before we start over
        const val SUSPECT = 3       // this many piece types that look wrong means our board is probably wrong
    }

    /** a promoted piece moved (keep watching it on its new square) or was taken (stop) */
    private fun followPromo(from: Int, to: Int) {
        promo.remove(to)
        promo.remove(from)?.let { promo[to] = it }
    }

    /**
     * looks at the promoted pieces again. two reads in a row that clearly show another piece (knight, bishop, rook
     * or queen of the same color) fix it, and the move in the record gets the right piece too
     */
    private fun checkPromoted(b: Board, seenChess: IntArray) {
        if (promo.isEmpty()) return
        val done = ArrayList<Int>()
        for ((sq, st) in promo.entries.map { Pair(it.key, it.value) }) {
            val piece = b.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(sq))
            val have = Game.typeCode(piece)
            if (have !in 2..5) { done.add(sq); continue } // not a promoted piece any more
            val seen = seenChess[sq]
            when {
                seen == have -> { st[2]++; st[1] = 0 }
                seen in 2..5 -> st[1]++
                else -> {} // can't tell (covered): no vote
            }
            if (st[1] >= 2) {
                val fixed = Game.withPieceType(b.fen, sq, seen) ?: continue
                val nb = Board()
                nb.loadFromFen(fixed)
                board = nb
                val idx = st[0]
                if (idx in playedMoves.indices && playedMoves[idx].length == 5) {
                    playedMoves[idx] = playedMoves[idx].substring(0, 4) + "?nbrq"[seen - 1]
                    logVersion++
                }
                st[1] = 0
                st[2] = 1
                return // the board object changed, look again next time
            }
            if (st[2] >= 3) done.add(sq) // seen right a few times: sure now
        }
        for (sq in done) promo.remove(sq)
    }

    /** true if the screen shows exactly the start position: white at the bottom (true), at the top (false), or not (null) */
    private fun startOrientation(screen: IntArray): Boolean? {
        val start = Game.startOcc()
        if (Game.mismatch(Game.screenToChess(screen, true), start) == 0) return true
        if (Game.mismatch(Game.screenToChess(screen, false), start) == 0) return false
        return null
    }

    /** the game we follow gets to the start position with a move or two (knights out and back home): not a new game */
    private fun reachesStart(b: Board, startWb: Boolean): Boolean {
        if (whiteBottom != startWb) return false
        val start = Game.startOcc()
        return (1..2).any { d -> Game.hitsAt(b, start, d).any { it.mis == 0 } }
    }

    private fun atStart(b: Board): Boolean = Game.placement(b) == START_PLACEMENT && b.sideToMove == Side.WHITE

    /** forget the game, the next update asks for a fresh read. (what the pieces look like is kept) */
    fun forget() {
        board = null
        whiteBottom = null
        needsVerify = false
        clearMisses()
        suspicious = 0
    }

    private fun clearMisses() {
        misses = 0
        missScreen = null
    }

    /**
     * screen = 64 values (0 empty, 1 white, 2 black), row 0 is the top of the screen.
     * reads = what the vision saw per square (for the piece shapes), nowMs = a clock (any unit of milliseconds)
     */
    /**
     * one read of the screen. the moves are followed (that knows whose turn it is, castling, the move list), and
     * the result is checked against a full read of every piece (the learned shapes). once the shapes are learned,
     * that full read is the truth for where the pieces are: when it is sure of every piece, legal, and the same on
     * two reads in a row while our board says something else, the board becomes what the read shows. so a wrong
     * board can't stay wrong, and losing track never needs the AI
     */
    fun update(screen: IntArray, reads: Array<SquareRead>? = null, nowMs: Long = 0L): Step {
        val before = board?.let { Board().apply { loadFromFen(it.fen) } }
        val step = follow(screen, reads, nowMs)
        if (reads == null || step == Step.HOLD || step == Step.STARTED) {
            if (step != Step.HOLD) truthRead = null
            return step
        }
        // the moves this read added to the list (taken back if the full read disagrees with them)
        val added = if (step == Step.MOVED) lastPlies else 0
        val base = if (step == Step.RESET || step == Step.NEED_AI) null else before
        return trustFullRead(screen, reads, nowMs, base, added) ?: step
    }

    // the full read that disagrees with our board, and since when
    private var truthRead: String? = null
    private var truthSince = 0L
    private var truthCount = 0

    /** every piece read from its shape, as a FEN placement. null when any piece isn't recognized for sure */
    fun fullRead(screen: IntArray, reads: Array<SquareRead>, wb: Boolean): String? {
        if (!templates.complete) return null
        val seen = templates.recognizeAll(reads, screen)
        for (i in 0 until 64) if (screen[i] != BoardVision.EMPTY && seen[i] == 0) return null
        val placement = Game.placementFromTypes(Game.screenToChess(screen, wb), Game.screenToChess(seen, wb))
        return if (Game.isValidPlacement(placement)) placement else null
    }

    private fun trustFullRead(screen: IntArray, reads: Array<SquareRead>, nowMs: Long, before: Board?, added: Int): Step? {
        val wb = whiteBottom ?: BoardVision.whiteBottomOf(screen) ?: return null
        val read = fullRead(screen, reads, wb) ?: return null
        val cur = board
        if (cur != null && Game.placement(cur) == read) {
            truthRead = null
            return null
        }
        if (read != truthRead) {
            truthRead = read
            truthSince = nowMs
            truthCount = 1
            return null
        }
        truthCount++
        // the old board to compare with: what we had before this read (or now, if there was none)
        val old = before ?: cur
        val mine = if (whiteBottom ?: wb) BoardVision.WHITE else BoardVision.BLACK
        val theirs = 3 - mine
        val readB = Game.newBoard(read, true)
        val a = old?.let { Game.occOf(it) }
        val r = Game.occOf(readB)
        // only one of your pieces gone and nothing new anywhere: usually your hand on it, give it 3 seconds
        val handOnPiece = a != null && (0 until 64).count { a[it] != r[it] } == 1 &&
            (0 until 64).any { a[it] == mine && r[it] == BoardVision.EMPTY }
        val waited = if (nowMs == 0L) truthCount >= (if (handOnPiece) 5 else 2)
            else truthCount >= 2 && (!handOnPiece || nowMs - truthSince >= 3000L)
        if (!waited) return null

        // whose turn: a king in check must move; else whose pieces changed (the side that moved waits); else the
        // site's last move highlight; else as it was
        var mineChanged = false
        var theirsChanged = false
        if (a != null) for (i in 0 until 64) {
            if (a[i] == r[i]) continue
            if (a[i] == mine || r[i] == mine) mineChanged = true
            if (a[i] == theirs || (r[i] == theirs && a[i] != mine)) theirsChanged = true
        }
        val meW = mine == BoardVision.WHITE
        val whiteToMove = Game.turnFromChecks(read)
            ?: when {
                mineChanged && !theirsChanged -> !meW   // you moved: the bot's turn
                theirsChanged && !mineChanged -> meW    // the bot moved: yours
                else -> lastMoverWhite(screen, reads, wb)?.not() ?: (old?.sideToMove == Side.WHITE)
            }
        // keep the move list when real moves (up to 3) lead there, else start the list from here
        if (old != null) {
            for (d in 1..3) {
                val hit = Game.hitsAt(old, Game.occOf(readB), d).firstOrNull { h ->
                    Game.play(old, h)
                    val ok = Game.placement(old) == read
                    repeat(h.path.size) { old.undoMove() }
                    ok
                }
                if (hit != null) {
                    if (whiteBottom == null) whiteBottom = wb
                    val nb = Board().apply { loadFromFen(old.fen) }
                    Game.play(nb, hit)
                    // this read's own (wrong) moves come off the list first
                    if (before != null) repeat(added) { if (playedMoves.isNotEmpty()) playedMoves.removeAt(playedMoves.size - 1) }
                    board = nb
                    for (m in hit.path) playedMoves.add(m.toString())
                    logVersion++
                    lastPlies = hit.path.size
                    clearMisses()
                    truthRead = null
                    return Step.MOVED
                }
            }
        }
        if (whiteBottom == null) whiteBottom = wb
        val nb = Game.newBoard(read, whiteToMove)
        board = nb
        restartLog(nb)
        clearMisses()
        lastPlies = 0
        truthRead = null
        return Step.RESET
    }

    /**
     * which side made the last move, from the site's highlight: chess sites tint the two squares of the last move.
     * the board has two normal square colors; a square whose background is neither is highlighted. the piece on a
     * highlighted square is the one that just moved. null when there is no clear highlight
     */
    fun lastMoverWhite(screen: IntArray, reads: Array<SquareRead>, wb: Boolean): Boolean? {
        val lit = litSquares(reads)
        if (lit.isEmpty() || lit.size > 3) return null // none, or not a clear two-square highlight
        val movers = lit.map { screen[it] }.filter { it != BoardVision.EMPTY }.distinct()
        if (movers.size != 1) return null
        return movers[0] == BoardVision.WHITE
    }

    /** the screen squares (row 0 = top) whose background is tinted: neither of the board's two square colors */
    fun litSquares(reads: Array<SquareRead>): List<Int> {
        fun dist(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
            val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
            val db = (a and 255) - (b and 255)
            return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
        }
        // the normal light and dark square colors: the most common background on each square color
        fun usual(light: Boolean): Int? {
            val bgs = (0 until 64).filter { ((it / 8 + it % 8) % 2 == 0) == light }.map { reads[it].bg }.filter { it != 0 }
            if (bgs.isEmpty()) return null
            return bgs.maxByOrNull { b -> bgs.count { dist(it, b) < 30 } }
        }
        val lightBg = usual(true) ?: return emptyList()
        val darkBg = usual(false) ?: return emptyList()
        return (0 until 64).filter { i ->
            val bg = reads[i].bg
            val normal = if ((i / 8 + i % 8) % 2 == 0) lightBg else darkBg
            bg != 0 && dist(bg, normal) > 45
        }
    }

    private fun follow(screen: IntArray, reads: Array<SquareRead>?, nowMs: Long): Step {
        // a new game: the start position on the screen, either way up. the way it faces says which side is yours
        // (the app shows your pieces at the bottom), so a game as black after a game as white just works
        val startWb = startOrientation(screen)
        val cur = board
        if (startWb != null && cur != null && !(atStart(cur) && whiteBottom == startWb) && !reachesStart(cur, startWb)) {
            val b = Board()
            board = b
            whiteBottom = startWb
            restartLog(b)
            turnSure = true
            needsVerify = false
            suspicious = 0
            clearMisses()
            if (reads != null) learnFrom(b, startWb, screen, reads, all = true)
            return Step.STARTED
        }
        val known = board
        if (known == null || whiteBottom == null) {
            whiteBottom = BoardVision.whiteBottomOf(screen) ?: whiteBottom ?: iAmWhite
        }
        val wb = whiteBottom!!
        val occ = Game.screenToChess(screen, wb)
        if (known == null) {
            if (Game.mismatch(occ, Game.startOcc()) <= 1) {
                val b = Board() // standard starting position, white to move
                board = b
                restartLog(b)
                turnSure = true
                needsVerify = false
                suspicious = 0
                clearMisses()
                if (reads != null) learnFrom(b, wb, screen, reads, all = true)
                return Step.STARTED
            }
            return Step.NEED_AI
        }

        val seen = if (reads != null) templates.recognizeAll(reads, screen) else null
        val seenChess = seen?.let { Game.screenToChess(it, wb) }

        // every explanation gets a cost: 2 per move, 3 per misread square, 3 extra if we have to
        // assume we had the wrong side to move. the cheapest wins. a one-square near-hit only counts
        // when it is the only one at that length, so a half-drawn frame can't pick a random move
        val other = Game.newBoard(Game.placement(known), known.sideToMove == Side.BLACK)
        var bestCost = Int.MAX_VALUE
        var bestHit: Game.Hit? = null
        var bestOther = false
        var bestD = 0
        for (d in 0..MAX_PLY) {
            if (bestCost <= 2 * d) break // nothing deeper can be cheaper
            for (useOther in booleanArrayOf(false, true)) {
                if (useOther && d == 0) continue
                val b = if (useOther) other else known
                val all = Game.hitsAt(b, occ, d)
                val exact = all.filter { it.mis == 0 }
                val near = all.filter { it.mis == 1 }
                val extra = if (useOther) 3 else 0
                if (exact.isNotEmpty() && 2 * d + extra < bestCost) {
                    bestCost = 2 * d + extra
                    bestHit = Game.pick(exact, b, seenChess)
                    bestOther = useOther
                    bestD = d
                } else if (exact.isEmpty() && near.size == 1 && !useOther && d == 0 && 2 * d + 3 < bestCost) {
                    bestCost = 2 * d + 3
                    bestHit = near[0]
                    bestOther = useOther
                    bestD = d
                }
            }
        }
        val hit0 = bestHit
        // your premove: it is the bot's turn and the screen shows one move of yours. the chess app draws a premove
        // before the bot has moved, so wait for the bot (then both moves show and are read normally). if it stays
        // like that for long, we really had the wrong side to move after all
        val botsTurn = (known.sideToMove == Side.WHITE) != meWhite()
        if (hit0 != null && bestOther && bestD == 1 && botsTurn && turnSure && whiteBottom != null && !heldTooLong(screen, nowMs)) {
            return Step.HOLD
        }
        // a piece you are holding: one of your squares is empty and nothing else changed
        if (hit0 == null) {
            val knownOcc = Game.occOf(known)
            val diff = (0 until 64).filter { knownOcc[it] != occ[it] }
            if (diff.size == 1 && occ[diff[0]] == 0) {
                val p = known.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(diff[0]))
                val mine = p != com.github.bhlangonijr.chesslib.Piece.NONE && (p.pieceSide == Side.WHITE) == meWhite()
                if (mine && !heldTooLong(screen, nowMs)) return Step.HOLD
            }
        }
        if (hit0 != null) holdScreen = null
        val hit = hit0
        if (System.getProperty("tdebug") != null) println("        [trk] known=${known.fen} mis0=${Game.mismatch(Game.occOf(known), occ)} -> other=$bestOther d=$bestD cost=$bestCost mis=${hit?.mis} path=${hit?.path}")
        if (hit != null) {
            val target = if (bestOther) other else known
            if (bestOther) restartLog(target) // we had the wrong side to move: the record starts here
            Game.play(target, hit)
            if (hit.path.isNotEmpty()) {
                for (m in hit.path) {
                    followPromo(m.from.ordinal, m.to.ordinal)
                    playedMoves.add(m.toString())
                    if (m.promotion != com.github.bhlangonijr.chesslib.Piece.NONE) promo[m.to.ordinal] = intArrayOf(playedMoves.size - 1, 0, 0)
                }
                logVersion++
            }
            board = target
            clearMisses()
            lastPlies = bestD
            if (bestD > 0 && !bestOther) turnSure = true
            if (Game.hasPromotion(hit)) {
                // with the piece shapes known we already picked the piece that fits what we see
                needsVerify = !templates.complete
            }
            if (seenChess != null) checkPromoted(target, seenChess)
            if (seenChess != null && reads != null) {
                val mis = Game.typeMismatch(board!!, seenChess)
                lastTypeMismatch = mis
                suspicious = if (mis >= SUSPECT) suspicious + 1 else 0
                if (suspicious >= 3 && resyncFromVision(screen, seen!!, reads, wb)) return Step.RESET
                if (mis == 0 && !templates.complete) learnFrom(board!!, wb, screen, reads, all = false)
            }
            return if (bestD == 0) Step.NO_CHANGE else Step.MOVED
        }

        // we can't explain what we see. a misread promoted piece is a common reason (a "queen" that just moved like
        // a knight). try the promoted pieces as the other types: the piece the screen shows there first, then any
        // type that explains the new position. if one fits, fix the piece and read this screen again
        if (!retrying && promo.isNotEmpty()) {
            for ((sq, st) in promo.entries.map { Pair(it.key, it.value) }) {
                val have = Game.typeCode(known.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(sq)))
                if (have !in 2..5) continue
                val seenT = seenChess?.get(sq) ?: 0
                val order = (listOf(seenT) + listOf(5, 2, 4, 3)).filter { it in 2..5 && it != have }.distinct()
                for (t in order) {
                    val fixed = Game.withPieceType(known.fen, sq, t) ?: continue
                    val nb = Board()
                    nb.loadFromFen(fixed)
                    val fits = t == seenT || (1..MAX_PLY).any { d -> Game.hitsAt(nb, occ, d).any { it.mis == 0 } }
                    if (!fits) continue
                    board = nb
                    val idx = st[0]
                    if (idx in playedMoves.indices && playedMoves[idx].length == 5) {
                        playedMoves[idx] = playedMoves[idx].substring(0, 4) + "?nbrq"[t - 1]
                        logVersion++
                    }
                    retrying = true
                    try {
                        val again = follow(screen, reads, nowMs)
                        if (again != Step.UNREADABLE && again != Step.RESYNC) return again
                    } finally {
                        retrying = false
                    }
                    board = known // that didn't help: put it back
                    if (idx in playedMoves.indices && playedMoves[idx].length == 5) {
                        playedMoves[idx] = playedMoves[idx].substring(0, 4) + "?nbrq"[have - 1]
                    }
                }
            }
        }
        // moves taken back (a coach's "rewinding", a takeback): the screen shows a position from earlier in this
        // game, or one or two moves after it. go back there instead of reading the board again
        rewind(occ, seenChess)?.let { return it }
        val ms = missScreen
        if (ms == null || Game.mismatch(ms, screen) > 0) {
            missScreen = screen.copyOf()
            missSince = nowMs
            misses = 0
        }
        misses++
        val lostLongEnough = if (nowMs == 0L) misses >= 3 else misses >= 2 && nowMs - missSince >= LOST_MS
        if (lostLongEnough) {
            if (seen != null && reads != null && resyncFromVision(screen, seen, reads, wb)) return Step.RESET
            return Step.RESYNC
        }
        return Step.UNREADABLE
    }

    /**
     * the newest earlier position of this game (up to REWIND_PLIES back) that the screen shows exactly, or that one
     * or two moves after it lead to. the record is cut back to it. null when none fits
     */
    private fun rewind(occ: IntArray, seenChess: IntArray?): Step? {
        if (startFen.isEmpty() || playedMoves.isEmpty()) return null
        val start = Board()
        try { start.loadFromFen(startFen) } catch (e: Exception) { return null }
        val boards = ArrayList<String>()
        boards.add(start.fen)
        try {
            for (m in playedMoves) {
                start.doMove(com.github.bhlangonijr.chesslib.move.Move(m.replace("?", ""), start.sideToMove))
                boards.add(start.fen)
            }
        } catch (e: Exception) {
            return null
        }
        // boards[k] = the position after k moves. the last one is where we are now (already checked)
        val lowest = maxOf(0, boards.size - 1 - REWIND_PLIES)
        for (k in boards.size - 2 downTo lowest) {
            val b = Board()
            b.loadFromFen(boards[k])
            val hit = (0..2).asSequence().flatMap { d ->
                Game.hitsAt(b, occ, d).filter { it.mis == 0 }.asSequence()
            }.firstOrNull() ?: continue
            Game.play(b, hit)
            if (seenChess != null && Game.typeMismatch(b, seenChess) > 2) continue
            while (playedMoves.size > k) playedMoves.removeAt(playedMoves.size - 1)
            promo.keys.removeAll { true }
            for (m in hit.path) playedMoves.add(m.toString())
            logVersion++
            board = b
            clearMisses()
            lastPlies = hit.path.size
            turnSure = false
            return Step.MOVED
        }
        return null
    }

    /**
     * builds the board from what we see (colors from the vision, types from the learned shapes). a few pieces we
     * can't recognize (hidden under a frame or a marker) get the most likely type that still fits a normal set.
     * false if we can't
     */
    private fun resyncFromVision(screen: IntArray, seen: IntArray, reads: Array<SquareRead>, oldWb: Boolean): Boolean {
        // the way the board faces normally stays the same during a game. but if the pieces can't stand like that
        // (pawns on the last rank, for example), the board was flipped: try it the other way up too
        for (wb in listOf(oldWb, !oldWb)) if (resyncAs(screen, seen, reads, wb)) return true
        return false
    }

    private fun resyncAs(screen: IntArray, seen: IntArray, reads: Array<SquareRead>, wb: Boolean): Boolean {
        val types = seen.copyOf()
        val unknown = (0 until 64).filter { screen[it] != BoardVision.EMPTY && seen[it] == 0 }
        if (unknown.size > 3) return false
        val limit = intArrayOf(0, 8, 2, 2, 2, 1, 1)
        for (i in unknown) {
            val colour = screen[i]
            val have = IntArray(7)
            for (j in 0 until 64) if (screen[j] == colour && types[j] != 0) have[types[j]]++
            val t = templates.ranked(colour, reads[i].mask).firstOrNull { have[it] < limit[it] } ?: return false
            types[i] = t
        }
        val placement = Game.placementFromTypes(Game.screenToChess(screen, wb), Game.screenToChess(types, wb))
        if (!Game.isValidPlacement(placement)) return false
        val whiteToMove = Game.turnFromChecks(placement) ?: me(wb) // no hint: assume it is our turn
        board = Game.newBoard(placement, whiteToMove)
        whiteBottom = wb
        board?.let { restartLog(it) }
        needsVerify = false
        suspicious = 0
        clearMisses()
        return true
    }

    /** remembers how the pieces of this board look (all of them, or only the kinds we haven't seen yet) */
    private fun learnFrom(b: Board, wb: Boolean, screen: IntArray, reads: Array<SquareRead>, all: Boolean) {
        for (i in 0 until 64) {
            if (screen[i] == BoardVision.EMPTY) continue
            val p = b.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(Game.chessIndex(i, wb)))
            val t = Game.typeCode(p)
            if (t == 0) continue
            val colour = if (p.pieceSide == Side.WHITE) BoardVision.WHITE else BoardVision.BLACK
            if (colour != screen[i]) continue
            if (all || templates.count(colour, t) == 0) templates.learn(colour, t, reads[i].mask)
        }
    }

    /** the AI looked again after a promotion: take its piece types, keep whose turn it is */
    fun verifyWith(grid: List<String>, screen: IntArray, reads: Array<SquareRead>? = null): Boolean {
        val b = board ?: return false
        val gridScreen = Game.gridOcc(grid)
        if (Game.mismatch(gridScreen, screen) > 3) return false
        val wb = whiteBottom ?: return false
        val placement = Game.placementFromGrid(grid, wb)
        if (!Game.isValidPlacement(placement)) return false
        val nb = Game.newBoard(placement, b.sideToMove == Side.WHITE)
        board = nb
        restartLog(nb)
        needsVerify = false
        suspicious = 0
        if (reads != null) learnFrom(nb, wb, screen, reads, all = true)
        return true
    }

    /**
     * Starts from what the AI read: 8 screen rows of text. Checks it against the piece colors we see
     * ourselves, so a misread board is thrown away instead of trusted. Returns false if it doesn't fit.
     */
    fun supplyGrid(grid: List<String>, claimWhiteBottom: Boolean?, screen: IntArray, reads: Array<SquareRead>? = null): Boolean {
        val gridScreen = Game.gridOcc(grid)
        if (Game.mismatch(gridScreen, screen) > 3) return false
        val wb = HaikuParse.whiteBottom(grid, claimWhiteBottom) ?: BoardVision.whiteBottomOf(screen) ?: iAmWhite
        val placement = Game.placementFromGrid(grid, wb)
        if (!Game.isValidPlacement(placement)) return false
        val mine = me(wb)
        val whiteToMove = if (myTurnAtStart) mine else !mine
        val nb = Game.newBoard(placement, whiteToMove)
        board = nb
        restartLog(nb)
        whiteBottom = wb
        clearMisses()
        suspicious = 0
        needsVerify = false
        if (reads != null) learnFrom(nb, wb, screen, reads, all = true)
        return true
    }
}
