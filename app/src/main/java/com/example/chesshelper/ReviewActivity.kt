package com.example.chesshelper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * the game review screen: takes a game (the one the helper is following right now, or a pasted pgn), has stockfish
 * look at every position, and labels every move like chess.com does (brilliant, great, best, excellent, good, book,
 * inaccuracy, mistake, miss, blunder) with an accuracy score and an eval graph for both sides.
 * it only looks at moves that were already played. it never picks or changes a move.
 */
class ReviewActivity : Activity() {
    private class Job(val id: Int, val game: ReviewGame, val ms: Int, val rating: Int)

    private lateinit var prefs: SharedPreferences
    private lateinit var ui: Ios
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var board: ReviewBoardView
    private lateinit var graph: EvalGraphView
    private lateinit var caption: TextView
    private lateinit var detail: TextView
    private lateinit var status: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var movesBox: LinearLayout

    private var game: ReviewGame? = null
    private var result: ReviewResult? = null
    private var ply = 0          // the position on the board: 0 = the start, n = after move n
    private var wantPly = 0      // where to go when the next analysis starts to come in (-1 = the end)
    private var live = false     // follow the game the helper is playing
    private var shownJob = 0
    private val rows = ArrayList<LinearLayout>()
    private val cells = ArrayList<TextView>() // the move texts of the list, move 1 first

    private val queue = LinkedBlockingQueue<Job>()
    @Volatile private var latestJob = 0
    @Volatile private var stopping = false
    private var worker: Thread? = null
    @Volatile private var engine: UciEngine? = null

    private fun ms() = prefs.getInt("review_ms", 500)
    private fun rating() = prefs.getInt("review_rating", 1500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Cfg.prefs(this)
        build()
        if (intent?.getBooleanExtra("live", false) == true) startLive()
    }

    override fun onResume() {
        super.onResume()
        if (live) {
            main.removeCallbacks(poll)
            main.postDelayed(poll, 1500)
        }
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        try { engine?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ screen

    private fun build() {
        ui = Ios(this)
        val (scroll, col) = ui.screen(this, "game review", "labels every move like chess.com. analysis only, it never picks a move for you")
        root = col

        // ---- where the game comes from
        val src = LinearLayout(this)
        src.orientation = LinearLayout.HORIZONTAL
        src.addView(ui.bigButton("live game") { startLive() }, ui.weight(gap = 0f).apply { rightMargin = ui.dp(5f) })
        src.addView(ui.bigButton("paste pgn", filled = false) { askPgn() }, ui.weight(gap = 0f).apply { leftMargin = ui.dp(5f) })
        root.addView(src, ui.full(top = 18f))
        status = ui.text("pick a game: live = the game the helper is following (it keeps up while you play)", 13f, ui.label2)
        status.setPadding(ui.dp(16f), ui.dp(8f), ui.dp(16f), 0)
        root.addView(status)

        // ---- the board
        var g = ui.group(root, "board")
        val boardBox = LinearLayout(this)
        boardBox.orientation = LinearLayout.VERTICAL
        boardBox.setPadding(ui.dp(12f), ui.dp(12f), ui.dp(12f), ui.dp(12f))
        val frame = roundedFrame(this, ui, ui.cell2, 10f)
        board = ReviewBoardView(this)
        frame.addView(board)
        boardBox.addView(frame, ui.full())
        board.show(START_FEN, null, null, 0.5f, false)
        caption = ui.text("start position", 20f, ui.label, ui.bold)
        caption.setPadding(ui.dp(2f), ui.dp(14f), 0, 0)
        boardBox.addView(caption)
        detail = ui.text("", 15f, ui.label2)
        detail.setPadding(ui.dp(2f), ui.dp(4f), 0, ui.dp(12f))
        boardBox.addView(detail)
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.background = ui.round(ui.fill, 9f)
        nav.setPadding(ui.dp(2f), ui.dp(2f), ui.dp(2f), ui.dp(2f))
        nav.addView(navButton("«") { goTo(0) }, ui.weight())
        nav.addView(navButton("‹") { goTo(ply - 1) }, ui.weight())
        nav.addView(navButton("›") { goTo(ply + 1) }, ui.weight())
        nav.addView(navButton("»") { goTo(Int.MAX_VALUE) }, ui.weight())
        nav.addView(navButton("flip") { board.flipped = !board.flipped }, ui.weight())
        boardBox.addView(nav, ui.full())
        val graphFrame = roundedFrame(this, ui, ui.cell2, 10f)
        graph = EvalGraphView(this)
        graph.accent = ui.blue
        graph.onSeek = { p -> goTo(p) }
        graphFrame.addView(graph)
        boardBox.addView(graphFrame, ui.full(top = 12f))
        g.add(boardBox)

        // ---- summary
        g = ui.group(root, "summary")
        summaryBox = LinearLayout(this)
        summaryBox.orientation = LinearLayout.VERTICAL
        summaryBox.setPadding(ui.dp(16f), ui.dp(6f), ui.dp(16f), ui.dp(6f))
        summaryBox.addView(ui.text("no game yet", 15f, ui.label2).apply { setPadding(0, ui.dp(6f), 0, ui.dp(6f)) })
        g.add(summaryBox)

        // ---- moves
        g = ui.group(root, "moves", "tap a move to see it on the board")
        movesBox = LinearLayout(this)
        movesBox.orientation = LinearLayout.VERTICAL
        movesBox.setPadding(ui.dp(8f), ui.dp(4f), ui.dp(8f), ui.dp(4f))
        g.add(movesBox)

        // ---- settings
        g = ui.group(root, "analysis", "lower ratings are judged more kindly (the same mistake counts for less), like on chess.com")
        g.add(ui.segRow("depth", listOf("250" to "fast", "500" to "normal", "1000" to "deep"), ms().toString()) { v ->
            prefs.edit().putInt("review_ms", v.toInt()).apply()
            game?.let { submit(it, -2) }
        })
        g.add(ui.segRow("judge like a player rated", listOf("600" to "600", "1000" to "1000", "1500" to "1500", "2000" to "2000", "2500" to "2500"), rating().toString()) { v ->
            prefs.edit().putInt("review_rating", v.toInt()).apply()
            game?.let { submit(it, -2) }
        })

        setContentView(scroll)
    }

    /** a label's color as text: the light screen needs it a bit darker to be readable */
    private fun ink(l: Label): Int {
        if (ui.dark) return l.color
        val c = l.color
        return Color.rgb((Color.red(c) * 0.78f).toInt(), (Color.green(c) * 0.78f).toInt(), (Color.blue(c) * 0.78f).toInt())
    }

    // ------------------------------------------------------------------ getting a game

    private val poll = object : Runnable {
        override fun run() {
            if (!live) return
            val g = GameLog.load(this@ReviewActivity)
            if (g != null && !g.same(game)) submit(g, -2)
            main.postDelayed(this, 1500)
        }
    }

    private fun startLive() {
        val g = GameLog.load(this)
        if (g == null) {
            toast("no game recorded yet: start the helper and play a few moves first")
            return
        }
        live = true
        board.flipped = !Cfg.load(this).iAmWhite
        submit(g, -1)
        main.removeCallbacks(poll)
        main.postDelayed(poll, 1500)
    }

    private fun askPgn() {
        val box = EditText(this)
        box.minLines = 6
        box.gravity = Gravity.TOP or Gravity.START
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        box.hint = "paste a pgn or a list of moves, like 1. e4 e5 2. Nf3"
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip != null && clip.length > 8 && clip.any { it.isLetter() } && clip.any { it.isDigit() }) box.setText(clip)
        } catch (_: Exception) {
        }
        box.textSize = 15f
        AlertDialog.Builder(this, if (ui.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("paste a game")
            .setView(box)
            .setPositiveButton("review") { _, _ -> loadPgn(box.text.toString()) }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun loadPgn(text: String) {
        val g = PgnReader.read(text)
        if (g == null || g.moves.isEmpty()) {
            toast(g?.note ?: "no moves found in that text")
            return
        }
        live = false
        main.removeCallbacks(poll)
        board.flipped = false
        g.note?.let { toast(it) }
        submit(g, 0)
    }

    /** starts reviewing a game. startAt: where to put the board when it comes in (-1 the end, -2 stay where we are) */
    private fun submit(g: ReviewGame, startAt: Int) {
        game = g
        val r = result
        wantPly = when {
            startAt >= -1 -> startAt
            r != null && ply >= r.fens.size - 1 -> -1 // we were looking at the latest position: keep following
            else -> ply
        }
        if (worker == null) {
            worker = Thread { workLoop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        latestJob++
        queue.put(Job(latestJob, g, ms(), rating()))
        status.text = "analyzing..."
    }

    // ------------------------------------------------------------------ the engine, on its own thread

    private fun workLoop() {
        val sf = try {
            UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath).also { it.configure(UciEngine.MAX_ELO) }
        } catch (e: Exception) {
            main.post {
                toast("stockfish failed to start: ${e.message}")
                status.text = "stockfish failed to start"
            }
            return
        }
        engine = sf
        val book = try {
            assets.open("openings.tsv").bufferedReader().useLines { OpeningBook.parse(it) }
        } catch (e: Exception) {
            null
        }
        val analyzer = Analyzer(sf, book)
        try {
            while (!stopping) {
                var job = queue.take()
                while (true) { // only the newest request matters
                    val newer = queue.poll() ?: break
                    job = newer
                }
                val j = job
                try {
                    analyzer.analyze(j.game, j.ms, j.rating, { stopping || queue.isNotEmpty() }) { res ->
                        main.post { onProgress(j.id, res) }
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (!stopping) main.post { status.text = "analysis failed: ${e.message}" }
                }
            }
        } catch (e: InterruptedException) {
            // closing
        } finally {
            sf.close()
        }
    }

    // ------------------------------------------------------------------ showing the review

    private fun onProgress(id: Int, res: ReviewResult) {
        if (id != latestJob || isFinishing) return
        val first = id != shownJob
        shownJob = id
        result = res
        if (first) {
            clearMoveList()
            ply = if (wantPly < 0) res.fens.size - 1 else minOf(wantPly, res.fens.size - 1)
        }
        for (i in cells.size until res.moves.size) addMoveCell(i, res.moves[i], res)
        rebuildSummary(res.moves)
        val total = res.fens.size - 1
        val base = if (res.moves.size < total) "analyzing ${res.moves.size}/$total moves..." else "analysis done · $total moves"
        status.text = if (res.note != null) "$base · ${res.note}" else base
        // the move on the board just got its label (or the board has just been set up)
        if (first || ply == res.moves.size) showPly(true) else if (ply > res.moves.size) showPly(false) else graph.set(res.evalsWhite, res.moves.map { it.label }, ply)
    }

    private fun goTo(p: Int) {
        val r = result ?: return
        ply = p.coerceIn(0, r.fens.size - 1)
        showPly(true)
    }

    private fun showPly(animate: Boolean) {
        val r = result ?: return
        val p = ply.coerceIn(0, r.fens.size - 1)
        ply = p
        val mv = if (p >= 1 && p - 1 < r.moves.size) r.moves[p - 1] else null
        val score = r.evalsWhite.getOrNull(p)
        board.show(r.fens[p], mv?.uci, mv?.label, if (score != null) ReviewFmt.whiteShare(score) else 0.5f, animate)

        val cap = SpannableStringBuilder()
        if (p == 0) {
            cap.append("start position")
        } else if (mv == null) {
            cap.append("move $p · analyzing...")
        } else {
            val num = (p + 1) / 2
            cap.append(if (mv.moverWhite) "$num. " else "$num... ").append(mv.san).append("  ")
            val s = cap.length
            cap.append("${mv.label.symbol} ${mv.label.text}")
            cap.setSpan(ForegroundColorSpan(ink(mv.label)), s, cap.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        caption.text = cap

        val parts = ArrayList<String>()
        if (mv != null) {
            parts.add("eval ${ReviewFmt.evalText(mv.afterScoreWhite)}")
            if (mv.label == Label.BOOK) {
                mv.opening?.let { parts.add(it) }
            } else if (mv.uci != mv.bestUci) {
                parts.add("best was ${mv.bestSan} (${ReviewFmt.evalText(mv.bestScoreWhite)})")
            }
        } else if (score != null) {
            parts.add("eval ${ReviewFmt.evalText(score)}")
        }
        detail.text = parts.joinToString(" · ")

        graph.set(r.evalsWhite, r.moves.map { it.label }, p)
        highlightCell(p)
    }

    // ------------------------------------------------------------------ summary and move list

    private fun rebuildSummary(moves: List<ReviewedMove>) {
        summaryBox.removeAllViews()
        if (moves.isEmpty()) return
        val w = Summary.of(moves, true)
        val b = Summary.of(moves, false)
        // two big accuracy tiles, then a count of every label
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        tiles.setPadding(0, ui.dp(10f), 0, ui.dp(8f))
        tiles.addView(accTile("white", w.accuracy), ui.weight().apply { rightMargin = ui.dp(5f) })
        tiles.addView(accTile("black", b.accuracy), ui.weight().apply { leftMargin = ui.dp(5f) })
        summaryBox.addView(tiles)
        summaryBox.addView(statRow("", ui.label2, "white", "black", header = true))
        val order = listOf(
            Label.BRILLIANT, Label.GREAT, Label.BEST, Label.EXCELLENT, Label.GOOD, Label.BOOK,
            Label.INACCURACY, Label.MISTAKE, Label.MISS, Label.BLUNDER
        )
        for (l in order) {
            val wc = w.count(l)
            val bc = b.count(l)
            if (wc == 0 && bc == 0) continue
            summaryBox.addView(statRow("${l.symbol}  ${l.text}", ink(l), wc.toString(), bc.toString()))
        }
    }

    private fun accTile(who: String, acc: Int?): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(ui.dp(10f), ui.dp(14f), ui.dp(10f), ui.dp(14f))
        box.background = ui.round(ui.cell2, 12f)
        val n = ui.text(acc?.let { "$it" } ?: "-", 34f, ui.label, ui.bold)
        n.gravity = Gravity.CENTER
        box.addView(n)
        val l = ui.text("$who accuracy", 13f, ui.label2)
        l.gravity = Gravity.CENTER
        l.setPadding(0, ui.dp(4f), 0, 0)
        box.addView(l)
        return box
    }

    private fun statRow(name: String, nameColor: Int, w: String, b: String, header: Boolean = false): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, ui.dp(if (header) 6f else 8f), 0, ui.dp(if (header) 2f else 8f))
        fun cell(t: String, weight: Float, color: Int, align: Int): TextView {
            val tv = ui.text(t, if (header) 13f else 16f, color, if (header) ui.regular else ui.medium)
            tv.gravity = align
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
            return tv
        }
        cell(name, 2f, nameColor, Gravity.START)
        cell(w, 1f, if (header) ui.label2 else ui.label, Gravity.CENTER)
        cell(b, 1f, if (header) ui.label2 else ui.label, Gravity.CENTER)
        return row
    }

    private fun clearMoveList() {
        movesBox.removeAllViews()
        rows.clear()
        cells.clear()
    }

    private fun addMoveCell(i: Int, m: ReviewedMove, res: ReviewResult) {
        val blackFirst = res.fens[0].split(" ").getOrNull(1) == "b"
        val off = if (blackFirst) 1 else 0
        val rowIdx = (i + off) / 2
        val col = (i + off) % 2
        while (rows.size <= rowIdx) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            if (rows.size % 2 == 1) row.background = ui.round(Theme.withAlpha(ui.fill, if (ui.dark) 90 else 60), 8f)
            val num = ui.text("${rows.size + 1}", 14f, ui.label3)
            num.gravity = Gravity.END
            num.setPadding(0, ui.dp(9f), ui.dp(10f), ui.dp(9f))
            row.addView(num, LinearLayout.LayoutParams(ui.dp(34f), ViewGroup.LayoutParams.WRAP_CONTENT))
            for (k in 0..1) {
                val c = ui.text("", 16f, ui.label, ui.medium)
                c.setPadding(ui.dp(10f), ui.dp(9f), ui.dp(6f), ui.dp(9f))
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(2f), ui.dp(2f), ui.dp(2f), ui.dp(2f)) })
            }
            movesBox.addView(row)
            rows.add(row)
        }
        val cell = rows[rowIdx].getChildAt(1 + col) as TextView
        val sb = SpannableStringBuilder(m.san)
        val s = sb.length
        sb.append("  ").append(m.label.symbol)
        sb.setSpan(ForegroundColorSpan(ink(m.label)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cell.text = sb
        cell.setOnClickListener { goTo(i + 1) }
        cells.add(cell)
    }

    private fun highlightCell(p: Int) {
        for ((i, c) in cells.withIndex()) {
            c.background = if (i == p - 1) ui.round(Theme.withAlpha(ui.blue, if (ui.dark) 70 else 40), 7f) else null
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun navButton(text: String, onClick: () -> Unit): View {
        val t = ui.text(text, 17f, ui.blue, ui.medium)
        t.gravity = Gravity.CENTER
        t.setPadding(ui.dp(4f), ui.dp(9f), ui.dp(4f), ui.dp(9f))
        t.background = ui.pressable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), ui.round(ui.segOn, 7f))
        t.setOnClickListener { onClick() }
        return t
    }
}
