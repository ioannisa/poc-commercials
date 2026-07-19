package eu.anifantakis.commercials.core.data.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-user config dir is probed from the environment/filesystem, never
 * `os.name` - these pin the decision table (Windows APPDATA beats everything;
 * a real `~/Library/Application Support` marks macOS; XDG then `~/.config`
 * close the Linux tail).
 */
class PerUserConfigDirTest {

    private val home = File(System.getProperty("java.io.tmpdir"), "cfg-test-home-${System.nanoTime()}")

    @Test
    fun `appdata set means windows`() {
        assertEquals(
            File("C:/Users/x/AppData/Roaming/CommercialsManager"),
            perUserConfigDir(home = home, appData = "C:/Users/x/AppData/Roaming", xdgConfigHome = null),
        )
    }

    @Test
    fun `application support dir marks macos`() {
        File(home, "Library/Application Support").mkdirs()
        assertEquals(
            File(home, "Library/Application Support/CommercialsManager"),
            perUserConfigDir(home = home, appData = null, xdgConfigHome = null),
        )
    }

    @Test
    fun `xdg config home wins on linux`() {
        // no Application Support under this home
        val fresh = File(home, "linux").apply { mkdirs() }
        assertEquals(
            File("/xdg/CommercialsManager"),
            perUserConfigDir(home = fresh, appData = null, xdgConfigHome = "/xdg"),
        )
    }

    @Test
    fun `bare home falls back to dot-config`() {
        val fresh = File(home, "bare").apply { mkdirs() }
        assertEquals(
            File(fresh, ".config/CommercialsManager"),
            perUserConfigDir(home = fresh, appData = null, xdgConfigHome = null),
        )
    }

    @Test
    fun `blank appdata does not count as windows`() {
        val fresh = File(home, "blank").apply { mkdirs() }
        assertEquals(
            File(fresh, ".config/CommercialsManager"),
            perUserConfigDir(home = fresh, appData = "", xdgConfigHome = null),
        )
    }
}
