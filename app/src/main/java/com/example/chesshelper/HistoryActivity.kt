package com.example.chesshelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * the match history: every game the helper followed, newest first, with the result, your side, accuracy and opening.
 * tap a game to open it in the game review. hold a game to set its result yourself (a resign or a loss on time
 * can't be seen on the board) or to delete it.
 */
class HistoryActivity : Activity() {
    private lateinit var ui: Ios
    private var onlyBrilliant = false   // the filter: all games, or only games with a brilliant move
    private val when_ = SimpleDateFormat("MMM d, HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        ui = Ios(this)
        val games = GameHistory.all(this)
        val (scroll, col) = ui.screen(this, "match history", if (games.isEmpty()) null else "${games.size} games")

        if (games.isEmpty()) {
            val g = ui.group(col, null)
            g.add(ui.noteRow("no games yet. start the helper and play: every game is saved here by itself"))
            setContentView(scroll)
            return
        }

        // a quick count of the games with a known result
        val won = games.count { it.shownResult == "win" }
        val lost = games.count { it.shownResult == "loss" }
        val drawn = games.count { it.shownResult == "draw" }
        val accs = games.mapNotNull { it.accMe }
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        tiles.addView(tile("$won", "won", ui.green), ui.weight().apply { rightMargin = ui.dp(4f) })
        tiles.addView(tile("$lost", "lost", ui.red), ui.weight().apply { leftMargin = ui.dp(4f); rightMargin = ui.dp(4f) })
        tiles.addView(tile("$drawn", "drawn", ui.gray), ui.weight().apply { leftMargin = ui.dp(4f); rightMargin = ui.dp(4f) })
        tiles.addView(tile(if (accs.isEmpty()) "-" else "${accs.average().toInt()}", "avg acc", ui.blue), ui.weight().apply { leftMargin = ui.dp(4f) })
        col.addView(tiles, ui.full(top = 16f))

        // ---- brilliant moves: how many ever, and where
        val bril = games.flatMap { gm -> gm.highlights.filter { it.label == "BRILLIANT" }.map { Pair(gm, it) } }
        val totalBril = games.sumOf { it.brilliants }
        val totalGreat = games.sumOf { it.greats }
        val inGames = games.count { it.brilliants > 0 }
        col.addView(brilliantCard(totalBril, inGames, totalGreat), ui.full(top = 10f))
        if (bril.isNotEmpty()) {
            val bg = ui.group(col, "your brilliant moves", "tap one to see it in the game review")
            for ((gm, h) in bril.take(60)) bg.add(brilliantRow(gm, h))
        }

        // ---- the games
        val shown = if (onlyBrilliant) games.filter { it.brilliants > 0 } else games
        val g = ui.group(col, "games", "tap a game to review it (that also counts its labels exactly). hold it to set the result (resign, time) or delete it")
        g.add(ui.segRow(null, listOf("all" to "all games", "bril" to "with a brilliant !!"), if (onlyBrilliant) "bril" else "all") { v ->
            onlyBrilliant = v == "bril"
            build()
        })
        if (shown.isEmpty()) g.add(ui.noteRow("no games with a brilliant move yet. keep going!"))
        for (game in shown) g.add(row(game))
        setContentView(scroll)
    }

    /** the big teal card: your brilliant moves so far */
    private fun brilliantCard(total: Int, inGames: Int, greats: Int): View {
        val teal = Label.BRILLIANT.color
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(ui.dp(16f), ui.dp(16f), ui.dp(16f), ui.dp(16f))
        box.background = ui.round(ui.cell, 14f)
        val icon = ui.text("!!", 22f, Color.WHITE, ui.bold)
        icon.gravity = Gravity.CENTER
        icon.background = ui.round(teal, 100f)
        box.addView(icon, LinearLayout.LayoutParams(ui.dp(52f), ui.dp(52f)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(ui.dp(14f), 0, 0, 0)
        texts.addView(ui.text("$total brilliant ${if (total == 1) "move" else "moves"}", 22f, teal, ui.bold))
        val sub = when {
            total == 0 -> "none yet. live labels count them while you play"
            else -> "in $inGames ${if (inGames == 1) "game" else "games"}"
        } + if (greats > 0) " · $greats great" else ""
        texts.addView(ui.text(sub, 14f, ui.label2).apply { setPadding(0, ui.dp(3f), 0, 0) })
        box.addView(texts, ui.weight())
        return box
    }

    /** one brilliant move: "!! Nf5", which move of which game, tap = review at that move */
    private fun brilliantRow(game: SavedGame, h: Highlight): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(14f), ui.dp(10f), ui.dp(14f), ui.dp(10f))
        row.background = ui.pressable(ColorDrawable(Color.TRANSPARENT), ColorDrawable(ui.pressed))
        val b = ui.text("!!", 13f, Color.WHITE, ui.bold)
        b.gravity = Gravity.CENTER
        b.background = ui.round(Label.BRILLIANT.color, 100f)
        row.addView(b, LinearLayout.LayoutParams(ui.dp(30f), ui.dp(30f)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(ui.dp(12f), 0, ui.dp(8f), 0)
        val n = game.moveNumber(h.ply)
        val dots = if (game.meWhite) "." else "..."
        texts.addView(ui.text("$n$dots ${h.san}", 16f, ui.label, ui.medium))
        val parts = ArrayList<String>()
        parts.add(game.opening ?: "game")
        when (game.shownResult) {
            "win" -> parts.add("won")
            "loss" -> parts.add("lost")
            "draw" -> parts.add("draw")
        }
        parts.add(when_.format(Date(game.id)))
        texts.addView(ui.text(parts.joinToString(" · "), 13f, ui.label2).apply { maxLines = 1; setPadding(0, ui.dp(2f), 0, 0) })
        row.addView(texts, ui.weight())
        row.addView(ui.text("›", 22f, ui.label3, ui.medium))
        row.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java).putExtra("history_id", game.id).putExtra("goto_ply", h.ply))
        }
        return row
    }

    private fun tile(big: String, small: String, color: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(ui.dp(6f), ui.dp(12f), ui.dp(6f), ui.dp(12f))
        box.background = ui.round(ui.cell, 12f)
        val n = ui.text(big, 24f, color, ui.bold)
        n.gravity = Gravity.CENTER
        box.addView(n)
        val l = ui.text(small, 12f, ui.label2)
        l.gravity = Gravity.CENTER
        l.setPadding(0, ui.dp(3f), 0, 0)
        box.addView(l)
        return box
    }

    private fun row(game: SavedGame): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(14f), ui.dp(11f), ui.dp(14f), ui.dp(11f))
        row.background = ui.pressable(ColorDrawable(Color.TRANSPARENT), ColorDrawable(ui.pressed))

        // the result in a colored circle: W, L, D, or a dash when nobody knows yet
        val res = game.shownResult
        val (letter, color) = when (res) {
            "win" -> Pair("W", ui.green)
            "loss" -> Pair("L", ui.red)
            "draw" -> Pair("D", ui.gray)
            else -> Pair("–", ui.label3)
        }
        val badge = ui.text(letter, 15f, Color.WHITE, ui.bold)
        badge.gravity = Gravity.CENTER
        badge.background = ui.round(color, 100f)
        row.addView(badge, LinearLayout.LayoutParams(ui.dp(34f), ui.dp(34f)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(ui.dp(12f), 0, ui.dp(8f), 0)
        val moves = (game.moves.size + 1) / 2
        texts.addView(ui.text(game.opening ?: "game · $moves moves", 16f, ui.label, ui.medium).apply { maxLines = 1 })
        val parts = ArrayList<String>()
        parts.add(if (game.meWhite) "white" else "black")
        if (game.opening != null) parts.add("$moves moves")
        game.accMe?.let { parts.add("acc $it%") }
        if (game.how.isNotEmpty() && game.manual.isEmpty()) parts.add(game.how)
        parts.add(when_.format(Date(game.id)))
        val sub = ui.text(parts.joinToString(" · "), 13f, ui.label2)
        sub.maxLines = 2
        sub.setPadding(0, ui.dp(3f), 0, 0)
        texts.addView(sub)
        row.addView(texts, ui.weight())

        // a teal pill when the game has brilliant moves
        if (game.brilliants > 0) {
            row.addView(ui.pill("!! ${game.brilliants}", Label.BRILLIANT.color), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.dp(6f) })
        }
        row.addView(ui.text("›", 22f, ui.label3, ui.medium))
        row.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java).putExtra("history_id", game.id))
        }
        row.setOnLongClickListener {
            ask(game)
            true
        }
        return row
    }

    /** set the result yourself, or delete the game */
    private fun ask(game: SavedGame) {
        val options = arrayOf("i won", "i lost", "draw", "unknown (read it from the board)", "delete this game")
        AlertDialog.Builder(this, if (ui.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(game.opening ?: "this game")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> GameHistory.setResult(this, game.id, "win")
                    1 -> GameHistory.setResult(this, game.id, "loss")
                    2 -> GameHistory.setResult(this, game.id, "draw")
                    3 -> GameHistory.setResult(this, game.id, "")
                    4 -> GameHistory.delete(this, game.id)
                }
                build()
            }
            .setNegativeButton("cancel", null)
            .show()
    }
}
