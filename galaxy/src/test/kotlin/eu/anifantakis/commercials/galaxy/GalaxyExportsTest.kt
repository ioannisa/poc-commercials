package eu.anifantakis.commercials.galaxy

import java.io.File
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalaxyExportsTest {

    // ── tokenizer ───────────────────────────────────────────────────────────

    @Test
    fun `plain tab rows split on CRLF`() {
        val rows = GalaxyExports.tokenize("a\tb\tc\r\n1\t2\t3\r\n", '\t')
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `quoted field swallows delimiter newline and doubled quotes`() {
        // Shaped like the real 31-column INTRAWAY row: a quoted cell containing
        // raw newlines AND raw tabs AND CSV-doubled quotes.
        val cell = "\"Διαφημιστική καταχώρηση\n\nΥΠΟΥΡΓΕΙΟ\t70% στην INTRAWAY\t30% \"\"ΟΜΑΔΑ ΣΥΜΠΡΑΞΙΣ\"\"\""
        val rows = GalaxyExports.tokenize("x\t$cell\ty\r\n", '\t')
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].size)
        assertEquals("x", rows[0][0])
        assertEquals("Διαφημιστική καταχώρηση\n\nΥΠΟΥΡΓΕΙΟ\t70% στην INTRAWAY\t30% \"ΟΜΑΔΑ ΣΥΜΠΡΑΞΙΣ\"", rows[0][1])
        assertEquals("y", rows[0][2])
    }

    @Test
    fun `bare LF ends a record and last record needs no terminator`() {
        val rows = GalaxyExports.tokenize("a;b\n1;2", ';')
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test
    fun `trailing empty field is preserved`() {
        val rows = GalaxyExports.tokenize("a\t\r\n", '\t')
        assertEquals(listOf(listOf("a", "")), rows)
    }

    @Test
    fun `quote mid-field is literal`() {
        val rows = GalaxyExports.tokenize("ab\"cd\tx\r\n", '\t')
        assertEquals(listOf(listOf("ab\"cd", "x")), rows)
    }

    // ── parse + GalaxyTable ─────────────────────────────────────────────────

    @Test
    fun `parse reads header maps NULL and blank to null and counts rejects`(): Unit = withTempFile(
        "COL_A\tCOL_B\tCOL_C\r\n" +
            "1\tNULL\t\r\n" +
            "only\ttwo\r\n" +           // arity 2 ≠ 3 → rejected
            "2\tβήτα\tc\r\n",
        Charset.forName("windows-1253"),
    ) { f ->
        val t = GalaxyExports.parse(f, '\t')
        assertEquals(2, t.rows.size)
        assertEquals(1, t.rejected)
        assertNull(t.value(t.rows[0], "COL_B"))
        assertNull(t.value(t.rows[0], "COL_C"))
        assertEquals("βήτα", t.value(t.rows[1], "COL_B"))
        assertNull(t.value(t.rows[1], "MISSING"))
    }

    @Test
    fun `utf8 input decodes as utf8`(): Unit = withTempFile("H\r\nΚρήτη\r\n", Charsets.UTF_8) { f ->
        val t = GalaxyExports.parse(f, '\t')
        assertEquals("Κρήτη", t.value(t.rows[0], "H"))
    }

    // ── coercers ────────────────────────────────────────────────────────────

    @Test
    fun `greek money`() {
        assertEquals(BigDecimal("8000.00"), GalaxyExports.greekMoney("8.000,00"))
        assertEquals(BigDecimal("-25000.00"), GalaxyExports.greekMoney("-25.000,00"))
        assertEquals(BigDecimal("1.00"), GalaxyExports.greekMoney("1,00"))
        assertEquals(BigDecimal("1580000.00"), GalaxyExports.greekMoney("1.580.000,00"))
        assertNull(GalaxyExports.greekMoney(null))
        assertNull(GalaxyExports.greekMoney("30% του ποσού"))
    }

    @Test
    fun `flat date`() {
        assertEquals(LocalDate.of(2024, 1, 4), GalaxyExports.flatDate("04/01/2024"))
        assertNull(GalaxyExports.flatDate("garbage"))
    }

    @Test
    fun `old export scaled12 money`() {
        // Verified §1: 6.168.300.000.000.000 → 6,168.30 €
        assertEquals(0, BigDecimal("6168.3").compareTo(GalaxyExports.scaled12("6.168.300.000.000.000")))
        assertEquals(0, BigDecimal.ONE.compareTo(GalaxyExports.scaled12("1.000.000.000.000")))
    }

    @Test
    fun `tin normalization pads the lost leading zero`() {
        assertEquals("097690560", GalaxyExports.normalizedTin("97690560"))
        assertEquals("094502024", GalaxyExports.normalizedTin("094502024"))
        assertNull(GalaxyExports.normalizedTin(""))
        assertNull(GalaxyExports.normalizedTin(null))
    }

    @Test
    fun `item digits bridge both code worlds`() {
        assertEquals("101", GalaxyExports.itemDigits("Σ101"))
        assertEquals("73003", GalaxyExports.itemDigits("73.003"))
        assertEquals("73003", GalaxyExports.itemDigits("73003"))
        assertNull(GalaxyExports.itemDigits("ΧΩΡΙΣ"))
    }

    @Test
    fun `doc numbers compare zero-stripped`() {
        assertEquals("450", GalaxyExports.normalizedDocNumber("000450"))
        assertEquals("0", GalaxyExports.normalizedDocNumber("000"))
        assertNull(GalaxyExports.normalizedDocNumber("  "))
    }

    // ── flat lines ──────────────────────────────────────────────────────────

    @Test
    fun `flatLines types the columns and builds the doc key`() {
        val header = listOf(
            "companycode", "custcode", "custname", "custid", "date", "docnumber", "doccode",
            "Type", FlatCols.ADV_CODE, FlatCols.ADV_NAME, "item_ID", "item_code", "itemname",
            "aqty", "Seconds", "Spot", "Comments", "Value",
        ).joinToString("\t")
        val row = listOf(
            "001", "01000012", "TEMPO OMD", "uuid-cust", "17/07/2026", "000121", "9004",
            "Εντολή Διαφήμισης", "30001582", "L OREAL HELLAS AE", "uuid-item", "Σ102", "Διαφ. TV",
            "1,00", "20,00", "125,00", "σχόλιο", "4.424,00",
        ).joinToString("\t")
        withTempFile("$header\r\n$row\r\n", Charset.forName("windows-1253")) { f ->
            val lines = GalaxyExports.parse(f, '\t').flatLines()
            assertEquals(1, lines.size)
            val l = lines.single()
            assertEquals("001:9004:121", l.docKey)
            assertEquals("01000012", l.custCode)
            assertEquals("30001582", l.advCode)
            assertEquals(LocalDate.of(2026, 7, 17), l.date)
            assertEquals(BigDecimal("125.00"), l.spots)
            assertEquals(BigDecimal("4424.00"), l.value)
        }
    }

    @Test
    fun `greekUpper strips the tonos`() {
        assertTrue("ΔΩΡ" in GalaxyImporter.greekUpper("Συμβόλαιο Πελάτη (Δώρα)"))
        assertTrue("ΕΚΛΟΓ" in GalaxyImporter.greekUpper("Συμβόλαιο Πελάτη (Εκλογικά)"))
    }

    private fun withTempFile(content: String, charset: Charset, block: (File) -> Unit) {
        val f = File.createTempFile("galaxy-test", ".txt")
        try {
            f.writeBytes(content.toByteArray(charset))
            block(f)
        } finally {
            f.delete()
        }
    }
}
