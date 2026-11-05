package com.example.chesshelper

/** what the AI told us about the screenshot: 8 rows of pieces as seen on screen, which side is at the bottom, where the board is */
class AiRead(val grid: List<String>, val bottomWhite: Boolean?, val box: FloatArray?)

/** turns the AI's text reply into an AiRead. kept free of android classes so it can be tested on a computer */
object HaikuParse {
    @Volatile var lastDebug: String = ""

    /**
     * which side is at the bottom of the screen, from a board read as 8 screen rows (top row first). counting where
     * the white pieces are goes wrong in the middle of a game (an attack puts them in the other half), so a few clues
     * vote: where the pawns are (they only move forward, the strongest clue), where the kings are, what the AI read
     * from the board's coordinates, and which half has more white pieces. null if there is nothing to go on
     */
    fun whiteBottom(grid: List<String>, claim: Boolean?): Boolean? {
        if (grid.size != 8) return claim
        var score = 0.0
        fun rowsOf(ch: Char) = grid.withIndex().flatMap { (r, row) -> row.withIndex().filter { it.value == ch }.map { r } }
        val wp = rowsOf('P')
        val bp = rowsOf('p')
        if (wp.isNotEmpty() && bp.isNotEmpty()) {
            val d = wp.average() - bp.average() // > 0: white pawns lower on the screen
            score += 3.0 * d.coerceIn(-1.5, 1.5) / 1.5
        } else {
            // only one side has pawns: where they stand compared to the middle of the board
            if (wp.isNotEmpty()) score += 0.75 * ((wp.average() - 3.5) / 3.5).coerceIn(-1.0, 1.0)
            if (bp.isNotEmpty()) score -= 0.75 * ((bp.average() - 3.5) / 3.5).coerceIn(-1.0, 1.0)
        }
        val wk = rowsOf('K').firstOrNull()
        val bk = rowsOf('k').firstOrNull()
        if (wk != null && bk != null && wk != bk) score += if (wk > bk) 1.0 else -1.0
        if (claim != null) score += if (claim) 2.5 else -2.5
        var top = 0
        var bottom = 0
        for ((r, row) in grid.withIndex()) for (ch in row) if (ch.isUpperCase()) { if (r >= 4) bottom++ else top++ }
        if (top != bottom) score += if (bottom > top) 0.5 else -0.5
        return if (score > 0.0) true else if (score < 0.0) false else claim
    }

    fun parse(reply: String): AiRead? {
        lastDebug = ""
        val rowRegex = Regex("^[prnbqkPRNBQK.]{8}$")
        val rows = reply.lines().map { it.trim().trim('`').trim() }.filter { rowRegex.matches(it) }
        if (rows.size < 8) {
            lastDebug = "no 8-row board in the reply: " + reply.trim().takeLast(140)
            return null
        }
        val grid = rows.takeLast(8)

        val bottom = Regex("BOTTOM:[\\s*`]*(white|black)", RegexOption.IGNORE_CASE).find(reply)
            ?.groupValues?.get(1)?.lowercase()
        val bottomWhite: Boolean? = when (bottom) {
            "white" -> true
            "black" -> false
            else -> null
        }

        val m = Regex("BOX:[\\s*`\\[]*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)").find(reply)
        var box: FloatArray? = null
        if (m != null) {
            val nums = m.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
            if (nums.size == 4 && nums[2] > nums[0] && nums[3] > nums[1] && nums.all { it in 0f..1.02f }) {
                box = nums.toFloatArray()
            }
        }
        return AiRead(grid, bottomWhite, box)
    }
}
