import com.example.chesshelper.*
import java.io.File

/** checks the remembered answers: the text format round trip, the file, the size limit. compile with Lookahead.kt and Candidates.kt */
private var bad = 0
private fun check(ok: Boolean, what: String) { if (!ok) { bad++; println("FAIL: $what") } }

fun main() {
    val a = Advice(
        listOf(Cand("e2e4", 35, null, 0, 1, true), Cand("d2d4", 30, null, 1, 2), Cand("g1f3", -29990, -1, 3, 3)),
        28, null, Threat("d8h4", 300), listOf("e7e5", "g1f3", "b8c6"), "e2e4", 35, 30, listOf("d8h4", "h4e4")
    )
    val s = AdviceCodec.encode(a)
    check(!s.contains('\t') && !s.contains('\n'), "one line: $s")
    val b = AdviceCodec.decode(s)
    check(b != null, "decodes")
    b!!
    check(b.cands.map { "${it.move} ${it.score} ${it.mate} ${it.grade} ${it.rank} ${it.star}" } == a.cands.map { "${it.move} ${it.score} ${it.mate} ${it.grade} ${it.rank} ${it.star}" }, "cands: ${b.cands.map { it.move }}")
    check(b.evalWhite == 28 && b.mateWhite == null && b.threat?.move == "d8h4" && b.threat?.gain == 300, "eval and threat")
    check(b.line == a.line && b.lineFor == "e2e4" && b.bestScore == 35 && b.secondScore == 30 && b.plan == a.plan, "line, scores, plan")
    val empty = Advice(listOf(Cand("e2e4", 0, null, 0, 1)), 0, 3, null, emptyList())
    val e2 = AdviceCodec.decode(AdviceCodec.encode(empty))
    check(e2 != null && e2.mateWhite == 3 && e2.threat == null && e2.line.isEmpty() && e2.plan.isEmpty() && e2.secondScore == null, "empty parts")
    check(AdviceCodec.decode("garbage") == null, "garbage is null")
    val g = listOf(Pair("e7e5", 0.52f), Pair("c7c5", null as Float?))
    check(AdviceCodec.decodeGuesses(AdviceCodec.encodeGuesses(g)) == g, "guesses round trip")

    val f = File.createTempFile("mem", ".tsv")
    val m = AdviceMemory(f, max = 3)
    m.putAdvice("k1", "sf", a); m.putAdvice("k2", "sf", a); m.putGuesses("k1", "maia1500", g)
    m.save()
    val m2 = AdviceMemory(f, max = 3); m2.load()
    check(m2.advice("k1", "sf")?.cands?.first()?.move == "e2e4", "file keeps answers")
    check(m2.advice("k1", "maia") == null, "other settings don't get it")
    check(m2.guesses("k1", "maia1500") == g, "file keeps guesses")
    m2.putAdvice("k3", "sf", a); m2.putAdvice("k4", "sf", a)
    check(m2.size == 3, "size limit: ${m2.size}")
    check(m2.advice("k4", "sf") != null, "newest kept")
    m2.clear()
    check(m2.size == 0 && !f.exists(), "clear")
    println(if (bad == 0) "lookahead test: all good" else "lookahead test: $bad failed")
}
