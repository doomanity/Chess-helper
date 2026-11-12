package com.example.chesshelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** settings screen: start / stop, side, engine, look, side bar, review, api key. ios settings style */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var maiaHostBox: EditText
    private lateinit var maiaModelBox: EditText
    private lateinit var ui: Ios

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Cfg.prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        build()
    }

    private fun save(edit: (SharedPreferences.Editor) -> Unit) {
        val e = prefs.edit()
        edit(e)
        e.apply()
    }

    /** builds the whole screen (again, when the engine or side bar mode changes) */
    private fun build() {
        val c = Cfg.load(this)
        ui = Ios(this)
        val (scroll, col) = ui.screen(this, "Chess helper", "reads the board on your screen and shows the best moves")
        root = col

        // ---- the hero: your last game's board, what the helper is doing, start / stop
        root.addView(Hero.build(this, ui, c, ChessService.active, { start() }, {
            stopService(Intent(this, ChessService::class.java))
            ChessService.active = false
            toast("stopped")
            root.post { rebuild() }
        }), ui.full(top = 16f))
        var g = ui.group(root, null, "the helper draws over your chess app, android asks for this once. you can also stop it from the side bar or the notification")
        g.add(ui.navRow("allow draw over other apps", if (Settings.canDrawOverlays(this)) "on" else "off") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        // ---- game
        g = ui.group(root, "game", "auto: your side is the one at the bottom of the board, so it works on any app with no switching")
        g.add(ui.segRow("i play", listOf("auto" to "auto", "white" to "white", "black" to "black"), c.colorMode) { v -> save { it.putString("color", v) } })
        g.add(ui.toggleRow("my turn when i press start", c.myTurnAtStart) { on -> save { it.putBoolean("my_turn", on) } })

        // ---- engine
        val engineNote = when {
            c.useMaia -> "maia3 runs on your pc, see the readme. if the pc can't be reached the app uses stockfish for that move"
            c.useIllusion -> "illusion is your own engine, running on the phone. its pick is tagged ✦ on the card (⚑ = a trap). stockfish still grades every move"
            else -> null
        }
        g = ui.group(root, "engine", engineNote)
        val engineNow = if (c.useMaia) "maia3" else if (c.useIllusion) "illusion" else "stockfish"
        g.add(ui.segRow(null, listOf("stockfish" to "stockfish", "maia3" to "maia3 (pc)", "illusion" to "illusion"), engineNow) { v ->
            storeText()
            save { it.putString("engine", v) }
            root.post { rebuild() }
        })
        if (c.useMaia) {
            val (hostRow, hostBox) = ui.fieldRow("pc address (tools/maia3_server.py prints it)", c.maiaHost, "192.168.1.20:8765")
            maiaHostBox = hostBox
            g.add(hostRow)
            val (modelRow, mBox) = ui.fieldRow("maia3 model: 5m, 23m or 79m", c.maiaModel, "23m")
            maiaModelBox = mBox
            g.add(modelRow)
            g.add(ui.sliderRow("plays like", 600, 2600, 100, c.maiaElo, { "$it" }) { v -> save { it.putInt("maia_elo", v) } })
            g.add(ui.sliderRow("human-ness", 0, 100, 5, c.maiaHuman, { if (it == 0) "top pick" else "$it%" }) { v -> save { it.putInt("maia_human", v) } })
        } else if (c.useIllusion) {
            maiaHostBox = EditText(this).apply { setText(c.maiaHost) }
            maiaModelBox = EditText(this).apply { setText(c.maiaModel) }
            g.add(ui.toggleRow("human mode", c.illusionHuman, "attacks, smart trades, anti-draw, sacrifices and traps. off = pure engine") { on ->
                save { it.putBoolean("illusion_human", on) }
                storeText(); root.post { rebuild() }
            })
            g.add(ui.sliderRow("opponent rating", 600, 2600, 100, c.maiaElo, { "$it" }) { v -> save { it.putInt("maia_elo", v) } })
            if (c.illusionHuman) {
                g.add(ui.sliderRow("aggression", 0, 100, 5, c.illusionAggression, { "$it" }) { v -> save { it.putInt("illusion_aggression", v) } })
                g.add(ui.sliderRow("contempt", 0, 200, 5, c.illusionContempt, { if (it == 0) "takes draws" else "$it" }) { v -> save { it.putInt("illusion_contempt", v) } })
                g.add(ui.sliderRow("swindle tolerance", 0, 300, 10, c.illusionTolerance, { if (it == 0) "no traps" else "$it" }) { v -> save { it.putInt("illusion_tolerance", v) } })
                g.add(ui.sliderRow("brilliant hunt", 0, 100, 5, c.illusionHunt, { if (it == 0) "only mates" else "$it" }) { v -> save { it.putInt("illusion_hunt", v) } })
            }
        } else {
            maiaHostBox = EditText(this).apply { setText(c.maiaHost) }
            maiaModelBox = EditText(this).apply { setText(c.maiaModel) }
            g.add(ui.sliderRow("strength", UciEngine.MIN_ELO, UciEngine.MAX_ELO, 10, c.elo,
                { if (it >= UciEngine.MAX_ELO) "full power" else "elo $it" }) { v -> save { it.putInt("elo2", v) } })
        }
        g.add(ui.sliderRow("think time", 300, 2000, 100, c.thinkMs, { "$it ms" }) { v -> save { it.putInt("think_ms", v) } })

        // ---- thinking ahead and remembering
        val memCount = try {
            java.io.File(filesDir, "advice_memory.tsv").let { f -> if (f.exists()) f.useLines { it.count() } else 0 }
        } catch (_: Exception) { 0 }
        g = ui.group(root, "think ahead", "in spare time the helper follows the likely game 10 half moves ahead (the bot's top 2 moves at each turn, your best answer), while the bot thinks and while you think. when the game follows it, your move shows at once. remembered positions stay on the phone, so openings the bots repeat are instant next game too. uses more battery")
        g.add(ui.toggleRow("think ahead", c.thinkAhead, "deep line, 10 half moves") { on -> save { it.putBoolean("think_ahead", on) } })
        g.add(ui.toggleRow("remember positions", c.remember, "$memCount stored on the phone") { on -> save { it.putBoolean("remember", on) } })
        g.add(ui.buttonRow("forget remembered positions", ui.red) {
            save { it.putLong("forget_memory", System.currentTimeMillis()) }
            try { java.io.File(filesDir, "advice_memory.tsv").delete() } catch (_: Exception) {}
            toast("forgot all remembered positions")
            root.post { rebuild() }
        })

        // ---- engine control
        g = ui.group(root, "engine control", "the floating play button lets the engine play your moves by tapping the board. only against computer bots: chess sites ban accounts that do this against people. if android greys the switch out, open app info > ⋮ > allow restricted settings first")
        g.add(ui.navRow("allow engine control", if (TapService.instance != null) "on" else "off") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
        })
        g.add(ui.toggleRow("show the play button", c.controlButton) { on -> save { it.putBoolean("control_button", on) } })
        g.add(ui.sliderRow("think time", 100, 1000, 50, c.autoThinkMs, { "$it ms" }) { v -> save { it.putInt("auto_think_ms", v) } })
        g.add(ui.sliderRow("wait before tapping", 0, 600, 25, c.autoPauseMs, { if (it == 0) "none" else "$it ms" }) { v -> save { it.putInt("auto_pause_ms", v) } })

        // ---- illusion's extra jobs (they work with any engine above)
        g = ui.group(root, "illusion", "your engine runs on the phone with its hand made eval for now (no trained net yet)")
        g.add(ui.toggleRow("brilliant hunter", c.illusionBrilliant, "blue !! arrow when a brilliant move is on the board (illusion or stockfish)") { on -> save { it.putBoolean("illusion_brilliant", on) } })
        g.add(ui.toggleRow("guess the bot with illusion", c.illusionGuess, "its move is the first guess when maia3 is off") { on -> save { it.putBoolean("illusion_guess", on) } })

        // ---- on the board
        g = ui.group(root, "on the board", "dark green = best, light green = decent, yellow = ok, red skull = blunder")
        g.add(ui.segRow("moves shown", (1..4).map { it.toString() to it.toString() }, c.nMoves.toString()) { v -> save { it.putInt("n_moves", v.toInt()) } })
        g.add(ui.toggleRow("eval bar", c.showEval) { on -> save { it.putBoolean("show_eval", on) } })
        g.add(ui.toggleRow("threat", c.showThreat, "what the opponent wants to do") { on -> save { it.putBoolean("show_threat", on) } })
        g.add(ui.toggleRow("best line", c.showLine, "the next few moves of the best line") { on -> save { it.putBoolean("show_line", on) } })
        g.add(ui.toggleRow("live accuracy", c.showAcc) { on -> save { it.putBoolean("show_acc", on) } })
        g.add(ui.toggleRow("blunder alert", c.blunderSquares, "dark red skull on squares where any move of yours would blunder") { on -> save { it.putBoolean("blunder_squares", on) } })
        g.add(ui.toggleRow("hanging pieces", c.hanging, "yellow ring on your pieces that can be taken for free") { on -> save { it.putBoolean("hanging", on) } })
        g.add(ui.toggleRow("safe squares", c.safeSquares, "touch a piece: green = safe, red = it gets taken there") { on -> save { it.putBoolean("safe_squares", on) } })
        g.add(ui.toggleRow("critical moments", c.critical, "the card lights up when only one move works or the bot slipped") { on -> save { it.putBoolean("critical", on) } })
        g.add(ui.toggleRow("bot's plan", c.botPlan, "its next moves if you did nothing, numbered in red") { on -> save { it.putBoolean("bot_plan", on) } })

        // ---- while the bot thinks
        g = ui.group(root, "while the bot thinks", "the premove is your answer to the bot's likely move, ready before it plays")
        g.add(ui.toggleRow("guess the bot's move", c.predict) { on -> save { it.putBoolean("predict", on) } })
        g.add(ui.toggleRow("premove", c.premove) { on -> save { it.putBoolean("premove", on) } })
        g.add(ui.toggleRow("move labels", c.showLabels, "brilliant, great, blunder... on every move played") { on -> save { it.putBoolean("show_labels", on) } })

        // ---- look
        g = ui.group(root, "look")
        g.add(ui.segRow("arrow style", listOf("neon" to "neon", "solid" to "solid", "minimal" to "minimal"), c.style) { v -> save { it.putString("arrow_style", v) } })
        g.add(ui.segRow("color", Theme.PRESETS.keys.map { it to it }, c.theme) { v -> save { it.putString("theme", v) } })
        g.add(ui.toggleRow("animations", c.anim) { on -> save { it.putBoolean("anim", on) } })
        g.add(ui.sliderRow("animation speed", 50, 200, 10, (c.animSpeed * 100).toInt(), { "$it%" }) { v -> save { it.putInt("anim_speed", v) } })
        g.add(ui.sliderRow("arrow size", 60, 160, 5, (c.arrowSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("arrow_size", v) } })
        g.add(ui.sliderRow("card size", 70, 140, 5, (c.cardSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("card_size", v) } })
        g.add(ui.sliderRow("opacity", 30, 100, 5, (c.opacity * 100).toInt(), { "$it%" }) { v -> save { it.putInt("opacity", v) } })

        // ---- side bar
        g = ui.group(root, "side bar", if (c.handleMode == "circle") "a round button you can drag anywhere. tap it to open the quick menu"
            else "touch the screen edge, then tap or slide inward to open the quick menu. drag it up or down to move it")
        g.add(ui.toggleRow("show the handle while playing", c.sidebar) { on -> save { it.putBoolean("sidebar", on) } })
        g.add(ui.segRow("handle", listOf("bar" to "edge bar", "circle" to "circle"), c.handleMode) { v ->
            storeText()
            save { it.putString("handle_mode", v) }
            root.post { rebuild() }
        })
        if (c.handleMode != "circle") {
            g.add(ui.segRow("edge", listOf("left" to "left", "right" to "right"), if (c.handleRight) "right" else "left") { v -> save { it.putString("handle_side", v) } })
        }
        g.add(ui.sliderRow("handle size", 70, 180, 10, (c.handleSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("handle_size", v) } })
        g.add(ui.sliderRow("visible when idle", 0, 100, 5, (c.handleIdle * 100).toInt(), { "$it%" }) { v -> save { it.putInt("handle_idle", v) } })
        g.add(ui.segRow("max power saves per game", listOf("1" to "1", "3" to "3", "5" to "5", "-1" to "∞"), c.savesPerGame.toString()) { v -> save { it.putInt("saves", v.toInt()) } })

        // ---- game review
        g = ui.group(root, "game review", "labels every move like chess.com for the game you just played or any pasted pgn. analysis only, it never picks a move")
        g.add(ui.navRow("open game review", tint = ui.blue) { startActivity(Intent(this, ReviewActivity::class.java)) })
        g.add(ui.navRow("match history", "${GameHistory.all(this).size} games", tint = ui.blue) { startActivity(Intent(this, HistoryActivity::class.java)) })

        // ---- claude
        g = ui.group(root, "claude", "only used once when you start in the middle of a game")
        val (keyRow, kBox) = ui.fieldRow("api key", c.apiKey, "sk-ant-...", secret = true)
        keyBox = kBox
        g.add(keyRow)
        val (modelRow, mdl) = ui.fieldRow("model", c.model, "claude-haiku-5-5")
        modelBox = mdl
        g.add(modelRow)
        for (box in listOf(keyBox, modelBox, maiaHostBox, maiaModelBox)) box.setOnFocusChangeListener { _, has -> if (!has) storeText() }

        val ver = ui.text("chess helper ${versionText()}", 13f, ui.label3)
        ver.gravity = android.view.Gravity.CENTER
        root.addView(ver, ui.full(top = 28f))

        setContentView(scroll)
    }

    private fun versionText(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "version ${info.versionName} (build ${info.longVersionCode})"
    } catch (e: Exception) {
        ""
    }

    private fun rebuild() {
        val y = (root.parent as? ScrollView)?.scrollY ?: 0
        build()
        (root.parent as? ScrollView)?.post { (root.parent as? ScrollView)?.scrollTo(0, y) }
    }

    override fun onResume() {
        super.onResume()
        // the "draw over other apps" row shows on / off, which may have changed in the settings app
        if (::root.isInitialized) rebuild()
    }

    /** text boxes are saved when leaving them out would lose what was typed */
    private fun storeText() {
        save {
            it.putString("api_key", keyBox.text.toString().trim())
            it.putString("model", modelBox.text.toString().trim().ifEmpty { "claude-haiku-5-5" })
            it.putString("maia_host", maiaHostBox.text.toString().trim().removePrefix("http://").trimEnd('/'))
            it.putString("maia_model", maiaModelBox.text.toString().trim().ifEmpty { "23m" })
        }
    }

    // ------------------------------------------------------------------ start

    private fun start() {
        storeText()
        val c = Cfg.load(this)
        if (c.useMaia && c.maiaHost.isBlank()) {
            toast("type the maia3 pc address first (run tools/maia3_server.py on your pc)")
            return
        }
        if (c.apiKey.isBlank()) {
            toast("no api key: works when you start from the normal start position")
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("overlay check says off, trying anyway")
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ChessService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            toast("running. open your chess app now")
            moveTaskToBack(true)
        } else if (requestCode == 100) {
            toast("screen capture was not allowed")
        }
    }

    override fun onPause() {
        storeText()
        super.onPause()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
