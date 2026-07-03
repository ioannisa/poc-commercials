package eu.anifantakis.commercials.server.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.anifantakis.commercials.server.stations.StationConfig
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One station's database: a dedicated connection pool over that station's
 * schema (its own jdbcUrl/credentials from stations.yaml) plus the queries.
 * Instances are created and cached by StationRegistry - NOT a Koin
 * definition, since the set of stations is data, not wiring.
 *
 * SCHEMA (normalized, migration-ready - see migration/legacy-schema.md):
 *
 *   customers ──< contracts ──< contract_lines
 *       │                            │
 *       └────────< spots >───────────┘        (the catalog, ≙ legacy `messages`)
 *                    │
 *                    └──< placements          (the WRITE model, ≙ legacy `schedule`)
 *
 *   break_slots                               (station airtime grid config)
 *   flow_comments, print_audit                (≙ legacy roh_comments / roh_print_history)
 *
 * The grid the client renders (cells + their commercials) is a READ model
 * DERIVED at query time from placements ⋈ spots ⋈ customers ⋈ contracts -
 * aggregates and cell colours are computed, never stored. Every table carries
 * a nullable legacy_id so the future migration from the original app's dumps
 * is idempotent and cross-checkable.
 */
class StationDb(private val station: StationConfig, maxPoolSize: Int) {

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = station.jdbcUrl
            username = station.username
            password = station.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            // Several station pools coexist - ceiling resolved from
            // stations.yaml (per-station override / file default / built-in)
            maximumPoolSize = maxPoolSize
            minimumIdle = 1
            connectionTimeout = 10_000
            // Do not fail-fast at pool construction: connections are
            // validated on first use, so an unreachable MySQL (or a test
            // environment without one) doesn't crash instance creation
            initializationFailTimeout = -1
            poolName = "station-${station.id}"
        }
    )

    /** A pooled connection; closing it (e.g. via `.use {}`) returns it to the pool. */
    fun connection(): Connection = dataSource.getConnection()

    fun close() {
        dataSource.close()
    }

    // ────────────────────────────────────────────────────────── bootstrap ──

    /**
     * Creates the schema. [seedDemo] false is for MIGRATED stations: the
     * migration tool builds breaks/catalog from the legacy dump instead of
     * demo data, and empty months must STAY empty. The choice is recorded in
     * station_meta (`demo_seed`) with INSERT IGNORE, so a later server
     * bootstrap with the default `true` can never flip a migrated station
     * back to demo seeding.
     */
    fun bootstrap(seedDemo: Boolean = true) {
        connection().use { c ->
            dropLegacyDemoTables(c)
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS break_slots (
                        id BIGINT PRIMARY KEY,
                        hour_of_day TINYINT NOT NULL,
                        minute_of_hour TINYINT NOT NULL,
                        label VARCHAR(8) NOT NULL,
                        zone VARCHAR(16) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS customers (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        code VARCHAR(32) NOT NULL UNIQUE,
                        name VARCHAR(128) NOT NULL,
                        vat_number VARCHAR(16) NULL,
                        contact_person VARCHAR(64) NULL,
                        phone VARCHAR(32) NULL,
                        fax VARCHAR(32) NULL,
                        email VARCHAR(128) NULL,
                        notes TEXT NULL,
                        synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                        KEY idx_customers_legacy (legacy_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS contracts (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_docid BIGINT NULL,
                        number VARCHAR(64) NOT NULL,
                        doc_type INT NULL,
                        is_gift BOOLEAN NOT NULL DEFAULT FALSE,
                        customer_id BIGINT NOT NULL,
                        agency_id BIGINT NULL,
                        entry_date DATE NULL,
                        synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                        KEY idx_contracts_number (number),
                        KEY idx_contracts_legacy (legacy_docid),
                        CONSTRAINT fk_contracts_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                        CONSTRAINT fk_contracts_agency FOREIGN KEY (agency_id) REFERENCES customers(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS contract_lines (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        contract_id BIGINT NOT NULL,
                        line_no INT NOT NULL,
                        desired_qty INT NOT NULL DEFAULT 0,
                        agel_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                        eidikos_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                        zone_val DECIMAL(10,2) NOT NULL DEFAULT 0,
                        UNIQUE KEY uq_contract_line (contract_id, line_no),
                        CONSTRAINT fk_lines_contract FOREIGN KEY (contract_id)
                            REFERENCES contracts(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS spots (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        customer_id BIGINT NOT NULL,
                        contract_line_id BIGINT NULL,
                        description VARCHAR(255) NOT NULL,
                        duration_seconds INT NOT NULL,
                        spot_type VARCHAR(64) NOT NULL DEFAULT '',
                        flow VARCHAR(32) NOT NULL DEFAULT '',
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        force_position INT NULL,
                        memo TEXT NULL,
                        KEY idx_spots_legacy (legacy_id),
                        CONSTRAINT fk_spots_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                        CONSTRAINT fk_spots_line FOREIGN KEY (contract_line_id) REFERENCES contract_lines(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS programs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        name VARCHAR(128) NOT NULL,
                        color_argb INT NULL,
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        KEY idx_programs_legacy (legacy_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS placements (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        spot_id BIGINT NOT NULL,
                        break_id BIGINT NOT NULL,
                        show_date DATE NOT NULL,
                        position INT NOT NULL,
                        duration_seconds INT NOT NULL,
                        program_id BIGINT NULL,
                        played BOOLEAN NOT NULL DEFAULT FALSE,
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        UNIQUE KEY uq_placement_slot (break_id, show_date, position),
                        KEY idx_placements_legacy (legacy_id),
                        CONSTRAINT fk_placements_spot FOREIGN KEY (spot_id) REFERENCES spots(id),
                        CONSTRAINT fk_placements_break FOREIGN KEY (break_id) REFERENCES break_slots(id),
                        CONSTRAINT fk_placements_program FOREIGN KEY (program_id) REFERENCES programs(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS flow_comments (
                        show_date DATE PRIMARY KEY,
                        comments TEXT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS print_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printed_date DATE NOT NULL,
                        username VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        KEY idx_print_audit_date (printed_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS station_meta (
                        meta_key VARCHAR(64) PRIMARY KEY,
                        meta_value VARCHAR(255) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            // show_date is not the leftmost column of the placement unique
            // key, so month-range scans need their own index.
            ensureIndex(c, "placements", "idx_placements_date", "show_date")
            // Schemas created before the migration tool lack these columns.
            ensureColumn(c, "customers", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
            ensureColumn(c, "contracts", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
            // Schemas created before programme colours (the ALTER path skips
            // the FK; fresh schemas get it via CREATE TABLE above).
            ensureColumn(c, "placements", "program_id", "BIGINT NULL")

            // INSERT IGNORE: first writer wins - a migrated station stays
            // demo_seed=false forever, whoever bootstraps it afterwards.
            c.prepareStatement(
                "INSERT IGNORE INTO station_meta(meta_key, meta_value) VALUES('demo_seed', ?)"
            ).use { ps ->
                ps.setString(1, if (seedDemo) "true" else "false")
                ps.executeUpdate()
            }

            if (seedDemo && isDemoSeedEnabled(c)) {
                seedBreaksIfEmpty(c)
                seedCatalogIfEmpty(c)
            }
        }
    }

    private fun isDemoSeedEnabled(c: Connection): Boolean =
        c.prepareStatement("SELECT meta_value FROM station_meta WHERE meta_key = 'demo_seed'").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) == "true" else true }
        }

    private fun ensureColumn(c: Connection, table: String, column: String, definition: String) {
        val exists = c.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            c.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
        }
    }

    /**
     * Migration from the pre-normalization demo schema: the old
     * `scheduler_cells` (stored aggregates) and `commercials` (denormalized
     * rows) tables are superseded by the derived read model. Their content
     * was deterministic demo data, so they are simply dropped; months reseed
     * on demand into `placements`. Idempotent.
     */
    private fun dropLegacyDemoTables(c: Connection) {
        for (table in listOf("commercials", "scheduler_cells")) {
            val exists = c.prepareStatement(
                """
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { it.next() }
            }
            if (exists) {
                c.createStatement().use { it.executeUpdate("DROP TABLE $table") }
            }
        }
    }

    private fun ensureIndex(c: Connection, table: String, indexName: String, columns: String) {
        val exists = c.prepareStatement(
            """
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, indexName)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            c.createStatement().use { it.executeUpdate("CREATE INDEX $indexName ON $table($columns)") }
        }
    }

    private fun seedBreaksIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM break_slots").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
        if (count > 0) return
        c.autoCommit = false
        try {
            c.prepareStatement(
                "INSERT INTO break_slots(id, hour_of_day, minute_of_hour, label, zone) VALUES(?,?,?,?,?)"
            ).use { ps ->
                for (b in generateBreaks()) {
                    ps.setLong(1, b.id)
                    ps.setInt(2, b.hour)
                    ps.setInt(3, b.minute)
                    ps.setString(4, b.label)
                    ps.setString(5, b.zone.name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    /**
     * Demo catalog: customers (with ΑΦΜ), one gift contract + line each, and
     * a deterministic per-station spot catalog. Runs once per schema; real
     * deployments will fill these tables via the legacy migration instead.
     */
    private fun seedCatalogIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM spots").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        val random = kotlin.random.Random(station.id.hashCode())
        c.autoCommit = false
        try {
            val customerIds = mutableListOf<Long>()
            val lineIdByCustomer = mutableMapOf<Long, Long>()

            c.prepareStatement(
                "INSERT INTO customers(code, name, vat_number) VALUES(?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                for (cust in demoCustomers) {
                    ps.setString(1, cust.code)
                    ps.setString(2, cust.name)
                    ps.setString(3, cust.vat)
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs -> rs.next(); customerIds += rs.getLong(1) }
                }
            }

            c.prepareStatement(
                "INSERT INTO contracts(number, is_gift, customer_id, entry_date) VALUES(?,TRUE,?,CURDATE())",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                customerIds.forEachIndexed { index, customerId ->
                    ps.setString(1, "DEMO-${1000 + index}")
                    ps.setLong(2, customerId)
                    ps.executeUpdate()
                    val contractId = ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                    c.prepareStatement(
                        "INSERT INTO contract_lines(contract_id, line_no, desired_qty) VALUES(?,1,0)",
                        Statement.RETURN_GENERATED_KEYS
                    ).use { lps ->
                        lps.setLong(1, contractId)
                        lps.executeUpdate()
                        lps.generatedKeys.use { rs -> rs.next(); lineIdByCustomer[customerId] = rs.getLong(1) }
                    }
                }
            }

            c.prepareStatement(
                """
                INSERT INTO spots(customer_id, contract_line_id, description, duration_seconds, spot_type, flow)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                repeat(DEMO_SPOTS_PER_STATION) {
                    val customerId = customerIds[random.nextInt(customerIds.size)]
                    ps.setLong(1, customerId)
                    ps.setLong(2, lineIdByCustomer.getValue(customerId))
                    ps.setString(3, demoMessages[random.nextInt(demoMessages.size)])
                    ps.setInt(4, demoDurations[random.nextInt(demoDurations.size)])
                    ps.setString(5, demoSpotTypes[random.nextInt(demoSpotTypes.size)])
                    ps.setString(6, "ΡΟΗ")
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            c.commit()
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    // ─────────────────────────────────────────────────────────── queries ──

    data class PlacementStats(val placements: Long, val minDate: String?, val maxDate: String?)

    /** Quick footprint of this station's data (Databases admin screen). */
    fun placementStats(): PlacementStats =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*), MIN(show_date), MAX(show_date) FROM placements").use { rs ->
                    rs.next()
                    PlacementStats(rs.getLong(1), rs.getString(2), rs.getString(3))
                }
            }
        }

    fun loadBreaks(): List<BreakSlotRow> =
        connection().use { c ->
            c.prepareStatement(
                "SELECT id, hour_of_day, minute_of_hour, label, zone FROM break_slots ORDER BY id"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<BreakSlotRow>()
                    while (rs.next()) {
                        out += BreakSlotRow(
                            id = rs.getLong(1),
                            hour = rs.getInt(2),
                            minute = rs.getInt(3),
                            label = rs.getString(4),
                            zone = BreakZone.valueOf(rs.getString(5))
                        )
                    }
                    out
                }
            }
        }

    /** [start, end) - first day of the month to first day of the next month. */
    private fun monthRange(year: Int, month: Int): Pair<LocalDate, LocalDate> {
        val start = LocalDate.of(year, month, 1)
        return start to start.plusMonths(1)
    }

    private fun monthHasPlacements(c: Connection, year: Int, month: Int): Boolean {
        val (start, end) = monthRange(year, month)
        return c.prepareStatement(
            "SELECT 1 FROM placements WHERE show_date >= ? AND show_date < ? LIMIT 1"
        ).use { ps ->
            ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
            ps.executeQuery().use { it.next() }
        }
    }

    /**
     * The month's grid, DERIVED from the normalized write model: one joined
     * query over placements ⋈ spots ⋈ customers ⋈ contracts, then per-cell
     * aggregates (spot count, total duration) and colours computed here.
     * Returns the same shapes the API always served - the client is unaware
     * of the schema evolution.
     */
    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> {
        val (start, end) = monthRange(year, month)
        val zoneByBreak = loadBreaks().associate { it.id to it.zone }

        val commercialsByKey = linkedMapOf<Pair<Long, LocalDate>, MutableList<CommercialRow>>()
        // Programme colour per cell (legacy `programtypes.color` semantics):
        // the break belongs to the programme airing at that slot, so the
        // first placement's programme colour is the cell's colour.
        val programColorByKey = mutableMapOf<Pair<Long, LocalDate>, Int>()

        connection().use { c ->
            c.prepareStatement(
                """
                SELECT p.id, p.break_id, p.show_date, p.position, p.duration_seconds,
                       s.description, s.spot_type, s.flow,
                       cu.code AS client_code, cu.name AS client_name,
                       ct.number AS contract_number, ct.is_gift,
                       pr.color_argb AS program_color
                FROM placements p
                JOIN spots s      ON s.id = p.spot_id
                JOIN customers cu ON cu.id = s.customer_id
                LEFT JOIN contract_lines cl ON cl.id = s.contract_line_id
                LEFT JOIN contracts ct      ON ct.id = cl.contract_id
                LEFT JOIN programs pr       ON pr.id = p.program_id
                WHERE p.show_date >= ? AND p.show_date < ?
                  AND p.hidden = FALSE AND s.hidden = FALSE
                ORDER BY p.break_id, p.show_date, p.position
                """.trimIndent()
            ).use { ps ->
                ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val breakId = rs.getLong("break_id")
                        val date = rs.getDate("show_date").toLocalDate()
                        val isGift = rs.getBoolean("is_gift")
                        val programColor = rs.getInt("program_color").takeIf { !rs.wasNull() }
                        if (programColor != null) {
                            programColorByKey.putIfAbsent(breakId to date, programColor)
                        }
                        val list = commercialsByKey.getOrPut(breakId to date) { mutableListOf() }
                        list += CommercialRow(
                            id = rs.getLong("id"),
                            position = rs.getInt("position"),
                            clientCode = rs.getString("client_code"),
                            clientName = rs.getString("client_name"),
                            message = rs.getString("description"),
                            durationSeconds = rs.getInt("duration_seconds"),
                            type = rs.getString("spot_type"),
                            contract = if (isGift) "ΔΩΡΑ" else (rs.getString("contract_number") ?: ""),
                            flow = rs.getString("flow")
                        )
                    }
                }
            }
        }

        val cells = commercialsByKey.map { (key, commercials) ->
            val (breakId, date) = key
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            CellRow(
                breakId = breakId,
                date = date,
                spotCount = commercials.size,
                totalDurationSeconds = commercials.sumOf { it.durationSeconds },
                // The PROGRAMME's colour wins (operator-assigned identity,
                // migrated from the legacy app); the zone/weekend/density
                // rules are the demo fallback for placements without one.
                zoneColorArgb = programColorByKey[key] ?: cellColorArgb(
                    zone = zoneByBreak[breakId] ?: BreakZone.DEFAULT,
                    isWeekend = isWeekend,
                    spotCount = commercials.size
                ),
                commercials = emptyList()
            )
        }

        return cells to commercialsByKey
    }

    /**
     * Loads the month; seeds demo placements if the schema has none for that
     * month.
     *
     * Concurrency: two requests for the same unseeded month can both pass the
     * `monthHasPlacements` check before either commits (check-then-act race -
     * each request uses its own connection, so there's no in-process
     * serialization point). Since generation is deterministic (RNG seeded
     * from station + year/month over a deterministic catalog), both requests
     * would generate identical rows; if a concurrent insert already committed
     * first, this request's INSERT hits the (break_id, show_date, position)
     * unique key and we treat that as "already seeded by someone else". This
     * is also correct across multiple server instances.
     */
    fun ensureMonthSeeded(year: Int, month: Int) {
        connection().use { c ->
            // Migrated stations hold REAL data: months without placements are
            // genuinely empty and must never be filled with demo spots.
            if (!isDemoSeedEnabled(c)) return
            if (monthHasPlacements(c, year, month)) return

            val breaks = loadBreaks()
            val spots = c.prepareStatement("SELECT id, duration_seconds FROM spots ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(SpotRef(rs.getLong(1), rs.getInt(2)))
                    }
                }
            }
            val generated = generateMonthPlacements(
                breaks, spots, year, month, stationSeed = station.id.hashCode()
            )
            if (generated.isEmpty()) return

            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO placements(spot_id, break_id, show_date, position, duration_seconds)
                    VALUES(?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    for (p in generated) {
                        ps.setLong(1, p.spotId)
                        ps.setLong(2, p.breakId)
                        ps.setDate(3, java.sql.Date.valueOf(p.date))
                        ps.setInt(4, p.position)
                        ps.setInt(5, p.durationSeconds)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: SQLException) {
                c.rollback()
                // Another request already seeded this exact month concurrently
                // (generation is deterministic, so the rows collide) - nothing
                // to do. Anything else is a real failure.
                if (!isDuplicateKeyViolation(e)) throw e
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    /**
     * True if [e] (or any exception chained via `getNextException`, which is
     * how JDBC batch failures report the real per-row cause) is a duplicate
     * primary key violation. Checked by SQLState/vendor code rather than a
     * concrete exception type, since JDBC drivers vary in whether a failed
     * batch surfaces as `BatchUpdateException`, `SQLIntegrityConstraintViolationException`,
     * or a plain `SQLException` wrapping the real one.
     */
    private fun isDuplicateKeyViolation(e: SQLException): Boolean {
        var current: SQLException? = e
        while (current != null) {
            if (current.errorCode == 1062 || current.sqlState?.startsWith("23") == true) return true
            current = current.nextException
        }
        return false
    }
}
