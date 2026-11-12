package com.example.chesshelper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build

/** the accent color: taken from the wallpaper (made brighter so it glows), or a fixed neon color */
object Theme {
    val PRESETS = linkedMapOf(
        "brass" to Color.rgb(222, 184, 98),
        "wallpaper" to 0,
        "cyan" to Color.rgb(0, 229, 255),
        "violet" to Color.rgb(179, 136, 255),
        "pink" to Color.rgb(255, 79, 216),
        "lime" to Color.rgb(178, 255, 89)
    )

    const val GOLD = 0xFFFFD54F.toInt()
    const val THREAT = 0xFFFF3D57.toInt()

    fun accent(ctx: Context, choice: String): Int {
        val fixed = PRESETS[choice]
        if (fixed != null && fixed != 0) return fixed
        return wallpaper(ctx)
    }

    private fun wallpaper(ctx: Context): Int {
        try {
            val wc = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (wc != null) return neon(wc.primaryColor.toArgb())
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return neon(ctx.getColor(android.R.color.system_accent1_300))
            } catch (_: Exception) {
            }
        }
        return Color.rgb(0, 229, 255)
    }

    /** the same hue, but bright and saturated, so it looks like a neon light */
    fun neon(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.08f) return Color.rgb(0, 229, 255) // a grey wallpaper has no color to take
        hsv[1] = maxOf(hsv[1], 0.62f)
        hsv[2] = maxOf(hsv[2], 0.96f)
        return Color.HSVToColor(hsv)
    }

    fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** a dark background with a hint of the accent */
    fun panel(accent: Int, alpha: Int = 238): Int {
        val r = (14 + Color.red(accent) * 0.07f).toInt()
        val g = (18 + Color.green(accent) * 0.07f).toInt()
        val b = (30 + Color.blue(accent) * 0.08f).toInt()
        return Color.argb(alpha, r, g, b)
    }

    /** a lighter version (the white-hot middle of a neon tube) */
    fun core(c: Int): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * 0.55f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * 0.55f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * 0.55f).toInt()
    )
}
