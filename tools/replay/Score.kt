import com.example.chesshelper.BoardGeom
import com.example.chesshelper.BoardVision
import java.io.File
import javax.imageio.ImageIO

/** the board's score at the first frame's board position, for every frame (and whether a fresh search finds it) */
fun main(args: Array<String>) {
    var g0: BoardGeom? = null
    for (f in File(args[0]).listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }) {
        val img = ImageIO.read(f); val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        if (g0 == null) g0 = BoardVision.findBoard(px, w, h, null)
        val sc = BoardVision.scoreGeom(px, w, h, g0!!)
        val fresh = BoardVision.findBoard(px, w, h, null) != null
        println("${f.name} score=${"%.2f".format(sc)} fresh=$fresh")
    }
}
