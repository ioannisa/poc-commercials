package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.stations.StationConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Command-line front for the legacy migration - same pipeline the in-app
 * Migration screen drives, for scripted/offline use. The host application
 * provides only its entry-point `main` (see the server's MigrationToolKt,
 * which keeps the historical class name so the `java -cp server.jar ...`
 * command is unchanged). Unlike the in-app service there is no running
 * StationRegistry here, so migrated stations are hosted at the next restart.
 *
 * ONE DUMP ⇒ ONE GROUP ⇒ ITS STATIONS. A legacy database is one company's, and
 * its `forTV` flag separates that company's TV station from its radio station -
 * which SHARE the customers and the contracts. So the dump lands in one group
 * schema and every flow becomes a station inside it, in a single run.
 *
 * Any missing option is prompted for interactively. Pipeline:
 *
 *   1. connect; create the target GROUP schema (or verify an existing EMPTY one)
 *   2. create the normalized tables (single-sourced DDL from :persistence,
 *      NO demo seeding)
 *   3. replay the dump's relevant tables into a scratch schema (streaming;
 *      includes the email archive - irrelevant tables are skipped)
 *   4. map each flow to a station (`--stations "1=crete-tv=Crete TV,0=radio-984=Radio 984"`,
 *      or answer the prompts; a flow left blank is skipped)
 *   5. transform scratch -> target (see LegacyTransformer; missing ERP data
 *      is faked deterministically and flagged synthetic=TRUE)
 *   6. if --sen-dir points at a folder of SEN (Oracle ERP) table exports,
 *      enrich the target with the real master data (see SenErpEnricher) -
 *      real customer names/VAT/contacts, real contract periods, gift flags.
 *      It runs ONCE per group: everything it fills is group-scoped
 *   7. drop the scratch schema (unless --keep-scratch)
 *   8. append the GROUP (with its stations) to server.yaml (unless --no-yaml)
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
    val schema = opts.get("schema", "Target GROUP schema name (e.g. commercials_crete_group)")
    val groupId = opts.get("group-id", "Group id for server.yaml (e.g. crete-group)")
    val groupName = opts.get("group-name", "Group display name", groupId)

    require(SCHEMA_NAME.matches(schema)) { "Schema name must match ${SCHEMA_NAME.pattern}" }
    require(STATION_ID.matches(groupId)) { "Group id must match ${STATION_ID.pattern}" }

    val serverUrl = "jdbc:mysql://$host:$port/?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&allowPublicKeyRetrieval=true&useSSL=false"
    val targetJdbcUrl = "jdbc:mysql://$host:$port/$schema?useUnicode=true&characterEncoding=utf8"

    println()
    println("═══ Commercials Manager - Legacy Migration ═══")
    println("Dump:   $dumpPath (${dumpFile.length() / 1_048_576} MB)")
    println("Target: $host:$port / $schema  (group '$groupId')")
    println("One dump -> one GROUP database -> its stations. Customers and contracts")
    println("are imported ONCE and shared by every station of the group.")
    println()

    // Hoisted out of the connection block: the yaml step (7) needs them.
    var stationsForYaml: List<Pair<String, String>> = emptyList()

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
        prepareGroupSchema(groupId, targetJdbcUrl, user, password)
        // CUSTOMERS, not placements: they are GROUP-scoped and imported
        // unconditionally, so a second dump into a populated group would
        // duplicate every one of them.
        val customerCount = c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$schema`.customers").use { rs -> rs.next(); rs.getLong(1) }
        }
        require(customerCount == 0L) {
            "Target group schema '$schema' already holds $customerCount customers - refusing to migrate into " +
                "non-empty data (both flows of one dump go in together; there is no second run to add). Use a fresh schema."
        }
        println("Normalized group tables ready (demo seeding disabled).")

        // ── 3. scratch replay ───────────────────────────────────────────
        val scratch = "${schema}_scratch"
        c.createStatement().use {
            it.executeUpdate("DROP DATABASE IF EXISTS `$scratch`")
            it.executeUpdate("CREATE DATABASE `$scratch` DEFAULT CHARACTER SET utf8mb4")
        }
        println("\nReplaying dump into scratch schema '$scratch' (irrelevant tables skipped)...")
        val replayed = DumpReplayer(c, scratch, log = { println(it) }).replay(dumpFile)
        replayed.forEach { (table, rows) -> println("  %-28s %,d rows".format(table, rows)) }

        // ── 4. map each flow to a station of the group ──────────────────
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
        println("\nFlows in this dump (a legacy DB serves a TV and a radio flow of the SAME company):")
        flowCounts.forEach { (tv, counts) ->
            println("  forTV=$tv (${if (tv == 1) "TV" else "radio"}): ${counts.first} spots, ${counts.second} placements")
        }
        val stations = resolveStations(opts, flowCounts.keys)
        require(stations.isNotEmpty()) { "No flow was mapped to a station - nothing to migrate" }
        stationsForYaml = stations.values.toList()

        // ── 5. transform BOTH flows in one pass ─────────────────────────
        println()
        stations.forEach { (forTv, st) -> println("Mapping forTV=$forTv (${if (forTv == 1) "TV" else "radio"}) -> station '${st.first}' (${st.second})") }
        prepareGroupSchema(
            groupId, targetJdbcUrl, user, password,
            stations.map { (_, st) -> StationConfig(id = st.first, name = st.second) }
        )
        println("Transforming ...")
        val summary = LegacyTransformer(
            c, scratch, schema, stations.mapValues { (_, st) -> st.first },
            log = { println("  $it") },
        ).run()

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
            coverage        migrated ${summary.placements} of ${summary.dumpScheduleRows} dump rows (unmappedFlow=${summary.otherFlowRows}, orphaned=${summary.orphanedRows}, invalidDate=${summary.zeroDateRows})
            ─────────────────────────────────────────────────────
            ${summary.stations.joinToString("\n            ") { "station ${it.stationId} (forTV=${it.forTv}): ${it.spots} spots, ${it.placements} placements" }}

            Customers, contracts and contract lines above are GROUP-wide: stored once
            and shared by every station listed, which is how one contract can hold a
            TV line and radio lines at the same time.

            Synthetic rows are flagged (customers.synthetic / contracts.synthetic)
            so a future ERP import can find and replace them.
            """.trimIndent()
        )

        // ── 5b. SEN (Oracle ERP) enrichment ─────────────────────────────
        val senDirPath = opts.optional("sen-dir")
        if (senDirPath != null) {
            val senDir = File(senDirPath)
            require(senDir.isDirectory) { "--sen-dir is not a directory: $senDirPath" }
            println("\nEnriching from the SEN exports in $senDirPath ...")
            val senSummary = SenErpEnricher(c, schema, log = { println("  $it") })
                .enrich(senDir, apply = true, legacyScratchSchema = scratch)
            printSenSummary(senSummary, apply = true)
        } else {
            println("\n(no --sen-dir given - the ERP enrichment can run later via SenEnrichToolKt)")
        }

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
        appendGroupToYaml(
            file = File(yamlPath),
            id = groupId,
            name = groupName,
            jdbcUrl = targetJdbcUrl,
            username = user,
            password = password,
            stations = stationsForYaml.map { Triple(it.first, it.second, null) },
        )
        println(
            """
            Added group '$groupId' with station(s) ${stationsForYaml.joinToString { it.first }} to $yamlPath.

            Next steps:
              1. restart the server - the super admin sees the station(s) immediately after
              2. grant users access via the super admin's "Manage Users" screen
              3. browse to a month inside the migrated date range shown above
            """.trimIndent()
        )
    }
}

/**
 * The (forTV -> station) map, from `--stations "1=crete-tv=Crete TV,0=radio-984=Radio 984"`
 * or asked per detected flow. A flow left blank is skipped (not migrated).
 */
private fun resolveStations(opts: Options, flows: Set<Int>): Map<Int, Pair<String, String>> {
    opts.optional("stations")?.let { spec ->
        val out = linkedMapOf<Int, Pair<String, String>>()
        for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bits = part.split('=', limit = 3)
            require(bits.size == 3) { "--stations entries look like '1=crete-tv=Crete TV' (got '$part')" }
            val forTv = bits[0].trim().toInt()
            require(forTv in flows) { "No messages with forTV=$forTv in this dump" }
            val id = bits[1].trim()
            require(STATION_ID.matches(id)) { "Station id must match ${STATION_ID.pattern} (got '$id')" }
            out[forTv] = id to bits[2].trim()
        }
        return out
    }
    val out = linkedMapOf<Int, Pair<String, String>>()
    for (forTv in flows.sortedDescending()) {
        val kind = if (forTv == 1) "TV" else "radio"
        print("Station id for the $kind flow (forTV=$forTv), blank to skip it: ")
        val id = readLine()?.trim().orEmpty()
        if (id.isEmpty()) continue
        require(STATION_ID.matches(id)) { "Station id must match ${STATION_ID.pattern}" }
        print("  display name for '$id': ")
        val name = readLine()?.trim().orEmpty().ifEmpty { id }
        out[forTv] = id to name
    }
    return out
}

private val SCHEMA_NAME = Regex("[a-zA-Z0-9_]{1,64}")
private val STATION_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")

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
