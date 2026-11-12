import com.example.chesshelper.BoardGeom
import com.example.chesshelper.BoardVision
import com.example.chesshelper.Tracker
import java.io.File
import javax.imageio.ImageIO

/**
 * replays frames of a real screen recording through the board reader (vision + tracker), like the phone does:
 * one line per frame with the tracker's step and the position it believes. args: frames dir, ms between frames
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val stepMs = args.getOrNull(1)?.toLong() ?: 500L
    val tr = Tracker(true, true)
    tr.autoColor = true
    // optional: a start position picture to learn the piece shapes from first (like the shapes saved on the phone)
    args.getOrNull(2)?.let { p ->
        val img = ImageIO.read(File(p)); val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val g = BoardVision.findBoard(px, w, h, null)!!
        val reads = BoardVision.readSquares(px, w, g)
        val shapes = Tracker(true, true)
        shapes.update(BoardVision.classify(reads)!!, reads, 1)
        tr.templates.decode(shapes.templates.encode())
    }
    var geom: BoardGeom? = null
    var clock = 1000L
    var lastFen = ""
    for (f in dir.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }) {
        val img = ImageIO.read(f)
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        clock += stepMs
        val g = BoardVision.findBoard(px, w, h, geom)
        if (g == null) { println("${f.name} board not found"); geom = null; continue }
        geom = g
        val reads = BoardVision.readSquares(px, w, g)
        val cls = BoardVision.classify(reads)
        if (cls == null) { println("${f.name} pieces not classified"); continue }
        val st = tr.update(cls, reads, clock)
        val fen = tr.board?.fen ?: "-"
        val mark = if (fen != lastFen) " *" else ""
        lastFen = fen
        println("${f.name} $st p=${tr.lastPlies} shapes=${tr.templates.known} moves=${tr.playedMoves.takeLast(3)} $fen$mark")
    }
}
