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
    helper = Helper(st, overlay, { win.status(it) })
    win.pauseButton.addActionListener {
        helper.paused = !helper.paused
        win.pauseButton.text = if (helper.paused) "resume" else "pause"
        if (helper.paused) win.status("paused")
    }
    win.newGameButton.addActionListener { helper.newGame() }
    win.debugButton.addActionListener {
        val dir = helper.saveDebug("saved by hand")
        if (dir != null) {
            win.status("saved in ${dir.path}")
            try { java.awt.Desktop.getDesktop().open(dir) } catch (_: Exception) {}
        }
    }
    // ---- hint: the button, or F2 from anywhere (the game keeps focus)
    win.hintButton.addActionListener { helper.hint() }
    Thread {
        var was = false
        while (true) {
            val down = Win32.keyDown(Win32.VK_F2)
            if (down && !was) helper.hint()
            was = down
            Thread.sleep(40)
        }
    }.apply { isDaemon = true; start() }

    // ---- game review: a game that ended (mate on the board, or the next game started) waits for its review
    var lastGame: GameRecord? = null
    helper.onGameOver = { rec, ended ->
        lastGame = rec
        win.reviewReady(true)
        if (ended) SwingUtilities.invokeLater { openReview(rec, helper, win) }
    }
    win.reviewButton.addActionListener {
        val done = lastGame
        val now = helper.currentGame()
        if (done != null && (now == null || now.moves.size < 2)) {
            openReview(done, helper, win)
            return@addActionListener
        }
        if (now == null && done == null) {
            win.status("no game to review yet: play one first")
            return@addActionListener
        }
        // the game on screen is still being followed: only review it once it's over (against people that's cheating)
        val options = if (done != null) arrayOf("this game, it's over", "the last finished game", "cancel")
            else arrayOf("yes, it's over", "cancel")
        val pick = javax.swing.JOptionPane.showOptionDialog(win.window,
            "review the game on screen? only do this once it's over (resigned, timed out, drawn).\n" +
                "looking at the engine during a game against people counts as cheating.",
            "Game review", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
            null, options, options.last())
        when {
            pick == 0 && now != null -> {
                try { now.savePgn() } catch (_: Exception) {}
                openReview(now, helper, win)
            }
            pick == 1 && done != null && options.size == 3 -> openReview(done, helper, win)
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { helper.stop() })
    helper.start()
}

private fun openReview(rec: GameRecord, helper: Helper, win: ControlWindow) {
    val path = helper.stockfishPath
    if (path == null) {
        win.status("the review needs stockfish: engines folder missing next to the app")
        return
    }
    win.reviewReady(false)
    ReviewWindow(rec, path).show()
}
