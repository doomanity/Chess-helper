import com.example.chesshelper.*
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Runs the board-reading and game-following code on a computer, with no phone:
 *  1. on a real screenshot,
 *  2. on drawn boards of simulated games (both colors, flipped and not, castling, en passant, promotion, ...),
 *  3. with a real Stockfish for the suggestions.
 * usage: java SelfTestKt <fixtures-folder> <stockfish-binary>
 */
var failures = 0
fun check(ok: Boolean, what: String) {
    if (!ok) { failures++; println("  FAIL: $what") }
}

// ---------------------------------------------------------------- drawing a fake chess app screen

class Look(val light: Color, val dark: Color, val highlight: Color, val pieceW: Color, val pieceB: Color)

val looks = listOf(
    Look(Color(235, 218, 188), Color(173, 138, 108), Color(213, 196, 80), Color(250, 250, 250), Color(70, 70, 70)),   // like the real app
    Look(Color(238, 238, 210), Color(118, 150, 86), Color(246, 246, 105), Color(255, 255, 255), Color(30, 30, 30)),   // green board
    Look(Color(222, 227, 230), Color(140, 162, 173), Color(155, 199, 0), Color(245, 245, 245), Color(60, 60, 64))     // blue board
)

class Scene(
    val look: Look, val w: Int, val h: Int, val left: Int, val top: Int, val size: Int,
    val whiteBottom: Boolean, val rng: Random
)

val glyphFont: Font by lazy { Font("DejaVu Sans", Font.PLAIN, 100) }
fun glyphOf(type: String) = when (type) { "KING" -> "\u265A"; "QUEEN" -> "\u265B"; "ROOK" -> "\u265C"; "BISHOP" -> "\u265D"; "KNIGHT" -> "\u265E"; else -> "\u265F" }

fun render(sc: Scene, b: Board, lastFrom: Int?, lastTo: Int?, lifted: Int? = null, dots: List<Int> = emptyList(), overlay: Boolean = false, frame: Int? = null): IntArray {
    val img = BufferedImage(sc.w, sc.h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(22, 32, 48); g.fillRect(0, 0, sc.w, sc.h)
    g.color = Color(38, 74, 117); g.fillRoundRect(20, 200, sc.w - 40, 90, 40, 40)          // a bar above the board
    g.color = Color(38, 74, 117); g.fillRoundRect(20, sc.top + sc.size + 20, sc.w - 40, 130, 40, 40)
    val s = sc.size / 8f
    for (r in 0..7) for (c in 0..7) {
        val file = if (sc.whiteBottom) c else 7 - c
        val rank = if (sc.whiteBottom) 7 - r else r
        val idx = rank * 8 + file
        val x = sc.left + c * s; val y = sc.top + r * s
        val lightSq = (file + rank) % 2 == 1
        g.color = if (idx == lastFrom || idx == lastTo) sc.look.highlight else if (lightSq) sc.look.light else sc.look.dark
        g.fillRect(Math.round(x), Math.round(y), Math.round(s) + 1, Math.round(s) + 1)
        if (c == 0) { g.color = Color(120, 100, 80); g.font = Font("SansSerif", Font.PLAIN, 22); g.drawString("${rank + 1}", x + 5, y + 24) }
        if (r == 7) { g.color = Color(120, 100, 80); g.drawString("${'a' + file}", x + s - 22, y + s - 8) }
        val p = b.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(idx))
        if (p != com.github.bhlangonijr.chesslib.Piece.NONE && idx != lifted) {
            val white = p.pieceSide == Side.WHITE
            // the piece is the chess glyph of the font, scaled to the square, with an outline like real apps
            val gv = glyphFont.createGlyphVector(g.fontRenderContext, glyphOf(p.pieceType.name))
            val shape0 = gv.outline
            val bb = shape0.bounds2D
            val k = (s * 0.86f / maxOf(bb.width, bb.height)).toFloat()
            val at = java.awt.geom.AffineTransform()
            at.translate(x + s / 2.0, y + s / 2.0 + s * 0.03)
            at.scale(k.toDouble(), k.toDouble())
            at.translate(-bb.centerX, -bb.centerY)
            val shape = at.createTransformedShape(shape0)
            g.color = if (white) sc.look.pieceW else sc.look.pieceB
            g.fill(shape)
            g.color = if (white) Color(30, 30, 30) else Color(15, 15, 15)
            g.stroke = java.awt.BasicStroke(3f)
            g.draw(shape)
        }
        if (idx in dots) { g.color = Color(115, 88, 64); val d = s * 0.3f; g.fill(java.awt.geom.Ellipse2D.Float(x + s / 2 - d, y + s / 2 - d, d * 2, d * 2)) }
        if (idx == frame) { g.color = Color(228, 117, 98); g.stroke = java.awt.BasicStroke(s * 0.08f); g.drawRect(Math.round(x + s * 0.04f), Math.round(y + s * 0.04f), Math.round(s * 0.92f), Math.round(s * 0.92f)) }
    }
    if (overlay) { g.color = Color(255, 87, 34, 200); g.stroke = java.awt.BasicStroke(14f); g.drawLine(sc.left + 200, sc.top + 200, sc.left + 600, sc.top + 700) }
    g.dispose()
    val px = IntArray(sc.w * sc.h)
    img.getRGB(0, 0, sc.w, sc.h, px, 0, sc.w)
    // a little camera noise, like a lossy copy
    for (i in px.indices step 7) {
        val n = sc.rng.nextInt(7) - 3
        val p = px[i]
        val r = (((p shr 16) and 255) + n).coerceIn(0, 255); val gg = (((p shr 8) and 255) + n).coerceIn(0, 255); val bb = ((p and 255) + n).coerceIn(0, 255)
        px[i] = (255 shl 24) or (r shl 16) or (gg shl 8) or bb
    }
    return px
}

/** the square of the king that is in check (apps draw a red frame there), or null */
fun checkedKing(b: Board): Int? {
    if (!b.isKingAttacked) return null
    val king = if (b.sideToMove == Side.WHITE) com.github.bhlangonijr.chesslib.Piece.WHITE_KING else com.github.bhlangonijr.chesslib.Piece.BLACK_KING
    return (0 until 64).firstOrNull { b.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(it)) == king }
}

fun sqIdx(name: String) = (name[1] - '1') * 8 + (name[0] - 'a')

// ---------------------------------------------------------------- what the phone app does per frame

/** what the AI would answer when asked to read the screen: the 8 rows as drawn */
fun screenRows(truth: Board, whiteBottom: Boolean): List<String> = (0..7).map { r -> (0..7).map { c ->
    val file = if (whiteBottom) c else 7 - c; val rank = if (whiteBottom) 7 - r else r
    val p = truth.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(rank * 8 + file))
    if (p == com.github.bhlangonijr.chesslib.Piece.NONE) '.' else p.fenSymbol[0]
}.joinToString("") }

class Phone(val tracker: Tracker, val sc: Scene) {
    var geom: BoardGeom? = null
    var truth: Board? = null      // lets the test play the part of the AI when the tracker asks for a check after a promotion
    var aiChecks = 0
    var clock = 1000L
    /** one look at the screen, returns the tracker's verdict */
    fun look(px: IntArray): Tracker.Step? {
        val g = BoardVision.findBoard(px, sc.w, sc.h, geom)
        if (g == null) { println("    [phone] board not found"); return null }
        geom = g
        val reads = BoardVision.readSquares(px, sc.w, g)
        val cls = BoardVision.classify(reads)
        if (cls == null) { println("    [phone] pieces not classified (tones ${reads.filter { it.occupied }.map { it.tone }.distinct().sorted()})"); return null }
        clock += 700
        val st = tracker.update(cls, reads, clock)
        if (System.getProperty("verbose") != null) println("      [look] step=$st tracker=${tracker.board?.fen}")
        if (System.getProperty("grid") != null) { println("      grid " + grid(cls)); println("      reads " + reads.map { (if (it.dot) "d" else if (it.occupied) "o" else ".") + it.tone }.joinToString(" ")) }
        val t = truth
        if (tracker.needsVerify && t != null && (st == Tracker.Step.MOVED || st == Tracker.Step.RESET)) {
            aiChecks++
            check(tracker.verifyWith(screenRows(t, sc.whiteBottom), cls, reads), "AI check after promotion was rejected")
        }
        return st
    }
}

fun samePosition(tracker: Tracker, truth: Board): Boolean {
    val b = tracker.board ?: return false
    return Game.placement(b) == Game.placement(truth) && b.sideToMove == truth.sideToMove
}

// ---------------------------------------------------------------- tests

/** what the screen should show for a board: 0 empty, 1 white, 2 black per screen square */
fun screenOfBoard(b: Board, whiteBottom: Boolean): IntArray {
    val occ = Game.occOf(b)
    return IntArray(64) { occ[Game.chessIndex(it, whiteBottom)] }
}

fun boardOf(fen: String): Board { val b = Board(); b.loadFromFen(fen); return b }

class Shot(val px: IntArray, val w: Int, val h: Int, val geom: BoardGeom, val reads: Array<SquareRead>, val cls: IntArray?)

fun loadShot(path: String, what: String): Shot? {
    val img = ImageIO.read(File(path))
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val g = BoardVision.findBoard(px, w, h)
    check(g != null, "$what: board not found")
    if (g == null) return null
    // the fixtures are cropped to the board with an 18 pixel margin; the board is 1032 pixels wide
    check(Math.abs(g.left - 18) <= 3 && Math.abs(g.top - 18) <= 3 && Math.abs(g.size - 1032) <= 4, "$what: board position $g differs from the one measured by hand (18, 18, 1032)")
    val reads = BoardVision.readSquares(px, w, g)
    return Shot(px, w, h, g, reads, BoardVision.classify(reads))
}

fun grid(cls: IntArray) = (0 until 64).map { ".WB"[cls[it]] }.joinToString("")

/** real screenshots from a phone: hint dots, our own overlay, a red check frame, a last-move highlight */
fun realShots(dir: String) {
    println("== real screenshots")
    val afterD4 = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
    val dotsFen = "r1bqkb1r/1pp2ppp/p1n1pn2/3P4/Q2P4/P4N2/1P2PPPP/RNB1KB1R b KQkq - 0 6"
    val checkFen = "r1bqkb1r/2p2ppp/p1Q1p3/8/3Pn3/P4N2/1P2PPPP/RNB1KB1R b KQkq - 0 8"
    val a = loadShot("$dir/after_d4.jpg", "after d4") ?: return
    val d = loadShot("$dir/dots_and_overlay.jpg", "dots") ?: return
    val c = loadShot("$dir/check_frame.jpg", "check") ?: return
    for ((name, shot, fen) in listOf(Triple("after d4", a, afterD4), Triple("hint dots + our overlay", d, dotsFen), Triple("red check frame", c, checkFen))) {
        val expect = grid(screenOfBoard(boardOf(fen), false))
        val got = shot.cls?.let { grid(it) } ?: "(not classified)"
        check(got == expect, "$name: piece map\n    got      $got\n    expected $expect")
        println("  $name: piece map ${if (got == expect) "ok" else "WRONG"}")
        if (shot.cls != null) check(BoardVision.whiteBottomOf(shot.cls) == false, "$name: orientation should be flipped (white at the top)")
    }
    if (a.cls == null || d.cls == null || c.cls == null) return

    // the app starts from the standard position, so 1.d4 is a position the AI has to read once
    val tr = Tracker(false, true)
    check(tr.update(a.cls, a.reads, 1000) == Tracker.Step.NEED_AI, "expected NEED_AI for a position after 1.d4")
    val ok = tr.supplyGrid(listOf("RNBKQBNR", "PPPP.PPP", "........", "....P...", "........", "........", "pppppppp", "rnbkqbnr"), false, a.cls, a.reads)
    check(ok, "AI grid for this screenshot was rejected")
    check(tr.board != null && Game.placement(tr.board!!) == "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR", "placement from the AI grid is wrong")
    check(tr.board!!.sideToMove == Side.BLACK, "side to move should be black (user plays black and checked 'my turn')")
    check(tr.templates.complete, "pieces learned from the AI read: only ${tr.templates.known} of 12 kinds")
    println("  learned ${tr.templates.known} of 12 piece kinds from one AI read")

    // now the pieces are known: every piece on the later screenshots must be recognized, with no AI
    for ((name, shot, fen) in listOf(Triple("hint dots + our overlay", d, dotsFen), Triple("red check frame", c, checkFen))) {
        val seen = tr.templates.recognizeAll(shot.reads, shot.cls!!)
        val truthTypes = Game.screenToChess(IntArray(64) { 0 }, false) // placeholder to keep the layout helper warm
        val tb = boardOf(fen)
        var wrong = 0; var unknown = 0; var total = 0
        for (i in 0 until 64) {
            if (shot.cls[i] == 0) continue
            total++
            val want = Game.typeCode(tb.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(Game.chessIndex(i, false))))
            if (seen[i] == 0) { unknown++; println("    $name: screen square $i unknown, want $want dist ${tr.templates.distances(shot.cls[i], shot.reads[i].mask).toList()}") } else if (seen[i] != want) { wrong++; println("    $name: screen square $i seen ${seen[i]} want $want dist ${tr.templates.distances(shot.cls[i], shot.reads[i].mask).toList()}") }
        }
        println("  $name: piece types recognized, $wrong wrong, $unknown unknown of $total")
        check(wrong == 0, "$name: $wrong piece types recognized wrong")
        check(unknown <= 1, "$name: $unknown piece types not recognized")
    }

    // lost the game completely (the screen is many moves later): start over from the pictures, no AI
    var step = Tracker.Step.UNREADABLE
    var t = 2000L
    for (i in 0 until 4) { t += 900; step = tr.update(d.cls, d.reads, t) }
    println("  many moves later, after waiting: $step")
    check(step == Tracker.Step.RESET, "should have started over from the pictures (got $step)")
    check(tr.board != null && Game.placement(tr.board!!) == dotsFen.substringBefore(' '), "board rebuilt from the pictures is wrong: ${tr.board?.fen}")
    // and keeps following from there: black plays Nf6-e4, then the check position is 3 plies later
    t += 900
    val st2 = tr.update(c.cls, c.reads, t)
    println("  next screenshot (4 plies on): $st2")
    t += 900
    val st3 = tr.update(c.cls, c.reads, t + 3000)
    check(tr.board != null && Game.placement(tr.board!!) == checkFen.substringBefore(' ') || st3 == Tracker.Step.RESET || st3 == Tracker.Step.UNREADABLE,
        "unexpected state after the check screenshot")
}

fun playMove(truth: Board, uci: String): Move {
    val mv = truth.legalMoves().first { it.toString() == uci }
    truth.doMove(mv)
    return mv
}

fun simulate(name: String, iAmWhite: Boolean, look: Look, sf: UciEngine?, plies: Int, seed: Long, size: Int = 1032, w: Int = 1189, h: Int = 2576, left: Int = 78, top: Int = 918) {
    val rng = Random(seed)
    val sc = Scene(look, w, h, left, top, size, whiteBottom = iAmWhite, rng = rng)
    val truth = Board()
    val tracker = Tracker(iAmWhite, true)
    val phone = Phone(tracker, sc)
    phone.truth = truth
    var step = phone.look(render(sc, truth, null, null))
    check(step == Tracker.Step.STARTED, "$name: start position not recognised ($step)")
    var suggestionsOk = 0; var suggestionsBad = 0
    var ply = 0
    var multi = 0; var anim = 0
    while (ply < plies && !truth.legalMoves().isEmpty() && !truth.isDraw) {
        // choose a move for whoever is to play
        val mineToPlay = (truth.sideToMove == Side.WHITE) == iAmWhite
        var mv: Move
        if (mineToPlay && sf != null && tracker.board != null && samePosition(tracker, truth)) {
            val s = sf.bestMove(tracker.board!!.fen, 60)
            val legal = s != null && truth.legalMoves().any { it.toString() == s.move }
            if (legal) suggestionsOk++ else suggestionsBad++
            mv = if (legal && rng.nextInt(10) < 8) truth.legalMoves().first { it.toString() == s!!.move } else truth.legalMoves()[rng.nextInt(truth.legalMoves().size)]
        } else {
            val l = truth.legalMoves(); mv = l[rng.nextInt(l.size)]
        }
        // prefer queen promotion like apps do by default
        val before = Board(); before.loadFromFen(truth.fen)
        truth.doMove(mv)
        ply++
        val from = mv.from.ordinal; val to = mv.to.ordinal
        // an animation frame: piece lifted off its square, nothing on the target yet
        if (rng.nextInt(4) == 0) {
            val frame = render(sc, before, null, null, lifted = from)
            phone.look(frame); anim++
        }
        // the legal move dots the app shows while a piece is selected (should not change anything)
        if (rng.nextInt(3) == 0) phone.look(render(sc, before, null, null, dots = listOf(to, (to + 8) % 64, (to + 17) % 64, (to + 30) % 64).filter { before.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(it)) == com.github.bhlangonijr.chesslib.Piece.NONE }))
        var frame = render(sc, truth, from, to, frame = checkedKing(truth))
        // sometimes the opponent's reply is already on screen when we look (2 moves at once)
        var multiMove = false
        if (!truth.legalMoves().isEmpty() && rng.nextInt(3) == 0) {
            val l = truth.legalMoves(); val mv2 = l[rng.nextInt(l.size)]
            truth.doMove(mv2); ply++; multiMove = true; multi++
            frame = render(sc, truth, mv2.from.ordinal, mv2.to.ordinal, frame = checkedKing(truth))
        }
        step = phone.look(frame)
        if (step == Tracker.Step.UNREADABLE || step == Tracker.Step.RESYNC) { step = phone.look(frame) } // a second look at the same screen
        val ok = samePosition(tracker, truth)
        if (System.getProperty("verbose") != null) println("    ply $ply ${mv} step=$step multi=$multiMove ok=$ok  tracker=${tracker.board?.fen}")
        if (!ok && System.getProperty("verbose") != null) {
            val cls = BoardVision.classify(BoardVision.readSquares(frame, sc.w, phone.geom!!))!!
            val occ = Game.screenToChess(cls, sc.whiteBottom)
            println("    DEBUG obs vs truth squares differing: " + Game.mismatch(occ, Game.occOf(truth)))
            val k = Board(); k.loadFromFen(before.fen)
            println("    DEBUG board before this ply: ${before.fen}")
            for (d in 0..3) {
                val hk = Game.hitsAt(k, occ, d).filter { it.mis == 0 }
                println("    DEBUG depth $d exact hits from before-board: ${hk.size} ${hk.take(3).map { h -> h.path.joinToString(",") }}")
            }
        }
        check(ok, "$name: ply $ply lost the game (step=$step multi=$multiMove)\n    truth   ${truth.fen}\n    tracker ${tracker.board?.fen}")
        if (!ok) return
    }
    println("  $name: followed $ply plies ok (animations $anim, double moves $multi, suggestions ok $suggestionsOk bad $suggestionsBad, ai checks ${phone.aiChecks})")
    check(suggestionsBad == 0, "$name: stockfish suggested an illegal move")
}

/** hand-made sequences with the special moves */
fun special(sf: UciEngine?) {
    println("== special moves")
    val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, true, Random(5))
    fun run(label: String, iAmWhite: Boolean, moves: String) {
        val s = sc.let { Scene(it.look, it.w, it.h, it.left, it.top, it.size, iAmWhite, it.rng) }
        val truth = Board(); val tr = Tracker(iAmWhite, true); val ph = Phone(tr, s)
        ph.truth = truth
        ph.look(render(s, truth, null, null))
        for (u in moves.split(" ")) {
            val mv = playMove(truth, u)
            ph.look(render(s, truth, mv.from.ordinal, mv.to.ordinal))
            check(samePosition(tr, truth), "$label: after $u\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  $label ok (${if (iAmWhite) "white" else "black"} at the bottom)")
    }
    run("castling both sides", true, "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 e1g1 d7d5 e4d5 f6d5 f1e1 c8e6 b1c3 f8e7 d2d4 e8g8")
    run("castling flipped", false, "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 e1g1 d7d5 e4d5 f6d5 f1e1 c8e6 b1c3 f8e7 d2d4 e8g8")
    run("en passant", true, "e2e4 a7a6 e4e5 d7d5 e5d6 c7d6")
    run("en passant black", false, "a2a3 e7e5 a3a4 e5e4 d2d4 e4d3 c2d3")
    run("queen promotion", true, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8q")
    run("queen promotion flipped", false, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8q")
    run("knight promotion (the AI check fixes the piece)", true, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8n")
    run("knight promotion flipped", false, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8n")
}

/**
 * a promotion read at a bad moment: the frame right after the move shows a queen (the piece picker or the animation),
 * but the pawn really became a knight. the next reads show the knight and the tracker must fix the piece by itself
 * (no AI), and the move in the game record must say knight too. also: the knight then moves like a knight
 */
fun promoRecheck() {
    println("== promotion read again")
    for (white in listOf(true, false)) {
        val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, white, Random(11))
        val truth = Board(); val tr = Tracker(white, true); val ph = Phone(tr, sc)
        ph.look(render(sc, truth, null, null))
        for (u in "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6".split(" ")) {
            val mv = playMove(truth, u)
            ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
        }
        // what the screen shows for a moment: a queen on a8
        val fake = boardOf(truth.fen); playMove(fake, "b7a8q")
        val mv = playMove(truth, "b7a8n")
        ph.look(render(sc, fake, mv.from.ordinal, mv.to.ordinal))
        val firstGuess = tr.board?.let { Game.placement(it) }
        // then the real piece shows up
        ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
        ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
        check(samePosition(tr, truth), "promotion recheck (white=$white): first read ${firstGuess}\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        check(tr.playedMoves.lastOrNull() == "b7a8n", "promotion recheck (white=$white): record says ${tr.playedMoves.lastOrNull()}")
        // the game goes on: black moves, then the knight jumps
        for (u in "g8f6 a8c7".split(" ")) {
            val m2 = playMove(truth, u)
            ph.look(render(sc, truth, m2.from.ordinal, m2.to.ordinal))
            check(samePosition(tr, truth), "after the fixed promotion (white=$white), $u\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  ${if (white) "white" else "black"} at the bottom: first read ${if (firstGuess == Game.placement(fake)) "queen (wrong, as planned)" else "knight"} -> fixed ok")
    }
}

/**
 * auto color: a game as white, then a new game as black (the app flips the board). the tracker must see the new game
 * by itself, turn the board around and know you are black now. then back to white again
 */
fun sideSwitch() {
    println("== new game with the other color (auto color)")
    val tr = Tracker(true, true)
    tr.autoColor = true
    var truth = Board()
    var ph = Phone(tr, Scene(looks[0], 1189, 2576, 78, 918, 1032, true, Random(21)))
    ph.look(render(ph.sc, truth, null, null))
    for (u in "e2e4 e7e5 g1f3 b8c6".split(" ")) {
        val mv = playMove(truth, u)
        ph.look(render(ph.sc, truth, mv.from.ordinal, mv.to.ordinal))
    }
    check(tr.meWhite(), "first game: you should be white")
    for ((round, white) in listOf(false, true, false).withIndex()) {
        truth = Board()
        ph = Phone(tr, Scene(looks[round % looks.size], 1189, 2576, 78, 918, 1032, white, Random(22L + round)))
        val st = ph.look(render(ph.sc, truth, null, null))
        check(st == Tracker.Step.STARTED, "new game ($round) should start a new game (was $st)")
        check(tr.whiteBottom == white && tr.meWhite() == white, "new game ($round): you should be ${if (white) "white" else "black"} (whiteBottom=${tr.whiteBottom})")
        for (u in "d2d4 g8f6 c2c4 e7e6".split(" ")) {
            val mv = playMove(truth, u)
            ph.look(render(ph.sc, truth, mv.from.ordinal, mv.to.ordinal))
            check(samePosition(tr, truth), "new game ($round) after $u\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  game ${round + 2}: ${if (white) "white" else "black"} at the bottom -> you are ${if (tr.meWhite()) "white" else "black"} ok")
    }
    // knights out and back: the start position again, but it is the same game, not a new one
    truth = Board()
    ph = Phone(tr, Scene(looks[0], 1189, 2576, 78, 918, 1032, true, Random(30)))
    ph.look(render(ph.sc, truth, null, null))
    val epoch = tr.logEpoch
    for (u in "g1f3 g8f6 f3g1 f6g8".split(" ")) {
        val mv = playMove(truth, u)
        ph.look(render(ph.sc, truth, mv.from.ordinal, mv.to.ordinal))
    }
    check(tr.logEpoch == epoch && tr.playedMoves.size == 4, "knights back home is not a new game (epoch ${tr.logEpoch} vs $epoch, moves ${tr.playedMoves.size})")
    println("  knights back home: still the same game ok")
}

/**
 * a premove: during the bot's turn the chess app already draws your next move. the tracker must wait (HOLD), not
 * believe it, then read the bot's move and your premove together when the bot plays. also a piece you hold
 * (lifted off its square) must not count as a change
 */
fun premoveHold() {
    println("== premove and a lifted piece")
    for (white in listOf(true, false)) {
        val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, white, Random(41))
        val truth = Board(); val tr = Tracker(white, true); tr.autoColor = true; val ph = Phone(tr, sc)
        ph.look(render(sc, truth, null, null))
        // white plays e4, black e5, white Nf3: then it is the bot's turn when you are white, yours when black
        val opening = if (white) "e2e4 e7e5 g1f3" else "e2e4 e7e5 g1f3 b8c6"
        for (u in opening.split(" ")) { val mv = playMove(truth, u); ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal)) }
        val before = tr.board?.fen
        // it is the bot's turn. you premove: the app draws your move already
        val myPre = if (white) "f1c4" else "g8f6"
        // draw the premove: put the side to move as you, play your move on a copy
        val asMe = boardOf(truth.fen.replace(if (white) " b " else " w ", if (white) " w " else " b "))
        playMove(asMe, myPre)
        val st = ph.look(render(sc, asMe, null, null))
        check(st == Tracker.Step.HOLD, "premove picture should be held (white=$white), was $st")
        check(tr.board?.fen == before, "premove must not change the board (white=$white)")
        // the bot plays, then the premove goes through at once
        val botMv = if (white) "b8c6" else "f1c4"
        playMove(truth, botMv); playMove(truth, myPre)
        val st2 = ph.look(render(sc, truth, null, null))
        check(samePosition(tr, truth), "after the bot's move and the premove (white=$white), step $st2\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        // a lifted piece: one of your pieces missing from its square
        val lifted = boardOf(truth.fen)
        val mySq = if (white) "D1" else "D8"
        lifted.unsetPiece(lifted.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(mySq)), com.github.bhlangonijr.chesslib.Square.valueOf(mySq))
        val st3 = ph.look(render(sc, lifted, null, null))
        check((st3 == Tracker.Step.HOLD || st3 == Tracker.Step.NO_CHANGE) && samePosition(tr, truth), "a lifted piece changes nothing (white=$white), was $st3")
        ph.look(render(sc, truth, null, null))
        check(samePosition(tr, truth), "piece put back (white=$white)")
        println("  ${if (white) "white" else "black"}: premove held, then read with the bot's move; lifted piece ignored ok")
    }
}

/**
 * holding or dragging one of your pieces on your turn: the piece leaves its square and shows up under your finger
 * (over empty squares and maybe an opponent piece). the tracker must wait (HOLD) for as long as you hold, never
 * start over, then read the real move when you drop it. also a new game whose start picture has one misread
 * square still starts, the right way up
 */
fun holdDrag() {
    println("== holding and dragging a piece, and a start with one misread square")
    for (white in listOf(true, false)) {
        val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, white, Random(51))
        val truth = Board(); val tr = Tracker(white, true); tr.autoColor = true; val ph = Phone(tr, sc)
        ph.look(render(sc, truth, null, null))
        val opening = if (white) "e2e4 e7e5" else "e2e4 e7e5 g1f3"
        for (u in opening.split(" ")) { val mv = playMove(truth, u); ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal)) }
        val before = tr.board?.fen
        // your queen in your hand: gone from its square, drawn over two empty squares and one opponent piece
        val sqs = if (white) listOf("D1", "H5", "G4", "E5") else listOf("D8", "H4", "G5", "F3")
        val dragged = boardOf(truth.fen)
        val q = dragged.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(sqs[0]))
        dragged.unsetPiece(q, com.github.bhlangonijr.chesslib.Square.valueOf(sqs[0]))
        for (sq in sqs.drop(1)) {
            val at = com.github.bhlangonijr.chesslib.Square.valueOf(sq)
            val there = dragged.getPiece(at)
            if (there != com.github.bhlangonijr.chesslib.Piece.NONE) dragged.unsetPiece(there, at)
            dragged.setPiece(q, at)
        }
        // held for about 3 seconds (longer than the "lost" wait, shorter than the still-hand limit)
        for (i in 1..4) {
            val st = ph.look(render(sc, dragged, null, null))
            check(st == Tracker.Step.HOLD, "dragged piece should be held (white=$white, look $i), was $st")
        }
        check(tr.board?.fen == before, "holding a piece must not change the board (white=$white)")
        // the site's own drawing while you hold: things on empty squares read as the opponent's color (hint dots,
        // a hover frame), one opponent piece covered by a ring
        val noisy = boardOf(dragged.fen)
        val theirPawn = if (white) com.github.bhlangonijr.chesslib.Piece.BLACK_PAWN else com.github.bhlangonijr.chesslib.Piece.WHITE_PAWN
        for (sq in listOf("A4", "C4", "B5")) noisy.setPiece(theirPawn, com.github.bhlangonijr.chesslib.Square.valueOf(sq))
        for (i in 1..2) {
            val st = ph.look(render(sc, noisy, null, null))
            check(st == Tracker.Step.HOLD, "held piece with the site's hints should be held (white=$white, look $i), was $st")
        }
        check(tr.board?.fen == before, "the site's hints must not change the board (white=$white)")
        // you drop it: the real move is read
        val mv = playMove(truth, if (white) "d1h5" else "d8h4")
        ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
        check(samePosition(tr, truth), "the move after holding (white=$white)\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        // a new game with the other color, and one square of the start picture misread (a piece hidden)
        val sc2 = Scene(looks[1], 1189, 2576, 78, 918, 1032, !white, Random(52))
        val ph2 = Phone(tr, sc2)
        val start = Board()
        val hide = com.github.bhlangonijr.chesslib.Square.valueOf("B7")
        val misread = boardOf(start.fen); misread.unsetPiece(misread.getPiece(hide), hide)
        val st = ph2.look(render(sc2, misread, null, null))
        check(st == Tracker.Step.STARTED && tr.whiteBottom == !white && tr.meWhite() == !white,
            "start with one misread square should start a new game as ${if (!white) "white" else "black"} (was $st, whiteBottom=${tr.whiteBottom})")
        ph2.look(render(sc2, start, null, null))
        check(samePosition(tr, start), "start position after the misread square (white=$white)")
        println("  ${if (white) "white" else "black"}: dragged piece held 3s then the move read; misread start ok")
    }
}

/** you and the bot both fast: 4 to 7 moves pass between two reads, in the opening and later on */
fun fastPlay() {
    println("== fast play: several moves between two reads")
    val line = ("e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8 h2h3 c6a5 b3c2 c7c5 " +
        "d2d4 d8c7 b1d2 c5d4 c3d4 a5c6 d2b3 a6a5 c1e3 a5a4 b3d2 c8d7 a1c1 c7b7").split(" ")
    for (white in listOf(true, false)) for ((gi, gap) in listOf(4, 5, 6, 7).withIndex()) {
        val sc = Scene(looks[gi % looks.size], 1189, 2576, 78, 918, 1032, white, Random(60L + gi))
        val truth = Board(); val tr = Tracker(white, true); tr.autoColor = true; val ph = Phone(tr, sc)
        ph.look(render(sc, truth, null, null))
        var i = 0
        var worst = 0L
        var ok = true
        while (i < line.size) {
            var last: com.github.bhlangonijr.chesslib.move.Move? = null
            for (k in 0 until gap) { if (i >= line.size) break; last = playMove(truth, line[i]); i++ }
            val t0 = System.nanoTime()
            val st = ph.look(render(sc, truth, last?.from?.ordinal, last?.to?.ordinal))
            worst = maxOf(worst, (System.nanoTime() - t0) / 1_000_000)
            if (!samePosition(tr, truth)) { ok = false; check(false, "fast play gap $gap (white=$white) after ply $i: $st\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}"); break }
        }
        if (ok) println("  ${if (white) "white" else "black"}, $gap moves between reads: followed the whole game (slowest read ${worst} ms) ok")
    }
}

/** moves taken back (a coach rewinding, a takeback): the tracker goes back to that earlier position */
fun takeback() {
    println("== takebacks: the coach rewinds")
    val line = "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 f3g5 d7d5 e4d5 f6d5 g5f7 e8f7".split(" ")
    for (white in listOf(true, false)) {
        val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, white, Random(70))
        val tr = Tracker(white, true); tr.autoColor = true; val ph = Phone(tr, sc)
        val truth = Board()
        ph.look(render(sc, truth, null, null))
        for (u in line) { val mv = playMove(truth, u); ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal)) }
        // back 2 moves
        val back2 = Board(); for (u in line.dropLast(2)) playMove(back2, u)
        var st = ph.look(render(sc, back2, null, null))
        check(st == Tracker.Step.MOVED && samePosition(tr, back2), "back 2 moves (white=$white): $st\n    truth   ${back2.fen}\n    tracker ${tr.board?.fen}")
        // back 5 more, and a different move played right away
        val back7 = Board(); for (u in line.dropLast(7)) playMove(back7, u)
        val alt = playMove(back7, if (back7.sideToMove == com.github.bhlangonijr.chesslib.Side.WHITE) "b1c3" else "h7h6")
        st = ph.look(render(sc, back7, alt.from.ordinal, alt.to.ordinal))
        check(samePosition(tr, back7), "back 7 moves and a new move (white=$white): $st\n    truth   ${back7.fen}\n    tracker ${tr.board?.fen}")
        check(tr.playedMoves.size == line.size - 7 + 1, "the record is cut back (white=$white): ${tr.playedMoves.size} moves")
        println("  ${if (white) "white" else "black"}: back 2, back 7 + a new move, record cut back ok")
    }
}

/**
 * no AI: a new session opened in the middle of a game reads the board from the piece shapes saved by an earlier
 * session, and tells whose turn it is from the last move highlight
 */
fun noAiMidGame() {
    println("== no AI: mid-game first look from saved piece shapes")
    val line = "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 d2d3 f8c5 c2c3 d7d6 e1g1 e8g8 b1d2 a7a6".split(" ")
    for (white in listOf(true, false)) for (cut in listOf(9, 14)) {
        val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, white, Random(80L + cut))
        // an earlier session learned the shapes at the start position
        val first = Tracker(white, true); first.autoColor = true
        Phone(first, sc).look(render(sc, Board(), null, null))
        val saved = first.templates.encode()
        // a new session: shapes loaded, opened after `cut` moves, the last move highlighted
        val tr = Tracker(white, true); tr.autoColor = true
        tr.templates.decode(saved)
        val truth = Board()
        var last: com.github.bhlangonijr.chesslib.move.Move? = null
        for (u in line.take(cut)) last = playMove(truth, u)
        val st = Phone(tr, sc).look(render(sc, truth, last?.from?.ordinal, last?.to?.ordinal))
        val sameTurn = tr.board?.sideToMove == truth.sideToMove
        check(st == Tracker.Step.STARTED && tr.board?.fen?.substringBefore(' ') == truth.fen.substringBefore(' ') && sameTurn,
            "mid-game read (white=$white, after $cut moves): $st\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        println("  ${if (white) "white" else "black"} at the bottom, after $cut moves: board and turn read with no AI ok")
    }
}

/** the lost-track case: the misread queen moves like a knight before we had a second look */
fun promoLost() {
    println("== misread promotion, then the piece moves")
    val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, true, Random(12))
    val truth = Board(); val tr = Tracker(true, true); val ph = Phone(tr, sc)
    ph.look(render(sc, truth, null, null))
    for (u in "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6".split(" ")) {
        val mv = playMove(truth, u)
        ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
    }
    val fake = boardOf(truth.fen); playMove(fake, "b7a8q")
    val mv = playMove(truth, "b7a8n")
    ph.look(render(sc, fake, mv.from.ordinal, mv.to.ordinal))
    for (u in "g8f6 a8c7".split(" ")) {
        val m2 = playMove(truth, u)
        ph.look(render(sc, truth, m2.from.ordinal, m2.to.ordinal))
    }
    check(samePosition(tr, truth), "misread promotion then a knight jump\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
    println("  ok")
}

/** start in the middle of a game (the AI reads the board), including a wrong guess about whose turn it is */
fun midGame() {
    println("== starting in the middle of a game")
    for (iAmWhite in listOf(true, false)) for (myTurnGuess in listOf(true, false)) {
        val sc = Scene(looks[1], 1189, 2576, 60, 700, 1070, iAmWhite, Random(9))
        val truth = Board()
        for (u in "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1".split(" ")) playMove(truth, u) // black to move
        val tr = Tracker(iAmWhite, myTurnGuess)
        val ph = Phone(tr, sc)
        ph.truth = truth
        val frame = render(sc, truth, sqIdx("e1"), sqIdx("g1"))
        val step = ph.look(frame)
        check(step == Tracker.Step.NEED_AI, "mid-game start should ask the AI (was $step)")
        val rows = screenRows(truth, iAmWhite)
        val g = BoardVision.findBoard(frame, sc.w, sc.h)!!
        val reads0 = BoardVision.readSquares(frame, sc.w, g)
        val cls = BoardVision.classify(reads0)!!
        // wrong claim about orientation must not matter
        check(tr.supplyGrid(rows, !iAmWhite, cls, reads0), "AI rows rejected")
        // a misread piece colour must be rejected
        val bad = rows.toMutableList(); bad[3] = bad[3].replace('.', 'P').take(8)
        check(!Tracker(iAmWhite, myTurnGuess).supplyGrid(bad, null, cls), "a clearly wrong AI board was accepted")
        // play on: black replies d6, then white c3. even if our guess about the turn was wrong, we recover
        for (u in "d7d6 c2c3".split(" ")) {
            val mv = playMove(truth, u)
            val st = ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
            check(samePosition(tr, truth), "mid-game (iAmWhite=$iAmWhite turnGuess=$myTurnGuess) after $u: step=$st\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  iAmWhite=$iAmWhite, guessed my turn=$myTurnGuess: ok")
    }
}

fun engineTest(sf: UciEngine) {
    println("== stockfish")
    val s = sf.bestMove("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1", 300)
    println("  after 1.d4 black suggestion: ${s?.move} eval ${s?.eval}")
    check(s != null && s.move.length >= 4, "no move from stockfish")
    // mate in one: white to play Qh5#  (scholar's mate setup)
    val m = sf.bestMove("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", 300)
    println("  scholar's mate position: ${m?.move} eval ${m?.eval}")
    check(m?.move == "h5f7", "stockfish should find Qxf7#")
}

fun adviceTest(sf: UciEngine) {
    println("== move advice")
    // reading engine output
    val l1 = UciInfo.parse("info depth 12 seldepth 15 multipv 2 score cp -34 nodes 1000 nps 5000 time 20 pv e7e5 g1f3")
    check(l1 != null && l1.multipv == 2 && l1.move == "e7e5" && l1.cp == -34 && l1.mate == null, "cp info line not parsed")
    val l2 = UciInfo.parse("info depth 9 multipv 1 score mate 3 nodes 5 pv h5f7")
    check(l2 != null && l2.mate == 3 && l2.move == "h5f7", "mate info line not parsed")
    check(UciInfo.parse("info depth 5 score cp 10 lowerbound pv e2e4") == null, "bound line should be skipped")
    check(Grade.of(0) == Grade.BEST && Grade.of(50) == Grade.DECENT && Grade.of(120) == Grade.OK && Grade.of(400) == Grade.BLUNDER, "grade thresholds")

    val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    check(Game.san(start, "e2e4") == "e4" && Game.san(start, "g1f3") == "Nf3", "short move notation: ${Game.san(start, "g1f3")}")
    check(Game.san("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1") == "O-O", "castling notation: ${Game.san("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1")}")
    check(Game.san(start, "zzzz") == "zzzz", "unknown move falls back")
    val adv = Advisor(sf, null)
    val fen = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
    val c = adv.advise(fen, 4, 300)
    println("  after 1.d4: " + c.joinToString { "${it.move} ${it.text} g${it.grade}" })
    check(c.size == 4, "wanted 4 moves, got ${c.size}")
    check(c.map { it.move }.distinct().size == c.size, "moves repeat")
    check(c.firstOrNull()?.grade == Grade.BEST, "the top move should be graded best")
    val b = boardOf(fen)
    check(c.all { m -> b.legalMoves().any { it.toString() == m.move } }, "an advised move is not legal")

    // scholar's mate: the mate is best, a quiet move is a blunder (and the likely moves of another engine get graded too)
    val mateFen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
    val fake = object : MoveLister { override fun topMoves(fen: String, n: Int) = listOf("a2a3", "h5f7") }
    val c2 = Advisor(sf, fake).advise(mateFen, 3, 300)
    println("  scholar's mate with a2a3 suggested by 'maia': " + c2.joinToString { "${it.move} ${it.text} g${it.grade}" })
    check(c2.size == 3 && c2[0].move == "a2a3" && c2[0].grade == Grade.BLUNDER, "a move that misses mate in one must be a blunder")
    check(c2.any { it.move == "h5f7" && it.grade == Grade.BEST && it.text == "M1" }, "the mate should be best and show M1")

    // a lister that fails: we still get advice from stockfish and the error is reported
    val broken = object : MoveLister { override fun topMoves(fen: String, n: Int): List<String> = throw java.io.IOException("no route") }
    val adv3 = Advisor(sf, broken)
    val c3 = adv3.advise(fen, 2, 300)
    check(c3.size == 2 && adv3.listerError == "no route", "fallback when the pc can't be reached")

    // extras: eval bar, threat, main line, max power save
    val pvLine = UciInfo.parse("info depth 10 multipv 1 score cp 20 pv e2e4 e7e5 g1f3 b8c6")
    check(pvLine != null && pvLine.pv == listOf("e2e4", "e7e5", "g1f3", "b8c6"), "pv not parsed")
    val threatened = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3" // white threatens Qxf7#
    val full = Advisor(sf, null).full(threatened, 3, 300)
    println("  threat test: threat=${full.threat?.move} gain=${full.threat?.gain} eval=${full.evalText} bar=${"%.2f".format(full.whiteShare)} line=${full.line} moves=" + full.cands.joinToString { "${it.move} g${it.grade}" })
    check(full.threat?.move == "h5f7", "the mate threat Qxf7 should be found")
    check(full.line.isNotEmpty(), "main line preview is empty")
    check(full.whiteShare in 0f..1f, "eval bar out of range")
    val quiet = Advisor(sf, null).full(fen, 2, 300)
    check(quiet.threat == null, "no threat expected after 1.d4 (got ${quiet.threat?.move})")
    val save = Advisor(sf, null).full(mateFen, 3, 300, maxPower = true)
    check(save.cands.size == 1 && save.cands[0].star && save.cands[0].move == "h5f7", "max power save should give the mate as one starred move")
    val inCheck = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3" // fool's mate: white is mated
    check(Advisor(sf, null).threatOf(inCheck, 0) == null, "no threat search while in check")

    // weak difficulty: its own pick goes first, still graded
    val weak = UciEngine(System.getProperty("sf") ?: "")
    weak.configure(1350)
    val c4 = Advisor(weak, null).advise(fen, 3, 300)
    println("  difficulty 1350: " + c4.joinToString { "${it.move} ${it.text} g${it.grade}" })
    check(c4.size == 3, "weak engine advice")
    weak.close()
}

fun accuracyTest(sf: UciEngine) {
    println("== accuracy and guessing")
    // a move that keeps the score is ~100%, a blunder is low
    check(Accuracy.moveAccuracy(true, 30, 25) > 97, "a quiet good move scores high")
    check(Accuracy.moveAccuracy(true, 30, -600) < 20, "dropping a piece scores low")
    check(Accuracy.moveAccuracy(false, -30, -25) > 97, "black's good move scores high (white's side numbers)")
    check(Accuracy.moveAccuracy(false, -30, 700) < 20, "black's blunder scores low")
    check(Accuracy.moveAccuracy(true, 30, 300) == 100.0, "a move better than expected is capped at 100")
    val a = Accuracy()
    check(a.text() == null, "no accuracy before any move")
    repeat(10) { a.add(true, true, 20, 15) }
    a.add(false, false, 20, 25)
    val good = a.myScore!!
    a.add(true, true, 20, -800)
    val after = a.myScore!!
    println("  ten good moves: $good%, plus one blunder: $after%, text: ${a.text()}")
    check(good >= 95, "ten good moves give a high score")
    check(after < good - 10, "a blunder pulls the score down a lot")
    check(a.text()!!.startsWith("you ") && " · bot " in a.text()!!, "text format")
    a.reset()
    check(a.myScore == null && a.botScore == null, "reset clears it")

    // the position after a guessed move, and position keys that ignore the move counters
    val start = Board().fen
    val e4 = Game.after(start, "e2e4")
    check(e4 != null && Game.posKey(e4).startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b"), "after e2e4: $e4")
    check(Game.after(start, "e2e5") == null, "illegal move gives null")
    val tb = Board()
    tb.doMove(com.github.bhlangonijr.chesslib.move.Move("e2e4", Side.WHITE))
    check(Game.posKey(tb.fen) == Game.posKey(e4!!), "the tracker's board and the guessed board give the same key")
    // a guessed reply is worked out for the position after the guess
    val adv = Advisor(sf, null)
    val ready = adv.full(e4, 3, 300, threat = false)
    check(ready.cands.isNotEmpty(), "answer ready for the guessed position")
}

fun main(args: Array<String>) {
    val fixtures = args[0]
    val sfPath = args[1]
    val sf = UciEngine(sfPath)
    sf.configure(UciEngine.MAX_ELO)
    realShots(fixtures)
    special(sf)
    promoRecheck()
    promoLost()
    sideSwitch()
    premoveHold()
    holdDrag()
    fastPlay()
    takeback()
    noAiMidGame()
    midGame()
    println("== simulated games")
    var seed = 100L
    for (look in looks.indices) for (white in listOf(true, false)) {
        simulate("look$look ${if (white) "white" else "black"}", white, looks[look], sf, 90, seed++)
    }
    // other screen sizes and board positions
    simulate("small phone", true, looks[0], sf, 60, 7, size = 800, w = 900, h = 1900, left = 50, top = 400)
    simulate("big tablet black", false, looks[2], sf, 60, 8, size = 1600, w = 1800, h = 2560, left = 100, top = 400)
    engineTest(sf)
    adviceTest(sf)
    accuracyTest(sf)
    sf.close()
    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
