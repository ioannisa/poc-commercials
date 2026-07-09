package eu.anifantakis.commercials.migration

import java.io.File
import java.nio.charset.Charset
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The SEN export parser: header detection, multi-line record reassembly
 * (embedded newlines in text cells), ISO-8859-7 fallback, Greek dates.
 */
class SenExportsTest {

    private fun file(name: String, content: String, charset: Charset = Charsets.UTF_8): File =
        File.createTempFile("sen-$name", ".csv").apply {
            deleteOnExit()
            writeBytes(content.toByteArray(charset))
        }

    @Test
    fun `header row is detected and mapped`() {
        val t = SenExports.parse(
            file("h", "LEEID\tLEENAME\tLEEAFM\r\n7619\tΖΩΓΡΑΦΑΚΗ Μ. & ΣΙΑ ΟΕ\t099465154\r\n")
        )
        assertEquals(1, t.rows.size)
        assertEquals("ΖΩΓΡΑΦΑΚΗ Μ. & ΣΙΑ ΟΕ", t.value(t.rows[0], "LEENAME"))
        assertEquals("099465154", t.value(t.rows[0], "LEEAFM"))
        assertEquals("", t.value(t.rows[0], "NOSUCH"))
    }

    @Test
    fun `headerless file uses the fallback header`() {
        val t = SenExports.parse(
            file("nh", "7619\tΖΩΓΡΑΦΑΚΗ\t099465154\n"),
            fallbackHeader = listOf("LEEID", "LEENAME", "LEEAFM"),
        )
        assertEquals("7619", t.value(t.rows[0], "LEEID"))
    }

    @Test
    fun `headerless file without fallback is a clear error`() {
        assertFailsWith<IllegalStateException> {
            SenExports.parse(file("nf", "7619\tΖΩΓΡΑΦΑΚΗ\t099465154\n"))
        }
    }

    @Test
    fun `a record split by an embedded newline is reassembled with a space`() {
        // 4 columns; the ADDRESS cell contains a newline -> record spans 2 lines
        val t = SenExports.parse(
            file("ml", "DOCID\tDOTID\tADDRESS\tVAT\r\n80135\t474\tΜΕΤΟΧΙ ΜΑΚΡΑΚΗ\r\n71005 ΗΡΑΚΛΕΙΟ\t094113461\r\n99\t450\tΠΛΑΚΑ\t111\r\n")
        )
        assertEquals(2, t.rows.size)
        assertEquals("ΜΕΤΟΧΙ ΜΑΚΡΑΚΗ 71005 ΗΡΑΚΛΕΙΟ", t.value(t.rows[0], "ADDRESS"))
        assertEquals("094113461", t.value(t.rows[0], "VAT"))
        assertEquals("ΠΛΑΚΑ", t.value(t.rows[1], "ADDRESS"))
    }

    @Test
    fun `a record that overshoots the field count is a loud error, not silent corruption`() {
        assertFailsWith<IllegalStateException> {
            SenExports.parse(file("ov", "A\tB\n1\t2\t3\n"))
        }
    }

    @Test
    fun `ISO-8859-7 bytes decode to Greek`() {
        val t = SenExports.parse(
            file("el", "LEEID\tLEENAME\n1\tΧΑΛΚΙΑΔΑΚΗΣ ΑΕ\n", Charset.forName("ISO-8859-7"))
        )
        assertEquals("ΧΑΛΚΙΑΔΑΚΗΣ ΑΕ", t.value(t.rows[0], "LEENAME"))
    }

    @Test
    fun `greekUpper strips accents so ΔΩΡ matching catches the gift doc types`() {
        // the real SDT descriptions: uppercase() alone gives "ΔΏΡΑ" (accented) and the match misses
        assertEquals("ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ (ΔΩΡΑ)", SenExports.greekUpper("Συμβόλαιο Πελάτη (Δώρα)"))
        assertEquals(true, SenExports.greekUpper("ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ (Δώρα)").contains("ΔΩΡ"))
        assertEquals(true, SenExports.greekUpper("ΕΝΤΟΛΗ ΔΙΑΦΗΜΙΣΗΣ (ΔΩΡΟ)").contains("ΔΩΡ"))
        assertEquals(false, SenExports.greekUpper("ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ ΕΚΛΟΓΩΝ").contains("ΔΩΡ"))
    }

    @Test
    fun `Greek dates parse, time-of-day and blanks handled`() {
        assertEquals(LocalDate.of(2025, 10, 1), SenExports.parseDate("1-Οκτ-2025"))
        assertEquals(LocalDate.of(2020, 12, 24), SenExports.parseDate("24-Δεκ-2020 15:40:22"))
        assertEquals(LocalDate.of(2014, 5, 5), SenExports.parseDate("5-Μαϊ-2014"))
        assertNull(SenExports.parseDate(""))
        assertNull(SenExports.parseDate("NAN"))
        assertNull(SenExports.parseDate("31-Φεβ-2020")) // impossible date -> null, not crash
    }
}
