package eu.anifantakis.commercials.server.scheduler

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid's rows - the one rule the retired `break_slots` table used to stand
 * in for, and the one most likely to be "tidied up" by someone who has not seen
 * the legacy console.
 *
 * The reference is a real screenshot: Crete TV, Δεκέμβριος 2025, "Προβολή κάθε:
 * Μισή Ώρα", broadcast day starting 07:00. Its rows run
 *
 *     00:05, 00:30, 01:00, 01:30, 01:45, 02:00 … 04:00, 07:00, 07:30, 08:00 …
 *
 * with 07:00-09:30 EMPTY but printed, and 04:30-06:30 absent altogether. Those
 * two facts together are the whole design: an empty row is drawn because the
 * scaffold says so, and a row exists off the scaffold because a spot aired there.
 */
class GridRowsTest {

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    /** The breaks Crete TV actually had in December 2025, before 10:30. */
    private val decemberBreaks = listOf(
        t(0, 5), t(0, 30), t(1, 0), t(1, 30), t(1, 45), t(2, 0), t(2, 30), t(2, 45),
        t(3, 0), t(3, 15), t(3, 30), t(3, 45), t(4, 0), t(9, 0), t(9, 30), t(10, 0),
        t(10, 15), t(10, 30),
    )

    @Test
    fun `half-hourly reproduces the legacy screenshot`() {
        val rows = gridRows(decemberBreaks, GridViewMode.HALF_HOURLY, emptyRowsFrom = t(7, 0))
            .map { it.time }

        // Real breaks BEFORE the broadcast day - printed because they exist.
        assertTrue(t(0, 5) in rows, "00:05 aired, so it is a row")
        assertTrue(t(1, 45) in rows, "01:45 is off the half-hour grid but aired")
        assertTrue(t(3, 15) in rows, "03:15 likewise")

        // The dead zone: neither a break nor inside the scaffold.
        for (dead in listOf(t(4, 30), t(5, 0), t(5, 30), t(6, 0), t(6, 30))) {
            assertTrue(dead !in rows, "$dead has no break and precedes the broadcast day")
        }

        // The scaffold, drawn from 07:00 whether or not anything airs.
        for (empty in listOf(t(7, 0), t(7, 30), t(8, 0), t(8, 30))) {
            assertTrue(empty in rows, "$empty is empty but the half-hourly view prints it")
        }
        assertTrue(t(23, 30) in rows, "the scaffold runs to the end of the day")

        // Off-grid breaks sit in TIME order, not rounded into a neighbour.
        val around = rows.filter { it in listOf(t(10, 0), t(10, 15), t(10, 30)) }
        assertEquals(listOf(t(10, 0), t(10, 15), t(10, 30)), around, "10:15 sits between 10:00 and 10:30")

        assertEquals(rows.sorted(), rows, "rows are in air order")
        assertEquals(rows.distinct(), rows, "a break that IS on the scaffold appears once, not twice")
    }

    @Test
    fun `hourly prints 24 rows plus the off-grid breaks`() {
        val rows = gridRows(decemberBreaks, GridViewMode.HOURLY, emptyRowsFrom = t(0, 0)).map { it.time }

        assertEquals(24, rows.count { it.minute == 0 }, "every hour of the day is a row")
        assertTrue(t(13, 0) in rows, "an empty hour is still an hourly row")
        assertTrue(t(0, 30) in rows, "a real 00:30 break keeps its own row")
        assertTrue(t(10, 15) in rows, "so does an off-grid 10:15")
    }

    @Test
    fun `condensed is exactly the breaks - no scaffold at all`() {
        val rows = gridRows(decemberBreaks, GridViewMode.CONDENSED, emptyRowsFrom = t(7, 0)).map { it.time }
        assertEquals(decemberBreaks.sorted(), rows)
        assertTrue(t(8, 0) !in rows, "CONDENSED prints no empty rows, whatever emptyRowsFrom says")
    }

    @Test
    fun `emptyRowsFrom never hides a real break`() {
        // A station whose day starts at 07:00 still shows the 03:00 spot it aired.
        val rows = gridRows(listOf(t(3, 0)), GridViewMode.HOURLY, emptyRowsFrom = t(7, 0)).map { it.time }
        assertTrue(t(3, 0) in rows, "the setting decides where EMPTY rows are drawn, nothing else")
        assertTrue(t(4, 0) !in rows, "…and 04:00 is empty and before the day starts")
    }

    @Test
    fun `a month with no airings still prints its scaffold`() {
        val rows = gridRows(emptyList(), GridViewMode.HOURLY, emptyRowsFrom = t(7, 0)).map { it.time }
        assertEquals((7..23).map { t(it, 0) }, rows)
    }

    @Test
    fun `an empty month in condensed view has no rows at all`() {
        assertEquals(emptyList(), gridRows(emptyList(), GridViewMode.CONDENSED, emptyRowsFrom = t(0, 0)))
    }
}
