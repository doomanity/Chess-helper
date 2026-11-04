package com.example.chesshelper

import android.content.Context

/**
 * the game the helper is following right now, saved after every move so the review screen can open it
 * (while the game is still going, or later). it is only a record: the review never touches the live game.
 */
object GameLog {
    private const val FILE = "game_log"

    fun save(ctx: Context, startFen: String, moves: List<String>) {
        if (startFen.isEmpty()) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("start", startFen)
            .putString("moves", moves.joinToString(" "))
            .apply()
    }

    /** the last game the helper followed, or null if there was none yet */
    fun load(ctx: Context): ReviewGame? {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val start = p.getString("start", null) ?: return null
        val moves = (p.getString("moves", "") ?: "").split(" ").filter { it.isNotEmpty() }
        return ReviewGame(start, moves)
    }
}
