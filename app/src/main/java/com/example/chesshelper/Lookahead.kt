package com.example.chesshelper

import java.io.File

/**
 * thinking ahead and remembering:
 * - AdviceCodec turns a worked out answer (Advice) into one line of text and back
 * - AdviceMemory keeps those answers (and the bot's likely moves) on the phone, across games. bots repeat their
 *   openings, so a position seen before gets its answer at once, even in a later game. the oldest entries go when
 *   it is full
 */
object AdviceCodec {
    /** one line, no tabs or new lines */
    fun encode(a: Advice): String {
        val cands = a.cands.joinToString("/") { c ->
            "${c.move},${c.score},${c.mate ?: ""},${c.grade},${c.rank},${if (c.star) 1 else 0}"
        }
        val threat = a.threat?.let { "${it.move},${it.gain}" } ?: ""
        return listOf(
            cands, a.evalWhite.toString(), a.mateWhite?.toString() ?: "", threat, a.line.joinToString(" "), a.lineFor,
            a.bestScore?.toString() ?: "", a.secondScore?.toString() ?: "", a.plan.joinToString(" ")
        ).joinToString(";")
    }

    fun decode(s: String): Advice? {
        return try {
            val f = s.split(";")
            if (f.size < 9) return null
            val cands = if (f[0].isEmpty()) emptyList() else f[0].split("/").map { c ->
                val p = c.split(",")
                Cand(p[0], p[1].toInt(), p[2].toIntOrNull(), p[3].toInt(), p[4].toInt(), p[5] == "1")
            }
            val threat = if (f[3].isEmpty()) null else f[3].split(",").let { Threat(it[0], it[1].toInt()) }
            Advice(
                cands, f[1].toInt(), f[2].toIntOrNull(), threat, words(f[4]), f[5],
                f[6].toIntOrNull(), f[7].toIntOrNull(), words(f[8])
            )
        } catch (e: Exception) {
            null
        }
    }

    /** the bot's likely moves with their odds (null when not known) */
    fun encodeGuesses(g: List<Pair<String, Float?>>): String = g.joinToString(",") { "${it.first}:${it.second ?: ""}" }

    fun decodeGuesses(s: String): List<Pair<String, Float?>>? {
        return try {
            s.split(",").filter { it.isNotEmpty() }.map { e ->
                val p = e.split(":")
                Pair(p[0], p.getOrNull(1)?.toFloatOrNull())
            }.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun words(s: String) = s.split(" ").filter { it.isNotEmpty() }
}

/**
 * the remembered answers, in a small text file (one "key<tab>value" per line). keys say what was worked out and
 * with which settings, so a different engine or strength never gets someone else's answer
 */
class AdviceMemory(private val file: File, private val max: Int = MAX) {
    private val map = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
    }
    private var unsaved = 0

    val size: Int get() = map.size

    fun load() {
        map.clear()
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                val t = line.indexOf('\t')
                if (t > 0) map[line.substring(0, t)] = line.substring(t + 1)
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    fun save() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in map) {
                    w.write(k)
                    w.write("\t")
                    w.write(v)
                    w.newLine()
                }
            }
            tmp.renameTo(file)
            unsaved = 0
        } catch (e: Exception) {
        }
    }

    private fun put(key: String, value: String) {
        map[key] = value
        if (++unsaved >= SAVE_EVERY) save()
    }

    fun advice(posKey: String, sig: String): Advice? = map["A|$posKey|$sig"]?.let { AdviceCodec.decode(it) }

    fun putAdvice(posKey: String, sig: String, a: Advice) {
        if (a.cands.isNotEmpty()) put("A|$posKey|$sig", AdviceCodec.encode(a))
    }

    fun guesses(posKey: String, sig: String): List<Pair<String, Float?>>? = map["G|$posKey|$sig"]?.let { AdviceCodec.decodeGuesses(it) }

    fun putGuesses(posKey: String, sig: String, g: List<Pair<String, Float?>>) {
        if (g.isNotEmpty()) put("G|$posKey|$sig", AdviceCodec.encodeGuesses(g))
    }

    fun clear() {
        map.clear()
        file.delete()
        unsaved = 0
    }

    companion object {
        const val MAX = 8000       // about 1-2 MB on the phone
        const val SAVE_EVERY = 25  // write the file after this many new entries (and when the helper stops)
    }
}
