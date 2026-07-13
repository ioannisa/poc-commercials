package eu.anifantakis.commercials.window

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * ONE desktop instance per OS user. The second one refuses to start.
 *
 * ── Why this exists ──
 *
 * Two instances on one machine would share ONE session: the KSafe store is keyed by
 * app namespace under the user's home
 * (`~/.eu_anifantakis_ksafe/eu.anifantakis.commercials/`), so both processes read and
 * write the same encrypted blob and hold the same bearer token. Nothing here is
 * unsafe - [eu.anifantakis.commercials.core.data.session.SessionKeepAlive] renews the
 * token's LIFETIME and never its VALUE, precisely so that co-owners of a store cannot
 * knock each other out - but the two would still fight over the smaller writes
 * (selected station, window geometry, preferences), each holding a stale in-memory
 * cache of the other's last write, and a logout in one would silently log out the
 * other. A scheduler is a single-window tool; two of them is a bug, not a feature.
 *
 * ── Why a file LOCK and not a lock FILE ──
 *
 * The lock is held by the PROCESS, not written into the file. The OS releases it when
 * the process dies - normally, on a crash, or on `kill -9` - so a leftover file can
 * never lock the user out of their own app. That is the failure mode of every
 * "write my PID and check on startup" scheme, and it is exactly the one nobody
 * notices until an app crashes in the field and refuses to reopen.
 *
 * The [RandomAccessFile] is deliberately never closed: closing the channel drops the
 * lock. It lives as long as the process, which is the point.
 *
 * ── Scope: per OS USER, not per machine ──
 *
 * Two people signed in to the same computer get one app each. That is right, and it
 * matches the thing being protected: the KSafe store lives under `user.home`, so two
 * OS users never shared a session in the first place.
 *
 * Not the browser's business: tabs share a token by design (requirement 5), and two
 * BROWSERS are two separate stores, hence two independent sessions - exactly like two
 * laptops. This guards only the JVM desktop app, where the store is a shared file.
 */
object SingleInstance {

    /** Held for the life of the process. Never closed - closing would release the lock. */
    private var lock: FileLock? = null
    private var handle: RandomAccessFile? = null

    // Under the user's home, next to (not inside) KSafe's own directory: the store is
    // KSafe's to own, and per-user is the granularity we are actually protecting.
    private val dir: File get() = File(System.getProperty("user.home"), ".commercials-manager")

    /**
     * The running instance's CURRENT language code, published by [publishLanguage] -
     * a two-character plain-text file, and deliberately not a KSafe read.
     *
     * ── Why the running app has to hand it over ──
     *
     * The language does live in KSafe, but a bounced instance must not open KSafe to
     * find out. That store is a DataStore file owned by the process that is already
     * running, it is not multi-process safe, and KSafe RUNS MIGRATIONS when it
     * initialises (the `.migrated` files in its directory are the receipts). Opening
     * it to fetch two characters would risk writing to a store we came here
     * specifically to leave alone.
     *
     * So the holder publishes; the bouncer reads. No KSafe, no DataStore, no Koin,
     * no decryption - one `readText` of a file nobody locks. And it reports the
     * LIVE language of the window on screen, not a persisted value that may be a
     * language-switch behind it.
     *
     * Its own file, never the lock file: on Windows a FileChannel lock is MANDATORY,
     * so another process reading the locked file could be refused outright.
     *
     * Empty when no instance has run since the app gained this feature, which is
     * exactly what `LocalizationManager.resolveStartup("")` is built for - it falls
     * through to the OS locale, then English.
     */
    fun runningLanguage(): String =
        try {
            File(dir, "language").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }

    /**
     * Publishes the language this instance is showing, for a future second instance
     * to greet the user in. Called by the holder on startup and on every switch.
     *
     * Best-effort by design: a failure here costs a bounced instance its Greek, and
     * nothing else. It must never take the running app down.
     */
    fun publishLanguage(code: String) {
        try {
            dir.mkdirs()
            File(dir, "language").writeText(code)
        } catch (_: Exception) {
            // A read-only home, a full disk. The dialog falls back to the OS locale.
        }
    }

    /**
     * True if we are the only instance and may proceed. False if another instance
     * already holds the lock - the caller must exit WITHOUT touching Koin or KSafe,
     * so the running app's store is never disturbed.
     */
    fun acquire(): Boolean {
        val file = File(dir.apply { mkdirs() }, "instance.lock")

        return try {
            val raf = RandomAccessFile(file, "rw")
            // tryLock returns null (or throws Overlapping, in-JVM) when someone else
            // holds it. It NEVER blocks - a second instance must fail fast, not hang.
            val acquired = raf.channel.tryLock()
            if (acquired == null) {
                raf.close()
                false
            } else {
                handle = raf
                lock = acquired
                true
            }
        } catch (_: OverlappingFileLockException) {
            // Same JVM already holds it. Cannot happen in production (main() runs once),
            // but a test or a dev tool re-entering must get the same honest "no".
            false
        } catch (_: Exception) {
            // A read-only home, an exotic filesystem with no lock support, a container
            // with a weird mount. Refusing to start over a LOCK failure would be worse
            // than the duplicate instance it was meant to prevent, so: let them in.
            true
        }
    }
}
