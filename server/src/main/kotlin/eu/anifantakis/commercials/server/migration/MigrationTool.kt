package eu.anifantakis.commercials.server.migration

import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Legacy Commercials Manager -> new normalized schema migration CLI.
 *
 * Run from the repo root against the fat jar:
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.migration.MigrationToolKt \
 *        --dump /path/to/commercials3.sql \
 *        --host localhost --port 3306 --user root --password *** \
 *        --schema commercials_mystation --create-schema \
 *        --station-id my-station --station-name "My Station" \
 *        --fortv 1
 *
 * Any missing option is prompted for interactively. Pipeline:
 *
 *   1. connect; create the target schema (or verify an existing EMPTY one)
 *   2. create the normalized tables (StationDb.bootstrap, NO demo seeding -
 *      station_meta.demo_seed=false so empty months stay empty forever)
 *   3. replay the dump's relevant tables into a scratch schema (streaming;
 *      emailhistory & friends are skipped entirely)
 *   4. transform scratch -> target (see LegacyTransformer; missing ERP data
 *      is faked deterministically and flagged synthetic=TRUE)
 *   5. drop the scratch schema (unless --keep-scratch)
 *   6. append the station to stations.yaml (unless --no-yaml)
 *
 * Requires MySQL 8+ on the TARGET server (window functions; MyISAM replay of
 * the 5.7-era dumps works fine there).
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    try {
        runMigration(opts)
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
        val jdbcUrl = "jdbc:mysql://$host:$port/$schema?useUnicode=true&characterEncoding=utf8"
        val stationDb = StationDb(
            StationConfig(id = schema, name = schema, jdbcUrl = jdbcUrl, username = user, password = password),
            maxPoolSize = 3
        )
        try {
            stationDb.bootstrap(seedDemo = false)
        } finally {
            stationDb.close()
        }
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
            customers       ${summary.customers}  (${summary.customers - summary.customersSynthetic} recovered real names, ${summary.customersSynthetic} synthetic)
            contracts       ${summary.contracts}  (${summary.contractsSynthetic} synthetic)
            contract lines  ${summary.contractLines}
            spots           ${summary.spots}
            placements      ${summary.placements}
            flow comments   ${summary.flowComments}
            print audits    ${summary.printAudits}
            date range      ${summary.dateRange}
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

    // ── 7. stations.yaml ────────────────────────────────────────────────
    if (!opts.has("no-yaml")) {
        val yamlPath = opts.optional("yaml") ?: "stations.yaml"
        val stationId = opts.get("station-id", "Station id for stations.yaml (e.g. my-station)")
        val stationName = opts.get("station-name", "Display name for the station dropdown")
        appendStationToYaml(
            file = File(yamlPath),
            id = stationId,
            name = stationName,
            jdbcUrl = "jdbc:mysql://$host:$port/$schema?useUnicode=true&characterEncoding=utf8",
            username = user,
            password = password,
        )
        println(
            """
            Added station '$stationId' to $yamlPath.

            Next steps:
              1. restart the server - the super admin sees '$stationName' immediately
              2. grant users access via the super admin's "Manage Users" screen
              3. browse to a month inside the migrated date range shown above
            """.trimIndent()
        )
    }
}

private val SCHEMA_NAME = Regex("[a-zA-Z0-9_]{1,64}")

/** Same stations.yaml resolution the server's config loading uses. */
internal fun stationsYamlFile(): File {
    val explicit = System.getProperty("stations.config") ?: System.getenv("POC_STATIONS")
    return File(explicit ?: "stations.yaml")
}

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

/**
 * Appends a station entry at the end of the `stations:` block, preserving
 * every comment in the file (which is why this is text surgery, not a YAML
 * round-trip). Refuses duplicate ids.
 */
internal fun appendStationToYaml(
    file: File,
    id: String,
    name: String,
    jdbcUrl: String,
    username: String,
    password: String,
) {
    require(file.isFile) { "stations.yaml not found at ${file.path} (use --yaml <path> or --no-yaml)" }
    val text = file.readText()
    require(!Regex("^\\s*-\\s*id:\\s*$id\\s*$", RegexOption.MULTILINE).containsMatchIn(text)) {
        "Station id '$id' already exists in ${file.path}"
    }

    val entry = buildString {
        append("\n  - id: ").append(id)
        append("\n    name: \"").append(name).append('"')
        append("\n    jdbcUrl: \"").append(jdbcUrl).append('"')
        append("\n    username: ").append(username)
        append("\n    password: ").append(password)
        append('\n')
    }

    val lines = text.lines()
    val stationsIdx = lines.indexOfFirst { it.trimEnd() == "stations:" }
    val newText = if (stationsIdx < 0) {
        // No stations key yet - start the list at the end of the file.
        text.trimEnd('\n') + "\n\nstations:" + entry
    } else {
        // The block ends at the next top-level key or EOF.
        var end = lines.size
        for (i in stationsIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("#")) {
                end = i; break
            }
        }
        (lines.subList(0, end).joinToString("\n").trimEnd('\n') +
            entry +
            if (end < lines.size) "\n" + lines.subList(end, lines.size).joinToString("\n") else "\n")
    }
    file.writeText(newText)
}

/**
 * Removes a station's list entry from stations.yaml, preserving everything
 * else (comments included). Matching starts at the entry's `- id:` line and
 * ends before the next `- ` list item or the end of the stations block.
 * Returns false when no such station exists in the file.
 */
internal fun removeStationFromYaml(file: File, id: String): Boolean {
    if (!file.isFile) return false
    val lines = file.readText().lines()
    val startIdx = lines.indexOfFirst { Regex("^\\s*-\\s*id:\\s*$id\\s*$").matches(it) }
    if (startIdx < 0) return false

    var endIdx = lines.size
    for (i in startIdx + 1 until lines.size) {
        val line = lines[i]
        val isNextItem = line.trimStart().startsWith("- ")
        val isTopLevelKey = line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("#")
        if (isNextItem || isTopLevelKey) {
            endIdx = i; break
        }
    }
    // Swallow the blank separator line before the entry, if any.
    val trimmedStart = if (startIdx > 0 && lines[startIdx - 1].isBlank()) startIdx - 1 else startIdx
    file.writeText((lines.subList(0, trimmedStart) + lines.subList(endIdx, lines.size)).joinToString("\n"))
    return true
}
