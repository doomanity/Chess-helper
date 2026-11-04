package com.example.chesshelper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
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
    private var accent = Color.rgb(0, 229, 255)
    private val d get() = resources.displayMetrics.density
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
        accent = Theme.accent(this, Cfg.load(this).theme)
        window.statusBarColor = Color.rgb(10, 13, 22)
        window.navigationBarColor = Color.rgb(10, 13, 22)
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
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(24f), dp(16f), dp(30f))

        val title = TextView(this)
        title.text = "game review"
        title.textSize = 30f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.setShadowLayer(26f, 0f, 0f, accent)
        root.addView(title)
        val sub = TextView(this)
        sub.text = "labels every move like chess.com. analysis only, it never picks a move for you"
        sub.textSize = 13f
        sub.setTextColor(Theme.withAlpha(accent, 220))
        sub.setPadding(0, 0, 0, dp(6f))
        root.addView(sub)

        // ---- where the game comes from
        var card = newCard("game")
        val srcRow = LinearLayout(this)
        srcRow.orientation = LinearLayout.HORIZONTAL
        srcRow.addView(button("live game", accent, true) { startLive() }, weight())
        srcRow.addView(button("paste pgn", accent, false) { askPgn() }, weight())
        card.addView(srcRow)
        label(card, "live game = the game the helper is following. it keeps up while you play. paste pgn = any game")

        // ---- settings
        card = newCard("analysis")
        label(card, "depth")
        card.addView(seg(listOf("250" to "fast", "500" to "normal", "1000" to "deep"), ms().toString()) { v ->
            prefs.edit().putInt("review_ms", v.toInt()).apply()
            game?.let { submit(it, -2) }
        })
        label(card, "judge like a player rated about")
        card.addView(seg(listOf("600" to "600", "1000" to "1000", "1500" to "1500", "2000" to "2000", "2500" to "2500"), rating().toString()) { v ->
            prefs.edit().putInt("review_rating", v.toInt()).apply()
            game?.let { submit(it, -2) }
        })
        label(card, "lower ratings are judged more kindly (the same mistake counts for less), like on chess.com")

        status = TextView(this)
        status.textSize = 13f
        status.setTextColor(Color.argb(200, 220, 228, 240))
        status.setPadding(dp(4f), dp(10f), 0, 0)
        status.text = "pick a game above"
        root.addView(status)

        // ---- the board
        card = newCard("board")
        board = ReviewBoardView(this)
        card.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })
        board.show(START_FEN, null, null, 0.5f, false)
        caption = TextView(this)
        caption.textSize = 20f
        caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        caption.setTextColor(Color.WHITE)
        caption.setPadding(dp(2f), dp(12f), 0, 0)
        caption.text = "start position"
        card.addView(caption)
        detail = TextView(this)
        detail.textSize = 13.5f
        detail.setTextColor(Color.argb(190, 220, 228, 240))
        detail.setPadding(dp(2f), dp(2f), 0, dp(8f))
        card.addView(detail)
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.addView(navButton("«") { goTo(0) }, weight())
        nav.addView(navButton("‹") { goTo(ply - 1) }, weight())
        nav.addView(navButton("›") { goTo(ply + 1) }, weight())
        nav.addView(navButton("»") { goTo(Int.MAX_VALUE) }, weight())
        nav.addView(navButton("flip") { board.flipped = !board.flipped }, weight())
        card.addView(nav)
        graph = EvalGraphView(this)
        graph.accent = accent
        graph.onSeek = { p -> goTo(p) }
        card.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })

        // ---- summary
        card = newCard("summary")
        summaryBox = LinearLayout(this)
        summaryBox.orientation = LinearLayout.VERTICAL
        card.addView(summaryBox)

        // ---- moves
        card = newCard("moves")
        movesBox = LinearLayout(this)
        movesBox.orientation = LinearLayout.VERTICAL
        card.addView(movesBox)

        val scroll = ScrollView(this)
        scroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.rgb(10, 13, 22), Theme.panel(accent, 255), Color.rgb(8, 10, 18)
        ))
        scroll.addView(root)
        setContentView(scroll)
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
        AlertDialog.Builder(this)
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
            cap.setSpan(ForegroundColorSpan(mv.label.color), s, cap.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        summaryBox.addView(statRow("", Color.WHITE, "white", "black", header = true))
        summaryBox.addView(statRow("accuracy", Color.WHITE, w.accuracy?.let { "$it%" } ?: "-", b.accuracy?.let { "$it%" } ?: "-", bold = true))
        val order = listOf(
            Label.BRILLIANT, Label.GREAT, Label.BEST, Label.EXCELLENT, Label.GOOD, Label.BOOK,
            Label.INACCURACY, Label.MISTAKE, Label.MISS, Label.BLUNDER
        )
        for (l in order) {
            val wc = w.count(l)
            val bc = b.count(l)
            if (wc == 0 && bc == 0) continue
            summaryBox.addView(statRow("${l.symbol}  ${l.text}", l.color, wc.toString(), bc.toString()))
        }
    }

    private fun statRow(name: String, nameColor: Int, w: String, b: String, header: Boolean = false, bold: Boolean = false): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(5f), 0, dp(5f))
        fun cell(t: String, weight: Float, color: Int, align: Int): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = if (header) 12f else 14.5f
            tv.setTextColor(color)
            tv.gravity = align
            if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
            return tv
        }
        val dim = Color.argb(170, 220, 228, 240)
        cell(name, 2f, nameColor, Gravity.START)
        cell(w, 1f, if (header) dim else Color.WHITE, Gravity.CENTER)
        cell(b, 1f, if (header) dim else Color.WHITE, Gravity.CENTER)
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
            val num = TextView(this)
            num.text = "${rows.size + 1}."
            num.textSize = 13f
            num.setTextColor(Color.argb(140, 220, 228, 240))
            num.setPadding(0, dp(7f), 0, dp(7f))
            row.addView(num, LinearLayout.LayoutParams(dp(34f), ViewGroup.LayoutParams.WRAP_CONTENT))
            for (k in 0..1) {
                val c = TextView(this)
                c.textSize = 15f
                c.setTextColor(Color.WHITE)
                c.setPadding(dp(8f), dp(7f), dp(4f), dp(7f))
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            movesBox.addView(row)
            rows.add(row)
        }
        val cell = rows[rowIdx].getChildAt(1 + col) as TextView
        val sb = SpannableStringBuilder(m.san)
        val s = sb.length
        sb.append("  ").append(m.label.symbol)
        sb.setSpan(ForegroundColorSpan(m.label.color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cell.text = sb
        cell.setOnClickListener { goTo(i + 1) }
        cells.add(cell)
    }

    private fun highlightCell(p: Int) {
        for ((i, c) in cells.withIndex()) {
            c.background = if (i == p - 1) rounded(Theme.withAlpha(accent, 70), Theme.withAlpha(accent, 160), 10f) else null
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun dp(v: Float) = (v * d).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3f), 0, dp(3f), 0)
    }

    private fun rounded(fillColor: Int, strokeColor: Int, radius: Float = 14f) = GradientDrawable().apply {
        cornerRadius = radius * d
        setColor(fillColor)
        setStroke(dp(1.2f), strokeColor)
    }

    private fun newCard(t: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
        box.background = rounded(Color.argb(150, 20, 26, 40), Theme.withAlpha(accent, 110), 22f)
        box.elevation = 4f * d
        val h = TextView(this)
        h.text = t.uppercase()
        h.textSize = 12f
        h.letterSpacing = 0.16f
        h.setTextColor(accent)
        h.setShadowLayer(12f, 0f, 0f, accent)
        h.setPadding(0, 0, 0, dp(6f))
        box.addView(h)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14f)
        })
        return box
    }

    private fun label(parent: LinearLayout, t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12.5f
        v.setTextColor(Color.argb(175, 220, 228, 240))
        v.setPadding(dp(2f), dp(8f), 0, dp(5f))
        parent.addView(v)
    }

    private fun seg(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        row.background = rounded(Color.argb(55, 255, 255, 255), Color.argb(35, 255, 255, 255), 16f)
        val views = ArrayList<TextView>()
        fun paint(sel: String) {
            for ((i, v) in views.withIndex()) {
                val on = options[i].first == sel
                v.background = if (on) rounded(accent, accent, 13f) else null
                v.setTextColor(if (on) Color.BLACK else Color.argb(220, 255, 255, 255))
            }
        }
        for ((value, text) in options) {
            val t = TextView(this)
            t.text = text
            t.textSize = 13.5f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
            t.setOnClickListener {
                paint(value)
                onPick(value)
            }
            views.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(current)
        return row
    }

    private fun button(text: String, col: Int, filled: Boolean, onClick: () -> Unit): View {
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setPadding(dp(12f), dp(13f), dp(12f), dp(13f))
        t.background = if (filled) rounded(col, Theme.core(col), 18f) else rounded(Theme.withAlpha(col, 30), Theme.withAlpha(col, 200), 18f)
        t.setTextColor(if (filled) Color.BLACK else col)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun navButton(text: String, onClick: () -> Unit): View {
        val t = TextView(this)
        t.text = text
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
        t.background = rounded(Color.argb(40, 255, 255, 255), Color.argb(60, 255, 255, 255), 14f)
        t.setOnClickListener { onClick() }
        return t
    }
}
