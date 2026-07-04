package eu.anifantakis.commercials.migration

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Command-line front for the legacy migration - same pipeline the in-app
 * Migration screen drives, for scripted/offline use. The host application
 * provides only its entry-point `main` (see the server's MigrationToolKt,
 * which keeps the historical class name so the `java -cp server.jar ...`
 * command is unchanged). Unlike the in-app service there is no running
 * StationRegistry here, so a migrated station is hosted at the next restart.
 *
 * Any missing option is prompted for interactively. Pipeline:
 *
 *   1. connect; create the target schema (or verify an existing EMPTY one)
 *   2. create the normalized tables (single-sourced DDL from :persistence,
 *      NO demo seeding)
 *   3. replay the dump's relevant tables into a scratch schema (streaming;
 *      includes the email archive - irrelevant tables are skipped)
 *   4. transform scratch -> target (see LegacyTransformer; missing ERP data
 *      is faked deterministically and flagged synthetic=TRUE)
 *   5. drop the scratch schema (unless --keep-scratch)
 *   6. append the station to server.yaml (unless --no-yaml)
 *
 * Requires MySQL 8+ on the TARGET server (window functions; MyISAM replay of
 * the 5.7-era dumps works fine there).
 */
fun runMigrationCli(args: Array<String>) {
    try {
        runMigration(parseArgs(args))
    } catch (e: Exception) {
        System.err.println("\nMIGRATION FAILED: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private class Options(private val map: MutableMap<String, String>, val flags: MutableSet<String>) {
    fun get(key: String, prompt: String, default: String? = null): String {
        map[key]?.let { return it }
        val suffix = default?.let { " [$it]" } ?: ""
        print("$prompt$suffix: ")
        val line = readLine()?.trim().orEmpty()
        val value = line.ifEmpty { default ?: "" }
        require(value.isNotEmpty()) { "A value for --$key is required" }
        map[key] = value
        return value
    }

    fun optional(key: String): String? = map[key]
    fun has(flag: String): Boolean = flag in flags
}

private fun parseArgs(args: Array<String>): Options {
    val map = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "Unexpected argument '$a' (options start with --)" }
        val key = a.removePrefix("--")
        if (key in setOf("create-schema", "keep-scratch", "no-yaml", "yes")) {
            flags += key; i++
        } else {
            require(i + 1 < args.size) { "Missing value for --$key" }
            map[key] = args[i + 1]; i += 2
        }
    }
    return Options(map, flags)
}

private fun runMigration(opts: Options) {
    val dumpPath = opts.get("dump", "Path to the legacy mysqldump file")
    val dumpFile = File(dumpPath)
    require(dumpFile.isFile) { "Dump file not found: $dumpPath" }

    val host = opts.get("host", "MySQL server host", "localhost")
    val port = opts.get("port", "MySQL server port", "3306").toInt()
    val user = opts.get("user", "MySQL username", "root")
    val password = opts.optional("password") ?: promptPassword()
    val schema = opts.get("schema", "Target schema name (e.g. commercials_mystation)")

    require(SCHEMA_NAME.matches(schema)) { "Schema name must match ${SCHEMA_NAME.pattern}" }

    val serverUrl = "jdbc:mysql://$host:$port/?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&allowPublicKeyRetrieval=true&useSSL=false"
    val targetJdbcUrl = "jdbc:mysql://$host:$port/$schema?useUnicode=true&characterEncoding=utf8"

    println()
    println("═══ Commercials Manager - Legacy Migration ═══")
    println("Dump:   $dumpPath (${dumpFile.length() / 1_048_576} MB)")
    println("Target: $host:$port / $schema")
    println()

    DriverManager.getConnection(serverUrl, user, password).use { c ->

        // ── 1. target schema ────────────────────────────────────────────
        val exists = schemaExists(c, schema)
        when {
            !exists && (opts.has("create-schema") || confirm(opts, "Schema '$schema' does not exist. Create it?")) -> {
                c.createStatement().use { it.executeUpdate("CREATE DATABASE `$schema` DEFAULT CHARACTER SET utf8mb4") }
                println("Created schema '$schema'.")
            }
            !exists -> error("Target schema '$schema' does not exist (pass --create-schema to create it)")
            else -> println("Using existing schema '$schema'.")
        }

        // ── 2. normalized tables, WITHOUT demo seeding ──────────────────
        prepareStationSchema(targetJdbcUrl, user, password)
        val placementCount = c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$schema`.placements").use { rs -> rs.next(); rs.getLong(1) }
        }
        require(placementCount == 0L) {
            "Target schema '$schema' already holds $placementCount placements - refusing to migrate into non-empty data. Use a fresh schema."
        }
        println("Normalized tables ready (demo seeding disabled for this station).")

        // ── 3. scratch replay ───────────────────────────────────────────
        val scratch = "${schema}_scratch"
        c.createStatement().use {
            it.executeUpdate("DROP DATABASE IF EXISTS `$scratch`")
            it.executeUpdate("CREATE DATABASE `$scratch` DEFAULT CHARACTER SET utf8mb4")
        }
        println("\nReplaying dump into scratch schema '$scratch' (irrelevant tables skipped)...")
        val replayed = DumpReplayer(c, scratch) { println(it) }.replay(dumpFile)
        replayed.forEach { (table, rows) -> println("  %-28s %,d rows".format(table, rows)) }

        // ── 4. pick the flow (each legacy DB can hold TV and radio) ─────
        val flowCounts = c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT m.forTV, COUNT(DISTINCT m.id), COUNT(sch.id)
                FROM `$scratch`.messages m LEFT JOIN `$scratch`.schedule sch ON sch.messageID = m.id
                GROUP BY m.forTV ORDER BY m.forTV
                """.trimIndent()
            ).use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getLong(2) to rs.getLong(3)) }
            }
        }
        println("\nFlows in this dump (a legacy DB can serve both a TV and a radio flow):")
        flowCounts.forEach { (tv, counts) ->
            println("  forTV=$tv (${if (tv == 1) "TV" else "radio"}): ${counts.first} spots, ${counts.second} placements")
        }
        val forTv = (opts.optional("fortv") ?: run {
            print("Which flow to migrate into '$schema'? (1=TV, 0=radio) [1]: ")
            readLine()?.trim().orEmpty().ifEmpty { "1" }
        }).toInt()
        require(forTv in flowCounts.keys) { "No messages with forTV=$forTv in this dump" }

        // ── 5. transform ────────────────────────────────────────────────
        println("\nTransforming (forTV=$forTv) ...")
        val summary = LegacyTransformer(c, scratch, schema, forTv) { println("  $it") }.run()

        println(
            """
            ─── Migration summary ───────────────────────────────
            break slots     ${summary.breaks} (from real airing times)
            programmes      ${summary.programs} (with operator-assigned colours)
            customers       ${summary.customers}  (${summary.customers - summary.customersSynthetic} recovered real names, ${summary.customersSynthetic} synthetic)
            contracts       ${summary.contracts}  (${summary.contractsSynthetic} synthetic)
            contract lines  ${summary.contractLines}
            triangular docs ${summary.triangularContracts} (spots land on the END client; ${summary.endClientsSynthesized} end clients known only by lee id)
            spots           ${summary.spots}
            placements      ${summary.placements}
            flow comments   ${summary.flowComments}
            print audits    ${summary.printAudits}
            emails archived ${summary.emails} (${summary.emailBodiesKept} bodies kept - cap per customer)
            price zones     ${summary.zones} (+${summary.zoneFillers} fillers; full price history)
            date range      ${summary.dateRange}
            coverage        migrated ${summary.placements} of ${summary.dumpScheduleRows} dump rows (otherFlow=${summary.otherFlowRows}, orphaned=${summary.orphanedRows}, invalidDate=${summary.zeroDateRows})
            ─────────────────────────────────────────────────────
            Synthetic rows are flagged (customers.synthetic / contracts.synthetic)
            so a future ERP import can find and replace them.
            """.trimIndent()
        )

        // ── 6. scratch cleanup ──────────────────────────────────────────
        if (opts.has("keep-scratch")) {
            println("\nScratch schema '$scratch' kept for inspection (drop it manually when done).")
        } else {
            c.createStatement().use { it.executeUpdate("DROP DATABASE `$scratch`") }
            println("\nScratch schema dropped.")
        }
    }

    // ── 7. server.yaml ────────────────────────────────────────────────
    if (!opts.has("no-yaml")) {
        val yamlPath = opts.optional("yaml") ?: "server.yaml"
        val stationId = opts.get("station-id", "Station id for server.yaml (e.g. my-station)")
        val stationName = opts.get("station-name", "Display name for the station dropdown")
        appendStationToYaml(
            file = File(yamlPath),
            id = stationId,
            name = stationName,
            jdbcUrl = targetJdbcUrl,
            username = user,
            password = password,
        )
        println(
            """
            Added station '$stationId' to $yamlPath.

            Next steps:
              1. restart the server - the super admin sees '$stationName' immediately after
              2. grant users access via the super admin's "Manage Users" screen
              3. browse to a month inside the migrated date range shown above
            """.trimIndent()
        )
    }
}

private val SCHEMA_NAME = Regex("[a-zA-Z0-9_]{1,64}")

private fun schemaExists(c: Connection, schema: String): Boolean =
    c.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?").use { ps ->
        ps.setString(1, schema)
        ps.executeQuery().use { it.next() }
    }

private fun confirm(opts: Options, question: String): Boolean {
    if (opts.has("yes")) return true
    print("$question (y/N): ")
    return readLine()?.trim()?.lowercase() in setOf("y", "yes")
}

private fun promptPassword(): String {
    val console = System.console()
    return if (console != null) {
        String(console.readPassword("MySQL password: "))
    } else {
        print("MySQL password: ")
        readLine().orEmpty()
    }
}
