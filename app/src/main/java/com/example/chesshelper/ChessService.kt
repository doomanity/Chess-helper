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
    private val ready = HashMap<String, Advice>()
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
        Cfg.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    private fun lookOf(c: Cfg) = OverlayLook(c.style, Theme.accent(this, c.theme), c.anim, c.animSpeed, c.arrowSize, c.cardSize, c.opacity)

    // ---------------------------------------------------------------- settings and the side menu

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        val c = Cfg.load(this)
        cfg = c
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

    override fun onPauseToggle() {
        paused = !paused
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
            it.trapMode = c.illusionTrap
            it.trapElo = c.maiaElo
            it.thinkMs = c.thinkMs
        }
        val ill = if (c.useIllusion) illusion else null
        engineName = if (ill != null) "illusion" + (if (c.illusionTrap) " · traps vs ${c.maiaElo}" else "")
            else if (remote != null) "maia3 ${c.maiaElo}" + (if (c.maiaHuman > 0) " · human ${c.maiaHuman}%" else "")
            else if (c.elo < UciEngine.MAX_ELO) "stockfish ${c.elo}" else "stockfish"
        ready.clear()
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
        val tr = Tracker(c0.iAmWhite, c0.myTurnAtStart)
        tr.autoColor = c0.autoColor
        tracker = tr

        var moving = false
        var lastRead = 0L
        var lastStep = Tracker.Step.UNREADABLE
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
                    val due = moving || prev == null || wantRead ||
                        (lastStep == Tracker.Step.UNREADABLE && now - lastRead >= 700)
                    if (!due) {
                        Thread.sleep(POLL)
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
                    say("error: ${e.message}")
                    toast("error: ${e.message}")
                    Thread.sleep(1500)
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
        myTurnShown = false
        var step = tracker.update(cls, reads, now)
        if (step == Tracker.Step.STARTED) savesLeft = c.savesPerGame

        if (step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC || (tracker.needsVerify && step != Tracker.Step.UNREADABLE)) {
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
        }

        debugLine(tracker, step)
        val b = tracker.board ?: return step
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
        if (step == Tracker.Step.MOVED || step == Tracker.Step.STARTED || step == Tracker.Step.RESET) forcedReads = 0
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
            showModel(cached)
            runJudge(g, wb, fen, key, c)
            return step
        }
        val adv = advisor ?: return step
        // the bot played the move we guessed: the answer is already worked out
        val early = if (mode == Mode.NORMAL) ready[key] else null
        val advice = if (early != null) early else {
            say(when (mode) {
                Mode.SAVE -> "max power: thinking hard..."
                Mode.RETHINK -> "thinking again..."
                else -> "thinking..."
            })
            val ms = if (mode == Mode.RETHINK) (c.thinkMs * 2.5f).toInt() else c.thinkMs
            val a = adv.full(fen, c.nMoves, ms, threat = c.showThreat || c.botPlan, maxPower = mode == Mode.SAVE)
            adv.listerError?.let { toast(if (c.useIllusion) "illusion: $it, using stockfish" else "maia3 pc not reachable ($it), using stockfish") }
            a
        }
        ready.clear()
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
            val tag = if (c.useIllusion && ill != null && early == null && cand.move == ill.lastPick) (if (ill.lastWasTrap) " ⚑" else " ✦") else ""
            moves.add(DrawMove(fr, to, cand.grade, cand.rank, Game.san(fen, cand.move) + tag, cand.text, cand.star))
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
        showModel(model)
        myTurnShown = true
        runJudge(g, wb, fen, key, c)
        val shownNow = if (c.illusionBrilliant) huntBrilliant(fen, model, advice, g, wb, c) else model
        if (c.blunderSquares) showDanger(fen, shownNow, g, wb, c)
        return step
    }

    /**
     * the brilliant hunter: illusion (and stockfish's best move) are checked for a brilliant move here, a good
     * sacrifice, judged like the move labels. if one is found it gets a teal "!!" arrow and the card says so.
     * returns the picture now on the screen
     */
    private fun huntBrilliant(fen: String, model: OverlayModel, advice: Advice, g: BoardGeom, wb: Boolean, c: Cfg): OverlayModel {
        val ill = illusion ?: return model
        val rv = review ?: return model
        val picks = LinkedHashSet<String>()
        try {
            ill.best(fen, maxOf(400, c.thinkMs))?.let { picks.add(it.move) }
        } catch (e: Exception) {
            return model
        }
        advice.cands.firstOrNull()?.let { picks.add(it.move) }
        var found: String? = null
        for (mv in picks) {
            if (boardMoved()) return model
            val lab = try {
                rv.peek(fen, mv, 250, c.reviewRating)
            } catch (e: Exception) {
                null
            }
            if (lab == Label.BRILLIANT) {
                found = mv
                break
            }
        }
        val mv = found ?: return model
        val fr = squareRect(g, wb, mv.substring(0, 2)) ?: return model
        val to = squareRect(g, wb, mv.substring(2, 4)) ?: return model
        if (lastModel !== model || boardMoved()) return model
        val next = model.withBrilliant(fr, to, Game.san(fen, mv))
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

        // maia3 on the pc guesses the bot's moves while stockfish scores the position: both at the same time
        val r = if (c.predict) remote else null
        var fromPc: List<Pair<String, Float?>>? = null
        // without maia3, illusion's move becomes the first guess (it thinks while stockfish does)
        val ill = if (c.predict && r == null && c.illusionGuess) illusion else null
        var fromIll: String? = null
        val illThread = ill?.let { e ->
            Thread {
                try {
                    fromIll = e.best(fen, 300)?.move
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
            sf.analyse(fen, 300, if (c.predict) GUESSES else 1)
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
        net?.join(4000)
        illThread?.join(2000)
        val sfGuesses = lines.map { Pair<String, Float?>(it.move, null) }
        val illFirst = fromIll?.let { listOf(Pair<String, Float?>(it, null)) } ?: emptyList()
        val raw = fromPc?.takeIf { it.isNotEmpty() } ?: (illFirst + sfGuesses)

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
        ready.clear()
        val adv = advisor
        var shown: OverlayModel = model
        if (adv != null) for ((i, x) in gs.withIndex()) {
            if (boardMoved()) return
            say("bot will likely play ${x.san} · getting your answer ready")
            val ms = if (i == 0) maxOf(300, (c.thinkMs * 0.6f).toInt()) else maxOf(250, (c.thinkMs * 0.4f).toInt())
            val a = adv.full(x.after, c.nMoves, ms, threat = c.showThreat || c.botPlan)
            if (adv.listerError != null || a.cands.isEmpty()) continue
            ready[Game.posKey(x.after)] = a
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
        settleLater()
        say("your answers are ready: " + gs.joinToString("  ") { it.san })
        // the label of the move you just played comes last: the premoves matter more right now
        runJudge(g, wb, fen, key, c)
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
        const val GUESSES = 3 // how many of the bot's likely moves to show while it thinks
        private const val POLL = 120L // ms between looks at the screen
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try { Cfg.prefs(this).unregisterOnSharedPreferenceChangeListener(this) } catch (_: Exception) {}
        sideBar?.remove()
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        stockfish?.close()
        remote?.close()
        illusion?.close()
        super.onDestroy()
    }
}
