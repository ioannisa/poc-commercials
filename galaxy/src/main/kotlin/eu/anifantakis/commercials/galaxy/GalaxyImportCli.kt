package eu.anifantakis.commercials.galaxy

import eu.anifantakis.commercials.server.scheduler.GroupDb
import eu.anifantakis.commercials.server.stations.GroupConfig
import java.io.File
import java.sql.DriverManager

/**
 * Command-line front for the Galaxy (new ERP) import - reconciles the flat
 * Galaxy export against an ALREADY MIGRATED group schema (see GalaxyImporter)
 * and inserts what is genuinely new. Dry-run by default; pass --apply to write.
 *
 *   ./gradlew :server:galaxyImportCli --args="\
 *        --galaxy-dir ~/Downloads/ctv/ss/galaxy2 \
 *        --old-export-dir ~/Downloads/ctv/ss/galaxy/customer \
 *        --schema commercials_crete_group \
 *        [--company 001] [--host localhost] [--port 3306] \
 *        [--user root] [--password ...] [--review-out galaxy-review.csv] \
 *        [--apply]"
 *
 * --galaxy-dir      the flat delivery (COMMERCIALENTRY.txt + CUSTOMER.csv + ...)
 * --old-export-dir  the OLD raw export's customer folder (customer.txt /
 *                   TRADER.txt / GXTRADERSITE.txt) - the FULL party dictionary;
 *                   omit only when the uncapped CUSTOMER delivery has arrived.
 * --company         Galaxy company code to import (001 ΙΚΑΡΟΣ → crete group,
 *                   003 Channel 4, 004 Sitia; 002 is the press - out of scope).
 */
fun runGalaxyImportCli(args: Array<String>) {
    try {
        run(parse(args))
    } catch (e: Exception) {
        System.err.println("\nGALAXY IMPORT FAILED: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

/** Prints the import summary in the CLI's tabular style. */
internal fun printGalaxySummary(s: GalaxyImporter.Summary, apply: Boolean) {
    println(
        """
        ─── Galaxy import summary ${if (apply) "(APPLIED)" else "(DRY RUN)"} ───
        flat-export lines       ${s.linesTotal} (company slice: ${s.linesCompany}, documents: ${s.docsSeen})
          without doc number    ${s.linesNoDocNumber}
        parties referenced      ${s.partiesReferenced}
          already stamped       ${s.partiesAlreadyStamped}
          matched by code       ${s.partiesByCode}
          matched by ΑΦΜ        ${s.partiesByVat}
          inserted (new)        ${s.partiesInserted}
          inserted (no info)    ${s.partiesInsertedBare}
          ambiguous → review    ${s.partiesAmbiguous}
          gxid conflicts        ${s.partiesConflict}
        item catalog            ${s.itemsReferenced} referenced
          already stamped       ${s.itemsAlreadyStamped}
          stamped now           ${s.itemsStamped} (shadowed: ${s.itemsShadowed})
          inserted (new)        ${s.itemsInserted}
        native Τριγωνικά (9010) twins skipped: ${s.twinDocsSkipped} docs / ${s.twinRowsSkipped} lines
          untwinned → flagged   ${s.untwinned9010Docs}
        documents examined      ${s.docsExamined}
          already galaxy-keyed  ${s.docsAlreadyKeyed}
          matched & stamped     ${s.docsMatched}
          inserted (new)        ${s.docsInserted} (+${s.docLinesInserted} lines; ${s.docsExcludedFromReports} off-reports)
          ambiguous → review    ${s.docsAmbiguous}
          payer unresolved      ${s.docsPayerUnresolved}
        review entries          ${s.reviews.size}
        rejected export records ${s.rejectedRecords}
        ──────────────────────────────────────────────
        """.trimIndent()
    )
}

private class Opts(private val map: Map<String, String>, private val flags: Set<String>) {
    fun require(key: String): String = map[key] ?: error("Missing required option --$key")
    fun get(key: String, default: String): String = map[key] ?: default
    fun optional(key: String): String? = map[key]
    fun has(flag: String): Boolean = flag in flags
}

private fun parse(args: Array<String>): Opts {
    val map = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "Unexpected argument '$a' (options start with --)" }
        val key = a.removePrefix("--")
        if (key == "apply") { flags += key; i++ }
        else {
            require(i + 1 < args.size) { "Missing value for --$key" }
            map[key] = args[i + 1]; i += 2
        }
    }
    return Opts(map, flags)
}

/**
 * `~/...` reaches us verbatim when the whole --args string is quoted (the
 * shell only expands an unquoted tilde) - expand it here so both spellings
 * work.
 */
private fun path(value: String): File = File(
    when {
        value == "~" -> System.getProperty("user.home")
        value.startsWith("~/") -> System.getProperty("user.home") + value.substring(1)
        else -> value
    }
)

private fun run(opts: Opts) {
    val galaxyDir = path(opts.require("galaxy-dir"))
    require(galaxyDir.isDirectory) { "--galaxy-dir is not a directory: $galaxyDir" }
    val oldExportDir = opts.optional("old-export-dir")?.let {
        path(it).also { d -> require(d.isDirectory) { "--old-export-dir is not a directory: $d" } }
    }
    val schema = opts.require("schema")
    val company = opts.get("company", "001")
    val host = opts.get("host", "localhost")
    val port = opts.get("port", "3306")
    val user = opts.get("user", "root")
    val password = opts.optional("password") ?: run {
        print("MySQL password: "); readLine().orEmpty()
    }
    val apply = opts.has("apply")
    val reviewOut = path(opts.get("review-out", "galaxy-review.csv"))

    println()
    println("═══ Commercials Manager - Galaxy (new ERP) import ═══")
    println("Schema:     $host:$port / $schema")
    println("Galaxy dir: $galaxyDir")
    println("Dictionary: ${oldExportDir ?: "(capped CUSTOMER.csv only - ⚠ pass --old-export-dir)"}")
    println("Company:    $company")
    println("Mode:       ${if (apply) "APPLY" else "DRY RUN (pass --apply to write)"}")
    println()

    if (apply) {
        // Single-sourced schema evolution: GroupDb.bootstrap() is the same
        // guarded DDL the server runs at startup - it adds the galaxy key
        // columns to schemas created by an older build. Apply-only so the
        // dry-run stays strictly read-only.
        val group = GroupDb(
            GroupConfig(
                id = "galaxy-import",
                jdbcUrl = "jdbc:mysql://$host:$port/$schema?useUnicode=true&characterEncoding=utf8" +
                    "&allowPublicKeyRetrieval=true&useSSL=false",
                username = user,
                password = password,
            ),
            maxPoolSize = 2,
        )
        try {
            group.bootstrap()
        } finally {
            group.close()
        }
    }

    val url = "jdbc:mysql://$host:$port/?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false"
    DriverManager.getConnection(url, user, password).use { c ->
        val summary = GalaxyImporter(c, schema, log = { println("  $it") }).import(
            GalaxyImporter.Config(
                galaxyDir = galaxyDir,
                oldExportDir = oldExportDir,
                companyCode = company,
                reviewOut = reviewOut,
            ),
            apply = apply,
        )
        printGalaxySummary(summary, apply)
        if (!apply) println("Nothing was written. Re-run with --apply to perform the import.")
    }
}
