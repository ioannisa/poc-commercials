package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authorization + station-resolution rules that keep the HTTP and stdio
 * transports honest, exercised without a live DB.
 */
class McpToolServicesTest {

    private fun services(vararg s: eu.anifantakis.commercials.server.stations.StationConfig) =
        McpToolServices(registryOf(*s))

    @Test
    fun `stations lists only the caller's granted stations`() {
        val svc = services(station("crete-tv", "Crete TV"), station("radio-984", "Radio 984"))
        val list = svc.stations(caller(grant("crete-tv")))
        assertEquals(1, list.size)
        assertEquals("crete-tv", list[0].id)
        assertEquals("Crete TV", list[0].name)
        assertEquals("NORMAL_USER", list[0].role)
        assertNull(list[0].clientCode)
    }

    @Test
    fun `stations surfaces the customer client code`() {
        val svc = services(station("crete-tv"))
        val info = svc.stations(caller(grant("crete-tv", UserRole.CUSTOMER_VIEWER, "30004521"))).single()
        assertEquals("CUSTOMER_VIEWER", info.role)
        assertEquals("30004521", info.clientCode)
    }

    @Test
    fun `resolveStation rejects a missing station param`() {
        val svc = services(station("crete-tv"))
        val e = assertFailsWith<McpToolException> { svc.resolveStation(caller(grant("crete-tv")), null) }
        assertTrue(e.message!!.contains("required"))
    }

    @Test
    fun `resolveStation rejects a station the caller has no grant for`() {
        val svc = services(station("crete-tv"), station("radio-984"))
        val e = assertFailsWith<McpToolException> { svc.resolveStation(caller(grant("crete-tv")), "radio-984") }
        assertTrue(e.message!!.contains("No access"))
    }

    @Test
    fun `resolveStation rejects an unhosted station even with a grant`() {
        val svc = services(station("crete-tv"))
        // grant for a station the registry does not host -> resolves to null without connecting
        val e = assertFailsWith<McpToolException> { svc.resolveStation(caller(grant("ghost")), "ghost") }
        assertTrue(e.message!!.contains("Unknown"))
    }

    @Test
    fun `requireCode blocks a customer from querying another client code`() {
        val svc = services(station("crete-tv"))
        val customer = grant("crete-tv", UserRole.CUSTOMER_VIEWER, "AAA")
        assertFailsWith<McpToolException> { svc.requireCode(customer, "BBB") }
        // own code and non-customer roles pass (no exception)
        svc.requireCode(customer, "AAA")
        svc.requireCode(grant("crete-tv", UserRole.NORMAL_USER), "BBB")
        svc.requireCode(grant("crete-tv", UserRole.REPORT_VIEWER), "BBB")
    }
}
