package com.example.chesshelper

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** maia3 running on your pc: tools/maia3_server.py answers with the move. host looks like 192.168.1.20:8765 */
class RemoteEngine(
    private val host: String, private val model: String, private val elo: Int,
    private val oppElo: Int = 0,          // rating of the bot you play against (0 = same as elo)
    private val temperature: Float = 0f   // 0 = maia's most likely move, about 1 = human randomness
) : MoveEngine, MoveLister {
    /** the n moves maia3 thinks a human of that rating plays most often, most likely first */
    override fun topMoves(fen: String, n: Int): List<String> = movesFor(fen, n, elo, oppElo, temperature, "you")

    /**
     * the opponent's most likely move: maia3 playing as the bot (its rating, plain most likely move).
     * used to guess the bot's next move while it is thinking
     */
    fun opponentMove(fen: String, botElo: Int): String? =
        movesFor(fen, 1, botElo, elo, 0f, "bot").firstOrNull()

    /**
     * the bot's n most likely moves (maia3 playing as the bot), most likely first, with maia3's odds (0..1) when the
     * pc server sends them (an older server doesn't: the odds are null then)
     */
    fun opponentGuesses(fen: String, botElo: Int, n: Int): List<Pair<String, Float?>> {
        val json = post(fen, n, botElo, elo, 0f, "bot")
        val arr = json.optJSONArray("moves") ?: return emptyList()
        val probs = json.optJSONArray("probs")
        val out = ArrayList<Pair<String, Float?>>()
        for (i in 0 until arr.length()) {
            val p = if (probs != null && i < probs.length()) probs.optDouble(i, -1.0).takeIf { it >= 0.0 }?.toFloat() else null
            out.add(Pair(arr.getString(i), p))
        }
        return out
    }

    private fun movesFor(fen: String, n: Int, self: Int, oppo: Int, temp: Float, who: String): List<String> {
        val json = post(fen, n, self, oppo, temp, who)
        val arr = json.optJSONArray("moves")
        val out = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) out.add(arr.getString(i))
        if (out.isEmpty()) json.optString("move").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    override fun bestMove(fen: String, ms: Int): Suggestion? {
        val move = post(fen, 1, elo, oppElo, temperature, "you").optString("move")
        return if (move.isEmpty() || move == "(none)") null else Suggestion(move, "maia3 $elo")
    }

    private fun post(fen: String, n: Int, self: Int, oppo: Int, temp: Float, who: String): JSONObject {
        val conn = URL("http://$host/move").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 60000 // the first move can take a while, the server loads the model then
            conn.setRequestProperty("content-type", "application/json")
            val body = JSONObject().put("fen", fen).put("model", model).put("elo", self).put("n", n)
                .put("oppo_elo", oppo).put("temperature", temp.toDouble()).put("for", who)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: throw IOException("server error $code"))
            val text = stream.bufferedReader().use { it.readText() }
            val json = try { JSONObject(text) } catch (e: org.json.JSONException) { throw IOException("the pc answered something odd") }
            if (code !in 200..299) throw IOException(json.optString("error", "server error $code"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}
}
