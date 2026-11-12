package com.example.chesshelper

/**
 * Learns what each piece looks like in the chess app being played (from the starting position, or from a position the
 * AI read once), so the type of a piece (pawn, knight, ...) can be recognized later on the phone, with no AI.
 * piece type codes: 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king (0 = unknown / empty)
 * no android classes in here on purpose, so it can be tested on a normal computer.
 */
class PieceTemplates {
    private val lib = HashMap<Int, ArrayList<LongArray>>() // key = colour * 8 + type

    private fun key(colour: Int, type: Int) = colour * 8 + type

    fun count(colour: Int, type: Int): Int = lib[key(colour, type)]?.size ?: 0

    /** true when we know all six pieces of both colors */
    val complete: Boolean
        get() = (1..2).all { c -> (1..6).all { t -> count(c, t) > 0 } }

    val known: Int
        get() = (1..2).sumOf { c -> (1..6).count { t -> count(c, t) > 0 } }

    fun clear() { lib.clear(); version++ }

    /** goes up when a shape is learned (the service saves the shapes then) */
    @Volatile var version = 0
        private set

    /** all learned shapes as text, one per line: colour type then the mask as hex words */
    fun encode(): String = buildString {
        for ((k, l) in lib) for (m in l) {
            append(k / 8).append(' ').append(k % 8)
            for (w in m) append(' ').append(java.lang.Long.toHexString(w))
            append('\n')
        }
    }

    /** loads shapes saved with encode (adds to what is known) */
    fun decode(text: String) {
        for (line in text.lines()) {
            val p = line.trim().split(' ')
            if (p.size < 3) continue
            val c = p[0].toIntOrNull() ?: continue
            val t = p[1].toIntOrNull() ?: continue
            val mask = try { LongArray(p.size - 2) { java.lang.Long.parseUnsignedLong(p[it + 2], 16) } } catch (e: Exception) { continue }
            learn(c, t, mask)
        }
    }

    private fun ham(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    fun learn(colour: Int, type: Int, mask: LongArray?) {
        if (mask == null || type !in 1..6 || colour !in 1..2) return
        val l = lib.getOrPut(key(colour, type)) { ArrayList() }
        if (l.any { ham(it, mask) < 12 }) return // we already have almost the same picture
        if (l.size >= 6) l.removeAt(0)
        l.add(mask)
        version++
    }

    /** distance to the best picture of each type (index 1..6), for debugging */
    fun distances(colour: Int, mask: LongArray?): IntArray {
        val out = IntArray(7) { 999 }
        if (mask == null) return out
        for (t in 1..6) {
            lib[key(colour, t)]?.forEach { out[t] = minOf(out[t], ham(it, mask)) }
            lib[key(3 - colour, t)]?.forEach { out[t] = minOf(out[t], ham(it, mask) + 10) }
        }
        return out
    }

    /** all six types, the most likely first (even when we are not sure) */
    fun ranked(colour: Int, mask: LongArray?): List<Int> {
        val d = distances(colour, mask)
        return (1..6).sortedBy { d[it] }
    }

    /** best guess for the type of the piece with this shape, or 0 if not sure */
    fun recognize(colour: Int, mask: LongArray?): Int {
        if (mask == null) return 0
        var best = Int.MAX_VALUE
        var bestType = 0
        var second = Int.MAX_VALUE
        for (t in 1..6) {
            // our own color's pictures first, the other color's silhouette is nearly the same so it is a fallback
            var d = Int.MAX_VALUE
            lib[key(colour, t)]?.forEach { d = minOf(d, ham(it, mask)) }
            lib[key(3 - colour, t)]?.forEach { d = minOf(d, ham(it, mask) + 10) }
            if (d < best) {
                second = best
                best = d
                bestType = t
            } else if (d < second) {
                second = d
            }
        }
        if (bestType == 0 || best > MAX_DIST || (second != Int.MAX_VALUE && second - best < MIN_MARGIN)) return 0
        return bestType
    }

    /** the type of every piece on the screen (index = screen row * 8 + column), 0 where unknown or empty */
    fun recognizeAll(reads: Array<SquareRead>, screen: IntArray): IntArray {
        if (known == 0) return IntArray(64)
        return IntArray(64) { i -> if (screen[i] == BoardVision.EMPTY) 0 else recognize(screen[i], reads[i].mask) }
    }

    companion object {
        const val MAX_DIST = 110
        const val MIN_MARGIN = 14
    }
}
