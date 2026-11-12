package com.example.chesshelper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** what the side menu needs from the service */
interface SideBarHost {
    fun onRethink()
    fun onMaxPower()
    fun onNewGame()
    fun onStopHelper()
    fun onReview()
    fun onPauseToggle()
    fun isPaused(): Boolean
    fun onPanelOpen(open: Boolean)
    fun status(): String
    fun savesLeft(): Int // -1 = unlimited
    fun boardRect(): android.graphics.RectF? // where the board is, so the circle can stay off it
}

/**
 * A handle on the edge of the screen, invisible until you touch it (like game turbo). Touch it and a thin glowing
 * bar shows up; slide it towards the middle of the screen and the quick menu opens. drag it up or down to move it.
 */
class SideBar(private val ctx: Context, private val wm: WindowManager, private val host: SideBarHost) {
    private val d = ctx.resources.displayMetrics.density
    private val screenW = wm.maximumWindowMetrics.bounds.width()
    private val screenH = wm.maximumWindowMetrics.bounds.height()
    private var cfg = Cfg.load(ctx)
    private var accent = Theme.accent(ctx, cfg.theme)

    private var handle: HandleView? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panel: FrameLayout? = null
    private var panelBox: LinearLayout? = null
    private var panelW = 0
    private var panelRight = true

    val isOpen: Boolean get() = panel != null

    private fun dp(v: Float) = (v * d).toInt()

    // ------------------------------------------------------------------ the handle

    private val circleMode get() = cfg.handleMode == "circle"

    fun show() {
        if (handle != null) return
        cfg = Cfg.load(ctx)
        accent = Theme.accent(ctx, cfg.theme)
        val v = HandleView(ctx)
        val k = cfg.handleSize
        val lp: WindowManager.LayoutParams
        if (circleMode) {
            val size = dp(58f * k)
            lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (cfg.circleX * screenW - size / 2).toInt().coerceIn(0, screenW - size)
            lp.y = (cfg.circleY * screenH - size / 2).toInt().coerceIn(0, screenH - size)
        } else {
            // a wide strip, so a thumb can grab it easily. only a thin line of it is ever drawn
            lp = WindowManager.LayoutParams(
                dp(40f * k), dp(190f * k),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or (if (cfg.handleRight) Gravity.END else Gravity.START)
            lp.y = (cfg.handleY * screenH).toInt().coerceIn(0, screenH - dp(190f * k))
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        v.vis = cfg.handleIdle
        wm.addView(v, lp)
        handle = v
        handleLp = lp
    }

    fun remove() {
        closePanel(animated = false)
        handle?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        handle = null
    }

    /** settings changed somewhere: new color, other side of the screen, other handle... */
    fun refresh() {
        val old = cfg
        cfg = Cfg.load(ctx)
        accent = Theme.accent(ctx, cfg.theme)
        if (!cfg.sidebar) {
            remove()
            return
        }
        val rebuildHandle = old.handleRight != cfg.handleRight || old.handleMode != cfg.handleMode || old.handleSize != cfg.handleSize
        if (rebuildHandle && handle != null) {
            val open = isOpen
            handle?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            handle = null
            show()
            if (open) rebuild()
            return
        }
        if (handle == null) show()
        handle?.let { if (!it.touched) it.fadeTo(cfg.handleIdle) else it.invalidate() }
        if (isOpen) rebuild()
    }

    /** the side the menu opens on: the handle's side, or the half of the screen the circle is in */
    private val menuOnRight: Boolean
        get() {
            if (!circleMode) return cfg.handleRight
            val lp = handleLp ?: return true
            return lp.x + lp.width / 2 > screenW / 2
        }

    private inner class HandleView(c: Context) : View(c) {
        var vis = 0f
        var touched = false
        private var fader: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false
        private var opened = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hide = Runnable { touched = false; fadeTo(cfg.handleIdle) }

        fun fadeTo(target: Float) {
            fader?.cancel()
            fader = ValueAnimator.ofFloat(vis, target).apply {
                duration = if (target > vis) 120L else 500L
                addUpdateListener { vis = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (vis <= 0.01f) return
            if (circleMode) drawCircle(canvas) else drawBar(canvas)
        }

        /** a soft white pill, like the iphone's home bar, with a faint dark edge so it shows on light apps too */
        private fun drawBar(canvas: Canvas) {
            val x = if (cfg.handleRight) width - 7f * d else 7f * d
            val top = height * 0.14f
            val bottom = height * 0.86f
            p.color = Theme.withAlpha(Color.BLACK, (70 * vis).toInt())
            p.strokeWidth = 7f * d
            canvas.drawLine(x, top, x, bottom, p)
            p.color = Theme.withAlpha(Color.WHITE, (235 * vis).toInt())
            p.strokeWidth = 5f * d
            canvas.drawLine(x, top, x, bottom, p)
        }

        /** like the iphone's assistive touch button: a dark rounded square with soft white rings */
        private fun drawCircle(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val half = width * 0.42f
            val rr = half * 0.62f
            f.color = Theme.withAlpha(Color.rgb(15, 46, 37), (215 * vis).toInt())
            f.setShadowLayer(6f * d, 0f, 2f * d, Theme.withAlpha(Color.BLACK, (90 * vis).toInt()))
            canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, rr, rr, f)
            f.clearShadowLayer()
            f.color = Theme.withAlpha(Color.WHITE, (70 * vis).toInt())
            canvas.drawCircle(cx, cy, half * 0.72f, f)
            f.color = Theme.withAlpha(Color.WHITE, (110 * vis).toInt())
            canvas.drawCircle(cx, cy, half * 0.55f, f)
            f.color = Theme.withAlpha(Color.WHITE, (245 * vis).toInt())
            canvas.drawCircle(cx, cy, half * 0.40f, f)
            f.color = Theme.withAlpha(Color.rgb(15, 46, 37), (235 * vis).toInt())
            f.textSize = half * 0.52f
            f.textAlign = Paint.Align.CENTER
            canvas.drawText(if (host.isPaused()) "\u275A\u275A" else "\u265E", cx, cy + half * 0.19f, f)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lp = handleLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(hide)
                    touched = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    downAt = e.eventTime
                    dragging = false
                    opened = false
                    fadeTo(1f)
                    if (cfg.anim) animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> if (circleMode) moveCircle(e, lp) else moveBar(e, lp)
                MotionEvent.ACTION_UP -> {
                    if (cfg.anim) animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val tap = !dragging && !opened && e.eventTime - downAt < 350 &&
                        abs(e.rawX - downX) < 12f * d && abs(e.rawY - downY) < 12f * d
                    if (tap) {
                        // a tap opens the menu too
                        opened = true
                        openPanel()
                    }
                    if (dragging) dropped(lp)
                    postDelayed(hide, if (opened) 200 else 1500)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (cfg.anim) animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    postDelayed(hide, 1500)
                }
            }
            return true
        }

        private fun moveBar(e: MotionEvent, lp: WindowManager.LayoutParams) {
            val dx = if (cfg.handleRight) downX - e.rawX else e.rawX - downX
            val dy = e.rawY - downY
            if (!opened && !dragging && dx > 22f * d && dx > abs(dy)) {
                opened = true
                openPanel()
            } else if (!opened && (dragging || (abs(dy) > 14f * d && abs(dy) > dx))) {
                dragging = true
                lp.y = (startY + dy).toInt().coerceIn(0, screenH - height)
                try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
            }
        }

        private fun moveCircle(e: MotionEvent, lp: WindowManager.LayoutParams) {
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            if (!dragging && (abs(dx) > 10f * d || abs(dy) > 10f * d)) dragging = true
            if (dragging) {
                lp.x = (startX + dx).toInt().coerceIn(0, screenW - width)
                lp.y = (startY + dy).toInt().coerceIn(0, screenH - height)
                try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
            }
        }

        /** let go after dragging: keep the circle off the chess board, then remember where it is */
        private fun dropped(lp: WindowManager.LayoutParams) {
            if (circleMode) {
                val b = host.boardRect()
                val cx = lp.x + width / 2f
                val cy = lp.y + height / 2f
                if (b != null && b.contains(cx, cy)) {
                    // push it out the nearest side of the board
                    val toTop = cy - b.top
                    val toBottom = b.bottom - cy
                    val toLeft = cx - b.left
                    val toRight = b.right - cx
                    val m = minOf(minOf(toTop, toBottom), minOf(toLeft, toRight))
                    val gap = 6f * d
                    var nx = lp.x.toFloat()
                    var ny = lp.y.toFloat()
                    when (m) {
                        toTop -> ny = b.top - height - gap
                        toBottom -> ny = b.bottom + gap
                        toLeft -> nx = b.left - width - gap
                        else -> nx = b.right + gap
                    }
                    slideTo(lp, nx.toInt().coerceIn(0, screenW - width), ny.toInt().coerceIn(0, screenH - height))
                }
                Cfg.prefs(ctx).edit()
                    .putFloat("circle_x", (lp.x + width / 2f) / screenW)
                    .putFloat("circle_y", (lp.y + height / 2f) / screenH)
                    .apply()
            } else {
                Cfg.prefs(ctx).edit().putFloat("handle_y", lp.y.toFloat() / screenH).apply()
            }
        }

        private fun slideTo(lp: WindowManager.LayoutParams, x: Int, y: Int) {
            val x0 = lp.x
            val y0 = lp.y
            if (!cfg.anim) {
                lp.x = x
                lp.y = y
                try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                return
            }
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    lp.x = (x0 + (x - x0) * t).toInt()
                    lp.y = (y0 + (y - y0) * t).toInt()
                    try { wm.updateViewLayout(this@HandleView, lp) } catch (_: Exception) {}
                }
                start()
            }
        }
    }

    // ------------------------------------------------------------------ the menu

    fun openPanel() {
        if (panel != null) return
        host.onPanelOpen(true)
        panelW = minOf(dp(340f), (screenW * 0.9f).toInt())
        val root = FrameLayout(ctx)
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14f), dp(16f), dp(14f), dp(18f))
        scroll.addView(box)
        scroll.background = sheet()
        scroll.elevation = 18f * d
        scroll.clipToOutline = true
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
        })
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closePanel()
            false
        }
        val lp = WindowManager.LayoutParams(
            panelW, (screenH * 0.8f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        val right = menuOnRight
        panelRight = right
        lp.gravity = Gravity.CENTER_VERTICAL or (if (right) Gravity.END else Gravity.START)
        lp.x = dp(8f)
        wm.addView(root, lp)
        panel = root
        panelBox = box
        rebuild()

        val from = if (right) panelW.toFloat() else -panelW.toFloat()
        if (cfg.anim) {
            root.translationX = from
            root.alpha = 0f
            root.animate().translationX(0f).alpha(1f)
                .setInterpolator(OvershootInterpolator(0.8f))
                .setDuration((340 / cfg.animSpeed).toLong()).start()
        }
    }

    fun closePanel(animated: Boolean = true) {
        val root = panel ?: return
        panel = null
        panelBox = null
        val done = Runnable {
            try { wm.removeView(root) } catch (_: Exception) {}
            host.onPanelOpen(false)
        }
        if (animated && cfg.anim) {
            val to = if (panelRight) panelW.toFloat() else -panelW.toFloat()
            root.animate().translationX(to).alpha(0f).setDuration((200 / cfg.animSpeed).toLong()).withEndAction(done).start()
        } else {
            done.run()
        }
    }

    private fun save(edit: (android.content.SharedPreferences.Editor) -> Unit) {
        val e = Cfg.prefs(ctx).edit()
        edit(e)
        e.apply()
    }

    /** the menu's background: a dark rounded sheet, like ios control center */
    private fun sheet() = GradientDrawable().apply {
        cornerRadius = 30f * d
        setColor(Color.argb(246, 14, 14, 16))
        setStroke(maxOf(1, dp(0.6f)), Color.argb(40, 255, 255, 255))
    }

    /** fills the menu with the current settings */
    private fun rebuild() {
        val box = panelBox ?: return
        cfg = Cfg.load(ctx)
        accent = Theme.accent(ctx, cfg.theme)
        val ui = Ios(ctx, forceDark = true)
        box.removeAllViews()

        // header: title, what the helper is doing, a round close button
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(ui.text("Chess helper", 23f, ui.label, ui.serif))
        val status = ui.text(host.status(), 13f, ui.label2)
        status.setPadding(0, dp(3f), 0, 0)
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ui.text("✕", 14f, ui.label2, ui.bold)
        close.gravity = Gravity.CENTER
        close.background = ui.round(ui.cell2, 100f)
        close.setOnClickListener { closePanel() }
        header.addView(close, LinearLayout.LayoutParams(dp(30f), dp(30f)))
        header.setPadding(dp(4f), 0, 0, dp(14f))
        box.addView(header)

        // control center style tiles
        val saves = host.savesLeft()
        val paused = host.isPaused()
        val row1 = LinearLayout(ctx)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(tile(ui, "↻", "rethink", ui.blue) { host.onRethink(); closePanel() }, weight())
        row1.addView(tile(ui, "★", if (saves < 0) "max power" else "max power · $saves", ui.orange, enabled = saves != 0) {
            host.onMaxPower(); closePanel()
        }, weight())
        row1.addView(tile(ui, "⟲", "new game", ui.gray) { host.onNewGame(); closePanel() }, weight())
        box.addView(row1)
        val row2 = LinearLayout(ctx)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(tile(ui, if (paused) "▶" else "❚❚", if (paused) "resume" else "pause", if (paused) ui.green else ui.orange, wide = true) {
            host.onPauseToggle(); closePanel()
        }, weight())
        row2.addView(tile(ui, "♞", "review game", ui.indigo, wide = true) { host.onReview(); closePanel() }, weight())
        box.addView(row2)

        var g = ui.group(box, "game")
        g.add(ui.segRow("i play", listOf("auto" to "auto", "white" to "white", "black" to "black"), cfg.colorMode) { v ->
            save { it.putString("color", v) }
        })
        val engineNow = if (cfg.useMaia) "maia3" else if (cfg.useIllusion) "illusion" else "stockfish"
        g.add(ui.segRow("engine", listOf("stockfish" to "stockfish", "maia3" to "maia3", "illusion" to "illusion"), engineNow) { v ->
            save { it.putString("engine", v) }
            box.post { rebuild() }
        })
        if (cfg.useMaia) {
            g.add(ui.sliderRow("plays like", 600, 2600, 100, cfg.maiaElo, { "$it" }) { v -> save { it.putInt("maia_elo", v) } })
            g.add(ui.sliderRow("human-ness", 0, 100, 5, cfg.maiaHuman, { if (it == 0) "top pick" else "$it%" }) { v -> save { it.putInt("maia_human", v) } })
            if (cfg.maiaHost.isEmpty()) g.add(ui.noteRow("set the pc address in the app first", ui.orange, 13f))
        } else if (cfg.useIllusion) {
            g.add(ui.toggleRow("human mode", cfg.illusionHuman) { on ->
                save { it.putBoolean("illusion_human", on) }
                box.post { rebuild() }
            })
            g.add(ui.sliderRow("opponent rating", 600, 2600, 100, cfg.maiaElo, { "$it" }) { v -> save { it.putInt("maia_elo", v) } })
            if (cfg.illusionHuman) {
                g.add(ui.sliderRow("aggression", 0, 100, 5, cfg.illusionAggression, { "$it" }) { v -> save { it.putInt("illusion_aggression", v) } })
                g.add(ui.sliderRow("contempt", 0, 200, 5, cfg.illusionContempt, { if (it == 0) "takes draws" else "$it" }) { v -> save { it.putInt("illusion_contempt", v) } })
                g.add(ui.sliderRow("swindle tolerance", 0, 300, 10, cfg.illusionTolerance, { if (it == 0) "no traps" else "$it" }) { v -> save { it.putInt("illusion_tolerance", v) } })
            }
        } else {
            g.add(ui.sliderRow("strength", UciEngine.MIN_ELO, UciEngine.MAX_ELO, 10, cfg.elo,
                { if (it >= UciEngine.MAX_ELO) "full power" else "elo $it" }) { v -> save { it.putInt("elo2", v) } })
        }
        g.add(ui.segRow("moves shown", (1..4).map { it.toString() to it.toString() }, cfg.nMoves.toString()) { v ->
            save { it.putInt("n_moves", v.toInt()) }
        })

        g = ui.group(box, "illusion")
        g.add(ui.toggleRow("brilliant hunter", cfg.illusionBrilliant) { on -> save { it.putBoolean("illusion_brilliant", on) } })
        g.add(ui.toggleRow("guess the bot", cfg.illusionGuess) { on -> save { it.putBoolean("illusion_guess", on) } })

        g = ui.group(box, "show")
        g.add(ui.toggleRow("eval bar", cfg.showEval) { on -> save { it.putBoolean("show_eval", on) } })
        g.add(ui.toggleRow("threat", cfg.showThreat) { on -> save { it.putBoolean("show_threat", on) } })
        g.add(ui.toggleRow("best line", cfg.showLine) { on -> save { it.putBoolean("show_line", on) } })
        g.add(ui.toggleRow("accuracy", cfg.showAcc) { on -> save { it.putBoolean("show_acc", on) } })
        g.add(ui.toggleRow("blunder alert", cfg.blunderSquares) { on -> save { it.putBoolean("blunder_squares", on) } })
        g.add(ui.toggleRow("hanging pieces", cfg.hanging) { on -> save { it.putBoolean("hanging", on) } })
        g.add(ui.toggleRow("safe squares", cfg.safeSquares) { on -> save { it.putBoolean("safe_squares", on) } })
        g.add(ui.toggleRow("critical moments", cfg.critical) { on -> save { it.putBoolean("critical", on) } })
        g.add(ui.toggleRow("bot's plan", cfg.botPlan) { on -> save { it.putBoolean("bot_plan", on) } })
        g.add(ui.toggleRow("guess the bot's move", cfg.predict) { on -> save { it.putBoolean("predict", on) } })
        g.add(ui.toggleRow("premove", cfg.premove) { on -> save { it.putBoolean("premove", on) } })
        g.add(ui.toggleRow("move labels", cfg.showLabels) { on -> save { it.putBoolean("show_labels", on) } })

        g = ui.group(box, "look")
        g.add(ui.segRow("arrows", listOf("neon" to "neon", "solid" to "solid", "minimal" to "minimal"), cfg.style) { v ->
            save { it.putString("arrow_style", v) }
        })
        g.add(ui.segRow("color", Theme.PRESETS.keys.map { it to it }, cfg.theme) { v -> save { it.putString("theme", v) } })
        g.add(ui.toggleRow("animations", cfg.anim) { on -> save { it.putBoolean("anim", on) } })
        g.add(ui.sliderRow("animation speed", 50, 200, 10, (cfg.animSpeed * 100).toInt(), { "$it%" }) { v -> save { it.putInt("anim_speed", v) } })
        g.add(ui.sliderRow("arrow size", 60, 160, 5, (cfg.arrowSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("arrow_size", v) } })
        g.add(ui.sliderRow("card size", 70, 140, 5, (cfg.cardSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("card_size", v) } })
        g.add(ui.sliderRow("opacity", 30, 100, 5, (cfg.opacity * 100).toInt(), { "$it%" }) { v -> save { it.putInt("opacity", v) } })

        g = ui.group(box, "handle")
        g.add(ui.segRow(null, listOf("bar" to "edge bar", "circle" to "circle"), cfg.handleMode) { v ->
            save { it.putString("handle_mode", v) }
            box.post { rebuild() }
        })
        if (!circleMode) {
            g.add(ui.segRow(null, listOf("left" to "left edge", "right" to "right edge"), if (cfg.handleRight) "right" else "left") { v ->
                save { it.putString("handle_side", v) }
            })
        }
        g.add(ui.sliderRow("size", 70, 180, 10, (cfg.handleSize * 100).toInt(), { "$it%" }) { v -> save { it.putInt("handle_size", v) } })
        g.add(ui.sliderRow("visible when idle", 0, 100, 5, (cfg.handleIdle * 100).toInt(), { "$it%" }) { v -> save { it.putInt("handle_idle", v) } })
        g.add(ui.segRow("max power saves per game", listOf("1" to "1", "3" to "3", "5" to "5", "-1" to "∞"), cfg.savesPerGame.toString()) { v ->
            save { it.putInt("saves", v.toInt()) }
        })

        g = ui.group(box, null)
        g.add(ui.buttonRow("stop helper", ui.red) { closePanel(false); host.onStopHelper() })
    }

    // ------------------------------------------------------------------ little building blocks

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
    }

    /** a control center tile: a colored round icon over a short label */
    private fun tile(ui: Ios, icon: String, label: String, col: Int, enabled: Boolean = true, wide: Boolean = false, onClick: () -> Unit): View {
        val v = LinearLayout(ctx)
        v.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        v.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER
        v.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        v.background = ui.pressable(ui.round(ui.cell, 20f), ui.round(ui.pressed, 20f))
        v.alpha = if (enabled) 1f else 0.4f
        val i = ui.text(icon, 17f, ui.ink, ui.bold)
        i.gravity = Gravity.CENTER
        i.background = ui.round(col, 100f)
        v.addView(i, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        val l = ui.text(label, 13f, ui.label, ui.medium)
        l.maxLines = 1
        if (wide) {
            l.setPadding(dp(10f), 0, 0, 0)
            v.addView(l)
        } else {
            l.gravity = Gravity.CENTER
            l.setPadding(0, dp(8f), 0, 0)
            v.addView(l)
        }
        if (enabled) v.setOnClickListener {
            if (cfg.anim) it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                onClick()
            }.start() else onClick()
        }
        return v
    }
}
