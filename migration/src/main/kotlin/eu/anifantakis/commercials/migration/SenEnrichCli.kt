package eu.anifantakis.commercials.migration

import java.io.File
import java.sql.DriverManager

/**
 * Command-line front for the SEN (Oracle ERP) enrichment - runs against an
 * ALREADY MIGRATED station schema, replacing the deterministic fakes with the
 * real ERP master data (see SenErpEnricher). The same enrichment also runs as
 * the final migration step when MigrationCli / the in-app Migration screen is
 * given a SEN folder. Dry-run by default; pass --apply to write.
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.migration.SenEnrichToolKt \
 *        --sen-dir /path/to/SEN --schema commercials_ctv_migrated \
 *        [--host localhost] [--port 3306] [--user root] [--password ...] \
 *        [--apply]
 *
 * The folder holds one tab-delimited export per Oracle table (`lee.csv`,
 * `cus.csv`, `adr.csv`, `sld.csv`, `sdt.csv`, ...); whichever files are
 * present drive the corresponding phases. A headerless export needs its
 * sidecar `<name>.headers.txt` ("<pos>\t<COLUMN>" lines) next to it.
 */
fun runSenEnrichCli(args: Array<String>) {
    try {
        run(parse(args))
    } catch (e: Exception) {
        System.err.println("\nSEN ENRICHMENT FAILED: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

/** Prints the enrichment summary in the CLI's tabular style. */
internal fun printSenSummary(summary: SenErpEnricher.Summary, apply: Boolean) {
    println(
        """
        ─── SEN enrichment summary ${if (apply) "(APPLIED)" else "(DRY RUN)"} ───
        customers examined     ${summary.customersExamined}
          resolved via lee id  ${summary.customersResolvedViaLee}
          resolved via trader  ${summary.customersResolvedViaCus}
          renamed              ${summary.customersRenamed}
          real VAT set         ${summary.customersVatSet}
          ERP code applied     ${summary.customersRecoded} (email archive remapped: ${summary.emailLogRecoded})
          contacts filled      ${summary.contactsFilled}
          already correct      ${summary.customersUnchanged}
          not in export        ${summary.customersUnresolved}
        contracts examined     ${summary.contractsExamined}
          real period applied  ${summary.contractsDated}
          agreed qty filled    ${summary.contractsQtySet}
          gift flag corrected  ${summary.contractsGiftFixed}
          ERP has no dates     ${summary.contractsNoErpDates}
          not in export        ${summary.contractsNotInSld}
          provisional backfill ${summary.contractsBackfilled}
        spot-type catalog      ${summary.spotTypesEnriched} entries got their ERP sales item
          spots linked to type ${summary.spotsTypeLinked} (adoption of a pre-catalog station)
        rejected export records ${summary.rejectedRecords}
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

private fun run(opts: Opts) {
    val dir = File(opts.require("sen-dir"))
    require(dir.isDirectory) { "--sen-dir is not a directory: $dir" }
    val schema = opts.require("schema")
    val host = opts.get("host", "localhost")
    val port = opts.get("port", "3306")
    val user = opts.get("user", "root")
    val password = opts.optional("password") ?: run {
        print("MySQL password: "); readLine().orEmpty()
    }
    val apply = opts.has("apply")
    // Optional schema holding the legacy dump's z_commercials (message->ERP-line
    // links) for EXACT per-spot sales items; the migration pipeline passes its
    // scratch schema automatically - this flag serves post-hoc runs.
    val legacyScratch = opts.optional("legacy-scratch")

    println()
    println("═══ Commercials Manager - SEN (Oracle ERP) enrichment ═══")
    println("Schema:  $host:$port / $schema")
    println("SEN dir: $dir")
    legacyScratch?.let { println("Scratch: $it (z_commercials line links)") }
    println("Mode:    ${if (apply) "APPLY" else "DRY RUN (pass --apply to write)"}")
    println()

    val url = "jdbc:mysql://$host:$port/?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false"
    DriverManager.getConnection(url, user, password).use { c ->
        val summary = SenErpEnricher(c, schema) { println("  $it") }.enrich(dir, apply, legacyScratch)
        printSenSummary(summary, apply)
        if (!apply) println("Nothing was written. Re-run with --apply to perform the update.")
    }
}
