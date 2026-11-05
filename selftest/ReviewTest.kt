import com.example.chesshelper.*

/**
 * checks the move labels (Review.kt) with made-up engine numbers, no phone, no engine, no chess library.
 * compile it together with Candidates.kt, Accuracy.kt and Review.kt and run ReviewTest:
 *   kotlinc Review.kt Accuracy.kt Candidates.kt ReviewTest.kt -include-runtime -d review-test.jar && java -cp review-test.jar ReviewTest
 */
object ReviewTest {
    private var failures = 0
    private fun check(ok: Boolean, what: String) {
        if (!ok) { failures++; println("  FAIL: $what") }
    }

    private fun ply(
        played: String = "e2e4", best: String = "e2e4", bestScore: Int = 30, second: Int? = 20, after: Int? = null,
        book: Boolean = false, recapture: Boolean = false, sac: Boolean = false, lead: Int = 0, prev: Label? = null
    ) = PlyInput(played, best, bestScore, second, after ?: bestScore, book, recapture, sac, lead, prev)

    @JvmStatic
    fun main(args: Array<String>) {
        // --- the thresholds (rating 1500 curve): every case is "how much worse than the best move"
        val l = { p: PlyInput -> Labeler.label(p) }
        check(l(ply()) == Label.BEST, "the best move is best")
        check(l(ply(played = "d2d4", after = 28)) == Label.BEST, "an equally good alternative is best too")
        check(l(ply(played = "d2d4", after = 10)) == Label.EXCELLENT, "20 cp worse is excellent")
        check(l(ply(played = "d2d4", after = -25)) == Label.GOOD, "55 cp worse is good")
        check(l(ply(played = "d2d4", after = -90)) == Label.INACCURACY, "120 cp worse is an inaccuracy")
        check(l(ply(played = "d2d4", after = -230)) == Label.MISTAKE, "260 cp worse is a mistake")
        check(l(ply(played = "d2d4", after = -500)) == Label.BLUNDER, "530 cp worse is a blunder")
        check(l(ply(played = "d2d4", bestScore = 0, after = -700)) == Label.BLUNDER, "an equal game thrown away is a blunder")

        // --- book wins over everything
        check(l(ply(played = "d2d4", after = -500, book = true)) == Label.BOOK, "book moves are book")

        // --- brilliant: a sacrifice that keeps the game fine and is (nearly) the best move
        check(l(ply(sac = true, bestScore = 40, second = -200)) == Label.BRILLIANT, "good sacrifice is brilliant")
        check(l(ply(sac = true, bestScore = 40, second = 30, after = null)) == Label.BRILLIANT, "good sacrifice is brilliant even if other moves are fine too")
        check(l(ply(played = "d2d4", sac = true, bestScore = 40, after = 20)) == Label.BRILLIANT, "a sacrifice a hair worse than best is still brilliant")
        check(l(ply(sac = true, bestScore = 900, second = 800)) != Label.BRILLIANT, "no brilliant when already crushing")
        check(l(ply(sac = true, bestScore = 100, lead = 1500)) != Label.BRILLIANT, "no brilliant a queen and more ahead")
        check(l(ply(played = "d2d4", sac = true, bestScore = 40, after = -400)) == Label.BLUNDER, "a sacrifice that ruins the position is a blunder")
        check(l(ply(sac = true, bestScore = -300, second = -400)) != Label.BRILLIANT, "a sacrifice that leaves you losing is not brilliant")
        check(l(ply(sac = true, book = true)) == Label.BOOK, "book beats brilliant")

        // --- great: the only move, turning the game
        check(l(ply(bestScore = 300, second = 0)) == Label.GREAT, "only move: equal becomes winning")
        check(l(ply(bestScore = 0, second = -400)) == Label.GREAT, "only move: losing becomes equal")
        check(l(ply(bestScore = 300, second = 250)) == Label.BEST, "two good moves: just best")
        check(l(ply(bestScore = 300, second = 0, recapture = true)) == Label.BEST, "a recapture is never great")
        check(l(ply(bestScore = 30, second = -60)) == Label.BEST, "a small gap is not great")
        check(l(ply(bestScore = 30, second = -250)) == Label.GREAT, "the only move that keeps it equal is great")
        check(l(ply(bestScore = 700, second = 250)) == Label.GREAT, "the only move that keeps a big win is great")
        check(l(ply(bestScore = 30, second = null)) == Label.BEST, "a single legal move is not great")
        check(l(ply(played = "d2d4", bestScore = 300, second = 0, after = 0)) != Label.GREAT, "playing the second move is not great")

        // --- miss: the opponent slipped and you did not use it
        check(l(ply(played = "d2d4", bestScore = 300, after = 100, prev = Label.BLUNDER)) == Label.MISS, "missed chance after a blunder")
        check(l(ply(played = "d2d4", bestScore = 300, after = 100, prev = Label.MISTAKE)) == Label.MISS, "missed chance after a mistake")
        check(l(ply(played = "d2d4", bestScore = 300, after = 100)) == Label.MISTAKE, "without the opponent's slip it is a plain mistake")
        check(l(ply(played = "d2d4", bestScore = 300, after = -500, prev = Label.BLUNDER)) == Label.BLUNDER, "a real blunder stays a blunder")
        check(l(ply(played = "d2d4", bestScore = 0, after = -150, prev = Label.BLUNDER)) == Label.MISTAKE, "no miss when there was nothing to take")

        // --- mates
        check(l(ply(played = "d2d4", bestScore = 29990, after = 300)) == Label.BLUNDER, "missing a mate in 1 costs a lot")
        check(l(ply(bestScore = 29990, second = 0)) == Label.GREAT, "finding the only mate is great")
        check(l(ply(bestScore = 29990, second = 900)) == Label.BEST, "a mate when other moves also win big is just best")
        check(l(ply(sac = true, bestScore = 29990, second = 350, after = null)) == Label.BRILLIANT, "a sacrifice that leads to mate is brilliant")
        check(l(ply(sac = true, bestScore = 29990, second = 29950, after = null)) != Label.BRILLIANT, "no brilliant when a quiet move mates too")
        check(l(ply(played = "d2d4", bestScore = 100, after = -29990)) == Label.BLUNDER, "walking into a mate is a blunder")

        // --- which side is at the bottom, from a board read as screen rows
        fun rows(fen: String, whiteBottom: Boolean): List<String> {
            val ranks = fen.substringBefore(' ').split("/").map { r -> r.map { if (it.isDigit()) ".".repeat(it - '0') else it.toString() }.joinToString("") }
            return if (whiteBottom) ranks else ranks.reversed().map { it.reversed() }
        }
        val attack = "r1bq1rk1/pp3pBp/2n1pN1Q/3pP3/3P2R1/2P5/P4PPP/R5K1 b - - 0 1" // white pieces swarm the black king
        for (wb in listOf(true, false)) {
            check(HaikuParse.whiteBottom(rows(attack, wb), null) == wb, "attack, no claim, whiteBottom=$wb")
            check(HaikuParse.whiteBottom(rows(attack, wb), !wb) == wb, "attack, wrong claim: the pawns win, whiteBottom=$wb")
            check(HaikuParse.whiteBottom(rows(attack, wb), wb) == wb, "attack, right claim, whiteBottom=$wb")
        }
        val ending = "8/2P1K3/1R6/8/8/8/5k2/8 w - - 0 1" // white king and pawn far up the board: only the coordinates know
        for (wb in listOf(true, false)) check(HaikuParse.whiteBottom(rows(ending, wb), wb) == wb, "ending, trust the coordinates, whiteBottom=$wb")
        check(HaikuParse.whiteBottom(rows("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", false), null) == false, "start, black at the bottom")

        // --- the blunder alert: squares where every move of yours that goes there blunders
        val sqs = Labeler.blunderSquares(50, listOf(
            Pair("d3e4", -400), Pair("f3e4", -350),        // both moves to e4 lose a lot
            Pair("c4d5", -400), Pair("e4d5", 40),          // one move to d5 is fine
            Pair("h4g5", null),                            // not scored: leave it alone
            Pair("a2a3", 45), Pair("b1c3", 30),            // fine moves
            Pair("h2h3", -29990)                           // walks into a mate
        ), 1500)
        check(sqs == listOf("e4", "h3"), "blunder squares: $sqs")
        check(Labeler.blunderSquares(-29990, listOf(Pair("a2a3", -29990), Pair("b2b3", -29990)), 1500).isEmpty(), "already mated anyway: nothing to warn about")
        check(Labeler.blunderSquares(700, listOf(Pair("a2a3", 450)), 600).isEmpty(), "a small slip in a won game is no blunder at 600")

        // --- the curve is kinder to lower rated players
        val slip = ply(played = "d2d4", bestScore = 150, after = 90)
        check(Labeler.label(slip, 2500) == Label.INACCURACY, "2500: a 60 cp drop at +1.5 is an inaccuracy")
        check(Labeler.label(slip, 600) == Label.GOOD, "600: the same drop is only good")
        check(Labeler.ep(0, 1500) == 0.5, "0 cp is half a point")
        check(Labeler.ep(300, 1500) > Labeler.ep(300, 600), "the same eval is more decisive for a stronger player")

        // --- eval text and bar
        check(ReviewFmt.evalText(0) == "0.0", "eval 0")
        check(ReviewFmt.evalText(135) == "+1.4" || ReviewFmt.evalText(135) == "+1.3", "eval +1.35")
        check(ReviewFmt.evalText(-120) == "-1.2", "eval -1.2")
        check(ReviewFmt.evalText(29970) == "M3", "mate in 3")
        check(ReviewFmt.evalText(-29980) == "-M2", "mated in 2")
        check(ReviewFmt.whiteShare(0) == 0.5f, "bar in the middle")
        check(ReviewFmt.whiteShare(29990) == 1f && ReviewFmt.whiteShare(-29990) == 0f, "bar at the ends for mates")

        // --- opening book: the hash has to match tools/build_openings.py
        check(OpeningBook.key("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") == 0x65e66843, "start position hash")
        val book = OpeningBook.parse(sequenceOf("65e66843\tstart", "bad line", "0003f68d\tKing's Gambit"))
        check(book.size == 2, "book lines parsed")
        check(book.name("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") == "start", "book lookup ignores the move counters")
        check(book.name("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1") == null, "other side to move is another position")

        // --- pgn text
        val pgn = """
            [Event "x"]
            [FEN "8/8/8/8/8/8/4K3/4k3 w - - 0 1"]

            1. e4 {a comment} e5 (1... c5 2. Nf3) 2. Nf3! Nc6?! 3.Bb5 a6 4. 0-0 $1 O-O ; trailing comment
            5. exd5 e8=Q+ 1-0
        """.trimIndent()
        val p = PgnText.parse(pgn)
        check(p.fen == "8/8/8/8/8/8/4K3/4k3 w - - 0 1", "fen header read")
        check(p.tokens == listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "O-O", "O-O", "exd5", "e8=Q"), "pgn tokens: ${p.tokens}")

        // --- summary
        val mv = { ply: Int, white: Boolean, lab: Label, acc: Double ->
            ReviewedMove(ply, "e2e4", "e4", white, lab, "e2e4", "e4", 0, 0, acc, null)
        }
        val s = Summary.of(listOf(mv(1, true, Label.BEST, 100.0), mv(2, false, Label.BLUNDER, 20.0), mv(3, true, Label.GREAT, 100.0)), true)
        check(s.count(Label.BEST) == 1 && s.count(Label.GREAT) == 1 && s.count(Label.BLUNDER) == 0, "summary counts white only")
        check(s.accuracy == 100, "accuracy of two perfect moves")

        println(if (failures == 0) "review test: all good" else "review test: $failures failed")
        if (failures != 0) System.exit(1)
    }
}
