package com.example.chesshelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * engine control: taps the screen for the helper (android only lets an accessibility service do that).
 * it never reads other apps, it only taps the board squares of the engine's move when you turn engine control on.
 * turn it on in the phone's settings: accessibility > chess helper engine control
 */
class TapService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** one short tap at (x, y) in screen pixels. done(true) when android played it */
    fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, TAP_MS))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, null)
        if (!ok) done(false)
    }

    companion object {
        const val TAP_MS = 40L

        /** the running service, or null when engine control isn't allowed in the phone's settings */
        @Volatile var instance: TapService? = null
            private set
    }
}
