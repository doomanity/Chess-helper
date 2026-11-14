package com.example.chesshelper.pc

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** the small control window: start / pause, and the settings (clubroom look: felt green, ivory, brass) */
class ControlWindow(private val st: Settings, private val onChange: () -> Unit) {
    private val frame = JFrame("Chess helper")
    private val statusLabel = JLabel("starting...")
    val pauseButton = JButton("pause")
    val newGameButton = JButton("new game")

    private val felt = Color(0x0F2E25)
    private val cell = Color(0x173F33)
    private val ivory = Color(0xF0E8D5)
    private val dim = Color(0xADB3A6)
    private val brass = Color(0xD2AD5B)

    fun show() {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.background = felt
        root.border = BorderFactory.createEmptyBorder(14, 16, 16, 16)

        val title = JLabel("Chess helper")
        title.font = Font("Georgia", Font.BOLD, 24)
        title.foreground = ivory
        root.add(left(title))
        statusLabel.foreground = dim
        statusLabel.font = Font("SansSerif", Font.PLAIN, 12)
        root.add(left(statusLabel))
        root.add(Box.createVerticalStrut(10))

        val buttons = JPanel(GridLayout(1, 2, 8, 0))
        buttons.isOpaque = false
        for (b in listOf(pauseButton, newGameButton)) {
            b.background = brass
            b.foreground = Color(0x16261F)
            b.font = Font("SansSerif", Font.BOLD, 13)
            b.isFocusPainted = false
            buttons.add(b)
        }
        root.add(left(buttons))

        // ---- game
        val game = section(root, "Game")
        game.add(row("i play", combo(listOf("auto", "white", "black"), st.colorMode) { st.colorMode = it }))
        game.add(row("engine", combo(listOf("stockfish", "illusion"), st.engine) { st.engine = it }))
        game.add(row("moves shown", combo(listOf("1", "2", "3", "4"), "${st.lines}") { st.lines = it.toInt() }))

        // ---- illusion
        val il = section(root, "Illusion")
        il.add(row("human mode", check(st.humanMode) { st.humanMode = it }))
        il.add(slider("opponent rating", 400, 2800, st.oppElo) { st.oppElo = it })
        il.add(slider("aggression", 0, 100, st.aggression) { st.aggression = it })
        il.add(slider("contempt", 0, 200, st.contempt) { st.contempt = it })
        il.add(slider("swindle tolerance", 0, 300, st.tolerance) { st.tolerance = it })
        il.add(slider("brilliant hunt", 0, 100, st.hunt) { st.hunt = it })
        il.add(slider("think time (ms)", 100, 2000, st.illusionMs) { st.illusionMs = it })

        // ---- the bot
        val bot = section(root, "The bot's turn")
        bot.add(row("guess its moves", check(st.guess) { st.guess = it }))
        bot.add(row("guess with", combo(listOf("stockfish", "maia3"), st.guessWith) { st.guessWith = it }))
        bot.add(slider("bot rating (maia3)", 600, 2600, st.botElo) { st.botElo = it })
        val host = JTextField(st.maiaHost, 14)
        host.addActionListener { st.maiaHost = host.text.trim(); changed() }
        host.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) { st.maiaHost = host.text.trim(); changed() }
        })
        bot.add(row("maia3 server", host))

        val scroll = JScrollPane(root)
        scroll.border = null
        scroll.verticalScrollBar.unitIncrement = 16
        frame.contentPane.add(scroll, BorderLayout.CENTER)
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.size = Dimension(380, 720)
        frame.isAlwaysOnTop = false
        frame.isVisible = true
    }

    fun status(text: String) = SwingUtilities.invokeLater { statusLabel.text = text }

    private fun changed() {
        st.save()
        onChange()
    }

    private fun left(c: JComponent): JComponent {
        c.alignmentX = Component.LEFT_ALIGNMENT
        return c
    }

    private fun section(root: JPanel, name: String): JPanel {
        root.add(Box.createVerticalStrut(16))
        val h = JLabel(name)
        h.font = Font("Georgia", Font.BOLD, 17)
        h.foreground = ivory
        root.add(left(h))
        root.add(Box.createVerticalStrut(6))
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.background = cell
        box.border = BorderFactory.createEmptyBorder(6, 12, 8, 12)
        root.add(left(box))
        return box
    }

    private fun row(label: String, c: JComponent): JComponent {
        val p = JPanel(BorderLayout(8, 0))
        p.isOpaque = false
        p.border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
        val l = JLabel(label)
        l.foreground = ivory
        p.add(l, BorderLayout.WEST)
        val r = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        r.isOpaque = false
        r.add(c)
        p.add(r, BorderLayout.EAST)
        p.maximumSize = Dimension(Int.MAX_VALUE, 40)
        return p
    }

    private fun combo(items: List<String>, value: String, set: (String) -> Unit): JComboBox<String> {
        val c = JComboBox(items.toTypedArray())
        c.selectedItem = value
        c.addActionListener { set(c.selectedItem as String); changed() }
        return c
    }

    private fun check(value: Boolean, set: (Boolean) -> Unit): JCheckBox {
        val c = JCheckBox()
        c.isSelected = value
        c.isOpaque = false
        c.addActionListener { set(c.isSelected); changed() }
        return c
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, set: (Int) -> Unit): JComponent {
        val p = JPanel(BorderLayout())
        p.isOpaque = false
        p.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        val top = JPanel(BorderLayout())
        top.isOpaque = false
        val l = JLabel(label)
        l.foreground = ivory
        val v = JLabel("$value")
        v.foreground = brass
        top.add(l, BorderLayout.WEST)
        top.add(v, BorderLayout.EAST)
        val s = JSlider(min, max, value.coerceIn(min, max))
        s.isOpaque = false
        s.addChangeListener {
            v.text = "${s.value}"
            if (!s.valueIsAdjusting) { set(s.value); changed() }
        }
        p.add(top, BorderLayout.NORTH)
        p.add(s, BorderLayout.CENTER)
        p.maximumSize = Dimension(Int.MAX_VALUE, 64)
        return p
    }
}
