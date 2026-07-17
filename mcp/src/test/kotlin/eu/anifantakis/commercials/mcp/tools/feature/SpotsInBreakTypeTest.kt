package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.commercial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Break Console's Τύπος = the ERP sales item, never the programme. This
 * exact category error ("a programme like ΚΛΕΨΑ is a product") was fixed in the
 * app's CommercialDetailScreen and then reappeared in `spots_in_break`, which
 * emitted the programme [type] as the spot type - so it is pinned here.
 */
class SpotsInBreakTypeTest {

    // The fixture's programme type - the WRONG value the bug surfaced.
    private val programme = commercial("AAA").copy(type = "ΚΛΕΨΑ")

    @Test
    fun `the sales item is the type`() {
        val row = programme.copy(salesItem = "Διαφ. TV Κρήτη Σ73.002")
        assertEquals("Διαφ. TV Κρήτη Σ73.002", row.breakConsoleType())
    }

    @Test
    fun `a gift with no sales item shows the gift marker, not the programme`() {
        val row = programme.copy(salesItem = null, isGift = true)
        assertEquals("ΔΩΡΑ", row.breakConsoleType())
    }

    @Test
    fun `an ERP-uncovered non-gift is unknown - NEVER the programme`() {
        val row = programme.copy(salesItem = null, isGift = false)
        assertNull(row.breakConsoleType())
    }

    @Test
    fun `the sales item wins over the gift marker`() {
        val row = programme.copy(salesItem = "Διαφ. TV", isGift = true)
        assertEquals("Διαφ. TV", row.breakConsoleType())
    }
}
