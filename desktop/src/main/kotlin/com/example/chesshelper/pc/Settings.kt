package com.example.chesshelper.pc

import java.io.File
import java.util.Properties

/** where the pc app keeps its settings and the learned piece shapes (%APPDATA%\ChessHelper on windows) */
object Store {
    val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "ChessHelper").also { it.mkdirs() }
    val shapes = File(dir, "piece_shapes.txt")
}

/** the pc app's settings, saved as they change */
class Settings {
    var engine = "stockfish"        // stockfish or illusion (your moves)
    var lines = 3                   // moves shown, 1..4
    var colorMode = "auto"          // auto (the side at the bottom), white, black
    var guess = true                // guess the bot's move on its turn, with premove answers
    var guessWith = "stockfish"     // stockfish or maia3
    var maiaHost = "127.0.0.1:8765" // tools/maia3_server.py on this pc
    var botElo = 1500               // the bot's rating (maia3 plays as it)
    var humanMode = true            // illusion's personality (traps, sacrifices) for its pick
    var oppElo = 1500               // illusion: the opponent's rating its traps aim at
    var aggression = 50
    var contempt = 25
    var tolerance = 60
    var hunt = 30
    var illusionMs = 400            // illusion's think for its pick in human mode (it needs a clock for traps)

    private val file = File(Store.dir, "settings.properties")

    fun load() {
        if (!file.exists()) return
        val p = Properties()
        try { file.inputStream().use { p.load(it) } } catch (_: Exception) { return }
        engine = p.getProperty("engine", engine)
        lines = p.getProperty("lines")?.toIntOrNull()?.coerceIn(1, 4) ?: lines
        colorMode = p.getProperty("colorMode", colorMode)
        guess = p.getProperty("guess")?.toBoolean() ?: guess
        guessWith = p.getProperty("guessWith", guessWith)
        maiaHost = p.getProperty("maiaHost", maiaHost)
        botElo = p.getProperty("botElo")?.toIntOrNull() ?: botElo
        humanMode = p.getProperty("humanMode")?.toBoolean() ?: humanMode
        oppElo = p.getProperty("oppElo")?.toIntOrNull() ?: oppElo
        aggression = p.getProperty("aggression")?.toIntOrNull() ?: aggression
        contempt = p.getProperty("contempt")?.toIntOrNull() ?: contempt
        tolerance = p.getProperty("tolerance")?.toIntOrNull() ?: tolerance
        hunt = p.getProperty("hunt")?.toIntOrNull() ?: hunt
        illusionMs = p.getProperty("illusionMs")?.toIntOrNull() ?: illusionMs
    }

    fun save() {
        val p = Properties()
        p["engine"] = engine; p["lines"] = "$lines"; p["colorMode"] = colorMode; p["guess"] = "$guess"
        p["guessWith"] = guessWith; p["maiaHost"] = maiaHost; p["botElo"] = "$botElo"; p["humanMode"] = "$humanMode"
        p["oppElo"] = "$oppElo"; p["aggression"] = "$aggression"; p["contempt"] = "$contempt"
        p["tolerance"] = "$tolerance"; p["hunt"] = "$hunt"; p["illusionMs"] = "$illusionMs"
        try { file.outputStream().use { p.store(it, "chess helper") } } catch (_: Exception) {}
    }
}
