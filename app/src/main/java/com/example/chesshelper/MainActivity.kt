package com.example.chesshelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** settings screen: api key, side, engine, look, side bar, start / stop. neon style in the wallpaper's color */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var maiaHostBox: EditText
    private lateinit var maiaModelBox: EditText
    private var accent = Color.rgb(0, 229, 255)
    private val d get() = resources.displayMetrics.density

    private fun dp(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Cfg.prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        window.statusBarColor = Color.rgb(10, 13, 22)
        window.navigationBarColor = Color.rgb(10, 13, 22)
        build()
    }

    private fun save(edit: (SharedPreferences.Editor) -> Unit) {
        val e = prefs.edit()
        edit(e)
        e.apply()
    }

    /** builds the whole screen (again, when the theme or engine changes) */
    private fun build() {
        val c = Cfg.load(this)
        accent = Theme.accent(this, c.theme)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18f), dp(26f), dp(18f), dp(30f))

        val title = TextView(this)
        title.text = "chess helper"
        title.textSize = 32f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.setShadowLayer(26f, 0f, 0f, accent)
        root.addView(title)
        val sub = TextView(this)
        sub.text = "tracks the board on your screen and shows the best moves"
        sub.textSize = 13f
        sub.setTextColor(Theme.withAlpha(accent, 220))
        sub.setPadding(0, 0, 0, dp(8f))
        root.addView(sub)

        // ---- haiku
        var card = newCard("claude api key")
        label(card, "only used once if you start in the middle of a game")
        keyBox = input(card, c.apiKey, "sk-ant-...", secret = true)
        label(card, "model")
        modelBox = input(card, c.model, "claude-haiku-5-5")

        // ---- game
        card = newCard("game")
        label(card, "i play")
        card.addView(seg(listOf("white" to "white", "black" to "black"), if (c.iAmWhite) "white" else "black") { v -> save { it.putString("color", v) } })
        card.addView(chip("it's my turn when i press start", c.myTurnAtStart) { on -> save { it.putBoolean("my_turn", on) } }, full(top = 10f))

        // ---- engine
        card = newCard("engine")
        card.addView(seg(listOf("stockfish" to "stockfish (phone)", "maia3" to "maia3 (pc)"), if (c.useMaia) "maia3" else "stockfish") { v ->
            storeText()
            save { it.putString("engine", v) }
            root.post { rebuild() }
        })
        if (c.useMaia) {
            label(card, "pc address (tools/maia3_server.py prints it)")
            maiaHostBox = input(card, c.maiaHost, "192.168.1.20:8765")
            label(card, "maia3 model: 5m, 23m or 79m")
            maiaModelBox = input(card, c.maiaModel, "23m")
            card.addView(slider(600, 2600, 100, c.maiaElo, { "plays like a human of: $it" }) { v -> save { it.putInt("maia_elo", v) } })
            card.addView(slider(0, 100, 5, c.maiaHuman,
                { if (it == 0) "human-ness: 0% (always maia's top pick, strongest)" else "human-ness: $it% (100% = picks like a real human, mistakes included)" }) { v ->
                save { it.putInt("maia_human", v) }
            })
        } else {
            maiaHostBox = EditText(this).apply { setText(c.maiaHost) }
            maiaModelBox = EditText(this).apply { setText(c.maiaModel) }
            card.addView(slider(UciEngine.MIN_ELO, UciEngine.MAX_ELO, 10, c.elo,
                { if (it >= UciEngine.MAX_ELO) "strength: full power" else "strength: elo $it" }) { v -> save { it.putInt("elo2", v) } })
        }
        card.addView(slider(300, 2000, 100, c.thinkMs, { "think time: ${it} ms" }) { v -> save { it.putInt("think_ms", v) } })

        // ---- what to show
        card = newCard("on the board")
        label(card, "moves shown (dark green best, light green decent, yellow ok, red skull blunder)")
        card.addView(seg((1..4).map { it.toString() to it.toString() }, c.nMoves.toString()) { v -> save { it.putInt("n_moves", v.toInt()) } })
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(chip("eval bar", c.showEval) { on -> save { it.putBoolean("show_eval", on) } }, weight())
        chips.addView(chip("threat", c.showThreat) { on -> save { it.putBoolean("show_threat", on) } }, weight())
        chips.addView(chip("best line", c.showLine) { on -> save { it.putBoolean("show_line", on) } }, weight())
        card.addView(chips, full(top = 10f))
        val chips2 = LinearLayout(this)
        chips2.orientation = LinearLayout.HORIZONTAL
        chips2.addView(chip("guess the bot's move", c.predict) { on -> save { it.putBoolean("predict", on) } }, weight())
        chips2.addView(chip("live accuracy", c.showAcc) { on -> save { it.putBoolean("show_acc", on) } }, weight())
        card.addView(chips2, full(top = 6f))
        val chips3 = LinearLayout(this)
        chips3.orientation = LinearLayout.HORIZONTAL
        chips3.addView(chip("move labels", c.showLabels) { on -> save { it.putBoolean("show_labels", on) } }, weight())
        chips3.addView(chip("premove", c.premove) { on -> save { it.putBoolean("premove", on) } }, weight())
        card.addView(chips3, full(top = 6f))
        label(card, "arrow style")
        card.addView(seg(listOf("neon" to "neon", "solid" to "solid", "minimal" to "minimal"), c.style) { v -> save { it.putString("arrow_style", v) } })
        label(card, "theme")
        card.addView(seg(Theme.PRESETS.keys.map { it to it }, c.theme) { v ->
            storeText()
            save { it.putString("theme", v) }
            root.post { rebuild() }
        })
        card.addView(chip("animations", c.anim) { on -> save { it.putBoolean("anim", on) } }, full(top = 10f))
        card.addView(slider(50, 200, 10, (c.animSpeed * 100).toInt(), { "animation speed: $it%" }) { v -> save { it.putInt("anim_speed", v) } })
        card.addView(slider(60, 160, 5, (c.arrowSize * 100).toInt(), { "arrow size: $it%" }) { v -> save { it.putInt("arrow_size", v) } })
        card.addView(slider(70, 140, 5, (c.cardSize * 100).toInt(), { "card size: $it%" }) { v -> save { it.putInt("card_size", v) } })
        card.addView(slider(30, 100, 5, (c.opacity * 100).toInt(), { "opacity: $it%" }) { v -> save { it.putInt("opacity", v) } })

        // ---- side bar
        card = newCard("side bar")
        card.addView(chip("show the side handle while playing", c.sidebar) { on -> save { it.putBoolean("sidebar", on) } })
        label(card, "edge bar: touch the screen edge, then tap it or slide it inward to open the quick menu. drag it up or down to move it. circle: a round button you can drag anywhere, tap it to open the menu")
        card.addView(seg(listOf("bar" to "edge bar", "circle" to "circle"), c.handleMode) { v ->
            storeText()
            save { it.putString("handle_mode", v) }
            root.post { rebuild() }
        })
        if (c.handleMode != "circle") {
            label(card, "edge")
            card.addView(seg(listOf("left" to "left", "right" to "right"), if (c.handleRight) "right" else "left") { v -> save { it.putString("handle_side", v) } })
        }
        card.addView(slider(70, 180, 10, (c.handleSize * 100).toInt(), { "handle size: $it%" }) { v -> save { it.putInt("handle_size", v) } })
        card.addView(slider(0, 100, 5, (c.handleIdle * 100).toInt(), { "visible when not touched: $it%" }) { v -> save { it.putInt("handle_idle", v) } })
        label(card, "max power saves per game")
        card.addView(seg(listOf("1" to "1", "3" to "3", "5" to "5", "-1" to "∞"), c.savesPerGame.toString()) { v -> save { it.putInt("saves", v.toInt()) } })

        // ---- game review
        card = newCard("game review")
        label(card, "labels every move like chess.com (brilliant, great, best, book, mistake, blunder...) for the game you just played or any pasted pgn. analysis only, it never picks a move for you")
        card.addView(button("open game review", Theme.GOLD, filled = false) {
            startActivity(Intent(this, ReviewActivity::class.java))
        }, full(top = 8f))

        // ---- buttons
        root.addView(button("allow draw over other apps", Color.rgb(160, 170, 190), filled = false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, full(top = 18f))
        root.addView(button("start", accent, filled = true) { start() }, full(top = 10f))
        root.addView(button("stop", Color.rgb(255, 82, 82), filled = false) {
            stopService(Intent(this, ChessService::class.java))
            toast("stopped")
        }, full(top = 10f))

        val scroll = ScrollView(this)
        scroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.rgb(10, 13, 22), Theme.panel(accent, 255), Color.rgb(8, 10, 18)
        ))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun rebuild() {
        val y = (root.parent as? ScrollView)?.scrollY ?: 0
        build()
        (root.parent as? ScrollView)?.post { (root.parent as? ScrollView)?.scrollTo(0, y) }
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

    // ------------------------------------------------------------------ building blocks

    private fun full(top: Float = 0f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

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
        box.setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
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
        root.addView(box, full(top = 14f))
        return box
    }

    private fun label(parent: LinearLayout, t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12.5f
        v.setTextColor(Color.argb(175, 220, 228, 240))
        v.setPadding(dp(2f), dp(10f), 0, dp(5f))
        parent.addView(v)
    }

    private fun input(parent: LinearLayout, value: String, hintText: String, secret: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(value)
        e.textSize = 15f
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.argb(110, 255, 255, 255))
        e.background = rounded(Color.argb(70, 0, 0, 0), Color.argb(70, 255, 255, 255), 14f)
        e.setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        e.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        e.setOnFocusChangeListener { _, has -> if (!has) storeText() }
        parent.addView(e, full())
        return e
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

    private fun chip(text: String, on: Boolean, onToggle: (Boolean) -> Unit): View {
        val t = TextView(this)
        var state = on
        t.text = text
        t.textSize = 13.5f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        fun paint() {
            t.background = if (state) rounded(Theme.withAlpha(accent, 55), accent) else rounded(Color.argb(35, 255, 255, 255), Color.argb(45, 255, 255, 255))
            t.setTextColor(if (state) Color.WHITE else Color.argb(150, 255, 255, 255))
            if (state) t.setShadowLayer(12f, 0f, 0f, accent) else t.setShadowLayer(0f, 0f, 0f, 0)
        }
        paint()
        t.setOnClickListener {
            state = !state
            paint()
            onToggle(state)
        }
        return t
    }

    private fun slider(min: Int, max: Int, step: Int, value: Int, label: (Int) -> String, onSet: (Int) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10f), 0, 0)
        val l = TextView(this)
        l.textSize = 13.5f
        l.setTextColor(Color.WHITE)
        val s = SeekBar(this)
        s.max = (max - min) / step
        s.progress = (value.coerceIn(min, max) - min) / step
        s.progressTintList = ColorStateList.valueOf(accent)
        s.thumbTintList = ColorStateList.valueOf(accent)
        s.progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        l.text = label(min + s.progress * step)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { l.text = label(min + p * step) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { onSet(min + s.progress * step) }
        })
        box.addView(l)
        box.addView(s)
        return box
    }

    private fun button(text: String, col: Int, filled: Boolean, onClick: () -> Unit): View {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
        t.background = if (filled) rounded(col, Theme.core(col), 18f) else rounded(Theme.withAlpha(col, 30), Theme.withAlpha(col, 200), 18f)
        t.setTextColor(if (filled) Color.BLACK else col)
        if (filled) t.elevation = 10f * d
        t.setOnClickListener {
            it.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }.start()
            onClick()
        }
        return t
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
