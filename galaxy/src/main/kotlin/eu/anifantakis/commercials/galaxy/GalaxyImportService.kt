package eu.anifantakis.commercials.galaxy

import eu.anifantakis.commercials.server.scheduler.GroupDb
import eu.anifantakis.commercials.server.stations.StationRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.sql.DriverManager
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * The engine behind the Galaxy Bridge screen: one Galaxy import at a time,
 * driven by the super admin over the API and executed here on the server.
 * Deliveries are UPLOADED from the operator's machine (zip → [deliveriesDir]);
 * the OLD-export party dictionary is uploaded once and persists in
 * [dictionaryDir] until the uncapped CUSTOMER delivery makes it unnecessary
 * (GALAXY-MATCHER.md §9.6).
 *
 * State machine (simpler than the migration's - no mid-run decision point):
 *
 *   IDLE ──start()──▶ RUNNING ──▶ DONE
 *                        │
 *                        └──▶ FAILED        (reset() ▶ IDLE)
 *
 * A DONE/FAILED run may also start() again directly - each run replaces the
 * previous snapshot, mirroring how the CLI is re-run per delivery.
 *
 * Koin singleton; transitions are synchronized, the import runs on a
 * background thread appending to [logLines] for live polling.
 */
class GalaxyImportService(
    private val registry: StationRegistry,
    /** Overridable for tests; production default is under the server CWD. */
    private val baseDir: File = File("galaxy-imports"),
) {

    enum class State { IDLE, RUNNING, DONE, FAILED }
    enum class Mode { DRY_RUN, APPLY }
    enum class UploadKind { DELIVERY, DICTIONARY }

    data class Snapshot(
        val state: State,
        val mode: Mode?,
        val log: List<String>,
        /** Null unless a step is running; total==0 ⇒ indeterminate bar. */
        val progress: Progress?,
        val summary: GalaxyImporter.Summary?,
        val error: String?,
        /** The hosted groups an import may target (existing groups only). */
        val groups: List<GroupOption>,
        val deliveries: List<DeliveryInfo>,
        val dictionaryPresent: Boolean,
        val groupId: String?,
        val companyCode: String?,
        val delivery: String?,
    )

    data class GroupOption(val id: String, val name: String, val schema: String)

    data class DeliveryInfo(val name: String, val files: Int, val uploadedAtMillis: Long)

    /** Same honesty contract as the migration's bar: total 0 = indeterminate. */
    data class Progress(val label: String, val done: Long, val total: Long)

    data class StartRequest(
        val groupId: String,
        /** Galaxy company: 001 ΙΚΑΡΟΣ (crete), 003 Channel 4, 004 Σητεία. */
        val companyCode: String,
        /** Name of an uploaded delivery folder under [deliveriesDir]. */
        val delivery: String,
        val apply: Boolean,
    )

    private val serviceLog = LoggerFactory.getLogger("GalaxyImport")

    @Volatile private var state = State.IDLE
    @Volatile private var mode: Mode? = null
    private val logLines = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var progress: Progress? = null
    @Volatile private var summary: GalaxyImporter.Summary? = null
    @Volatile private var error: String? = null
    @Volatile private var request: StartRequest? = null

    private val deliveriesDir get() = File(baseDir, "deliveries")
    private val dictionaryDir get() = File(baseDir, "dictionary")

    /** Delivery labels become directory names - keep them path-safe. */
    private val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}")
    private val companyPattern = Regex("[0-9]{3}")

    fun snapshot() = Snapshot(
        state = state,
        mode = mode,
        log = logLines.toList(),
        progress = progress,
        summary = summary,
        error = error,
        groups = registry.groups.map {
            GroupOption(it.id, it.name ?: it.id, it.jdbcUrl.substringAfterLast('/').substringBefore('?'))
        },
        deliveries = deliveries(),
        dictionaryPresent = dictionaryReady(),
        groupId = request?.groupId,
        companyCode = request?.companyCode,
        delivery = request?.delivery,
    )

    @Synchronized
    fun reset() {
        check(state != State.RUNNING) { "A Galaxy import is still running" }
        state = State.IDLE
        mode = null; logLines.clear(); progress = null; summary = null
        error = null; request = null
    }

    /**
     * Validates fast on the caller's thread (mistakes surface as immediate
     * 400s through StatusPages), then imports in the background.
     */
    @Synchronized
    fun start(req: StartRequest) {
        check(state != State.RUNNING) { "A Galaxy import is already running" }
        val group = requireNotNull(registry.groupConfig(req.groupId)) {
            "Unknown group '${req.groupId}' - the Galaxy import targets an already-hosted group"
        }
        require(companyPattern.matches(req.companyCode)) { "Company code must be 3 digits, e.g. 001" }
        val deliveryDir = deliveryContentDir(req.delivery)
            ?: throw IllegalArgumentException(
                "Delivery '${req.delivery}' has no COMMERCIALENTRY export - upload the delivery zip first"
            )
        // The dictionary is optional by contract (Config.oldExportDir is
        // nullable) but §9.6 makes it required in practice until the uncapped
        // CUSTOMER delivery: without it new parties lose names/ΑΦΜ.
        val dictDir = dictionaryDir.takeIf { dictionaryReady() }

        logLines.clear(); progress = null; summary = null; error = null
        request = req
        mode = if (req.apply) Mode.APPLY else Mode.DRY_RUN
        state = State.RUNNING
        val schema = group.jdbcUrl.substringAfterLast('/').substringBefore('?')
        log("Galaxy import → group '${group.id}' ($schema), company ${req.companyCode}, delivery '${req.delivery}'" +
            if (req.apply) " [APPLY]" else " [DRY RUN]")
        if (dictDir == null) log("⚠ no party dictionary uploaded - new parties will import name-only")

        thread(name = "galaxy-import") {
            try {
                if (req.apply) {
                    // Single-sourced schema evolution, same as the CLI: adds the
                    // galaxy key columns on schemas created by an older build.
                    log("Preparing target schema (GroupDb bootstrap)")
                    val db = GroupDb(group, maxPoolSize = 2)
                    try {
                        db.bootstrap()
                    } finally {
                        db.close()
                    }
                }
                DriverManager.getConnection(group.jdbcUrl, group.username, group.password).use { c ->
                    val result = GalaxyImporter(
                        c, schema,
                        log = ::log,
                        onStep = { done, total, label ->
                            progress = Progress(label, done.toLong(), total.toLong())
                        },
                    ).import(
                        GalaxyImporter.Config(
                            galaxyDir = deliveryDir,
                            oldExportDir = dictDir,
                            companyCode = req.companyCode,
                            reviewOut = null,   // reviews travel in the snapshot instead
                        ),
                        apply = req.apply,
                    )
                    summary = result
                }
                progress = null
                log(if (req.apply) "Import applied." else "Dry run complete. Nothing was written.")
                state = State.DONE
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Uploads
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Stores an uploaded zip: a DELIVERY lands in its own named folder (an
     * existing one is replaced); the DICTIONARY replaces [dictionaryDir].
     * Zip-slip protected; a single all-enclosing root folder in the zip is
     * stripped so the exports sit directly in the target dir.
     */
    fun saveUpload(kind: UploadKind, name: String, zip: InputStream) {
        val target = when (kind) {
            UploadKind.DELIVERY -> {
                require(namePattern.matches(name)) { "Delivery name must match ${namePattern.pattern}" }
                File(deliveriesDir, name)
            }
            UploadKind.DICTIONARY -> dictionaryDir
        }
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        unzipInto(zip, target)
        hoistSingleRoot(target)
        log("Uploaded ${kind.name.lowercase()} '${if (kind == UploadKind.DELIVERY) name else "dictionary"}' " +
            "(${target.walkTopDown().count { it.isFile }} files)")
    }

    private fun unzipInto(zip: InputStream, target: File) {
        val targetRoot = target.canonicalFile
        ZipInputStream(zip).use { zin ->
            var entry = zin.nextEntry
            var files = 0
            while (entry != null) {
                val out = File(target, entry.name)
                // zip-slip guard: every entry must resolve INSIDE the target
                require(out.canonicalFile.toPath().startsWith(targetRoot.toPath())) {
                    "Zip entry escapes the target folder: ${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (!out.name.startsWith(".") && !entry.name.contains("__MACOSX")) {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    files++
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
            require(files > 0) { "The zip contained no files" }
        }
    }

    /** `delivery.zip` holding `galaxy2/…` should serve its files at the root. */
    private fun hoistSingleRoot(target: File) {
        val children = target.listFiles()?.filterNot { it.name.startsWith(".") } ?: return
        val single = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        single.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
        single.delete()
    }

    private fun deliveries(): List<DeliveryInfo> =
        deliveriesDir.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.map { d -> DeliveryInfo(d.name, d.walkTopDown().count { it.isFile }, d.lastModified()) }
            ?: emptyList()

    /**
     * The folder actually holding COMMERCIALENTRY for a delivery, or null when
     * the export is missing. Kept for symmetry with future nested layouts.
     */
    private fun deliveryContentDir(name: String): File? {
        val dir = File(deliveriesDir, name)
        if (!dir.isDirectory) return null
        return dir.takeIf { d ->
            d.listFiles()?.any { it.name.equals("COMMERCIALENTRY.txt", ignoreCase = true) } == true
        }
    }

    /** The dictionary is usable when the old export's core files are there. */
    private fun dictionaryReady(): Boolean =
        dictionaryDir.isDirectory &&
            dictionaryDir.listFiles()?.any { it.name.equals("TRADER.txt", true) } == true &&
            dictionaryDir.listFiles()?.any { it.name.equals("customer.txt", true) } == true

    private fun log(line: String) {
        logLines += line
    }

    private fun fail(e: Exception) {
        // A half-filled bar next to FAILED reads as "still working" - clear it.
        progress = null
        error = e.message ?: e.toString()
        log("FAILED: ${error}")
        serviceLog.error("Galaxy import failed", e)
        state = State.FAILED
    }
}
