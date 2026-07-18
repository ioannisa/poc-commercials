package eu.anifantakis.commercials.mailer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The branding rules the operator asked for: EVERY email opens with the hosting
 * group's name, station-specific mail also names the station, and a fresh
 * account carries the installation's own connection details (web + MCP) - all
 * driven by the values server.yaml exposes, never hard-coded.
 */
class EmailBrandingTest {

    private val org = "Κρητική Ραδιοτηλεόραση"
    private val web = "https://commercials.anifantakis.eu"
    private val mcp = "https://commercials.anifantakis.eu/mcp/http"

    @Test
    fun `new account email carries org name and both connection URLs`() {
        val html = renderTempPasswordEmail(
            orgName = org, username = "ioannisa", tempPassword = "8ERJA45DE7VQ",
            newAccount = true, webUrl = web, mcpUrl = mcp,
        )
        assertContains(html, org)                       // masthead
        assertContains(html, "8ERJA45DE7VQ")            // temp password
        assertContains(html, "Πρόσβαση στην εφαρμογή")  // connection guide heading
        assertContains(html, web)                       // web login URL
        assertContains(html, mcp)                       // MCP connector URL
    }

    @Test
    fun `reset email is branded but has no connection guide`() {
        val html = renderTempPasswordEmail(
            orgName = org, username = "ioannisa", tempPassword = "TMP123",
            newAccount = false, webUrl = web, mcpUrl = mcp,
        )
        assertContains(html, org)
        assertFalse("Πρόσβαση στην εφαρμογή" in html, "reset must not onboard connections")
        assertFalse(mcp in html, "reset must not show the MCP URL")
    }

    @Test
    fun `new account with no public config omits the connection guide entirely`() {
        val html = renderTempPasswordEmail(
            orgName = org, username = "ioannisa", tempPassword = "TMP123",
            newAccount = true, webUrl = null, mcpUrl = null,
        )
        assertContains(html, org)
        assertFalse("Πρόσβαση στην εφαρμογή" in html, "no URLs configured -> no guide")
    }

    @Test
    fun `only the web URL configured shows web but not MCP guidance`() {
        val html = renderTempPasswordEmail(
            orgName = org, username = "ioannisa", tempPassword = "TMP123",
            newAccount = true, webUrl = web, mcpUrl = null,
        )
        assertContains(html, web)
        assertFalse("/mcp/http" in html, "no MCP host -> no connector line")
    }

    @Test
    fun `every auth email opens with the org name`() {
        assertContains(renderPasswordResetEmail(org, "123456", 15), org)
        assertContains(renderConsentOtpEmail(org, "123456", "Claude", 10), org)
        assertContains(
            renderConnectionApprovalEmail(org, "Claude", "user@example.com", "1.2.3.4", "UA", "https://x/approve"),
            org,
        )
    }

    @Test
    fun `schedule email shows the group name above the station`() {
        val html = renderScheduleEmail(
            ScheduleEmailData(
                stationName = "Crete TV",
                orgName = org,
                customerName = "ACME",
                year = 2026, month = 7,
                spots = emptyList(),
            )
        )
        assertContains(html, org)
        assertContains(html, "Crete TV")
        // group name must precede the station name in the masthead
        assertTrue(html.indexOf(org) < html.indexOf("Crete TV"), "org must lead the masthead")
    }

    @Test
    fun `masthead escapes html in names`() {
        val header = emailMasthead("<script>", "A & B")
        assertContains(header, "&lt;script&gt;")
        assertContains(header, "A &amp; B")
    }
}
