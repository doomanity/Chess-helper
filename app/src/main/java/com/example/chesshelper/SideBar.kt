package com.example.chesshelper

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.widget.SeekBar
import android.widget.TextView
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

        private fun drawBar(canvas: Canvas) {
            val x = if (cfg.handleRight) width - 8f * d else 8f * d
            val top = height * 0.12f
            val bottom = height * 0.88f
            p.color = Theme.withAlpha(accent, (60 * vis).toInt())
            p.strokeWidth = 13f * d
            canvas.drawLine(x, top, x, bottom, p)
            p.color = Theme.withAlpha(accent, (170 * vis).toInt())
            p.strokeWidth = 6f * d
            canvas.drawLine(x, top, x, bottom, p)
            p.color = Theme.withAlpha(Color.WHITE, (240 * vis).toInt())
            p.strokeWidth = 2.6f * d
            canvas.drawLine(x, top, x, bottom, p)
        }

        private fun drawCircle(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width * 0.36f
            f.color = Theme.withAlpha(accent, (55 * vis).toInt())
            canvas.drawCircle(cx, cy, r * 1.32f, f)
            f.color = Theme.withAlpha(Theme.panel(accent, 255), (235 * vis).toInt())
            canvas.drawCircle(cx, cy, r, f)
            p.color = Theme.withAlpha(accent, (255 * vis).toInt())
            p.strokeWidth = 2.6f * d
            canvas.drawCircle(cx, cy, r, p)
            p.color = Theme.withAlpha(Theme.core(accent), (200 * vis).toInt())
            p.strokeWidth = 1f * d
            canvas.drawCircle(cx, cy, r, p)
            // a small knight
            f.color = Theme.withAlpha(Color.WHITE, (245 * vis).toInt())
            f.textSize = r * 1.15f
            f.textAlign = Paint.Align.CENTER
            f.setShadowLayer(r * 0.35f, 0f, 0f, Theme.withAlpha(accent, (255 * vis).toInt()))
            canvas.drawText(if (host.isPaused()) "\u275A\u275A" else "\u265E", cx, cy + r * 0.4f, f)
            f.clearShadowLayer()
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
        box.setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
        scroll.addView(box)
        scroll.background = GradientDrawable().apply {
            cornerRadius = 26f * d
            setColor(Theme.panel(accent, 242))
            setStroke(dp(1.5f), Theme.withAlpha(accent, 200))
        }
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

    /** fills the menu with the current settings */
    private fun rebuild() {
        val box = panelBox ?: return
        cfg = Cfg.load(ctx)
        accent = Theme.accent(ctx, cfg.theme)
        box.removeAllViews()
        (box.parent as? View)?.background = GradientDrawable().apply {
            cornerRadius = 26f * d
            setColor(Theme.panel(accent, 242))
            setStroke(dp(1.5f), Theme.withAlpha(accent, 200))
        }

        // header
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ctx)
        title.text = "chess helper"
        title.textSize = 20f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.setShadowLayer(14f, 0f, 0f, accent)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(ctx)
        close.text = "✕"
        close.textSize = 18f
        close.setTextColor(Color.argb(200, 255, 255, 255))
        close.setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
        close.setOnClickListener { closePanel() }
        header.addView(close)
        box.addView(header)
        val status = TextView(ctx)
        status.text = host.status()
        status.textSize = 12f
        status.setTextColor(Theme.withAlpha(accent, 230))
        status.setPadding(0, dp(2f), 0, dp(10f))
        box.addView(status)

        // big buttons
        val saves = host.savesLeft()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(bigButton("↻", "rethink", accent) { host.onRethink(); closePanel() }, weight())
        row.addView(bigButton("★", if (saves < 0) "max power" else "max power ($saves)", Theme.GOLD, enabled = saves != 0) {
            host.onMaxPower(); closePanel()
        }, weight())
        row.addView(bigButton("⟲", "new game", Color.rgb(160, 170, 190)) { host.onNewGame(); closePanel() }, weight())
        box.addView(row)
        val paused = host.isPaused()
        box.addView(bigButton(if (paused) "▶" else "❚❚", if (paused) "resume" else "pause", if (paused) Color.rgb(0, 200, 83) else Color.rgb(255, 196, 0)) {
            host.onPauseToggle(); closePanel()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
        })
        box.addView(bigButton("♞", "review this game", Theme.GOLD) { host.onReview(); closePanel() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
            })

        section("i play")
        box.addView(seg(listOf("white" to "white", "black" to "black"), if (cfg.iAmWhite) "white" else "black") { v ->
            save { it.putString("color", v) }
        })

        section("engine")
        box.addView(seg(listOf("stockfish" to "stockfish", "maia3" to "maia3 (pc)"), if (cfg.useMaia) "maia3" else "stockfish") { v ->
            save { it.putString("engine", v) }
            box.post { rebuild() }
        })
        if (cfg.useMaia) {
            box.addView(slider(600, 2600, 100, cfg.maiaElo, { "maia3 plays like: $it" }) { v -> save { it.putInt("maia_elo", v) } })
            box.addView(slider(0, 100, 5, cfg.maiaHuman, { if (it == 0) "human-ness: 0% (always maia's top pick)" else "human-ness: $it%" }) { v ->
                save { it.putInt("maia_human", v) }
            })
            if (cfg.maiaHost.isEmpty()) note("set the pc address in the app first")
        } else {
            box.addView(slider(UciEngine.MIN_ELO, UciEngine.MAX_ELO, 10, cfg.elo,
                { if (it >= UciEngine.MAX_ELO) "stockfish strength: full power" else "stockfish strength: elo $it" }) { v ->
                save { it.putInt("elo2", v) }
            })
        }

        section("moves shown")
        box.addView(seg((1..4).map { it.toString() to it.toString() }, cfg.nMoves.toString()) { v ->
            save { it.putInt("n_moves", v.toInt()) }
        })

        section("show")
        val chips = LinearLayout(ctx)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(chip("eval bar", cfg.showEval) { on -> save { it.putBoolean("show_eval", on) } }, weight())
        chips.addView(chip("threat", cfg.showThreat) { on -> save { it.putBoolean("show_threat", on) } }, weight())
        chips.addView(chip("best line", cfg.showLine) { on -> save { it.putBoolean("show_line", on) } }, weight())
        box.addView(chips)
        val chips2 = LinearLayout(ctx)
        chips2.orientation = LinearLayout.HORIZONTAL
        chips2.addView(chip("guess bot move", cfg.predict) { on -> save { it.putBoolean("predict", on) } }, weight())
        chips2.addView(chip("accuracy", cfg.showAcc) { on -> save { it.putBoolean("show_acc", on) } }, weight())
        box.addView(chips2)

        section("arrow style")
        box.addView(seg(listOf("neon" to "neon", "solid" to "solid", "minimal" to "minimal"), cfg.style) { v ->
            save { it.putString("arrow_style", v) }
        })

        section("effects")
        box.addView(chip("animations", cfg.anim) { on -> save { it.putBoolean("anim", on) } })
        box.addView(slider(50, 200, 10, (cfg.animSpeed * 100).toInt(), { "animation speed: ${it}%" }) { v -> save { it.putInt("anim_speed", v) } })
        box.addView(slider(60, 160, 5, (cfg.arrowSize * 100).toInt(), { "arrow size: ${it}%" }) { v -> save { it.putInt("arrow_size", v) } })
        box.addView(slider(70, 140, 5, (cfg.cardSize * 100).toInt(), { "card size: ${it}%" }) { v -> save { it.putInt("card_size", v) } })
        box.addView(slider(30, 100, 5, (cfg.opacity * 100).toInt(), { "opacity: ${it}%" }) { v -> save { it.putInt("opacity", v) } })

        section("theme")
        box.addView(seg(Theme.PRESETS.keys.map { it to it }, cfg.theme) { v ->
            save { it.putString("theme", v) }
            box.post { rebuild() }
        })

        section("handle")
        box.addView(seg(listOf("bar" to "edge bar", "circle" to "circle"), cfg.handleMode) { v ->
            save { it.putString("handle_mode", v) }
            box.post { rebuild() }
        })
        if (!circleMode) {
            box.addView(seg(listOf("left" to "left edge", "right" to "right edge"), if (cfg.handleRight) "right" else "left") { v ->
                save { it.putString("handle_side", v) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        }
        box.addView(slider(70, 180, 10, (cfg.handleSize * 100).toInt(), { "handle size: $it%" }) { v -> save { it.putInt("handle_size", v) } })
        box.addView(slider(0, 100, 5, (cfg.handleIdle * 100).toInt(), { "visible when not touched: $it%" }) { v -> save { it.putInt("handle_idle", v) } })

        section("max power saves per game")
        box.addView(seg(listOf("1" to "1", "3" to "3", "5" to "5", "-1" to "∞"), cfg.savesPerGame.toString()) { v ->
            save { it.putInt("saves", v.toInt()) }
        })

        val stop = bigButton("■", "stop helper", Color.rgb(255, 82, 82)) { closePanel(false); host.onStopHelper() }
        box.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18f)
        })
    }

    // ------------------------------------------------------------------ little building blocks

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
    }

    private fun section(t: String) {
        val v = TextView(ctx)
        v.text = t.uppercase()
        v.textSize = 11f
        v.letterSpacing = 0.14f
        v.setTextColor(Color.argb(170, 220, 228, 240))
        v.setPadding(dp(2f), dp(14f), 0, dp(6f))
        panelBox?.addView(v)
    }

    private fun note(t: String) {
        val v = TextView(ctx)
        v.text = t
        v.textSize = 12f
        v.setTextColor(Color.rgb(255, 180, 80))
        v.setPadding(dp(2f), dp(2f), 0, 0)
        panelBox?.addView(v)
    }

    private fun rounded(fillColor: Int, strokeColor: Int, radius: Float = 14f) = GradientDrawable().apply {
        cornerRadius = radius * d
        setColor(fillColor)
        setStroke(dp(1.2f), strokeColor)
    }

    private fun bigButton(icon: String, label: String, col: Int, enabled: Boolean = true, onClick: () -> Unit): View {
        val v = LinearLayout(ctx)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        v.background = rounded(Theme.withAlpha(col, if (enabled) 40 else 15), Theme.withAlpha(col, if (enabled) 210 else 70), 18f)
        val i = TextView(ctx)
        i.text = icon
        i.textSize = 20f
        i.gravity = Gravity.CENTER
        i.setTextColor(if (enabled) col else Color.GRAY)
        if (enabled) i.setShadowLayer(16f, 0f, 0f, col)
        val l = TextView(ctx)
        l.text = label
        l.textSize = 12f
        l.gravity = Gravity.CENTER
        l.setTextColor(if (enabled) Color.WHITE else Color.GRAY)
        v.addView(i)
        v.addView(l)
        if (enabled) v.setOnClickListener {
            if (cfg.anim) it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                onClick()
            }.start() else onClick()
        }
        return v
    }

    /** a row of options, one of them picked */
    private fun seg(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        row.background = rounded(Color.argb(60, 255, 255, 255), Color.argb(40, 255, 255, 255), 16f)
        val views = ArrayList<TextView>()
        fun paint(sel: String) {
            for ((i, v) in views.withIndex()) {
                val on = options[i].first == sel
                v.background = if (on) rounded(accent, accent, 13f) else null
                v.setTextColor(if (on) Color.BLACK else Color.argb(220, 255, 255, 255))
                v.setShadowLayer(if (on) 0f else 0f, 0f, 0f, 0)
                v.elevation = if (on) 6f * d else 0f
            }
        }
        for ((value, label) in options) {
            val t = TextView(ctx)
            t.text = label
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
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

    private fun chip(label: String, on: Boolean, onToggle: (Boolean) -> Unit): View {
        val t = TextView(ctx)
        var state = on
        t.text = label
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
        fun paint() {
            t.background = if (state) rounded(Theme.withAlpha(accent, 60), accent) else rounded(Color.argb(40, 255, 255, 255), Color.argb(50, 255, 255, 255))
            t.setTextColor(if (state) Color.WHITE else Color.argb(160, 255, 255, 255))
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

    /** a slider with its label above it; the setting is saved when you let go */
    private fun slider(min: Int, max: Int, step: Int, value: Int, label: (Int) -> String, onSet: (Int) -> Unit): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(6f), 0, 0)
        val l = TextView(ctx)
        l.textSize = 13f
        l.setTextColor(Color.WHITE)
        l.setPadding(dp(2f), 0, 0, 0)
        val s = SeekBar(ctx)
        s.max = (max - min) / step
        s.progress = ((value.coerceIn(min, max) - min) / step)
        s.progressTintList = ColorStateList.valueOf(accent)
        s.thumbTintList = ColorStateList.valueOf(accent)
        s.progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        l.text = label(min + s.progress * step)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                l.text = label(min + p * step)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onSet(min + s.progress * step)
            }
        })
        box.addView(l)
        box.addView(s)
        return box
    }
}
