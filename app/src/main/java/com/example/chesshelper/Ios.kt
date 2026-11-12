package com.example.chesshelper

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * the app's look, "clubroom": a chess club at night. tournament felt green, ivory text like the light pieces,
 * brass for what you can press (like the fittings on an old chess clock). grouped lists with rounded sections,
 * switches, segmented controls, sliders and big buttons. one look in light and dark mode alike.
 * titles are set in fraunces (a soft serif), everything else in the phone's sans.
 */
class Ios(private val ctx: Context, @Suppress("UNUSED_PARAMETER") forceDark: Boolean = false) {
    /** always a dark look (some screens pick alphas by this) */
    val dark: Boolean = true
    private val d = ctx.resources.displayMetrics.density

    // the clubroom palette
    val felt = 0xFF0F2E25.toInt()                     // the table: screen background
    val bg = felt
    val cell = 0xFF173F33.toInt()                     // a section, raised felt
    val cell2 = 0xFF1F4D3F.toInt()                    // something inside a section
    val pressed = 0xFF275C4B.toInt()
    val ivory = 0xFFF0E8D5.toInt()                    // text, the light pieces
    val label = ivory
    val label2 = 0xADF0E8D5.toInt()                   // secondary text
    val label3 = 0x5CF0E8D5                           // placeholder, faint text
    val separator = 0xFF2A5646.toInt()
    val fill = 0xFF0B241D.toInt()                     // segmented control track, text field: sunk into the felt
    val brass = 0xFFD2AD5B.toInt()                    // what you can press
    val brassDeep = 0xFF9C7A33.toInt()
    val ink = 0xFF16261F.toInt()                      // text on brass or ivory, the dark pieces
    val segOn = brass
    val blue = brass                                  // the old accent name: now brass everywhere
    val green = 0xFF86C796.toInt()                    // sage: good
    val red = 0xFFE2694F.toInt()                      // brick: stop, danger
    val orange = 0xFFE8A23A.toInt()                   // amber
    val indigo = 0xFFA592DE.toInt()
    val gray = 0xFF8FA398.toInt()

    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    /** the serif for titles and section headers */
    val serif: Typeface = serifOf(ctx, 600)
    val serifSoft: Typeface = serifOf(ctx, 500)

    fun dp(v: Float) = (v * d).toInt()
    fun px(v: Float) = v * d

    fun round(color: Int, radius: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(radius)
        setColor(color)
        if (stroke != 0) setStroke(maxOf(1, dp(0.5f)), stroke)
    }

    /** a background that turns grey while pressed, like a table row */
    fun pressable(normal: Drawable, down: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), down)
        addState(intArrayOf(), normal)
    }

    fun text(t: String, size: Float, color: Int = label, face: Typeface = regular): TextView = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        setLineSpacing(px(2f), 1f)
    }

    fun full(top: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    fun weight(w: Float = 1f, gap: Float = 0f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
        setMargins(dp(gap), 0, dp(gap), 0)
    }

    // ------------------------------------------------------------------ the screen

    /** a scrolling screen with a large title, like the settings app. returns (scroll, column to fill) */
    fun screen(a: Activity, title: String, subtitle: String? = null): Pair<ScrollView, LinearLayout> {
        a.window.statusBarColor = bg
        a.window.navigationBarColor = bg
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18f), dp(18f), dp(18f), dp(40f))
        val t = text(title, 34f, label, serif)
        t.letterSpacing = -0.015f
        t.setPadding(dp(2f), dp(22f), 0, dp(2f))
        col.addView(t)
        if (subtitle != null) {
            val s = text(subtitle, 15f, label2)
            s.setPadding(dp(4f), dp(4f), 0, dp(6f))
            col.addView(s)
        }
        val scroll = ScrollView(ctx)
        scroll.background = feltBackground()
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col)
        return Pair(scroll, col)
    }

    /** the table: felt that is a touch lighter where the lamp is (top) */
    fun feltBackground(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF143A2E.toInt(), felt, 0xFF0B251D.toInt())
    )

    // ------------------------------------------------------------------ grouped sections

    /** one rounded section of a grouped list. rows added with add() get the thin line between them */
    inner class Group(val box: LinearLayout) {
        private var rows = 0

        fun add(v: View): View {
            if (rows > 0) {
                val line = View(ctx)
                line.setBackgroundColor(separator)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
                lp.leftMargin = dp(18f)
                lp.rightMargin = dp(18f)
                box.addView(line, lp)
            }
            box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rows++
            return v
        }
    }

    /** a section with a small grey header above and an optional explanation below */
    fun group(parent: LinearLayout, header: String?, footer: String? = null): Group {
        if (header != null) {
            // section names in the serif, sentence case, ivory: like a chapter in a chess book
            val h = text(header.replaceFirstChar { it.uppercase() }, 19f, label, serifSoft)
            h.setPadding(dp(4f), dp(30f), dp(16f), dp(9f))
            parent.addView(h)
        } else {
            parent.addView(View(ctx), LinearLayout.LayoutParams(1, dp(22f)))
        }
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.background = round(cell, 16f, 0x1FF0E8D5)
        box.clipToOutline = true
        parent.addView(box, full())
        if (footer != null) {
            val f = text(footer, 13f, label2)
            f.setPadding(dp(4f), dp(8f), dp(12f), 0)
            parent.addView(f)
        }
        return Group(box)
    }

    private fun rowBox(minH: Float = 46f): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(minH + 6f)
        setPadding(dp(18f), dp(12f), dp(16f), dp(12f))
    }

    private fun titleBlock(title: String, subtitle: String?): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 16.5f, label))
        if (subtitle != null) {
            val s = text(subtitle, 13f, label2)
            s.setPadding(0, dp(3f), 0, 0)
            col.addView(s)
        }
        return col
    }

    /** a row with a title and a switch */
    fun toggleRow(title: String, on: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit): View {
        val row = rowBox()
        row.addView(titleBlock(title, subtitle), weight())
        val sw = IosSwitch(ctx, this, on, onChange)
        val lp = LinearLayout.LayoutParams(dp(51f), dp(31f))
        lp.leftMargin = dp(12f)
        row.addView(sw, lp)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    /** a row that opens something: title, a grey value on the right and a chevron */
    fun navRow(title: String, value: String? = null, tint: Int = label, onClick: () -> Unit): View {
        val row = rowBox()
        row.addView(text(title, 17f, tint), weight())
        if (value != null) row.addView(text(value, 17f, label2))
        val chev = text("›", 22f, brass, medium)
        chev.setPadding(dp(8f), 0, 0, dp(2f))
        row.addView(chev)
        row.background = pressable(ColorDrawable(Color.TRANSPARENT), ColorDrawable(pressed))
        row.setOnClickListener { onClick() }
        return row
    }

    /** a centered tappable text row (blue, or red for things like stop) */
    fun buttonRow(title: String, tint: Int = blue, onClick: () -> Unit): View {
        val row = rowBox()
        row.gravity = Gravity.CENTER
        row.addView(text(title, 17f, tint))
        row.background = pressable(ColorDrawable(Color.TRANSPARENT), ColorDrawable(pressed))
        row.setOnClickListener { onClick() }
        return row
    }

    /** a row with a small title above a segmented control */
    fun segRow(title: String?, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        if (title != null) {
            val t = text(title, 15f, label2)
            t.setPadding(0, 0, 0, dp(9f))
            col.addView(t)
        }
        col.addView(segmented(options, current, onPick), full())
        return col
    }

    /** apple's segmented control */
    fun segmented(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        row.background = round(fill, 11f)
        val views = ArrayList<TextView>()
        fun paint(sel: String) {
            for ((i, v) in views.withIndex()) {
                val on = options[i].first == sel
                v.background = if (on) round(segOn, 9f) else null
                v.elevation = if (on) px(1.5f) else 0f
                v.typeface = if (on) medium else regular
                v.setTextColor(if (on) ink else label2)
            }
        }
        for ((value, t) in options) {
            val v = text(t, 13f)
            v.gravity = Gravity.CENTER
            v.maxLines = 1
            v.setPadding(dp(4f), dp(7f), dp(4f), dp(7f))
            v.setOnClickListener {
                paint(value)
                onPick(value)
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(1f), 0, dp(1f), 0) })
        }
        paint(current)
        return row
    }

    /** a row with a title, the current value on the right, and an ios slider under them */
    fun sliderRow(title: String, min: Int, max: Int, step: Int, value: Int, show: (Int) -> String, onSet: (Int) -> Unit): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18f), dp(14f), dp(18f), dp(6f))
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(text(title, 16.5f, label), weight())
        val v = text(show(value), 15f, brass, medium)
        top.addView(v)
        col.addView(top)
        val s = IosSlider(ctx, this, min, max, step, value.coerceIn(min, max), { v.text = show(it) }, onSet)
        col.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))
        return col
    }

    /** a text field inside a row (with an optional small title above it) */
    fun fieldRow(title: String?, value: String, hint: String, secret: Boolean = false): Pair<View, EditText> {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        if (title != null) {
            val t = text(title, 13f, label2)
            t.setPadding(0, 0, 0, dp(6f))
            col.addView(t)
        }
        val e = EditText(ctx)
        e.setText(value)
        e.hint = hint
        e.textSize = 17f
        e.typeface = regular
        e.setTextColor(label)
        e.setHintTextColor(label3)
        e.background = round(fill, 10f)
        e.setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        e.setSingleLine(true)
        e.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        col.addView(e, full())
        return Pair(col, e)
    }

    /** a plain text row (a note inside a section) */
    fun noteRow(t: String, color: Int = label2, size: Float = 15f): TextView {
        val v = text(t, size, color)
        v.setPadding(dp(18f), dp(13f), dp(18f), dp(13f))
        return v
    }

    /** the big rounded filled button at the bottom of an ios screen */
    fun bigButton(t: String, color: Int = blue, filled: Boolean = true, onClick: () -> Unit): View {
        // filled brass (or another color) with dark ink text, like a brass plate
        val light = Color.luminance(color) > 0.35f
        val v = text(t, 17f, if (filled) (if (light) ink else ivory) else color, medium)
        v.gravity = Gravity.CENTER
        v.minHeight = dp(54f)
        v.setPadding(dp(16f), dp(15f), dp(16f), dp(15f))
        val normal = if (filled) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighter(color, 0.12f), color)).apply { cornerRadius = px(16f) }
            else round(Theme.withAlpha(color, 40), 16f, Theme.withAlpha(color, 90))
        val down = round(if (filled) darker(color, 0.15f) else Theme.withAlpha(color, 80), 16f)
        v.background = pressable(normal, down)
        v.setOnClickListener {
            it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            onClick()
        }
        return v
    }

    /** a small rounded label like a tag */
    fun pill(t: String, color: Int): TextView {
        val v = text(t, 12f, color, medium)
        v.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        v.background = round(Theme.withAlpha(color, 50), 100f)
        return v
    }

    fun lighter(c: Int, t: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(), (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
    )

    fun darker(c: Int, t: Float) = Color.rgb(
        (Color.red(c) * (1 - t)).toInt(), (Color.green(c) * (1 - t)).toInt(), (Color.blue(c) * (1 - t)).toInt()
    )

    companion object {
        private val cache = HashMap<Int, Typeface>()

        /** fraunces at a weight (bundled in assets, a variable font), or the phone's serif if it can't load */
        fun serifOf(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
            try {
                Typeface.Builder(ctx.assets, "fonts/fraunces.ttf")
                    .setFontVariationSettings("'wght' $weight, 'opsz' 72, 'SOFT' 60")
                    .build() ?: Typeface.SERIF
            } catch (e: Exception) {
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
        }
    }
}

/** apple's on / off switch: a green pill with a white knob that slides */
class IosSwitch(ctx: Context, private val ui: Ios, on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private var state = on
    private var pos = if (on) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun toggle() {
        state = !state
        anim?.cancel()
        val a = ValueAnimator.ofFloat(pos, if (state) 1f else 0f)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        a.start()
        anim = a
        onChange(state)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        val off = ui.fill
        p.color = blend(off, ui.brass, pos)
        c.drawRoundRect(r, h / 2, h / 2, p)
        val m = ui.px(2f)
        val kr = h / 2 - m
        val cx = m + kr + (w - 2 * m - 2 * kr) * pos
        p.color = ui.ivory
        p.setShadowLayer(ui.px(3f), 0f, ui.px(1.5f), 0x55000000)
        c.drawCircle(cx, h / 2, kr, p)
        p.clearShadowLayer()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
}

/** apple's slider: a thin track, blue up to a round white knob */
class IosSlider(
    ctx: Context, private val ui: Ios, private val min: Int, private val max: Int, private val step: Int,
    private var value: Int, private val onMove: (Int) -> Unit, private val onSet: (Int) -> Unit
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val knob get() = ui.px(14f)

    private fun frac() = if (max == min) 0f else (value - min).toFloat() / (max - min)

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val left = knob
        val right = width - knob
        val x = left + (right - left) * frac()
        val th = ui.px(2f)
        p.color = ui.fill
        r.set(left, y - th, right, y + th)
        c.drawRoundRect(r, th, th, p)
        p.color = ui.brass
        r.set(left, y - th, x, y + th)
        c.drawRoundRect(r, th, th, p)
        p.color = ui.ivory
        p.setShadowLayer(ui.px(4f), 0f, ui.px(2f), 0x55000000)
        c.drawCircle(x, y, knob, p)
        p.clearShadowLayer()
        // a brass center, like a clock button
        p.color = ui.brass
        c.drawCircle(x, y, knob * 0.32f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val left = knob
        val right = width - knob
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val t = ((e.x - left) / (right - left)).coerceIn(0f, 1f)
                val raw = min + t * (max - min)
                val v = (min + Math.round((raw - min) / step) * step).coerceIn(min, max)
                if (v != value) {
                    value = v
                    onMove(v)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onSet(value)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** a frame that rounds its children's corners (for a board or a graph inside a section) */
fun roundedFrame(ctx: Context, ui: Ios, color: Int, radius: Float): FrameLayout = FrameLayout(ctx).apply {
    background = ui.round(color, radius)
    clipToOutline = true
}
