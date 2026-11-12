package com.example.chesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import java.io.File

/**
 * the brain of the app. runs in the background, in real time and without the AI:
 * look at the screen a few times a second -> find the board and see which squares have white / black pieces
 * -> follow the game with the rules of chess (and the piece shapes it learned) -> the engines pick and grade moves
 * -> draw them on the overlay. haiku is only asked when the game doesn't start from the normal position,
 * or when the app is completely lost and can't recognize the pieces itself.
 * the side bar (an invisible handle on the screen edge) opens a quick menu to change settings while playing.
 */
class ChessService : Service(), SideBarHost, SharedPreferences.OnSharedPreferenceChangeListener {
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: BoardOverlay? = null
    private var sideBar: SideBar? = null
    private var advisor: Advisor? = null
    private var stockfish: UciEngine? = null
    private var remote: RemoteEngine? = null
    @Volatile private var illusion: IllusionEngine? = null
    private var restarts = 0

    // engine control: the engine plays your moves by tapping the board (against computer bots)
    @Volatile private var autoPlay = false
    private var controlBtn: ControlButton? = null
    private var autoFen: String? = null
    private var autoTries = 0
    private var shownBest: String? = null      // the move engine control plays in the position shown
    @Volatile private var autoRetryAt = 0L
    private var worker: Thread? = null
    @Volatile private var running = false

    private var wide: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var screenW = 0
    private var screenH = 0

    // settings, and requests from the side menu (set on the main thread, handled by the worker)
    @Volatile private var cfg: Cfg? = null
    @Volatile private var engineDirty = false
    @Volatile private var wantRethink = false
    @Volatile private var wantSave = false
    @Volatile private var wantNewGame = false
    @Volatile private var wantRead = false
    @Volatile private var panelOpen = false
    @Volatile private var savesLeft = 3
    @Volatile private var lastSaid = ""
    @Volatile private var paused = false

    // what we know about the screen
    private var geom: BoardGeom? = null           // the board, in screen pixels
    private var region = IntArray(0)              // pixels of the board area (reused every frame)
    @Volatile private var prevThumb: IntArray? = null
    private var lastCls: IntArray? = null         // piece colors at the last frame we processed
    private var overlayShown = false
    private var overlaySquares = IntArray(0)      // screen squares our highlights start or end on
    private var overlayCrossed = IntArray(0)      // squares our arrows only pass over
    private var shownFen: String? = null
    private var shownModel: OverlayModel? = null
    @Volatile private var lastModel: OverlayModel? = null // what is on the screen now (our move or the bot's guess)
    private var aiCalls = 0
    private var aiBlockedUntil = 0L
    private var engineName = "stockfish"
    private var tracker: Tracker? = null
    private var savedLogVersion = -1 // the game record (for the review screen) that was last saved

    // live accuracy: the engine score of every position we saw (white's side), and the move waiting for its score
    private val accuracy = Accuracy()
    @Volatile private var accLine: String? = null
    private val evals = HashMap<String, Int>()
    private var lastPosKey: String? = null
    private var pendingAcc: Triple<String, String, Boolean>? = null // position before, position after, mover is white

    // the bot's guessed move, and our answer worked out early for the position it leads to
    // your answers worked out ahead of time, by position (the premoves and the deep line). the oldest go when full
    private val ready = object : LinkedHashMap<String, Advice>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Advice>?) = size > READY_MAX
    }
    // the bot's likely moves, by position (the deep line and the bot's turn share them)
    private val guessCache = object : LinkedHashMap<String, List<Pair<String, Float?>>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, Float?>>>?) = size > READY_MAX
    }
    // positions remembered across games (on the phone)
    private var memory: AdviceMemory? = null
    @Volatile private var wantForget = false
    private var savedShapes = -1

    /** the piece shapes, saved per board look (the two square colors), so a later session reads mid-game at once */
    private fun shapesFile(): File = File(filesDir, "piece_shapes.txt")

    private fun loadShapes(tr: Tracker) {
        try {
            val f = shapesFile()
            if (f.exists()) tr.templates.decode(f.readText())
        } catch (_: Exception) {
        }
        savedShapes = tr.templates.version
    }

    private fun saveShapes(tr: Tracker) {
        savedShapes = tr.templates.version
        try {
            val f = shapesFile()
            val tmp = File(filesDir, "piece_shapes.tmp")
            tmp.writeText(tr.templates.encode())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
    // the position being worked on, and the position the picture on screen belongs to
    private var drawingFen: String? = null
    private var modelFen: String? = null
    // the deep line: positions to work on in spare time, nearest first
    private class AheadJob(val fen: String, val ply: Int, val botTurn: Boolean)
    private val ahead = ArrayDeque<AheadJob>()
    private val aheadSeen = HashSet<String>()
    private var aheadDone = 0
    private var predShownKey: String? = null
    private var predModel: OverlayModel? = null

    // live move labels (the badge on the square the last move went to)
    private var review: LiveReview? = null
    private var book: OpeningBook? = null
    private var lastFen: String? = null                          // the position before the newest one
    private var pendingJudge: Pair<String, String>? = null       // (position before, move) waiting for its label
    private var badgeCache: Pair<String, ReviewedMove>? = null   // position key the label belongs to, the label
    private var badgeNear = IntArray(0)                          // squares around the badge, busy while it glows
    @Volatile private var badgeNearUntil = 0L
    private var badgeIdx = -1                                    // the screen square the badge sits on

    // reading the board again: after a promotion (the piece is checked a few more times), and when the board
    // changed while we were busy drawing (the bot moved fast)
    @Volatile private var recheckAt = 0L
    private var promoLooks = 0
    private var forcedReads = 0

    // the match history: which game the record belongs to
    private var histEpoch = -1
    private var histId = 0L
    // the labels of your moves in this game (for the history: how many brilliant moves, and which)
    private val gameLabels = HashMap<String, Int>()
    private val gameHighlights = ArrayList<Highlight>()
    private var pendingPly = 0

    // touch a piece: its safe squares. lastReads = the last clean read of the board (to see a square light up)
    private var lastReads: Array<SquareRead>? = null
    private var myTurnShown = false
    private var touchSq: String? = null
    private var touchIdx = -1
    private var touchMarks = IntArray(0)

    // the score before the bot moved (white's view), to see a big chance after its move
    private var botTurnEval: Int? = null

    @Volatile private var dropOnResume = false
    private var settleAt = 0L // when what we last drew is fully on the screen
    private var forcedClear = false

    private enum class Mode { NORMAL, RETHINK, SAVE }

    override fun onBind(intent: Intent?): IBinder? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "PAUSE") {
            if (running) onPauseToggle()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForeground(1, buildNotification("starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            setup(code, data)
        } catch (e: Exception) {
            toast("start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        active = true
        worker = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun setup(code: Int, data: Intent) {
        wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()
        val c = Cfg.load(this)
        cfg = c
        savesLeft = c.savesPerGame

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        projection = p

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vdisplay = p.createVirtualDisplay(
            "chess", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        val ov = BoardOverlay(this)
        ov.setLook(lookOf(c))
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(ov, lp)
        overlay = ov

        if (c.sidebar) {
            val sb = SideBar(this, wm, this)
            sb.show()
            sideBar = sb
        }
        if (c.controlButton) {
            controlBtn = ControlButton(this, wm) { toggleAuto() }.also { it.show() }
        }
        Cfg.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    private fun lookOf(c: Cfg) = OverlayLook(c.style, Theme.accent(this, c.theme), c.anim, c.animSpeed, c.arrowSize, c.cardSize, c.opacity)

    // ---------------------------------------------------------------- settings and the side menu

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        val c = Cfg.load(this)
        cfg = c
        if (key == "ctl_x" || key == "ctl_y") return // the floating button was moved
        if (key == "forget_memory") {
            wantForget = true // the worker clears it (the memory belongs to its thread)
            return
        }
        if (key == "control_button") {
            if (c.controlButton && controlBtn == null) {
                controlBtn = ControlButton(this, wm) { toggleAuto() }.also { it.show(); it.playing = autoPlay }
            } else if (!c.controlButton) {
                stopAuto("engine control button hidden: you play")
                controlBtn?.remove()
                controlBtn = null
            }
            return
        }
        if (key != null && key in Cfg.LOOK_KEYS) {
            overlay?.setLook(lookOf(c))
            lastModel?.let { if (overlayShown && !paused) overlay?.setModel(it) }
            if (key == "sidebar" || key == "theme" || key == "handle_side" || key == "handle_mode" ||
                key == "handle_size" || key == "handle_idle") {
                val sb = sideBar
                if (c.sidebar && sb == null) {
                    sideBar = SideBar(this, wm, this).also { it.show() }
                } else {
                    sb?.refresh()
                    if (!c.sidebar) sideBar = null
                }
            }
        } else {
            engineDirty = true // the worker applies it (the engines belong to its thread)
        }
    }

    override fun onRethink() { wantRethink = true }

    override fun onMaxPower() {
        if (savesLeft == 0) toast("no max power saves left this game") else wantSave = true
    }

    override fun onNewGame() { wantNewGame = true }

    override fun onStopHelper() { main.post { stopSelf() } }

    /** opens the game review on the game that is being played right now */
    override fun onReview() {
        main.post {
            try {
                val i = Intent(this, ReviewActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra("live", true)
                startActivity(i)
            } catch (e: Exception) {
                toast("can't open the review: ${e.message}")
            }
        }
    }

    /** the floating button: give the engine control, or take it back */
    private fun toggleAuto() {
        if (!autoPlay && TapService.instance == null) {
            toast("allow engine control first: settings > accessibility > chess helper engine control")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            return
        }
        autoPlay = !autoPlay
        autoFen = null
        autoTries = 0
        controlBtn?.playing = autoPlay
        toast(if (autoPlay) "engine plays your moves (vs computer bots only)" else "you play again")
        if (autoPlay) {
            prevThumb = null
            wantRead = true
        }
    }

    private fun stopAuto(why: String) {
        if (!autoPlay) return
        autoPlay = false
        autoRetryAt = 0L
        main.post {
            controlBtn?.playing = false
            toast(why)
        }
    }

    /**
     * engine control: plays mv by tapping its from square, then its to square (and the piece in the promotion
     * picker: queen, knight, rook, bishop from the promotion square down). if the board doesn't change, it tries
     * again a couple of times, then gives control back
     */
    private fun playForMe(mv: String, fen: String, g: BoardGeom, wb: Boolean) {
        val svc = TapService.instance ?: return stopAuto("engine control is off in the phone's settings, you play again")
        if (autoFen == fen) {
            autoTries++
            if (autoTries > 2) return stopAuto("engine control couldn't play $mv, you play again")
        } else {
            autoFen = fen
            autoTries = 0
        }
        val fr = squareRect(g, wb, mv.substring(0, 2)) ?: return
        val to = squareRect(g, wb, mv.substring(2, 4)) ?: return
        // the wait before tapping you set (0 = none), a little uneven so it isn't robotic, and the chance to stop
        val pause = cfg?.autoPauseMs ?: 0
        if (pause > 0) Thread.sleep(pause + (Math.random() * minOf(150, pause / 2 + 1)).toLong())
        if (!autoPlay || paused || boardMoved()) return
        if (!tapAndWait(svc, fr.centerX(), fr.centerY())) return stopAuto("engine control tap failed, you play again")
        Thread.sleep(70)
        tapAndWait(svc, to.centerX(), to.centerY())
        if (mv.length == 5) {
            // the promotion picker opens on the promotion square: queen there, then knight, rook, bishop below it
            Thread.sleep(300)
            val k = "qnrb".indexOf(mv[4]).coerceAtLeast(0)
            tapAndWait(svc, to.centerX(), to.centerY() + k * to.height())
        }
        autoRetryAt = SystemClock.elapsedRealtime() + 2500 // if nothing happened by then, look again
    }

    private fun tapAndWait(svc: TapService, x: Float, y: Float): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        main.post {
            svc.tap(x, y) { r ->
                ok = r
                latch.countDown()
            }
        }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ok
    }

    override fun onPauseToggle() {
        paused = !paused
        if (paused) stopAuto("paused: you play again")
        // forget everything drawn before: after a pause the screen may show another game, and an old picture
        // must never come back (the worker drops its cached drawings too, see dropDrawings)
        dropOnResume = true
        if (!paused) {
            prevThumb = null
            wantRead = true
        }
        val text = if (paused) "paused · tap resume to go on" else "resumed"
        lastSaid = ""
        say(text)
        main.post { sideBar?.refresh() }
    }

    override fun isPaused(): Boolean = paused

    override fun onPanelOpen(open: Boolean) {
        panelOpen = open
        if (!open) {
            prevThumb = null
            wantRead = true
        }
    }

    override fun status(): String {
        val acc = if (cfg?.showAcc != false) accLine?.let { "\naccuracy: $it" } ?: "" else ""
        return "$engineName · ${lastSaid.ifEmpty { "starting" }}$acc"
    }

    override fun savesLeft(): Int = savesLeft

    override fun boardRect(): RectF? = geom?.let { RectF(it.left.toFloat(), it.top.toFloat(), (it.left + it.size).toFloat(), (it.top + it.size).toFloat()) }

    /** applies changed settings on the worker thread */
    private fun applyEngineSettings() {
        val c = Cfg.load(this)
        cfg = c
        val sf = stockfish ?: return
        if (sf.elo != c.elo) sf.configure(c.elo)
        remote?.close()
        remote = if (c.useMaia && c.maiaHost.isNotEmpty()) RemoteEngine(c.maiaHost, c.maiaModel, c.maiaElo, 0, c.maiaTemp) else null
        // illusion (brody's engine) runs on the phone when any of its jobs is on
        if (c.needIllusion && illusion == null) {
            illusion = try {
                IllusionEngine(File(applicationInfo.nativeLibraryDir, "libillusion.so").absolutePath)
            } catch (e: Exception) {
                toast("illusion engine missing in this build, using stockfish")
                null
            }
        } else if (!c.needIllusion) {
            illusion?.close()
            illusion = null
        }
        illusion?.let {
            it.humanMode = c.illusionHuman
            it.trapElo = c.maiaElo
            it.aggression = c.illusionAggression
            it.contempt = c.illusionContempt
            it.tolerance = c.illusionTolerance
            it.hunt = c.illusionHunt
            it.thinkMs = c.thinkMs
        }
        val ill = if (c.useIllusion) illusion else null
        engineName = if (ill != null) "illusion" + (if (c.illusionHuman) " · human vs ${c.maiaElo}" else "")
            else if (remote != null) "maia3 ${c.maiaElo}" + (if (c.maiaHuman > 0) " · human ${c.maiaHuman}%" else "")
            else if (c.elo < UciEngine.MAX_ELO) "stockfish ${c.elo}" else "stockfish"
        ready.clear()
        guessCache.clear()
        ahead.clear()
        predShownKey = null
        advisor = Advisor(sf, ill ?: remote)
        tracker?.iAmWhite = c.iAmWhite
        tracker?.autoColor = c.autoColor
        shownFen = null
        wantRead = true
    }

    // ---------------------------------------------------------------- screen capture

    /** the newest picture of the screen (the same bitmap object every time, so don't keep it) */
    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                val plane = img.planes[0]
                val rowPad = plane.rowStride - plane.pixelStride * screenW
                val bw = screenW + rowPad / plane.pixelStride
                var b = wide
                if (b == null || b.width != bw || b.height != screenH) {
                    b?.recycle()
                    b = Bitmap.createBitmap(bw, screenH, Bitmap.Config.ARGB_8888)
                    wide = b
                }
                plane.buffer.rewind()
                b!!.copyPixelsFromBuffer(plane.buffer)
            } finally {
                img.close()
            }
        }
        return wide
    }

    /** the board area of the screen: pixels plus where the board is inside them. abs is in screen pixels */
    private class Frame(val px: IntArray, val w: Int, val h: Int, val local: BoardGeom, val abs: BoardGeom)

    private fun regionFrame(bmp: Bitmap, g: BoardGeom): Frame? {
        if (g.left < 0 || g.top < 0 || g.left + g.size > screenW || g.top + g.size > screenH) return null
        val m = (g.size * 0.06f).toInt()
        val rx = maxOf(0, g.left - m)
        val ry = maxOf(0, g.top - m)
        val rw = minOf(screenW, g.left + g.size + m) - rx
        val rh = minOf(screenH, g.top + g.size + m) - ry
        if (rw <= 0 || rh <= 0) return null
        if (region.size != rw * rh) region = IntArray(rw * rh)
        bmp.getPixels(region, 0, rw, rx, ry, rw, rh)
        val local = BoardGeom(g.left - rx, g.top - ry, g.size)
        if (BoardVision.scoreGeom(region, rw, rh, local) < 0.7) return null
        return Frame(region, rw, rh, local, g)
    }

    /** grabs the screen and finds the board on it. null if there is no board on the screen right now */
    private fun look(): Frame? {
        val bmp = grab() ?: return null
        geom?.let { g -> regionFrame(bmp, g)?.let { return it } }
        // the board is not where it was: search the whole screen
        val px = IntArray(screenW * screenH)
        bmp.getPixels(px, 0, screenW, 0, 0, screenW, screenH)
        val g = BoardVision.findBoard(px, screenW, screenH, null)
        geom = g
        return if (g == null) null else regionFrame(bmp, g)
    }

    private fun thumbOf(f: Frame) = BoardVision.thumb(f.px, f.w, f.h, f.local)

    /** what your answers depend on: a different engine, strength or style never reuses another's answer */
    private fun adviceSig(c: Cfg): String {
        val style = if (c.useIllusion && c.illusionHuman) "|${c.illusionAggression},${c.illusionContempt},${c.illusionTolerance},${c.illusionHunt}" else ""
        return "$engineName|${c.nMoves}$style"
    }

    /** what the bot guesses depend on: maia3 at the bot's rating, illusion, or stockfish */
    private fun guessSig(c: Cfg): String = when {
        remote != null -> "maia${c.maiaElo}"
        c.illusionGuess && illusion != null -> "ill"
        else -> "sf"
    }

    /** an answer worked out before: this session first, then the phone's memory */
    private fun knownAdvice(fen: String, c: Cfg): Advice? {
        val k = Game.posKey(fen)
        ready[k]?.let { if (Game.fits(fen, it)) return it else ready.remove(k) }
        if (!c.remember) return null
        val m = memory?.advice(k, adviceSig(c)) ?: return null
        if (!Game.fits(fen, m)) return null // a saved answer that doesn't fit this position: never use it
        ready[k] = m
        return m
    }

    private fun storeAdvice(fen: String, a: Advice, c: Cfg) {
        if (a.cands.isEmpty() || !Game.fits(fen, a)) return
        val k = Game.posKey(fen)
        ready[k] = a
        if (c.remember) memory?.putAdvice(k, adviceSig(c), a)
    }

    private fun knownGuesses(fen: String, c: Cfg): List<Pair<String, Float?>>? {
        val k = Game.posKey(fen)
        val legal = Game.legalUci(fen)
        guessCache[k]?.let { g -> if (g.all { it.first in legal }) return g else guessCache.remove(k) }
        if (!c.remember) return null
        val g = memory?.guesses(k, guessSig(c)) ?: return null
        if (!g.all { it.first in legal }) return null
        guessCache[k] = g
        return g
    }

    private fun storeGuesses(fen: String, g: List<Pair<String, Float?>>, c: Cfg) {
        if (g.isEmpty()) return
        val legal = Game.legalUci(fen)
        if (!g.all { it.first in legal }) return
        val k = Game.posKey(fen)
        guessCache[k] = g
        if (c.remember) memory?.putGuesses(k, guessSig(c), g)
    }

    /** starts a new deep line from these positions (all are reached from the position on the screen) */
    private fun seedAhead(jobs: List<AheadJob>) {
        ahead.clear()
        aheadSeen.clear()
        aheadDone = 0
        for (j in jobs) if (aheadSeen.add(Game.posKey(j.fen) + j.botTurn)) ahead.addLast(j)
    }

    /**
     * one step of thinking ahead, done in spare time (the screen is calm). a bot position: its 2 likely moves (from
     * the memory or a quick search) lead to your positions. your position: your answer (from the memory or a short
     * think), and the bot position after it. nearest first, up to 10 half moves ahead
     */
    private fun aheadStep(c: Cfg) {
        val job = ahead.removeFirstOrNull() ?: return
        if (aheadDone++ >= AHEAD_JOBS || job.ply >= AHEAD_PLIES) {
            if (aheadDone >= AHEAD_JOBS) ahead.clear()
            return
        }
        val sf = stockfish ?: return
        if (job.botTurn) {
            val g = knownGuesses(job.fen, c) ?: run {
                val r = remote
                val got = try {
                    if (r != null) r.opponentGuesses(job.fen, c.maiaElo, AHEAD_GUESSES)
                    else sf.analyse(job.fen, 150, AHEAD_GUESSES).map { Pair<String, Float?>(it.move, null) }
                } catch (e: Exception) {
                    emptyList()
                }
                storeGuesses(job.fen, got, c)
                got
            }
            for ((mv, _) in g.take(AHEAD_GUESSES)) {
                val next = Game.after(job.fen, mv) ?: continue
                if (aheadSeen.add(Game.posKey(next) + false)) ahead.addLast(AheadJob(next, job.ply + 1, false))
            }
        } else {
            val adv = advisor ?: return
            val a = knownAdvice(job.fen, c) ?: run {
                val ill = illusion
                val illMs = ill?.thinkMs
                ill?.thinkMs = PREMOVE_ILLUSION_MS
                val got = try {
                    adv.full(job.fen, c.nMoves, maxOf(250, (c.thinkMs * 0.4f).toInt()), threat = false)
                } finally {
                    if (ill != null && illMs != null) ill.thinkMs = illMs
                }
                storeAdvice(job.fen, got, c)
                got
            }
            val best = a.cands.firstOrNull()?.move ?: return
            val next = Game.after(job.fen, best) ?: return
            if (aheadSeen.add(Game.posKey(next) + true)) ahead.addLast(AheadJob(next, job.ply + 1, true))
        }
    }

    /**
     * starts an engine again if its program died (android may kill a busy background program, especially with two
     * engines running, or it crashed). the board reading goes on, and the next move uses the new engine
     */
    private fun reviveEngines() {
        val c = cfg ?: Cfg.load(this)
        val sf = stockfish
        if (sf != null && !sf.alive) {
            try { sf.close() } catch (_: Exception) {}
            try {
                val n = UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
                n.configure(c.elo)
                stockfish = n
                review = LiveReview(n, book)
                restarts++
                toast("stockfish stopped, restarted it ($restarts)")
            } catch (e: Exception) {
                say("stockfish won't start again: ${e.message}")
                return
            }
            illusion?.let { if (!it.alive) { try { it.close() } catch (_: Exception) {}; illusion = null } }
            applyEngineSettings() // the advisor and illusion get the new stockfish
            return
        }
        val ill = illusion
        if (ill != null && !ill.alive) {
            try { ill.close() } catch (_: Exception) {}
            illusion = null
            restarts++
            toast("illusion stopped, restarted it ($restarts)")
            applyEngineSettings() // starts illusion again
        }
    }

    // ---------------------------------------------------------------- main loop

    private fun loop() {
        val c0 = cfg ?: Cfg.load(this)
        try {
            val sf = UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
            sf.configure(c0.elo)
            stockfish = sf
        } catch (e: Exception) {
            toast("stockfish failed to start: ${e.message}")
            main.post { stopSelf() }
            return
        }
        applyEngineSettings()
        book = try {
            assets.open("openings.tsv").bufferedReader().useLines { OpeningBook.parse(it) }
        } catch (e: Exception) {
            null
        }
        review = LiveReview(stockfish ?: return, book)
        memory = AdviceMemory(File(filesDir, "advice_memory.tsv")).also { it.load() }
        // version 1.21: answers saved by older versions may belong to the wrong position, start the memory fresh once
        val pf = Cfg.prefs(this)
        if (!pf.getBoolean("memory_wipe_v121", false)) {
            memory?.clear()
            pf.edit().putBoolean("memory_wipe_v121", true).apply()
        }
        val tr = Tracker(c0.iAmWhite, c0.myTurnAtStart)
        loadShapes(tr) // the shapes learned before: the full read works from the first look
        tr.autoColor = c0.autoColor
        tracker = tr

        var moving = false
        var lastRead = 0L
        var lastStep = Tracker.Step.UNREADABLE
        var lastCheck = 0L
        var lostBoardSince = 0L
        say("open your chess app")
        try {
            Thread.sleep(2500)
            while (running) {
                try {
                    if (engineDirty) {
                        engineDirty = false
                        applyEngineSettings()
                    }
                    reviveEngines()
                    if (wantForget) {
                        wantForget = false
                        memory?.clear()
                        ready.clear()
                        guessCache.clear()
                    }
                    if (panelOpen) {
                        // the menu covers the screen: don't read it, just wait
                        Thread.sleep(200)
                        continue
                    }
                    if (dropOnResume) {
                        dropOnResume = false
                        dropDrawings()
                    }
                    if (paused) {
                        hideMove(force = true)
                        main.post { overlay?.setDebug("paused") }
                        prevThumb = null
                        Thread.sleep(300)
                        continue
                    }
                    if (wantNewGame) {
                        wantNewGame = false
                        tr.forget()
                        savesLeft = cfg?.savesPerGame ?: 3
                        shownFen = null
                        resetGameStats()
                        hideMove()
                        wantRead = true
                        say("new game: reading the board again")
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (recheckAt in 1..now) {
                        recheckAt = 0L
                        wantRead = true
                    }
                    if (autoPlay && autoRetryAt in 1..now) {
                        // engine control tapped but the board didn't change: read again (it tries the move again)
                        autoRetryAt = 0L
                        prevThumb = null
                        wantRead = true
                    }
                    val f = look()
                    if (f == null) {
                        if (lostBoardSince == 0L) lostBoardSince = now
                        if (now - lostBoardSince > 1200) hideMove(force = true)
                        say("looking for the chess board...")
                        prevThumb = null
                        geom = null
                        Thread.sleep(500)
                        continue
                    }
                    lostBoardSince = 0L

                    if (wantRethink || wantSave) {
                        val mode = if (wantSave) Mode.SAVE else Mode.RETHINK
                        wantRethink = false
                        wantSave = false
                        shownFen = null
                        lastRead = now
                        lastStep = process(f, tr, now, mode)
                        continue
                    }

                    val thumb = thumbOf(f)
                    val prev = prevThumb
                    prevThumb = thumb

                    if (prev != null && BoardVision.changed(prev, thumb)) {
                        if (!moving) {
                            // the first sign of a change. hint dots or a selected piece don't change the position
                            if (justCosmetic(f)) {
                                // a piece touched (hint dots, a lit square): show where it is safe
                                try {
                                    watchTouch(f)
                                } catch (e: Exception) {
                                }
                                Thread.sleep(POLL)
                                continue
                            }
                            moving = true
                            hideMove()
                        }
                        Thread.sleep(POLL)
                        continue
                    }

                    // the picture is calm. read it when something moved, or again while we can't make sense of it
                    var due = moving || prev == null || wantRead ||
                        (lastStep == Tracker.Step.UNREADABLE && now - lastRead >= 700) ||
                        (lastStep == Tracker.Step.HOLD && now - lastRead >= 3000)
                    // the board check: every few seconds compare the squares themselves with the last read (not just
                    // the small picture), so a change too small to notice still gets read
                    if (!due && now - lastCheck >= CHECK_MS && lastCls != null) {
                        lastCheck = now
                        if (!justCosmetic(f)) due = true
                    }
                    if (!due) {
                        // spare time: think ahead (one position at a time, so a move on the board is seen quickly)
                        val cc = cfg
                        if (cc != null && cc.thinkAhead && ahead.isNotEmpty() && !paused && !panelOpen) {
                            try {
                                aheadStep(cc)
                            } catch (e: Exception) {
                                if (e is InterruptedException) throw e
                                ahead.clear()
                            }
                        } else {
                            Thread.sleep(POLL)
                        }
                        continue
                    }
                    moving = false
                    wantRead = false
                    lastRead = now
                    lastStep = process(f, tr, now, Mode.NORMAL)
                    Thread.sleep(POLL)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (stockfish?.alive == false || illusion?.alive == false) {
                        // an engine died in the middle of a move: start it again and read the board again
                        say("an engine stopped, restarting it...")
                        reviveEngines()
                        prevThumb = null
                        wantRead = true
                    } else {
                        say("error: ${e.message}")
                        toast("error: ${e.message}")
                    }
                    Thread.sleep(800)
                }
            }
        } catch (e: InterruptedException) {
            // service is stopping
        }
    }

    /** true if the only changes are the ones that don't matter: hint dots, a selected piece, our own drawing */
    private fun justCosmetic(f: Frame): Boolean {
        val last = lastCls ?: return false
        val cls = BoardVision.classify(BoardVision.readSquares(f.px, f.w, f.local)) ?: return false
        val glowing = SystemClock.elapsedRealtime() < badgeNearUntil
        for (i in 0 until 64) {
            if (i in overlayCrossed) continue // our arrows pass over these, we can't read them now
            if (i in touchMarks) continue // our safe / unsafe dots for the touched piece
            if (glowing && i in badgeNear) continue // the glow of a brilliant move spills over these
            if (i in overlaySquares) {
                // under our highlight the color can look different, but a piece appearing or leaving is real
                if ((cls[i] != BoardVision.EMPTY) != (last[i] != BoardVision.EMPTY)) return false
            } else if (cls[i] != last[i]) {
                return false
            }
        }
        return true
    }

    /** reads the board in this calm frame, follows the game, and shows the best moves if it is our turn */
    private fun process(first: Frame, tracker: Tracker, now: Long, mode: Mode): Tracker.Step {
        val c = cfg ?: Cfg.load(this)
        var f = first
        if (overlayShown) {
            // our own drawing would be part of the picture
            hideMove()
            Thread.sleep(180)
            f = look() ?: return Tracker.Step.UNREADABLE
        }
        val reads = BoardVision.readSquares(f.px, f.w, f.local)
        val cls = BoardVision.classify(reads)
        if (cls == null) {
            say("can't tell the pieces apart on this screen")
            return Tracker.Step.UNREADABLE
        }
        lastCls = cls
        lastReads = reads
        val wasMyTurnShown = myTurnShown
        myTurnShown = false
        var step = tracker.update(cls, reads, now)
        if (step == Tracker.Step.STARTED) savesLeft = c.savesPerGame
        if (tracker.templates.version != savedShapes) saveShapes(tracker)

        // without the AI (the default): the board is read on the phone only, from the piece shapes it learned.
        // stuck: say what helps and keep looking (every read tries the shapes again)
        val askAi = c.useAi && c.apiKey.isNotBlank()
        if (!askAi && (step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC)) {
            say(if (tracker.templates.known < 10) "show the start position once (a new game) so I learn this board's pieces"
                else "can't follow this position yet, looking again...")
            debugLine(tracker, step)
            return Tracker.Step.UNREADABLE
        }
        if (askAi && (step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC || (tracker.needsVerify && step != Tracker.Step.UNREADABLE))) {
            val starting = step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC
            if (now < aiBlockedUntil) {
                say("waiting a bit before asking haiku again")
                return Tracker.Step.UNREADABLE
            }
            say(if (starting) "reading the board with haiku..." else "checking the promoted piece...")
            val shot = Bitmap.createBitmap(f.px, 0, f.w, f.w, f.h, Bitmap.Config.ARGB_8888)
            var read: AiRead? = null
            aiCalls++
            for (i in 1..2) {
                read = Haiku.read(c.apiKey, c.model, shot)
                if (read != null || Haiku.lastError.isNotEmpty()) break
            }
            shot.recycle()
            if (read == null) {
                val msg = if (Haiku.lastError.isNotEmpty()) Haiku.lastError else "couldn't read the board. " + HaikuParse.lastDebug
                say(msg)
                toast(msg)
                aiBlockedUntil = SystemClock.elapsedRealtime() + 8000
                return Tracker.Step.UNREADABLE
            }
            if (starting) {
                if (step == Tracker.Step.RESYNC) tracker.forget()
                if (!tracker.supplyGrid(read.grid, read.bottomWhite, cls, reads)) {
                    say("haiku's board didn't match the screen, trying again")
                    aiBlockedUntil = SystemClock.elapsedRealtime() + 5000
                    return Tracker.Step.UNREADABLE
                }
                step = Tracker.Step.STARTED
                savesLeft = c.savesPerGame
            } else {
                tracker.verifyWith(read.grid, cls, reads)
            }
        } else if (step == Tracker.Step.UNREADABLE) {
            say("can't follow what's on the board, looking again...")
            debugLine(tracker, step)
            return step
        } else if (step == Tracker.Step.HOLD) {
            // your premove drawn early, or a piece in your hand: the position didn't change, put the picture back
            say("waiting for the bot (premove) or your move...")
            debugLine(tracker, step)
            // only the picture of this very position (never an older game's arrows)
            if (modelFen != null && modelFen == tracker.board?.fen) lastModel?.let { showModel(it, restart = false) }
            myTurnShown = wasMyTurnShown
            return step
        }

        debugLine(tracker, step)
        val b = tracker.board ?: return step
        drawingFen = b.fen
        if (tracker.logVersion != savedLogVersion) {
            // keep a record of the game so the review screen can open it, and in the match history
            savedLogVersion = tracker.logVersion
            GameLog.save(this, tracker.startFen, tracker.playedMoves, tracker.meWhite())
            saveHistory(tracker, b)
        }
        // a promoted piece is checked again on the next few reads (the first look can catch the piece picker
        // or the animation), so ask for a fresh read soon even if the screen stays still
        if (tracker.promoUnsure && promoLooks < 4) {
            promoLooks++
            recheckAt = SystemClock.elapsedRealtime() + 600
        } else if (!tracker.promoUnsure) {
            promoLooks = 0
        }
        if (step == Tracker.Step.MOVED || step == Tracker.Step.STARTED || step == Tracker.Step.RESET) {
            forcedReads = 0
            autoRetryAt = 0L // engine control's move went through
        }
        val wb = tracker.whiteBottom ?: c.iAmWhite
        val fen = b.fen
        val key = Game.posKey(fen)
        if (step == Tracker.Step.STARTED) {
            resetGameStats()
            review?.newGame(tracker.startFen)
        }
        if (step == Tracker.Step.RESET) review?.broken()
        if (key != lastPosKey) {
            // a new position: if exactly one move was played, its accuracy is known once this position has a score
            val before = lastPosKey
            pendingAcc = if (before != null && step == Tracker.Step.MOVED && tracker.lastPlies == 1)
                Triple(before, key, b.sideToMove == Side.BLACK) else null
            lastPosKey = key
            // exactly one move was played: it gets a label once the engine has looked at both positions
            val prevFen = lastFen
            val last = tracker.playedMoves.lastOrNull()
            if (prevFen != null && last != null && step == Tracker.Step.MOVED && tracker.lastPlies == 1) {
                pendingJudge = Pair(prevFen, last)
                pendingPly = tracker.playedMoves.size
            } else {
                pendingJudge = null
                badgeCache = null
                // the very first position of a game has no move before it; any other jump means we missed a move
                if (prevFen != null) review?.broken()
            }
            lastFen = fen
        }
        val g = f.abs
        val myTurn = (b.sideToMove == Side.WHITE) == tracker.meWhite()
        if (!myTurn) {
            if (mode != Mode.NORMAL) toast("it's the opponent's turn")
            shownFen = null
            botTurn(fen, key, g, wb, c, mode)
            return step
        }

        val cached = shownModel
        if (mode == Mode.NORMAL && shownFen == fen && cached != null) {
            val best = shownBest
            if (autoPlay && best != null) {
                // tap first, the drawing settles while the move plays
                showModel(cached, wait = false)
                playForMe(best, fen, g, wb)
                settleLater()
                return step
            }
            showModel(cached)
            runJudge(g, wb, fen, key, c)
            return step
        }
        val adv = advisor ?: return step
        var quickShown = false
        // the bot played the move we guessed: the answer is already worked out
        val early = if (mode == Mode.NORMAL) knownAdvice(fen, c) else null
        val advice = if (early != null) early else {
            say(when (mode) {
                Mode.SAVE -> "max power: thinking hard..."
                Mode.RETHINK -> "thinking again..."
                else -> "thinking..."
            })
            // engine control has its own short think; the threat and the bot's plan come after the arrows are up
            val fast = autoPlay && mode == Mode.NORMAL
            val ms = if (mode == Mode.RETHINK) (c.thinkMs * 2.5f).toInt() else if (fast) c.autoThinkMs else c.thinkMs
            // a quick first look (stockfish, about 150 ms) is drawn at once, the full think replaces it
            if (mode == Mode.NORMAL && !fast && ms > QUICK_MS + 150) {
                try {
                    val q = adv.full(fen, c.nMoves, QUICK_MS, threat = false, quick = true)
                    if (q.cands.isNotEmpty()) {
                        showQuick(q, fen, g, wb, c)
                        quickShown = true
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                }
            }
            val myIll = illusion
            val illMs = myIll?.thinkMs
            if (fast) myIll?.thinkMs = c.autoThinkMs
            val a = try {
                adv.full(fen, c.nMoves, ms, threat = false, maxPower = mode == Mode.SAVE)
            } finally {
                if (myIll != null && illMs != null) myIll.thinkMs = illMs
            }
            adv.listerError?.let { toast(if (c.useIllusion) "illusion: $it, using stockfish" else "maia3 pc not reachable ($it), using stockfish") }
            if (mode == Mode.NORMAL) storeAdvice(fen, a, c)
            a
        }
        if (advice.cands.isEmpty()) {
            say("no move found, game over?")
            runJudge(g, wb, fen, key, c)
            return step
        }
        if (mode == Mode.SAVE && savesLeft > 0) savesLeft--
        noteEval(key, advice.evalWhite)

        fun rect(sq: String) = squareRect(g, wb, sq)
        val moves = ArrayList<DrawMove>()
        for (cand in advice.cands) {
            val fr = rect(cand.move.substring(0, 2)) ?: continue
            val to = rect(cand.move.substring(2, 4)) ?: continue
            // illusion's own pick is tagged: ✦ (its move) or ⚑ (a trap for the opponent's rating)
            val ill = illusion
            val illPick = c.useIllusion && ill != null && early == null && cand.move == ill.lastPick
            val bril = illPick && ill!!.lastWasBrilliant && sane(cand)
            val tag = if (illPick && !bril) { if (ill!!.lastWasTrap) " ⚑" else " ✦" } else ""
            moves.add(DrawMove(fr, to, cand.grade, cand.rank, Game.san(fen, cand.move) + tag, cand.text, cand.star, bril))
        }
        val line = ArrayList<LineStep>()
        if (c.showLine && advice.cands.firstOrNull()?.move == advice.lineFor) {
            for ((i, mv) in advice.line.withIndex()) {
                val fr = rect(mv.substring(0, 2)) ?: break
                val to = rect(mv.substring(2, 4)) ?: break
                line.add(LineStep(fr, to, i + 1, i % 2 == 1))
            }
        }
        val th = if (c.showThreat) advice.threat else null
        val plan = ArrayList<LineStep>()
        if (c.botPlan) {
            for ((i, mv) in advice.plan.withIndex()) {
                val fr = rect(mv.substring(0, 2)) ?: break
                val to = rect(mv.substring(2, 4)) ?: break
                plan.add(LineStep(fr, to, i + 1, false))
            }
        }
        val hanging = if (c.hanging) {
            try {
                Safety.hanging(fen).mapNotNull { rect(it) }
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
        val alert = criticalOf(advice, tracker.meWhite(), c)
        botTurnEval = null
        val title = when {
            mode == Mode.SAVE -> "max power" + (if (savesLeft >= 0) " · $savesLeft left" else "")
            early != null -> "$engineName · ready early"
            else -> engineName
        }
        val model = OverlayModel(
            moves, boardRectOf(g), wb,
            evalShare = if (c.showEval) advice.whiteShare else null,
            evalText = if (c.showEval) advice.evalText else null,
            threatFrom = th?.let { rect(it.move.substring(0, 2)) },
            threatTo = th?.let { rect(it.move.substring(2, 4)) },
            line = line,
            title = title,
            accuracy = accText(c),
            hanging = hanging,
            plan = plan,
            alert = alert?.first,
            alertColor = alert?.second ?: 0
        )
        say("best: " + advice.cands.joinToString("  ") { "${Game.san(fen, it.move)} ${it.text}" })
        shownFen = fen
        shownModel = model
        myTurnShown = true
        shownBest = advice.cands.first().move
        if (autoPlay) {
            // engine control: tap the engine's pick (the first move on the card) at once, the drawing settles while
            // the move plays, the extras are left out
            showModel(model, wait = false)
            playForMe(advice.cands.first().move, fen, g, wb)
            settleLater()
            return step
        }
        // after the quick look: the arrows change in place, no new entrance animation
        showModel(model, restart = !quickShown)
        // the arrows are up: now the threat and the bot's plan (searched after, so the arrows come sooner)
        var onScreen = model
        if (c.showThreat || c.botPlan) onScreen = addThreatAndPlan(fen, model, advice, g, wb, c, adv)
        runJudge(g, wb, fen, key, c)
        val shownNow = if (c.illusionBrilliant) huntBrilliant(fen, onScreen, advice, g, wb, c) else onScreen
        if (c.blunderSquares) showDanger(fen, shownNow, g, wb, c)
        // while you think: work on the bot's replies to your top moves, and on from there
        if (c.thinkAhead) {
            seedAhead(advice.cands.take(AHEAD_MINE).mapNotNull { cd -> Game.after(fen, cd.move)?.let { AheadJob(it, 1, true) } })
        }
        return step
    }

    /** the quick first look: the moves and the eval bar only, drawn while the full think runs */
    private fun showQuick(a: Advice, fen: String, g: BoardGeom, wb: Boolean, c: Cfg) {
        val moves = ArrayList<DrawMove>()
        for (cand in a.cands) {
            val fr = squareRect(g, wb, cand.move.substring(0, 2)) ?: continue
            val to = squareRect(g, wb, cand.move.substring(2, 4)) ?: continue
            moves.add(DrawMove(fr, to, cand.grade, cand.rank, Game.san(fen, cand.move), cand.text, cand.star))
        }
        if (moves.isEmpty()) return
        val model = OverlayModel(
            moves, boardRectOf(g), wb,
            evalShare = if (c.showEval) a.whiteShare else null,
            evalText = if (c.showEval) a.evalText else null,
            title = "$engineName · thinking...",
            accuracy = accText(c)
        )
        showModel(model, wait = false)
    }

    /** the threat and the bot's plan for a premove answer that was shown without them */
    private fun addThreatAndPlan(fen: String, model: OverlayModel, a: Advice, g: BoardGeom, wb: Boolean, c: Cfg, adv: Advisor): OverlayModel {
        val (th, planMoves) = try {
            adv.threatAndPlan(fen, a.bestScore ?: 0)
        } catch (e: Exception) {
            return model
        }
        val thr = if (c.showThreat) th else null
        val tf = thr?.let { squareRect(g, wb, it.move.substring(0, 2)) }
        val tt = thr?.let { squareRect(g, wb, it.move.substring(2, 4)) }
        val plan = ArrayList<LineStep>()
        if (c.botPlan) for ((i, mv) in planMoves.withIndex()) {
            val fr = squareRect(g, wb, mv.substring(0, 2)) ?: break
            val to = squareRect(g, wb, mv.substring(2, 4)) ?: break
            plan.add(LineStep(fr, to, i + 1, false))
        }
        if (tf == null && plan.isEmpty()) return model
        if (lastModel !== model || boardMoved()) return model
        val next = model.withThreatPlan(tf, tt, plan)
        shownModel = next
        showModel(next, restart = false)
        return next
    }

    /**
     * the brilliant hunter: illusion (and stockfish's best move) are checked for a brilliant move here, a good
     * sacrifice, judged like the move labels. if one is found it gets a teal "!!" arrow and the card says so.
     * returns the picture now on the screen
     */
    /** a move flagged brilliant still has to be a decent move by stockfish (no "brilliant" blunders) */
    private fun sane(cand: Cand): Boolean = cand.grade != Grade.BLUNDER

    /**
     * brilliant moves among your arrows: illusion's "brilliant move !!" flag, or stockfish's check (the move labels'
     * brilliant rule: a sound sacrifice). the arrow turns blue with "!!". illusion is only asked again when it isn't
     * already the engine for your moves (then its flag came with the arrows)
     */
    private fun huntBrilliant(fen: String, model: OverlayModel, advice: Advice, g: BoardGeom, wb: Boolean, c: Cfg): OverlayModel {
        if (model.moves.any { it.brilliant }) return model // illusion flagged one of the arrows already
        val rv = review
        var found: String? = null
        val ill = illusion
        if (ill != null && !(c.useIllusion && ill.lastPick != null)) {
            try {
                val l = ill.best(fen, maxOf(300, c.thinkMs / 2), c.illusionHuman, c.maiaElo)
                if (l != null && ill.lastFlaggedBrilliant) {
                    val cand = advice.cands.firstOrNull { it.move == l.move }
                    if (cand == null || sane(cand)) found = l.move
                }
            } catch (e: Exception) {
            }
        }
        // stockfish's check on your top 3 arrows (a sacrifice that stays sound)
        if (found == null && rv != null) for (cand in advice.cands.take(3)) {
            if (!sane(cand)) continue
            if (boardMoved() || lastModel !== model) return model
            val lab = try {
                rv.peek(fen, cand.move, 250, c.reviewRating)
            } catch (e: Exception) {
                null
            }
            if (lab == Label.BRILLIANT) {
                found = cand.move
                break
            }
        }
        val mv = found ?: return model
        val fr = squareRect(g, wb, mv.substring(0, 2)) ?: return model
        val to = squareRect(g, wb, mv.substring(2, 4)) ?: return model
        if (lastModel !== model || boardMoved()) return model
        val next = model.withBrilliantMove(fr, to, Game.san(fen, mv))
        shownModel = next
        showModel(next, restart = false)
        return next
    }

    /**
     * a critical moment, from your side: a mate you can play, only one good move (every other move gives away a lot),
     * or a big chance (the bot's last move threw a lot away). null when this move is a normal one
     */
    private fun criticalOf(advice: Advice, meWhite: Boolean, c: Cfg): Pair<String, Int>? {
        if (!c.critical) return null
        val best = advice.bestScore ?: return null
        val rating = c.reviewRating
        advice.mateWhite?.let { m -> if ((m > 0) == meWhite) return Pair("mate in ${kotlin.math.abs(m)}!", BoardOverlay.ALERT_CHANCE) }
        val epBest = Labeler.ep(best, rating)
        val second = advice.secondScore
        if (second != null && epBest >= 0.3 && Labeler.lossOf(best, second, rating) >= 0.15) {
            return Pair("only one good move!", BoardOverlay.ALERT_ONLY)
        }
        val before = botTurnEval
        if (before != null) {
            val beforeMe = if (meWhite) before else -before
            if (epBest >= 0.6 && epBest - Labeler.ep(beforeMe, rating) >= 0.18) return Pair("big chance: the bot slipped!", BoardOverlay.ALERT_CHANCE)
        }
        return null
    }

    /**
     * you touched a piece in the chess app (it shows hint dots and lights up the square): every square it can go to
     * gets a green dot (safe) or a red one (the opponent wins it there). the piece is found from the lit square, or
     * from the hint dots (the one piece of yours that can reach all of them)
     */
    private fun watchTouch(f: Frame) {
        val c = cfg ?: return
        if (!c.safeSquares || !myTurnShown) return
        val tr = tracker ?: return
        val b = tr.board ?: return
        val wb = tr.whiteBottom ?: return
        val clean = lastReads ?: return
        val me = tr.meWhite()
        if ((b.sideToMove == Side.WHITE) != me) return
        val reads = BoardVision.readSquares(f.px, f.w, f.local)
        fun nameOf(i: Int): String {
            val file = if (wb) i % 8 else 7 - i % 8
            val rank = if (wb) 7 - i / 8 else i / 8
            return "${'a' + file}${'1' + rank}"
        }
        val busy = HashSet<Int>()
        for (i in overlayCrossed) busy.add(i)
        for (i in touchMarks) busy.add(i)
        val myPieces = (0 until 64).filter { i ->
            val p = b.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(nameOf(i).uppercase()))
            p != com.github.bhlangonijr.chesslib.Piece.NONE && (p.pieceSide == Side.WHITE) == me
        }
        val lit = myPieces.filter { it !in busy && BoardVision.colorDist(reads[it].bg, clean[it].bg) > 30 }
        val dots = (0 until 64).filter { reads[it].dot && it !in busy }.map { nameOf(it) }.toSet()
        val fen = b.fen
        var pick: Int? = if (lit.size == 1) lit[0] else null
        if (pick == null && dots.isNotEmpty()) {
            val fits = myPieces.filter { Safety.moveSquares(fen, nameOf(it)).containsAll(dots) }
            pick = if (fits.size == 1) fits[0] else lit.firstOrNull { it in fits }
        }
        if (pick == null) {
            // nothing touched now: take the dots away once the touched piece's square looks normal again
            val ti = touchIdx
            if (touchSq != null && (ti !in 0..63 || BoardVision.colorDist(reads[ti].bg, clean[ti].bg) <= 30)) clearTouch()
            return
        }
        val from = nameOf(pick)
        if (from == touchSq) return
        val marks = Safety.targets(fen, from)
        if (marks.isEmpty()) {
            clearTouch()
            return
        }
        val list = ArrayList<SafeMark>()
        val idx = ArrayList<Int>()
        for ((sq, safe) in marks) {
            val r = squareRect(f.abs, wb, sq) ?: continue
            list.add(SafeMark(r, safe))
            idx.add(screenIndexOf(r.centerX(), r.centerY(), f.abs))
        }
        touchSq = from
        touchIdx = pick
        touchMarks = idx.toIntArray()
        main.post { overlay?.setSafety(list) }
    }

    private fun clearTouch() {
        touchSq = null
        touchIdx = -1
        touchMarks = IntArray(0)
        main.post { overlay?.setSafety(emptyList()) }
    }

    /**
     * the blunder alert: one more search that scores every legal move, then the squares where every move of yours
     * that goes there is a blunder turn dark red with a skull. drawn after the suggestions, so they show up first
     */
    private fun showDanger(fen: String, model: OverlayModel, g: BoardGeom, wb: Boolean, c: Cfg) {
        val sf = stockfish ?: return
        val squares = try {
            Danger.squares(sf, fen, maxOf(400, c.thinkMs), c.reviewRating)
        } catch (e: Exception) {
            return
        }
        if (squares.isEmpty()) return
        if (lastModel !== model || boardMoved()) return // you already moved
        val rects = squares.mapNotNull { squareRect(g, wb, it) }
        val next = model.withDanger(rects)
        shownModel = next
        showModel(next, restart = false)
    }

    /**
     * the bot's turn: score the position (for accuracy), guess the bot's 3 likely moves and show them at once,
     * then work out your answer to each one (a premove with the same number and color as its guess), so your move
     * shows up the moment the bot plays one of them. nothing here waits for an animation: the screen gets its new
     * calm picture at the end
     */
    private fun botTurn(fen: String, key: String, g: BoardGeom, wb: Boolean, c: Cfg, mode: Mode) {
        val cachedPred = predModel
        if (mode == Mode.NORMAL && predShownKey == key && cachedPred != null) {
            showModel(cachedPred)
            runJudge(g, wb, fen, key, c)
            return
        }
        say("waiting for the opponent...")
        if (!c.predict && !c.showAcc && !c.showLabels) return
        val sf = stockfish ?: return

        // guesses worked out ahead (the deep line or the memory): no need to ask again
        val cachedG = if (c.predict) knownGuesses(fen, c)?.takeIf { it.size >= GUESSES } else null
        // maia3 on the pc guesses the bot's moves while stockfish scores the position: both at the same time
        val r = if (c.predict && cachedG == null) remote else null
        var fromPc: List<Pair<String, Float?>>? = null
        // without maia3, illusion's move becomes the first guess (it thinks while stockfish does)
        val ill = if (c.predict && cachedG == null && r == null && c.illusionGuess) illusion else null
        var fromIll: String? = null
        val illThread = ill?.let { e ->
            Thread {
                try {
                    fromIll = e.best(fen, 200)?.move
                } catch (x: Exception) {
                }
            }.also { it.isDaemon = true; it.start() }
        }
        val net = r?.let { rr ->
            Thread {
                try {
                    fromPc = rr.opponentGuesses(fen, c.maiaElo, GUESSES)
                } catch (e: Exception) {
                    // pc not reachable: stockfish's guesses below
                }
            }.also { it.isDaemon = true; it.start() }
        }
        // one search gives the score (eval bar, accuracy) and, without maia3, the guesses
        val lines = try {
            sf.analyse(fen, GUESS_MS, if (c.predict && cachedG == null) GUESSES else 1)
        } catch (e: IllegalStateException) {
            emptyList()
        }
        val top = lines.firstOrNull()
        if (top == null) {
            runJudge(g, wb, fen, key, c)
            return
        }
        val whiteToMove = fen.split(" ").getOrNull(1) != "b"
        val evalWhite = if (whiteToMove) top.score else -top.score
        noteEval(key, evalWhite)
        botTurnEval = evalWhite
        if (!c.predict) {
            runJudge(g, wb, fen, key, c)
            return
        }
        net?.join(1500)
        illThread?.join(800)
        val sfGuesses = lines.map { Pair<String, Float?>(it.move, null) }
        val illFirst = fromIll?.let { listOf(Pair<String, Float?>(it, null)) } ?: emptyList()
        val raw = cachedG ?: fromPc?.takeIf { it.isNotEmpty() } ?: (illFirst + sfGuesses)
        if (cachedG == null) storeGuesses(fen, raw.distinctBy { it.first }.take(GUESSES), c)

        val gs = ArrayList<Guess>()
        for ((mv, prob) in raw) {
            if (gs.size >= GUESSES) break
            if (gs.any { it.move == mv }) continue
            val after = Game.after(fen, mv) ?: continue
            val fr = squareRect(g, wb, mv.substring(0, 2)) ?: continue
            val to = squareRect(g, wb, mv.substring(2, 4)) ?: continue
            gs.add(Guess(mv, after, fr, to, Game.san(fen, mv), prob))
        }
        if (gs.isEmpty()) {
            runJudge(g, wb, fen, key, c)
            return
        }
        val evalInfo = Advice(emptyList(), evalWhite, top.mate?.let { if (whiteToMove) it else -it }, null, emptyList())
        // your answers: null = still thinking, "" = not shown (premove off)
        val answers = arrayOfNulls<String>(gs.size)
        val ansFrom = arrayOfNulls<RectF>(gs.size)
        val ansTo = arrayOfNulls<RectF>(gs.size)
        if (!c.premove) for (i in answers.indices) answers[i] = ""
        fun draws() = gs.mapIndexed { i, x -> GuessDraw(x.from, x.to, x.san, x.prob, answers[i], ansFrom[i], ansTo[i]) }
        val model = OverlayModel(
            emptyList(), boardRectOf(g), wb,
            evalShare = if (c.showEval) evalInfo.whiteShare else null,
            evalText = if (c.showEval) evalInfo.evalText else null,
            title = "bot's turn",
            accuracy = accText(c),
            guesses = draws()
        )
        predShownKey = key
        predModel = model
        showModel(model, wait = false)

        // your answer to each guess, most likely first, with a short think each. stop as soon as the bot moves
        // (the screen then still differs from the last calm picture, so the main loop reads it right away)
        val adv = advisor
        var shown: OverlayModel = model
        // premoves have to be quick: no threat / plan search here (that comes right after your move shows), and
        // illusion gets a short think. a failed illusion pick just falls back to stockfish's moves
        val preIll = illusion
        val illMs = preIll?.thinkMs
        preIll?.thinkMs = PREMOVE_ILLUSION_MS
        try {
        if (adv != null) for ((i, x) in gs.withIndex()) {
            if (boardMoved()) return
            say("bot will likely play ${x.san} · getting your answer ready")
            val ms = if (i == 0) maxOf(300, (c.thinkMs * 0.5f).toInt()) else maxOf(250, (c.thinkMs * 0.35f).toInt())
            val a = knownAdvice(x.after, c) ?: adv.full(x.after, c.nMoves, ms, threat = false).also { storeAdvice(x.after, it, c) }
            if (a.cands.isEmpty()) continue
            if (!c.premove) continue
            val best = a.cands.first()
            answers[i] = Game.san(x.after, best.move)
            ansFrom[i] = squareRect(g, wb, best.move.substring(0, 2))
            ansTo[i] = squareRect(g, wb, best.move.substring(2, 4))
            if (lastModel !== shown || boardMoved()) return // something else is on the screen by now
            val next = model.withGuesses(draws())
            predModel = next
            shown = next
            showModel(next, restart = false, wait = false)
        }
        } finally {
            if (preIll != null && illMs != null) preIll.thinkMs = illMs
        }
        settleLater()
        say("your answers are ready: " + gs.joinToString("  ") { it.san })
        // the label of the move you just played comes last: the premoves matter more right now
        runJudge(g, wb, fen, key, c)
        // the deep line: from each guess and your answer to it, on toward 10 half moves ahead, while the bot thinks
        if (c.thinkAhead) {
            val jobs = ArrayList<AheadJob>()
            for (x in gs) {
                val a = ready[Game.posKey(x.after)]
                val best = a?.cands?.firstOrNull()?.move
                val next = best?.let { Game.after(x.after, it) }
                if (next != null) jobs.add(AheadJob(next, 2, true)) else jobs.add(AheadJob(x.after, 1, false))
            }
            seedAhead(jobs)
        }
    }

    /** one guess of the bot's next move */
    private class Guess(val move: String, val after: String, val from: RectF, val to: RectF, val san: String, val prob: Float?)

    /** remembers the score of a position, and finishes the accuracy of the move that led to it */
    private fun noteEval(key: String, evalWhite: Int) {
        if (evals.size > 500) evals.clear()
        evals[key] = evalWhite
        val p = pendingAcc ?: return
        if (p.second != key) return
        pendingAcc = null
        val before = evals[p.first] ?: return
        val moverWhite = p.third
        accuracy.add(moverWhite == (tracker?.meWhite() ?: true), moverWhite, before, evalWhite)
        accLine = accuracy.text()
    }

    private fun accText(c: Cfg): String? = if (c.showAcc) accLine else null

    private fun resetGameStats() {
        accuracy.reset()
        accLine = null
        evals.clear()
        lastPosKey = null
        pendingAcc = null
        ready.clear()
        ahead.clear()
        predShownKey = null
        predModel = null
        lastFen = null
        pendingJudge = null
        badgeCache = null
    }

    /** labels the move that was just played (on the square it went to). analysis only, it changes nothing about the moves we pick */
    private fun runJudge(g: BoardGeom, wb: Boolean, fenAfter: String, key: String, c: Cfg) {
        if (!c.showLabels) return
        val cache = badgeCache
        if (cache != null && cache.first == key) {
            applyBadge(cache.second, g, wb)
            return
        }
        val p = pendingJudge ?: return
        pendingJudge = null
        val r = review ?: return
        val mv = try {
            r.judge(p.first, p.second, fenAfter, 250, c.reviewRating)
        } catch (e: Exception) {
            null
        } ?: return
        badgeCache = Pair(key, mv)
        applyBadge(mv, g, wb)
        // your move: count its label for the match history
        val tr = tracker ?: return
        if (mv.moverWhite == tr.meWhite()) {
            gameLabels[mv.label.name] = (gameLabels[mv.label.name] ?: 0) + 1
            if (mv.label == Label.BRILLIANT || mv.label == Label.GREAT) gameHighlights.add(Highlight(pendingPly, mv.san, mv.label.name))
            try {
                GameHistory.setLabels(this, histId, HashMap(gameLabels), ArrayList(gameHighlights), reviewed = false)
            } catch (e: Exception) {
            }
        }
    }

    private fun applyBadge(mv: ReviewedMove, g: BoardGeom, wb: Boolean) {
        val rect = squareRect(g, wb, mv.uci.substring(2, 4)) ?: return
        val idx = screenIndexOf(rect.centerX(), rect.centerY(), g)
        val glows = mv.label.glows && (cfg?.anim ?: true)
        // the badge pops out of its square a little, and a glow (brilliant, great) and its floating squares reach all
        // the squares around it for a few seconds. the board reader leaves those alone while that plays
        val near = HashSet<Int>()
        for (dr in -1..1) for (dc in -1..1) {
            val rr = idx / 8 + dr
            val cc = idx % 8 + dc
            if (rr in 0..7 && cc in 0..7) near.add(rr * 8 + cc)
        }
        badgeNear = near.toIntArray()
        badgeNearUntil = SystemClock.elapsedRealtime() + (if (glows) 3900L else 700L)
        badgeIdx = idx
        if (idx !in overlaySquares) overlaySquares += idx // a piece appearing or leaving there is still a real change
        if (paused) return
        overlayShown = true
        forcedClear = false
        main.post { overlay?.setBadge(BadgeModel(rect, mv.label)) }
    }

    /**
     * the match history: the game being played is saved after every move (moves, your side, result when the board
     * shows one, accuracy, opening). a new record starts when the game record starts over
     */
    private fun saveHistory(tracker: Tracker, b: Board) {
        if (tracker.logEpoch != histEpoch) {
            histEpoch = tracker.logEpoch
            histId = System.currentTimeMillis()
            gameLabels.clear()
            gameHighlights.clear()
        }
        val moves = tracker.playedMoves
        if (moves.size < 2) return
        try {
            val me = tracker.meWhite()
            val (result, how) = GameHistory.resultOf(b, me)
            GameHistory.put(this, SavedGame(
                id = histId, startFen = tracker.startFen, moves = ArrayList(moves), meWhite = me,
                result = result, how = how, accMe = accuracy.myScore, accOpp = accuracy.botScore,
                opening = GameHistory.openingOf(tracker.startFen, moves, book), updated = System.currentTimeMillis(),
                engine = engineName, labels = HashMap(gameLabels), highlights = ArrayList(gameHighlights)
            ))
        } catch (e: Exception) {
            // the history is a nice extra: never let it stop the helper
        }
    }

    private fun boardRectOf(g: BoardGeom) = RectF(g.left.toFloat(), g.top.toFloat(), (g.left + g.size).toFloat(), (g.top + g.size).toFloat())

    private fun debugLine(tracker: Tracker, step: Tracker.Step) {
        val text = "$step ${tracker.lastPlies}p · shapes ${tracker.templates.known}/12 · off ${tracker.lastTypeMismatch} · ai $aiCalls · $engineName"
        main.post { overlay?.setDebug(text) }
    }

    // ---------------------------------------------------------------- overlay

    /**
     * takes our drawing off the screen. force: clear it even if we think nothing is shown (pause, no board on the
     * screen), so a drawing can never get stuck
     */
    private fun hideMove(force: Boolean = false) {
        if (!overlayShown && !force) return
        if (!overlayShown) {
            if (forcedClear) return // already cleared, don't post it again every loop
            forcedClear = true
            main.post { overlay?.clearMove() }
            return
        }
        overlayShown = false
        overlaySquares = IntArray(0)
        overlayCrossed = IntArray(0)
        badgeNear = IntArray(0)
        badgeNearUntil = 0L
        badgeIdx = -1
        touchSq = null
        touchIdx = -1
        touchMarks = IntArray(0)
        main.post { overlay?.clearMove() }
    }

    /** the cached drawings (suggestions, the bot's guesses, the move label) are dropped: they get worked out again */
    private fun dropDrawings() {
        hideMove(force = true)
        shownFen = null
        shownModel = null
        predShownKey = null
        predModel = null
        lastModel = null
        badgeCache = null
    }

    /**
     * draws a picture. wait = true: wait until it is fully on the screen (and its animation is over) and take that
     * as the calm picture. wait = false: return at once, call settleLater() when done drawing
     */
    private fun showModel(m: OverlayModel, restart: Boolean = true, wait: Boolean = true) {
        if (paused) return // never draw while paused
        overlayShown = true
        forcedClear = false
        lastModel = m
        modelFen = drawingFen
        val g = BoardGeom(m.board.left.toInt(), m.board.top.toInt(), m.board.width().toInt())
        val ends = HashSet<Int>()
        val crossed = HashSet<Int>()
        val pairs = ArrayList<Pair<RectF, RectF>>()
        for (mv in m.moves) pairs.add(Pair(mv.from, mv.to))
        for (gs in m.guesses) if (gs.ansFrom != null && gs.ansTo != null) pairs.add(Pair(gs.ansFrom, gs.ansTo))
        for (s in m.line) pairs.add(Pair(s.from, s.to))
        if (m.threatFrom != null && m.threatTo != null) pairs.add(Pair(m.threatFrom, m.threatTo))
        for (gs in m.guesses) pairs.add(Pair(gs.from, gs.to))
        for (s in m.plan) pairs.add(Pair(s.from, s.to))
        if (m.brilFrom != null && m.brilTo != null) pairs.add(Pair(m.brilFrom, m.brilTo))
        for ((a, b) in pairs) {
            ends.add(screenIndexOf(a.centerX(), a.centerY(), g))
            ends.add(screenIndexOf(b.centerX(), b.centerY(), g))
            for (k in 1..15) {
                val t = k / 16f
                crossed.add(screenIndexOf(a.centerX() + (b.centerX() - a.centerX()) * t, a.centerY() + (b.centerY() - a.centerY()) * t, g))
            }
        }
        if (badgeIdx >= 0) ends.add(badgeIdx)
        for (r in m.hanging) ends.add(screenIndexOf(r.centerX(), r.centerY(), g))
        // the blunder alert squares are covered by a skull: the reader leaves them alone (your moves always start
        // on a square of your own, which is never one of them, so a move is still seen)
        for (r in m.danger) crossed.add(screenIndexOf(r.centerX(), r.centerY(), g))
        crossed.removeAll(ends)
        overlaySquares = ends.toIntArray()
        overlayCrossed = crossed.toIntArray()
        val now = SystemClock.elapsedRealtime()
        if (!restart) {
            // the same picture plus a small addition: no new entrance animation, just a short wait for it to settle
            main.post { overlay?.updateModel(m) }
            settleAt = maxOf(settleAt, now + 350)
        } else {
            main.post { overlay?.setModel(m) }
            // our drawing (and its animation) has to be on the screen before we take the calm picture,
            // so it isn't mistaken for a change on the board
            val c = cfg
            val anim = if (c != null && c.anim) ((OverlayLook.DRAW_MS + OverlayLook.GLOW_MS) / c.animSpeed).toLong() else 0L
            settleAt = now + 350 + anim
        }
        if (wait) settleLater()
    }

    /** waits until what we drew is fully on the screen, then takes the calm picture (see settle) */
    private fun settleLater() {
        val left = settleAt - SystemClock.elapsedRealtime()
        if (left > 0) Thread.sleep(left)
        settle()
    }

    /**
     * after drawing: the picture now on the screen becomes the calm one we compare against. but if the board itself
     * changed while we were busy (a fast bot, or you moved quickly), don't swallow that change: read it right away
     */
    private fun settle() {
        val f = look() ?: return
        if (forcedReads < 2 && !justCosmetic(f)) {
            forcedReads++
            prevThumb = null
            return
        }
        prevThumb = thumbOf(f)
    }

    /** true if the board changed since we last read it (our own drawing doesn't count) */
    private fun boardMoved(): Boolean {
        val f = look() ?: return true
        val p = prevThumb ?: return true
        if (!BoardVision.changed(p, thumbOf(f))) return false
        return !justCosmetic(f)
    }

    /** which screen square (row * 8 + column) a point is on */
    private fun screenIndexOf(x: Float, y: Float, g: BoardGeom): Int {
        val s = g.size / 8f
        val col = ((x - g.left) / s).toInt().coerceIn(0, 7)
        val row = ((y - g.top) / s).toInt().coerceIn(0, 7)
        return row * 8 + col
    }

    /** screen rectangle of a square like "e2", from the board we found on the screen */
    private fun squareRect(g: BoardGeom, whiteBottom: Boolean, sq: String): RectF? {
        val file = sq[0] - 'a'
        val rank = sq[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        val col = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank
        val s = g.size / 8f
        return RectF(g.left + col * s, g.top + row * s, g.left + (col + 1) * s, g.top + (row + 1) * s)
    }

    // ---------------------------------------------------------------- notifications

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chess", "chess helper", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ChessService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, ChessService::class.java).setAction("PAUSE"), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val pauseIcon = Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
        return Notification.Builder(this, "chess")
            .setContentTitle(if (paused) "chess helper · paused" else "chess helper")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_king_small)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(pauseIcon, if (paused) "resume" else "pause", pause).build())
            .addAction(Notification.Action.Builder(icon, "stop", stop).build())
            .build()
    }

    private fun say(text: String) {
        if (text == lastSaid) return
        lastSaid = text
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        /** true while the helper runs (the main screen shows it). not the loop's own "running" flag */
        @Volatile @JvmStatic var active = false
        const val GUESSES = 3 // how many of the bot's likely moves to show while it thinks
        const val PREMOVE_ILLUSION_MS = 250 // illusion's think for one premove
        const val READY_MAX = 600            // answers kept in the phone's memory while the helper runs
        const val AHEAD_PLIES = 10           // how far the deep line looks (half moves)
        const val AHEAD_GUESSES = 2          // the bot's top moves followed at every bot move in the deep line
        const val AHEAD_MINE = 3             // your top moves followed while you think
        const val AHEAD_JOBS = 120           // the most positions worked on ahead per move
        const val CHECK_MS = 2500L          // how often the board check compares the squares with the last read
        const val QUICK_MS = 150            // the quick first look on your turn, drawn before the full think
        const val GUESS_MS = 180            // stockfish's search for the score and the bot's likely moves
        private const val POLL = 120L // ms between looks at the screen
    }

    override fun onDestroy() {
        active = false
        running = false
        worker?.interrupt()
        try { Cfg.prefs(this).unregisterOnSharedPreferenceChangeListener(this) } catch (_: Exception) {}
        sideBar?.remove()
        controlBtn?.remove()
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        stockfish?.close()
        remote?.close()
        illusion?.close()
        memory?.save()
        super.onDestroy()
    }
}
