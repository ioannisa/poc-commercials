package eu.anifantakis.commercials.galaxy

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GalaxyImporterLogicTest {

    private fun line(
        type: String,
        cust: String = "01000012",
        adv: String? = null,
        date: LocalDate? = LocalDate.of(2026, 7, 17),
        seconds: String? = "20,00",
        value: String? = "4.424,00",
    ) = FlatLine(
        companyCode = "001", custCode = cust, custName = null, custId = null,
        date = date, docNumber = "000121", docCode = "9004", type = type,
        advCode = adv, advName = null, itemId = null, itemCode = null, itemName = null,
        seconds = GalaxyExports.greekMoney(seconds), spots = null, comments = null,
        value = GalaxyExports.greekMoney(value),
    )

    // ── families (§9.2) ─────────────────────────────────────────────────────

    @Test
    fun `type text classifies into families`() {
        assertEquals(DocFamily.CONTRACT, GalaxyImporter.familyOf("Συμβόλαιο Πελάτη"))
        assertEquals(DocFamily.CONTRACT, GalaxyImporter.familyOf("Συμβόλαιο Πελάτη από SEN"))
        assertEquals(DocFamily.CONTRACT, GalaxyImporter.familyOf("Συμβόλαιο Πελάτη (Δώρα) από SEN"))
        assertEquals(DocFamily.ORDER, GalaxyImporter.familyOf("Εντολή Διαφήμισης"))
        assertEquals(DocFamily.ORDER, GalaxyImporter.familyOf("Εντολή Διαφήμησης (Δώρα)"))
        assertEquals(DocFamily.TRIANGULAR_SEN, GalaxyImporter.familyOf("Τριγωνικό Πελάτη από SEN"))
        assertEquals(DocFamily.TRIANGULAR_NATIVE, GalaxyImporter.familyOf("Τριγωνικό Πελάτη"))
        assertEquals(DocFamily.BOOKKEEPING, GalaxyImporter.familyOf("Κλείσιμο Εκκρεμοτήτων"))
        assertEquals(DocFamily.BOOKKEEPING, GalaxyImporter.familyOf("Ακύρωση Συμβολαίο/Εντολής"))
        assertEquals(DocFamily.BOOKKEEPING, GalaxyImporter.familyOf("Διόρθωση Εκκρεμών συμβολαίων (Αύξηση)"))
    }

    // ── roles (§9.3 - verified on LOREAL/TEMPO, contract 645) ──────────────

    @Test
    fun `payer is custcode on orders and contracts`() {
        // 9004: cust=TEMPO (agency, payer), adv=LOREAL (advertiser)
        assertEquals("01000012", GalaxyImporter.payerOf(DocFamily.ORDER, "01000012", "30001582"))
        assertEquals("30001582", GalaxyImporter.advertiserOf(DocFamily.ORDER, "01000012", "30001582"))
        assertEquals("30030937", GalaxyImporter.payerOf(DocFamily.CONTRACT, "30030937", null))
    }

    @Test
    fun `sen triangular inverts the roles`() {
        // 9110: cust=LOREAL (advertiser), adv=TEMPO (agency, payer)
        assertEquals("01000012", GalaxyImporter.payerOf(DocFamily.TRIANGULAR_SEN, "30001582", "01000012"))
        assertEquals("30001582", GalaxyImporter.advertiserOf(DocFamily.TRIANGULAR_SEN, "30001582", "01000012"))
    }

    @Test
    fun `native triangular has the advertiser in both columns`() {
        assertEquals("30001582", GalaxyImporter.payerOf(DocFamily.TRIANGULAR_NATIVE, "30001582", "30001582"))
        assertEquals("30001582", GalaxyImporter.advertiserOf(DocFamily.TRIANGULAR_NATIVE, "30001582", "30001582"))
    }

    // ── twins ───────────────────────────────────────────────────────────────

    @Test
    fun `twin signature matches a 9010 row to its 9004 twin`() {
        // 9004: agency doc, advertiser in the extra column
        val order = line("Εντολή Διαφήμισης", cust = "01000012", adv = "30001582")
        // 9010: advertiser doc, same date/seconds/value
        val triang = line("Τριγωνικό Πελάτη", cust = "30001582", adv = "30001582")
        assertEquals(
            GalaxyImporter.twinSignature(order.advCode, order),
            GalaxyImporter.twinSignature(triang.custCode, triang),
        )
    }

    @Test
    fun `value drift breaks the twin signature`() {
        val order = line("Εντολή Διαφήμισης", cust = "01000012", adv = "30001582", value = "4.424,00")
        val triang = line("Τριγωνικό Πελάτη", cust = "30001582", value = "5.000,00")
        assertNotEquals(
            GalaxyImporter.twinSignature(order.advCode, order),
            GalaxyImporter.twinSignature(triang.custCode, triang),
        )
    }

    @Test
    fun `twin signature ignores trailing zeros`() {
        val a = line("Τριγωνικό Πελάτη", value = "4.424,00")
        val b = line("Τριγωνικό Πελάτη", value = "4.424,0")
        assertEquals(
            GalaxyImporter.twinSignature("x", a),
            GalaxyImporter.twinSignature("x", b),
        )
    }

    // ── doc key normalization ───────────────────────────────────────────────

    @Test
    fun `doc key strips leading zeros so series compare stably`() {
        val l = line("Συμβόλαιο Πελάτη")
        assertEquals("001:9004:121", l.docKey)
        assertEquals(BigDecimal("20.00"), l.seconds)
    }
}
