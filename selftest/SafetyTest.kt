import com.example.chesshelper.Safety

/**
 * checks the quick piece safety checks (hanging pieces, safe squares for a touched piece) on real positions.
 * needs chesslib: compile with app/src/main/java/com/example/chesshelper/Safety.kt and run SafetyTestKt
 */
private var bad = 0
private fun check(ok: Boolean, what: String) {
    if (!ok) { bad++; println("FAIL: $what") }
}

fun main() {
    // white's knight on e5 is attacked by the d6 pawn and nobody defends it; the bishop on c4 is defended
    val p1 = "rnbqkb1r/ppp2ppp/3p1n2/4N3/2B1P3/8/PPPP1PPP/RNBQK2R w KQkq - 0 5"
    check(Safety.hanging(p1) == listOf("e5"), "hanging knight: ${Safety.hanging(p1)}")
    // a defended rook attacked by a knight hangs (the knight is worth less)
    val p2 = "4k3/8/8/3n4/8/4R3/4K3/8 w - - 0 1"
    check(Safety.hanging(p2) == listOf("e3"), "rook attacked by a knight: ${Safety.hanging(p2)}")
    // nothing attacked: nothing hangs
    check(Safety.hanging("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").isEmpty(), "start position")
    // black to move: black's pieces are checked
    val p3 = "4k3/8/8/3q4/8/4N3/4K3/8 b - - 0 1" // black queen on d5 attacked by nothing... knight e3 attacks d5
    check(Safety.hanging(p3) == listOf("d5"), "black queen attacked by a knight: ${Safety.hanging(p3)}")

    // touched piece: the white queen on d1 in the start position after 1.e4 e5 2.Nf3 d6 (queen can go e2 only)
    val t1 = Safety.targets("rnbqkbnr/ppp2ppp/3p4/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3", "d1")
    check(t1 == listOf(Pair("e2", true)), "queen squares: $t1")
    // white knight f3: g5 is attacked by the queen d8 (undefended there) -> taken; e5 takes a pawn but the d6 pawn takes back
    val t2 = Safety.targets("rnbqkbnr/ppp2ppp/3p4/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3", "f3").toMap()
    check(t2["g5"] == false, "Ng5 gets taken: $t2")
    check(t2["e5"] == false, "Nxe5 loses the knight for a pawn: $t2")
    check(t2["d4"] == false, "Nd4 runs into exd4: $t2")
    check(t2["h4"] == false, "Nh4 is hit by the queen on d8 (e7 is open): $t2")
    check(t2["g1"] == true, "back to g1 is safe: $t2")
    // taking a queen with a knight is fine even if the knight is taken back
    val t3 = Safety.targets("4k3/8/3p4/4q3/8/5N2/4K3/8 w - - 0 1", "f3").toMap()
    check(t3["e5"] == true, "Nxe5 wins the queen: $t3")
    check(Safety.targets("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", "e7").isEmpty(), "not your piece")
    println(if (bad == 0) "safety test: all good" else "safety test: $bad failed")
}
