package eu.anifantakis.commercials.window

import eu.anifantakis.commercials.core.presentation.string_resources.Language
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationManager
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.localized
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A single-instance guard can only be proven with REAL processes. An in-JVM test
 * would exercise [java.nio.channels.OverlappingFileLockException] - the JVM's own
 * bookkeeping - and tell us nothing about whether the OPERATING SYSTEM keeps a
 * second `java` process out, which is the only thing that matters here.
 *
 * So this forks actual JVMs, and asserts the two properties the guard lives or dies by:
 *
 *  1. while one holds the lock, another is refused;
 *  2. when the holder is KILLED (-9, no cleanup, no shutdown hook), the lock is gone
 *     and the next one gets straight in.
 *
 * (2) is the one that matters in the field. Every "write a PID file and check it on
 * startup" scheme passes (1) and fails (2): the app crashes once, the stale file
 * survives, and the user can never open their app again. A FileChannel lock is held
 * by the process, so the kernel drops it when the process dies, however it dies.
 */
class SingleInstanceTest {

    /**
     * Runs [SingleInstance.acquire] in a fresh JVM, on the SAME classpath, against a
     * home directory we control - so the test never touches the developer's real
     * `~/.commercials-manager/instance.lock`.
     *
     * Prints ACQUIRED or REFUSED, then (if it got the lock) holds it until killed.
     */
    private fun spawn(home: File): Process =
        ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", System.getProperty("java.class.path"),
            "-Duser.home=${home.absolutePath}",
            LockProbe::class.java.name,
        ).redirectErrorStream(true).start()

    private fun Process.firstLine(): String =
        inputStream.bufferedReader().readLine() ?: "<no output>"

    @Test
    fun `a second process is refused, and a KILLED first one frees the lock`() {
        val home = createTempDirectory("single-instance-test").toFile()
        try {
            // ── 1. First in. Gets the lock and holds it.
            val first = spawn(home)
            assertEquals("ACQUIRED", first.firstLine(), "the first instance must start")
            assertTrue(first.isAlive)

            // ── 2. Second, while the first still lives. Must bounce.
            val second = spawn(home)
            assertEquals(
                "REFUSED", second.firstLine(),
                "a second instance would attach to the running app's KSafe store",
            )
            second.waitFor()

            // ── 3. Kill the holder outright: SIGKILL, no shutdown hook, no cleanup.
            //       The lock file is left behind on disk, exactly as after a crash.
            first.destroyForcibly()
            first.waitFor()
            assertTrue(
                File(home, ".commercials-manager/instance.lock").exists(),
                "the file survives a crash - which is precisely why the guard must not be the FILE",
            )

            // ── 4. And the next one gets straight in. This is the whole point.
            val third = spawn(home)
            assertEquals(
                "ACQUIRED", third.firstLine(),
                "a crash must never lock the user out of their own app",
            )
            third.destroyForcibly()
            third.waitFor()
        } finally {
            home.deleteRecursively()
        }
    }

    /**
     * The bounced instance greets the user in the language of the window ALREADY on
     * their screen - and reaches it WITHOUT opening KSafe, which is the running app's
     * DataStore and not multi-process safe (it even runs migrations on init).
     *
     * The holder publishes a two-character sidecar; the bouncer reads it. That is the
     * entire mechanism, and this pins both halves of it.
     */
    @Test
    fun `the refused instance speaks the RUNNING instance's language, without touching KSafe`() {
        val home = createTempDirectory("single-instance-lang").toFile()
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            // Nobody has published yet -> resolveStartup falls through to OS locale, then English.
            assertEquals("", SingleInstance.runningLanguage())

            // The running window is in Greek. It publishes; we read it back.
            SingleInstance.publishLanguage(Language.EL.code)
            assertEquals("el", SingleInstance.runningLanguage())

            // ...and that is enough to render the dialog from the real catalog.
            LocalizationManager.setLanguage(
                LocalizationManager.resolveStartup(SingleInstance.runningLanguage())
            )
            assertEquals(
                "Η εφαρμογή εκτελείται ήδη",
                StringKey.DESKTOP_ALREADY_RUNNING_TITLE.localized(),
            )

            // A language switch in the running window reaches the next bounced instance.
            SingleInstance.publishLanguage(Language.HE.code)
            LocalizationManager.setLanguage(
                LocalizationManager.resolveStartup(SingleInstance.runningLanguage())
            )
            assertEquals(
                "האפליקציה כבר פועלת",
                StringKey.DESKTOP_ALREADY_RUNNING_TITLE.localized(),
                "the sidecar must track the LIVE language, not just the one at launch",
            )

            // The sidecar is its OWN file: on Windows a FileChannel lock is mandatory,
            // so a reader of the LOCK file could be refused outright.
            assertTrue(File(home, ".commercials-manager/language").isFile)
        } finally {
            System.setProperty("user.home", previous)
            LocalizationManager.setLanguage(Language.EN)
            home.deleteRecursively()
        }
    }
}

/** Spawned by the test above, in its own JVM. Not a test itself. */
internal object LockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val got = SingleInstance.acquire()
        println(if (got) "ACQUIRED" else "REFUSED")
        System.out.flush()
        // Hold the lock (and the process) until the test kills us.
        if (got) Thread.sleep(60_000)
    }
}
