package com.example.chesshelper

/**
 * Labels the moves of the game you are playing, one at a time, as they happen (the little badge on the board).
 * Analysis only: it looks at a move that was already played and never picks or changes a move.
 * It keeps the little bit of history the labels need (still in the opening book, was the last move a capture,
 * how the opponent's last move was labeled).
 */
class LiveReview(engine: UciEngine, private val book: OpeningBook?) {
    private val analyzer = Analyzer(engine, book)
    private var inBook = false
    private var prevLabel: Label? = null
    private var prevCapture = false
    private var prevTo = ""

    /** a new game starts from startFen */
    fun newGame(startFen: String) {
        inBook = book != null && startFen.startsWith("$STANDARD_PLACEMENT w")
        prevLabel = null
        prevCapture = false
        prevTo = ""
    }

    /** we lost track of the moves (a move was missed): the history is no good any more */
    fun broken() {
        inBook = false
        prevLabel = null
        prevCapture = false
        prevTo = ""
    }

    /** labels the move uci played in position fen (next = the position after it). null if it can't be judged */
    fun judge(fen: String, uci: String, next: String, ms: Int, rating: Int): ReviewedMove? {
        val facts = analyzer.moveFacts(fen, uci)
        if (facts == null) {
            broken()
            return null
        }
        val bookName = if (inBook && book != null) book.name(next) else null
        if (bookName == null) inBook = false
        val recapture = prevCapture && facts.second == prevTo
        val mv = analyzer.judge(fen, uci, next, ms, rating, bookName, recapture, prevLabel)
        prevLabel = mv.label
        prevCapture = facts.first
        prevTo = facts.second
        return mv
    }

    /** the label a move would get, without counting it in the game (the brilliant hunter asks this) */
    fun peek(fen: String, uci: String, ms: Int, rating: Int): Label? {
        val next = Game.after(fen, uci) ?: return null
        return analyzer.judge(fen, uci, next, ms, rating, null, false, null).label
    }

    /** the engine's best line for a position (the side to move's view). remembered, so asking twice is free */
    fun topLine(fen: String, ms: Int): Line? = analyzer.topLine(fen, ms)
}
