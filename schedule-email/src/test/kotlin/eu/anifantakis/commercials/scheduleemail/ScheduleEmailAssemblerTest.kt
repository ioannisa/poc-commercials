package eu.anifantakis.commercials.scheduleemail

import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler.toSettings
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.stations.SmtpConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure parts of the shared schedule-email assembly. `assemble` itself needs a
 * live `StationDb` (a concrete JDBC class with no interface seam), so it is
 * covered by the server/MCP integration checks rather than here; the rules that
 * actually differ per customer — the triangular spot label — are unit-tested.
 */
class ScheduleEmailAssemblerTest {

    private fun row(clientCode: String, clientName: String, message: String = "SPOT") = CommercialRow(
        id = 1,
        spotId = 1,
        position = 0,
        clientCode = clientCode,
        clientName = clientName,
        message = message,
        durationSeconds = 30,
        type = "Διαφημίσεις τηλεόρασης",
        contract = "C-1",
        flow = "ΡΟΗ",
    )

    @Test
    fun `customer mode labels a spot with just its message`() {
        val label = ScheduleEmailAssembler.spotLabel(
            row(clientCode = "AAA", clientName = "ΠΕΛΑΤΗΣ Α"),
            byTrader = false,
            partyCode = "AAA",
        )
        assertEquals("SPOT", label)
    }

    @Test
    fun `trader mode appends the END CLIENT when the spot is not the payer's own`() {
        // triangular: the agency BBB pays, but the spot belongs to end client AAA
        val label = ScheduleEmailAssembler.spotLabel(
            row(clientCode = "AAA", clientName = "ΠΕΛΑΤΗΣ Α"),
            byTrader = true,
            partyCode = "BBB",
        )
        assertEquals("SPOT — ΠΕΛΑΤΗΣ Α", label)
    }

    @Test
    fun `trader mode keeps the bare message when the payer is the spot's own customer`() {
        val label = ScheduleEmailAssembler.spotLabel(
            row(clientCode = "BBB", clientName = "ΠΡΑΚΤΟΡΕΙΟ Β"),
            byTrader = true,
            partyCode = "BBB",
        )
        assertEquals("SPOT", label)
    }

    @Test
    fun `toSettings carries every SMTP field across`() {
        val settings = SmtpConfig(
            host = "smtp.example.gr",
            port = 2525,
            username = "user",
            password = "pass",
            from = "station@example.gr",
            startTls = false,
        ).toSettings()

        assertEquals("smtp.example.gr", settings.host)
        assertEquals(2525, settings.port)
        assertEquals("user", settings.username)
        assertEquals("pass", settings.password)
        assertEquals("station@example.gr", settings.from)
        assertEquals(false, settings.startTls)
    }

    @Test
    fun `greekMonths is 1-based by index minus one`() {
        assertEquals(12, ScheduleEmailAssembler.greekMonths.size)
        assertEquals("Ιανουάριος", ScheduleEmailAssembler.greekMonths[0])
        assertEquals("Δεκέμβριος", ScheduleEmailAssembler.greekMonths[11])
    }
}
