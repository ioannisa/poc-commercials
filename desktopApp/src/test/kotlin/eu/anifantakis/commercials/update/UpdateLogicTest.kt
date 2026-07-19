package eu.anifantakis.commercials.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VersionCompareTest {

    @Test
    fun `equal versions compare zero`() {
        assertEquals(0, compareVersions("1.2.0", "1.2.0"))
        assertEquals(0, compareVersions("1.2", "1.2.0")) // missing segment = 0
        assertEquals(0, compareVersions(" 1.2.0 ", "1.2.0")) // whitespace tolerated
    }

    @Test
    fun `numeric ordering beats lexicographic`() {
        // The pair a string sort gets WRONG - the whole reason this exists.
        assertTrue(compareVersions("1.10.0", "1.9.3") > 0)
        assertTrue(compareVersions("1.9.3", "1.10.0") < 0)
    }

    @Test
    fun `patch and minor bumps order correctly`() {
        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(compareVersions("1.1.0", "1.0.9") > 0)
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `malformed segments degrade to zero instead of throwing`() {
        assertEquals(0, compareVersions("1.x.0", "1.0.0"))
        assertTrue(compareVersions("garbage", "0.0.1") < 0)
    }
}

class UpdateDecisionTest {

    private val base = "http://localhost:8080"
    private fun info(
        latest: String? = null,
        minSupported: String? = null,
        installers: Map<String, String> = mapOf("dmg" to "/downloads/a.dmg", "msi" to "/downloads/a.msi", "deb" to "/downloads/a.deb"),
    ) = VersionInfo(serverVersion = "1.0.0", latest = latest, minSupported = minSupported, installers = installers)

    @Test
    fun `no advertisement means none`() {
        assertIs<UpdateDecision.None>(UpdateCheck.decide("1.0.0", info(latest = null), base))
    }

    @Test
    fun `up to date means none`() {
        assertIs<UpdateDecision.None>(UpdateCheck.decide("1.2.0", info(latest = "1.2.0"), base))
        assertIs<UpdateDecision.None>(UpdateCheck.decide("1.3.0", info(latest = "1.2.0"), base))
    }

    @Test
    fun `newer version offers an optional update`() {
        val d = UpdateCheck.decide("1.0.0", info(latest = "1.1.0"), base)
        assertIs<UpdateDecision.Available>(d)
        assertEquals("1.1.0", d.latest)
        assertEquals(false, d.mandatory)
    }

    @Test
    fun `below minSupported is mandatory`() {
        val d = UpdateCheck.decide("1.0.0", info(latest = "1.2.0", minSupported = "1.1.0"), base)
        assertIs<UpdateDecision.Available>(d)
        assertEquals(true, d.mandatory)
    }

    @Test
    fun `at or above minSupported stays optional`() {
        val d = UpdateCheck.decide("1.1.0", info(latest = "1.2.0", minSupported = "1.1.0"), base)
        assertIs<UpdateDecision.Available>(d)
        assertEquals(false, d.mandatory)
    }

    @Test
    fun `newer version without an installer for this OS stays silent`() {
        assertIs<UpdateDecision.None>(
            UpdateCheck.decide("1.0.0", info(latest = "1.1.0", installers = emptyMap()), base)
        )
    }
}

class InstallerUrlTest {

    @Test
    fun `os name maps to the installer format`() {
        assertEquals(HostOs.MAC, HostOs.detect("Mac OS X"))
        assertEquals(HostOs.WINDOWS, HostOs.detect("Windows 11"))
        assertEquals(HostOs.LINUX, HostOs.detect("Linux"))
        assertEquals("dmg", HostOs.MAC.installerKey)
        assertEquals("msi", HostOs.WINDOWS.installerKey)
        assertEquals("deb", HostOs.LINUX.installerKey)
    }

    @Test
    fun `relative urls resolve against the base url`() {
        assertEquals(
            "http://localhost:8080/downloads/a.msi",
            UpdateCheck.installerUrlForCurrentOs(
                mapOf("msi" to "/downloads/a.msi"), "http://localhost:8080/", os = HostOs.WINDOWS,
            ),
        )
    }

    @Test
    fun `absolute urls pass through untouched`() {
        assertEquals(
            "https://cdn.example.gr/a.dmg",
            UpdateCheck.installerUrlForCurrentOs(
                mapOf("dmg" to "https://cdn.example.gr/a.dmg"), "http://localhost:8080", os = HostOs.MAC,
            ),
        )
    }

    @Test
    fun `spaces in installer paths are percent-encoded`() {
        // jpackage's natural artifact name, pasted as-is by an admin.
        assertEquals(
            "http://localhost:8080/downloads/Commercials%20Manager%202-1.1.0.msi",
            UpdateCheck.installerUrlForCurrentOs(
                mapOf("msi" to "/downloads/Commercials Manager 2-1.1.0.msi"),
                "http://localhost:8080", os = HostOs.WINDOWS,
            ),
        )
        assertEquals(
            "https://cdn.example.gr/CM%202.dmg",
            UpdateCheck.installerUrlForCurrentOs(
                mapOf("dmg" to "https://cdn.example.gr/CM 2.dmg"), "http://x", os = HostOs.MAC,
            ),
        )
    }

    @Test
    fun `download file name comes url-decoded from the last segment`() {
        assertEquals(
            "Commercials Manager 2-1.1.0.msi",
            UpdateDownloader.fileNameFrom("http://x/downloads/Commercials%20Manager%202-1.1.0.msi"),
        )
        assertEquals("a.deb", UpdateDownloader.fileNameFrom("http://x/downloads/a.deb?token=1"))
    }
}
