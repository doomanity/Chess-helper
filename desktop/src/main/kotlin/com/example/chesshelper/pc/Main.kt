package com.example.chesshelper.pc

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import javax.swing.SwingUtilities

/** chess helper for the pc: the control window, the see-through overlay over the board, and the helper loop */
fun main() {
    // one java pixel = one screen pixel (the captured board and the drawing must line up on scaled displays)
    System.setProperty("sun.java2d.uiScale", "1")
    FlatLaf.setGlobalExtraDefaults(
        mapOf("@background" to "#0F2E25", "@foreground" to "#F0E8D5", "@accentColor" to "#D2AD5B",
            "@selectionBackground" to "#D2AD5B", "@selectionForeground" to "#16261F", "Component.arc" to "10",
            "Button.arc" to "12")
    )
    FlatDarkLaf.setup()

    val st = Settings().also { it.load() }
    val overlay = Overlay()
    lateinit var helper: Helper
    lateinit var win: ControlWindow
    SwingUtilities.invokeAndWait {
        win = ControlWindow(st) { helper.settingsChanged() }
        win.show()
    }
    overlay.show()
    helper = Helper(st, overlay) { win.status(it) }
    win.pauseButton.addActionListener {
        helper.paused = !helper.paused
        win.pauseButton.text = if (helper.paused) "resume" else "pause"
        if (helper.paused) win.status("paused")
    }
    win.newGameButton.addActionListener { helper.newGame() }
    Runtime.getRuntime().addShutdownHook(Thread { helper.stop() })
    helper.start()
}
