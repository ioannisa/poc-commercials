package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.system.measureNanoTime

class GlyphFallbackBench {

    // consumed, so the JIT cannot delete the work we are trying to time
    private var sink = 0

    @Test
    fun bench() {
        val chain = GlyphFallback(listOf(
            ScriptFont("Hebrew", listOf('\u0590'..'\u05FF', '\uFB1D'..'\uFB4F'), FontFamily.Cursive),
        ))
        val noop = GlyphFallback(emptyList())

        val row = listOf(
            "ΣΑΒΟΪΔΑΚΗΣ Γ. ΑΕ", "04000154", "ΣΑΒΟΙΔΑΚΗΣ ΤΟΥΡΤΕΣ ΠΑΓΩΤΟ 06/2024 ΠΡΙΝ ΔΕΛΤΙΟ",
            "36", "Διαφ. TV Αθήνα 73.000", "595", "ΡΟΗ", "LOREAL Elvive Glycolic Gloss 07/07-22/07",
        )
        // The DENSE grid (the 30x50 scheduler, ~1,500 cells) holds only digits and
        // times and never goes near the fallback - it draws plain Text. The grid
        // that DOES scan is the Break Console: about 20 rows of 8 text columns.
        // So this is the honest worst case, not 1,500.
        val CELLS = 20 * 8
        val strings = List(CELLS) { row[it % row.size] }
        val avgLen = strings.sumOf { it.length } / CELLS

        fun run(g: GlyphFallback): Long = measureNanoTime {
            for (s in strings) sink += g.annotateOrNull(s)?.text?.length ?: s.length
        }

        repeat(300) { run(chain); run(noop) }        // warm up

        val ns = (1..300).minOf { run(chain) }
        val floor = (1..300).minOf { run(noop) }

        println("ΜΕΤΡΗΣΗ (sink=$sink, μέσο μήκος κελιού $avgLen χαρακτήρες)")
        println("  Break Console ($CELLS κελιά), με αλυσίδα : $ns ns   (${ns / CELLS} ns/κελί)")
        println("  το ίδιο, ΧΩΡΙΣ αλυσίδα                  : $floor ns   (${floor / CELLS} ns/κελί)")
        println("  ΚΑΘΑΡΟ ΚΟΣΤΟΣ ΑΛΥΣΙΔΑΣ                  : ${ns - floor} ns  (${(ns - floor) / CELLS} ns/κελί)")
        val frameNs = 16_666_667.0
        println("  frame budget στα 60fps          : ${(frameNs / 1000).toInt()} μs")
        println("  ΠΟΣΟΣΤΟ ΤΟΥ FRAME (καθαρό)      : ${"%.4f".format((ns - floor) / frameNs * 100)} %")
    }
}
