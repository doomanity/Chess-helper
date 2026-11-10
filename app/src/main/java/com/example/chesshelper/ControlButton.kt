package com.example.chesshelper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * the small round floating button for engine control: green play = you play (tap to let the engine play),
 * red stop = the engine plays (tap to take control back). drag it anywhere, it remembers where
 */
@SuppressLint("ViewConstructor")
class ControlButton(private val ctx: Context, private val wm: WindowManager, private val onToggle: () -> Unit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val size = (52 * d).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )
    private var added = false

    /** true while the engine plays */
    var playing = false
        set(v) {
            field = v
            invalidate()
        }

    init {
        lp.gravity = Gravity.TOP or Gravity.START
        val prefs = Cfg.prefs(ctx)
        val dm = ctx.resources.displayMetrics
        lp.x = (prefs.getFloat("ctl_x", 0.04f) * dm.widthPixels).toInt()
        lp.y = (prefs.getFloat("ctl_y", 0.18f) * dm.heightPixels).toInt()
    }

    fun show() {
        if (added) return
        try {
            wm.addView(this, lp)
            added = true
        } catch (e: Exception) {
        }
    }

    fun remove() {
        if (!added) return
        try {
            wm.removeView(this)
        } catch (e: Exception) {
        }
        added = false
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.color = Color.argb(215, 28, 28, 30)
        p.setShadowLayer(4 * d, 0f, 2 * d, Color.argb(110, 0, 0, 0))
        c.drawCircle(r, r, r - 4 * d, p)
        p.clearShadowLayer()
        p.color = if (playing) Color.rgb(255, 69, 58) else Color.rgb(48, 209, 88)
        c.drawCircle(r, r, r - 9 * d, p)
        p.color = Color.WHITE
        if (playing) {
            val s = r * 0.3f
            c.drawRoundRect(r - s, r - s, r + s, r + s, s * 0.25f, s * 0.25f, p)
        } else {
            val s = r * 0.34f
            val path = android.graphics.Path()
            path.moveTo(r - s * 0.7f, r - s)
            path.lineTo(r + s, r)
            path.lineTo(r - s * 0.7f, r + s)
            path.close()
            c.drawPath(path, p)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = lp.x
                startY = lp.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (abs(dx) > 8 * d || abs(dy) > 8 * d) moved = true
                if (moved) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    val dm = ctx.resources.displayMetrics
                    Cfg.prefs(ctx).edit()
                        .putFloat("ctl_x", lp.x.toFloat() / dm.widthPixels)
                        .putFloat("ctl_y", lp.y.toFloat() / dm.heightPixels)
                        .apply()
                } else {
                    onToggle()
                }
            }
        }
        return true
    }
}
