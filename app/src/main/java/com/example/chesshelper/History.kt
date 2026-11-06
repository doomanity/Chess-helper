package com.example.chesshelper

import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** one of your standout moves in a game: a brilliant or great one. ply = which move of the game (1 = the first) */
class Highlight(val ply: Int, val san: String, val label: String)

/**
 * one game in the match history. result: "win", "loss", "draw" or "" (unknown: the board can't show a resign or a
 * loss on time). manual: the result you set yourself (wins over the one read from the board)
 */
class SavedGame(
    val id: Long,
    val startFen: String,
    val moves: List<String>,
    val meWhite: Boolean,
    val result: String,
    val how: String,            // "checkmate", "stalemate", ... or ""
    val accMe: Int?,
    val accOpp: Int?,
    val opening: String?,
    val updated: Long,
    val engine: String,
    val manual: String = "",
    val labels: Map<String, Int> = emptyMap(),   // how many of each label your moves got (Label names, like BRILLIANT)
    val highlights: List<Highlight> = emptyList(), // your brilliant and great moves
    val reviewed: Boolean = false                  // labels from a full review (exact), not from the live labels
) {
    /** the result to show: yours if you set one, else the one the board showed */
    val shownResult: String get() = manual.ifEmpty { result }

    val brilliants: Int get() = labels["BRILLIANT"] ?: 0
    val greats: Int get() = labels["GREAT"] ?: 0

    /** the move number of a ply (white's and black's move share a number) */
    fun moveNumber(ply: Int): Int {
        val blackFirst = startFen.split(" ").getOrNull(1) == "b"
        return if (blackFirst) (ply + 2) / 2 else (ply + 1) / 2
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("start", startFen).put("moves", moves.joinToString(" ")).put("white", meWhite)
        .put("result", result).put("how", how).put("accMe", accMe ?: -1).put("accOpp", accOpp ?: -1)
        .put("opening", opening ?: "").put("updated", updated).put("engine", engine).put("manual", manual)
        .put("labels", JSONObject().also { j -> for ((k, v) in labels) j.put(k, v) })
        .put("highlights", highlights.joinToString(";") { "${it.ply}|${it.san}|${it.label}" })
        .put("reviewed", reviewed)

    fun with(manual: String) = SavedGame(id, startFen, moves, meWhite, result, how, accMe, accOpp, opening, updated, engine,
        manual, labels, highlights, reviewed)

    fun withLabels(labels: Map<String, Int>, highlights: List<Highlight>, reviewed: Boolean) = SavedGame(id, startFen, moves,
        meWhite, result, how, accMe, accOpp, opening, updated, engine, manual, labels, highlights, reviewed)

    companion object {
        fun fromJson(o: JSONObject) = SavedGame(
            id = o.getLong("id"),
            startFen = o.getString("start"),
            moves = o.optString("moves").split(" ").filter { it.isNotEmpty() },
            meWhite = o.optBoolean("white", true),
            result = o.optString("result"),
            how = o.optString("how"),
            accMe = o.optInt("accMe", -1).takeIf { it >= 0 },
            accOpp = o.optInt("accOpp", -1).takeIf { it >= 0 },
            opening = o.optString("opening").ifEmpty { null },
            updated = o.optLong("updated"),
            engine = o.optString("engine"),
            manual = o.optString("manual"),
            labels = o.optJSONObject("labels")?.let { j ->
                val m = HashMap<String, Int>()
                val it = j.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = j.optInt(k)
                }
                m
            } ?: emptyMap(),
            highlights = o.optString("highlights").split(";").mapNotNull { h ->
                val p = h.split("|")
                if (p.size == 3) p[0].toIntOrNull()?.let { Highlight(it, p[1], p[2]) } else null
            },
            reviewed = o.optBoolean("reviewed", false)
        )
    }
}

/** the match history, kept in a small file on the phone. newest game first */
object GameHistory {
    private const val FILE = "history.json"
    private const val MAX = 400

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun all(ctx: Context): List<SavedGame> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                try { SavedGame.fromJson(arr.getJSONObject(i)) } catch (e: Exception) { null }
            }.sortedByDescending { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(ctx: Context, id: Long): SavedGame? = all(ctx).firstOrNull { it.id == id }

    /** adds the game, or updates it (a result you set yourself is kept, and so are labels from a full review) */
    @Synchronized
    fun put(ctx: Context, g: SavedGame) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == g.id }
        if (i >= 0) {
            val old = list[i]
            var n = g.with(old.manual)
            if (old.reviewed) n = n.withLabels(old.labels, old.highlights, true)
            list[i] = n
        } else {
            list.add(0, g)
        }
        write(ctx, list)
    }

    /** the labels of your moves in a game. live labels never replace the exact ones from a full review */
    @Synchronized
    fun setLabels(ctx: Context, id: Long, labels: Map<String, Int>, highlights: List<Highlight>, reviewed: Boolean) {
        val list = all(ctx)
        val old = list.firstOrNull { it.id == id } ?: return
        if (old.reviewed && !reviewed) return
        write(ctx, list.map { if (it.id == id) it.withLabels(labels, highlights, reviewed) else it })
    }

    @Synchronized
    fun setResult(ctx: Context, id: Long, result: String) {
        write(ctx, all(ctx).map { if (it.id == id) it.with(result) else it })
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    private fun write(ctx: Context, list: List<SavedGame>) {
        val arr = JSONArray()
        for (g in list.sortedByDescending { it.id }.take(MAX)) arr.put(g.toJson())
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** the result the board shows (checkmate, stalemate...), from your side. ("", "") while the game goes on */
    fun resultOf(b: Board, meWhite: Boolean): Pair<String, String> {
        return try {
            when {
                b.isMated -> {
                    val loserWhite = b.sideToMove == Side.WHITE
                    Pair(if (loserWhite == meWhite) "loss" else "win", "checkmate")
                }
                b.isStaleMate -> Pair("draw", "stalemate")
                b.isInsufficientMaterial -> Pair("draw", "not enough pieces to mate")
                else -> Pair("", "")
            }
        } catch (e: Exception) {
            Pair("", "")
        }
    }

    /** the name of the opening (the last book position the game went through), or null */
    fun openingOf(startFen: String, moves: List<String>, book: OpeningBook?): String? {
        if (book == null || !startFen.startsWith("$STANDARD_PLACEMENT w")) return null
        return try {
            val b = Board()
            b.loadFromFen(startFen)
            var name: String? = null
            for (m in moves) {
                val mv = b.legalMoves().firstOrNull { it.toString() == m } ?: break
                b.doMove(mv)
                name = book.name(b.fen) ?: break
            }
            name
        } catch (e: Exception) {
            null
        }
    }
}
