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
        val (scroll, col) = ui.screen(this, "chess helper", "reads the board on your screen and shows the best moves")
        root = col

        // ---- start / stop
        root.addView(ui.bigButton("start") { start() }, ui.full(top = 18f))
        var g = ui.group(root, null, "the helper needs to draw over other apps once. stop it here, from the side bar or the notification")
        g.add(ui.navRow("allow draw over other apps", if (Settings.canDrawOverlays(this)) "on" else "off") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        g.add(ui.buttonRow("stop", ui.red) {
            stopService(Intent(this, ChessService::class.java))
            toast("stopped")
        })

        // ---- game
        g = ui.group(root, "game", "auto: your side is the one at the bottom of the board, so it works on any app with no switching")
        g.add(ui.segRow("i play", listOf("auto" to "auto", "white" to "white", "black" to "black"), c.colorMode) { v -> save { it.putString("color", v) } })
        g.add(ui.toggleRow("my turn when i press start", c.myTurnAtStart) { on -> save { it.putBoolean("my_turn", on) } })

        // ---- engine
        g = ui.group(root, "engine", if (c.useMaia) "maia3 runs on your pc, see the readme. if the pc can't be reached the app uses stockfish for that move" else null)
        g.add(ui.segRow(null, listOf("stockfish" to "stockfish (phone)", "maia3" to "maia3 (pc)"), if (c.useMaia) "maia3" else "stockfish") { v ->
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
        } else {
            maiaHostBox = EditText(this).apply { setText(c.maiaHost) }
            maiaModelBox = EditText(this).apply { setText(c.maiaModel) }
            g.add(ui.sliderRow("strength", UciEngine.MIN_ELO, UciEngine.MAX_ELO, 10, c.elo,
                { if (it >= UciEngine.MAX_ELO) "full power" else "elo $it" }) { v -> save { it.putInt("elo2", v) } })
        }
        g.add(ui.sliderRow("think time", 300, 2000, 100, c.thinkMs, { "$it ms" }) { v -> save { it.putInt("think_ms", v) } })

        // ---- on the board
        g = ui.group(root, "on the board", "dark green = best, light green = decent, yellow = ok, red skull = blunder")
        g.add(ui.segRow("moves shown", (1..4).map { it.toString() to it.toString() }, c.nMoves.toString()) { v -> save { it.putInt("n_moves", v.toInt()) } })
        g.add(ui.toggleRow("eval bar", c.showEval) { on -> save { it.putBoolean("show_eval", on) } })
        g.add(ui.toggleRow("threat", c.showThreat, "what the opponent wants to do") { on -> save { it.putBoolean("show_threat", on) } })
        g.add(ui.toggleRow("best line", c.showLine, "the next few moves of the best line") { on -> save { it.putBoolean("show_line", on) } })
        g.add(ui.toggleRow("live accuracy", c.showAcc) { on -> save { it.putBoolean("show_acc", on) } })

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
