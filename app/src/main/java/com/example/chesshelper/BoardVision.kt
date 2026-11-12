package com.example.chesshelper

import kotlin.math.abs

/** where the 8x8 squares are on the screen, in pixels */
class BoardGeom(val left: Int, val top: Int, val size: Int) {
    val sq: Float get() = size / 8f
    override fun toString() = "board(left=$left, top=$top, size=$size)"
}

/**
 * what we see on one square: is a piece on it, how bright is it, and its shape (mask, 24x24 bits: which spots
 * differ from the empty square). hint dots that apps draw for possible moves are not pieces (dot = true).
 */
class SquareRead(val occupied: Boolean, val tone: Int, val fill: Float, val mask: LongArray? = null, val dot: Boolean = false, val bg: Int = 0)

/**
 * Finds the chessboard in a screenshot and tells which squares have a piece and what color it is.
 * No android classes in here on purpose, so it can be tested on a normal computer.
 * pixels are ARGB ints like android bitmaps: (p shr 16) and 255 = red.
 */
object BoardVision {
    const val EMPTY = 0
    const val WHITE = 1
    const val BLACK = 2

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    private fun dist(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))

    /** how different two colors are (0 = the same). a square that got highlighted is more than 30 off */
    fun colorDist(a: Int, b: Int) = dist(a, b)
    private fun luma(p: Int) = (red(p) * 299 + green(p) * 587 + blue(p) * 114) / 1000

    // ------------------------------------------------------------------ finding the board

    /** looks for the board. if hint is still a good board (same place as last time) it is returned as is */
    fun findBoard(px: IntArray, w: Int, h: Int, hint: BoardGeom? = null): BoardGeom? {
        if (hint != null && scoreGeom(px, w, h, hint) >= 0.7) return hint

        // the most common colors on screen: the two square colors are always among them
        val buckets = HashMap<Int, IntArray>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                val k = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)
                val e = buckets.getOrPut(k) { IntArray(4) }
                e[0]++
                e[1] += red(p)
                e[2] += green(p)
                e[3] += blue(p)
                x += 3
            }
            y += 3
        }
        val merged = ArrayList<IntArray>() // [count, r, g, b] sums, bins of the same color merged
        for (e in buckets.values.sortedByDescending { it[0] }) {
            val c = rgb(e[1] / e[0], e[2] / e[0], e[3] / e[0])
            val into = merged.firstOrNull { dist(rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]), c) <= 24 }
            if (into != null) {
                for (i in 0..3) into[i] += e[i]
            } else if (merged.size < 40) {
                merged.add(e.copyOf())
            }
        }
        val top = merged.sortedByDescending { it[0] }.take(8)
            .map { rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }

        var best: BoardGeom? = null
        var bestScore = 0.0
        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                if (dist(top[i], top[j]) < 30) continue
                val g = candidate(px, w, h, top[i], top[j]) ?: continue
                val an = analyze(px, w, h, g) ?: continue
                // the colors the squares really have must be the two colors we looked for,
                // otherwise this is some other squarish area that happens to look like a board
                val sameColors = (dist(an.a, top[i]) <= 40 && dist(an.b, top[j]) <= 40) ||
                    (dist(an.a, top[j]) <= 40 && dist(an.b, top[i]) <= 40)
                // textured boards (marble, wood) score lower: a board as wide as most of the screen is surely
                // the board, so it may score a little less
                val need = if (g.size >= w * 0.6) 0.72 else 0.85
                if (an.score >= need && sameColors && an.score * g.size > bestScore) {
                    bestScore = an.score * g.size
                    best = g
                }
            }
        }
        return best
    }

    /** the biggest square-ish area made only of these two colors */
    fun candidate(px: IntArray, w: Int, h: Int, c1: Int, c2: Int): BoardGeom? {
        val tol = 40
        val rowOk = BooleanArray(h)
        var y = 0
        while (y < h) {
            var cnt = 0
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                if (dist(p, c1) <= tol || dist(p, c2) <= tol) cnt++
                x += 2
            }
            val ok = cnt * 2 >= w * 0.10
            rowOk[y] = ok
            if (y + 1 < h) rowOk[y + 1] = ok
            y += 2
        }
        val rows = longestRun(rowOk) ?: return null
        val y0 = rows.first
        val y1 = rows.second
        val colOk = BooleanArray(w)
        for (x in 0 until w) {
            var cnt = 0
            var yy = y0
            while (yy <= y1) {
                val p = px[yy * w + x]
                if (dist(p, c1) <= tol || dist(p, c2) <= tol) cnt++
                yy += 2
            }
            colOk[x] = cnt * 2 >= (y1 - y0 + 1) * 0.10
        }
        val cols = longestRun(colOk) ?: return null
        val bw = cols.second - cols.first + 1
        val bh = y1 - y0 + 1
        if (minOf(bw, bh) < minOf(w, h) * 0.3) return null
        if (abs(bw - bh) > 0.04 * maxOf(bw, bh)) return null
        return BoardGeom(cols.first, y0, (bw + bh) / 2)
    }

    /** longest stretch of true values, ignoring gaps of up to 3 (the blurry line between two squares) */
    private fun longestRun(ok: BooleanArray): Pair<Int, Int>? {
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        var lastOn = -1
        for (i in 0..ok.size) {
            val on = i < ok.size && ok[i]
            if (on) {
                if (start < 0) start = i
                lastOn = i
            } else if (start >= 0 && (i >= ok.size || i - lastOn > 3)) {
                if (lastOn - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = lastOn
                }
                start = -1
            }
        }
        return if (bestStart >= 0) Pair(bestStart, bestEnd) else null
    }

    /** color of the empty part of a square, taken from points near the middle of its four edges */
    private fun edgeColor(px: IntArray, w: Int, g: BoardGeom, r: Int, c: Int): Int {
        val s = g.size / 8f
        val x0 = g.left + c * s
        val y0 = g.top + r * s
        val pts = arrayOf(
            floatArrayOf(0.5f, 0.07f), floatArrayOf(0.5f, 0.93f),
            floatArrayOf(0.07f, 0.5f), floatArrayOf(0.93f, 0.5f)
        )
        val cols = IntArray(4) { i ->
            val x = (x0 + pts[i][0] * s).toInt()
            val y = (y0 + pts[i][1] * s).toInt()
            px[y * w + x]
        }
        var bestIdx = 0
        var bestVotes = -1
        for (i in 0..3) {
            var votes = 0
            for (j in 0..3) if (dist(cols[i], cols[j]) <= 30) votes++
            if (votes > bestVotes) {
                bestVotes = votes
                bestIdx = i
            }
        }
        return cols[bestIdx]
    }

    private class Analysis(val score: Double, val a: Int, val b: Int)

    /** how much this looks like a real chessboard (squares alternate between two colors), and which two colors */
    private fun analyze(px: IntArray, w: Int, h: Int, g: BoardGeom): Analysis? {
        if (g.left < 0 || g.top < 0 || g.left + g.size > w || g.top + g.size > h || g.size < 160) return null
        val cols = IntArray(64)
        for (r in 0..7) for (c in 0..7) cols[r * 8 + c] = edgeColor(px, w, g, r, c)
        // the two most common square colors
        val reps = ArrayList<IntArray>() // [color, count]
        for (c in cols) {
            val e = reps.firstOrNull { dist(it[0], c) <= 30 }
            if (e != null) e[1]++ else reps.add(intArrayOf(c, 1))
        }
        reps.sortByDescending { it[1] }
        if (reps.size < 2 || dist(reps[0][0], reps[1][0]) < 24) return null
        val a = reps[0][0]
        val b = reps[1][0]
        val cls = IntArray(64) { i ->
            when {
                dist(cols[i], a) <= 36 -> 0
                dist(cols[i], b) <= 36 -> 1
                else -> -1
            }
        }
        var m0 = 0
        var m1 = 0
        for (r in 0..7) for (c in 0..7) {
            val k = cls[r * 8 + c]
            if (k == (r + c) % 2) m0++
            if (k == (r + c + 1) % 2) m1++
        }
        return Analysis(maxOf(m0, m1) / 64.0, a, b)
    }

    /** 0..1: how much this looks like a real chessboard (squares alternate between two colors) */
    fun scoreGeom(px: IntArray, w: Int, h: Int, g: BoardGeom): Double = analyze(px, w, h, g)?.score ?: 0.0

    // ------------------------------------------------------------------ reading the squares

    private const val N = 24
    private const val MASK_WORDS = (N * N + 63) / 64
    private const val DIFF = 36        // how far from the background a pixel must be to belong to something
    private const val SPECK_CELLS = 8  // smaller connected spots are specks (snow, stars), not part of a piece

    /** per channel median of the ring colors within 40 of c */
    private fun medianNear(ring: List<Int>, c: Int): Int {
        val near = ring.filter { dist(it, c) <= 40 }
        if (near.size < 3) return c
        val rs = near.map { red(it) }.sorted()
        val gs = near.map { green(it) }.sorted()
        val bs = near.map { blue(it) }.sorted()
        val m = near.size / 2
        return rgb(rs[m], gs[m], bs[m])
    }

    private class Scan(val diff: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val flags: BooleanArray, val luma: IntArray)

    /** how much of the middle of a square differs from the background color bg (the differing pixels go into cols) */
    private fun scan(px: IntArray, w: Int, x0: Float, y0: Float, s: Float, bg: Int, cols: IntArray): Scan {
        var diff = 0
        var minX = N
        var maxX = -1
        var minY = N
        var maxY = -1
        val flags = BooleanArray(N * N)
        val lum = IntArray(N * N)
        for (iy in 0 until N) {
            for (ix in 0 until N) {
                val fx = 0.18f + 0.64f * (ix + 0.5f) / N
                val fy = 0.18f + 0.64f * (iy + 0.5f) / N
                val p = px[(y0 + fy * s).toInt() * w + (x0 + fx * s).toInt()]
                lum[iy * N + ix] = luma(p)
                if (dist(p, bg) > DIFF) flags[iy * N + ix] = true
            }
        }
        // specks are not part of anything: board themes with falling snow, stars or grain scatter tiny spots over
        // every square. keep only the real shapes (a piece, a hint dot): connected spots of a fair size
        val comp = IntArray(N * N) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(N * N)
        for (k in 0 until N * N) {
            if (!flags[k] || comp[k] >= 0) continue
            val id = sizes.size
            var n = 0
            var top = 0
            stack[top++] = k
            comp[k] = id
            while (top > 0) {
                val q = stack[--top]
                n++
                val qx = q % N
                val qy = q / N
                if (qx > 0 && flags[q - 1] && comp[q - 1] < 0) { comp[q - 1] = id; stack[top++] = q - 1 }
                if (qx < N - 1 && flags[q + 1] && comp[q + 1] < 0) { comp[q + 1] = id; stack[top++] = q + 1 }
                if (qy > 0 && flags[q - N] && comp[q - N] < 0) { comp[q - N] = id; stack[top++] = q - N }
                if (qy < N - 1 && flags[q + N] && comp[q + N] < 0) { comp[q + N] = id; stack[top++] = q + N }
            }
            sizes.add(n)
        }
        val biggest = sizes.maxOrNull() ?: 0
        val keepMin = maxOf(SPECK_CELLS, (biggest * 0.15f).toInt())
        for (iy in 0 until N) {
            for (ix in 0 until N) {
                val k = iy * N + ix
                if (!flags[k]) continue
                if (sizes[comp[k]] < keepMin) {
                    flags[k] = false
                    continue
                }
                val fx = 0.18f + 0.64f * (ix + 0.5f) / N
                val fy = 0.18f + 0.64f * (iy + 0.5f) / N
                cols[diff++] = px[(y0 + fy * s).toInt() * w + (x0 + fx * s).toInt()]
                if (ix < minX) minX = ix
                if (ix > maxX) maxX = ix
                if (iy < minY) minY = iy
                if (iy > maxY) maxY = iy
            }
        }
        return Scan(diff, minX, maxX, minY, maxY, flags, lum)
    }

    private fun median(a: IntArray, n: Int): Int = percentile(a, n, 0.5f)

    private fun percentile(a: IntArray, n: Int, p: Float): Int {
        val c = a.copyOf(n)
        java.util.Arrays.sort(c)
        return c[((n - 1) * p).toInt()]
    }

    /** one SquareRead per square, index = screen row * 8 + screen column (row 0 is the top of the screen) */
    fun readSquares(px: IntArray, w: Int, g: BoardGeom): Array<SquareRead> {
        val s = g.size / 8f
        val out = Array(64) { SquareRead(false, 0, 0f) }
        val colsA = IntArray(N * N)
        val colsB = IntArray(N * N)
        for (r in 0..7) for (c in 0..7) {
            val x0 = g.left + c * s
            val y0 = g.top + r * s

            // background of this square: the most common color on a ring just inside its border.
            // apps sometimes draw a colored frame on a square (like a king in check), so the second most
            // common ring color is a candidate too, and the one that leaves less "not background" wins
            val ring = ArrayList<Int>(48)
            for (i in 0 until 12) {
                val t = 0.1f + 0.8f * i / 11f
                ring.add(px[(y0 + 0.06f * s).toInt() * w + (x0 + t * s).toInt()])
                ring.add(px[(y0 + 0.94f * s).toInt() * w + (x0 + t * s).toInt()])
                ring.add(px[(y0 + t * s).toInt() * w + (x0 + 0.06f * s).toInt()])
                ring.add(px[(y0 + t * s).toInt() * w + (x0 + 0.94f * s).toInt()])
            }
            var bg = ring[0]
            var bestVotes = -1
            for (i in ring.indices step 2) {
                var votes = 0
                for (j in ring.indices) if (dist(ring[i], ring[j]) <= 30) votes++
                if (votes > bestVotes) {
                    bestVotes = votes
                    bg = ring[i]
                }
            }
            // squares with a soft gradient (frosted, glass themes): the middle of the ring colors near the most
            // common one, not one edge's shade
            bg = medianNear(ring, bg)
            var sc = scan(px, w, x0, y0, s, bg, colsA)
            var cols = colsA
            if (sc.diff > 0.8f * N * N) {
                var bg2 = 0
                var votes2 = 0
                for (i in ring.indices) {
                    if (dist(ring[i], bg) <= 30) continue
                    var v = 0
                    for (j in ring.indices) if (dist(ring[i], ring[j]) <= 30) v++
                    if (v > votes2) {
                        votes2 = v
                        bg2 = ring[i]
                    }
                }
                if (votes2 >= 8) {
                    val sc2 = scan(px, w, x0, y0, s, bg2, colsB)
                    if (sc2.diff < sc.diff) {
                        sc = sc2
                        cols = colsB
                        bg = bg2
                    }
                }
            }

            val fill = sc.diff.toFloat() / (N * N)
            // a piece is a big blob; the little dots that apps draw for possible moves are not
            val blobW = if (sc.maxX >= sc.minX) (sc.maxX - sc.minX + 1) * 0.64f / N else 0f
            val blobH = if (sc.maxY >= sc.minY) (sc.maxY - sc.minY + 1) * 0.64f / N else 0f

            // a hint dot is one solid color in a round shape. pieces have outlines and details
            var dot = false
            if (sc.diff >= 30) {
                val mr = median(IntArray(sc.diff) { red(cols[it]) }, sc.diff)
                val mg = median(IntArray(sc.diff) { green(cols[it]) }, sc.diff)
                val mb = median(IntArray(sc.diff) { blue(cols[it]) }, sc.diff)
                val mc = rgb(mr, mg, mb)
                var near = 0
                for (i in 0 until sc.diff) if (dist(cols[i], mc) <= 36) near++
                val bw = sc.maxX - sc.minX + 1
                val bh = sc.maxY - sc.minY + 1
                val solid = sc.diff.toFloat() / (bw * bh)
                val round = bw.toFloat() / bh
                dot = near >= 0.9f * sc.diff && solid >= 0.7f && round in 0.8f..1.25f && blobW <= 0.68f
            }
            val occupied = !dot && fill >= 0.15f && blobW >= 0.36f && blobH >= 0.36f

            var tone = 0
            var mask: LongArray? = null
            if (occupied) {
                // brightness of the piece: bright or dark body? only the inside of the piece counts (all four
                // neighbours are piece too), so thin dark outlines and blurry edges can't fool us
                var bright = 0
                var darkPx = 0
                var edgeBright = 0
                var edgeDark = 0
                for (iy in 0 until N) for (ix in 0 until N) {
                    val k = iy * N + ix
                    if (!sc.flags[k]) continue
                    val l = sc.luma[k]
                    val inside = ix > 0 && iy > 0 && ix < N - 1 && iy < N - 1 &&
                        sc.flags[k - 1] && sc.flags[k + 1] && sc.flags[k - N] && sc.flags[k + N]
                    if (inside) {
                        if (l >= 160) bright++ else if (l <= 110) darkPx++
                    } else {
                        if (l >= 160) edgeBright++ else if (l <= 110) edgeDark++
                    }
                }
                if (bright + darkPx < 6) { bright += edgeBright; darkPx += edgeDark }
                tone = if (bright + darkPx == 0) percentile(IntArray(sc.diff) { luma(cols[it]) }, sc.diff, 0.5f)
                else 70 + 175 * bright / (bright + darkPx)
                // shape of the piece: which spots of the square differ from the background
                mask = LongArray(MASK_WORDS)
                val on = BooleanArray(N * N)
                for (iy in 0 until N) for (ix in 0 until N) {
                    val fx = 0.08f + 0.84f * (ix + 0.5f) / N
                    val fy = 0.08f + 0.84f * (iy + 0.5f) / N
                    val p = px[(y0 + fy * s).toInt() * w + (x0 + fx * s).toInt()]
                    if (dist(p, bg) > DIFF) on[iy * N + ix] = true
                }
                // the shape without specks (snow): a spot counts only with a neighbour that is on too
                for (iy in 0 until N) for (ix in 0 until N) {
                    val k = iy * N + ix
                    if (!on[k]) continue
                    val nb = (ix > 0 && on[k - 1]) || (ix < N - 1 && on[k + 1]) || (iy > 0 && on[k - N]) || (iy < N - 1 && on[k + N])
                    if (!nb) continue
                    mask[k shr 6] = mask[k shr 6] or (1L shl (k and 63))
                }
            }
            out[r * 8 + c] = SquareRead(occupied, tone, fill, mask, dot, bg)
        }
        return out
    }

    /**
     * splits the pieces into white and black by brightness (2-means on the tone of occupied squares).
     * returns null if it can't tell (for example no pieces found, or all the same brightness).
     */
    fun classify(reads: Array<SquareRead>): IntArray? {
        val tones = reads.filter { it.occupied }.map { it.tone.toFloat() }
        if (tones.size < 2) return null
        var lo = tones.min()
        var hi = tones.max()
        if (hi - lo < 50f) return null
        repeat(12) {
            val mid = (lo + hi) / 2f
            val a = tones.filter { it <= mid }
            val b = tones.filter { it > mid }
            if (a.isEmpty() || b.isEmpty()) return null
            lo = a.average().toFloat()
            hi = b.average().toFloat()
        }
        if (hi - lo < 50f) return null
        // a bit closer to the dark group: thin bright pieces look darker than they are, thin dark pieces rarely look bright
        val mid = lo + 0.4f * (hi - lo)
        return IntArray(64) { i ->
            val r = reads[i]
            if (!r.occupied) EMPTY else if (r.tone > mid) WHITE else BLACK
        }
    }

    /** true if white pieces are mostly at the bottom of the screen, false if at the top, null if can't tell */
    fun whiteBottomOf(screen: IntArray): Boolean? {
        var top = 0
        var bottom = 0
        for (i in 0 until 64) {
            if (screen[i] != WHITE) continue
            if (i / 8 >= 4) bottom++ else top++
        }
        return if (bottom == top) null else bottom > top
    }

    /** tiny grayscale copy of an area, used to notice that something on the screen changed */
    fun thumb(px: IntArray, w: Int, h: Int, g: BoardGeom?): IntArray {
        val n = 80
        val x0 = g?.left ?: 0
        val y0 = g?.top ?: 0
        val sw = g?.size ?: w
        val sh = g?.size ?: h
        return IntArray(n * n) { i ->
            val x = (x0 + ((i % n) + 0.5f) * sw / n).toInt().coerceIn(0, w - 1)
            val y = (y0 + ((i / n) + 0.5f) * sh / n).toInt().coerceIn(0, h - 1)
            luma(px[y * w + x])
        }
    }

    fun changed(a: IntArray, b: IntArray): Boolean {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 24) n++
        return n >= 10
    }
}
