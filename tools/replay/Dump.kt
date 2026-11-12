import com.example.chesshelper.BoardVision
import java.io.File
import javax.imageio.ImageIO

/** what the board reader sees on one frame: board box, then per square . / W / B, and tone / dot / fill */
fun main(args: Array<String>) {
    val img = ImageIO.read(File(args[0]))
    val w = img.width; val h = img.height
    val px = img.getRGB(0, 0, w, h, null, 0, w)
    val g = BoardVision.findBoard(px, w, h, null) ?: run { println("board not found"); return }
    println("board x=${g.left} y=${g.top} size=${g.size}")
    val reads = BoardVision.readSquares(px, w, g)
    val cls = BoardVision.classify(reads)
    for (r in 0 until 8) {
        val sb = StringBuilder()
        for (c in 0 until 8) {
            val i = r * 8 + c
            val ch = when (cls?.get(i)) { 1 -> 'W'; 2 -> 'B'; 0 -> '.'; else -> '?' }
            val rd = reads[i]
            sb.append(String.format("%c(%s t%3d f%.2f bg%06x) ", ch, if (rd.dot) "d" else if (rd.occupied) "o" else "-", rd.tone, rd.fill, rd.bg and 0xFFFFFF))
        }
        println(sb)
    }
}
