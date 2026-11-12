import com.example.chesshelper.BoardVision
import com.example.chesshelper.Tracker
import java.io.File
import javax.imageio.ImageIO

/** learn the piece shapes from a start position frame, then print what each square of another frame is read as */
fun main(args: Array<String>) {
    fun load(p: String): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(File(p)); val w = img.width; val h = img.height
        return Triple(img.getRGB(0, 0, w, h, null, 0, w), w, h)
    }
    val tr = Tracker(true, true); tr.autoColor = true
    for (p in args.dropLast(1)) {
        val (px, w, h) = load(p)
        val g = BoardVision.findBoard(px, w, h, null)!!
        val reads = BoardVision.readSquares(px, w, g)
        println("learn ${File(p).name}: ${tr.update(BoardVision.classify(reads)!!, reads, 1000)} shapes=${tr.templates.known}")
    }
    val (px, w, h) = load(args.last())
    val g = BoardVision.findBoard(px, w, h, null)!!
    val reads = BoardVision.readSquares(px, w, g)
    val cls = BoardVision.classify(reads)!!
    val seen = tr.templates.recognizeAll(reads, cls)
    val names = " PNBRQK"
    for (r in 0 until 8) println((0 until 8).joinToString(" ") { c -> val i = r * 8 + c
        if (cls[i] == 0) "." else { val t = seen[i]; val ch = if (t == 0) '?' else names[t]; if (cls[i] == 1) ch.toString() else ch.lowercase() } })
}
