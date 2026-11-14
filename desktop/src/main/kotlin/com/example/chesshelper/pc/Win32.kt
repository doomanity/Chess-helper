package com.example.chesshelper.pc

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/** the bits of windows the overlay needs: clicks pass through it, it stays on top, and screen capture skips it */
object Win32 {
    private interface User32Ex : StdCallLibrary {
        fun SetWindowDisplayAffinity(hWnd: WinDef.HWND, affinity: Int): Boolean
    }

    private val ex: User32Ex? = try {
        if (Platform.isWindows()) Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS) else null
    } catch (_: Throwable) {
        null
    }

    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x20
    private const val WS_EX_TOOLWINDOW = 0x80
    private const val WS_EX_LAYERED = 0x80000
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val WS_EX_TOPMOST = 0x8
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11

    /**
     * makes the window a pass-through overlay: mouse clicks go to the game under it, it never takes focus and has
     * no taskbar button. returns true when screen capture skips it too (windows 10 2004 and newer), so reading
     * the board never sees our own arrows
     */
    fun makeOverlay(w: Window): Boolean {
        if (!Platform.isWindows()) return false
        return try {
            val hwnd = WinDef.HWND(Native.getComponentPointer(w))
            val u = User32.INSTANCE
            val style = u.GetWindowLong(hwnd, GWL_EXSTYLE) or WS_EX_LAYERED or WS_EX_TRANSPARENT or
                WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE or WS_EX_TOPMOST
            u.SetWindowLong(hwnd, GWL_EXSTYLE, style)
            ex?.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) == true
        } catch (_: Throwable) {
            false
        }
    }
}
