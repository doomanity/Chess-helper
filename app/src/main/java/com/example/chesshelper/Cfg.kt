package com.example.chesshelper

import android.content.Context
import android.content.SharedPreferences

/** all the settings, read from the app's saved preferences (the app screen and the side menu both write them) */
class Cfg(p: SharedPreferences) {
    val apiKey = (p.getString("api_key", "") ?: "").trim()
    val model = (p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5").trim()
    val colorMode = p.getString("color", "auto") ?: "auto"     // auto (the side at the bottom of the board), white, black
    val autoColor = colorMode == "auto"
    val iAmWhite = colorMode != "black"
    val myTurnAtStart = p.getBoolean("my_turn", true)
    val elo = p.getInt("elo2", UciEngine.MAX_ELO)
    val useMaia = p.getString("engine", "stockfish") == "maia3"
    val maiaHost = (p.getString("maia_host", "") ?: "").trim()
    val maiaModel = (p.getString("maia_model", "23m") ?: "23m").trim().ifEmpty { "23m" }
    val maiaElo = p.getInt("maia_elo", 1500)
    val maiaHuman = p.getInt("maia_human", 0)                   // 0..100: 0 = maia's top pick, 100 = full human randomness
    val predict = p.getBoolean("predict", true)                 // guess the bot's move while it thinks, get your answer ready
    val showAcc = p.getBoolean("show_acc", true)                // live accuracy for you and the bot
    val showLabels = p.getBoolean("show_labels", true)
    val blunderSquares = p.getBoolean("blunder_squares", true) // on your turn, dark red skull squares where every move blunders          // label every move that was just played (brilliant, blunder...) on the board
    val premove = p.getBoolean("premove", true)                 // while the bot thinks, also show your answer to its guessed move
    val reviewRating = p.getInt("review_rating", 1500)          // how the labels are judged (same setting as the review screen)
    val nMoves = p.getInt("n_moves", 3).coerceIn(1, 4)
    val thinkMs = p.getInt("think_ms", 700)

    // what to draw
    val showEval = p.getBoolean("show_eval", true)
    val showThreat = p.getBoolean("show_threat", true)
    val showLine = p.getBoolean("show_line", true)
    val style = p.getString("arrow_style", "neon") ?: "neon"   // neon, solid, minimal
    val anim = p.getBoolean("anim", true)
    val animSpeed = p.getInt("anim_speed", 100) / 100f          // 0.5 .. 2
    val arrowSize = p.getInt("arrow_size", 100) / 100f          // 0.6 .. 1.6
    val cardSize = p.getInt("card_size", 100) / 100f            // 0.7 .. 1.4
    val opacity = p.getInt("opacity", 95) / 100f                // 0.3 .. 1
    val theme = p.getString("theme", "wallpaper") ?: "wallpaper"

    // the side bar
    val sidebar = p.getBoolean("sidebar", true)
    val maiaTemp: Float get() = maiaHuman / 100f
    val handleRight = p.getString("handle_side", "right") != "left"
    val handleY = p.getFloat("handle_y", 0.42f)
    val savesPerGame = p.getInt("saves", 3)                     // -1 = unlimited
    val handleMode = p.getString("handle_mode", "bar") ?: "bar"   // bar (screen edge) or circle (drag anywhere)
    val handleSize = p.getInt("handle_size", 100) / 100f          // 0.7 .. 1.8
    val handleIdle = p.getInt("handle_idle", 15) / 100f           // how visible it is when you don't touch it, 0 .. 1
    val circleX = p.getFloat("circle_x", 0.86f)
    val circleY = p.getFloat("circle_y", 0.3f)

    companion object {
        fun prefs(ctx: Context): SharedPreferences {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            // version 1.2 picks your side by itself: switch everybody to auto once (they can pick a side again)
            if (!p.getBoolean("color_auto_v12", false)) {
                p.edit().putString("color", "auto").putBoolean("color_auto_v12", true).apply()
            }
            return p
        }
        fun load(ctx: Context) = Cfg(prefs(ctx))

        /** keys that only change the look: no need to think again when they change */
        val LOOK_KEYS = setOf("arrow_style", "anim", "anim_speed", "arrow_size", "card_size", "opacity", "theme",
            "handle_side", "handle_y", "sidebar", "handle_mode", "handle_size", "handle_idle", "circle_x", "circle_y",
            "show_labels", "premove", "review_rating", "review_ms")
    }
}
